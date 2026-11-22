package com.shiyin.app.kid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.shiyin.app.compat.Utf8;
import com.shiyin.app.model.Book;
import com.shiyin.app.model.Cat;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 青少年模式：共享内核，手机版与 TV 版都直接调用这一份。
 *
 * <p>两条独立职责，别混在一起看：
 * <ul>
 *   <li><b>开关与密码</b>：PBKDF2 加盐存储、常量时间比较、连错锁定。</li>
 *   <li><b>内容边界</b>：{@link #filterCategories} 是主防线（白名单分类树），
 *       {@link #allowsBook} 只是搜索/书架等旁路的兜底。</li>
 * </ul>
 *
 * <p>API 16 兼容：只用 {@code getBytes("UTF-8")}（{@code StandardCharsets} 是 API19），
 * 加密用 {@code PBKDF2WithHmacSHA1}（{@code PBKDF2WithHmacSHA256} 在 4.1 上没有）。
 */
public final class KidMode {

    /* ---------------- 存储 ---------------- */

    /** 独立文件，绝不能塞进 shiyin 主文件里跟书架/进度混在一起。 */
    private static final String FILE = "shiyin_kid";

    private static final String K_SALT = "kid.salt";
    private static final String K_HASH = "kid.hash";
    private static final String K_ITER = "kid.iter";
    private static final String K_ALG = "kid.alg";
    private static final String K_ENABLED = "kid.enabled";
    private static final String K_FAIL_AT = "kid.failAt";
    private static final String K_FAIL_COUNT = "kid.failCount";

    /* ---------------- 密码参数 ---------------- */

    /** 首选算法；API10+ 才有，Android 4.1 上有。 */
    private static final String ALG_PBKDF2_SHA1 = "pbkdf2-sha1";
    /** 极端机型上 PBKDF2 不可用时才用；仍然是加盐 + 两万次迭代，不是裸哈希。 */
    private static final String ALG_SHA256_ITER = "sha256-iter";

    private static final String JCE_PBKDF2 = "PBKDF2WithHmacSHA1";
    private static final String JCE_SHA256 = "SHA-256";

    private static final int ITERATIONS = 20000;   // 契约要求 >= 10000
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;       // 32 字节

    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 16;

    private static final int MAX_FAILS = 5;
    private static final long LOCK_MILLIS = 30L * 1000L;

    private static final int MAX_DEPTH = 8;

    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();

    private static volatile SharedPreferences sp;

    private KidMode() {
    }

    /** 初始化存储；App/TvApp 启动时调用一次，可重复调用。 */
    public static void init(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        synchronized (LOCK) {
            sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    /* ---------------- 状态 ---------------- */

    public static boolean enabled() {
        SharedPreferences p = sp;
        if (p == null) return false;
        // 密码被清掉/损坏时按关闭处理，宁可少拦也不能把人锁在外面。
        return p.getBoolean(K_ENABLED, false) && hasPassword();
    }

    /** 是否已经设置过密码（决定 UI 显示「设置密码」还是「输入密码」）。 */
    public static boolean hasPassword() {
        SharedPreferences p = sp;
        if (p == null) return false;
        return p.getString(K_SALT, "").length() > 0 && p.getString(K_HASH, "").length() > 0;
    }

    /** 锁定剩余秒数；0 表示没锁。UI 用它显示「请等 N 秒再试」。 */
    public static int lockRemainingSeconds() {
        SharedPreferences p = sp;
        if (p == null) return 0;
        long at = p.getLong(K_FAIL_AT, 0L);
        if (at <= 0) return 0;
        long left = LOCK_MILLIS - (System.currentTimeMillis() - at);
        if (left <= 0) return 0;
        return (int) ((left + 999L) / 1000L);
    }

    /* ---------------- 密码 ---------------- */

    /**
     * 首次设置密码。长度 4-16、非空白。
     *
     * <p>已经有密码时返回 false —— 否则谁都能不验证就重置密码，
     * 等于青少年模式能被小孩一键解除。改密码走 {@link #changePassword}。
     */
    public static boolean setPassword(String password) {
        SharedPreferences p = sp;
        if (p == null) return false;
        if (!validPassword(password)) return false;
        if (hasPassword()) return false;
        return store(p, password);
    }

    /** 校验密码是否正确；连续失败 5 次后 30 秒内一律 false。 */
    public static boolean verify(String password) {
        SharedPreferences p = sp;
        if (p == null) return false;
        if (!hasPassword()) return false;
        if (lockRemainingSeconds() > 0) return false;
        if (matches(p, password)) {
            p.edit().putInt(K_FAIL_COUNT, 0).remove(K_FAIL_AT).commit();
            return true;
        }
        recordFailure(p);
        return false;
    }

    /** 校验后开启青少年模式；必须 verify 通过。 */
    public static boolean enableWith(String password) {
        SharedPreferences p = sp;
        if (p == null) return false;
        if (!hasPassword()) return false;
        if (!verify(password)) return false;
        boolean before = enabled();
        p.edit().putBoolean(K_ENABLED, true).commit();
        if (!before) notifyListeners(true);
        return true;
    }

    /** 关闭青少年模式；必须 verify 通过。 */
    public static boolean disableWith(String password) {
        SharedPreferences p = sp;
        if (p == null) return false;
        if (!hasPassword()) return false;
        if (!verify(password)) return false;
        boolean before = enabled();
        p.edit().putBoolean(K_ENABLED, false).commit();
        if (before) notifyListeners(false);
        return true;
    }

    /** 修改密码；旧密码必须正确。 */
    public static boolean changePassword(String oldPassword, String newPassword) {
        SharedPreferences p = sp;
        if (p == null) return false;
        if (!validPassword(newPassword)) return false;
        if (!verify(oldPassword)) return false;
        return store(p, newPassword);
    }

    private static boolean validPassword(String password) {
        if (password == null) return false;
        int length = password.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) return false;
        return password.trim().length() > 0;
    }

    /** 写 salt/hash/iter/alg，并清掉失败计数。 */
    private static boolean store(SharedPreferences p, String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        String alg = ALG_PBKDF2_SHA1;
        byte[] hash;
        try {
            hash = pbkdf2(password, salt, ITERATIONS);
        } catch (Throwable unavailable) {
            // 极个别 ROM 没有 PBKDF2：降级成加盐 SHA-256 迭代，并记下算法版本，
            // 绝不能悄悄变成「单次不加盐哈希」。
            alg = ALG_SHA256_ITER;
            try {
                hash = sha256Iterated(password, salt, ITERATIONS);
            } catch (Throwable fatal) {
                return false;
            }
        }
        if (hash == null || hash.length == 0) return false;
        return p.edit()
                .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(K_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt(K_ITER, ITERATIONS)
                .putString(K_ALG, alg)
                .putInt(K_FAIL_COUNT, 0)
                .remove(K_FAIL_AT)
                .commit();
    }

    private static boolean matches(SharedPreferences p, String password) {
        String pw = password == null ? "" : password;
        try {
            byte[] salt = Base64.decode(p.getString(K_SALT, ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(p.getString(K_HASH, ""), Base64.NO_WRAP);
            if (salt.length == 0 || expected.length == 0) return false;
            int iterations = p.getInt(K_ITER, ITERATIONS);
            if (iterations < 1) return false;
            String alg = p.getString(K_ALG, ALG_PBKDF2_SHA1);
            byte[] actual;
            if (ALG_SHA256_ITER.equals(alg)) {
                actual = sha256Iterated(pw, salt, iterations);
            } else {
                try {
                    actual = pbkdf2(pw, salt, iterations);
                } catch (Throwable unavailable) {
                    // 这条记录是用 PBKDF2 存的，现在算不出来就别放行。
                    return false;
                }
            }
            // 常量时间比较，别用 Arrays.equals 泄露前缀匹配时间。
            return MessageDigest.isEqual(expected, actual);
        } catch (Throwable broken) {
            return false;
        }
    }

    private static void recordFailure(SharedPreferences p) {
        int count = p.getInt(K_FAIL_COUNT, 0) + 1;
        if (count >= MAX_FAILS) {
            p.edit().putLong(K_FAIL_AT, System.currentTimeMillis())
                    .putInt(K_FAIL_COUNT, 0).commit();
        } else {
            p.edit().putInt(K_FAIL_COUNT, count).commit();
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(JCE_PBKDF2);
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    /** 降级路径：SHA-256(salt||password) 再迭代 SHA-256，仍是一万次以上的加盐派生。 */
    private static byte[] sha256Iterated(String password, byte[] salt, int iterations)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance(JCE_SHA256);
        digest.update(salt);
        byte[] block = digest.digest(Utf8.bytes(password));
        for (int i = 1; i < iterations; i++) {
            digest.reset();
            block = digest.digest(block);
        }
        return block;
    }

    /* ---------------- 监听 ---------------- */

    /** 状态变化的监听，供 UI 立即刷新；不需要持久化。 */
    public interface Listener {
        void onKidModeChanged(boolean enabled);
    }

    public static void addListener(Listener l) {
        if (l == null) return;
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        if (l == null) return;
        LISTENERS.remove(l);
    }

    private static void notifyListeners(boolean on) {
        for (Listener listener : LISTENERS) {
            try {
                listener.onKidModeChanged(on);
            } catch (RuntimeException ignored) {
                // 某个 UI 监听器抛异常不能影响开关本身。
            }
        }
    }

    /* ---------------- 内容白名单 ---------------- */

    /**
     * 整棵子树放行。按真实订阅数据：lrts 的根分类 6 就是「儿童」频道。
     * 不枚举子类 id —— 子类会变，根不会。
     */
    private static final Map<String, Set<String>> ALLOWED_ROOTS;

    /**
     * 白名单子分类。两种用途：
     * <ul>
     *   <li>父分类不在白名单（tingyou 的「有声小说」）时，把子类提升为根分类显示。</li>
     *   <li>根分类意外缺失时的兜底（lrts 那几个儿童频道子类）。</li>
     * </ul>
     */
    private static final Map<String, Set<String>> ALLOWED_LEAVES;

    static {
        Map<String, Set<String>> roots = new HashMap<String, Set<String>>();
        Map<String, Set<String>> leaves = new HashMap<String, Set<String>>();

        roots.put("lrts", set("6"));
        // 「儿童」下这些子类都属儿童频道：63 益智故事、3027 儿童文学、9031 国学启蒙、
        // 9245 少儿名著、59 儿歌童谣、64 少儿科普、65 家教育儿、9029 卡通动画。
        leaves.put("lrts", set("63", "3027", "9031", "9245", "59", "64", "65", "9029"));

        // tingyou 是 children 级白名单：父分类 cat:category=2 不留，直接提升子类。
        // 只放童话寓言(20)：「亲子教育」(43) 实测混着「松弛感养育」这类家长向内容，
        // 给孩子的模式里不该出现，已剔除。
        leaves.put("tingyou", set("cat:type=20"));

        // 没有登记的源（kuwo 等）青少年模式下分类为空、书目不可见。
        ALLOWED_ROOTS = Collections.unmodifiableMap(roots);
        ALLOWED_LEAVES = Collections.unmodifiableMap(leaves);
    }

    private static Set<String> set(String... values) {
        Set<String> out = new HashSet<String>();
        for (int i = 0; i < values.length; i++) out.add(values[i]);
        return Collections.unmodifiableSet(out);
    }

    private static boolean isKidSource(String sourceId) {
        return ALLOWED_ROOTS.containsKey(sourceId) || ALLOWED_LEAVES.containsKey(sourceId);
    }

    /**
     * 分类过滤：输入某源的全部分类树，返回青少年模式应该显示的分类树。
     *
     * <p>青少年模式没开时原样深拷贝返回 —— 这样 UI 可以无脑调用，不怕把正常模式过滤空。
     * 永远不改传入的对象。
     */
    public static List<Cat> filterCategories(String sourceId, List<Cat> cats) {
        List<Cat> out = new ArrayList<Cat>();
        if (cats == null || cats.isEmpty()) return out;
        if (!enabled()) {
            for (int i = 0; i < cats.size(); i++) {
                Cat cat = cats.get(i);
                if (cat != null) out.add(copyTree(cat, 0));
            }
            return out;
        }
        String source = sourceId == null ? "" : sourceId;
        Set<String> roots = ALLOWED_ROOTS.get(source);
        Set<String> leaves = ALLOWED_LEAVES.get(source);
        if (roots == null && leaves == null) return out;
        for (int i = 0; i < cats.size(); i++) {
            collect(cats.get(i), roots, leaves, out, 0);
        }
        return out;
    }

    private static void collect(Cat cat, Set<String> roots, Set<String> leaves,
                                List<Cat> out, int depth) {
        if (cat == null || depth > MAX_DEPTH) return;
        if (roots != null && roots.contains(cat.id)) {
            // 白名单根：整棵子树放行（子类会变，跟着根走）。
            out.add(copyTree(cat, depth));
            return;
        }
        if (leaves != null && leaves.contains(cat.id)) {
            // 白名单叶子：提升为根，children 清空（它自己有书目）。
            out.add(copyShallow(cat));
            return;
        }
        for (int i = 0; i < cat.children.size(); i++) {
            collect(cat.children.get(i), roots, leaves, out, depth + 1);
        }
    }

    private static Cat copyShallow(Cat src) {
        Cat out = new Cat(src.source, src.id, src.name);
        out.icon = src.icon;
        out.desc = src.desc;
        out.color = src.color;
        return out;
    }

    private static Cat copyTree(Cat src, int depth) {
        Cat out = copyShallow(src);
        if (depth > MAX_DEPTH) return out;
        for (int i = 0; i < src.children.size(); i++) {
            Cat child = src.children.get(i);
            if (child != null) out.children.add(copyTree(child, depth + 1));
        }
        return out;
    }

    /**
     * 该书是否允许显示。
     *
     * <p>{@link Book} 没有分类字段，所以这里只能做兜底：源必须属于白名单源，
     * 标题再过一道黑名单。真正的边界在 {@link #filterCategories} ——
     * UI 必须只在白名单分类页里取书，这个函数只挡搜索、书架、历史、最近播放这些旁路。
     */
    public static boolean allowsBook(Book book) {
        if (!enabled()) return true;
        if (book == null) return false;
        String source = book.source == null ? "" : book.source;
        if (!isKidSource(source)) return false;
        return !blockedTitle(book.title);
    }

    /**
     * 标题黑名单：兜底用，故意克制（只挡明显不适宜的题材），不是关键词防火墙。
     * 误杀一本正经书也比放一本进来好，但不能把「儿童」「故事」这种词塞进来。
     */
    private static final String[] BLOCKED_TITLE_WORDS = {
            // 成人 / 情色
            "色情", "情色", "成人", "艳", "裸", "淫", "嫖", "娼", "黄色",
            // 情感 / 官场 / 投机
            "都市言情", "霸道总裁", "总裁的", "官场", "股市", "炒股",
            // 暴力 / 恐怖
            "凶杀", "杀人", "血腥", "暴力", "恐怖", "惊悚", "鬼故事", "灵异", "自杀",
            // 赌博 / 毒品
            "赌博", "赌场", "毒品", "drug",
    };

    private static boolean blockedTitle(String title) {
        if (title == null || title.length() == 0) return false;
        String text = lowerAscii(title);
        for (int i = 0; i < BLOCKED_TITLE_WORDS.length; i++) {
            if (text.indexOf(BLOCKED_TITLE_WORDS[i]) >= 0) return true;
        }
        return false;
    }

    /** 只折 ASCII，避免 toLowerCase 在某些 locale 下把中文标题改坏。 */
    private static String lowerAscii(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c >= 'A' && c <= 'Z') chars[i] = (char) (c + 32);
        }
        return new String(chars);
    }

    /**
     * 该源在青少年模式下允许浏览/搜索的叶子分类 id。
     *
     * <p>搜索没有服务端分类过滤（实测 cid 参数无效），所以青少年模式下的搜索
     * 只能限定在这些分类里翻页做本地匹配，见 {@link KidSearch}。
     * 未登记的源返回空列表 —— 默认拒绝。
     */
    public static List<String> allowedLeafIds(String sourceId) {
        if (sourceId == null) return new ArrayList<String>();
        Set<String> ids = ALLOWED_LEAVES.get(sourceId);
        if (ids == null || ids.isEmpty()) return new ArrayList<String>();
        return new ArrayList<String>(ids);
    }

    /* ---------------- UI 开关 ---------------- */

    /**
     * 搜索是否允许。青少年模式下也允许 —— 搜索是找内容的主要途径，
     * 关掉会让模式很难用。安全性由结果侧保证：搜索结果必须逐本过
     * {@link #allowsBook(Book)}，非白名单内容不会出现。
     */
    public static boolean allowsSearch() {
        return true;
    }

    /**
     * 过滤搜索结果：青少年模式下逐本校验，普通模式原样返回。
     *
     * <p>搜索是少数不知道「这本书属于哪个分类」的入口，所以这里只能靠
     * {@link #allowsBook(Book)} 的源白名单 + 标题黑名单兜底。
     * 返回新列表，不修改传入的列表。
     */
    public static List<Book> filterSearchResults(List<Book> books) {
        if (books == null) return new ArrayList<Book>();
        if (!enabled()) return books;
        List<Book> out = new ArrayList<Book>();
        for (int i = 0; i < books.size(); i++) {
            Book book = books.get(i);
            // allowsBook 内部已含源白名单 + 标题黑名单，不必重复判断。
            if (allowsBook(book)) out.add(book);
        }
        return out;
    }

    /** 首页是否允许展示通用推荐（青少年模式下 false，UI 改用儿童分类书目）。 */
    public static boolean allowsGenericHome() {
        return !enabled();
    }
}

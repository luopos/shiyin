package com.shiyin.test;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.shiyin.app.kid.KidMode;
import com.shiyin.app.model.Book;
import com.shiyin.app.model.Cat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 青少年模式内核的独立 instrumentation runner。
 *
 * <p>跑真实 Android 环境（真 SharedPreferences、真 PBKDF2、真锁定计时），
 * 不做任何网络访问。
 */
public final class KidModeTest extends Instrumentation {

    private static final String FILE = "shiyin_kid";

    private final StringBuilder results = new StringBuilder();
    private int passed;

    private void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        passed++;
        results.append("PASS ").append(name).append('\n');
        android.util.Log.i("ShiyinKidMode", "PASS " + name);
    }

    private void equal(Object expected, Object actual, String name) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        check(same, same ? name : name + " 期望=" + expected + " 实际=" + actual);
    }

    private SharedPreferences kid() {
        return getTargetContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private void wipe() {
        kid().edit().clear().commit();
        KidMode.init(getTargetContext());
    }

    /* ---------------- 真实分类结构 ---------------- */

    private static Cat cat(String source, String id, String name, Cat... children) {
        Cat node = new Cat(source, id, name);
        for (int i = 0; i < children.length; i++) node.children.add(children[i]);
        return node;
    }

    /** lrts：11 个根，id=6「儿童」下 8 个子类（真实订阅数据）。 */
    private static List<Cat> lrtsTree() {
        List<Cat> out = new ArrayList<Cat>();
        out.add(cat("lrts", "1", "有声小说", cat("lrts", "101", "都市言情")));
        out.add(cat("lrts", "78", "名著文学", cat("lrts", "7801", "世界名著")));
        Cat kids = cat("lrts", "6", "儿童",
                cat("lrts", "63", "益智故事"),
                cat("lrts", "3027", "儿童文学"),
                cat("lrts", "9031", "国学启蒙"),
                cat("lrts", "9245", "少儿名著"),
                cat("lrts", "59", "儿歌童谣"),
                cat("lrts", "64", "少儿科普"),
                cat("lrts", "65", "家教育儿"),
                cat("lrts", "9029", "卡通动画"));
        out.add(kids);
        out.add(cat("lrts", "17", "人文"));
        out.add(cat("lrts", "16", "财经"));
        out.add(cat("lrts", "4", "曲艺戏曲"));
        out.add(cat("lrts", "3", "相声评书"));
        out.add(cat("lrts", "66", "健康"));
        out.add(cat("lrts", "9068", "历史"));
        out.add(cat("lrts", "1022", "生活"));
        out.add(cat("lrts", "83", "成功"));
        return out;
    }

    /** tingyou：3 个根，白名单子类挂在 cat:category=2 下，另有一个非白名单子类做诱饵。 */
    private static List<Cat> tingyouTree() {
        List<Cat> out = new ArrayList<Cat>();
        out.add(cat("tingyou", "rank:recommend", "推荐榜"));
        out.add(cat("tingyou", "cat:category=2", "有声小说",
                cat("tingyou", "cat:type=43", "亲子教育"),
                cat("tingyou", "cat:type=20", "童话寓言"),
                cat("tingyou", "cat:type=99", "悬疑推理")));
        out.add(cat("tingyou", "cat:category=1", "相声评书"));
        return out;
    }

    private static List<Cat> kuwoTree() {
        List<Cat> out = new ArrayList<Cat>();
        out.add(cat("kuwo", "k1", "有声书", cat("kuwo", "k11", "玄幻")));
        out.add(cat("kuwo", "k2", "儿童"));
        return out;
    }

    private static List<String> ids(List<Cat> cats) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < cats.size(); i++) out.add(cats.get(i).id);
        return out;
    }

    private static Cat find(List<Cat> cats, String id) {
        for (int i = 0; i < cats.size(); i++) {
            if (id.equals(cats.get(i).id)) return cats.get(i);
        }
        return null;
    }

    /* ---------------- 测试 ---------------- */

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle outcome = new Bundle();
        try {
            runAll();
            outcome.putString("stream", results.toString() + "TOTAL " + passed + " PASS 0 FAIL");
            finish(-1, outcome);
        } catch (Throwable failure) {
            outcome.putString("stream", results.toString() + "FAIL " + failure);
            finish(0, outcome);
        } finally {
            KidMode.removeListener(silent);
            kid().edit().clear().commit();
        }
    }

    private final KidMode.Listener silent = new KidMode.Listener() {
        @Override public void onKidModeChanged(boolean enabled) {
            lastEvent = enabled;
            events++;
        }
    };
    private volatile boolean lastEvent;
    private volatile int events;

    private void runAll() throws Exception {
        wipe();

        // 1. 初始状态
        KidMode.init(getTargetContext());
        KidMode.init(getTargetContext());   // 可重复调用
        check(!KidMode.hasPassword(), "初始状态没有密码");
        check(!KidMode.enabled(), "初始状态青少年模式关闭");
        check(KidMode.lockRemainingSeconds() == 0, "初始状态没有锁定");
        check(KidMode.allowsSearch() && KidMode.allowsGenericHome(),
                "关闭状态下允许搜索与通用首页");

        // 2. 长度校验
        check(!KidMode.setPassword("123"), "3 位密码被拒绝");
        check(!KidMode.setPassword("12345678901234567"), "17 位密码被拒绝");
        check(!KidMode.setPassword("    "), "全空白密码被拒绝");
        check(!KidMode.setPassword(null), "null 密码被拒绝");
        check(!KidMode.hasPassword(), "非法长度不会写入密码");
        check(KidMode.setPassword("1234"), "4 位密码可以设置");
        check(KidMode.hasPassword(), "设置后 hasPassword 为 true");

        // 3. 校验
        check(KidMode.verify("1234"), "正确密码校验通过");
        check(!KidMode.verify("12345"), "错误密码校验失败");
        check(!KidMode.verify(""), "空密码校验失败");
        check(!KidMode.verify(null), "null 密码校验失败");
        check(!KidMode.setPassword("5678"), "已有密码时 setPassword 拒绝静默覆盖");

        // 4. 存储不含明文
        Map<String, ?> stored = kid().getAll();
        boolean plaintext = false;
        StringBuilder dump = new StringBuilder();
        for (Map.Entry<String, ?> entry : stored.entrySet()) {
            Object value = entry.getValue();
            dump.append(entry.getKey()).append('=').append(value).append(' ');
            if ("1234".equals(String.valueOf(value))) plaintext = true;
        }
        check(!plaintext, "shiyin_kid 里没有明文密码");
        check(String.valueOf(stored.get("kid.hash")).length() > 0, "kid.hash 非空");
        check(String.valueOf(stored.get("kid.salt")).length() > 0, "kid.salt 非空");
        check(String.valueOf(stored.get("kid.iter")).equals("20000"), "kid.iter 记录迭代次数");
        check("pbkdf2-sha1".equals(String.valueOf(stored.get("kid.alg"))), "kid.alg 记录算法版本");
        check(!dump.toString().contains("1234"), "整个 prefs 快照不含密码原文");
        boolean leakedToMainFile = false;
        for (String key : getTargetContext().getSharedPreferences("shiyin", Context.MODE_PRIVATE)
                .getAll().keySet()) {
            if (key.startsWith("kid.")) leakedToMainFile = true;
        }
        check(!leakedToMainFile, "密码没有写进 shiyin 主文件");

        // 5. 两次设置同一密码盐不同
        String salt1 = String.valueOf(stored.get("kid.salt"));
        String hash1 = String.valueOf(stored.get("kid.hash"));
        wipe();
        check(KidMode.setPassword("1234"), "清空后可以重新设置同一密码");
        String salt2 = String.valueOf(kid().getAll().get("kid.salt"));
        String hash2 = String.valueOf(kid().getAll().get("kid.hash"));
        check(salt1.length() > 0 && salt2.length() > 0 && !salt1.equals(salt2),
                "同一密码两次设置的 salt 不同");
        check(!hash1.equals(hash2), "同一密码两次设置的 hash 不同");
        check(KidMode.verify("1234"), "重新设置后密码仍可校验");

        // 6. enableWith
        KidMode.addListener(silent);
        events = 0;
        check(!KidMode.enableWith("9999"), "错误密码不能开启青少年模式");
        check(!KidMode.enabled(), "开启失败后仍为关闭");
        check(events == 0, "开启失败不触发监听");
        check(KidMode.enableWith("1234"), "正确密码可以开启青少年模式");
        check(KidMode.enabled(), "开启后 enabled 为 true");
        check(events == 1 && lastEvent, "开启时监听收到 true");

        // 13. 开启后搜索与通用首页关闭
        check(!KidMode.allowsSearch(), "青少年模式下不允许搜索");
        check(!KidMode.allowsGenericHome(), "青少年模式下不允许通用首页");

        // 10/11/12 分类过滤
        List<Cat> lrts = lrtsTree();
        List<Cat> lrtsFiltered = KidMode.filterCategories("lrts", lrts);
        check(lrtsFiltered.size() == 1, "lrts 只剩一个根分类（儿童）");
        equal("6", lrtsFiltered.get(0).id, "lrts 保留根分类 6");
        equal("儿童", lrtsFiltered.get(0).name, "lrts 保留儿童分类名称");
        equal(8, lrtsFiltered.get(0).children.size(), "lrts 儿童下 8 个子类全部保留");
        equal("[63, 3027, 9031, 9245, 59, 64, 65, 9029]",
                ids(lrtsFiltered.get(0).children).toString(), "lrts 子类 id 与真实数据一致");
        equal(11, lrts.size(), "过滤没有改动原始根列表");
        equal(8, find(lrts, "6").children.size(), "过滤没有改动原始子类列表");
        check(lrtsFiltered.get(0) != find(lrts, "6"), "过滤结果是深拷贝而不是原对象");
        check(lrtsFiltered.get(0).children.get(0) != find(lrts, "6").children.get(0),
                "子类也是深拷贝");
        lrtsFiltered.get(0).children.get(0).name = "被测试改过的名字";
        equal("益智故事", find(lrts, "6").children.get(0).name, "改拷贝不影响原始分类树");
        check("lrts".equals(lrtsFiltered.get(0).source), "过滤结果保留 source 字段");

        List<Cat> kuwoFiltered = KidMode.filterCategories("kuwo", kuwoTree());
        check(kuwoFiltered.isEmpty(), "kuwo 没有白名单，青少年模式下分类为空");
        List<Cat> unknownFiltered = KidMode.filterCategories("no-such-source", lrtsTree());
        check(unknownFiltered.isEmpty(), "未知源在青少年模式下分类为空");
        check(KidMode.filterCategories("lrts", null).isEmpty(), "null 分类树返回空列表");

        List<Cat> tingyou = tingyouTree();
        List<Cat> tingyouFiltered = KidMode.filterCategories("tingyou", tingyou);
        equal(2, tingyouFiltered.size(), "tingyou 提升两个白名单子类为根");
        equal("[cat:type=43, cat:type=20]", ids(tingyouFiltered).toString(),
                "tingyou 只留亲子教育与童话寓言");
        equal("亲子教育", tingyouFiltered.get(0).name, "tingyou 提升后保留分类名称");
        check(tingyouFiltered.get(0).children.isEmpty(), "tingyou 提升后清空 children");
        check(find(tingyouFiltered, "cat:category=2") == null, "tingyou 父分类不留");
        check(find(tingyouFiltered, "cat:type=99") == null, "tingyou 非白名单子类被丢弃");
        check(find(tingyouFiltered, "rank:recommend") == null, "tingyou 推荐榜被过滤");

        // 根分类意外缺失时的兜底：白名单子类单独出现也能被提升。
        List<Cat> orphan = new ArrayList<Cat>();
        orphan.add(cat("lrts", "1", "有声小说", cat("lrts", "63", "益智故事")));
        List<Cat> orphanFiltered = KidMode.filterCategories("lrts", orphan);
        equal(1, orphanFiltered.size(), "lrts 根缺失时兜底提升白名单子类");
        equal("63", orphanFiltered.get(0).id, "兜底提升的是益智故事");
        check(orphanFiltered.get(0).children.isEmpty(), "兜底提升后清空 children");

        // allowsBook 兜底
        check(KidMode.allowsBook(new Book("lrts", "1", "安徒生童话")), "lrts 正常儿童书目允许");
        check(!KidMode.allowsBook(new Book("kuwo", "1", "安徒生童话")), "非白名单源的书目被拒绝");
        check(!KidMode.allowsBook(new Book("lrts", "2", "都市言情：总裁的替身")),
                "标题命中黑名单的书目被拒绝");
        check(!KidMode.allowsBook(new Book("lrts", "3", "恐怖鬼故事合集")),
                "恐怖题材书目被拒绝");
        check(!KidMode.allowsBook(null), "null 书目被拒绝");

        // 7. disableWith
        check(!KidMode.disableWith("0000"), "错误密码不能关闭青少年模式");
        check(KidMode.enabled(), "关闭失败后仍是开启");
        check(events == 1, "关闭失败不触发监听");
        check(KidMode.disableWith("1234"), "正确密码可以关闭青少年模式");
        check(!KidMode.enabled(), "关闭后 enabled 为 false");
        check(KidMode.hasPassword(), "关闭青少年模式不会删除密码");

        // 14. 关闭后搜索与通用首页恢复
        check(KidMode.allowsSearch(), "退出后允许搜索");
        check(KidMode.allowsGenericHome(), "退出后允许通用首页");
        check(KidMode.allowsBook(new Book("kuwo", "1", "都市言情")), "关闭状态下不过滤书目");
        List<Cat> allLrts = KidMode.filterCategories("lrts", lrtsTree());
        equal(11, allLrts.size(), "关闭状态下分类原样返回");
        check(allLrts.get(0) != null, "关闭状态下返回的是拷贝");

        // 8. changePassword
        check(!KidMode.changePassword("wrong", "abcd"), "旧密码错误时改密码失败");
        check(KidMode.verify("1234"), "改密码失败后旧密码仍有效");
        check(!KidMode.changePassword("1234", "abc"), "新密码过短时改密码失败");
        check(KidMode.changePassword("1234", "abcd"), "旧密码正确时改密码成功");
        check(KidMode.verify("abcd"), "新密码生效");
        check(!KidMode.verify("1234"), "旧密码失效");
        check("abcd".length() == 4 && KidMode.enableWith("abcd"), "新密码可以开启青少年模式");
        check(KidMode.disableWith("abcd"), "新密码可以关闭青少年模式");

        // 9. 连续输错锁定
        check(KidMode.lockRemainingSeconds() == 0, "无失败时不锁定");
        for (int i = 0; i < 4; i++) check(!KidMode.verify("bad" + i), "第 " + (i + 1) + " 次错密码失败");
        check(KidMode.lockRemainingSeconds() == 0, "错 4 次还没锁定");
        check(!KidMode.verify("bad-final"), "第 5 次错密码失败");
        int remaining = KidMode.lockRemainingSeconds();
        check(remaining > 0 && remaining <= 30, "错 5 次后锁定，剩余秒数合理：" + remaining);
        check(!KidMode.verify("abcd"), "锁定期间正确密码也被拒绝");
        check(!KidMode.enableWith("abcd"), "锁定期间不能用正确密码开启");
        check(!KidMode.enabled(), "锁定期间青少年模式保持关闭");
        check(!KidMode.changePassword("abcd", "wxyz"), "锁定期间不能改密码");
        // 直接把时间戳往前挪 31 秒，模拟锁定过期，不真等。
        kid().edit().putLong("kid.failAt", System.currentTimeMillis() - 31000L).commit();
        check(KidMode.lockRemainingSeconds() == 0, "锁定过期后剩余秒数为 0");
        check(KidMode.verify("abcd"), "锁定过期后正确密码通过");
        check(KidMode.lockRemainingSeconds() == 0, "正确密码清掉失败状态");

        // 监听器增删
        KidMode.removeListener(silent);
        events = 0;
        check(KidMode.enableWith("abcd") && KidMode.disableWith("abcd"), "移除监听后仍能开关");
        check(events == 0, "移除监听后不再收到回调");

        // 清理并复查初始状态可重现
        wipe();
        check(!KidMode.hasPassword(), "清空存储后回到未设置状态");
        check(!KidMode.enabled(), "清空存储后青少年模式关闭");
    }
}

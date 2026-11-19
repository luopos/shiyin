package com.shiyin.app.source;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Cat;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.net.Http;
import com.shiyin.app.store.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 订阅源引擎：接口地址用模板，字段用点路径描述，不执行任何脚本。
 *
 * <p>模板变量：{kw} 关键词（自动 URL 编码）、{page} 当前页、{page0} 页码减一、
 * {page1} 页码加一、{id} 书籍 ID、{cat} 分类 ID、{chapterId} 章节 ID。
 *
 * <p>路径用点号分隔，纯数字段表示数组下标，例如 {@code data.list}、
 * {@code data.list.0.name}。取不到时返回空串，不抛异常。
 */
public final class JsonSource extends Source {

    private final JSONObject spec;
    private final String id;
    private final String name;
    private final String desc;
    private final Map<String, String> headers;

    public JsonSource(JSONObject spec) throws IOException {
        if (spec == null) throw new IOException("订阅源配置为空");
        this.spec = spec;
        this.id = text(spec, "id");
        this.name = text(spec, "name");
        this.desc = text(spec, "desc");
        if (id.length() == 0 || name.length() == 0) {
            throw new IOException("订阅源缺少 id 或 name");
        }
        Map<String, String> h = new HashMap<>();
        JSONObject hs = spec.optJSONObject("headers");
        if (hs != null) {
            Iterator<String> it = hs.keys();
            while (it.hasNext()) {
                String k = it.next();
                h.put(k, hs.optString(k));
            }
        }
        headers = h;
    }

    @Override public String id() { return id; }

    @Override public String name() { return name; }

    @Override public String desc() { return desc; }

    @Override public boolean searchable() { return spec.optJSONObject("search") != null; }

    @Override public boolean hasCats() { return spec.optJSONObject("categories") != null; }

    public boolean hasHome() { return spec.optJSONObject("home") != null; }

    /** 缓存随源配置变化失效，磁盘只保存摘要。 */
    public String homeCacheVersion() {
        try {
            byte[] bytes = spec.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** 首页板块；订阅里配了 home.sections 就是多板块，否则单板块。 */
    public static final class HomeSection {
        public final String label;
        public final List<Book> books;

        public HomeSection(String label, List<Book> books) {
            this.label = label;
            this.books = books;
        }
    }

    /** 订阅里配了 home 才有；没配就让调用方退回分类第一页。 */
    public List<HomeSection> home() throws Exception {
        JSONObject block = spec.getJSONObject("home");
        String url = url(block, vars("page", 1, "page0", 0, "page1", 2,
                "kw", "", "id", "", "cat", "", "chapterId", ""));
        JSONObject root = fetch(url);
        JSONObject fields = block.optJSONObject("fields");
        List<HomeSection> out = new ArrayList<>();
        String sectionsPath = text(block, "sections");
        if (sectionsPath.length() > 0) {
            JSONArray arr = array(root, sectionsPath);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject sec = arr.optJSONObject(i);
                if (sec == null) continue;
                List<Book> books = booksOf(array(sec, "items"), fields);
                if (!books.isEmpty()) out.add(new HomeSection(string(sec, "label"), books));
            }
        }
        if (out.isEmpty()) {
            List<Book> books = booksOf(array(root, text(block, "items")), fields);
            if (!books.isEmpty()) out.add(new HomeSection("", books));
        }
        return out;
    }

    /* ---------------- 搜索 ---------------- */

    @Override public List<Book> search(String keyword, int page) throws Exception {
        JSONObject block = spec.getJSONObject("search");
        String url = url(block, vars("kw", keyword, "page", page, "page0", page - 1,
                "page1", page + 1, "id", "", "cat", "", "chapterId", ""));
        return books(fetch(url), block);
    }

    /* ---------------- 分类 ---------------- */

    @Override public synchronized List<Cat> cats() throws Exception {
        String version = homeCacheVersion();
        Prefs.CachedCategories cached = Prefs.cachedCategories(id, version);
        long now = System.currentTimeMillis();
        if (cached != null && !Prefs.categoryRefreshDue(id, version, now,
                Subscriptions.CACHE_MAX_AGE_MS)) return cached.categories;
        try {
            JSONObject block = spec.getJSONObject("categories");
            JSONArray items = array(fetch(expand(text(block, "url"), vars())), text(block, "items"));
            List<Cat> out = parseCategories(items, block.optJSONObject("fields"), 0);
            if (out.isEmpty()) throw new IOException("分类接口暂时没有返回分类");
            Prefs.cacheCategories(id, version, out, System.currentTimeMillis());
            return out;
        } catch (Exception failure) {
            if (cached != null) return cached.categories;
            throw failure;
        } finally {
            Prefs.markCategoryAttempt(id, version, System.currentTimeMillis());
        }
    }

    private List<Cat> parseCategories(JSONArray items, JSONObject fields, int depth) {
        List<Cat> out = new ArrayList<>();
        if (depth > 8) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String cid = field(item, fields, "id");
            String cname = field(item, fields, "name");
            if (cid.length() == 0 || cname.length() == 0) continue;
            Cat c = new Cat(id, cid, cname);
            c.icon = field(item, fields, "icon");
            c.desc = field(item, fields, "desc");
            String childPath = fields == null ? "children" : fields.optString("children", "children");
            c.children.addAll(parseCategories(array(item, childPath), fields, depth + 1));
            out.add(c);
        }
        return out;
    }

    public List<Cat> cachedCategories() {
        Prefs.CachedCategories cached = Prefs.cachedCategories(id, homeCacheVersion());
        return cached == null ? new ArrayList<>() : cached.categories;
    }

    @Override public Page page(Cat cat, int pn) throws Exception {
        JSONObject block = spec.getJSONObject("categories").getJSONObject("page");
        String url = url(block, vars("cat", cat == null ? "" : cat.id, "page", pn,
                "page0", pn - 1, "page1", pn + 1, "kw", "", "id", "", "chapterId", ""));
        JSONObject root = fetch(url);
        Page p = new Page();
        p.page = pn;
        p.books = books(root, block);
        p.totalPage = totalPage(root, block, pn, p.books.size());
        return p;
    }

    /* ---------------- 详情 ---------------- */

    @Override public Detail detail(Book book, boolean withEpisodes) throws Exception {
        JSONObject block = spec.getJSONObject("detail");
        String url = url(block, vars("id", book == null ? "" : book.id, "page", 1,
                "page0", 0, "page1", 2, "kw", "", "cat", "", "chapterId", ""));
        JSONObject root = fetch(url);
        Detail d = new Detail();
        JSONObject fields = block.optJSONObject("fields");
        JSONObject meta = obj(root, text(block, "meta"));
        d.meta = meta == null ? copyBook(book) : book(meta, fields, id, book);
        if (withEpisodes) {
            JSONObject chapters = block.optJSONObject("chapters");
            if (chapters != null) {
                JSONArray items = array(root, text(chapters, "items"));
                JSONObject cf = chapters.optJSONObject("fields");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    Chapter c = chapter(item, cf, book == null ? "" : book.id);
                    if (c != null) d.chapters.add(c);
                }
            }
        }
        return d;
    }

    /** 配了 chapters.paged 的源走服务端分页，否则沿用整本切片。 */
    @Override public ChapterPage chapterPage(Book book, int page) throws Exception {
        JSONObject chapters = spec.optJSONObject("detail") == null ? null
                : spec.getJSONObject("detail").optJSONObject("chapters");
        JSONObject paged = chapters == null ? null : chapters.optJSONObject("paged");
        if (paged == null) return super.chapterPage(book, page);

        String url = url(paged, vars("id", book == null ? "" : book.id, "page", page,
                "page0", page, "page1", page + 1, "kw", "", "cat", "", "chapterId", ""));
        JSONObject root = fetch(url);
        JSONArray items = array(root, text(paged, "items"));
        ChapterPage out = new ChapterPage();
        out.page = page;
        out.networkPaged = true;
        JSONObject cf = paged.optJSONObject("fields");
        if (cf == null) cf = chapters.optJSONObject("fields");
        int offset = page * CHAPTER_PAGE_SIZE;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            Chapter c = chapter(item, cf, book == null ? "" : book.id);
            if (c == null) continue;
            c.index = offset + out.chapters.size();
            out.chapters.add(c);
        }
        out.total = intValue(root, text(paged, "total"), -1);
        if (out.total < 0) out.total = out.chapters.isEmpty() ? offset : offset + out.chapters.size();
        String more = text(paged, "hasMore");
        out.hasMore = more.length() > 0 ? bool(root, more) : offset + out.chapters.size() < out.total;
        if (book != null) out.meta = copyBook(book);
        return out;
    }

    /* ---------------- 播放地址 ---------------- */

    @Override public String audio(Chapter chapter) throws Exception {
        JSONObject block = spec.optJSONObject("play");
        if (block == null) throw new IOException("该订阅源未配置播放接口");
        String url = url(block, vars("chapterId", chapter == null ? "" : chapter.id,
                "id", chapter == null ? "" : chapter.bookId, "page", 1,
                "page0", 0, "page1", 2, "kw", "", "cat", ""));
        JSONObject root = fetch(url);
        String field = text(block, "field");
        String value = field.length() == 0 ? "" : string(root, field);
        if (value.length() == 0) throw new IOException("订阅源没有返回播放地址");
        return value;
    }

    /* ---------------- 请求与解析 ---------------- */

    private JSONObject fetch(String url) throws IOException {
        if (url == null || url.length() == 0) throw new IOException("订阅源未配置接口地址");
        Http.Resp r = Http.get(url, headers.isEmpty() ? null : headers, Http.UA);
        if (!r.ok()) throw new IOException("订阅源返回 HTTP " + r.code);
        String body = r.text == null ? "" : r.text.trim();
        if (body.length() == 0) throw new IOException("订阅源返回了空内容");
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("订阅源返回的不是 JSON", e);
        }
    }

    private List<Book> books(JSONObject root, JSONObject block) {
        return booksOf(array(root, text(block, "items")), block.optJSONObject("fields"));
    }

    private List<Book> booksOf(JSONArray items, JSONObject fields) {
        List<Book> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            Book b = book(item, fields, id, null);
            if (b != null && b.valid()) out.add(b);
        }
        return out;
    }

    private Book book(JSONObject item, JSONObject fields, String source, Book fallback) {
        String title = field(item, fields, "title");
        if (title.length() == 0) return fallback;
        Book b = new Book(source, field(item, fields, "id"), title);
        b.author = field(item, fields, "author");
        b.narrator = field(item, fields, "narrator");
        b.cover = field(item, fields, "cover");
        b.desc = field(item, fields, "desc");
        b.status = field(item, fields, "status");
        String num = field(item, fields, "songNum");
        if (num.length() > 0) {
            try {
                b.songNum = Integer.parseInt(num.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (b.songNum > 0 && b.status.length() == 0) b.status = "共 " + b.songNum + " 集";
        return b;
    }

    private Chapter chapter(JSONObject item, JSONObject fields, String bookId) {
        String title = field(item, fields, "title");
        String cid = field(item, fields, "id");
        if (title.length() == 0 && cid.length() == 0) return null;
        Chapter c = new Chapter(title.length() == 0 ? cid : title, cid);
        c.id = cid;
        c.bookId = bookId;
        String duration = field(item, fields, "duration");
        if (duration.length() > 0) {
            try {
                c.duration = Integer.parseInt(duration.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String paid = field(item, fields, "paid");
        c.paid = "true".equalsIgnoreCase(paid) || "1".equals(paid);
        return c;
    }

    private int totalPage(JSONObject root, JSONObject block, int pn, int size) {
        String more = text(block, "hasMore");
        if (more.length() > 0) return bool(root, more) ? pn + 1 : pn;
        String total = text(block, "totalPage");
        if (total.length() > 0) {
            int value = intValue(root, total, -1);
            if (value >= pn) return value;
        }
        return size == 0 ? pn : pn + 1;
    }

    /* ---------------- 模板与路径 ---------------- */

    private String url(JSONObject block, Map<String, String> vars) {
        return expand(text(block, "url"), vars);
    }

    private static String expand(String template, Map<String, String> vars) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static Map<String, String> vars(Object... pairs) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            String text = value == null ? "" : String.valueOf(value);
            if ("kw".equals(pairs[i]) || "cat".equals(pairs[i])) {
                try {
                    text = URLEncoder.encode(text, "UTF-8");
                } catch (Exception ignored) {
                }
            }
            out.put(String.valueOf(pairs[i]), text);
        }
        return out;
    }

    public static Object pick(Object root, String path) {
        if (root == null || path == null || path.length() == 0) return root;
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (cur instanceof JSONObject) {
                cur = ((JSONObject) cur).opt(seg);
            } else if (cur instanceof JSONArray) {
                int index;
                try {
                    index = Integer.parseInt(seg);
                } catch (NumberFormatException e) {
                    return null;
                }
                JSONArray a = (JSONArray) cur;
                cur = index >= 0 && index < a.length() ? a.opt(index) : null;
            } else {
                return null;
            }
            if (cur == null) return null;
        }
        return cur;
    }

    public static String string(Object root, String path) {
        Object v = pick(root, path);
        if (v == null || v == JSONObject.NULL) return "";
        return String.valueOf(v);
    }

    private static JSONArray array(JSONObject root, String path) {
        Object v = pick(root, path);
        return v instanceof JSONArray ? (JSONArray) v : new JSONArray();
    }

    private static JSONObject obj(JSONObject root, String path) {
        Object v = pick(root, path);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    private static int intValue(JSONObject root, String path, int fallback) {
        String v = string(root, path);
        if (v.length() == 0) return fallback;
        try {
            return (int) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(JSONObject root, String path) {
        Object v = pick(root, path);
        if (v instanceof Boolean) return (Boolean) v;
        String s = v == null ? "" : String.valueOf(v);
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static String text(JSONObject o, String key) {
        if (o == null) return "";
        String v = o.optString(key, "");
        return v == null ? "" : v.trim();
    }

    private static String field(JSONObject item, JSONObject fields, String key) {
        String path = fields == null ? key : fields.optString(key, "");
        if (path.length() == 0) path = key;
        return string(item, path);
    }
}

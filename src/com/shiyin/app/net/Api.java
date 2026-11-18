package com.shiyin.app.net;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.store.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 源1（听书吧 / lrts）和源2（酷我）的边缘函数接口。
 *
 * 这两个是纯 JSON，不需要解析 HTML，也是内容量最大的两个源 —— 原生版的主力。
 * 匿名 key 就能读，不用登录。
 */
public final class Api {

    private static final String BASE =
            "https://backend.appmiaoda.com/projects/supabase345057433544081408/functions/v1";
    /**
     * 边缘函数要求带 Supabase 的匿名 key。role=anon，本来就会随客户端一起分发，
     * 不是私密凭据；写在这里只是为了让工程能直接编译运行。
     */
    private static final String KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJhdXRoZW50aWNhdGVkIiwiZXhwIjoyMTAxNjk1MzAzLCJpc3MiOiJzdXBhYmFzZSIsInJvbGUiOiJhbm9uIiwic3ViIjoiYW5vbiJ9.d_zv-NO7ifqZtHZHdEFYo0Kaxldn9NeW7Yy4YmHnsnc";

    /** 远程配置：播放节点会变，从这儿拉。 */
    private static final String RC_URL = "http://www.tingshu8.top/api.php";
    private static final String[] FALLBACK_HOSTS = {
            "http://43.142.122.173", "http://115.190.207.125"
    };

    /** 请求都在 Bg 线程池里并发跑，缓存必须线程安全；上限避免搜索词把内存撑爆。 */
    private static final Map<String, Cache> MEM = new ConcurrentHashMap<>();
    private static final int MEM_LIMIT = 128;
    private static final long TTL = 4 * 60 * 1000L;

    private Api() {
    }

    private static final class Cache {
        long t;
        String v;

        Cache(long t, String v) {
            this.t = t;
            this.v = v;
        }
    }

    /* ---------------- 基础请求 ---------------- */

    public static JSONObject get(String fn, Map<String, String> params) throws IOException {
        return get(fn, params, TTL);
    }

    public static JSONObject get(String fn, Map<String, String> params, long ttl) throws IOException {
        StringBuilder keyB = new StringBuilder(fn);
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                keyB.append('|').append(e.getKey()).append('=').append(e.getValue());
            }
        }
        String key = keyB.toString();

        Cache hit = MEM.get(key);
        if (hit != null && System.currentTimeMillis() - hit.t < ttl) {
            try {
                return new JSONObject(hit.v);
            } catch (Exception ignored) {
            }
        }

        StringBuilder url = new StringBuilder(BASE).append('/').append(fn);
        if (params != null && !params.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) url.append('&');
                first = false;
                url.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            }
        }

        Map<String, String> h = new HashMap<>();
        h.put("apikey", KEY);
        h.put("Authorization", "Bearer " + KEY);
        h.put("Accept", "application/json");

        Http.Resp r = Http.request("GET", url.toString(), h, null, null, Http.UA);
        if (!r.ok()) throw new IOException(fn + " HTTP " + r.code);
        String body = r.text == null ? "{}" : r.text;
        MEM.put(key, new Cache(System.currentTimeMillis(), body));
        if (MEM.size() > MEM_LIMIT) MEM.clear();
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IOException(fn + " 返回的不是 JSON");
        }
    }

    /* ---------------- 归一化 ---------------- */

    public static Book normBook(JSONObject b, String source) {
        if (b == null) return null;
        Book x = new Book();
        x.source = b.optString("source", source);
        x.id = b.optString("id", b.optString("albumId", ""));
        x.title = first(b.optString("title"), b.optString("name")).trim();
        x.author = b.optString("author", "");
        x.narrator = b.optString("narrator", "");
        x.cover = b.optString("cover", "");
        x.desc = first(b.optString("description"), b.optString("desc")).trim();
        x.songNum = b.optInt("songNum", b.optInt("songnum", 0));
        x.rating = (float) b.optDouble("rating", 0);
        x.listeners = b.optString("listeners", "");
        x.status = x.songNum > 0 ? ("共 " + x.songNum + " 集") : "";
        x.bookUrl = b.optString("bookUrl", "");
        if (!x.valid()) return null;
        return x;
    }

    private static String first(String a, String b) {
        if (a != null && a.length() > 0 && !"null".equals(a)) return a;
        return b == null || "null".equals(b) ? "" : b;
    }

    public static Chapter normChapter(JSONObject c, String bookId) {
        Chapter x = new Chapter();
        x.id = c.optString("id", "");
        x.bookId = bookId;
        x.title = first(c.optString("title"), c.optString("name")).trim();
        x.duration = c.optInt("duration", 0);
        x.paid = c.optBoolean("paid", false);
        if (x.title.length() == 0) {
            int no = 0;
            if (x.id.contains("*")) {
                try {
                    no = Integer.parseInt(x.id.substring(x.id.lastIndexOf('*') + 1));
                } catch (Exception ignored) {
                }
            }
            x.title = "第" + (no > 0 ? String.valueOf(no) : "?") + "集";
        }
        x.url = x.id;
        return x;
    }

    private static List<Book> books(JSONArray a, String source) {
        List<Book> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            Book b = normBook(a.optJSONObject(i), source);
            if (b != null) out.add(b);
        }
        return out;
    }

    private static List<Chapter> chapters(JSONArray a, String bookId) {
        List<Chapter> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(normChapter(o, bookId));
        }
        return out;
    }

    /* ---------------- 首页 ---------------- */

    public static final class Home {
        public List<Book> books = new ArrayList<>();
        public List<Section> sections = new ArrayList<>();
    }

    public static final class Section {
        public String label = "";
        public List<Book> books = new ArrayList<>();
    }

    public static Home home() throws IOException {
        JSONObject j = get("lrts-home", null);
        Home h = new Home();
        h.books = books(j.optJSONArray("books"), "lrts");
        JSONArray sa = j.optJSONArray("sections");
        if (sa != null) {
            for (int i = 0; i < sa.length(); i++) {
                JSONObject s = sa.optJSONObject(i);
                if (s == null) continue;
                Section sec = new Section();
                sec.label = s.optString("label", "");
                sec.books = books(s.optJSONArray("books"), "lrts");
                if (!sec.books.isEmpty()) h.sections.add(sec);
            }
        }
        return h;
    }

    /* ---------------- 分类 ---------------- */

    public static final class Page {
        public List<Book> books = new ArrayList<>();
        public int total = 0;
        public int page = 1;
    }

    public static Page categoryLrts(String categoryId, int page, String sortType) throws IOException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("categoryId", categoryId);
        p.put("page", String.valueOf(page));
        p.put("sortType", sortType == null ? "recommend" : sortType);
        JSONObject j = get("lrts-category", p);
        Page pg = new Page();
        pg.books = books(j.optJSONArray("books"), "lrts");
        pg.total = j.optInt("total", 0);
        pg.page = page;
        return pg;
    }

    public static Page categoryKuwo(String classifyId, String categoryId, int page, int rn)
            throws IOException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("classifyId", classifyId == null ? "" : classifyId);
        p.put("categoryId", categoryId == null ? "" : categoryId);
        p.put("pn", String.valueOf(page));
        p.put("rn", String.valueOf(rn));
        p.put("sortType", "tsScore");
        JSONObject j = get("kuwo-albums", p);
        Page pg = new Page();
        pg.books = books(j.optJSONArray("books"), "kuwo");
        pg.total = j.optInt("total", 0);
        pg.page = page;
        return pg;
    }

    /* ---------------- 搜索 ---------------- */

    public static Page search(String source, String keyword, int page) throws IOException {
        if ("kuwo".equals(source)) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("keyword", keyword);
            p.put("pn", String.valueOf(page));
            p.put("rn", "20");
            JSONObject j = get("kuwo-search", p);
            Page pg = new Page();
            pg.books = books(j.optJSONArray("books"), "kuwo");
            pg.total = j.optInt("total", 0);
            pg.page = page;
            return pg;
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", keyword);
        p.put("page", String.valueOf(page));
        JSONObject j = get("lrts-search", p);
        Page pg = new Page();
        pg.books = books(j.optJSONArray("books"), "lrts");
        pg.total = j.optInt("total", 0);
        pg.page = page;
        return pg;
    }

    /* ---------------- 详情 ---------------- */

    public static final class Detail {
        public Book meta;
        public List<Chapter> chapters = new ArrayList<>();
    }

    public static Detail book(String source, String id) throws IOException {
        boolean kuwo = "kuwo".equals(source);
        Map<String, String> p = new LinkedHashMap<>();
        p.put(kuwo ? "albumId" : "bookId", id);
        JSONObject j = get(kuwo ? "kuwo-albuminfo" : "lrts-book", p, 10 * 60 * 1000L);
        JSONObject m = j.optJSONObject("meta");
        Detail d = new Detail();
        if (m != null) {
            JSONObject copy = new JSONObject();
            try {
                java.util.Iterator<String> it = m.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    copy.put(k, m.opt(k));
                }
                if (!copy.has("id")) copy.put("id", id);
                if (!copy.has("title")) copy.put("title", m.optString("name", ""));
                if (!copy.has("songNum")) copy.put("songNum", m.optInt("songnum", 0));
            } catch (Exception ignored) {
            }
            d.meta = normBook(copy, source);
        }
        if (d.meta == null) {
            d.meta = new Book(source, id, "未知书籍");
        }
        d.chapters = chapters(j.optJSONArray("chapters"), id);
        return d;
    }

    /* ---------------- 排行榜 ---------------- */

    public static final class Rank {
        public List<Book> books = new ArrayList<>();
        public List<String> badges = new ArrayList<>();
    }

    public static Rank rank() throws IOException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("rankId", "");
        p.put("rangeType", "");
        JSONObject j = get("lrts-rank", p);
        Rank r = new Rank();
        r.books = books(j.optJSONArray("books"), "lrts");
        JSONArray b = j.optJSONArray("badgeList");
        if (b != null) {
            for (int i = 0; i < b.length(); i++) r.badges.add(b.optString(i));
        }
        return r;
    }

    /* ---------------- 播放地址 ---------------- */

    public static final class PlayUrl {
        public String url = "";
        public String lyric = "";
    }

    /** 取播放地址：先直连内置/配置节点，再使用边缘接口兜底。 */
    public static PlayUrl playUrl(Book book, Chapter chapter) throws IOException {
        if (chapter == null) throw new IOException("没有章节");
        if ("kuwo".equals(book.source)) {
            String q = "320".equals(Prefs.quality()) ? "320" : "128";
            List<String> hosts = new ArrayList<>();
            String stored = Prefs.playHost();
            if (stored != null && stored.length() > 0) hosts.add(stored);
            for (String h : FALLBACK_HOSTS) {
                if (!hosts.contains(h)) hosts.add(h);
            }

            // 每个节点先打直连的 kuwo.php（快、不用过边缘函数），再试边缘函数。
            // 全程单次请求不重试 —— 三节点 × 4 次重试会把"取地址"拖成 20 秒。
            for (String h : hosts) {
                String u = directKuwo(h, q, chapter.id);
                if (u.length() > 0) return wrap(u, "");
            }
            for (String h : hosts) {
                try {
                    Map<String, String> p = new LinkedHashMap<>();
                    p.put("rid", chapter.id);
                    p.put("quality", q);
                    p.put("host", h);
                    JSONObject j = getOnce("kuwo-play", p);
                    String u = j.optString("url", "");
                    if (u.length() > 0) return wrap(u, j.optString("lyric", ""));
                } catch (Exception ignored) {
                }
            }
            throw new IOException("取播放地址失败，换个源试试");
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", chapter.id);
        JSONObject j = get("lrts-play", p, 0);
        JSONObject data = j.optJSONObject("data");
        String u = data == null ? "" : data.optString("url", "");
        if (u.length() == 0) throw new IOException(j.optString("message", "取播放地址失败"));
        return wrap(u, "");
    }

    private static PlayUrl wrap(String url, String lyric) {
        PlayUrl pu = new PlayUrl();
        pu.url = url;
        pu.lyric = lyric;
        return pu;
    }

    /** 直连节点的 kuwo.php，返回空串表示这个节点不行。 */
    private static String directKuwo(String host, String quality, String rid) {
        try {
            String u = host + "/kuwo.php?type=" + quality + "&rid="
                    + URLEncoder.encode(rid, "UTF-8");
            Http.Resp r = Http.once(u, null);
            if (!r.ok() || r.text == null || r.text.length() == 0) return "";
            JSONObject j = new JSONObject(r.text);
            String add = j.optString("add", "");
            return add == null ? "" : add;
        } catch (Exception e) {
            return "";
        }
    }

    /** 边缘函数单次请求，不重试。 */
    private static JSONObject getOnce(String fn, Map<String, String> params) throws IOException {
        StringBuilder url = new StringBuilder(BASE).append('/').append(fn);
        if (params != null && !params.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) url.append('&');
                first = false;
                url.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            }
        }
        Map<String, String> h = new HashMap<>();
        h.put("apikey", KEY);
        h.put("Authorization", "Bearer " + KEY);
        h.put("Accept", "application/json");
        Http.Resp r = Http.once(url.toString(), h);
        if (!r.ok() || r.text == null) throw new IOException(fn + " HTTP " + r.code);
        try {
            return new JSONObject(r.text);
        } catch (Exception e) {
            throw new IOException(fn + " 返回的不是 JSON");
        }
    }

    /* ---------------- 远程配置 ---------------- */

    /** 拉播放节点配置，失败就算了，用内置的。 */
    public static void syncRemoteConfig() {
        try {
            Http.Resp r = Http.get(RC_URL, null, Http.UA);
            if (!r.ok() || r.text == null) return;
            JSONObject j = new JSONObject(r.text);
            JSONObject data = j.optJSONObject("data");
            if (data == null) return;
            JSONObject play = data.optJSONObject("play_api");
            if (play == null) return;
            String u = play.optString("url", "");
            if (u.length() > 0) {
                while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                Prefs.setPlayHost(u);
            }
        } catch (Exception ignored) {
        }
    }
}

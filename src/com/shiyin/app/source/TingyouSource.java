package com.shiyin.app.source;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Cat;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 听友听书（tingyou.fm）：网站自己的 JSON 接口，不需要登录。
 *
 * 两个坑：
 *  1) 接口域名会换 —— 先试已知的，都不行就到网站首页里正则找当前的；
 *  2) 接口只认从网站页面发出的请求（校验 Origin/Referer + 会话头）。
 */
public class TingyouSource extends Source {

    private static final String SITE = "https://tingyou.fm";
    private static final String[] BASES = {
            "https://api-preview.toulaopao.cc/api",
            "https://appp.fdhtbz.cn/api",
            "https://laopaoaappi.oobyvy.vip/api"
    };
    private static final Pattern DISCOVER =
            Pattern.compile("https://[a-z0-9.\\-]+/api(?=[\"\\\\/])", Pattern.CASE_INSENSITIVE);

    private static String working = null;
    private static String session = null;
    private static JSONObject filters = null;

    private static synchronized String session() {
        if (session == null) session = UUID.randomUUID().toString();
        return session;
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Origin", SITE);
        h.put("Referer", SITE + "/");
        h.put("Accept", "application/json");
        h.put("x-listening-session", session());
        h.put("x-listening-timezone", "Asia/Shanghai");
        return h;
    }

    private static String discover() {
        try {
            String html = Http.getString(SITE + "/listening");
            Matcher m = DISCOVER.matcher(html);
            return m.find() ? m.group() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 调接口，自动换域名。allowEmpty：翻过最后一页返回空内容不算错。 */
    private static JSONObject call(String path, JSONObject body, boolean allowEmpty)
            throws Exception {
        List<String> list = new ArrayList<>();
        if (working != null) list.add(working);
        for (String b : BASES) if (!list.contains(b)) list.add(b);

        Set<String> tried = new HashSet<>();
        Exception last = null;
        for (int i = 0; i <= list.size(); i++) {
            String base = i < list.size() ? list.get(i) : discover();
            if (base == null || tried.contains(base)) continue;
            tried.add(base);
            try {
                String url = base + "/h5/listening/" + path;
                Http.Resp r;
                if (body != null) {
                    r = Http.post(url, body.toString(), "application/json", headers());
                } else {
                    r = Http.request("GET", url, headers(), null, null, Http.UA);
                }
                String text = r.text == null ? "" : r.text.trim();
                if (text.length() == 0 && allowEmpty) return new JSONObject();
                if (!r.ok()) throw new Exception("HTTP " + r.code);
                JSONObject j = new JSONObject(text);
                if (j.has("error") && !j.isNull("error")) {
                    String msg = j.optString("message", "接口返回错误");
                    throw new Exception(msg);
                }
                working = base;
                return j;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new Exception("源3 的接口现在访问不了："
                + (last == null ? "没有可用的地址" : last.getMessage()));
    }

    private static Book toBook(JSONObject a) {
        Book b = new Book();
        b.source = "tingyou";
        b.id = a.optString("id", "");
        b.bookUrl = SITE + "/listening/album/" + b.id;
        b.title = a.optString("title", "").trim();
        b.author = a.optString("author", "");
        b.narrator = a.optString("teller", "");
        b.cover = a.optString("cover_url", "");
        String intro = a.optString("intro", "");
        if (intro.length() == 0) intro = a.optString("description", "");
        if (intro.length() == 0) intro = a.optString("synopsis", "");
        b.desc = intro;
        int count = a.optInt("count", 0);
        b.songNum = count;
        b.status = (a.optInt("status", 1) == 0 ? "已完结" : "连载中")
                + (count > 0 ? " · " + count + " 集" : "");
        return b;
    }

    private static final String[] SORT_KEYS = {"comprehensive", "popular", "updated", "new"};

    @Override
    public String id() {
        return "tingyou";
    }

    @Override
    public String name() {
        return "源3";
    }

    @Override
    public String desc() {
        return "有声小说和评书比较全，更新快，不需要登录";
    }

    @Override
    public List<Book> search(String keyword, int page) throws Exception {
        JSONObject body = new JSONObject();
        body.put("keyword", keyword);
        body.put("page", page);
        JSONObject j = call("search", body, false);
        List<Book> out = new ArrayList<>();
        JSONArray a = j.optJSONArray("results");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(toBook(o));
            }
        }
        return out;
    }

    @Override
    public List<Cat> cats() throws Exception {
        List<Cat> menus = new ArrayList<>();

        Cat rec = new Cat("tingyou", "rank", "推荐");
        rec.icon = "🔥";
        rec.children.add(new Cat("tingyou", "rank:latest", "最新"));
        rec.children.add(new Cat("tingyou", "rank:hot", "热门"));
        rec.children.add(new Cat("tingyou", "rank:recommend", "推荐"));
        rec.children.add(new Cat("tingyou", "rank:serial", "连载"));
        menus.add(rec);

        try {
            if (filters == null) filters = call("filters", null, false);
            JSONArray cs = filters.optJSONArray("categories");
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject c = cs.optJSONObject(i);
                    if (c == null) continue;
                    String cid = c.optString("id");
                    Cat root = new Cat("tingyou", "cat:" + cid, c.optString("name"));
                    root.icon = "📚";
                    root.children.add(new Cat("tingyou", "cat:" + cid, "全部"));
                    JSONArray ts = c.optJSONArray("types");
                    if (ts != null) {
                        for (int k = 0; k < ts.length(); k++) {
                            JSONObject t = ts.optJSONObject(k);
                            if (t == null) continue;
                            root.children.add(new Cat("tingyou",
                                    "cat:type=" + t.optString("id"), t.optString("name")));
                        }
                    }
                    menus.add(root);
                }
            }
        } catch (Exception ignored) {
            // 分类拿不到不影响搜索和推荐
        }
        return menus;
    }

    @Override
    public Page page(Cat cat, int pn) throws Exception {
        Page p = new Page();
        p.page = pn;
        String key = cat.id;

        if (key.startsWith("rank:")) {
            String section = key.substring(5);
            JSONObject r = call("rank?section=" + section + "&page=" + pn, null, true);
            JSONArray a = r.optJSONArray("items");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) p.books.add(toBook(o));
                }
            }
            boolean more = r.optBoolean("has_more", false);
            int size = r.optInt("page_size", 30);
            if (size <= 0) size = 30;
            int total = r.optInt("total", 0);
            p.totalPage = more ? Math.max(pn + 1, (int) Math.ceil(total / (double) size)) : pn;
        } else {
            // cat:type=xx 或 cat:category=xx
            String query = key.startsWith("cat:") ? key.substring(4) : key;
            String sort = SORT_KEYS[1]; // 默认播放最多
            JSONObject c = call("category?sort=" + sort + "&" + query + "&page=" + pn, null, true);
            JSONArray a = c.optJSONArray("data");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) p.books.add(toBook(o));
                }
            }
            p.totalPage = Math.max(pn, c.optInt("pages", pn));
        }
        if (p.books.isEmpty()) p.totalPage = pn;
        return p;
    }

    @Override
    public Detail detail(Book book, boolean withEpisodes) throws Exception {
        String id = book.id;
        if (id == null || id.length() == 0) {
            Matcher m = Pattern.compile("album/(\\d+)").matcher(book.bookUrl);
            if (m.find()) id = m.group(1);
        }
        JSONObject a = call("album/" + id, null, false);
        if (!a.optBoolean("available", true)) throw new Exception("这本书已经下架了");

        Detail d = new Detail();
        Book meta = toBook(a);
        if (meta.title.length() == 0) meta.title = book.title;
        if (meta.cover.length() == 0) meta.cover = book.cover;
        d.meta = meta;

        if (withEpisodes) {
            JSONObject c = call("chapters/" + id, null, false);
            JSONArray a2 = c.optJSONArray("chapters");
            if (a2 != null) {
                for (int i = 0; i < a2.length(); i++) {
                    JSONObject o = a2.optJSONObject(i);
                    if (o == null) continue;
                    Chapter ch = new Chapter();
                    ch.bookId = id;
                    ch.index = o.optInt("index", i + 1);
                    ch.title = o.optString("title", "").trim();
                    if (ch.title.length() == 0) ch.title = "第 " + ch.index + " 集";
                    ch.url = SITE + "/listening/album/" + id + "#" + ch.index;
                    d.chapters.add(ch);
                }
            }
        }
        return d;
    }

    @Override
    public String audio(Chapter chapter) throws Exception {
        Matcher m = Pattern.compile("album/(\\d+)#(\\d+)$").matcher(chapter.url);
        String albumId;
        int idx;
        if (m.find()) {
            albumId = m.group(1);
            idx = Integer.parseInt(m.group(2));
        } else {
            albumId = chapter.bookId;
            idx = chapter.index;
        }
        JSONObject body = new JSONObject();
        body.put("album_id", albumId);
        body.put("chapter_idx", idx);
        JSONObject j = call("play", body, false);
        String play = j.optString("play_url", "");
        if (play.length() == 0) {
            String msg = j.optString("detail", j.optString("message", "没有拿到播放地址"));
            throw new Exception(msg);
        }
        return play;
    }
}

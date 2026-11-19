package com.shiyin.app.store;

import android.content.Context;
import android.content.SharedPreferences;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.source.Source;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 本地存储：书架、播放进度、设置。
 *
 * 没有数据库 —— 书架几十本、进度几百条，SharedPreferences 里塞 JSON 完全够，
 * 还省掉一个 SQLite 的样板代码。
 */
public final class Prefs {

    private static final String FILE = "shiyin";
    private static final String PAGE_FILE = "shiyin_chapter_pages";
    private static volatile SharedPreferences sp;
    private static volatile SharedPreferences pageSp;

    public static void init(Context ctx) {
        Context app = ctx.getApplicationContext();
        sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        pageSp = app.getSharedPreferences(PAGE_FILE, Context.MODE_PRIVATE);
    }

    private Prefs() {
    }

    /* ---------------- 设置 ---------------- */

    public static String quality() {
        return sp.getString("quality", "128");
    }

    public static void setQuality(String q) {
        sp.edit().putString("quality", q).apply();
    }

    /** 播放节点，远程配置会改它；拿不到就用内置默认。 */
    public static String playHost() {
        return sp.getString("playHost", "");
    }

    public static void setPlayHost(String host) {
        if (host != null && host.length() > 0) {
            sp.edit().putString("playHost", host).apply();
        }
    }

    public static long lastSync() {
        return sp.getLong("lastSync", 0L);
    }

    public static void setLastSync(long t) {
        sp.edit().putLong("lastSync", t).apply();
    }

    /* ---------------- 订阅源 ---------------- */

    private static final String SUB_URLS = "sub.urls";

    private static String subKey(String url) {
        return "sub.body." + Integer.toHexString(url.hashCode()) + "." + url.length();
    }

    private static String subStampKey(String url) {
        return "sub.at." + Integer.toHexString(url.hashCode()) + "." + url.length();
    }

    /** 已添加的订阅地址，保持添加顺序。 */
    public static List<String> subscriptionUrls() {
        List<String> out = new ArrayList<>();
        String raw = sp.getString(SUB_URLS, "");
        if (raw == null || raw.length() == 0) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String url = a.optString(i, "");
                if (url.length() > 0 && !out.contains(url)) out.add(url);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static String subscription(String url) {
        return url == null ? "" : sp.getString(subKey(url), "");
    }

    /** 订阅缓存的写入时间；0 表示老版本留下的缓存，没有时间戳。 */
    public static long subscriptionSavedAt(String url) {
        if (url == null || url.length() == 0) return 0L;
        return sp.getLong(subStampKey(url), 0L);
    }

    public static void putSubscription(String url, String json) {
        if (url == null || url.length() == 0) return;
        List<String> urls = subscriptionUrls();
        if (!urls.contains(url)) urls.add(url);
        JSONArray a = new JSONArray();
        for (String u : urls) a.put(u);
        sp.edit().putString(SUB_URLS, a.toString())
                .putString(subKey(url), json == null ? "" : json)
                .putLong(subStampKey(url), System.currentTimeMillis()).apply();
    }

    public static void removeSubscription(String url) {
        if (url == null || url.length() == 0) return;
        List<String> urls = subscriptionUrls();
        urls.remove(url);
        JSONArray a = new JSONArray();
        for (String u : urls) a.put(u);
        sp.edit().putString(SUB_URLS, a.toString())
                .remove(subKey(url)).remove(subStampKey(url)).apply();
    }

    public static boolean bootReminder() { return sp.getBoolean("bootReminder", false); }

    public static void setBootReminder(boolean enabled) {
        sp.edit().putBoolean("bootReminder", enabled).apply();
    }

    public static boolean backgroundHintShown() { return sp.getBoolean("backgroundHintShown", false); }

    public static void markBackgroundHintShown() {
        sp.edit().putBoolean("backgroundHintShown", true).apply();
    }

    public static void rememberLastBook(Book book) {
        try {
            JSONObject data = new JSONObject();
            data.put("source", book.source);
            data.put("id", book.id);
            data.put("title", book.title);
            data.put("cover", book.cover);
            data.put("author", book.author);
            data.put("narrator", book.narrator);
            data.put("bookUrl", book.bookUrl);
            sp.edit().putString("lastBook", data.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static Book lastBook() {
        try {
            JSONObject data = new JSONObject(sp.getString("lastBook", ""));
            Book book = new Book(data.optString("source"), data.optString("id"), data.optString("title"));
            book.cover = data.optString("cover");
            book.author = data.optString("author");
            book.narrator = data.optString("narrator");
            book.bookUrl = data.optString("bookUrl");
            return book.valid() ? book : null;
        } catch (Exception ignored) { return null; }
    }

    /* ---------------- 书架 ---------------- */

    public static List<Book> shelf() {
        List<Book> out = new ArrayList<>();
        String raw = sp.getString("shelf", "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Book b = new Book();
                b.id = o.optString("id");
                b.source = o.optString("source");
                b.title = o.optString("title");
                b.author = o.optString("author");
                b.narrator = o.optString("narrator");
                b.cover = o.optString("cover");
                b.desc = o.optString("desc");
                b.status = o.optString("status");
                b.songNum = o.optInt("songNum");
                b.bookUrl = o.optString("bookUrl");
                out.add(b);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveShelf(List<Book> list) {
        JSONArray a = new JSONArray();
        for (Book b : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", b.id);
                o.put("source", b.source);
                o.put("title", b.title);
                o.put("author", b.author);
                o.put("narrator", b.narrator);
                o.put("cover", b.cover);
                o.put("desc", b.desc);
                o.put("status", b.status);
                o.put("songNum", b.songNum);
                o.put("bookUrl", b.bookUrl);
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString("shelf", a.toString()).apply();
    }

    public static boolean inShelf(Book b) {
        for (Book x : shelf()) {
            if (same(x, b)) return true;
        }
        return false;
    }

    public static boolean toggleShelf(Book b) {
        List<Book> list = shelf();
        for (int i = 0; i < list.size(); i++) {
            if (same(list.get(i), b)) {
                list.remove(i);
                saveShelf(list);
                return false;
            }
        }
        list.add(0, b);
        saveShelf(list);
        return true;
    }

    private static boolean same(Book a, Book b) {
        return a != null && b != null && eq(a.source, b.source) && eq(a.id, b.id);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /* ---------------- 播放进度 ---------------- */

    public static final class Prog {
        public int chapterIndex;
        public int position;     // 毫秒
        public int duration;     // 毫秒
        public long updated;
        public String chapterTitle = "";
    }

    public static Prog progress(String source, String bookId) {
        String raw = sp.getString("p." + source + "." + bookId, null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Prog p = new Prog();
            p.chapterIndex = o.optInt("ci");
            p.position = o.optInt("pos");
            p.duration = o.optInt("dur");
            p.updated = o.optLong("t");
            p.chapterTitle = o.optString("ct");
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    public static void saveProgress(String source, String bookId, int chapterIndex,
                                    int position, int duration, String chapterTitle) {
        try {
            JSONObject o = new JSONObject();
            o.put("ci", chapterIndex);
            o.put("pos", position);
            o.put("dur", duration);
            o.put("t", System.currentTimeMillis());
            o.put("ct", chapterTitle == null ? "" : chapterTitle);
            sp.edit().putString("p." + source + "." + bookId, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 最近在听的（按更新时间倒序）。 */
    public static List<Book> recent() {
        List<Book> all = shelf();
        final List<Book> withTime = new ArrayList<>();
        for (Book b : all) {
            Prog p = progress(b.source, b.id);
            if (p != null) withTime.add(b);
        }
        java.util.Collections.sort(withTime, new java.util.Comparator<Book>() {
            @Override
            public int compare(Book a, Book b) {
                Prog pa = progress(a.source, a.id);
                Prog pb = progress(b.source, b.id);
                long ta = pa == null ? 0 : pa.updated;
                long tb = pb == null ? 0 : pb.updated;
                return Long.compare(tb, ta);
            }
        });
        return withTime;
    }

    /* ---------------- 搜索历史 ---------------- */

    public static List<String> searchHistory() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("sh", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void addSearchHistory(String kw) {
        if (kw == null || kw.trim().length() == 0) return;
        List<String> list = searchHistory();
        list.remove(kw);
        list.add(0, kw);
        while (list.size() > 12) list.remove(list.size() - 1);
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        sp.edit().putString("sh", a.toString()).apply();
    }

    public static void clearSearchHistory() {
        sp.edit().putString("sh", "[]").apply();
    }

    /* ---------------- 源开关 ---------------- */

    public static boolean sourceEnabled(String id) {
        return sp.getBoolean("src." + id, true);
    }

    public static void setSourceEnabled(String id, boolean on) {
        sp.edit().putBoolean("src." + id, on).apply();
    }

    /* ---------------- 章节缓存（详情页离线可看目录） ---------------- */

    public static void cacheChapters(String source, String bookId, List<Chapter> list) {
        if (list == null) return;
        JSONArray a = new JSONArray();
        for (Chapter c : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("t", c.title);
                o.put("u", c.url);
                o.put("i", c.index);
                o.put("id", c.id);
                o.put("dur", c.duration);
                o.put("paid", c.paid);
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        String key = "ch." + source + "." + bookId;
        sp.edit().putString(key, a.toString())
                .putLong("cht." + source + "." + bookId, System.currentTimeMillis()).apply();
    }

    public static List<Chapter> cachedChapters(String source, String bookId) {
        List<Chapter> out = new ArrayList<>();
        String raw = sp.getString("ch." + source + "." + bookId, null);
        if (raw == null) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Chapter c = new Chapter();
                c.title = o.optString("t");
                c.url = o.optString("u");
                c.index = o.optInt("i", i);
                c.id = o.optString("id", "");
                c.duration = o.optInt("dur", 0);
                c.paid = o.optBoolean("paid", false);
                c.bookId = bookId;
                // 章节没有任何标识就没法取播放地址，跳过。
                if (c.id.isEmpty() && c.url.isEmpty()) continue;
                out.add(c);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /* ---------------- 分页目录缓存 ---------------- */

    public static final long CHAPTER_PAGE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_BOOK_PAGES = 24;
    private static final int MAX_PAGES = 96;
    private static final Object PAGE_CACHE_LOCK = new Object();

    public static final class CachedChapterPage {
        public final Source.ChapterPage page;
        public final long savedAt;

        private CachedChapterPage(Source.ChapterPage page, long savedAt) {
            this.page = page;
            this.savedAt = savedAt;
        }
    }

    public static void cacheChapterPage(Book book, Source.ChapterPage page, long savedAt) {
        SharedPreferences pages = pageSp;
        if (pages == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("page", page.page);
            data.put("total", page.total);
            data.put("hasMore", page.hasMore);
            data.put("networkPaged", page.networkPaged);
            if (page.meta != null) data.put("meta", chapterPageMeta(page.meta));
            JSONArray chapters = new JSONArray();
            for (Chapter c : page.chapters) {
                JSONObject chapter = new JSONObject();
                chapter.put("t", c.title);
                chapter.put("u", c.url);
                chapter.put("i", c.index);
                chapter.put("id", c.id);
                chapter.put("b", c.bookId);
                chapter.put("dur", c.duration);
                chapter.put("paid", c.paid);
                chapters.put(chapter);
            }
            data.put("chapters", chapters);
            String bookPrefix = chapterPageBookPrefix(book);
            String key = bookPrefix + page.page;
            String value = savedAt + "\n" + data;
            synchronized (PAGE_CACHE_LOCK) {
                SharedPreferences.Editor editor = pages.edit().putString(key, value);
                List<PageStamp> stamps = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (Map.Entry<String, ?> entry : pages.getAll().entrySet()) {
                    if (key.equals(entry.getKey())) continue;
                    long written = pageStamp(entry.getValue());
                    if (validChapterPageTime(written, now)) {
                        stamps.add(new PageStamp(entry.getKey(), written));
                    } else {
                        editor.remove(entry.getKey());
                    }
                }
                Collections.sort(stamps, (a, b) -> Long.compare(b.savedAt, a.savedAt));
                int bookPages = 1;
                int totalPages = 1;
                for (PageStamp stamp : stamps) {
                    boolean sameBook = stamp.key.startsWith(bookPrefix);
                    if (totalPages >= MAX_PAGES || (sameBook && bookPages >= MAX_BOOK_PAGES)) {
                        editor.remove(stamp.key);
                    } else {
                        totalPages++;
                        if (sameBook) bookPages++;
                    }
                }
                editor.apply();
            }
        } catch (Exception ignored) {
            // 缓存失败不影响已成功加载的目录。
        }
    }

    public static CachedChapterPage cachedChapterPage(Book book, int page) {
        SharedPreferences pages = pageSp;
        if (pages == null) return null;
        try {
            String raw = pages.getString(chapterPageBookPrefix(book) + page, null);
            long savedAt = pageStamp(raw);
            if (!validChapterPageTime(savedAt, System.currentTimeMillis())) return null;
            JSONObject data = new JSONObject(raw.substring(raw.indexOf('\n') + 1));
            if (data.getInt("page") != page) return null;
            Source.ChapterPage result = new Source.ChapterPage();
            result.page = page;
            result.total = data.getInt("total");
            result.hasMore = data.getBoolean("hasMore");
            result.networkPaged = data.getBoolean("networkPaged");
            result.meta = chapterPageMeta(data.optJSONObject("meta"));
            JSONArray chapters = data.getJSONArray("chapters");
            long offset = (long) page * Source.CHAPTER_PAGE_SIZE;
            if (page < 0 || chapters.length() > Source.CHAPTER_PAGE_SIZE) return null;
            for (int i = 0; i < chapters.length(); i++) {
                JSONObject item = chapters.getJSONObject(i);
                Chapter chapter = new Chapter(item.getString("t"), item.optString("u"));
                chapter.index = item.getInt("i");
                if (chapter.index != offset + i) return null;
                chapter.id = item.optString("id");
                chapter.bookId = item.optString("b", book.id);
                chapter.duration = item.optInt("dur");
                chapter.paid = item.getBoolean("paid");
                result.chapters.add(chapter);
            }
            return new CachedChapterPage(result, savedAt);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 旧版只缓存整本目录。只读取非空且逐条有效的记录，不用它阻止在线刷新。 */
    public static CachedChapterPage cachedLegacyChapterPage(Book book, int page) {
        SharedPreferences legacy = sp;
        if (legacy == null || page < 0) return null;
        try {
            String key = book.source + "." + book.id;
            String raw = legacy.getString("ch." + key, null);
            if (raw == null) return null;
            long now = System.currentTimeMillis();
            String stampKey = "cht." + key;
            long savedAt = legacy.getLong(stampKey, 0);
            if (savedAt != 0 && !validChapterPageTime(savedAt, now)) return null;
            JSONArray stored = new JSONArray(raw);
            long offset = (long) page * Source.CHAPTER_PAGE_SIZE;
            if (offset >= stored.length() || (book.songNum > 0 && stored.length() < book.songNum)) {
                return null;
            }
            Source.ChapterPage result = new Source.ChapterPage();
            result.page = page;
            result.total = stored.length();
            int end = (int) Math.min(stored.length(), offset + Source.CHAPTER_PAGE_SIZE);
            result.hasMore = end < result.total;
            for (int i = 0; i < stored.length(); i++) {
                JSONObject item = stored.getJSONObject(i);
                String title = item.optString("t").trim();
                String id = item.optString("id").trim();
                String url = item.optString("u").trim();
                if (title.isEmpty() || (id.isEmpty() && url.isEmpty())) {
                    return null;
                }
                if (i < offset || i >= end) continue;
                Chapter chapter = new Chapter(title, url);
                chapter.index = i;
                chapter.id = id;
                chapter.bookId = book.id;
                chapter.duration = item.optInt("dur");
                chapter.paid = item.optBoolean("paid");
                result.chapters.add(chapter);
            }
            if (savedAt == 0) {
                // 老版本没有时间戳，只能从首次读取时起计算离线保留期。
                synchronized (PAGE_CACHE_LOCK) {
                    savedAt = legacy.getLong(stampKey, 0);
                    if (savedAt == 0) {
                        savedAt = now;
                        legacy.edit().putLong(stampKey, savedAt).apply();
                    }
                }
            }
            return new CachedChapterPage(result, savedAt);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String chapterPageBookPrefix(Book book) {
        String source = book.source == null ? "" : book.source;
        String id = book.id == null || book.id.isEmpty() ? book.bookUrl : book.id;
        if (id == null) id = "";
        return source.length() + ":" + source + id.length() + ":" + id + ":";
    }

    private static boolean validChapterPageTime(long savedAt, long now) {
        return savedAt > 0 && savedAt <= now && now - savedAt <= CHAPTER_PAGE_MAX_AGE_MS;
    }

    private static long pageStamp(Object value) {
        if (!(value instanceof String)) return 0;
        String text = (String) value;
        int end = text.indexOf('\n');
        if (end < 0) return 0;
        try {
            return Long.parseLong(text.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static final class PageStamp {
        final String key;
        final long savedAt;

        PageStamp(String key, long savedAt) {
            this.key = key;
            this.savedAt = savedAt;
        }
    }

    private static JSONObject chapterPageMeta(Book meta) throws org.json.JSONException {
        JSONObject data = new JSONObject();
        data.put("source", meta.source);
        data.put("id", meta.id);
        data.put("title", meta.title);
        data.put("author", meta.author);
        data.put("narrator", meta.narrator);
        data.put("cover", meta.cover);
        data.put("desc", meta.desc);
        data.put("status", meta.status);
        data.put("songNum", meta.songNum);
        data.put("rating", meta.rating);
        data.put("listeners", meta.listeners);
        data.put("bookUrl", meta.bookUrl);
        return data;
    }

    private static Book chapterPageMeta(JSONObject data) {
        if (data == null) return null;
        Book meta = new Book(data.optString("source"), data.optString("id"), data.optString("title"));
        meta.author = data.optString("author");
        meta.narrator = data.optString("narrator");
        meta.cover = data.optString("cover");
        meta.desc = data.optString("desc");
        meta.status = data.optString("status");
        meta.songNum = data.optInt("songNum");
        meta.rating = (float) data.optDouble("rating", 0);
        meta.listeners = data.optString("listeners");
        meta.bookUrl = data.optString("bookUrl");
        return meta;
    }
}

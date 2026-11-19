package com.shiyin.app.source;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.store.Prefs;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;

/** 固定大小目录页；内存/磁盘缓存保留总数与后续页状态。网络请求不持全局锁。 */
public final class ChapterRepository {
    private static final long FRESH_MS = 10 * 60 * 1000L;
    private static final long OFFLINE_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final LinkedHashMap<List<Object>, Entry> CACHE =
            new LinkedHashMap<>(24, 0.75f, true);
    private static final Map<List<Object>, FutureTask<Entry>> PENDING = new LinkedHashMap<>();
    private static final class Entry {
        final Source.ChapterPage page;
        final long savedAt;
        Entry(Source.ChapterPage page, long savedAt) { this.page = page; this.savedAt = savedAt; }
    }
    private ChapterRepository() { }

    private static List<Object> key(Book book, int page) {
        Source.chapterOffset(page);
        if (book == null) throw new IllegalArgumentException("缺少书籍");
        return Arrays.<Object>asList(book.source, Source.bookIdentity(book), page);
    }
    private static boolean valid(Entry entry, long age) {
        long elapsed = entry == null ? Long.MAX_VALUE : System.currentTimeMillis() - entry.savedAt;
        return entry != null && elapsed >= 0 && elapsed <= age;
    }
    private static void remember(List<Object> key, Entry entry) {
        synchronized (CACHE) {
            CACHE.put(key, entry);
            while (CACHE.size() > 48) CACHE.remove(CACHE.keySet().iterator().next());
        }
    }
    private static Entry read(Book book, int page) {
        List<Object> key = key(book, page);
        synchronized (CACHE) {
            Entry cached = CACHE.get(key);
            if (valid(cached, OFFLINE_MS)) return cached;
            CACHE.remove(key);
        }
        Prefs.CachedChapterPage disk = Prefs.cachedChapterPage(book, page);
        Entry found = disk == null ? null : new Entry(disk.page, disk.savedAt);
        if (valid(found, OFFLINE_MS)) { remember(key, found); return found; }
        return null;
    }
    public static Source.ChapterPage cached(Book book, int page) {
        Entry entry = read(book, page);
        return entry == null ? null : copy(entry.page);
    }
    public static Source.ChapterPage load(Book book, int page) throws Exception {
        Entry entry = read(book, page);
        if (valid(entry, FRESH_MS)) return copy(entry.page);
        try {
            return fetch(book, page, false);
        } catch (Exception failure) {
            // 已有页断网仍可播；旧完整缓存只在后台网络失败时读取。
            if (entry != null) return copy(entry.page);
            Prefs.CachedChapterPage legacy = Prefs.cachedLegacyChapterPage(book, page);
            if (legacy != null) return copy(legacy.page);
            throw failure;
        }
    }
    public static Source.ChapterPage refresh(Book book, int page) throws Exception {
        return fetch(book, page, true);
    }
    private static Source.ChapterPage fetch(Book book, int page, boolean force) throws Exception {
        final List<Object> key = key(book, page);
        final Book requestBook = Source.copyBook(book);
        FutureTask<Entry> task;
        boolean owner = false;
        synchronized (CACHE) {
            Entry current = CACHE.get(key);
            if (!force && valid(current, FRESH_MS)) return copy(current.page);
            task = PENDING.get(key);
            if (task == null) {
                task = new FutureTask<>(() -> {
                    Source source = Sources.forBook(requestBook);
                    if (source == null) throw new IOException("这个内容源没有安装，请到「我的 → 内容源」添加订阅");
                    Source.ChapterPage result = source.chapterPage(requestBook, page);
                    validate(result, page);
                    Source.ChapterPage snapshot = copy(result);
                    Entry entry = new Entry(snapshot, System.currentTimeMillis());
                    Prefs.cacheChapterPage(requestBook, snapshot, entry.savedAt);
                    remember(key, entry);
                    return entry;
                });
                PENDING.put(key, task);
                owner = true;
            }
        }
        try {
            if (owner) task.run();
            return copy(Source.await(task).page);
        } finally {
            if (owner) synchronized (CACHE) { PENDING.remove(key); }
        }
    }
    private static void validate(Source.ChapterPage page, int expected) throws IOException {
        if (page == null || page.page != expected || page.chapters == null
                || page.chapters.size() > Source.CHAPTER_PAGE_SIZE) {
            throw new IOException("目录分页响应异常");
        }
        int offset = expected * Source.CHAPTER_PAGE_SIZE;
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < page.chapters.size(); i++) {
            Chapter c = page.chapters.get(i);
            if (c == null) throw new IOException("章节数据异常");
            c.index = offset + i;
            String identity = c.id == null || c.id.isEmpty() ? c.url : c.id;
            if (identity != null && !identity.isEmpty() && !ids.add(identity)) {
                throw new IOException("目录含重复章节，请重试");
            }
        }
        if (page.total >= 0 && !page.chapters.isEmpty() && (long) offset + page.chapters.size() > page.total) {
            throw new IOException("章节总数不一致，请重试");
        }
    }
    private static Source.ChapterPage copy(Source.ChapterPage original) {
        Source.ChapterPage out = new Source.ChapterPage();
        out.meta = Source.copyBook(original.meta);
        out.page = original.page;
        out.total = original.total;
        out.hasMore = original.hasMore;
        out.networkPaged = original.networkPaged;
        for (Chapter c : original.chapters) out.chapters.add(Source.copyChapter(c, c.index));
        return out;
    }
}

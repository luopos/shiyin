package com.shiyin.app.source;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Cat;
import com.shiyin.app.model.Chapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * 一个"源"就是一个内容提供方。
 *
 * 原生版只保留**实测能放出声音**的源，不做在线更新 —— 按用户的话："坏了再修"。
 * 现在的清单：
 *   lrts     听书吧（源1，JSON 边缘函数，量最大）
 *   kuwo     酷我（源2，JSON 边缘函数）
 *   tingyou  听友听书（自家 JSON 接口，实测可播）
 *   podcast  Apple 播客（iTunes 搜索 + RSS，实测可播）
 */
public abstract class Source {

    public static final int CHAPTER_PAGE_SIZE = 50;
    private static final int DETAIL_CACHE_LIMIT = 3;
    private static final long DETAIL_TTL_NS = 10L * 60 * 1_000_000_000;
    private static final Map<List<Object>, FullDetail> FULL_DETAILS = new LinkedHashMap<>(4, 0.75f, true);
    private static final Map<List<Object>, FutureTask<FullDetail>> FULL_PENDING = new HashMap<>();

    public abstract String id();

    public abstract String name();

    public String desc() {
        return "";
    }

    /** 有没有搜索。没有的话搜索页不列它。 */
    public boolean searchable() {
        return true;
    }

    /** 有没有分类。 */
    public boolean hasCats() {
        return true;
    }

    public List<Book> search(String keyword, int page) throws Exception {
        return new ArrayList<>();
    }

    /** 根分类（子分类在 Cat.children 里）。 */
    public List<Cat> cats() throws Exception {
        return new ArrayList<>();
    }

    public Page page(Cat cat, int pn) throws Exception {
        return new Page();
    }

    public Detail detail(Book book, boolean withEpisodes) throws Exception {
        return new Detail(book, new ArrayList<Chapter>());
    }

    /** 没有原生分页的源只取一次完整目录，再按全局位置切成固定大小的页。 */
    public ChapterPage chapterPage(Book book, int page) throws Exception {
        long offset = chapterOffset(page);
        FullDetail full = fullDetail(book);
        ChapterPage out = new ChapterPage();
        out.page = page;
        out.meta = copyBook(full.detail.meta);
        out.total = full.detail.chapters.size();
        int to = (int) Math.min(out.total, offset + CHAPTER_PAGE_SIZE);
        for (int i = (int) Math.min(offset, out.total); i < to; i++) {
            out.chapters.add(copyChapter(full.detail.chapters.get(i), i));
        }
        out.hasMore = to < out.total;
        return out;
    }

    private FullDetail fullDetail(Book book) throws Exception {
        if (book == null) throw new IllegalArgumentException("缺少书籍");
        List<Object> key = Arrays.<Object>asList(this, bookIdentity(book));
        FutureTask<FullDetail> request;
        boolean owner = false;
        synchronized (FULL_DETAILS) {
            FullDetail cached = FULL_DETAILS.get(key);
            if (cached != null) {
                if (System.nanoTime() - cached.savedAt < DETAIL_TTL_NS) return cached;
                FULL_DETAILS.remove(key);
            }
            request = FULL_PENDING.get(key);
            if (request == null) {
                request = new FutureTask<>(() -> {
                    Detail fetched = detail(copyBook(book), true);
                    if (fetched == null || fetched.chapters == null) {
                        throw new IOException("未收到章节目录");
                    }
                    Detail snapshot = new Detail();
                    snapshot.meta = copyBook(fetched.meta == null ? book : fetched.meta);
                    for (Chapter c : fetched.chapters) {
                        if (c == null) throw new IOException("章节目录格式异常");
                        snapshot.chapters.add(copyChapter(c, snapshot.chapters.size()));
                    }
                    FullDetail full = new FullDetail(snapshot);
                    synchronized (FULL_DETAILS) {
                        FULL_DETAILS.put(key, full);
                        while (FULL_DETAILS.size() > DETAIL_CACHE_LIMIT) {
                            FULL_DETAILS.remove(FULL_DETAILS.keySet().iterator().next());
                        }
                    }
                    return full;
                });
                FULL_PENDING.put(key, request);
                owner = true;
            }
        }
        try {
            if (owner) request.run();
            return await(request);
        } finally {
            if (owner) {
                synchronized (FULL_DETAILS) {
                    FULL_PENDING.remove(key);
                }
            }
        }
    }

    static String bookIdentity(Book book) {
        return book.id == null || book.id.isEmpty() ? book.bookUrl : book.id;
    }

    static long chapterOffset(int page) {
        if (page < 0 || (long) page * CHAPTER_PAGE_SIZE > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("目录页码无效");
        }
        return (long) page * CHAPTER_PAGE_SIZE;
    }

    static <T> T await(FutureTask<T> request) throws Exception {
        try {
            return request.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        }
    }

    static Book copyBook(Book book) {
        if (book == null) return null;
        Book out = new Book(book.source, book.id, book.title);
        out.author = book.author;
        out.narrator = book.narrator;
        out.cover = book.cover;
        out.desc = book.desc;
        out.status = book.status;
        out.songNum = book.songNum;
        out.rating = book.rating;
        out.listeners = book.listeners;
        out.bookUrl = book.bookUrl;
        return out;
    }

    static Chapter copyChapter(Chapter chapter, int index) {
        Chapter out = new Chapter(chapter.title, chapter.url);
        out.id = chapter.id;
        out.bookId = chapter.bookId;
        out.index = index;
        out.duration = chapter.duration;
        out.paid = chapter.paid;
        return out;
    }

    private static final class FullDetail {
        final Detail detail;
        final long savedAt = System.nanoTime();

        FullDetail(Detail detail) {
            this.detail = detail;
        }
    }

    /** 章节 → 真实音频地址。 */
    public abstract String audio(Chapter chapter) throws Exception;

    /* ---------------- 返回容器 ---------------- */

    public static class Page {
        public List<Book> books = new ArrayList<>();
        public int page = 1;
        public int totalPage = 1;
    }

    public static class ChapterPage {
        public Book meta;
        public List<Chapter> chapters = new ArrayList<>();
        public int page;
        public int total = -1;
        public boolean hasMore;
        public boolean networkPaged;
    }

    public static class Detail {
        public Book meta;
        public List<Chapter> chapters = new ArrayList<>();

        public Detail() {
        }

        public Detail(Book b, List<Chapter> c) {
            this.meta = b;
            this.chapters = c;
        }
    }
}

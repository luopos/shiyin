package com.shiyin.app.kid;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Cat;
import com.shiyin.app.source.Source;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 青少年模式下的搜索。
 *
 * <p>为什么不用全站搜索接口：实测源站的 search 只吃 {@code kw} 和 {@code page}，
 * 加 {@code cid}/{@code cat} 参数**不会真正过滤**（cid=63 搜「都市」照样返回都市爽文），
 * 返回字段里也没有分类信息，详情接口同样没有。也就是说，青少年模式下拿不到
 * 「这本书属于哪个分类」的证据，只能反过来做：
 *
 * <p><b>只在白名单叶子分类里翻页，本地按关键词匹配。</b>
 * 这样能搜到的书，必然是白名单分类里的书，从根上杜绝跨分类泄漏。
 *
 * <p>因为要翻多个分类，整体耗时明显比全站搜索长。所以提供增量版本：
 * <b>每翻完一页就回调一次</b>，UI 可以边搜边显示，并给出真实进度；
 * 全部扫完再回调 {@link Listener#onDone}。
 */
public final class KidSearch {

    /** 每个白名单分类最多翻几页。调大会更慢，但能覆盖更深的书目。 */
    public static final int MAX_PAGES_PER_CAT = 2;

    private KidSearch() {
    }

    /** 增量搜索回调。在后台线程触发，实现方自己切主线程。 */
    public interface Listener {
        /**
         * 扫完一页。
         *
         * @param hits     累计命中的书（只增不减，已去重、已过 {@link KidMode#allowsBook}）
         * @param done     已完成的请求数
         * @param total    预计总请求数（分类数 × 每类页数上限）
         * @param lastPage 刚扫完的这页是否带来新命中，UI 可据此决定要不要刷新列表
         */
        void onPage(List<Book> hits, int done, int total, boolean lastPage);

        /** 全部扫完；即使 0 命中也会调用，调用方必须给用户反馈。 */
        void onDone(List<Book> hits, boolean anyError);
    }

    /** 阻塞版，保留给测试与不需要进度的场景。 */
    public static List<Book> searchSource(Source source, String keyword) throws Exception {
        return searchSource(source, keyword, MAX_PAGES_PER_CAT);
    }

    public static List<Book> searchSource(Source source, String keyword, int maxPages)
            throws Exception {
        final List<Book> out = new ArrayList<Book>();
        searchSource(source, keyword, maxPages, new Listener() {
            @Override
            public void onPage(List<Book> hits, int done, int total, boolean lastPage) {
            }

            @Override
            public void onDone(List<Book> hits, boolean anyError) {
                out.addAll(hits);
            }
        });
        return out;
    }

    /**
     * 增量搜索。每翻完一页回调 {@link Listener#onPage}，最后一定回调 onDone。
     *
     * <p>非青少年模式、空关键词、无白名单分类会立即 onDone（0 命中），
     * 保证调用方的「无结果」分支永远能走到。
     */
    public static void searchSource(Source source, String keyword, int maxPages,
                                    Listener listener) throws Exception {
        List<Book> hits = new ArrayList<Book>();
        if (listener == null) return;
        if (source == null || keyword == null || !KidMode.enabled()) {
            listener.onDone(hits, false);
            return;
        }
        final String kw = keyword.trim();
        if (kw.length() == 0) {
            listener.onDone(hits, false);
            return;
        }
        List<String> leaves = KidMode.allowedLeafIds(source.id());
        if (leaves.isEmpty()) {
            listener.onDone(hits, false);
            return;
        }

        final int pageCap = Math.max(1, maxPages);
        final int total = leaves.size() * pageCap;
        int done = 0;
        boolean anyError = false;
        Set<String> seen = new HashSet<String>();

        for (int i = 0; i < leaves.size(); i++) {
            String leafId = leaves.get(i);
            // 名字不参与匹配，构造时留空即可；id 才是请求参数。
            Cat cat = new Cat(source.id(), leafId, "");
            int totalPage = pageCap;
            for (int page = 1; page <= pageCap && page <= totalPage; page++) {
                Source.Page result;
                try {
                    result = source.page(cat, page);
                } catch (Exception e) {
                    anyError = true;
                    done += (pageCap - page + 1);
                    break;
                }
                if (result == null) {
                    done += (pageCap - page + 1);
                    break;
                }
                if (result.totalPage > 0) totalPage = Math.min(totalPage, result.totalPage);
                List<Book> books = result.books;
                boolean added = false;
                if (books != null) {
                    for (int j = 0; j < books.size(); j++) {
                        Book book = books.get(j);
                        if (book == null) continue;
                        if (!seen.add(book.source + "\u0000" + book.id)) continue;
                        if (matches(book, kw) && KidMode.allowsBook(book)) {
                            hits.add(book);
                            added = true;
                        }
                    }
                }
                done++;
                listener.onPage(new ArrayList<Book>(hits), done, total, added);
                if (books == null || books.isEmpty()) {
                    // 空页 = 这个分类到底了，把剩余页数记进进度
                    done += (pageCap - page);
                    break;
                }
            }
        }
        listener.onDone(new ArrayList<Book>(hits), anyError);
    }

    /** 标题 / 作者 / 播讲人 任一命中即算命中，大小写不敏感。 */
    public static boolean matches(Book book, String keyword) {
        if (book == null || keyword == null) return false;
        String kw = lowerAscii(keyword.trim());
        if (kw.length() == 0) return false;
        return lowerAscii(book.title).contains(kw)
                || lowerAscii(book.author).contains(kw)
                || lowerAscii(book.narrator).contains(kw);
    }

    /** 只折 ASCII，避免 toLowerCase 在某些 locale 下把中文改坏（与 KidMode 一致）。 */
    private static String lowerAscii(String text) {
        if (text == null) return "";
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c >= 'A' && c <= 'Z') chars[i] = (char) (c + 32);
        }
        return new String(chars);
    }
}
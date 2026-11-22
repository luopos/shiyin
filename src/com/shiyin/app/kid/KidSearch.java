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
 * <p>代价是要多打几次分类接口。每个分类默认翻 {@link #MAX_PAGES_PER_CAT} 页
 * （每页约 50 本），8 个儿童分类约 800 本，足够覆盖常见关键词；
 * 页码上限同时受服务端返回的 {@code totalPage} 约束。
 */
public final class KidSearch {

    /** 每个白名单分类最多翻几页。调大会更慢，但能覆盖更深的书目。 */
    public static final int MAX_PAGES_PER_CAT = 2;

    private KidSearch() {
    }

    /**
     * 在某个源的白名单分类里搜。
     *
     * @return 命中且通过 {@link KidMode#allowsBook(Book)} 的书；非白名单源返回空列表。
     */
    public static List<Book> searchSource(Source source, String keyword) throws Exception {
        return searchSource(source, keyword, MAX_PAGES_PER_CAT);
    }

    public static List<Book> searchSource(Source source, String keyword, int maxPages)
            throws Exception {
        List<Book> out = new ArrayList<Book>();
        if (source == null || keyword == null) return out;
        String kw = keyword.trim();
        if (kw.length() == 0) return out;
        if (!KidMode.enabled()) return out;   // 非青少年模式不该走这条路径

        List<String> leaves = KidMode.allowedLeafIds(source.id());
        if (leaves.isEmpty()) return out;

        int pageCap = Math.max(1, maxPages);
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
                    // 某个分类某一页失败不影响其它分类；这个分类也不继续往下翻。
                    break;
                }
                if (result == null) break;
                if (result.totalPage > 0) totalPage = Math.min(totalPage, result.totalPage);
                List<Book> books = result.books;
                if (books == null || books.isEmpty()) break;
                for (int j = 0; j < books.size(); j++) {
                    Book book = books.get(j);
                    if (book == null) continue;
                    if (!seen.add(book.source + "\u0000" + book.id)) continue;
                    if (matches(book, kw) && KidMode.allowsBook(book)) out.add(book);
                }
            }
        }
        return out;
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
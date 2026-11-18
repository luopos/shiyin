package com.shiyin.app.source;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Cat;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.net.Api;
import com.shiyin.app.net.Http;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;

/** 源2：酷我。分类要同时带根分类和子分类两个 id。 */
public class KuwoSource extends Source {

    @Override
    public String id() {
        return "kuwo";
    }

    @Override
    public String name() {
        return "源2";
    }

    @Override
    public String desc() {
        return "有声书和广播剧，部分付费内容只能试听";
    }

    @Override
    public List<Cat> cats() {
        return Catalog.kuwo();
    }

    @Override
    public List<Book> search(String keyword, int page) throws Exception {
        return Api.search("kuwo", keyword, page).books;
    }

    @Override
    public Page page(Cat cat, int pn) throws Exception {
        // cat 可能是根分类，也可能是子分类。接口要 (classifyId=根, categoryId=子)
        String rootId = cat.id;
        String childId = cat.id;
        // 子分类的 id 在根分类的 children 里能找到
        for (Cat root : Catalog.kuwo()) {
            for (Cat c : root.children) {
                if (c.id.equals(cat.id)) {
                    rootId = root.id;
                    childId = c.id;
                    break;
                }
            }
            if (root.id.equals(cat.id)) {
                rootId = root.id;
                childId = root.children.isEmpty() ? "" : root.children.get(0).id;
                break;
            }
        }
        Api.Page ap = Api.categoryKuwo(rootId, childId, pn, 20);
        Page p = new Page();
        p.books = ap.books;
        p.page = pn;
        p.totalPage = Math.max(1, (int) Math.ceil(ap.total / 20.0));
        return p;
    }

    @Override
    public Detail detail(Book book, boolean withEpisodes) throws Exception {
        Api.Detail d = Api.book("kuwo", book.id);
        Detail out = new Detail();
        out.meta = d.meta == null ? book : d.meta;
        if (out.meta.cover.length() == 0) out.meta.cover = book.cover;
        if (withEpisodes) out.chapters = d.chapters;
        return out;
    }

    /** 服务端每次只返回50集，不再先下载整本目录。 */
    @Override public ChapterPage chapterPage(Book book, int page) throws Exception {
        long offset = chapterOffset(page);
        String url = "https://search.kuwo.cn/r.s?stype=albuminfo&loginUid=0&loginSid=null"
                + "&prod=kwplayer_ar_9.1.7.0&bkprod=kwbook_ar_9.1.7.0"
                + "&source=kwplayer_ar_9.1.7.0_t18.apk&bksource=kwbook_ar_9.1.7.0_t18.apk"
                + "&corp=kuwo&show_copyright_off=1&vipver=MUSIC_8.2.0.0_BCS17"
                + "&mobi=1&sortby=3&iskwbook=1&albumid=" + URLEncoder.encode(book.id, "UTF-8")
                + "&rn=" + CHAPTER_PAGE_SIZE + "&pn=" + page;
        Http.Resp response = Http.get(url, null, Http.UA);
        if (!response.ok()) throw new IOException("目录请求失败 HTTP " + response.code);
        String text = response.text == null ? new String(response.bytes, "UTF-8") : response.text;
        JSONObject json = new JSONObject(text);
        JSONArray rows = json.optJSONArray("musiclist");
        if (rows == null) throw new IOException("目录响应格式异常，请稍后重试");
        ChapterPage out = new ChapterPage();
        out.page = page;
        out.networkPaged = true;
        out.total = json.optInt("songnum", -1);
        out.meta = copyBook(book);
        String name = json.optString("name", "");
        if (!name.isEmpty()) out.meta.title = name;
        String image = json.optString("img", "");
        if (!image.isEmpty()) out.meta.cover = image;
        out.meta.desc = json.optString("info", book.desc);
        if (out.total >= 0) out.meta.songNum = out.total;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw new IOException("章节数据异常");
            Chapter chapter = new Chapter();
            chapter.id = row.optString("musicrid", row.optString("id", ""));
            if (!chapter.id.matches("[0-9]+")) throw new IOException("章节播放标识格式异常");
            chapter.bookId = book.id;
            chapter.url = chapter.id;
            chapter.title = row.optString("name", row.optString("title", ""));
            chapter.duration = row.optInt("duration", 0);
            chapter.index = (int) offset + i;
            chapter.paid = isTrueOrOne(row, "pay") || isTrueOrOne(row, "paid");
            JSONObject pay = row.optJSONObject("payInfo");
            if (pay != null) chapter.paid |= isTrueOrOne(pay, "cannotOnlinePlay")
                    || isTrueOrOne(pay, "listen_fragment");
            out.chapters.add(chapter);
        }
        if (out.total < 0 && rows.length() < CHAPTER_PAGE_SIZE) out.total = (int) offset + rows.length();
        if (out.total >= 0 && offset + rows.length() < out.total
                && rows.length() < CHAPTER_PAGE_SIZE) {
            throw new IOException("目录返回不完整，请稍后重试");
        }
        out.hasMore = out.total >= 0 ? offset + rows.length() < out.total
                : rows.length() == CHAPTER_PAGE_SIZE;
        return out;
    }

    private static boolean isTrueOrOne(JSONObject object, String field) {
        Object value = object.opt(field);
        return Boolean.TRUE.equals(value) || (value instanceof Number && ((Number) value).intValue() == 1)
                || "1".equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    @Override
    public String audio(Chapter chapter) throws Exception {
        Book fake = new Book("kuwo", chapter.bookId, "");
        return Api.playUrl(fake, chapter).url;
    }
}

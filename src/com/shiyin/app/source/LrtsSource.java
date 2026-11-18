package com.shiyin.app.source;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Cat;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.net.Api;

import java.util.List;

/** 源1：听书吧 / lrts。边缘函数返回 JSON，内容量最大。 */
public class LrtsSource extends Source {

    @Override
    public String id() {
        return "lrts";
    }

    @Override
    public String name() {
        return "源1";
    }

    @Override
    public String desc() {
        return "有声小说、评书、儿童、人文，量最大";
    }

    @Override
    public List<Cat> cats() {
        return Catalog.lrts();
    }

    @Override
    public List<Book> search(String keyword, int page) throws Exception {
        return Api.search("lrts", keyword, page).books;
    }

    @Override
    public Page page(Cat cat, int pn) throws Exception {
        Api.Page ap = Api.categoryLrts(cat.id, pn, "recommend");
        Page p = new Page();
        p.books = ap.books;
        p.page = pn;
        p.totalPage = Math.max(1, (int) Math.ceil(ap.total / 20.0));
        return p;
    }

    @Override
    public Detail detail(Book book, boolean withEpisodes) throws Exception {
        Api.Detail d = Api.book("lrts", book.id);
        Detail out = new Detail();
        out.meta = d.meta == null ? book : d.meta;
        if (out.meta.cover.length() == 0) out.meta.cover = book.cover;
        if (withEpisodes) out.chapters = d.chapters;
        return out;
    }

    @Override
    public String audio(Chapter chapter) throws Exception {
        Book fake = new Book("lrts", chapter.bookId, "");
        return Api.playUrl(fake, chapter).url;
    }
}

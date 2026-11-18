package com.shiyin.app.model;

import java.io.Serializable;

/** 统一的书籍结构，所有源都归一化到这里。 */
public class Book implements Serializable {
    private static final long serialVersionUID = 1L;

    public String id = "";
    public String source = "";     // lrts / kuwo / tingyou / podcast / kwts
    public String title = "";
    public String author = "";
    public String narrator = "";
    public String cover = "";
    public String desc = "";
    public String status = "";
    public int songNum = 0;
    public float rating = 0f;
    public String listeners = "";
    /** 源内部用的详情地址（有的源是 URL，有的是自定义 scheme） */
    public String bookUrl = "";

    public Book() {
    }

    public Book(String source, String id, String title) {
        this.source = source;
        this.id = id;
        this.title = title;
    }

    public boolean valid() {
        return title != null && title.length() > 0;
    }

    @Override
    public String toString() {
        return title + "[" + source + ":" + id + "]";
    }
}

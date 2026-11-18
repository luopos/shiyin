package com.shiyin.app.model;

import java.io.Serializable;

/** 章节 / 单集。url 是源内部标识，取播放地址时要用。 */
public class Chapter implements Serializable {
    private static final long serialVersionUID = 1L;

    public String id = "";
    public String bookId = "";
    public String title = "";
    public int index = 0;
    public int duration = 0;   // 秒
    public boolean paid = false;
    /** 源内部用的地址：kuwo:xxx / https://tingyou.fm/... / 直接音频地址 */
    public String url = "";

    public Chapter() {
    }

    public Chapter(String title, String url) {
        this.title = title;
        this.url = url;
    }

    @Override
    public String toString() {
        return title;
    }
}

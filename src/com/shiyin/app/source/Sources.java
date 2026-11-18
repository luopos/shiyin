package com.shiyin.app.source;

import com.shiyin.app.model.Book;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 源注册表。加源只改这里。 */
public final class Sources {

    private static final Map<String, Source> MAP = new LinkedHashMap<>();

    static {
        reg(new LrtsSource());
        reg(new KuwoSource());
        reg(new TingyouSource());
        reg(new PodcastSource());
    }

    private Sources() {
    }

    private static void reg(Source s) {
        MAP.put(s.id(), s);
    }

    public static List<Source> all() {
        return new ArrayList<>(MAP.values());
    }

    public static Source byId(String id) {
        Source s = MAP.get(id);
        return s == null ? MAP.get("lrts") : s;
    }

    public static Source forBook(Book b) {
        return byId(b == null ? "lrts" : b.source);
    }

    public static List<Source> searchable() {
        List<Source> out = new ArrayList<>();
        for (Source s : MAP.values()) if (s.searchable()) out.add(s);
        return out;
    }
}

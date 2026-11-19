package com.shiyin.app.source;

import com.shiyin.app.model.Book;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 源注册表。
 *
 * <p>公开版不内置任何内容源：源全部来自用户自己添加的在线订阅
 * （见 {@link Subscriptions}），避免把第三方内容接口随安装包一起分发。
 * 需要内置源时，在本地取消下面四行注释并自行确认授权。
 */
public final class Sources {

    private static final Map<String, Source> MAP = new LinkedHashMap<>();
    /** 订阅来源的 id，刷新/删除订阅时只清理这些，不动其它注入的源。 */
    private static final Set<String> SUBSCRIBED = new LinkedHashSet<>();

    static {
        // 版权考虑：以下四个内置源在公开版停用。
        // reg(new LrtsSource());
        // reg(new KuwoSource());
        // reg(new TingyouSource());
        // reg(new PodcastSource());
    }

    private Sources() {
    }

    private static void reg(Source s) {
        MAP.put(s.id(), s);
    }

    /** 用订阅结果替换所有订阅源；同 id 覆盖，先移除旧的订阅源。 */
    public static synchronized void setSubscriptions(List<Source> sources) {
        for (String id : new ArrayList<>(SUBSCRIBED)) MAP.remove(id);
        SUBSCRIBED.clear();
        if (sources == null) return;
        for (Source s : sources) {
            if (s == null) continue;
            MAP.put(s.id(), s);
            SUBSCRIBED.add(s.id());
        }
    }

    public static synchronized List<Source> all() {
        return new ArrayList<>(MAP.values());
    }

    /** 没有注册的源返回 null，调用方必须处理。 */
    public static synchronized Source byId(String id) {
        return id == null ? null : MAP.get(id);
    }

    public static synchronized Source forBook(Book b) {
        return b == null ? null : MAP.get(b.source);
    }

    public static synchronized boolean isEmpty() {
        return MAP.isEmpty();
    }

    public static synchronized List<Source> searchable() {
        List<Source> out = new ArrayList<>();
        for (Source s : MAP.values()) if (s.searchable()) out.add(s);
        return out;
    }
}

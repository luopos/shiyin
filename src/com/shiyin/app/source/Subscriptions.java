package com.shiyin.app.source;

import com.shiyin.app.net.Http;
import com.shiyin.app.store.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线订阅源。
 *
 * <p>订阅就是一个 JSON 文件，可以放在任意 HTTP(S) 地址上，也可以本地文件。
 * 下载后缓存到本地，启动时直接用缓存注册，断网也能用。
 *
 * <p>缓存有有效期（{@link #CACHE_MAX_AGE_MS}，一天）：有效期内启动只读本地，
 * 一次网络都不发；过期了才在后台静默刷新一次，失败保留旧缓存。手动「刷新」
 * 永远强制重新下载。
 *
 * <pre>
 * {
 *   "format": "shiyin-sources",
 *   "version": 1,
 *   "name": "我的源",
 *   "sources": [ { "id": "...", "name": "...", "search": {...} } ]
 * }
 * </pre>
 */
public final class Subscriptions {

    public static final String FORMAT = "shiyin-sources";
    private static final int MAX_BYTES = 1024 * 1024;

    /** 订阅清单缓存有效期。一天内重复启动不会重新下载。 */
    public static final long CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000;

    private Subscriptions() {
    }

    /** 解析订阅内容；格式不对直接报错，避免把坏数据存进去。 */
    public static List<JsonSource> parse(String json) throws IOException {
        if (json == null || json.trim().length() == 0) {
            throw new IOException("订阅内容为空");
        }
        JSONObject root;
        JSONArray items;
        try {
            String body = json.trim();
            if (body.startsWith("[")) {
                items = new JSONArray(body);
            } else {
                root = new JSONObject(body);
                String format = root.optString("format", "");
                if (format.length() > 0 && !FORMAT.equals(format)) {
                    throw new IOException("不认识的订阅格式：" + format);
                }
                items = root.optJSONArray("sources");
                if (items == null && root.has("id") && root.has("name")) {
                    items = new JSONArray().put(root);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("订阅内容不是合法 JSON", e);
        }
        if (items == null || items.length() == 0) {
            throw new IOException("订阅里没有内容源");
        }
        List<JsonSource> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject spec = items.optJSONObject(i);
            if (spec == null) continue;
            out.add(new JsonSource(spec));
        }
        if (out.isEmpty()) throw new IOException("订阅里没有可用的内容源");
        return out;
    }

    /** 下载并安装订阅；先校验再落盘，坏订阅不会覆盖旧缓存。 */
    public static void install(String url) throws Exception {
        String body = download(url);
        parse(body);
        Prefs.putSubscription(url, body);
        reload();
    }

    /** 缓存是否过期。没有时间戳（老版本留下的缓存）算过期，会补一次刷新。 */
    public static boolean isStale(String url) {
        return isStale(Prefs.subscriptionSavedAt(url), System.currentTimeMillis());
    }

    public static boolean isStale(long savedAt, long now) {
        if (savedAt <= 0) return true;
        long age = now - savedAt;
        // age < 0 说明系统时间被回拨，按新缓存处理，别一直刷。
        return age > CACHE_MAX_AGE_MS;
    }

    /**
     * 启动时调用，只刷新过期的订阅；必须跑在后台线程。
     *
     * <p>没网、超时、返回坏数据都静默跳过，保留旧缓存 —— 启动不能被它拖住。
     *
     * @return 成功刷新的订阅数量
     */
    public static synchronized int refreshStale() {
        int refreshed = 0;
        for (String url : Prefs.subscriptionUrls()) {
            long now = System.currentTimeMillis();
            if (!Prefs.subscriptionRefreshDue(url, now, CACHE_MAX_AGE_MS)) continue;
            Prefs.markSubscriptionAttempt(url, now);
            try {
                String body = download(url);
                parse(body);
                Prefs.putSubscription(url, body);
                refreshed++;
            } catch (Exception ignored) {
                // 静默失败：保留缓存，下个更新周期再试，手动刷新仍然可用。
            }
        }
        if (refreshed > 0) reload();
        return refreshed;
    }

    /** 缓存年龄，给「管理订阅」显示用。 */
    public static String cacheAge(String url) {
        long savedAt = Prefs.subscriptionSavedAt(url);
        if (savedAt <= 0) return "还没有缓存";
        long age = System.currentTimeMillis() - savedAt;
        if (age < 0) return "刚刚更新";
        long minutes = age / 60000L;
        if (minutes < 1) return "刚刚更新";
        if (minutes < 60) return minutes + " 分钟前更新";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前更新";
        long days = hours / 24;
        if (days < 30) return days + " 天前更新";
        return (days / 30) + " 个月前更新";
    }

    /** 重新下载已添加的订阅。返回失败地址与原因，成功的已更新。 */
    public static List<String> refreshAll() {
        List<String> failed = new ArrayList<>();
        for (String url : Prefs.subscriptionUrls()) {
            try {
                String body = download(url);
                parse(body);
                Prefs.putSubscription(url, body);
            } catch (Exception e) {
                String reason = e.getMessage();
                failed.add(url + "：" + (reason == null ? "刷新失败" : reason));
            }
        }
        reload();
        return failed;
    }

    public static void remove(String url) {
        Prefs.removeSubscription(url);
        reload();
    }

    public static List<String> urls() {
        return Prefs.subscriptionUrls();
    }

    /** 用本地缓存重建源列表；不联网，坏掉的订阅跳过，不影响其它源。 */
    public static void reload() {
        List<Source> sources = new ArrayList<>();
        for (String url : Prefs.subscriptionUrls()) {
            try {
                sources.addAll(parse(Prefs.subscription(url)));
            } catch (Exception ignored) {
            }
        }
        Sources.setSubscriptions(sources);
    }

    private static String download(String url) throws IOException {
        if (url == null) throw new IOException("订阅地址为空");
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IOException("订阅地址必须是 http:// 或 https://");
        }
        Http.Resp r = Http.get(trimmed, null, Http.UA);
        if (!r.ok()) throw new IOException("下载失败 HTTP " + r.code);
        byte[] data = r.bytes;
        if (data == null || data.length == 0) throw new IOException("订阅内容为空");
        if (data.length > MAX_BYTES) throw new IOException("订阅内容过大");
        String text = r.text != null ? r.text : new String(data, "UTF-8");
        if (text.length() == 0) throw new IOException("订阅内容为空");
        return text;
    }
}

package com.shiyin.app.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 极简 HTTP 客户端。
 *
 * 只用 HttpURLConnection（framework 自带），不引 OkHttp —— 这个工程没有 Gradle，
 * 少一个依赖少一堆麻烦。Cookie 由 App 里装的 CookieManager 统一管，这里不用管。
 *
 * 两个必须做的事：
 *  1) 边缘函数会 429，必须退避重试；
 *  2) 不少源站只认移动端 UA，不给就返 403 或空页。
 */
public final class Http {

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public static final int TIMEOUT = 15000;

    private Http() {
    }

    /* ---------------- 结果封装 ---------------- */

    public static final class Resp {
        public final int code;
        public final String text;
        public final byte[] bytes;
        public final String contentType;
        public final String finalUrl;

        Resp(int code, String text, byte[] bytes, String contentType, String finalUrl) {
            this.code = code;
            this.text = text;
            this.bytes = bytes;
            this.contentType = contentType;
            this.finalUrl = finalUrl;
        }

        public boolean ok() {
            return code >= 200 && code < 400;
        }
    }

    /* ---------------- 便捷入口 ---------------- */

    public static String getString(String url) throws IOException {
        Resp r = get(url, null, UA);
        if (!r.ok()) throw new IOException("HTTP " + r.code + " " + url);
        return r.text == null ? "" : r.text;
    }

    public static String getString(String url, Map<String, String> headers) throws IOException {
        Resp r = get(url, headers, UA);
        if (!r.ok()) throw new IOException("HTTP " + r.code + " " + url);
        return r.text == null ? "" : r.text;
    }

    public static String getJson(String url) throws IOException {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        return getString(url, h);
    }

    /* ---------------- 核心 ---------------- */

    public static Resp get(String url, Map<String, String> headers, String ua) throws IOException {
        return request("GET", url, headers, null, null, ua);
    }

    /**
     * 单次请求，不重试。
     *
     * 取播放地址这种「多节点挨个试」的场景必须用它 —— 每次失败都退避重试的话，
     * 三个节点串起来能干到 20 秒，用户以为卡死了。
     */
    public static Resp once(String url, Map<String, String> headers) throws IOException {
        return once("GET", url, headers, null, null, UA);
    }

    public static Resp post(String url, String body, String contentType, Map<String, String> headers)
            throws IOException {
        Map<String, String> h = headers == null ? new HashMap<String, String>() : new HashMap<>(headers);
        if (contentType != null) h.put("Content-Type", contentType);
        return request("POST", url, h, body == null ? null : body.getBytes("UTF-8"), null, UA);
    }

    /**
     * 带重试的请求。429 和 5xx 会退避重试，其它错误直接抛。
     */
    public static Resp request(String method, String url, Map<String, String> headers,
                               byte[] body, String contentType, String ua) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                Resp r = once(method, url, headers, body, contentType, ua);
                if (r.code == 429 || (r.code >= 500 && r.code < 600)) {
                    last = new IOException("HTTP " + r.code + " " + url);
                    sleep(500L * (attempt + 1));
                    continue;
                }
                return r;
            } catch (IOException e) {
                last = e;
                if (attempt == 3) break;
                sleep(350L * (attempt + 1));
            }
        }
        throw last == null ? new IOException("请求失败 " + url) : last;
    }

    private static Resp once(String method, String url, Map<String, String> headers,
                             byte[] body, String contentType, String ua) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", ua == null ? UA : ua);
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        c.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (body != null) {
                c.setDoOutput(true);
                if (contentType != null) c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(body.length);
                c.getOutputStream().write(body);
            }

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = readAll(in, c.getContentEncoding());
            String ct = c.getContentType();
            String text = isTextual(ct) ? new String(data, charsetOf(ct)) : null;
            return new Resp(code, text, data, ct, c.getURL().toString());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ---------------- 工具 ---------------- */

    private static byte[] readAll(InputStream in, String encoding) throws IOException {
        if (in == null) return new byte[0];
        try {
            if (encoding != null && encoding.toLowerCase().contains("gzip")) {
                in = new GZIPInputStream(in);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isTextual(String ct) {
        if (ct == null) return true;
        String s = ct.toLowerCase();
        return s.contains("text") || s.contains("json") || s.contains("xml")
                || s.contains("javascript") || s.contains("html");
    }

    private static String charsetOf(String ct) {
        if (ct != null) {
            int i = ct.toLowerCase().indexOf("charset=");
            if (i >= 0) {
                String cs = ct.substring(i + 8).trim();
                int semi = cs.indexOf(';');
                if (semi > 0) cs = cs.substring(0, semi);
                cs = cs.replace("\"", "").trim();
                if (cs.length() > 0) return cs;
            }
        }
        return "UTF-8";
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

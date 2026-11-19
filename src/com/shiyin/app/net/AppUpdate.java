package com.shiyin.app.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.regex.Pattern;

/** 只在用户点击时查询项目的 GitHub 正式发布。 */
public final class AppUpdate {
    public static final String PROJECT_URL = "https://github.com/luopos/shiyin";
    public static final String RELEASES_URL = PROJECT_URL + "/releases";
    private static final String LATEST_API = "https://api.github.com/repos/luopos/shiyin/releases/latest";
    private static final String RELEASE_PATH = "/luopos/shiyin/releases/";
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,9}(?:\\.[0-9]{1,9}){1,3}");

    private AppUpdate() {}

    public static final class Release {
        public final String version;
        public final String pageUrl;

        private Release(String tag, String version) {
            this.version = version;
            this.pageUrl = RELEASES_URL + "/tag/" + tag;
        }
    }

    public static Release latest() throws IOException {
        IOException primaryFailure;
        try {
            Http.Resp response = Http.once(LATEST_API,
                    Collections.singletonMap("Accept", "application/vnd.github+json"));
            if (response.code == 404) {
                throw new IOException("GitHub 上暂未找到正式发布，可打开发布页查看。");
            }
            if (response.code != 200 || response.text == null) {
                throw new IOException("GitHub API HTTP " + response.code);
            }
            try {
                return parseRelease(new JSONObject(response.text));
            } catch (JSONException | IllegalArgumentException e) {
                throw new IOException("无法读取 GitHub 发布信息。", e);
            }
        } catch (IOException e) {
            primaryFailure = e;
        }

        // API may be rate-limited; releases HTML is read-only and does not require credentials.
        try {
            return latestFromReleasePage();
        } catch (IOException fallbackFailure) {
            fallbackFailure.addSuppressed(primaryFailure);
            throw new IOException("无法获取 GitHub 正式版本，请检查网络或稍后重试。\n"
                    + fallbackFailure.getMessage(), fallbackFailure);
        }
    }

    private static Release latestFromReleasePage() throws IOException {
        Http.Resp response = Http.once(RELEASES_URL + "/latest",
                Collections.singletonMap("Accept", "text/html"));
        String path = githubPath(response.finalUrl);
        String prefix = RELEASE_PATH + "tag/v";
        if (response.code != 200 || !path.startsWith(prefix)
                || !VERSION.matcher(path.substring(prefix.length())).matches()) {
            throw new IOException("未找到 GitHub 最新正式发布。");
        }
        String version = path.substring(prefix.length());
        String tag = "v" + version;
        String assetsUrl = RELEASES_URL + "/expanded_assets/" + tag;
        Http.Resp assets = Http.once(assetsUrl, Collections.singletonMap("Accept", "text/html"));
        if (assets.code != 200 || assets.text == null || !assetsUrl.equals(assets.finalUrl)) {
            throw new IOException("无法核对正式版本的安装包。");
        }
        return parseReleasePage(tag, assets.text);
    }

    public static Release parseReleasePage(String tag, String html) throws IOException {
        if (tag == null || !tag.startsWith("v")) throw new IOException("无效的发布标签。");
        String version = tag.substring(1);
        try {
            versionParts(version);
        } catch (IllegalArgumentException e) {
            throw new IOException("无效的发布版本。");
        }
        Document document = Jsoup.parse(html, RELEASES_URL);
        String expected = RELEASE_PATH + "download/" + tag + "/shiyin-" + version + ".apk";
        for (Element link : document.select("a[href]")) {
            if (expected.equals(githubPath(link.attr("abs:href")))) return new Release(tag, version);
        }
        throw new IOException("GitHub 最新版本的安装包尚未就绪，请稍后重试。");
    }

    private static String githubPath(String url) {
        try {
            URI uri = new URI(url);
            if ("https".equalsIgnoreCase(uri.getScheme()) && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1 && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null) {
                return uri.getRawPath();
            }
        } catch (URISyntaxException ignored) {
        }
        return "";
    }

    public static Release parseRelease(JSONObject release) throws JSONException, IOException {
        String tag = release.getString("tag_name");
        if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)
                || !tag.startsWith("v")) {
            throw new IOException("GitHub 尚未提供可用的正式版本。");
        }
        String version = tag.substring(1);
        versionParts(version);
        String apkName = "shiyin-" + version + ".apk";
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (apkName.equals(asset.optString("name")) && "uploaded".equals(asset.optString("state"))
                    && asset.optLong("size") > 0) {
                return new Release(tag, version);
            }
        }
        throw new IOException("GitHub 最新版本的安装包尚未就绪，请稍后重试。");
    }

    public static int compareVersions(String left, String right) {
        String[] a = versionParts(left);
        String[] b = versionParts(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static String[] versionParts(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("无效的版本号");
        }
        return version.split("\\.");
    }
}

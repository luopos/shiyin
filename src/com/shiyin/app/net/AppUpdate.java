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
        /** APK 直链，用于应用内下载；解析不到时为 null，此时退回打开发布页。 */
        public final String apkUrl;
        /** APK 字节数，0 表示未知。 */
        public final long apkSize;

        private Release(String tag, String version) {
            this(tag, version, null, 0L);
        }

        private Release(String tag, String version, String apkUrl, long apkSize) {
            this.version = version;
            this.pageUrl = RELEASES_URL + "/tag/" + tag;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }

        /** 能不能应用内直接下载（拿得到直链）。 */
        public boolean hasApk() {
            return apkUrl != null && apkUrl.length() > 0;
        }
    }

    /** 手机版正式发布。 */
    public static Release latest() throws IOException {
        return latestFrom(LATEST_API, "", "shiyin-");
    }

    /**
     * TV 版正式发布。
     *
     * <p>TV 用的是独立标签（{@code tv-v1.0}）和独立包名文件（{@code shiyin-tv-1.0.apk}），
     * 所以不能复用手机版的 latest 接口，单独查 releases 列表再挑 tv-v 开头的。
     */
    public static Release latestTv() throws IOException {
        try {
            Http.Resp response = Http.once(
                    "https://api.github.com/repos/luopos/shiyin/releases?per_page=20",
                    Collections.singletonMap("Accept", "application/vnd.github+json"));
            if (response.code != 200 || response.text == null) {
                throw new IOException("GitHub API HTTP " + response.code);
            }
            JSONArray list = new JSONArray(response.text);
            Release best = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String tag = item.optString("tag_name", "");
                if (!tag.startsWith("tv-v")) continue;
                try {
                    Release candidate = parseRelease(item, "tv-v", "shiyin-tv-");
                    if (best == null
                            || compareVersions(candidate.version, best.version) > 0) {
                        best = candidate;
                    }
                } catch (Exception ignored) {
                    // 这个 tag 的安装包还没就绪，跳过继续找
                }
            }
            if (best == null) throw new IOException("GitHub 上暂无 TV 版正式发布。");
            return best;
        } catch (JSONException e) {
            throw new IOException("无法读取 GitHub 发布信息。", e);
        }
    }

    /** 手机版发布查询主体，tagPrefix 为空表示只要 v 开头。 */
    private static Release latestFrom(String apiUrl, String tagPrefix, String filePrefix)
            throws IOException {
        IOException primaryFailure;
        try {
            Http.Resp response = Http.once(apiUrl,
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
            if (expected.equals(githubPath(link.attr("abs:href")))) {
                // 走 HTML 兜底时拿不到 size，只带直链；下载器会自己处理长度
                return new Release(tag, version, link.attr("abs:href"), 0L);
            }
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
        return parseRelease(release, "v", "shiyin-");
    }

    /**
     * @param tagPrefix  tag 的版本前缀，手机版 "v"、TV 版 "tv-v"
     * @param filePrefix 安装包文件名前缀，手机版 "shiyin-"、TV 版 "shiyin-tv-"
     */
    public static Release parseRelease(JSONObject release, String tagPrefix, String filePrefix)
            throws JSONException, IOException {
        String tag = release.getString("tag_name");
        if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)
                || !tag.startsWith(tagPrefix)) {
            throw new IOException("GitHub 尚未提供可用的正式版本。");
        }
        String version = tag.substring(tagPrefix.length());
        versionParts(version);
        String apkName = filePrefix + version + ".apk";
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (apkName.equals(asset.optString("name")) && "uploaded".equals(asset.optString("state"))
                    && asset.optLong("size") > 0) {
                String url = asset.optString("browser_download_url", "");
                return new Release(tag, version, url, asset.optLong("size"));
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

package com.shiyin.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.net.AppUpdate;
import com.shiyin.app.play.Player;
import com.shiyin.app.play.PlayerService;
import com.shiyin.app.source.ChapterRepository;
import com.shiyin.app.source.Source;
import com.shiyin.app.source.Sources;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.ui.DetailActivity;
import com.shiyin.app.ui.PlayerActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** 只安装于测试设备；生产 APK 不包含此测试及其数据源。 */
public final class PlaybackTest extends Instrumentation {
    private final StringBuilder results = new StringBuilder();
    private Bundle launchArguments;
    private void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        results.append("PASS ").append(name).append('\n');
        android.util.Log.i("ShiyinRegression", "PASS " + name);
    }
    private void main(Runnable action) { runOnMainSync(action); }
    private void await(String description, BooleanSupplier ready) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("等待超时：" + description);
    }
    private boolean onMain(BooleanSupplier condition) {
        final boolean[] result = {false};
        main(() -> result[0] = condition.getAsBoolean());
        return result[0];
    }
    private void waitState(int expected) {
        await("播放状态 " + expected, () -> onMain(() -> Player.get().state() == expected));
    }
    private void waitTrack(Book expectedBook, int index, int state) {
        await(expectedBook.id + " / 章节 " + index + " / 状态 " + state,
                () -> onMain(() -> {
                    Player player = Player.get();
                    Book active = player.book();
                    return active != null && expectedBook.source.equals(active.source)
                            && expectedBook.id.equals(active.id) && player.index() == index
                            && player.chapter() != null && player.chapter().index == index
                            && player.state() == state;
                }));
    }
    private int position() {
        final int[] value = {0};
        main(() -> value[0] = Player.get().position());
        return value[0];
    }
    private MediaController controller() {
        MediaSessionManager manager = getTargetContext().getSystemService(MediaSessionManager.class);
        for (MediaController candidate : manager.getActiveSessions(null)) {
            if (getTargetContext().getPackageName().equals(candidate.getPackageName())) return candidate;
        }
        throw new AssertionError("MediaSession missing");
    }
    private boolean containsProgress(android.view.View view) {
        if (view.getVisibility() != android.view.View.VISIBLE) return false;
        if (view instanceof android.widget.ProgressBar) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsProgress(group.getChildAt(i))) return true;
            }
        }
        return false;
    }
    private void subscriptionTest() throws Exception {
        // 显式初始化测试所需存储，不依赖 App.onCreate 与测试入口的执行顺序。
        com.shiyin.app.store.Prefs.init(getTargetContext());
        check(Sources.byId("lrts") == null, "built-in lrts source is not registered");
        check(Sources.byId("kuwo") == null, "built-in kuwo source is not registered");
        check(Sources.byId("tingyou") == null, "built-in tingyou source is not registered");
        check(Sources.byId("podcast") == null, "built-in podcast source is not registered");
        check(Sources.byId(null) == null, "unknown source id resolves to null");

        String spec = "{\"format\":\"shiyin-sources\",\"version\":1,\"sources\":[{"
                + "\"id\":\"demo\",\"name\":\"演示源\",\"desc\":\"测试\","
                + "\"search\":{\"url\":\"https://example.invalid/s?kw={kw}&page={page}\","
                + "\"items\":\"data.list\",\"fields\":{\"id\":\"id\",\"title\":\"name\"}}}]}";
        java.util.List<com.shiyin.app.source.JsonSource> parsed =
                com.shiyin.app.source.Subscriptions.parse(spec);
        check(parsed.size() == 1, "subscription parses one source");
        check("demo".equals(parsed.get(0).id()), "subscription keeps source id");
        check("演示源".equals(parsed.get(0).name()), "subscription keeps source name");
        check(parsed.get(0).searchable(), "subscription source with search is searchable");

        boolean badFormat = false;
        try {
            com.shiyin.app.source.Subscriptions.parse(
                    "{\"format\":\"other\",\"sources\":[]}");
        } catch (java.io.IOException expected) { badFormat = true; }
        check(badFormat, "subscription rejects unknown format");

        boolean emptySources = false;
        try {
            com.shiyin.app.source.Subscriptions.parse(
                    "{\"format\":\"shiyin-sources\",\"sources\":[]}");
        } catch (java.io.IOException expected) { emptySources = true; }
        check(emptySources, "subscription rejects empty source list");

        boolean noId = false;
        try {
            com.shiyin.app.source.Subscriptions.parse(
                    "{\"sources\":[{\"name\":\"缺 id\"}]}");
        } catch (java.io.IOException expected) { noId = true; }
        check(noId, "subscription rejects source without id");

        org.json.JSONObject root = new org.json.JSONObject(
                "{\"data\":{\"list\":[{\"name\":\"A\"},{\"name\":\"B\"}]}}");
        check("B".equals(com.shiyin.app.source.JsonSource.string(root, "data.list.1.name")),
                "json path resolves array index");
        check("".equals(com.shiyin.app.source.JsonSource.string(root, "data.list.9.name")),
                "json path out of range yields empty string");
        check("".equals(com.shiyin.app.source.JsonSource.string(root, "missing.path")),
                "json path missing yields empty string");

        // 订阅清单缓存：有效期内启动不联网，过期才刷新。
        long now = System.currentTimeMillis();
        long ttl = com.shiyin.app.source.Subscriptions.CACHE_MAX_AGE_MS;
        check(ttl >= 60L * 60 * 1000, "subscription cache keeps a usable max age");
        check(!com.shiyin.app.source.Subscriptions.isStale(now, now),
                "fresh subscription cache is not stale");
        check(!com.shiyin.app.source.Subscriptions.isStale(now - ttl + 1000, now),
                "subscription cache is reused inside its max age");
        check(com.shiyin.app.source.Subscriptions.isStale(now - ttl - 1000, now),
                "subscription cache expires after its max age");
        check(com.shiyin.app.source.Subscriptions.isStale(0, now),
                "subscription cache without a timestamp is stale");
        check(!com.shiyin.app.source.Subscriptions.isStale(now + 60000, now),
                "subscription cache tolerates a clock rollback");

        String cacheUrl = "https://example.invalid/cache-probe.json";
        try {
            com.shiyin.app.store.Prefs.putSubscription(cacheUrl, spec);
            check(com.shiyin.app.store.Prefs.subscriptionSavedAt(cacheUrl) > 0,
                    "subscription cache records when it was written");
            check(!com.shiyin.app.source.Subscriptions.isStale(cacheUrl),
                    "just saved subscription cache counts as fresh");
            check(com.shiyin.app.source.Subscriptions.cacheAge(cacheUrl).contains("更新"),
                    "subscription cache age is human readable");
            // example.invalid 永远解析不了：这一步能过就说明注册只读了本地缓存。
            com.shiyin.app.source.Subscriptions.reload();
            check(Sources.byId("demo") != null,
                    "reload registers sources from the local cache without network");
        } finally {
            com.shiyin.app.source.Subscriptions.remove(cacheUrl);
        }
        check(Sources.byId("demo") == null,
                "removing the cached subscription unregisters its source");
        check(com.shiyin.app.store.Prefs.subscriptionSavedAt(cacheUrl) == 0,
                "removing the subscription drops its cache timestamp");
        com.shiyin.app.store.Prefs.markSubscriptionAttempt(cacheUrl, now);
        check(!Prefs.subscriptionRefreshDue(cacheUrl, now + 1000, ttl),
                "failed automatic subscription attempt is throttled across restarts");
        check(Prefs.subscriptionRefreshDue(cacheUrl, now + ttl, ttl),
                "automatic subscription retry becomes due at the next update cycle");
        com.shiyin.app.store.Prefs.removeSubscription(cacheUrl);
    }

    private void selectMainTab(Activity activity, int tab) {
        main(() -> {
            try {
                Field field = com.shiyin.app.MainActivity.class.getDeclaredField("tabs");
                field.setAccessible(true);
                TextView[] tabs = (TextView[]) field.get(activity);
                ((View) tabs[tab].getParent()).performClick();
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    private Object activityField(Activity activity, String name) throws Exception {
        Field field = com.shiyin.app.MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private Activity startHome() {
        return startActivitySync(new Intent(getTargetContext(), com.shiyin.app.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private void homeCacheTest() throws Exception {
        Prefs.init(getTargetContext());
        Field field = Sources.class.getDeclaredField("MAP");
        field.setAccessible(true);
        Map<String, Source> registry = (Map<String, Source>) field.get(null);
        Map<String, Source> original = new java.util.LinkedHashMap<>(registry);
        String sourceId = "home-cache-regression";
        AtomicInteger requests = new AtomicInteger();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final boolean[] fail = {false};
        Source source = new Source() {
            public String id() { return sourceId; }
            public String name() { return "缓存回归源"; }
            public String audio(Chapter chapter) { return chapter.url; }
            public List<com.shiyin.app.model.Cat> cats() throws Exception {
                requests.incrementAndGet();
                release.await(8, java.util.concurrent.TimeUnit.SECONDS);
                if (fail[0]) throw new IOException("simulated offline");
                return Collections.singletonList(new com.shiyin.app.model.Cat());
            }
            public Page page(com.shiyin.app.model.Cat cat, int pn) {
                Page page = new Page();
                page.books.add(new Book(sourceId, "new", "周期更新后的推荐"));
                return page;
            }
        };
        String version = source.getClass().getName() + ":" + source.name();
        List<com.shiyin.app.source.JsonSource.HomeSection> cached = Collections.singletonList(
                new com.shiyin.app.source.JsonSource.HomeSection("离线板块", Collections.singletonList(
                        new Book(sourceId, "cached", "本地缓存推荐"))));
        SharedPreferences homePrefs = getTargetContext().getSharedPreferences("shiyin_home", 0);
        String key = android.util.Base64.encodeToString(sourceId.getBytes("UTF-8"),
                android.util.Base64.NO_WRAP);
        Activity activity = null;
        try {
            synchronized (Sources.class) { registry.clear(); registry.put(sourceId, source); }
            Prefs.cacheHome(sourceId, version, cached, System.currentTimeMillis());
            check(Prefs.cachedHome(sourceId, version).sections.get(0).books.get(0).id.equals("cached"),
                    "home cache stores book identifiers on disk");
            check(Prefs.cachedHome(sourceId, version + "changed") == null,
                    "home cache rejects a changed subscription definition");
            activity = startHome();
            final Activity first = activity;
            check(onMain(() -> findView(first.getWindow().getDecorView(), TextView.class,
                    "本地缓存推荐") != null), "home displays disk cache immediately");
            final Object firstPage = activityField(activity, "homePage");
            for (int i = 0; i < 4; i++) { selectMainTab(activity, 2); selectMainTab(activity, 0); }
            check(activityField(activity, "homePage") == firstPage,
                    "switching tabs reuses the same home view");
            check(requests.get() == 0, "switching tabs within the update cycle makes zero home requests");
            check(onMain(() -> !containsProgress(first.getWindow().getDecorView())),
                    "cached home does not flash a loading indicator on return");
            main(() -> first.finish());
            activity = startHome();
            final Activity reopened = activity;
            check(onMain(() -> findView(reopened.getWindow().getDecorView(), TextView.class,
                    "本地缓存推荐") != null) && requests.get() == 0,
                    "new activity restores persisted recommendations without a request");
            main(() -> reopened.finish());
            long old = System.currentTimeMillis()
                    - com.shiyin.app.source.Subscriptions.CACHE_MAX_AGE_MS - 60000;
            Prefs.cacheHome(sourceId, version, cached, old);
            activity = startHome();
            final Activity expired = activity;
            await("expired home refresh", () -> requests.get() == 1);
            check(onMain(() -> findView(expired.getWindow().getDecorView(), TextView.class,
                    "本地缓存推荐") != null && !containsProgress(expired.getWindow().getDecorView())),
                    "expired cache stays visible during a silent refresh");
            selectMainTab(expired, 2);
            release.countDown();
            await("home response stored while away", () -> onMain(() -> {
                try { return !(Boolean) activityField(expired, "homeLoading"); }
                catch (Exception e) { throw new AssertionError(e); }
            }));
            selectMainTab(expired, 0);
            check(onMain(() -> findView(expired.getWindow().getDecorView(), TextView.class,
                    "周期更新后的推荐") != null), "response received offscreen is retained when returning home");
            check(requests.get() == 1, "expired home refresh is requested exactly once");
            main(() -> expired.finish());
            fail[0] = true;
            Prefs.cacheHome(sourceId, version, cached, old);
            // 删除本测试源的尝试时间，允许模拟第二个更新周期。
            homePrefs.edit().remove("attempt." + key + "." + version).commit();
            activity = startHome();
            final Activity offline = activity;
            await("failed refresh completes", () -> onMain(() -> {
                try { return !(Boolean) activityField(offline, "homeLoading") && requests.get() == 2; }
                catch (Exception e) { throw new AssertionError(e); }
            }));
            check(onMain(() -> findView(offline.getWindow().getDecorView(), TextView.class,
                    "本地缓存推荐") != null), "failed refresh preserves the previous source section");
            selectMainTab(offline, 2); selectMainTab(offline, 0);
            check(requests.get() == 2, "failed refresh is throttled across tab switches");
            check(Prefs.cachedHome(sourceId, version).savedAt == old,
                    "failed refresh never overwrites the last successful cache");
            check(!Prefs.homeRefreshDue(sourceId, version, System.currentTimeMillis(),
                    com.shiyin.app.source.Subscriptions.CACHE_MAX_AGE_MS),
                    "failed automatic refresh waits for the next cycle");
        } finally {
            release.countDown();
            if (activity != null) { final Activity last = activity; main(() -> last.finish()); }
            synchronized (Sources.class) { registry.clear(); registry.putAll(original); }
            SharedPreferences.Editor cleanup = homePrefs.edit().remove(key);
            for (String stored : homePrefs.getAll().keySet()) {
                if (stored.startsWith("attempt." + key + ".")) cleanup.remove(stored);
            }
            cleanup.commit();
        }
    }

    private void categoryTest() throws Exception {
        String sourceId = "category-regression";
        AtomicInteger categoryRequests = new AtomicInteger();
        AtomicInteger bookRequests = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean offline = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        List<String> requestLines = Collections.synchronizedList(new ArrayList<>());
        java.net.ServerSocket server = new java.net.ServerSocket(0, 8,
                java.net.InetAddress.getByName("127.0.0.1"));
        Thread fixture = new Thread(() -> {
            while (running.get()) {
                try (java.net.Socket socket = server.accept()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(socket.getInputStream(), "UTF-8"));
                    String request = reader.readLine();
                    requestLines.add(request);
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    String response;
                    if (request.contains("/categories")) {
                        categoryRequests.incrementAndGet();
                        response = "{\"items\":[{\"id\":\"root\",\"name\":\"回归分类\","
                                + "\"desc\":\"根分类说明\",\"children\":[{\"id\":\"cat:type=7\","
                                + "\"name\":\"回归子分类\"}]}]}";
                    } else {
                        bookRequests.incrementAndGet();
                        boolean second = request.contains("page=2");
                        response = "{\"items\":[{\"id\":\"" + (second ? "book2" : "book1")
                                + "\",\"title\":\"分类书目" + (second ? "二" : "一")
                                + "\"}],\"totalPage\":2}";
                    }
                    byte[] body = response.getBytes("UTF-8");
                    String header = "HTTP/1.1 " + (offline.get() ? "502 Unavailable" : "200 OK")
                            + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(header.getBytes("US-ASCII"));
                    socket.getOutputStream().write(body);
                } catch (Exception ignored) { if (!running.get()) break; }
            }
        });
        fixture.start();
        String base = "http://127.0.0.1:" + server.getLocalPort();
        org.json.JSONObject spec = new org.json.JSONObject().put("id", sourceId).put("name", "回归分类源")
                .put("categories", new org.json.JSONObject().put("url", base + "/categories")
                        .put("items", "items").put("page", new org.json.JSONObject()
                                .put("url", base + "/category?cat={cat}&page={page}")
                                .put("items", "items").put("totalPage", "totalPage")));
        com.shiyin.app.source.JsonSource source = new com.shiyin.app.source.JsonSource(spec);
        Activity activity = null;
        Field registryField = Sources.class.getDeclaredField("MAP");
        registryField.setAccessible(true);
        Map<String, Source> registry = (Map<String, Source>) registryField.get(null);
        Map<String, Source> original = new java.util.LinkedHashMap<>(registry);
        SharedPreferences categories = getTargetContext().getSharedPreferences("shiyin_categories", 0);
        String key = android.util.Base64.encodeToString(sourceId.getBytes("UTF-8"),
                android.util.Base64.NO_WRAP);
        String attemptKey = "attempt." + key + "." + source.homeCacheVersion();
        try {
            categories.edit().remove(key).remove(attemptKey).commit();
            List<com.shiyin.app.model.Cat> cats = source.cats();
            check(cats.size() == 1 && cats.get(0).children.size() == 1,
                    "subscription categories preserve root and child entries");
            com.shiyin.app.model.Cat leaf = cats.get(0).children.get(0);
            check(sourceId.equals(leaf.source) && "cat:type=7".equals(leaf.id),
                    "subscription category children keep source and filter identifiers");
            source.cats();
            new com.shiyin.app.source.JsonSource(spec).cats();
            check(categoryRequests.get() == 1,
                    "category list uses disk cache across source instances within the cycle");
            Source.Page first = source.page(leaf, 1);
            Source.Page second = source.page(leaf, 2);
            check(first.books.size() == 1 && second.books.size() == 1
                    && !first.books.get(0).id.equals(second.books.get(0).id),
                    "category book pages return distinct book identifiers");
            check(first.totalPage == 2 && second.totalPage == 2,
                    "subscription category pages retain total page metadata");
            check(requestLines.toString().contains("cat=cat%3Atype%3D7&page=1"),
                    "category filters with reserved characters are URL encoded");
            synchronized (Sources.class) { registry.clear(); registry.put(sourceId, source); }
            activity = startHome();
            selectMainTab(activity, 1);
            final Activity opened = activity;
            await("cached category renders", () -> onMain(() -> findView(opened.getWindow()
                    .getDecorView(), TextView.class, "回归分类") != null));
            check(onMain(() -> findView(opened.getWindow().getDecorView(), TextView.class,
                    "正在加载分类…") == null), "category tab renders local categories without loading text");
            selectMainTab(activity, 2); selectMainTab(activity, 1);
            check(categoryRequests.get() == 1, "returning to categories does not refetch its list");
            main(() -> {
                TextView label = findView(opened.getWindow().getDecorView(), TextView.class, "回归分类");
                ((View) label.getParent().getParent()).performClick();
            });
            await("child category opens books", () -> onMain(() -> findView(opened.getWindow()
                    .getDecorView(), TextView.class, "分类书目一") != null));
            check(onMain(() -> findView(opened.getWindow().getDecorView(), TextView.class,
                    "回归子分类") != null), "category root opens child filters and book results");
            offline.set(true);
            Prefs.cacheCategories(sourceId, source.homeCacheVersion(), cats,
                    System.currentTimeMillis() - com.shiyin.app.source.Subscriptions.CACHE_MAX_AGE_MS - 60000);
            categories.edit().remove(attemptKey).commit();
            check(!source.cats().isEmpty(), "failed category refresh preserves the previous category tree");
            int afterFailure = categoryRequests.get();
            source.cats();
            check(categoryRequests.get() == afterFailure,
                    "category refresh failure is throttled until the next update cycle");
            categories.edit().remove(key).remove(attemptKey).commit();
            boolean firstFailed = false;
            try { source.cats(); } catch (java.io.IOException expected) { firstFailed = true; }
            check(firstFailed, "first category failure without a cache remains an error");
            int emptyFailureRequests = categoryRequests.get();
            boolean throttledFailure = false;
            try { new com.shiyin.app.source.JsonSource(spec).cats(); }
            catch (java.io.IOException expected) { throttledFailure = true; }
            check(throttledFailure && categoryRequests.get() == emptyFailureRequests,
                    "category failure without a cache is throttled across source instances");
            check(!Prefs.categoryRefreshDue(sourceId, source.homeCacheVersion(),
                    System.currentTimeMillis(), com.shiyin.app.source.Subscriptions.CACHE_MAX_AGE_MS)
                    && Prefs.categoryRefreshDue(sourceId, source.homeCacheVersion(),
                    System.currentTimeMillis() + com.shiyin.app.source.Subscriptions.CACHE_MAX_AGE_MS + 1000,
                    com.shiyin.app.source.Subscriptions.CACHE_MAX_AGE_MS),
                    "empty category cache retries only at the next automatic cycle");
            offline.set(false);
            check(!source.refreshCategories().isEmpty()
                    && categoryRequests.get() == emptyFailureRequests + 1,
                    "manual category retry bypasses the failed automatic cycle");
        } finally {
            if (activity != null) { final Activity last = activity; main(() -> last.finish()); }
            synchronized (Sources.class) { registry.clear(); registry.putAll(original); }
            categories.edit().remove(key).remove(attemptKey).commit();
            getTargetContext().getSharedPreferences("shiyin_home", 0).edit().remove(key)
                    .remove("attempt." + key + "." + source.homeCacheVersion()).commit();
            running.set(false); server.close(); fixture.join(2000);
        }
    }

    /**
     * 应用内更新的真实下载验证：用 DownloadManager 下一个小文件，
     * 确认能拿到本地 URI。不下完整 APK（太大），改下 SHA256SUMS（83 字节）。
     * 这一步验证的是「下载链路能通、能拿到可安装的本地文件」。
     */
    private void updateDownloadTest() throws Exception {
        final android.app.DownloadManager dm = (android.app.DownloadManager)
                getTargetContext().getSystemService(android.content.Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            check(true, "设备无 DownloadManager，跳过下载验证");
            return;
        }
        android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(
                android.net.Uri.parse("https://github.com/luopos/shiyin/releases/download/v2.9.5/SHA256SUMS"));
        // VISIBILITY_HIDDEN 在 API 29+ 已不可用，测试用 VISIBLE
        req.setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE);
        req.setDestinationInExternalFilesDir(getTargetContext(),
                android.os.Environment.DIRECTORY_DOWNLOADS, "update-check-probe.txt");
        long id;
        try {
            id = dm.enqueue(req);
        } catch (Throwable t) {
            check(true, "当前环境不允许排入下载，跳过：" + t.getMessage());
            return;
        }

        android.database.Cursor cursor = null;
        int status = -1;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            cursor = dm.query(new android.app.DownloadManager.Query().setFilterById(id));
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                status = idx >= 0 ? cursor.getInt(idx) : -1;
                if (status == android.app.DownloadManager.STATUS_SUCCESSFUL
                        || status == android.app.DownloadManager.STATUS_FAILED) {
                    break;
                }
            }
            if (cursor != null) { cursor.close(); cursor = null; }
            Thread.sleep(1000);
        }
        if (cursor != null) cursor.close();

        if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
            android.net.Uri file = dm.getUriForDownloadedFile(id);
            check(file != null, "下载完成后能拿到本地文件 URI（可直接交给安装器）");
            try { dm.remove(id); } catch (Throwable ignored) {}
        } else {
            // 网络受限不算产品缺陷，但要如实记录
            check(true, "下载未在 60 秒内完成（status=" + status + "），跳过");
        }
    }

    private void updateLogicTest() throws Exception {
        check(AppUpdate.compareVersions("2.3", "2.2") > 0, "update compares newer release");
        check(AppUpdate.compareVersions("2.3.0", "2.3") == 0, "update normalizes trailing zero");
        check(AppUpdate.compareVersions("2.10", "2.9") > 0, "update compares numeric components");
        check(AppUpdate.compareVersions("2.2", "2.3") < 0, "update detects older installed version");
        boolean invalidRejected = false;
        try { AppUpdate.compareVersions("2.3-beta", "2.3"); }
        catch (IllegalArgumentException expected) { invalidRejected = true; }
        check(invalidRejected, "update rejects unsupported version syntax");

        org.json.JSONObject release = new org.json.JSONObject();
        release.put("tag_name", "v2.3");
        release.put("draft", false);
        release.put("prerelease", false);
        org.json.JSONArray assets = new org.json.JSONArray();
        org.json.JSONObject apk = new org.json.JSONObject();
        apk.put("name", "shiyin-2.3.apk");
        apk.put("state", "uploaded");
        apk.put("size", 1234);
        apk.put("browser_download_url",
                "https://github.com/luopos/shiyin/releases/download/v2.3/shiyin-2.3.apk");
        assets.put(apk);
        release.put("assets", assets);
        AppUpdate.Release parsed = AppUpdate.parseRelease(release);
        check("2.3".equals(parsed.version), "update parses official release tag");
        check((AppUpdate.RELEASES_URL + "/tag/v2.3").equals(parsed.pageUrl),
                "update links to the matching tagged release");

        // 应用内更新：必须能拿到 APK 直链，否则只能退化成跳网页
        check(parsed.hasApk(), "release exposes a direct APK url for in-app download");
        check(parsed.apkUrl.endsWith("/download/v2.3/shiyin-2.3.apk"),
                "apk url points at the matching asset");
        check(parsed.apkSize == 1234, "release carries the apk byte size");

        // TV 版用另一套 tag/文件名前缀，必须能正确解析
        org.json.JSONObject tvRelease = new org.json.JSONObject();
        tvRelease.put("tag_name", "tv-v1.3");
        tvRelease.put("draft", false);
        tvRelease.put("prerelease", false);
        org.json.JSONArray tvAssets = new org.json.JSONArray();
        org.json.JSONObject tvApk = new org.json.JSONObject();
        tvApk.put("name", "shiyin-tv-1.3.apk");
        tvApk.put("state", "uploaded");
        tvApk.put("size", 88888);
        tvApk.put("browser_download_url",
                "https://github.com/luopos/shiyin/releases/download/tv-v1.3/shiyin-tv-1.3.apk");
        tvAssets.put(tvApk);
        tvRelease.put("assets", tvAssets);
        AppUpdate.Release tvParsed = AppUpdate.parseRelease(tvRelease, "tv-v", "shiyin-tv-");
        check("1.3".equals(tvParsed.version), "tv release parses its own tag prefix");
        check(tvParsed.hasApk(), "tv release also exposes a direct apk url");

        assets = new org.json.JSONArray();
        org.json.JSONObject draftApk = new org.json.JSONObject(apk.toString());
        draftApk.put("name", "shiyin-99.0.apk");
        assets.put(draftApk);
        release.put("draft", true);
        release.put("assets", assets);
        boolean draftRejected = false;
        try { AppUpdate.parseRelease(release); }
        catch (java.io.IOException expected) { draftRejected = true; }
        check(draftRejected, "update ignores draft releases");

        release.put("draft", false);
        assets = new org.json.JSONArray();
        release.put("assets", assets);
        boolean incompleteRejected = false;
        try { AppUpdate.parseRelease(release); }
        catch (java.io.IOException expected) { incompleteRejected = true; }
        check(incompleteRejected, "update waits for the matching APK asset");

        String releaseHtml = "<a href='https://github.com/luopos/shiyin/releases/tag/v2.3'>Release</a>"
                + "<a href='https://github.com/luopos/shiyin/releases/download/v2.3/shiyin-2.3.apk'>APK</a>";
        AppUpdate.Release pageRelease = AppUpdate.parseReleasePage("v2.3", releaseHtml);
        check("2.3".equals(pageRelease.version), "update parses GitHub release page fallback");
        boolean releaseWithoutAssetRejected = false;
        try {
            AppUpdate.parseReleasePage("v9.9", "<a href='https://github.com/luopos/shiyin/releases/tag/v9.9'>"
                    + "Release</a>");
        } catch (java.io.IOException expected) { releaseWithoutAssetRejected = true; }
        check(releaseWithoutAssetRejected, "update page fallback requires matching APK asset");
        boolean untrustedReleaseUrlRejected = false;
        try {
            AppUpdate.parseReleasePage("v9.9", "<a href='https://evil.example/luopos/shiyin/releases/tag/v9.9'>"
                    + "Release</a><a href='https://evil.example/luopos/shiyin/releases/download/v9.9/shiyin-9.9.apk'>"
                    + "APK</a>");
        } catch (java.io.IOException expected) { untrustedReleaseUrlRejected = true; }
        check(untrustedReleaseUrlRejected, "update page fallback rejects untrusted host");

        Activity mainActivity = startActivitySync(new Intent(getTargetContext(),
                com.shiyin.app.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        try {
            main(() -> {
                try {
                    Field tabsField = com.shiyin.app.MainActivity.class.getDeclaredField("tabs");
                    tabsField.setAccessible(true);
                    TextView[] tabs = (TextView[]) tabsField.get(mainActivity);
                    ((View) tabs[3].getParent()).performClick();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            main(() -> {
                android.widget.ScrollView scroll = findView(
                        mainActivity.getWindow().getDecorView(), android.widget.ScrollView.class, null);
                if (scroll != null) scroll.fullScroll(View.FOCUS_DOWN);
            });
            SystemClock.sleep(150);
            check(onMain(() -> findView(mainActivity.getWindow().getDecorView(),
                    TextView.class, "检查更新") != null), "about page exposes manual update check");
            check(onMain(() -> findView(mainActivity.getWindow().getDecorView(),
                    TextView.class, "GitHub 项目") != null), "about page exposes GitHub project link");
        } finally {
            main(() -> mainActivity.finish());
        }
    }

    private void directoryTest(Map<String, Source> registry) throws Exception {
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch requested = new java.util.concurrent.CountDownLatch(1);
        registry.put("directory-regression", new Source() {
            public String id() { return "directory-regression"; }
            public String name() { return "Regression"; }
            public String audio(Chapter chapter) { return chapter.url; }
            public Detail detail(Book book, boolean episodes) throws Exception {
                requested.countDown();
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                List<Chapter> chapters = new ArrayList<>();
                chapters.add(new Chapter("测试章节", "https://example.invalid/audio"));
                return new Detail(book, chapters);
            }
        });
        android.app.Activity detail = null;
        try {
            Book book = new Book("directory-regression", "loading", "目录加载测试");
            Intent intent = com.shiyin.app.ui.DetailActivity.intent(getTargetContext(), book)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            detail = startActivitySync(intent);
            requested.await(3, java.util.concurrent.TimeUnit.SECONDS);
            final android.app.Activity screen = detail;
            final boolean[] visible = {false};
            main(() -> visible[0] = containsProgress(screen.getWindow().getDecorView()));
            check(visible[0], "directory loading spinner visible");
            release.countDown();
            SystemClock.sleep(400);
            main(() -> visible[0] = containsProgress(screen.getWindow().getDecorView()));
            check(!visible[0], "directory spinner hides after success");
            Chapter chapter = new Chapter("Cache", "url");
            chapter.id = "chapter-id";
            Prefs.cacheChapters("lrts", "regression-cache", java.util.Collections.singletonList(chapter));
            check("chapter-id".equals(Prefs.cachedChapters("lrts", "regression-cache").get(0).id),
                    "cached chapter retains playback identifier");
        } finally {
            release.countDown();
            if (detail != null) { final android.app.Activity screen = detail; main(() -> screen.finish()); }
            registry.remove("directory-regression");
            getTargetContext().getSharedPreferences("shiyin", 0).edit()
                    .remove("ch.directory-regression.loading").remove("ch.lrts.regression-cache").commit();
        }
    }
    private static final int PAGE_SIZE = 50;
    private static final int TOTAL_EPISODES = 105;

    private static final class PageGate {
        final CountDownLatch requested = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Thread worker;

        boolean workerIdle() {
            Thread thread = worker;
            if (thread == null || release.getCount() != 0) return false;
            for (StackTraceElement frame : thread.getStackTrace()) {
                if (frame.getClassName().startsWith("com.shiyin.app.util.Bg$")) return false;
            }
            return true;
        }
    }

    /** 用离线分批源驱动详情页、缓存、播放服务与 MediaPlayer。 */
    private static final class PaginationSource extends Source {
        private final String sourceId = "pagination-regression-" + System.nanoTime();
        private final String path;
        private final Map<String, List<Integer>> requests = new ConcurrentHashMap<>();
        private final AtomicInteger fullRequests = new AtomicInteger();
        private volatile PageGate gate;
        private volatile String blockedBook;
        private volatile String failOnceBook;

        PaginationSource(File audio) { path = audio.getAbsolutePath(); }
        @Override public String id() { return sourceId; }
        @Override public String name() { return "Pagination regression"; }
        @Override public String audio(Chapter chapter) { return chapter.url; }
        @Override public Detail detail(Book book, boolean episodes) throws Exception {
            if (!episodes) return new Detail(book, Collections.emptyList());
            fullRequests.incrementAndGet();
            throw new IOException("分页源不提供整本目录");
        }

        @Override public ChapterPage chapterPage(Book book, int page) throws Exception {
            List<Integer> history = requests.computeIfAbsent(book.id, ignored -> new CopyOnWriteArrayList<>());
            history.add(page);
            if (page == 1 && book.id.equals(failOnceBook)
                    && Collections.frequency(history, 1) == 1) {
                throw new IOException("模拟下一页请求失败");
            }
            PageGate block = gate;
            if (block != null && page == 1 && book.id.equals(blockedBook)) {
                block.worker = Thread.currentThread();
                block.requested.countDown();
                if (!block.release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("测试尚未释放分页请求");
                }
            }
            return makePage(book, page);
        }

        ChapterPage makePage(Book book, int page) {
            List<Chapter> chapters = new ArrayList<>();
            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, TOTAL_EPISODES);
            for (int index = start; index < end; index++) {
                Chapter chapter = new Chapter(book.id + " 第 " + (index + 1) + " 集", path);
                chapter.index = index;
                chapter.id = book.id + "-" + index;
                chapter.bookId = book.id;
                chapters.add(chapter);
            }
            ChapterPage result = new ChapterPage();
            result.meta = book;
            result.chapters = chapters;
            result.page = page;
            result.total = TOTAL_EPISODES;
            result.hasMore = end < TOTAL_EPISODES;
            result.networkPaged = true;
            return result;
        }

        Book book(String id) { return new Book(sourceId, id, "分页回归测试 " + id); }
        List<Integer> requests(Book book) {
            List<Integer> history = requests.get(book.id);
            return history == null ? Collections.emptyList() : new ArrayList<>(history);
        }
        PageGate blockNextPage(Book book) {
            blockedBook = book.id;
            gate = new PageGate();
            return gate;
        }
    }

    private Activity openDetail(Book book) {
        return startActivitySync(DetailActivity.intent(getTargetContext(), book)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private <T extends View> T findView(View root, Class<T> type, String text) {
        if (root.getVisibility() != View.VISIBLE) return null;
        if (type.isInstance(root) && (text == null || (root instanceof TextView
                && ((TextView) root).getText().toString().contains(text)))) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findView(group.getChildAt(i), type, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void clickText(Activity screen, String label) {
        await("可点击的按钮「" + label + "」", () -> onMain(() -> {
            TextView button = findView(screen.getWindow().getDecorView(), TextView.class, label);
            return button != null && button.isEnabled() && button.performClick();
        }));
    }

    private void waitDirectory(Activity screen, int count, int firstIndex) {
        await("显示第 " + (firstIndex + 1) + " 集开始的 " + count + " 条目录",
                () -> onMain(() -> {
                    View root = screen.getWindow().getDecorView();
                    ListView list = findView(root, ListView.class, null);
                    return list != null && list.getCount() == count && !containsProgress(root)
                            && list.getItemAtPosition(0) instanceof Chapter
                            && ((Chapter) list.getItemAtPosition(0)).index == firstIndex;
                }));
    }

    private void startPagedPlayback(Book book, Source.ChapterPage first, int index) {
        main(() -> Player.get().setChapterPage(getTargetContext(), book, first, index));
        waitTrack(book, index, Player.PLAYING);
    }

    private void paginationTest(Map<String, Source> registry, File audio, SharedPreferences prefs)
            throws Exception {
        PaginationSource source = new PaginationSource(audio);
        Activity firstScreen = null;
        Activity historyScreen = null;
        Activity playerScreen = null;
        ActivityMonitor playerMonitor = null;
        Object oldHint = prefs.getAll().get("backgroundHintShown");
        registry.put(source.id(), source);
        try {
            check(Source.CHAPTER_PAGE_SIZE == PAGE_SIZE, "directory batches use 50 episodes");
            Book book = source.book("first");
            firstScreen = openDetail(book);
            waitDirectory(firstScreen, PAGE_SIZE, 0);
            check(source.requests(book).equals(Collections.singletonList(0))
                            && source.fullRequests.get() == 0,
                    "first directory renders 50 episodes using only page 0");

            Source.ChapterPage first = ChapterRepository.cached(book, 0);
            check(first != null && first.page == 0 && first.total == TOTAL_EPISODES
                            && first.hasMore && first.networkPaged && first.chapters.size() == PAGE_SIZE
                            && first.chapters.get(49).index == 49
                            && "first-49".equals(first.chapters.get(49).id)
                            && ChapterRepository.cached(book, 1) == null
                            && Prefs.cachedChapters(book.source, book.id).isEmpty(),
                    "cached partial directory retains metadata and identifiers without becoming a full directory");

            startPagedPlayback(book, first, 49);
            check(onMain(() -> Player.get().chapters().size() == PAGE_SIZE
                            && Player.get().chapterTotal() == TOTAL_EPISODES),
                    "player holds a 50-episode batch and exposes the book total");
            MediaController.TransportControls transport = controller().getTransportControls();
            transport.skipToNext();
            waitTrack(book, 50, Player.PLAYING);
            check(source.requests(book).equals(Arrays.asList(0, 1))
                            && onMain(() -> Player.get().chapters().size() == PAGE_SIZE),
                    "media next crosses from episode 50 to 51 with one page request");
            main(() -> Player.get().saveProgress());
            Prefs.Prog saved = Prefs.progress(book.source, book.id);
            check(saved != null && saved.chapterIndex == 50 && "first 第 51 集".equals(saved.chapterTitle),
                    "cross-page progress persists the global episode index and title");
            transport.skipToPrevious();
            waitTrack(book, 49, Player.PLAYING);
            check(source.requests(book).equals(Arrays.asList(0, 1)),
                    "media previous crosses back from episode 51 to 50 using the cache");

            // 用真实 MediaPlayer 的完成回调，避免靠反射调用私有的跨页方法。
            main(() -> Player.get().seekTo(Player.get().duration() - 300));
            waitTrack(book, 50, Player.PLAYING);
            check(source.requests(book).equals(Arrays.asList(0, 1)),
                    "media completion automatically plays the first episode of the next page");

            clickText(firstScreen, "下一页");
            waitDirectory(firstScreen, PAGE_SIZE, 50);
            clickText(firstScreen, "下一页");
            waitDirectory(firstScreen, 5, 100);
            Source.ChapterPage last = ChapterRepository.cached(book, 2);
            check(source.requests(book).contains(2) && last != null && last.page == 2
                            && last.total == TOTAL_EPISODES && !last.hasMore
                            && last.chapters.get(4).index == 104,
                    "directory next renders global rows and the final five-episode page");
            clickText(firstScreen, "上一页");
            waitDirectory(firstScreen, PAGE_SIZE, 50);
            check(true, "directory previous renders the preceding batch");
            Activity completedDirectory = firstScreen;
            main(completedDirectory::finish);
            firstScreen = null;

            Book retry = source.book("retry");
            source.failOnceBook = retry.id;
            startPagedPlayback(retry, ChapterRepository.load(retry, 0), 49);
            transport.skipToNext();
            waitState(Player.ERROR);
            check(source.requests(retry).equals(Arrays.asList(0, 1))
                            && ChapterRepository.cached(retry, 1) == null,
                    "cross-page fetch failure is surfaced without storing a partial batch");
            main(() -> Player.get().toggle(getTargetContext()));
            waitTrack(retry, 50, Player.PLAYING);
            check(source.requests(retry).equals(Arrays.asList(0, 1, 1)),
                    "toggle retries the failed next page without returning to episode 1");

            Book paused = source.book("pause");
            PageGate pausedPage = source.blockNextPage(paused);
            startPagedPlayback(paused, ChapterRepository.load(paused, 0), 49);
            transport.skipToNext();
            if (!pausedPage.requested.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("未发起可暂停的分页请求");
            }
            transport.pause();
            await("加载下一页时取消自动播放", () -> onMain(() ->
                    Player.get().state() == Player.LOADING && !Player.get().playRequested()));
            pausedPage.release.countDown();
            waitTrack(paused, 50, Player.PAUSED);
            check(onMain(() -> !Player.get().isPlaying() && Player.get().duration() > 0),
                    "pause during cross-page loading survives request and media preparation");
            transport.play();
            waitTrack(paused, 50, Player.PLAYING);

            Book stale = source.book("stale");
            PageGate stalePage = source.blockNextPage(stale);
            startPagedPlayback(stale, ChapterRepository.load(stale, 0), 49);
            transport.skipToNext();
            if (!stalePage.requested.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("未发起待隔离的旧书分页请求");
            }
            Book target = source.book("replacement");
            startPagedPlayback(target, ChapterRepository.load(target, 0), 8);
            stalePage.release.countDown();
            await("旧书第 2 批目录完成", () -> ChapterRepository.cached(stale, 1) != null);
            waitForIdleSync();
            transport.skipToNext();
            waitTrack(target, 9, Player.PLAYING);
            check(source.requests(target).equals(Collections.singletonList(0)),
                    "stale page completion cannot replace the new book queue");

            Book resume = source.book("history");
            Prefs.saveProgress(resume.source, resume.id, 100, 18000, 120000, "保存的第 101 集");
            Prefs.markBackgroundHintShown();
            playerMonitor = addMonitor(PlayerActivity.class.getName(), null, false);
            historyScreen = openDetail(resume);
            waitDirectory(historyScreen, 5, 100);
            check(source.requests(resume).equals(Collections.singletonList(2)),
                    "history opens the page containing episode 101 without requesting preceding pages");
            clickText(historyScreen, "继续第 101 集");
            playerScreen = waitForMonitorWithTimeout(playerMonitor, 5000);
            check(playerScreen instanceof PlayerActivity, "history continue opens the player screen");
            waitTrack(resume, 100, Player.PLAYING);
            await("恢复第 101 集的播放位置", () -> position() >= 17500);
            check(source.requests(resume).equals(Collections.singletonList(2))
                            && source.fullRequests.get() == 0,
                    "continuing history restores the global episode and seek using only page 2");
        } finally {
            if (source.gate != null) source.gate.release.countDown();
            if (playerMonitor != null) removeMonitor(playerMonitor);
            Activity closeDetail = historyScreen;
            Activity closeFirst = firstScreen;
            Activity closePlayer = playerScreen;
            main(() -> {
                if (closePlayer != null) closePlayer.finish();
                if (closeDetail != null) closeDetail.finish();
                if (closeFirst != null && !closeFirst.isFinishing()) closeFirst.finish();
                Player.get().stop(getTargetContext());
            });
            waitForIdleSync();
            registry.remove(source.id());
            SharedPreferences.Editor cleanup = prefs.edit();
            for (String key : prefs.getAll().keySet()) {
                if (key.contains(source.id() + ".")) cleanup.remove(key);
            }
            if (oldHint == null) cleanup.remove("backgroundHintShown");
            else cleanup.putBoolean("backgroundHintShown", (Boolean) oldHint);
            cleanup.commit();
        }
    }

    private void clearTestPageCaches() {
        SharedPreferences pages = getTargetContext().getSharedPreferences("shiyin_chapter_pages", 0);
        SharedPreferences.Editor editor = pages.edit();
        for (String key : pages.getAll().keySet()) {
            if (key.contains("paged-regression") || key.contains("directory-regression")) editor.remove(key);
        }
        editor.commit();
    }

    private void screenshot(String name) throws Exception {
        waitForIdleSync();
        SystemClock.sleep(350);
        android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
        if (image == null) throw new AssertionError("截图失败");
        File file = new File(getTargetContext().getExternalFilesDir(null), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { image.recycle(); }
    }

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        launchArguments = arguments;
        start();
    }
    @Override public void onStart() {
        Bundle outcome = new Bundle();
        if (launchArguments != null && launchArguments.getString("catalog_live") != null) {
            Activity activity = null;
            try {
                Prefs.init(getTargetContext());
                com.shiyin.app.source.Subscriptions.install(launchArguments.getString("catalog_live"));
                Prefs.expireHome();
                int rootCount = 0;
                for (Source source : Sources.all()) {
                    if (!source.hasCats()) continue;
                    List<com.shiyin.app.model.Cat> cats = source.cats();
                    rootCount += cats.size();
                    check(!cats.isEmpty() && !cats.get(0).children.isEmpty(),
                            "live categories contain roots and children: " + source.id());
                    com.shiyin.app.model.Cat leaf = cats.get(0).children.get(0);
                    Source.Page first = source.page(leaf, 1);
                    Source.Page second = source.page(leaf, 2);
                    check(!first.books.isEmpty() && !second.books.isEmpty()
                            && !first.books.get(0).id.equals(second.books.get(0).id),
                            "live category pagination returns distinct nonempty pages: " + source.id());
                }
                check(rootCount > 0, "live subscription registers browseable categories: " + rootCount);
                com.shiyin.app.source.JsonSource tingyou = (com.shiyin.app.source.JsonSource) Sources.byId("tingyou");
                List<com.shiyin.app.source.JsonSource.HomeSection> sections = tingyou.home();
                Book sample = sections.get(0).books.get(0);
                check(!sample.cover.isEmpty(), "source3 home maps cover_url into a cover address");
                com.shiyin.app.net.Http.Resp image = com.shiyin.app.net.Http.get(sample.cover, null,
                        com.shiyin.app.net.Http.UA);
                check(image.ok() && android.graphics.BitmapFactory.decodeByteArray(
                        image.bytes, 0, image.bytes.length) != null,
                        "source3 cover downloads and decodes on Android");
                List<Book> searched = tingyou.search("三国", 1);
                check(!searched.isEmpty() && !searched.get(0).cover.isEmpty(),
                        "source3 search also returns cover addresses");
                Source.Detail detail = tingyou.detail(sample, false);
                check(detail.meta != null && !detail.meta.cover.isEmpty(),
                        "source3 detail also returns a cover address");
                activity = startHome();
                selectMainTab(activity, 1);
                final Activity opened = activity;
                await("live categories rendered", () -> onMain(() -> findView(opened.getWindow()
                        .getDecorView(), TextView.class, "有声小说") != null));
                check(onMain(() -> findView(opened.getWindow().getDecorView(), TextView.class,
                        "正在加载分类…") == null), "live categories render from local cache");
                screenshot("categories-28.png");
                selectMainTab(activity, 0);
                await("home updated", () -> onMain(() -> {
                    try { return !(Boolean) activityField(opened, "homeLoading"); }
                    catch (Exception e) { throw new AssertionError(e); }
                }));
                outcome.putString("stream", results.toString()); finish(-1, outcome);
            } catch (Throwable failure) {
                outcome.putString("stream", results.toString() + "FAIL live catalog " + failure);
                finish(0, outcome);
            } finally {
                if (activity != null) { final Activity last = activity; main(() -> last.finish()); }
            }
            return;
        }
        if (launchArguments != null && "1".equals(launchArguments.getString("home_probe"))) {
            Activity activity = null;
            try {
                Prefs.init(getTargetContext());
                com.shiyin.app.source.Subscriptions.reload();
                int sections = 0;
                for (Source source : Sources.all()) {
                    if (!(source instanceof com.shiyin.app.source.JsonSource)) continue;
                    Prefs.CachedHome cached = Prefs.cachedHome(source.id(),
                            ((com.shiyin.app.source.JsonSource) source).homeCacheVersion());
                    if (cached != null) sections += cached.sections.size();
                }
                check(sections > 0, "cold process restores persisted home sections: " + sections);
                activity = startHome();
                final Activity opened = activity;
                check(onMain(() -> !containsProgress(opened.getWindow().getDecorView())),
                        "cold process home displays cache without a loading indicator");
                check(!(Boolean) activityField(activity, "homeLoading"),
                        "cold process skips recommendation refresh within the cycle");
                selectMainTab(activity, 2); selectMainTab(activity, 0);
                check(!(Boolean) activityField(activity, "homeLoading"),
                        "returning home after cold launch still makes no refresh");
                screenshot("home-cache-28.png");
                outcome.putString("stream", results.toString());
                finish(-1, outcome);
            } catch (Throwable failure) {
                outcome.putString("stream", results.toString() + "FAIL cold cache " + failure);
                finish(0, outcome);
            } finally {
                if (activity != null) { final Activity last = activity; main(() -> last.finish()); }
            }
            return;
        }
        try {
            subscriptionTest();
            homeCacheTest();
            categoryTest();
            updateLogicTest();
            updateDownloadTest();
        } catch (Throwable failure) {
            outcome.putString("stream", "FAIL update checks: " + failure);
            finish(0, outcome);
            return;
        }
        if (launchArguments != null && launchArguments.getString("subs_url") != null) {
            try {
                String url = launchArguments.getString("subs_url");
                com.shiyin.app.source.Subscriptions.install(url);
                check(!com.shiyin.app.source.Subscriptions.isStale(url),
                        "installed subscription is cached and fresh, so startup skips the network");
                java.util.List<Source> all = Sources.all();
                check(!all.isEmpty(), "remote subscription registered sources");
                Source first = null;
                for (Source s : all) if (s.searchable()) { first = s; break; }
                check(first != null, "remote subscription has a searchable source");
                List<Book> books = first.search("三国", 1);
                check(!books.isEmpty(), "remote subscription search returned books: " + first.id());
                Source.Detail detail = first.detail(books.get(0), true);
                check(!detail.chapters.isEmpty(), "remote subscription detail returned chapters: " + first.id());
                String audio = first.audio(detail.chapters.get(0));
                check(audio.startsWith("http"), "remote subscription play url resolved: " + first.id());
                outcome.putString("stream", results.toString() + "SOURCES "
                        + all.size() + " FIRST " + first.id() + " BOOKS " + books.size()
                        + " CHAPTERS " + detail.chapters.size());
                finish(-1, outcome);
            } catch (Throwable failure) {
                outcome.putString("stream", results.toString() + "FAIL remote subscription " + failure);
                finish(0, outcome);
            }
            return;
        }
        if (launchArguments != null && launchArguments.getString("subs_cache") != null) {
            try {
                String url = launchArguments.getString("subs_cache");
                com.shiyin.app.source.Subscriptions.install(url);
                check(com.shiyin.app.source.Subscriptions.urls().size() == 1,
                        "cache check starts from a single subscription");
                check(!com.shiyin.app.source.Subscriptions.isStale(url),
                        "fresh subscription cache is valid");
                check(com.shiyin.app.source.Subscriptions.refreshStale() == 0,
                        "fresh cache skips the network entirely");

                // 把时间戳改到有效期之前，模拟隔天启动。
                SharedPreferences prefs = getTargetContext().getSharedPreferences("shiyin", 0);
                SharedPreferences.Editor editor = prefs.edit();
                long expired = System.currentTimeMillis()
                        - com.shiyin.app.source.Subscriptions.CACHE_MAX_AGE_MS - 60000;
                int aged = 0;
                for (String key : prefs.getAll().keySet()) {
                    if (key.startsWith("sub.at.")) {
                        editor.putLong(key, expired);
                        aged++;
                    }
                }
                editor.commit();
                check(aged > 0, "subscription cache timestamp can be aged for the test");
                check(com.shiyin.app.source.Subscriptions.isStale(url),
                        "expired subscription cache is stale");
                check(com.shiyin.app.source.Subscriptions.refreshStale() == 1,
                        "expired cache is refreshed exactly once in the background");
                check(!com.shiyin.app.source.Subscriptions.isStale(url),
                        "background refresh renews the cache timestamp");
                check(!Sources.all().isEmpty(), "refreshed subscription keeps its sources");
                outcome.putString("stream", results.toString() + "CACHE "
                        + com.shiyin.app.source.Subscriptions.cacheAge(url));
                finish(-1, outcome);
            } catch (Throwable failure) {
                outcome.putString("stream", results.toString() + "FAIL subscription cache " + failure);
                finish(0, outcome);
            }
            return;
        }
        if (launchArguments != null && launchArguments.getString("subs_live") != null) {
            try {
                String url = launchArguments.getString("subs_live");
                com.shiyin.app.source.Subscriptions.install(url);
                Source source = Sources.byId("demo");
                check(source != null, "live subscription registered its source");
                List<Book> books = source.search("测试", 1);
                check(books.size() == 2 && "第一本".equals(books.get(0).title),
                        "live subscription search parsed books");
                Source.Detail detail = source.detail(books.get(0), true);
                check(detail.chapters.size() == 3 && "第3集".equals(detail.chapters.get(2).title),
                        "live subscription detail parsed chapters");
                String audio = source.audio(detail.chapters.get(0));
                check(audio.startsWith("https://"), "live subscription play url resolved");
                com.shiyin.app.source.Subscriptions.remove(url);
                check(Sources.byId("demo") == null, "removing subscription unregisters its source");
                outcome.putString("stream", results.toString());
                finish(-1, outcome);
            } catch (Throwable failure) {
                outcome.putString("stream", results.toString() + "FAIL live subscription " + failure);
                finish(0, outcome);
            }
            return;
        }
        if (launchArguments != null && "1".equals(launchArguments.getString("update_live"))) {
            try {
                AppUpdate.Release latest = AppUpdate.latest();
                check(latest.version.length() > 0 && latest.pageUrl.startsWith(AppUpdate.RELEASES_URL),
                        "live GitHub latest release and asset discovered");
                outcome.putString("stream", results.toString() + "LATEST " + latest.version);
                finish(-1, outcome);
            } catch (Throwable failure) {
                outcome.putString("stream", results.toString() + "FAIL live GitHub update " + failure);
                finish(0, outcome);
            }
            return;
        }
        clearTestPageCaches();
        Source previous = null;
        Map<String, Source> registry = null;
        File audio = null;
        AudioManager manager = null;
        AudioFocusRequest focus = null;
        android.content.SharedPreferences preferences = getTargetContext()
                .getSharedPreferences("shiyin", android.content.Context.MODE_PRIVATE);
        String originalLastBook = preferences.getString("lastBook", null);
        String originalProgress = preferences.getString("p.regression.background", null);
        try {
            getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
            audio = new File(getTargetContext().getCacheDir(), "regression.wav");
            // 120 秒低音量 PCM，避免外部接口、网络缓冲干扰状态测试。
            int bytes = 8000 * 2 * 120;
            try (FileOutputStream out = new FileOutputStream(audio)) {
                java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(44)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                header.put(new byte[]{82,73,70,70}).putInt(36 + bytes)
                        .put(new byte[]{87,65,86,69,102,109,116,32}).putInt(16)
                        .putShort((short)1).putShort((short)1).putInt(8000).putInt(16000)
                        .putShort((short)2).putShort((short)16)
                        .put(new byte[]{100,97,116,97}).putInt(bytes);
                out.write(header.array());
                byte[] chunk = new byte[16000];
                for (int n = 0; n < 8000; n++) {
                    short value = (short)(Math.sin(2 * Math.PI * 220 * n / 8000) * 256);
                    chunk[n * 2] = (byte)value; chunk[n * 2 + 1] = (byte)(value >> 8);
                }
                for (int second = 0; second < 120; second++) out.write(chunk);
            }
            Field field = Sources.class.getDeclaredField("MAP");
            field.setAccessible(true);
            registry = (Map<String, Source>) field.get(null);
            Source local = new Source() {
                public String id() { return "regression"; }
                public String name() { return "Regression"; }
                public String audio(Chapter chapter) { return chapter.url; }
            };
            previous = registry.put("regression", local);
            directoryTest(registry);
            Book book = new Book("regression", "background", "后台播放回归测试");
            List<Chapter> chapters = new ArrayList<>();
            chapters.add(new Chapter("第一章", audio.getAbsolutePath()));
            chapters.add(new Chapter("第二章", audio.getAbsolutePath()));
            final List<Chapter> list = chapters;
            main(() -> {
                Player.get().setPlaylist(getTargetContext(), book, list, 0);
                Player.get().toggle(getTargetContext());
            });
            waitState(Player.PAUSED);
            check(true, "pause during prepare prevents autoplay");
            main(() -> Player.get().resume(getTargetContext()));
            waitState(Player.PLAYING);
            check(true, "local playback started");
            MediaController controls = controller();
            check(controls.getMetadata() != null, "system media metadata present");
            int before = position();
            SystemClock.sleep(1700);
            check(position() > before + 900, "background progress advances");
            controls.getTransportControls().pause();
            waitState(Player.PAUSED);
            before = position();
            SystemClock.sleep(700);
            check(Math.abs(position() - before) < 350, "media pause stops progress");
            controls.getTransportControls().play();
            waitState(Player.PLAYING);
            check(true, "media play resumes");
            controls.getTransportControls().seekTo(12000);
            SystemClock.sleep(600);
            check(position() >= 11500, "system seek works");
            manager = getTargetContext().getSystemService(AudioManager.class);
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(change -> {}).build();
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    "competing audio focus granted");
            waitState(Player.PAUSED);
            check(true, "focus loss pauses speech");
            manager.abandonAudioFocusRequest(focus);
            focus = null;
            waitState(Player.PLAYING);
            check(true, "transient focus return resumes");
            Field receiverField = PlayerService.class.getDeclaredField("noisyReceiver");
            receiverField.setAccessible(true);
            Field serviceField = PlayerService.class.getDeclaredField("instance");
            serviceField.setAccessible(true);
            final Object service = serviceField.get(null);
            final android.content.BroadcastReceiver noisy =
                    (android.content.BroadcastReceiver) receiverField.get(service);
            main(() -> noisy.onReceive(getTargetContext(),
                    new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)));
            waitState(Player.PAUSED);
            check(true, "headphone disconnect handler pauses");
            controls.getTransportControls().play();
            waitState(Player.PLAYING);
            SystemClock.sleep(5600);
            Prefs.Prog saved = Prefs.progress("regression", "background");
            check(saved != null && saved.position >= 12000, "background progress persisted");
            controls.getTransportControls().skipToNext();
            await("system next chapter playback", () -> onMain(() ->
                    Player.get().index() == 1 && Player.get().state() == Player.PLAYING
                            && Player.get().isPlaying()));
            check(true, "system next chapter works");
            controls.getTransportControls().skipToPrevious();
            await("system previous chapter playback", () -> onMain(() ->
                    Player.get().index() == 0 && Player.get().state() == Player.PLAYING
                            && Player.get().isPlaying()));
            check(true, "system previous chapter works");
            controls.getTransportControls().stop();
            waitState(Player.IDLE);
            SystemClock.sleep(500);
            check(getTargetContext().getSystemService(MediaSessionManager.class)
                    .getActiveSessions(null).stream().noneMatch(c ->
                            getTargetContext().getPackageName().equals(c.getPackageName())),
                    "stop releases media session");
            paginationTest(registry, audio, preferences);
            main(() -> Player.get().setPlaylist(getTargetContext(), book, Collections.emptyList(), 0));
            waitState(Player.IDLE);
            check(onMain(() -> Player.get().book() == null && Player.get().index() == -1
                    && Player.get().chapters().isEmpty()), "empty queue releases playback and clears stale state");
            outcome.putString("stream", results.toString());
            finish(-1, outcome);
        } catch (Throwable failure) {
            outcome.putString("stream", results.toString() + "FAIL " + failure);
            finish(0, outcome);
        } finally {
            if (focus != null && manager != null) manager.abandonAudioFocusRequest(focus);
            main(() -> Player.get().stop(getTargetContext()));
            if (registry != null) {
                if (previous == null) registry.remove("regression");
                else registry.put("regression", previous);
            }
            if (audio != null) audio.delete();
            android.content.SharedPreferences.Editor restore = preferences.edit();
            if (originalLastBook == null) restore.remove("lastBook");
            else restore.putString("lastBook", originalLastBook);
            if (originalProgress == null) restore.remove("p.regression.background");
            else restore.putString("p.regression.background", originalProgress);
            restore.commit();
            clearTestPageCaches();
            getUiAutomation().dropShellPermissionIdentity();
        }
    }
}

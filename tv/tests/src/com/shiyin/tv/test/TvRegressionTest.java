package com.shiyin.tv.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.shiyin.app.model.Book;
import com.shiyin.app.source.Source;
import com.shiyin.app.source.Sources;
import com.shiyin.app.source.Subscriptions;
import com.shiyin.app.store.Prefs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TV 版回归测试（instrumentation，无 AndroidX）。
 *
 * <p>用本地 HTTP fixture 当订阅源，走真实链路：订阅 JSON → Sources 注册 →
 * 首页/分类请求 → 真实 D-pad 按键 → 详情 Intent。
 * 断言点：横屏、首页书卡渲染、焦点移动与确认键、分类两级、书架/我的、订阅增删。
 */
public final class TvRegressionTest extends Instrumentation {

    private static final String TAG = "ShiyinTvRegression";
    private static final String FIXTURE_ID = "tv-fixture";
    private static final String FIXTURE_URL = "https://fixture.invalid/tv-sources.json";

    private final StringBuilder results = new StringBuilder();
    private Bundle launchArguments;

    /* ---------------- 基础工具 ---------------- */

    private void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        results.append("PASS ").append(name).append('\n');
        Log.i(TAG, "PASS " + name);
    }

    private void note(String line) {
        results.append("NOTE ").append(line).append('\n');
        Log.i(TAG, "NOTE " + line);
    }

    private void main(Runnable action) {
        runOnMainSync(action);
    }

    private void await(String description, Condition condition) {
        long deadline = SystemClock.elapsedRealtime() + 12000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.ok()) return;
            SystemClock.sleep(60);
        }
        throw new AssertionError("等待超时：" + description);
    }

    private interface Condition {
        boolean ok();
    }

    private boolean onMain(Condition condition) {
        final boolean[] value = {false};
        main(new Runnable() {
            @Override
            public void run() {
                value[0] = condition.ok();
            }
        });
        return value[0];
    }

    private <T extends View> T findView(View root, Class<T> type, String text) {
        if (root.getVisibility() != View.VISIBLE) return null;
        if (type.isInstance(root) && (text == null
                || (root instanceof TextView
                && ((TextView) root).getText().toString().contains(text)))) {
            return type.cast(root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findView(group.getChildAt(i), type, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 找到文本所在的可聚焦祖先（书卡最外层才是 focusable）。 */
    private View focusableAncestor(View view) {
        View cur = view;
        while (cur != null) {
            if (cur.isFocusable()) return cur;
            android.view.ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private Activity startMain() {
        Intent intent = new Intent();
        intent.setClassName(getTargetContext(), "com.shiyin.tv.TvMainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return startActivitySync(intent);
    }

    /* ---------------- 本地 fixture ---------------- */

    private ServerSocket server;
    private Thread serverThread;
    private volatile boolean serving;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<String>());

    private void startFixtureServer() throws Exception {
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        serving = true;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (serving) {
                    Socket socket = null;
                    try {
                        socket = server.accept();
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), "UTF-8"));
                        String request = reader.readLine();
                        requests.add(request == null ? "" : request);
                        String line;
                        while ((line = reader.readLine()) != null && line.length() > 0) {
                            // 吃掉请求头。
                        }
                        String body = responseFor(request == null ? "" : request);
                        byte[] bytes = body.getBytes("UTF-8");
                        String header = "HTTP/1.1 200 OK\r\n"
                                + "Content-Type: application/json; charset=utf-8\r\n"
                                + "Content-Length: " + bytes.length + "\r\n"
                                + "Connection: close\r\n\r\n";
                        OutputStream out = socket.getOutputStream();
                        out.write(header.getBytes("US-ASCII"));
                        out.write(bytes);
                        out.flush();
                    } catch (Exception ignored) {
                        if (!serving) break;
                    } finally {
                        if (socket != null) {
                            try {
                                socket.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        }, "tv-fixture-http");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private String responseFor(String request) {
        if (request.contains("/home")) {
            return "{\"items\":["
                    + "{\"id\":\"b1\",\"title\":\"电视测试书一\",\"author\":\"作者甲\","
                    + "\"cover\":\"\"},"
                    + "{\"id\":\"b2\",\"title\":\"电视测试书二\",\"author\":\"作者乙\","
                    + "\"cover\":\"\"}]}";
        }
        if (request.contains("/cats")) {
            return "{\"items\":[{\"id\":\"c1\",\"name\":\"测试分类\",\"desc\":\"fixture\","
                    + "\"children\":[{\"id\":\"c1a\",\"name\":\"子分类甲\"}]}]}";
        }
        if (request.contains("/list")) {
            return "{\"items\":["
                    + "{\"id\":\"x1\",\"title\":\"分类书目甲\"},"
                    + "{\"id\":\"x2\",\"title\":\"分类书目乙\"}],\"totalPage\":1}";
        }
        return "{}";
    }

    private String fixtureSpec() {
        String base = "http://127.0.0.1:" + server.getLocalPort();
        return "{\"format\":\"shiyin-sources\",\"version\":1,\"sources\":[{"
                + "\"id\":\"" + FIXTURE_ID + "\",\"name\":\"电视测试源\","
                + "\"desc\":\"本地 fixture\","
                + "\"home\":{\"url\":\"" + base + "/home\",\"items\":\"items\","
                + "\"fields\":{\"id\":\"id\",\"title\":\"title\",\"author\":\"author\","
                + "\"cover\":\"cover\"}},"
                + "\"categories\":{\"url\":\"" + base + "/cats\",\"items\":\"items\","
                + "\"fields\":{\"id\":\"id\",\"name\":\"name\",\"desc\":\"desc\","
                + "\"children\":\"children\"},"
                + "\"page\":{\"url\":\"" + base + "/list?cat={cat}&page={page}\","
                + "\"items\":\"items\","
                + "\"fields\":{\"id\":\"id\",\"title\":\"title\"},"
                + "\"totalPage\":\"totalPage\"}}"
                + "}]}";
    }

    private void installFixture() {
        Prefs.putSubscription(FIXTURE_URL, fixtureSpec());
        Subscriptions.reload();
        if (Sources.byId(FIXTURE_ID) == null) throw new AssertionError("fixture 源未注册");
    }

    private void stopFixtureServer() {
        serving = false;
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            server = null;
        }
        if (serverThread != null) {
            try {
                serverThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            serverThread = null;
        }
    }

    /* ---------------- 测试项 ---------------- */

    private void apiInfo() {
        note("Build.VERSION.SDK_INT=" + Build.VERSION.SDK_INT
                + " release=" + Build.VERSION.RELEASE + " model=" + Build.MODEL);
        boolean hasChannel;
        try {
            Class.forName("android.app.NotificationChannel");
            hasChannel = true;
        } catch (Throwable missing) {
            hasChannel = false;
        }
        note("运行环境是否有 android.app.NotificationChannel（仅环境说明，不作断言）：" + hasChannel);
        check(Build.VERSION.SDK_INT >= 16, "target device is API16 or newer");
        check(getTargetContext().getPackageName().equals("com.shiyin.tv"),
                "instrumentation targets com.shiyin.tv");
    }

    private void subscriptionTest() {
        // 从干净状态开始，避免历史订阅干扰。
        for (String url : new ArrayList<String>(Subscriptions.urls())) {
            Subscriptions.remove(url);
        }
        check(Sources.all().isEmpty(), "fresh install has no built-in sources");
        String spec = fixtureSpec();
        Prefs.putSubscription(FIXTURE_URL, spec);
        Subscriptions.reload();
        Source added = Sources.byId(FIXTURE_ID);
        check(added != null, "adding a subscription registers its source");
        check(Sources.all().size() == 1, "source registry reflects the single subscription");
        check(Subscriptions.cacheAge(FIXTURE_URL).contains("更新"),
                "subscription cache age is human readable");
        Subscriptions.remove(FIXTURE_URL);
        check(Sources.byId(FIXTURE_ID) == null, "removing a subscription unregisters its source");
        check(Sources.all().isEmpty(), "source registry is empty after removal");
    }

    private void homeAndDpadTest() throws Exception {
        installFixture();
        Activity main = startMain();
        try {
            check(onMain(new Condition() {
                @Override
                public boolean ok() {
                    return mainActivityOrientation(main) == Configuration.ORIENTATION_LANDSCAPE;
                }
            }), "main activity runs in landscape");
            // 按键注入要求目标窗口真的在前台；模拟器上偶尔会被系统弹窗抢焦点。
            await("主界面获得窗口焦点", new Condition() {
                @Override
                public boolean ok() {
                    return main.hasWindowFocus();
                }
            });

            await("首页渲染书卡", new Condition() {
                @Override
                public boolean ok() {
                    return onMain(new Condition() {
                        @Override
                        public boolean ok() {
                            return findView(main.getWindow().getDecorView(), TextView.class,
                                    "电视测试书一") != null;
                        }
                    });
                }
            });
            check(true, "home renders book titles from the subscription fixture");
            check(onMain(new Condition() {
                @Override
                public boolean ok() {
                    return findView(main.getWindow().getDecorView(), TextView.class,
                            "电视测试源") != null;
                }
            }), "home groups books into a per-source section");

            // 焦点放到第一本书卡，再用真实按键向右移动。
            main(new Runnable() {
                @Override
                public void run() {
                    TextView title = findView(main.getWindow().getDecorView(), TextView.class,
                            "电视测试书一");
                    View card = focusableAncestor(title);
                    if (card == null) throw new AssertionError("书卡不可聚焦");
                    check(card.requestFocusFromTouch(), "first book card accepts D-pad focus");
                }
            });
            final View[] before = new View[1];
            main(new Runnable() {
                @Override
                public void run() {
                    before[0] = main.getCurrentFocus();
                }
            });
            check(before[0] != null, "home has a focused view before key events");
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            SystemClock.sleep(250);
            final View[] after = new View[1];
            main(new Runnable() {
                @Override
                public void run() {
                    after[0] = main.getCurrentFocus();
                }
            });
            check(after[0] != null && after[0] != before[0],
                    "DPAD_RIGHT moves focus between book cards");
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            SystemClock.sleep(250);
            check(true, "DPAD_DOWN is handled without crash");

            // 详情页由 impl-c 提供；类存在就必须能被 ActivityMonitor 捕获，
            // 不存在时主界面必须只是 Toast，不允许崩溃。
            boolean detailExists;
            try {
                Class.forName("com.shiyin.tv.TvDetailActivity");
                detailExists = true;
            } catch (Throwable missing) {
                detailExists = false;
            }
            final String[] focusInfo = new String[1];
            main(new Runnable() {
                @Override
                public void run() {
                    View focused = main.getCurrentFocus();
                    focusInfo[0] = focused == null ? "null"
                            : focused.getClass().getName() + " clickable=" + focused.isClickable()
                            + " focusable=" + focused.isFocusable() + " w=" + focused.getWidth();
                }
            });
            note("focus before ENTER: " + focusInfo[0]);
            ActivityMonitor monitor = addMonitor("com.shiyin.tv.TvDetailActivity", null, false);
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            if (detailExists) {
                Activity detail = waitForMonitorWithTimeout(monitor, 4000);
                note("monitor result: " + detail + " / focus=" + focusInfo[0]);
                check(detail != null, "ENTER on a book card opens TvDetailActivity");
                if (detail != null) {
                    final Activity close = detail;
                    main(new Runnable() {
                        @Override
                        public void run() {
                            close.finish();
                        }
                    });
                }
            } else {
                SystemClock.sleep(600);
                note("TvDetailActivity 尚未落地：ENTER 走 Nav 的反射降级（Toast），不算失败");
                check(true, "ENTER without TvDetailActivity degrades gracefully");
            }
            removeMonitor(monitor);

            // 切到分类：内容区最左侧按 LEFT 回导航栏，再 DOWN 到「分类」。
            splashTabs(main);
        } finally {
            final Activity close = main;
            main(new Runnable() {
                @Override
                public void run() {
                    close.finish();
                }
            });
            waitForIdleSync();
        }
    }

    private int mainActivityOrientation(Activity activity) {
        return activity.getResources().getConfiguration().orientation;
    }

    /** 找到文本完全相等的 TextView（避免「分类」匹配到「返回分类」）。 */
    private TextView findExactText(View root, String text) {
        if (root.getVisibility() != View.VISIBLE) return null;
        if (root instanceof TextView) {
            String value = ((TextView) root).getText().toString();
            if (value.equals(text)) return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findExactText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 真实按键切 tab：焦点落到左栏导航项，再按 OK。 */
    private void openNav(final Activity main, final String label) {
        main(new Runnable() {
            @Override
            public void run() {
                TextView text = findExactText(main.getWindow().getDecorView(), label);
                if (text == null) throw new AssertionError("找不到导航项：" + label);
                View item = (View) text.getParent();
                check(item.requestFocusFromTouch(), "nav item accepts focus: " + label);
            }
        });
        SystemClock.sleep(200);
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        SystemClock.sleep(900);
    }

    private void splashTabs(Activity main) {
        openNav(main, "分类");
        await("分类根列表出现 fixture 分类", new Condition() {
            @Override
            public boolean ok() {
                return onMain(new Condition() {
                    @Override
                    public boolean ok() {
                        return findView(main.getWindow().getDecorView(), TextView.class,
                                "测试分类") != null;
                    }
                });
            }
        });
        check(true, "category tab lists root categories from the subscription");
        screenshot("tv-cats.png");

        // 切 tab 后焦点仍留在左栏（TV 常见交互），RIGHT 进内容区，OK 打开根分类。
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        SystemClock.sleep(600);
        final String[] afterRight = new String[1];
        main(new Runnable() {
            @Override
            public void run() {
                View f = main.getCurrentFocus();
                afterRight[0] = f == null ? "null" : f.getClass().getName()
                        + " clickable=" + f.isClickable() + " focusable=" + f.isFocusable();
            }
        });
        note("focus after RIGHT on cats: " + afterRight[0]);
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        SystemClock.sleep(1200);
        await("子分类书目出现", new Condition() {
            @Override
            public boolean ok() {
                return onMain(new Condition() {
                    @Override
                    public boolean ok() {
                        return findView(main.getWindow().getDecorView(), TextView.class,
                                "分类书目甲") != null;
                    }
                });
            }
        });
        check(onMain(new Condition() {
            @Override
            public boolean ok() {
                return findView(main.getWindow().getDecorView(), TextView.class,
                        "子分类甲") != null;
            }
        }), "category subpage shows child chips and books");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        SystemClock.sleep(700);
        check(onMain(new Condition() {
            @Override
            public boolean ok() {
                return findView(main.getWindow().getDecorView(), TextView.class,
                        "测试分类") != null;
            }
        }), "BACK returns from the category detail to the root list");

        openNav(main, "我的");
        check(onMain(new Condition() {
            @Override
            public boolean ok() {
                return findView(main.getWindow().getDecorView(), TextView.class,
                        "添加订阅") != null;
            }
        }), "mine tab exposes subscription management");
        check(onMain(new Condition() {
            @Override
            public boolean ok() {
                return findView(main.getWindow().getDecorView(), TextView.class,
                        "电视测试源") != null;
            }
        }), "mine tab lists the installed source");
        screenshot("tv-mine.png");

        openNav(main, "书架");
        check(onMain(new Condition() {
            @Override
            public boolean ok() {
                return findView(main.getWindow().getDecorView(), TextView.class,
                        "我的书架") != null;
            }
        }), "shelf tab renders its own page");
        screenshot("tv-shelf.png");
    }

    private void screenshot(String name) {
        try {
            waitForIdleSync();
            SystemClock.sleep(400);
            android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
            if (image == null) return;
            java.io.File dir = getTargetContext().getExternalFilesDir(null);
            java.io.File file = new java.io.File(dir, name);
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            try {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                out.close();
                image.recycle();
            }
            note("screenshot " + file.getAbsolutePath());
        } catch (Throwable failure) {
            note("screenshot failed: " + failure);
        }
    }

    /* ---------------- 入口 ---------------- */

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        launchArguments = arguments;
        start();
    }

    @Override
    public void onStart() {
        Bundle outcome = new Bundle();
        Activity main = null;
        try {
            Prefs.init(getTargetContext());
            startFixtureServer();
            apiInfo();
            subscriptionTest();
            homeAndDpadTest();
            outcome.putString("stream", results.toString());
            finish(-1, outcome);
        } catch (Throwable failure) {
            outcome.putString("stream", results.toString() + "FAIL " + failure);
            finish(0, outcome);
        } finally {
            stopFixtureServer();
            try {
                Subscriptions.remove(FIXTURE_URL);
            } catch (Throwable ignored) {
            }
        }
    }
}

package com.shiyin.tv;

import android.app.Application;

import com.shiyin.app.kid.KidMode;
import com.shiyin.app.source.Subscriptions;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.util.Bg;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;

/**
 * TV 版全局初始化：和手机版走同一套共享层，只是没有内置源。
 *
 * <p>顺序必须是 Prefs.init → 迁移 → reload → 后台刷新，别把网络放到启动前。
 */
public class TvApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // 源站有会话依赖（先列表页拿 cookie 再取详情），与手机版保持一致。
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookies);

        Prefs.init(this);
        // 青少年模式内核与手机版同一份实现，密码存独立的 shiyin_kid 文件。
        KidMode.init(this);
        try {
            Prefs.migrateSubscriptionSchema();
        } catch (Throwable ignored) {
            // 迁移失败不该拦住启动。
        }
        // 断网也能用：先把磁盘里的订阅注册成源，再在后台按周期静默刷新。
        Subscriptions.reload();
        Bg.fire(new Runnable() {
            @Override
            public void run() {
                try {
                    Subscriptions.refreshStale();
                } catch (Throwable ignored) {
                    // 后台刷新失败保留旧缓存。
                }
            }
        });
    }
}

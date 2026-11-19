package com.shiyin.app;

import android.app.Application;

import com.shiyin.app.source.Subscriptions;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.util.Bg;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;

/** 全局初始化。 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // 源站要会话（先访问列表页拿 cookie，再取详情），统一挂一个内存 cookie 罐
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cm);

        Prefs.init(this);
        Prefs.migrateSubscriptionSchema();
        // 订阅清单先用本地缓存注册：断网能用，启动也不用等网络。
        Subscriptions.reload();
        // 缓存超过有效期的才在后台静默刷新一次，失败就算了，不打扰用户。
        Bg.fire(new Runnable() {
            @Override
            public void run() {
                Subscriptions.refreshStale();
            }
        });
    }
}

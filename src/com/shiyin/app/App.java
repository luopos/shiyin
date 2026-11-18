package com.shiyin.app;

import android.app.Application;

import com.shiyin.app.net.Api;
import com.shiyin.app.source.Catalog;
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
        Catalog.load(this);

        // 播放节点会变，后台拉一次；失败就用内置的
        Bg.fire(new Runnable() {
            @Override
            public void run() {
                Api.syncRemoteConfig();
            }
        });
    }
}

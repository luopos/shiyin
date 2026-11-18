package com.shiyin.app.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 后台线程 + 回主线程。所有网络请求都走这里，别在主线程碰 Http。 */
public final class Bg {

    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Bg() {
    }

    public interface Cb<T> {
        void ok(T value);

        void fail(Exception e);
    }

    public static <T> void run(final Callable<T> task, final Cb<T> cb) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final T v = task.call();
                    if (cb == null) return;
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.ok(v);
                        }
                    });
                } catch (final Exception e) {
                    if (cb == null) return;
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.fail(e);
                        }
                    });
                }
            }
        });
    }

    /** 只要后台跑，不要结果。 */
    public static void fire(final Runnable r) {
        POOL.execute(r);
    }

    public static void ui(Runnable r) {
        UI.post(r);
    }

    public static void uiDelayed(Runnable r, long ms) {
        UI.postDelayed(r, ms);
    }
}

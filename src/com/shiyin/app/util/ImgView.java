package com.shiyin.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.shiyin.app.net.Http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 页面和通知共用封面缓存；同一 URL 的并发请求只下载一次。 */
public final class ImgView extends ImageView {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            // getAllocationByteCount 是 API19，Android 4.1 只有 getByteCount（API12）。
            return bitmap.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Map<String, List<Callback>> WAITERS = new HashMap<>();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private String current;
    private float radiusDp = 8f;

    public interface Callback { void loaded(Bitmap bitmap); }

    public ImgView(Context context) {
        super(context);
        setScaleType(ScaleType.CENTER_CROP);
        setBackgroundColor(Ui.BG3);
    }

    public ImgView radius(float dp) {
        radiusDp = dp;
        return this;
    }

    public void load(final String url) {
        if (url != null && url.equals(current) && getDrawable() != null) return;
        current = url;
        setImageDrawable(null);
        if (url == null || url.isEmpty()) return;
        loadBitmap(url, new Callback() {
            @Override public void loaded(Bitmap bitmap) {
                if (url.equals(current) && bitmap != null) setImageBitmap(bitmap);
            }
        });
    }

    public static void loadBitmap(final String url, final Callback callback) {
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            final Bitmap result = cached;
            UI.post(new Runnable() {
                @Override public void run() { callback.loaded(result); }
            });
            return;
        }
        synchronized (WAITERS) {
            List<Callback> pending = WAITERS.get(url);
            if (pending != null) {
                pending.add(callback);
                return;
            }
            pending = new ArrayList<>();
            pending.add(callback);
            WAITERS.put(url, pending);
        }
        POOL.execute(new Runnable() {
            @Override public void run() {
                Bitmap bitmap = null;
                try {
                    Http.Resp response = Http.get(url, null, Http.UA);
                    if (response.ok() && response.bytes != null && response.bytes.length > 0) {
                        byte[] bytes = response.bytes;
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                        int sample = 1;
                        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2;
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = sample;
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    }
                } catch (Exception ignored) {
                    // 封面失败不阻塞列表或播放。
                }
                final Bitmap result = bitmap;
                if (result != null) CACHE.put(url, result);
                final List<Callback> callbacks;
                synchronized (WAITERS) { callbacks = WAITERS.remove(url); }
                UI.post(new Runnable() {
                    @Override public void run() {
                        if (callbacks == null) return;
                        for (Callback waiter : callbacks) waiter.loaded(result);
                    }
                });
            }
        });
    }

    @Override protected void onDraw(Canvas canvas) {
        if (getDrawable() == null) {
            super.onDraw(canvas);
            return;
        }
        int save = canvas.save();
        float radius = Ui.dp(getContext(), radiusDp);
        android.graphics.Path path = new android.graphics.Path();
        path.addRoundRect(new RectF(0, 0, getWidth(), getHeight()), radius, radius,
                android.graphics.Path.Direction.CW);
        canvas.clipPath(path);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
    }

    public static void clearCache() { CACHE.evictAll(); }
}

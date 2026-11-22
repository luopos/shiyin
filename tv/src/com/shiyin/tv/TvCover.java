package com.shiyin.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.shiyin.app.net.Http;
import com.shiyin.app.util.Ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV 版封面控件。
 *
 * <p>不复用手机版 util.ImgView：那边 sizeOf 用了 API19 的 getAllocationByteCount()
 * 和 LruCache（API12 没问题，但 sizeOf 会在 API16 上直接崩），TV 端要 minSdk16 干净。
 * 这里的缓存按 rowBytes*height 计大小，全 API1 可用。
 */
public final class TvCover extends ImageView {

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(6 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getRowBytes() * bitmap.getHeight();
                }
            };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private String current;
    private final float radiusDp = 8f;

    public TvCover(Context context) {
        super(context);
        setScaleType(ScaleType.CENTER_CROP);
        setBackgroundColor(Ui.BG3);
    }

    public void load(final String url) {
        if (url != null && url.equals(current) && getDrawable() != null) return;
        current = url;
        setImageDrawable(null);
        if (url == null || url.length() == 0) return;
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            setImageBitmap(cached);
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                try {
                    // 封面失败不该重试 4 次，用 once。
                    Http.Resp r = Http.once(url, null);
                    byte[] data = r.bytes;
                    if (r.ok() && data != null && data.length > 0) {
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                        int sample = 1;
                        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2;
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = sample;
                        bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                    }
                } catch (Throwable ignored) {
                    // 断网、403、坏图都只是没封面，不影响列表。
                }
                final Bitmap result = bitmap;
                if (result != null) CACHE.put(url, result);
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result != null && url.equals(current)) setImageBitmap(result);
                    }
                });
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getDrawable() == null || Build.VERSION.SDK_INT < 18) {
            super.onDraw(canvas);
            return;
        }
        int save = canvas.save();
        float r = Ui.dp(getContext(), radiusDp);
        Path path = new Path();
        path.addRoundRect(new RectF(0, 0, getWidth(), getHeight()), r, r, Path.Direction.CW);
        canvas.clipPath(path);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
    }
}

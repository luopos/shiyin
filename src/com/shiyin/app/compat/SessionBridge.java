package com.shiyin.app.compat;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;

/**
 * 系统媒体会话兼容桥。
 *
 * <p>{@code MediaSession}/{@code PlaybackState}（android.media.session）和
 * {@code MediaMetadata}（android.media）都是 API21 才有的，API16 编译基准里没有这些类。所以这里一个类型都不 import，全部靠反射：
 * 编译期零依赖，运行期 {@link #available()} 为 false 时通知退化成普通通知。
 *
 * <p>唯一的例外是回调：{@code MediaSession.Callback} 是抽象类，反射无法动态继承，
 * 真实子类只能有一个，放在 {@code com.shiyin.app.phone.SessionCallbackShim}
 * （只随手机包编译，TV 包不需要——API16 上根本没有会话）。这里按类名加载，
 * 加载不到就不设回调，只保留通知按钮和媒体键的既有行为。
 */
public final class SessionBridge {

    /** 媒体会话回调；由真实子类转发进来。 */
    public interface Callbacks {
        void onPlay();

        void onPause();

        void onSkipToPrevious();

        void onSkipToNext();

        void onSeekTo(long positionMs);

        void onStop();

        void onCustomAction(String action);
    }

    /** 只在手机包编译的回调子类；TV 包没有这个类。 */
    private static final String CALLBACK_SHIM = "com.shiyin.app.phone.SessionCallbackShim";

    /** 与 PlayerService.ACTION_STOP 保持一致（compat 不能反向依赖 play 包）。 */
    private static final String ACTION_STOP = "com.shiyin.app.STOP";

    private static final String KEY_TITLE = "android.media.metadata.TITLE";
    private static final String KEY_ALBUM = "android.media.metadata.ALBUM";
    private static final String KEY_ARTIST = "android.media.metadata.ARTIST";
    private static final String KEY_DISPLAY_TITLE = "android.media.metadata.DISPLAY_TITLE";
    private static final String KEY_DISPLAY_SUBTITLE = "android.media.metadata.DISPLAY_SUBTITLE";
    private static final String KEY_DURATION = "android.media.metadata.DURATION";
    private static final String KEY_ALBUM_ART = "android.media.metadata.ALBUM_ART";
    private static final String KEY_DISPLAY_ICON = "android.media.metadata.DISPLAY_ICON";

    private Object session;
    private Object token;
    private Class<?> sessionClass;

    public SessionBridge(Context context, String tag) {
        if (Build.VERSION.SDK_INT < 21 || context == null) return;
        try {
            sessionClass = Class.forName("android.media.session.MediaSession");
            session = sessionClass.getConstructor(Context.class, String.class)
                    .newInstance(context, tag);
            try {
                int flags = sessionClass.getField("FLAG_HANDLES_MEDIA_BUTTONS").getInt(null)
                        | sessionClass.getField("FLAG_HANDLES_TRANSPORT_CONTROLS").getInt(null);
                sessionClass.getMethod("setFlags", int.class).invoke(session, flags);
            } catch (Throwable ignored) {
                // 个别 ROM 缺 FLAG_HANDLES_TRANSPORT_CONTROLS，不影响会话本身。
            }
            try {
                token = sessionClass.getMethod("getSessionToken").invoke(session);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            session = null;
            sessionClass = null;
        }
    }

    public boolean available() {
        return session != null;
    }

    /** MediaSession.Token，给通知的 MediaStyle 用；没有会话时为 null。 */
    public Object token() {
        return token;
    }

    /**
     * 回调子类工厂。
     *
     * <p>{@code MediaSession.Callback} 是抽象类，反射造不出来，必须有个编译期就认识
     * {@code android.media.session.*} 的真实子类。默认实现是反射加载手机包的
     * {@code com.shiyin.app.phone.SessionCallbackShim}；TV 包没有 ui/phone 这类页面代码，
     * 若要在 ≥21 的设备上也要锁屏/蓝牙控制，就在自己的 Application 里注册一个工厂，
     * 返回自己的子类实例。
     */
    public interface CallbackFactory {
        Object create(Callbacks callbacks);
    }

    private static volatile CallbackFactory callbackFactory;

    /** 注入回调工厂；传 null 恢复默认（反射找手机包 shim）。 */
    public static void setCallbackFactory(CallbackFactory factory) {
        callbackFactory = factory;
    }

    public void setCallback(Callbacks callbacks) {
        if (session == null || callbacks == null) return;
        try {
            Object shim = newCallback(callbacks);
            if (shim == null) return;
            sessionClass.getMethod("setCallback",
                            Class.forName("android.media.session.MediaSession$Callback"))
                    .invoke(session, shim);
        } catch (Throwable ignored) {
        }
    }

    private static Object newCallback(Callbacks callbacks) {
        CallbackFactory factory = callbackFactory;
        if (factory != null) {
            try {
                return factory.create(callbacks);
            } catch (Throwable ignored) {
                return null;
            }
        }
        try {
            Class<?> shim = Class.forName(CALLBACK_SHIM);
            return shim.getConstructor(Callbacks.class).newInstance(callbacks);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void setSessionActivity(PendingIntent intent) {
        if (session == null || intent == null) return;
        try {
            sessionClass.getMethod("setSessionActivity", PendingIntent.class)
                    .invoke(session, intent);
        } catch (Throwable ignored) {
        }
    }

    public void setActive(boolean active) {
        if (session == null) return;
        try {
            sessionClass.getMethod("setActive", boolean.class)
                    .invoke(session, Boolean.valueOf(active));
        } catch (Throwable ignored) {
        }
    }

    public void setMetadata(String title, String album, String artist, String subtitle,
                            int durationMs, Bitmap artwork) {
        if (session == null) return;
        try {
            Class<?> metadataClass = Class.forName("android.media.MediaMetadata");
            Class<?> builderClass = Class.forName("android.media.MediaMetadata$Builder");
            Object builder = builderClass.getConstructor().newInstance();
            java.lang.reflect.Method putString =
                    builderClass.getMethod("putString", String.class, String.class);
            putString.invoke(builder, KEY_TITLE, title == null ? "" : title);
            putString.invoke(builder, KEY_ALBUM, album == null ? "" : album);
            putString.invoke(builder, KEY_ARTIST, artist == null ? "" : artist);
            putString.invoke(builder, KEY_DISPLAY_TITLE, title == null ? "" : title);
            putString.invoke(builder, KEY_DISPLAY_SUBTITLE, subtitle == null ? "" : subtitle);
            builderClass.getMethod("putLong", String.class, long.class)
                    .invoke(builder, KEY_DURATION, Long.valueOf(durationMs));
            if (artwork != null) {
                java.lang.reflect.Method putBitmap =
                        builderClass.getMethod("putBitmap", String.class, Bitmap.class);
                putBitmap.invoke(builder, KEY_ALBUM_ART, artwork);
                putBitmap.invoke(builder, KEY_DISPLAY_ICON, artwork);
            }
            Object metadata = builderClass.getMethod("build").invoke(builder);
            sessionClass.getMethod("setMetadata", metadataClass).invoke(session, metadata);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 发布播放状态。
     *
     * @param stateName PlaybackState 常量名，例如 {@code STATE_PLAYING}；常量本身是 API21 的，
     *                  所以按名字反射取值，避免在共享代码里写魔法数字
     */
    public void setPlaybackState(String stateName, long positionMs, float speed, String error) {
        if (session == null) return;
        try {
            Class<?> stateClass = Class.forName("android.media.session.PlaybackState");
            Class<?> builderClass = Class.forName("android.media.session.PlaybackState$Builder");
            int state = stateClass.getField(stateName).getInt(null);
            long actions = stateClass.getField("ACTION_PLAY").getLong(null)
                    | stateClass.getField("ACTION_PAUSE").getLong(null)
                    | stateClass.getField("ACTION_PLAY_PAUSE").getLong(null)
                    | stateClass.getField("ACTION_SKIP_TO_NEXT").getLong(null)
                    | stateClass.getField("ACTION_SKIP_TO_PREVIOUS").getLong(null)
                    | stateClass.getField("ACTION_SEEK_TO").getLong(null)
                    | stateClass.getField("ACTION_STOP").getLong(null);
            Object builder = builderClass.getConstructor().newInstance();
            builderClass.getMethod("setActions", long.class)
                    .invoke(builder, Long.valueOf(actions));
            try {
                builderClass.getMethod("addCustomAction", String.class, CharSequence.class, int.class)
                        .invoke(builder, ACTION_STOP, "停止",
                                Integer.valueOf(android.R.drawable.ic_menu_close_clear_cancel));
            } catch (Throwable ignored) {
            }
            builderClass.getMethod("setState", int.class, long.class, float.class, long.class)
                    .invoke(builder, Integer.valueOf(state), Long.valueOf(positionMs),
                            Float.valueOf(speed), Long.valueOf(SystemClock.elapsedRealtime()));
            if (error != null && error.length() > 0) {
                try {
                    builderClass.getMethod("setErrorMessage", CharSequence.class)
                            .invoke(builder, error);
                } catch (Throwable ignored) {
                }
            }
            Object playbackState = builderClass.getMethod("build").invoke(builder);
            sessionClass.getMethod("setPlaybackState", stateClass)
                    .invoke(session, playbackState);
        } catch (Throwable ignored) {
        }
    }

    public void release() {
        if (session == null) return;
        try {
            sessionClass.getMethod("setActive", boolean.class)
                    .invoke(session, Boolean.FALSE);
        } catch (Throwable ignored) {
        }
        try {
            sessionClass.getMethod("release").invoke(session);
        } catch (Throwable ignored) {
        }
        session = null;
        token = null;
        sessionClass = null;
    }
}

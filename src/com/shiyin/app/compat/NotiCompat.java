package com.shiyin.app.compat;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;

/**
 * 通知兼容层。
 *
 * <p>API16 基准里没有 NotificationChannel（API26）、{@code Notification.Builder(ctx, channel)}
 * （API26）、setColor / setCategory / setVisibility（API21）、setShowWhen（API17），
 * 这些全部反射调用并按版本跳过。API16 能用的只有普通 Notification.Builder + addAction。
 */
public final class NotiCompat {

    /** NotificationManager.IMPORTANCE_LOW，API24 常量。 */
    private static final int IMPORTANCE_LOW = 2;
    /** Notification.VISIBILITY_PUBLIC，API21 常量。 */
    private static final int VISIBILITY_PUBLIC = 1;

    private NotiCompat() {
    }

    /** API26+ 建通知渠道；低版本是空操作。 */
    public static void createChannel(Context context, String id, CharSequence name) {
        if (context == null || Build.VERSION.SDK_INT < 26) return;
        try {
            Class<?> channelClass = Class.forName("android.app.NotificationChannel");
            Object channel = channelClass.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(id, name, IMPORTANCE_LOW);
            channelClass.getMethod("setShowBadge", boolean.class).invoke(channel, Boolean.FALSE);
            try {
                channelClass.getMethod("setSound", Uri.class,
                        Class.forName("android.media.AudioAttributes"))
                        .invoke(channel, null, null);
            } catch (Throwable ignored) {
                // 有的 ROM 改了签名，静音失败不影响通知本身。
            }
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            NotificationManager.class.getMethod("createNotificationChannel", channelClass)
                    .invoke(manager, channel);
        } catch (Throwable ignored) {
        }
    }

    /** API26+ 用带渠道的构造器，低版本用无参渠道的构造器。 */
    public static Notification.Builder builder(Context context, String channelId) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return Notification.Builder.class
                        .getConstructor(Context.class, String.class)
                        .newInstance(context, channelId);
            } catch (Throwable ignored) {
                // 落到老构造器。
            }
        }
        return new Notification.Builder(context);
    }

    /** Notification.Builder.setShowWhen 是 API17。 */
    public static void setShowWhen(Notification.Builder builder, boolean show) {
        if (builder == null || Build.VERSION.SDK_INT < 17) return;
        try {
            Notification.Builder.class.getMethod("setShowWhen", boolean.class)
                    .invoke(builder, Boolean.valueOf(show));
        } catch (Throwable ignored) {
        }
    }

    /** Notification.Builder.setColor 是 API21。 */
    public static void setColor(Notification.Builder builder, int color) {
        if (builder == null || Build.VERSION.SDK_INT < 21) return;
        try {
            Notification.Builder.class.getMethod("setColor", int.class)
                    .invoke(builder, Integer.valueOf(color));
        } catch (Throwable ignored) {
        }
    }

    /** CATEGORY_TRANSPORT + VISIBILITY_PUBLIC 都是 API21，一起设。 */
    public static void setTransportCategory(Notification.Builder builder) {
        if (builder == null || Build.VERSION.SDK_INT < 21) return;
        try {
            Object category = Class.forName("android.app.Notification")
                    .getField("CATEGORY_TRANSPORT").get(null);
            Notification.Builder.class.getMethod("setCategory", String.class)
                    .invoke(builder, category);
        } catch (Throwable ignored) {
        }
        try {
            Notification.Builder.class.getMethod("setVisibility", int.class)
                    .invoke(builder, Integer.valueOf(VISIBILITY_PUBLIC));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 套上 MediaStyle 并绑定媒体会话；没有会话（API&lt;21）就保持普通通知。
     *
     * @param sessionToken SessionBridge.token() 返回的 MediaSession.Token，可为 null
     */
    public static void setMediaStyle(Notification.Builder builder, Object sessionToken,
                                     int... compactActions) {
        if (builder == null || sessionToken == null || Build.VERSION.SDK_INT < 21) return;
        try {
            Class<?> styleClass = Class.forName("android.app.Notification$MediaStyle");
            Object style = styleClass.getConstructor().newInstance();
            styleClass.getMethod("setMediaSession",
                            Class.forName("android.media.session.MediaSession$Token"))
                    .invoke(style, sessionToken);
            if (compactActions != null && compactActions.length > 0) {
                styleClass.getMethod("setShowActionsInCompactView", int[].class)
                        .invoke(style, (Object) compactActions);
            }
            builder.setStyle((Notification.Style) style);
        } catch (Throwable ignored) {
            // 套不上就退回普通通知，按钮仍然可用。
        }
    }

    /** 大图标在 API11 就有，但有些 ROM 对 null 敏感，统一挡一下。 */
    public static void setLargeIcon(Notification.Builder builder, Bitmap bitmap) {
        if (builder == null || bitmap == null) return;
        try {
            builder.setLargeIcon(bitmap);
        } catch (Throwable ignored) {
        }
    }
}

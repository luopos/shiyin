package com.shiyin.app.compat;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;

/**
 * 音频属性与音频焦点兼容层。
 *
 * <p>{@code AudioAttributes} 是 API21、{@code AudioFocusRequest} 是 API26，
 * API16 编译基准里根本没有这两个类，所以这里全部用反射：编译期不出现类型引用，
 * 运行期按版本决定走新接口还是 {@code requestAudioFocus(listener, STREAM_MUSIC, GAIN)} 老接口。
 *
 * <p>AudioAttributes 必须延迟到真正播放时再构造，不能写成字段初始化器——
 * 字段初始化器在类加载/实例化时就会跑，Android 4.1 上直接 NoClassDefFoundError。
 */
public final class AudioCompat {

    /** 一次焦点请求的结果；token 只在 API26+ 有值，用来配对 abandon。 */
    public static final class Focus {
        public final boolean granted;
        public final Object token;

        Focus(boolean granted, Object token) {
            this.granted = granted;
            this.token = token;
        }
    }

    private AudioCompat() {
    }

    /** API21 才有 AudioAttributes；低于 21 返回 null，调用方退回 setAudioStreamType。 */
    public static Object speechAttributes() {
        if (Build.VERSION.SDK_INT < 21) return null;
        try {
            Class<?> attributes = Class.forName("android.media.AudioAttributes");
            Class<?> builder = Class.forName("android.media.AudioAttributes$Builder");
            Object instance = builder.getConstructor().newInstance();
            builder.getMethod("setUsage", int.class)
                    .invoke(instance, attributes.getField("USAGE_MEDIA").getInt(null));
            builder.getMethod("setContentType", int.class)
                    .invoke(instance, attributes.getField("CONTENT_TYPE_SPEECH").getInt(null));
            return builder.getMethod("build").invoke(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把属性装到 MediaPlayer 上；老系统走等价的 setAudioStreamType(STREAM_MUSIC)。 */
    public static void applyAttributes(MediaPlayer player, Object attributes) {
        if (player == null) return;
        if (attributes != null) {
            try {
                MediaPlayer.class.getMethod("setAudioAttributes",
                        Class.forName("android.media.AudioAttributes")).invoke(player, attributes);
                return;
            } catch (Throwable ignored) {
                // 反射失败就退回老接口，别让播放起不来。
            }
        }
        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        } catch (Throwable ignored) {
        }
    }

    /** 申请音频焦点。API26+ 用 AudioFocusRequest，更老用三参数老接口。 */
    public static Focus request(AudioManager manager,
                                AudioManager.OnAudioFocusChangeListener listener,
                                Handler handler, Object attributes) {
        if (manager == null) return new Focus(false, null);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> requestClass = Class.forName("android.media.AudioFocusRequest");
                Class<?> builderClass = Class.forName("android.media.AudioFocusRequest$Builder");
                Object builder = builderClass.getConstructor(int.class)
                        .newInstance(AudioManager.AUDIOFOCUS_GAIN);
                if (attributes != null) {
                    builderClass.getMethod("setAudioAttributes",
                            Class.forName("android.media.AudioAttributes")).invoke(builder, attributes);
                }
                builderClass.getMethod("setWillPauseWhenDucked", boolean.class)
                        .invoke(builder, Boolean.TRUE);
                builderClass.getMethod("setOnAudioFocusChangeListener",
                        AudioManager.OnAudioFocusChangeListener.class, Handler.class)
                        .invoke(builder, listener, handler);
                Object request = builderClass.getMethod("build").invoke(builder);
                Object result = AudioManager.class.getMethod("requestAudioFocus", requestClass)
                        .invoke(manager, request);
                boolean granted = result instanceof Integer
                        && (Integer) result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                return new Focus(granted, request);
            } catch (Throwable ignored) {
                // 反射失败落回老接口。
            }
        }
        try {
            int result = manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            return new Focus(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED, null);
        } catch (Throwable ignored) {
            return new Focus(false, null);
        }
    }

    /** 释放焦点；有 AudioFocusRequest 就按请求释放，否则按监听器释放。 */
    public static void abandon(AudioManager manager,
                               AudioManager.OnAudioFocusChangeListener listener, Object token) {
        if (manager == null) return;
        if (token != null) {
            try {
                AudioManager.class.getMethod("abandonAudioFocusRequest",
                        Class.forName("android.media.AudioFocusRequest")).invoke(manager, token);
                return;
            } catch (Throwable ignored) {
                // 退回老接口。
            }
        }
        try {
            manager.abandonAudioFocus(listener);
        } catch (Throwable ignored) {
        }
    }
}

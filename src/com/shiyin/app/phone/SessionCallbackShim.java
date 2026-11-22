package com.shiyin.app.phone;

import android.media.session.MediaSession;
import android.os.Bundle;

import com.shiyin.app.compat.SessionBridge;

/**
 * 媒体会话回调的真实子类。
 *
 * <p>为什么单独一个文件、还放在 phone 包：{@code MediaSession.Callback} 是<b>抽象类</b>，
 * 反射没法动态继承（Proxy 只支持接口），所以必须有一个编译期就认识
 * {@code android.media.session.*} 的子类。这个文件只随手机包编译——
 * TV 包跑在 API16，压根没有 MediaSession，不需要它。
 *
 * <p>SessionBridge 按类名 {@code com.shiyin.app.phone.SessionCallbackShim} 加载，
 * 加载不到就退化成「只有通知按钮」。
 */
public final class SessionCallbackShim extends MediaSession.Callback {

    private final SessionBridge.Callbacks callbacks;

    public SessionCallbackShim(SessionBridge.Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    @Override public void onPlay() {
        callbacks.onPlay();
    }

    @Override public void onPause() {
        callbacks.onPause();
    }

    @Override public void onSkipToPrevious() {
        callbacks.onSkipToPrevious();
    }

    @Override public void onSkipToNext() {
        callbacks.onSkipToNext();
    }

    @Override public void onSeekTo(long position) {
        callbacks.onSeekTo(position);
    }

    @Override public void onStop() {
        callbacks.onStop();
    }

    @Override public void onCustomAction(String action, Bundle extras) {
        callbacks.onCustomAction(action);
    }
}

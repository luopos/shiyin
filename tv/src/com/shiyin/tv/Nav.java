package com.shiyin.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.shiyin.app.model.Book;
import com.shiyin.app.util.Ui;

/**
 * TV 端跨页跳转的唯一入口。
 *
 * <p>详情页 / 播放页由 impl-c 实现，可能比主界面晚落地。这里用 Class.forName 找类：
 * 类在就直接跳，类不在就 Toast 提示，保证主界面骨架永远能编译、能启动。
 * 类到位后这里不用改一行代码。
 */
public final class Nav {

    public static final String EXTRA_BOOK = "book";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_AUTHOR = "author";
    public static final String EXTRA_NARRATOR = "narrator";
    public static final String EXTRA_COVER = "cover";
    public static final String EXTRA_DESC = "desc";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_SONG_NUM = "songNum";
    public static final String EXTRA_BOOK_URL = "bookUrl";

    private static final String DETAIL_CLASS = "com.shiyin.tv.TvDetailActivity";
    private static final String PLAYER_CLASS = "com.shiyin.tv.TvPlayerActivity";

    private Nav() {
    }

    /** 打开详情；详情页未就绪时只提示，不崩。 */
    public static void openDetail(Activity from, Book book) {
        if (from == null || book == null) return;
        Intent intent = new Intent();
        intent.setClassName(from, DETAIL_CLASS);
        // 两种传法都给：Book 是 Serializable，另外再给扁平字段，impl-c 读哪种都行。
        try {
            intent.putExtra(EXTRA_BOOK, book);
        } catch (Throwable ignored) {
            // 极端情况下 Serializable 传参失败，扁平字段仍可用。
        }
        intent.putExtra(EXTRA_SOURCE, book.source);
        intent.putExtra(EXTRA_ID, book.id);
        intent.putExtra(EXTRA_TITLE, book.title);
        intent.putExtra(EXTRA_AUTHOR, book.author);
        intent.putExtra(EXTRA_NARRATOR, book.narrator);
        intent.putExtra(EXTRA_COVER, book.cover);
        intent.putExtra(EXTRA_DESC, book.desc);
        intent.putExtra(EXTRA_STATUS, book.status);
        intent.putExtra(EXTRA_SONG_NUM, book.songNum);
        intent.putExtra(EXTRA_BOOK_URL, book.bookUrl);
        launch(from, intent, DETAIL_CLASS, "详情页还没装好：" + book.title);
    }

    /** 从别处恢复播放页（媒体键或后续首页「正在播放」用）。 */
    public static void openPlayer(Activity from) {
        if (from == null) return;
        Intent intent = new Intent();
        intent.setClassName(from, PLAYER_CLASS);
        launch(from, intent, PLAYER_CLASS, "播放页还没装好");
    }

    private static void launch(Activity from, Intent intent, String className, String missingHint) {
        try {
            Class.forName(className);
        } catch (Throwable missing) {
            Ui.toast(from, missingHint);
            return;
        }
        try {
            from.startActivity(intent);
        } catch (Throwable failure) {
            Ui.toast(from, "打不开页面：" + failure.getClass().getSimpleName());
        }
    }

    /* ---------------- 遥控器媒体键 → 共享播放器 ---------------- */

    /**
     * 走反射调共享 Player，避免主界面在 impl-a 改完播放层之前被硬依赖卡住。
     * 成功返回 true；播放层未编进 APK 时返回 false。
     */
    public static boolean mediaKey(Context context, int keyCode) {
        Object player;
        Class<?> cls;
        try {
            cls = Class.forName("com.shiyin.app.play.Player");
            player = cls.getMethod("get").invoke(null);
        } catch (Throwable missing) {
            return false;
        }
        if (player == null) return false;
        try {
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case android.view.KeyEvent.KEYCODE_HEADSETHOOK:
                    cls.getMethod("toggle", Context.class).invoke(player, context);
                    return true;
                case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
                    cls.getMethod("resume", Context.class).invoke(player, context);
                    return true;
                case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                    cls.getMethod("pause", Context.class).invoke(player, context);
                    return true;
                case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
                    cls.getMethod("next", Context.class).invoke(player, context);
                    return true;
                case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    cls.getMethod("prev", Context.class).invoke(player, context);
                    return true;
                default:
                    return false;
            }
        } catch (Throwable failure) {
            Ui.toast(context, "播放器暂不可用：" + failure.getClass().getSimpleName());
            return true;
        }
    }
}

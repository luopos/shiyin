package com.shiyin.app.play;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.shiyin.app.compat.NotiCompat;
import com.shiyin.app.compat.PendingIntents;
import com.shiyin.app.compat.SessionBridge;
import com.shiyin.app.model.Book;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.util.ImgView;
import com.shiyin.app.util.Ui;

/**
 * 播放服务持有系统媒体会话，负责后台通知、锁屏控制和生命周期清理。
 *
 * <p>共享代码要能在 Android 4.1（API16）上编译：媒体会话、通知渠道、PendingIntent
 * 新标志位全部走 {@code com.shiyin.app.compat}，这里不出现任何 API21+ 的类型。
 * 播放页也不再硬编码 {@code com.shiyin.app.ui.PlayerActivity}——TV 包没有 ui 包，
 * 改成「注入优先、反射兜底、最后退回启动 Intent」。
 */
public class PlayerService extends Service implements Player.Listener {
    public static final String ACTION_TOGGLE = "com.shiyin.app.TOGGLE";
    public static final String ACTION_PREV = "com.shiyin.app.PREV";
    public static final String ACTION_NEXT = "com.shiyin.app.NEXT";
    public static final String ACTION_STOP = "com.shiyin.app.STOP";

    private static final String CHANNEL = "playback";
    private static final int NOTI_ID = 0x5EED;
    /** 手机包的播放页；TV 包没有这个类，反射失败后走启动 Intent。 */
    private static final String OPEN_ACTIVITY = "com.shiyin.app.ui.PlayerActivity";
    /** Context.RECEIVER_NOT_EXPORTED，API33 常量。 */
    private static final int RECEIVER_NOT_EXPORTED = 0x4;

    private static PlayerService instance;
    private static Class<?> openActivity;
    private static boolean openActivityResolved;

    private SessionBridge session;
    private long lastPositionUpdate;
    private long lastNotificationUpdate;
    private String coverUrl = "";
    private Bitmap artwork;
    private boolean destroyed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expirePaused = new Runnable() {
        @Override public void run() {
            Player player = Player.get();
            if (player.state() == Player.PAUSED || player.state() == Player.ERROR) {
                player.saveProgress();
                stopSelf();
            }
        }
    };

    /**
     * 注入「点通知打开哪个页面」。
     *
     * <p>TV 包在 Application 里调用一次即可；不调用时按约定反射找手机包的 PlayerActivity。
     */
    public static void setOpenActivity(Class<?> activity) {
        openActivity = activity;
        openActivityResolved = true;
    }

    private static Class<?> resolveOpenActivity() {
        if (!openActivityResolved) {
            openActivityResolved = true;
            try {
                openActivity = Class.forName(OPEN_ACTIVITY);
            } catch (Throwable missing) {
                // TV 包没有 ui 包，属于正常情况。
                openActivity = null;
            }
        }
        return openActivity;
    }

    private PendingIntent contentIntent() {
        Intent open;
        Class<?> activity = resolveOpenActivity();
        if (activity != null) {
            open = new Intent().setClass(this, activity);
        } else {
            open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open == null) return null;
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            return PendingIntent.getActivity(this, 1, open, PendingIntents.updateCurrent());
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private void loadArtwork(Book book) {
        final String url = book == null || book.cover == null ? "" : book.cover;
        if (url.equals(coverUrl)) return;
        coverUrl = url;
        artwork = null;
        if (url.isEmpty()) return;
        ImgView.loadBitmap(url, new ImgView.Callback() {
            @Override public void loaded(Bitmap bitmap) {
                if (!destroyed && url.equals(coverUrl)) {
                    artwork = bitmap;
                    refresh();
                }
            }
        });
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Player.get().pause(PlayerService.this);
            }
        }
    };

    public static void start(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, PlayerService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            // startForegroundService 是 API26 方法，API16 基准里没有，只能反射。
            try {
                Context.class.getMethod("startForegroundService", Intent.class)
                        .invoke(app, intent);
                return;
            } catch (Throwable ignored) {
                // 落回 startService。
            }
        }
        app.startService(intent);
    }

    public static void stop(Context context) {
        context.getApplicationContext().stopService(new Intent(context, PlayerService.class));
    }

    public static void update(Context context, boolean playing) {
        if (instance != null) instance.refresh();
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotiCompat.createChannel(this, CHANNEL, "播放");
        session = new SessionBridge(this, "ShiyinPlayback");
        session.setCallback(new SessionBridge.Callbacks() {
            @Override public void onPlay() { Player.get().resume(PlayerService.this); }
            @Override public void onPause() { Player.get().pause(PlayerService.this); }
            @Override public void onSkipToPrevious() { Player.get().prev(PlayerService.this); }
            @Override public void onSkipToNext() { Player.get().next(PlayerService.this); }
            @Override public void onSeekTo(long position) {
                Player.get().seekTo((int) Math.min(Integer.MAX_VALUE, Math.max(0, position)));
            }
            @Override public void onStop() { Player.get().stop(PlayerService.this); }
            @Override public void onCustomAction(String action) {
                if (ACTION_STOP.equals(action)) Player.get().stop(PlayerService.this);
            }
        });
        session.setSessionActivity(contentIntent());
        session.setActive(true);
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerNoisyReceiver(filter);
        Player.get().addListener(this);
        startForeground(NOTI_ID, buildNotification());
        refresh();
    }

    /** API33 的 registerReceiver(receiver, filter, flags) 在 API16 里不存在，反射调用。 */
    private void registerNoisyReceiver(IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Context.class.getMethod("registerReceiver", BroadcastReceiver.class,
                                IntentFilter.class, int.class)
                        .invoke(this, noisyReceiver, filter,
                                Integer.valueOf(RECEIVER_NOT_EXPORTED));
                return;
            } catch (Throwable ignored) {
                // 落回老签名。
            }
        }
        registerReceiver(noisyReceiver, filter);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Player player = Player.get();
        if (player.book() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            player.stop(this);
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) player.toggle(this);
        else if (ACTION_PREV.equals(action)) player.prev(this);
        else if (ACTION_NEXT.equals(action)) player.next(this);
        refresh();
        // 不重启一个没有播放队列的空服务；进度由播放器定期保存。
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        Player.get().saveProgress();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(expirePaused);
        Player.get().removeListener(this);
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Throwable ignored) {
            // 注册失败过就别在销毁时再炸一次。
        }
        if (session != null) session.release();
        instance = null;
        Player.get().releaseForService();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void publishState() {
        Player player = Player.get();
        String state;
        switch (player.state()) {
            case Player.LOADING: state = "STATE_BUFFERING"; break;
            case Player.PLAYING: state = "STATE_PLAYING"; break;
            case Player.PAUSED: state = "STATE_PAUSED"; break;
            case Player.ERROR: state = "STATE_ERROR"; break;
            default: state = "STATE_STOPPED";
        }
        session.setPlaybackState(state, player.position(), player.isPlaying() ? 1f : 0f,
                player.state() == Player.ERROR ? player.error() : "");
    }

    private void refresh() {
        Player player = Player.get();
        Book book = player.book();
        Chapter chapter = player.chapter();
        loadArtwork(book);
        session.setMetadata(book == null ? "拾音" : book.title,
                book == null ? "拾音" : book.title,
                chapter == null ? "" : chapter.title,
                chapter == null ? "" : chapter.title,
                player.duration(), artwork);
        publishState();
        notifyNotification();
    }

    private void notifyNotification() {
        if (destroyed) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        try {
            manager.notify(NOTI_ID, buildNotification());
        } catch (Throwable ignored) {
            // 通知刷新失败不影响播放。
        }
    }

    @Override public void onState(int state, String message) {
        handler.removeCallbacks(expirePaused);
        if (state == Player.PAUSED || state == Player.ERROR) {
            handler.postDelayed(expirePaused, 30 * 60 * 1000L);
        }
        refresh();
    }

    @Override public void onTrack(int index, Chapter chapter) { refresh(); }

    @Override public void onProgress(int positionMs, int durationMs) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPositionUpdate >= 1000) {
            lastPositionUpdate = now;
            publishState();
        }
        if (now - lastNotificationUpdate >= 5000) {
            lastNotificationUpdate = now;
            notifyNotification();
        }
    }

    private static PendingIntent action(Context context, String action) {
        Intent intent = new Intent(context, PlayerService.class).setAction(action);
        return PendingIntent.getService(context, action.hashCode(), intent,
                PendingIntents.updateCurrent());
    }

    private Notification buildNotification() {
        Player player = Player.get();
        Chapter chapter = player.chapter();
        boolean playing = player.isPlaying();
        boolean pauseAction = player.playRequested();
        PendingIntent content = contentIntent();
        String status = player.state() == Player.LOADING ? player.message()
                : player.state() == Player.ERROR ? "播放失败，点开重试"
                : playing ? "正在播放" : "已暂停";
        if (player.duration() > 0) status += " · " + Ui.timeMs(player.position())
                + " / " + Ui.timeMs(player.duration());
        Notification.Builder builder = NotiCompat.builder(this, CHANNEL);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(player.book() == null ? "拾音" : player.book().title)
                .setContentText(chapter == null ? "未在播放" : chapter.title)
                .setDeleteIntent(action(this, ACTION_STOP))
                .setSubText(status)
                .setOngoing(playing || player.state() == Player.LOADING)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_previous, "上一集", action(this, ACTION_PREV))
                .addAction(pauseAction ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        pauseAction ? "暂停" : "播放", action(this, ACTION_TOGGLE))
                .addAction(android.R.drawable.ic_media_next, "下一集", action(this, ACTION_NEXT))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(this, ACTION_STOP));
        // TV 包可能拿不到启动页，拿不到就不挂点击，通知照样能用按钮。
        if (content != null) builder.setContentIntent(content);
        NotiCompat.setLargeIcon(builder, artwork);
        NotiCompat.setShowWhen(builder, false);
        NotiCompat.setColor(builder, 0xFFF5A524);
        NotiCompat.setTransportCategory(builder);
        NotiCompat.setMediaStyle(builder, session.token(), 0, 1, 2);
        return builder.build();
    }
}

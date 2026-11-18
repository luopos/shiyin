package com.shiyin.app.play;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import com.shiyin.app.util.ImgView;
import com.shiyin.app.util.Ui;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.ui.PlayerActivity;

/** 播放服务持有系统媒体会话，负责后台通知、锁屏控制和生命周期清理。 */
public class PlayerService extends Service implements Player.Listener {
    public static final String ACTION_TOGGLE = "com.shiyin.app.TOGGLE";
    public static final String ACTION_PREV = "com.shiyin.app.PREV";
    public static final String ACTION_NEXT = "com.shiyin.app.NEXT";
    public static final String ACTION_STOP = "com.shiyin.app.STOP";

    private static final String CHANNEL = "playback";
    private static final int NOTI_ID = 0x5EED;
    private static PlayerService instance;
    private MediaSession session;
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

    private PendingIntent contentIntent() {
        Intent open = new Intent(this, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent);
        else app.startService(intent);
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
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "播放",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        session = new MediaSession(this, "ShiyinPlayback");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { Player.get().resume(PlayerService.this); }
            @Override public void onPause() { Player.get().pause(PlayerService.this); }
            @Override public void onSkipToPrevious() { Player.get().prev(PlayerService.this); }
            @Override public void onSkipToNext() { Player.get().next(PlayerService.this); }
            @Override public void onSeekTo(long position) {
                Player.get().seekTo((int) Math.min(Integer.MAX_VALUE, Math.max(0, position)));
            }
            @Override public void onStop() { Player.get().stop(PlayerService.this); }
            @Override public void onCustomAction(String action, android.os.Bundle extras) {
                if (ACTION_STOP.equals(action)) Player.get().stop(PlayerService.this);
            }
        });
        session.setSessionActivity(contentIntent());
        session.setActive(true);
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, filter);
        Player.get().addListener(this);
        startForeground(NOTI_ID, buildNotification());
        refresh();
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
        unregisterReceiver(noisyReceiver);
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        instance = null;
        Player.get().releaseForService();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void publishState() {
        Player player = Player.get();
        int state;
        switch (player.state()) {
            case Player.LOADING: state = PlaybackState.STATE_BUFFERING; break;
            case Player.PLAYING: state = PlaybackState.STATE_PLAYING; break;
            case Player.PAUSED: state = PlaybackState.STATE_PAUSED; break;
            case Player.ERROR: state = PlaybackState.STATE_ERROR; break;
            default: state = PlaybackState.STATE_STOPPED;
        }
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .addCustomAction(ACTION_STOP, "停止", android.R.drawable.ic_menu_close_clear_cancel)
                .setState(state, player.position(), player.isPlaying() ? 1f : 0f,
                        SystemClock.elapsedRealtime());
        if (player.state() == Player.ERROR) builder.setErrorMessage(player.error());
        session.setPlaybackState(builder.build());
    }

    private void refresh() {
        if (session == null) return;
        Player player = Player.get();
        Book book = player.book();
        Chapter chapter = player.chapter();
        loadArtwork(book);
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, book == null ? "拾音" : book.title)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, book == null ? "拾音" : book.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, chapter == null ? "" : chapter.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, book == null ? "拾音" : book.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, chapter == null ? "" : chapter.title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration());
        if (artwork != null) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
            metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork);
        }
        session.setMetadata(metadata.build());
        publishState();
        getSystemService(NotificationManager.class).notify(NOTI_ID, buildNotification());
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
            if (session != null) publishState();
        }
        if (now - lastNotificationUpdate >= 5000) {
            lastNotificationUpdate = now;
            if (!destroyed) getSystemService(NotificationManager.class)
                    .notify(NOTI_ID, buildNotification());
        }
    }

    private static PendingIntent action(Context context, String action) {
        Intent intent = new Intent(context, PlayerService.class).setAction(action);
        return PendingIntent.getService(context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(player.book() == null ? "拾音" : player.book().title)
                .setContentText(chapter == null ? "未在播放" : chapter.title)
                .setContentIntent(content)
                .setDeleteIntent(action(this, ACTION_STOP))
                .setSubText(status)
                .setLargeIcon(artwork)
                .setColor(0xFFF5A524)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(playing || player.state() == Player.LOADING)
                .setOnlyAlertOnce(true).setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "上一集", action(this, ACTION_PREV))
                .addAction(pauseAction ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        pauseAction ? "暂停" : "播放", action(this, ACTION_TOGGLE))
                .addAction(android.R.drawable.ic_media_next, "下一集", action(this, ACTION_NEXT))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(this, ACTION_STOP))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return builder.build();
    }
}

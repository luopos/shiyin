package com.shiyin.app.play;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.shiyin.app.compat.AudioCompat;
import com.shiyin.app.model.Book;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.source.Source;
import com.shiyin.app.source.Sources;
import com.shiyin.app.source.ChapterRepository;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.util.Bg;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放器单例。
 *
 * MediaPlayer、播放队列和音频焦点集中管理；只保存 Application Context。
 * PlayerService 管理前台通知和系统媒体会话，页面销毁不终止播放。
 * 解析地址和缓冲期间通过 loadToken 隔离过期回调。
 */
public final class Player {

    public static final int IDLE = 0;
    public static final int LOADING = 1;
    public static final int PLAYING = 2;
    public static final int PAUSED = 3;
    public static final int ERROR = 4;

    public interface Listener {
        void onState(int state, String message);

        void onProgress(int positionMs, int durationMs);

        void onTrack(int index, Chapter chapter);
    }

    private static final Player I = new Player();

    public static Player get() {
        return I;
    }

    private MediaPlayer mp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();

    private Book book;
    private List<Chapter> chapters = new ArrayList<>();
    private int index = -1;
    private boolean pagedQueue;
    private int queueOffset;
    private int chapterTotal = -1;
    private boolean queueHasMore;
    private int pendingChapter = -1;
    private int state = IDLE;
    private String error = "";
    private String hint = "";
    private boolean prepared = false;
    private int pendingSeek = -1;
    private boolean loopAll = true;
    private int loadToken = 0;
    private Context appContext;
    private AudioManager audioManager;
    /** API26+ 是 AudioFocusRequest，更低版本为 null；只用于配对释放。 */
    private Object focusToken;
    private boolean hasFocus;
    private boolean resumeAfterFocus;
    private boolean playWhenReady = true;
    private long lastProgressSave;
    /**
     * AudioAttributes 是 API21 的类，不能写成字段初始化器——那样类一实例化就在
     * Android 4.1 上 NoClassDefFoundError。这里只存 Object，真正构造推迟到播放时。
     */
    private Object audioAttributes;
    private boolean audioAttributesResolved;

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                hasFocus = true;
                boolean shouldResume = resumeAfterFocus;
                resumeAfterFocus = false;
                if (shouldResume && appContext != null) resume(appContext);
            } else if (change == AudioManager.AUDIOFOCUS_LOSS
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                boolean interrupted = isPlaying() || (state == LOADING && playWhenReady);
                pausePlayback();
                hasFocus = false;
                resumeAfterFocus = interrupted && change != AudioManager.AUDIOFOCUS_LOSS;
                if (change == AudioManager.AUDIOFOCUS_LOSS) abandonFocus();
            }
        }
    };

    private Object audioAttributes() {
        if (!audioAttributesResolved) {
            audioAttributesResolved = true;
            audioAttributes = AudioCompat.speechAttributes();
        }
        return audioAttributes;
    }

    private boolean requestFocus() {
        if (hasFocus) return true;
        if (appContext == null) return false;
        if (audioManager == null) {
            audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) return false;
        AudioCompat.Focus focus = AudioCompat.request(audioManager, focusListener, ui,
                audioAttributes());
        if (focus.token != null) focusToken = focus.token;
        hasFocus = focus.granted;
        return hasFocus;
    }

    private void abandonFocus() {
        resumeAfterFocus = false;
        if (audioManager != null) {
            AudioCompat.abandon(audioManager, focusListener, focusToken);
        }
        focusToken = null;
        hasFocus = false;
    }

    private Player() {
    }

    /* ---------------- 状态 ---------------- */

    public int state() {
        return state;
    }

    public String error() {
        return error;
    }

    public String message() {
        return state == LOADING ? (playWhenReady ? hint : "已暂停，准备就绪后等待播放") : error;
    }

    public boolean playRequested() {
        return state == PLAYING || (state == LOADING && playWhenReady);
    }

    public Book book() {
        return book;
    }

    public List<Chapter> chapters() {
        return chapters;
    }

    public int index() {
        return index;
    }

    public Chapter chapter() {
        int local = index - queueOffset;
        return local >= 0 && local < chapters.size() ? chapters.get(local) : null;
    }

    /** 总数来自源接口，不能把当前50集误报为整本集数。 */
    public int chapterTotal() {
        return pagedQueue ? chapterTotal : chapters.size();
    }

    public int position() {
        try {
            return mp != null && prepared ? mp.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int duration() {
        try {
            return mp != null && prepared ? mp.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isPlaying() {
        try {
            return mp != null && prepared && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void emitState() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                String msg = message();
                for (Listener l : new ArrayList<>(listeners)) l.onState(state, msg);
            }
        });
    }

    private void emitTrack() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Chapter c = chapter();
                for (Listener l : new ArrayList<>(listeners)) l.onTrack(index, c);
            }
        });
    }

    private void emitProgress() {
        final int p = position();
        final int d = duration();
        ui.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : new ArrayList<>(listeners)) l.onProgress(p, d);
            }
        });
    }

    /* ---------------- 进度上报 ---------------- */

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (state == PLAYING || state == PAUSED) emitProgress();
            long now = SystemClock.elapsedRealtime();
            if (state == PLAYING && now - lastProgressSave >= 5000) {
                saveProgress();
                lastProgressSave = now;
            }
            if (prepared) ui.postDelayed(this, 500);
        }
    };

    private void startTicker() {
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    /* ---------------- 播放控制 ---------------- */

    /** 换一本书开始播。 */
    public void setPlaylist(Context ctx, Book b, List<Chapter> list, int startIndex) {
        if (list == null || list.isEmpty()) {
            stop(ctx);
            book = null;
            chapters = new ArrayList<>();
            index = -1;
            queueOffset = 0;
            chapterTotal = 0;
            pagedQueue = false;
            queueHasMore = false;
            emitTrack();
            return;
        }
        saveProgress();
        releasePlayer();
        this.book = b;
        if (b != null) Prefs.rememberLastBook(b);
        this.chapters = list == null ? new ArrayList<Chapter>() : new ArrayList<>(list);
        pagedQueue = false;
        queueOffset = 0;
        chapterTotal = chapters.size();
        queueHasMore = false;
        pendingChapter = -1;
        loadToken++;
        if (startIndex < 0 || startIndex >= chapters.size()) startIndex = 0;
        playIndex(ctx, startIndex);
    }

    public void setChapterPage(Context ctx, Book b, Source.ChapterPage page, int globalIndex) {
        if (page == null || page.chapters == null || page.chapters.isEmpty()) return;
        saveProgress();
        releasePlayer();
        loadToken++;
        book = b;
        if (b != null) Prefs.rememberLastBook(b);
        pagedQueue = true;
        applyPage(page);
        pendingChapter = -1;
        playIndex(ctx, globalIndex);
    }

    private void applyPage(Source.ChapterPage page) {
        chapters = new ArrayList<>(page.chapters);
        queueOffset = page.page * Source.CHAPTER_PAGE_SIZE;
        chapterTotal = page.total;
        queueHasMore = page.hasMore;
    }

    public void playIndex(Context ctx, int i) {
        if (chapters.isEmpty() || ctx == null) return;
        i = Math.max(0, i);
        if (!pagedQueue) i = Math.min(i, chapters.size() - 1);
        if (pagedQueue && (i < queueOffset || i >= queueOffset + chapters.size())) {
            requestChapterPage(ctx, i);
            return;
        }
        saveProgress();
        releasePlayer();
        pendingSeek = -1;
        pendingChapter = -1;
        appContext = ctx.getApplicationContext();
        index = i;
        emitTrack();
        loadCurrent(appContext);
    }

    /** 跨页请求只持有当前批次；失败后重试同一目标，不会回到第一集。 */
    private void requestChapterPage(Context ctx, final int target) {
        if (pendingChapter == target && state == LOADING) return;
        saveProgress();
        releasePlayer();
        pendingSeek = -1;
        appContext = ctx.getApplicationContext();
        final int token = ++loadToken;
        final Book requestedBook = book;
        pendingChapter = target;
        playWhenReady = true;
        state = LOADING;
        error = "";
        hint = "正在载入第 " + (target + 1) + " 集目录…";
        emitState();
        try {
            PlayerService.start(appContext);
        } catch (RuntimeException e) {
            setError("无法启动后台播放服务，请回到应用内重试");
            return;
        }
        Bg.run(() -> ChapterRepository.load(requestedBook, target / Source.CHAPTER_PAGE_SIZE),
                new Bg.Cb<Source.ChapterPage>() {
            @Override public void ok(Source.ChapterPage page) {
                if (token != loadToken || book != requestedBook) return;
                if (page != null && page.chapters != null && page.chapters.isEmpty()
                        && !page.hasMore && target == index + 1 && page.total == target) {
                    chapterTotal = page.total;
                    queueHasMore = false;
                    pendingChapter = -1;
                    if (loopAll) {
                        boolean requested = playWhenReady;
                        playIndex(appContext, 0);
                        playWhenReady = requested;
                    } else {
                        state = PAUSED;
                        abandonFocus();
                        emitState();
                    }
                    return;
                }
                int local = page == null ? -1 : target - page.page * Source.CHAPTER_PAGE_SIZE;
                if (page == null || page.chapters == null
                        || local < 0 || local >= page.chapters.size()) {
                    setError("该章节暂不可用，请刷新目录后重试");
                    return;
                }
                boolean requested = playWhenReady;
                int seek = pendingSeek;
                applyPage(page);
                index = target;
                pendingChapter = -1;
                emitTrack();
                loadCurrent(appContext);
                playWhenReady = requested;
                pendingSeek = seek;
            }
            @Override public void fail(Exception e) {
                if (token != loadToken || book != requestedBook) return;
                setError("目标章节目录加载失败，点击播放重试");
            }
        });
    }

    private void loadCurrent(final Context ctx) {
        final Chapter c = chapter();
        if (c == null) return;
        final Book b = book;
        final int token = ++loadToken;
        playWhenReady = true;
        state = LOADING;
        error = "";
        hint = "正在取播放地址…";
        emitState();
        try {
            PlayerService.start(ctx);
        } catch (RuntimeException e) {
            setError("无法启动后台播放服务，请回到应用内重试");
            return;
        }

        Bg.run(new java.util.concurrent.Callable<String>() {
            @Override
            public String call() throws Exception {
                Source s = Sources.forBook(b);
                if (s == null) throw new java.io.IOException("这个内容源没有安装，请到「我的 → 内容源」添加订阅");
                return s.audio(c);
            }
        }, new Bg.Cb<String>() {
            @Override
            public void ok(final String url) {
                if (token != loadToken) return;
                if (url == null || url.length() == 0) {
                    setError("拿不到播放地址");
                    return;
                }
                hint = "正在缓冲…";
                emitState();
                startUrl(ctx, url);
            }

            @Override
            public void fail(Exception e) {
                if (token != loadToken) return;
                setError(e.getMessage() == null ? "加载失败" : e.getMessage());
            }
        });
    }

    private void startUrl(final Context ctx, final String url) {
        releasePlayer();
        try {
            mp = new MediaPlayer();
            AudioCompat.applyAttributes(mp, audioAttributes());
            mp.setWakeMode(ctx.getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    if (m != mp) return;
                    prepared = true;
                    if (pendingSeek > 0) {
                        m.seekTo(pendingSeek);
                        pendingSeek = -1;
                    }
                    if (playWhenReady && requestFocus()) {
                        m.start();
                        state = PLAYING;
                    } else {
                        state = PAUSED;
                    }
                    emitState();
                    emitProgress();
                    startTicker();
                    PlayerService.update(ctx, isPlaying());
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    if (m != mp) return;
                    saveProgress();
                    prepared = false;
                    if (hasNextChapter() || loopAll) {
                        next(ctx);
                    } else {
                        state = PAUSED;
                        abandonFocus();
                        emitState();
                        PlayerService.update(ctx, false);
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    if (m != mp) return true;
                    prepared = false;
                    setError("播放失败（" + what + "/" + extra + "），可能是防盗链或格式不支持");
                    return true;
                }
            });
            mp.setDataSource(url);
            mp.prepareAsync();
        } catch (Exception e) {
            setError("播放器初始化失败：" + e.getMessage());
        }
    }

    private void setError(String msg) {
        releasePlayer();
        abandonFocus();
        error = msg;
        state = ERROR;
        emitState();
    }

    public void toggle(Context ctx) {
        if (state == PLAYING || (state == LOADING && playWhenReady)) {
            pause(ctx);
        } else if (state == PAUSED || state == LOADING) {
            resume(ctx);
        } else if (state == ERROR || state == IDLE) {
            if (!chapters.isEmpty()) playIndex(ctx,
                    pendingChapter >= 0 ? pendingChapter : (index < 0 ? 0 : index));
        }
    }

    public void pause(Context ctx) {
        pausePlayback();
        abandonFocus();
    }

    private void pausePlayback() {
        playWhenReady = false;
        resumeAfterFocus = false;
        try {
            if (mp != null && prepared) {
                if (mp.isPlaying()) mp.pause();
                saveProgress();
                state = PAUSED;
                emitState();
            } else if (state == LOADING) {
                emitState();
            }
        } catch (IllegalStateException e) {
            setError("暂停失败，请重新播放");
        }
    }

    public void resume(Context ctx) {
        playWhenReady = true;
        if (ctx != null) appContext = ctx.getApplicationContext();
        try {
            if (mp != null && prepared) {
                if (!requestFocus()) return;
                mp.start();
                state = PLAYING;
                emitState();
                PlayerService.update(ctx, true);
            } else if (state == LOADING) {
                emitState();
            } else if (!chapters.isEmpty()) {
                playIndex(appContext,
                        pendingChapter >= 0 ? pendingChapter : (index < 0 ? 0 : index));
            }
        } catch (Exception ignored) {
        }
    }

    private boolean hasNextChapter() {
        if (!pagedQueue) return index + 1 < chapters.size();
        return chapterTotal >= 0 ? index + 1 < chapterTotal
                : index + 1 < queueOffset + chapters.size() || queueHasMore;
    }

    public void next(Context ctx) {
        if (state == LOADING && pendingChapter >= 0) return;
        if (hasNextChapter()) playIndex(ctx, index + 1);
        else if (loopAll && !chapters.isEmpty()) playIndex(ctx, 0);
    }

    public void prev(Context ctx) {
        if (state == LOADING && pendingChapter >= 0) return;
        if (index - 1 >= 0) playIndex(ctx, index - 1);
        else if (!chapters.isEmpty()) playIndex(ctx, 0);
    }

    public void seekTo(int ms) {
        try {
            if (mp != null && prepared) {
                mp.seekTo(ms);
                emitProgress();
            } else {
                pendingSeek = ms;
            }
        } catch (Exception ignored) {
        }
    }

    /** 秒数偏移（正数前进）。 */
    public void seekBy(int seconds) {
        int t = position() + seconds * 1000;
        if (t < 0) t = 0;
        int d = duration();
        if (d > 0 && t > d) t = d;
        seekTo(t);
    }

    /* ---------------- 进度落盘 ---------------- */

    /** 由 Activity 在 onPause / 定时调用，写进 SharedPreferences。 */
    public void saveProgress() {
        if (book == null || chapter() == null || !prepared) return;
        Prefs.saveProgress(book.source, book.id, index, position(), duration(),
                chapter().title);
    }

    /* ---------------- 释放 ---------------- */

    private void releasePlayer() {
        ui.removeCallbacks(ticker);
        try {
            if (mp != null) {
                mp.reset();
                mp.release();
            }
        } catch (Exception ignored) {
        }
        mp = null;
        prepared = false;
    }

    void releaseForService() {
        saveProgress();
        loadToken++;
        pendingChapter = -1;
        playWhenReady = false;
        pendingSeek = -1;
        releasePlayer();
        abandonFocus();
        state = IDLE;
        emitState();
    }

    public void stop(Context ctx) {
        releaseForService();
        PlayerService.stop(ctx);
    }
}

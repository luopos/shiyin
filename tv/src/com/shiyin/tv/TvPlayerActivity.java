package com.shiyin.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.shiyin.app.kid.KidMode;
import com.shiyin.app.model.Book;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.play.Player;
import com.shiyin.app.play.PlayerService;
import com.shiyin.app.source.Source;
import com.shiyin.app.source.Sources;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.util.ImgView;
import com.shiyin.app.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 电视 / 车机版播放页。纯遥控 + 媒体键。
 *
 * <p>左栏封面 / 书名 / 章节 / 状态，右栏进度条和控制键，底部是当前章节前后 5 集的迷你列表。
 *
 * <p>两个刻意的行为：
 * <ul>
 *   <li>进页面**不自动播放**——车机上打开播放页不应该突然出声，必须用户按播放键；</li>
 *   <li>离开页面**不停止播放**——只摘掉监听，MediaPlayer 与前台服务归 {@link PlayerService} 管。</li>
 * </ul>
 * 低性能设备：封面走共享 {@link ImgView} 的 LruCache，进度刷新 500ms 节流，
 * onProgress 里只改文字和重绘进度条，绝不触发列表重绑或整页布局。
 */
public class TvPlayerActivity extends Activity implements Player.Listener {

    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";

    /**
     * 焦点链（上下键走这一串）。左右键固定给「快退 / 快进」，所以三个控制按钮不能靠
     * 左右键选择——它们各自是链条上的一站，上下键过去，确认键触发。
     */
    private static final int ZONE_BAR = 0;
    private static final int ZONE_PREV = 1;
    private static final int ZONE_PLAY = 2;
    private static final int ZONE_NEXT = 3;
    private static final int ZONE_LIST = 4;
    private static final int ZONE_BACK = 5;

    private static final int CTRL_PREV = 0;
    private static final int CTRL_PLAY = 1;
    private static final int CTRL_NEXT = 2;

    /** 进度条左右键步进：10 秒；长按最多 8 次/秒。 */
    private static final int SEEK_STEP_SECONDS = 10;
    private static final long SEEK_REPEAT_GAP = 120;
    /** 进度刷新节流，低端车机上 500ms 够用又不难看。 */
    private static final long PROGRESS_GAP = 500;

    public static Intent intent(Context context, Book book) {
        Intent intent = new Intent(context, TvPlayerActivity.class);
        if (book != null) {
            intent.putExtra(EXTRA_SOURCE, book.source);
            intent.putExtra(EXTRA_ID, book.id);
            intent.putExtra(EXTRA_TITLE, book.title);
        }
        return intent;
    }

    private final Book fallback = new Book();
    private final List<Chapter> mini = new ArrayList<Chapter>();

    private int zone = ZONE_PLAY;
    private int miniSel = 0;

    private long lastProgressAt = 0;
    private long lastSeekAt = 0;
    private long lastConfirmAt = 0;
    private boolean bound = false;

    private ImgView cover;
    private TextView titleView;
    private TextView chapterView;
    private TextView stateView;
    private TextView footView;
    private TextView timeCur;
    private TextView timeTotal;
    private BarView bar;
    private LinearLayout ctrlBar;
    private final List<TextView> ctrlViews = new ArrayList<TextView>();
    private TextView miniLabel;
    private ListView miniList;
    private MiniAdapter miniAdapter;
    private TextView backView;

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            Prefs.init(getApplicationContext());
        } catch (RuntimeException ignored) {
            // 与详情页一致：初始化失败不阻塞界面。
        }
        readExtras();
        // 青少年模式兜底：不合法就停播并退出，不进渲染。
        if (KidMode.enabled() && !KidMode.allowsBook(fallback)) {
            Player.get().stop(this);
            Ui.toast(this, "青少年模式下不可用");
            finish();
            return;
        }
        // 通知点开时优先回到 TV 播放页，而不是手机版 PlayerActivity。
        try {
            PlayerService.setOpenActivity(TvPlayerActivity.class);
        } catch (Throwable ignored) {
            // 兼容层缺失时保持通知原行为。
        }
        setContentView(build());
        Player.get().addListener(this);
        bound = true;
        rebuildMini();
        render();
        // 注意：这里不调用 play/resume —— 进页面不自动出声。
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!bound) {
            Player.get().addListener(this);
            bound = true;
        }
        rebuildMini();
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Player.get().saveProgress();
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            Player.get().removeListener(this);
            bound = false;
        }
        super.onDestroy();
    }

    private void readExtras() {
        Intent it = getIntent();
        fallback.source = text(it.getStringExtra(EXTRA_SOURCE));
        fallback.id = text(it.getStringExtra(EXTRA_ID));
        fallback.title = text(it.getStringExtra(EXTRA_TITLE));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    /* ---------------- 布局 ---------------- */

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(26), dp(10), dp(26), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = Ui.text(this, "拾音TV", 13f, Ui.AMBER);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(brand);
        header.addView(Ui.text(this, "  播放", 13f, Ui.DIM));
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        header.addView(gap);
        header.addView(Ui.text(this,
                "确认 播放/暂停 · ←→ 快退快进 10 秒 · 媒体键 切集 · 返回 退出", 11f, Ui.DIM2));
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(4);
        body.setLayoutParams(bodyLp);
        body.addView(buildLeft(), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.38f));
        body.addView(buildRight(), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.62f));
        root.addView(body);

        root.addView(buildBottom(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.72f));
        return root;
    }

    private View buildLeft() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, dp(18), 0);

        int screenH = getResources().getDisplayMetrics().heightPixels;
        int coverH = Math.min(dp(200), (int) (screenH * 0.30f));
        if (coverH < dp(90)) coverH = dp(90);
        int coverW = (int) (coverH * 0.75f);

        cover = new ImgView(this);
        cover.radius(10);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(coverW, coverH);
        coverLp.topMargin = dp(4);
        col.addView(cover, coverLp);

        titleView = Ui.bold(this, "", 17f, Ui.TEXT);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPadding(0, dp(10), 0, 0);
        col.addView(titleView);

        chapterView = Ui.text(this, "", 13.5f, Ui.AMBER);
        chapterView.setMaxLines(2);
        chapterView.setEllipsize(TextUtils.TruncateAt.END);
        chapterView.setPadding(0, dp(6), 0, 0);
        col.addView(chapterView);

        stateView = Ui.text(this, "", 12f, Ui.DIM);
        stateView.setMaxLines(2);
        stateView.setPadding(0, dp(6), 0, 0);
        col.addView(stateView);

        footView = Ui.text(this, "", 11f, Ui.DIM2);
        footView.setPadding(0, dp(6), 0, 0);
        col.addView(footView);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        col.addView(spacer);
        return col;
    }

    private View buildRight() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // 进度区：时间 + 进度条，作为一个焦点项（左右键 seek）。
        LinearLayout progRow = new LinearLayout(this);
        progRow.setOrientation(LinearLayout.HORIZONTAL);
        progRow.setGravity(Gravity.CENTER_VERTICAL);

        timeCur = Ui.text(this, "00:00", 12f, Ui.DIM);
        timeCur.setSingleLine(true);
        progRow.addView(timeCur, new LinearLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new BarView(this);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        barLp.leftMargin = dp(6);
        barLp.rightMargin = dp(6);
        progRow.addView(bar, barLp);

        timeTotal = Ui.text(this, "00:00", 12f, Ui.DIM);
        timeTotal.setSingleLine(true);
        timeTotal.setGravity(Gravity.END);
        progRow.addView(timeTotal, new LinearLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(progRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 控制区：上一集 / 播放暂停 / 下一集，一横行，左右键在三个按钮间移动。
        ctrlBar = new LinearLayout(this);
        ctrlBar.setOrientation(LinearLayout.HORIZONTAL);
        ctrlBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctrlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctrlLp.topMargin = dp(10);
        ctrlBar.setLayoutParams(ctrlLp);

        ctrlViews.clear();
        ctrlViews.add(ctrlButton("⏮ 上一集", CTRL_PREV));
        ctrlViews.add(ctrlButton("▶ 播放", CTRL_PLAY));
        ctrlViews.add(ctrlButton("下一集 ⏭", CTRL_NEXT));
        for (int i = 0; i < ctrlViews.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(6);
            lp.rightMargin = dp(6);
            ctrlBar.addView(ctrlViews.get(i), lp);
        }
        col.addView(ctrlBar);

        TextView tip = Ui.text(this,
                "↑↓ 在「进度条 / 上一集 / 播放 / 下一集 / 附近章节 / 返回」之间移动；"
                        + "←→ 快退快进 10 秒，长按连发", 10.5f, Ui.DIM2);
        tip.setPadding(dp(8), dp(10), 0, 0);
        col.addView(tip);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        col.addView(spacer);
        return col;
    }

    private TextView ctrlButton(String label, final int which) {
        TextView t = Ui.text(this, label, 13f, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(9), dp(18), dp(9));
        t.setSingleLine(true);
        t.setFocusable(false);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activate(which);
            }
        });
        return t;
    }

    private View buildBottom() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        colLp.topMargin = dp(6);
        col.setLayoutParams(colLp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        miniLabel = Ui.text(this, "附近章节", 11.5f, Ui.DIM);
        miniLabel.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(miniLabel);

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(gap);

        backView = Ui.text(this, "‹ 返回详情", 12.5f, Ui.TEXT);
        backView.setGravity(Gravity.CENTER);
        backView.setPadding(dp(16), dp(7), dp(16), dp(7));
        backView.setSingleLine(true);
        backView.setFocusable(false);
        backView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(backView);
        col.addView(head);

        miniList = new ListView(this);
        miniList.setDivider(null);
        miniList.setDividerHeight(0);
        miniList.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        miniList.setCacheColorHint(0);
        miniList.setVerticalScrollBarEnabled(false);
        miniList.setSmoothScrollbarEnabled(false);
        miniList.setFocusable(false);
        miniList.setFocusableInTouchMode(false);
        miniList.setItemsCanFocus(false);
        miniAdapter = new MiniAdapter();
        miniList.setAdapter(miniAdapter);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = dp(2);
        col.addView(miniList, listLp);
        return col;
    }

    /* ---------------- 渲染 ---------------- */

    private Book currentBook() {
        Book b = Player.get().book();
        return b == null ? fallback : b;
    }

    private void render() {
        Player p = Player.get();
        Book b = currentBook();
        titleView.setText(b.title.length() == 0 ? "未选择书籍" : b.title);
        if (b.cover.length() > 0) cover.load(b.cover);

        Chapter c = p.chapter();
        chapterView.setText(c == null || c.title == null ? "—" : c.title);

        int state = p.state();
        String hint;
        int color;
        if (state == Player.LOADING) {
            hint = p.message().length() == 0 ? "正在缓冲…" : p.message();
            color = Ui.AMBER;
        } else if (state == Player.PLAYING) {
            hint = "正在播放";
            color = Ui.TEAL;
        } else if (state == Player.PAUSED) {
            hint = "已暂停";
            color = Ui.DIM;
        } else if (state == Player.ERROR) {
            hint = p.error() == null || p.error().length() == 0 ? "播放失败" : p.error();
            color = Ui.RED;
        } else {
            hint = "未播放（按确认开始）";
            color = Ui.DIM2;
        }
        stateView.setText(hint);
        stateView.setTextColor(color);

        int idx = p.index();
        int count = p.chapterTotal();
        String src = "";
        try {
            Source s = Sources.byId(b.source);
            if (s != null) src = s.name();
        } catch (RuntimeException ignored) {
            // 源没注册时只是少显示一行来源。
        }
        footView.setText(idx < 0 ? src
                : "第 " + ((long) idx + 1) + (count > 0 ? " / " + count : "") + " 集"
                + (src.length() > 0 ? " · " + src : ""));

        ctrlViews.get(CTRL_PLAY).setText(
                state == Player.PLAYING ? "❚❚ 暂停"
                        : state == Player.LOADING ? "… 缓冲" : "▶ 播放");

        updateProgress(p.position(), p.duration());
        paintFocus();
        paintMini();
    }

    private void updateProgress(int positionMs, int durationMs) {
        if (durationMs > 0) {
            String total = Ui.timeMs(durationMs);
            if (!total.equals(timeTotal.getText().toString())) timeTotal.setText(total);
        }
        String cur = Ui.timeMs(positionMs);
        if (!cur.equals(timeCur.getText().toString())) timeCur.setText(cur);
        if (bar != null) bar.set(positionMs, durationMs);
    }

    private void rebuildMini() {
        mini.clear();
        Player p = Player.get();
        int cur = p.index();
        if (cur < 0) {
            paintMini();
            return;
        }
        List<Chapter> all = p.chapters();
        for (int i = 0; i < all.size(); i++) {
            Chapter c = all.get(i);
            if (c == null) continue;
            if (c.index >= cur - 5 && c.index <= cur + 5) mini.add(c);
        }
        miniSel = miniSelOf();
        if (miniAdapter != null) miniAdapter.notifyDataSetChanged();
        miniLabel.setText(mini.isEmpty() ? "附近章节" : "附近章节 · 当前第 " + ((long) cur + 1) + " 集");
    }

    private int miniSelOf() {
        if (mini.isEmpty()) return 0;
        int cur = Player.get().index();
        for (int i = 0; i < mini.size(); i++) {
            if (mini.get(i).index == cur) return i;
        }
        return Math.min(miniSel, mini.size() - 1);
    }

    private void activate(int which) {
        Player p = Player.get();
        if (which == CTRL_PREV) {
            p.prev(this);
        } else if (which == CTRL_NEXT) {
            p.next(this);
        } else {
            togglePlayback();
        }
    }

    private void togglePlayback() {
        Player p = Player.get();
        if (p.book() == null && p.chapters().isEmpty()) {
            Ui.toast(this, "还没有选中章节，回详情页选一集");
            return;
        }
        p.toggle(this);
    }

    /* ---------------- 遥控 / 媒体键 ---------------- */

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (key == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
            if (onRemoteKey(key, event.getRepeatCount())) return true;
        } else if (event.getAction() == KeyEvent.ACTION_UP && ownedKey(key)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean ownedKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return true;
            default:
                return false;
        }
    }

    private boolean onRemoteKey(int key, int repeat) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // 左右键固定是快退 / 快进，任何焦点位置都可用，遥控器不用先"对准"进度条。
                seekBy(-SEEK_STEP_SECONDS, repeat);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(SEEK_STEP_SECONDS, repeat);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (repeat > 0) return true;
                confirm();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (repeat > 0) return true;
                togglePlayback();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (repeat > 0) return true;
                Player.get().next(this);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (repeat > 0) return true;
                Player.get().prev(this);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(SEEK_STEP_SECONDS, repeat);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-SEEK_STEP_SECONDS, repeat);
                return true;
            default:
                return false;
        }
    }

    private void move(int dir) {
        if (zone == ZONE_LIST) {
            int next = miniSel + dir;
            if (next >= 0 && next < mini.size()) {
                int old = miniSel;
                miniSel = next;
                paintMiniRow(old);
                paintMiniRow(miniSel);
                ensureMiniVisible(miniSel);
                return;
            }
            zone = dir > 0 ? ZONE_BACK : ZONE_NEXT;
            paintFocus();
            return;
        }
        int next = zone;
        for (int i = 0; i < ZONES; i++) {
            next = (next + dir + ZONES) % ZONES;
            if (next != ZONE_LIST || !mini.isEmpty()) break;
        }
        zone = next;
        if (zone == ZONE_LIST) {
            miniSel = miniSelOf();
            ensureMiniVisible(miniSel);
        }
        paintFocus();
    }

    private static final int ZONES = 6;

    private void confirm() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastConfirmAt < 120) return;
        lastConfirmAt = now;

        if (zone == ZONE_PREV) {
            Player.get().prev(this);
            return;
        }
        if (zone == ZONE_NEXT) {
            Player.get().next(this);
            return;
        }
        if (zone == ZONE_PLAY || zone == ZONE_BAR) {
            togglePlayback();
            return;
        }
        if (zone == ZONE_BACK) {
            finish();
            return;
        }
        // ZONE_LIST
        if (mini.isEmpty()) {
            Ui.toast(this, "附近没有章节");
            return;
        }
        Player.get().playIndex(this, mini.get(miniSel).index);
    }

    private void seekBy(int seconds, int repeat) {
        Player p = Player.get();
        if (p.state() == Player.IDLE || p.state() == Player.ERROR) {
            Ui.toast(this, "先按播放键开始");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (repeat > 0 && now - lastSeekAt < SEEK_REPEAT_GAP) return;
        lastSeekAt = now;

        int duration = p.duration();
        int target = p.position() + seconds * 1000;
        if (target < 0) target = 0;
        if (duration > 0 && target > duration) target = duration;
        p.seekTo(target);
        // 立刻回显目标位置，不等 MediaPlayer 的异步回调。
        String cur = Ui.timeMs(target);
        if (!cur.equals(timeCur.getText().toString())) timeCur.setText(cur);
        if (bar != null) bar.set(target, duration);
    }

    /* ---------------- 播放回调（节流） ---------------- */

    @Override
    public void onState(int state, String message) {
        render();
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastProgressAt < PROGRESS_GAP) return;
        lastProgressAt = now;
        updateProgress(positionMs, durationMs);
    }

    @Override
    public void onTrack(int index, Chapter chapter) {
        rebuildMini();
        render();
        ensureMiniVisible(miniSelOf());
    }

    /* ---------------- 焦点绘制 ---------------- */

    private void paintFocus() {
        if (bar != null) bar.setFocused(zone == ZONE_BAR);
        for (int i = 0; i < ctrlViews.size(); i++) {
            TextView v = ctrlViews.get(i);
            boolean focus = zoneOfControl(i) == zone;
            if (focus) {
                v.setBackground(Ui.roundStroke(0xFF2A1E08, Ui.AMBER, 20, 1.5f, this));
                v.setTextColor(Ui.AMBER);
            } else {
                v.setBackground(Ui.roundStroke(Ui.BG3,
                        i == CTRL_PLAY ? 0xFF3A4354 : 0xFF2A3242, 20, 1f, this));
                v.setTextColor(Ui.TEXT);
            }
        }
        if (backView != null) {
            backView.setBackground(zone == ZONE_BACK
                    ? Ui.roundStroke(0xFF2A1E08, Ui.AMBER, 16, 1.5f, this) : null);
            backView.setTextColor(zone == ZONE_BACK ? Ui.AMBER : Ui.TEXT);
        }
        paintMini();
    }

    private static int zoneOfControl(int which) {
        if (which == CTRL_PREV) return ZONE_PREV;
        if (which == CTRL_NEXT) return ZONE_NEXT;
        return ZONE_PLAY;
    }

    private void paintMini() {
        if (miniAdapter != null) miniAdapter.notifyDataSetChanged();
    }

    private void paintMiniRow(int pos) {
        if (miniList == null || pos < 0 || pos >= mini.size()) return;
        int child = pos - miniList.getFirstVisiblePosition();
        if (child < 0) return;
        View v = miniList.getChildAt(child);
        if (v == null) return;
        Object tag = v.getTag();
        if (tag instanceof MiniHolder) bind((MiniHolder) tag, pos);
    }

    private void ensureMiniVisible(int pos) {
        if (miniList == null || pos < 0 || pos >= mini.size()) return;
        int first = miniList.getFirstVisiblePosition();
        int last = miniList.getLastVisiblePosition();
        if (pos >= first && pos <= last) return;
        miniList.setSelectionFromTop(Math.max(0, pos - 2), 0);
    }

    /* ---------------- 进度条 ---------------- */

    /** 自绘进度条：不依赖 SeekBar 的触摸逻辑，遥控只读，长按不抖。 */
    private final class BarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int pos;
        private int dur;
        private boolean focused;

        BarView(Context context) {
            super(context);
        }

        void set(int positionMs, int durationMs) {
            if (pos == positionMs && dur == durationMs) return;
            pos = positionMs;
            dur = durationMs;
            invalidate();
        }

        void setFocused(boolean value) {
            if (focused == value) return;
            focused = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            int cy = h / 2;
            float trackH = Math.max(dp(4), focused ? dp(7) : dp(5));
            float radius = trackH / 2f;

            if (focused) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.5f));
                paint.setColor(0xFF3A4354);
                rect.set(dp(1), dp(1), w - dp(1), h - dp(1));
                canvas.drawRoundRect(rect, dp(9), dp(9), paint);
                paint.setStyle(Paint.Style.FILL);
            }

            float left = dp(4);
            float right = w - dp(4);
            rect.set(left, cy - trackH / 2f, right, cy + trackH / 2f);
            paint.setColor(0xFF2A3242);
            canvas.drawRoundRect(rect, radius, radius, paint);

            float ratio = dur > 0 ? (float) pos / (float) dur : 0f;
            if (ratio < 0) ratio = 0;
            if (ratio > 1) ratio = 1;
            float filled = left + (right - left) * ratio;
            if (filled > left) {
                rect.set(left, cy - trackH / 2f, filled, cy + trackH / 2f);
                paint.setColor(focused ? Ui.AMBER : 0xFFB8801C);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
            if (focused) {
                paint.setColor(0xFFFFFFFF);
                canvas.drawCircle(filled, cy, dp(5), paint);
                paint.setColor(Ui.AMBER);
                canvas.drawCircle(filled, cy, dp(3), paint);
            }
        }
    }

    /* ---------------- 迷你列表 ---------------- */

    private static final class MiniHolder {
        LinearLayout row;
        TextView no;
        TextView title;
        int style = -1;
    }

    private void bind(MiniHolder h, int pos) {
        Chapter c = mini.get(pos);
        boolean playing = c.index == Player.get().index();
        boolean focus = zone == ZONE_LIST && miniSel == pos;
        int style = focus ? 2 : (playing ? 1 : 0);
        if (style != h.style) {
            h.style = style;
            if (style == 2) {
                h.row.setBackground(Ui.roundStroke(0xFF232B3A, Ui.AMBER, 6, 1.5f, this));
            } else if (style == 1) {
                h.row.setBackground(Ui.round(0xFF1A1408, 6, this));
            } else {
                h.row.setBackground(null);
            }
        }
        h.no.setText(playing ? "▶" : String.valueOf((long) c.index + 1));
        h.no.setTextColor(playing || style == 2 ? Ui.AMBER : Ui.DIM2);
        h.title.setText(c.title == null ? "" : c.title);
        h.title.setTextColor(playing ? Ui.AMBER : Ui.TEXT);
        h.title.setTypeface(playing || style == 2 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private final class MiniAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mini.size();
        }

        @Override
        public Object getItem(int position) {
            return mini.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            MiniHolder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(TvPlayerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(6), dp(10), dp(6));
                row.setFocusable(false);

                TextView no = Ui.text(TvPlayerActivity.this, "", 11f, Ui.DIM2);
                no.setGravity(Gravity.CENTER);
                no.setSingleLine(true);
                row.addView(no, new LinearLayout.LayoutParams(dp(34),
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView title = Ui.text(TvPlayerActivity.this, "", 12.5f, Ui.TEXT);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tlp.leftMargin = dp(6);
                title.setLayoutParams(tlp);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(title);

                h = new MiniHolder();
                h.row = row;
                h.no = no;
                h.title = title;
                row.setTag(h);
                convertView = row;
            } else {
                h = (MiniHolder) convertView.getTag();
            }
            bind(h, position);
            return convertView;
        }
    }
}

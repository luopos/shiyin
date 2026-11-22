package com.shiyin.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.shiyin.app.kid.KidMode;
import com.shiyin.app.model.Book;
import com.shiyin.app.model.Chapter;
import com.shiyin.app.play.Player;
import com.shiyin.app.source.ChapterRepository;
import com.shiyin.app.source.Source;
import com.shiyin.app.source.Sources;
import com.shiyin.app.source.Subscriptions;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.util.Bg;
import com.shiyin.app.util.ImgView;
import com.shiyin.app.util.Ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 电视 / 车机版详情页。纯遥控，没有触屏也没有鼠标。
 *
 * <p>布局：左栏封面 + 元信息 + 简介，右栏章节列表，底部「继续 / 从第 1 集 / 书架」。
 * 所有按键在 {@link #dispatchKeyEvent} 里自己处理（子视图一律 focusable=false），
 * 焦点高亮只用背景色 + 描边，不碰 Ripple / elevation / stateListAnimator，
 * API16 编译基准下可用，低端车机也不会因为焦点动画掉帧。
 *
 * <p>章节按 {@link Source#CHAPTER_PAGE_SIZE} 分页：一次 50 集，「下一批」那一行
 * 拿到焦点才预取下一页，同一页只发一次请求，不靠滚动回调刷网络。
 */
public class TvDetailActivity extends Activity {

    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_COVER = "cover";
    public static final String EXTRA_AUTHOR = "author";
    public static final String EXTRA_NARRATOR = "narrator";
    public static final String EXTRA_DESC = "desc";
    public static final String EXTRA_BOOK_URL = "bookUrl";

    private static final int ZONE_LEFT = 0;
    private static final int ZONE_LIST = 1;
    private static final int ZONE_BOTTOM = 2;

    private static final int LEFT_INFO = 0;
    private static final int LEFT_DESC = 1;

    private static final int ACT_CONTINUE = 0;
    private static final int ACT_FIRST = 1;
    private static final int ACT_SHELF = 2;

    private static final int ROW_NORMAL = 0;
    private static final int ROW_FOCUS = 1;
    private static final int ROW_PLAYING = 2;
    private static final int ROW_PLAYING_FOCUS = 3;
    private static final int ROW_MORE = 4;
    private static final int ROW_MORE_FOCUS = 5;

    /** 简介收起 / 展开的行数；展开时封面让位，长简介也放得下。 */
    private static final int DESC_LINES = 5;
    private static final int DESC_LINES_OPEN = 20;

    /** TV 首页只用 source/id/title 三个 extra，其余字段手机版同样会传。 */
    public static Intent intent(Context context, Book book) {
        Intent intent = new Intent(context, TvDetailActivity.class);
        intent.putExtra(EXTRA_SOURCE, book.source);
        intent.putExtra(EXTRA_ID, book.id);
        intent.putExtra(EXTRA_TITLE, book.title);
        intent.putExtra(EXTRA_COVER, book.cover);
        intent.putExtra(EXTRA_AUTHOR, book.author);
        intent.putExtra(EXTRA_NARRATOR, book.narrator);
        intent.putExtra(EXTRA_DESC, book.desc);
        intent.putExtra(EXTRA_BOOK_URL, book.bookUrl);
        return intent;
    }

    private final Book book = new Book();
    private final List<Chapter> items = new ArrayList<Chapter>();

    private int zone = ZONE_LIST;
    private int leftPos = LEFT_INFO;
    private int listPos = 0;
    private int bottomPos = 0;

    /** 列表从哪一页开始；items 从这一页第一集连续往后排。 */
    private int firstPage = 0;
    private boolean hasMore = false;
    private int total = -1;

    private int continuation = -1;
    private int playingIndex = -1;

    private int loadGeneration = 0;

    private boolean firstLoading = false;
    private boolean nextLoading = false;
    private String firstError = null;
    private String nextError = null;
    private int firstErrorPage = 0;

    private long lastConfirmAt = 0;

    private ImgView cover;
    private View infoBox;
    private View descBlock;
    private TextView titleView;
    private TextView whoView;
    private TextView statusView;
    private TextView srcView;
    private TextView descView;
    private TextView countView;
    private LinearLayout stateBox;
    private ProgressBar stateSpinner;
    private TextView stateText;
    private TextView emptyView;
    private ListView listView;
    private ChapterAdapter adapter;
    private final List<TextView> bottomViews = new ArrayList<TextView>();

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            // TvApp 正常已初始化；直接 am start 进来时也保证 SharedPreferences 可用。
            Prefs.init(getApplicationContext());
        } catch (RuntimeException ignored) {
            // 初始化失败留给加载阶段报错。
        }
        readExtras();
        // 青少年模式兜底：必须在渲染和任何网络请求之前挡掉，避免内容闪现。
        if (KidMode.enabled() && !KidMode.allowsBook(book)) {
            Ui.toast(this, "青少年模式下不可用");
            finish();
            return;
        }
        setContentView(build());
        int startPage = savedInstanceState == null
                ? -1 : savedInstanceState.getInt("startPage", -1);
        if (startPage < 0) {
            continuation = completeContinuationIndex();
            startPage = continuation >= 0 ? continuation / Source.CHAPTER_PAGE_SIZE : 0;
        }
        loadPage(startPage, true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("startPage", firstPage);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPlaying();
        updateBottomLabels();
        paintBottom();
        adapter.notifyDataSetChanged();
        paintRow(listPos);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Player.get().saveProgress();
    }

    @Override
    protected void onDestroy() {
        loadGeneration++;
        super.onDestroy();
    }

    private void readExtras() {
        Intent it = getIntent();
        book.source = text(it.getStringExtra(EXTRA_SOURCE));
        book.id = text(it.getStringExtra(EXTRA_ID));
        book.title = text(it.getStringExtra(EXTRA_TITLE));
        book.cover = text(it.getStringExtra(EXTRA_COVER));
        book.author = text(it.getStringExtra(EXTRA_AUTHOR));
        book.narrator = text(it.getStringExtra(EXTRA_NARRATOR));
        book.desc = text(it.getStringExtra(EXTRA_DESC));
        book.bookUrl = text(it.getStringExtra(EXTRA_BOOK_URL));
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
        root.setPadding(dp(26), dp(12), dp(26), dp(10));

        root.addView(buildHeader());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(6);
        body.setLayoutParams(bodyLp);

        body.addView(buildLeft(), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.40f));
        body.addView(buildRight(), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.60f));
        root.addView(body);

        root.addView(buildBottom());
        return root;
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView left = Ui.text(this, "拾音TV", 13f, Ui.AMBER);
        left.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(left);
        bar.addView(Ui.text(this, "  详情", 13f, Ui.DIM));

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(gap);
        bar.addView(Ui.text(this, "↑↓ 选择 · ←→ 切栏 · 确认 播放 · 返回 上一页", 11f, Ui.DIM2));
        return bar;
    }

    private View buildLeft() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(4), dp(18), 0);

        infoBox = buildInfoBox();
        col.addView(infoBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        descBlock = buildDescBlock();
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(8);
        col.addView(descBlock, descLp);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        col.addView(spacer);
        return col;
    }

    private View buildInfoBox() {
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setPadding(dp(10), dp(10), dp(10), dp(10));

        int screenH = getResources().getDisplayMetrics().heightPixels;
        // 大封面约 240x320dp；小屏 / 车机按可用高度收缩，保证标题和按钮都在屏内。
        int coverH = Math.min(dp(320), (int) (screenH * 0.44f));
        if (coverH < dp(120)) coverH = dp(120);
        int coverW = (int) (coverH * 0.75f);
        if (coverW > dp(300)) {
            coverW = dp(300);
            coverH = (int) (coverW / 0.75f);
        }

        cover = new ImgView(this);
        cover.radius(10);
        cover.setLayoutParams(new LinearLayout.LayoutParams(coverW, coverH));
        cover.load(book.cover);
        info.addView(cover);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        metaLp.leftMargin = dp(14);
        meta.setLayoutParams(metaLp);

        titleView = Ui.bold(this, book.title, 19f, Ui.TEXT);
        titleView.setMaxLines(3);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(titleView);

        whoView = Ui.text(this, "", 12.5f, Ui.DIM);
        whoView.setPadding(0, dp(8), 0, 0);
        whoView.setMaxLines(2);
        meta.addView(whoView);

        statusView = Ui.text(this, "", 11.5f, Ui.DIM2);
        statusView.setPadding(0, dp(6), 0, 0);
        meta.addView(statusView);

        srcView = Ui.text(this, "", 11f, Ui.TEAL);
        srcView.setPadding(0, dp(6), 0, 0);
        meta.addView(srcView);

        info.addView(meta);
        return info;
    }

    private View buildDescBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView label = Ui.text(this, "简介", 11f, Ui.AMBER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(label);

        descView = Ui.text(this, "", 12f, Ui.DIM);
        descView.setMaxLines(DESC_LINES);
        descView.setEllipsize(TextUtils.TruncateAt.END);
        descView.setLineSpacing(dp(2), 1f);
        descView.setPadding(0, dp(5), 0, 0);
        box.addView(descView);
        return box;
    }

    private View buildRight() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), 0, dp(4), dp(6));

        View bar = new View(this);
        bar.setBackground(Ui.round(Ui.AMBER, 2, this));
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(3), dp(14)));
        head.addView(bar);

        TextView label = Ui.bold(this, "目录", 15f, Ui.TEXT);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = dp(8);
        label.setLayoutParams(labelLp);
        head.addView(label);

        countView = Ui.text(this, "每页 " + Source.CHAPTER_PAGE_SIZE + " 集", 11.5f, Ui.DIM2);
        head.addView(countView);
        col.addView(head);

        stateBox = new LinearLayout(this);
        stateBox.setOrientation(LinearLayout.HORIZONTAL);
        stateBox.setGravity(Gravity.CENTER_VERTICAL);
        stateBox.setPadding(dp(10), dp(4), dp(10), dp(6));

        stateSpinner = new ProgressBar(this);
        stateSpinner.setIndeterminate(true);
        stateBox.addView(stateSpinner, new LinearLayout.LayoutParams(dp(20), dp(20)));

        stateText = Ui.text(this, "", 12f, Ui.DIM);
        stateText.setPadding(dp(10), 0, 0, 0);
        stateText.setMaxLines(2);
        stateBox.addView(stateText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stateBox.setVisibility(View.GONE);
        col.addView(stateBox);

        FrameLayout listBox = new FrameLayout(this);
        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        listView.setCacheColorHint(0);
        listView.setSmoothScrollbarEnabled(false);
        listView.setVerticalScrollBarEnabled(false);
        // 焦点由页面自己画：列表不吃按键，也不抢焦点。
        listView.setFocusable(false);
        listView.setFocusableInTouchMode(false);
        listView.setItemsCanFocus(false);
        adapter = new ChapterAdapter();
        listView.setAdapter(adapter);
        listBox.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = Ui.text(this, "正在载入目录…", 13f, Ui.DIM2);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(16), dp(16), dp(16), dp(16));
        listBox.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        col.addView(listBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private View buildBottom() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(8);
        bar.setLayoutParams(barLp);

        bottomViews.clear();
        bottomViews.add(button("▶ 播放", ACT_CONTINUE));
        bottomViews.add(button("▶ 从第 1 集播放", ACT_FIRST));
        bottomViews.add(button("＋ 加入书架", ACT_SHELF));
        for (int i = 0; i < bottomViews.size(); i++) {
            TextView v = bottomViews.get(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(12);
            bar.addView(v, lp);
        }
        return bar;
    }

    private TextView button(String label, final int action) {
        TextView t = Ui.text(this, label, 13.5f, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(20), dp(10), dp(20), dp(10));
        t.setFocusable(false);
        t.setSingleLine(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activate(action);
            }
        });
        return t;
    }

    /* ---------------- 数据 ---------------- */

    private void loadPage(final int page, final boolean reset) {
        if (page < 0) return;
        final int generation = ++loadGeneration;
        if (reset) {
            firstLoading = true;
            firstError = null;
            firstErrorPage = page;
            nextError = null;
            nextLoading = false;
            hasMore = false;
        } else {
            nextLoading = true;
            nextError = null;
        }
        if (reset) {
            // 有缓存先铺上，断网 / 车机弱网也能马上看到目录。
            Source.ChapterPage cached = ChapterRepository.cached(book, page);
            if (cached != null && cached.chapters != null && !cached.chapters.isEmpty()) {
                applyPage(cached, page);
            }
        }
        showState();
        final Book request = book;
        Bg.run(new Callable<Source.ChapterPage>() {
            @Override
            public Source.ChapterPage call() throws Exception {
                ensureSource();
                return ChapterRepository.load(request, page);
            }
        }, new Bg.Cb<Source.ChapterPage>() {
            @Override
            public void ok(Source.ChapterPage result) {
                if (generation != loadGeneration || isFinishing()) return;
                if (reset) firstLoading = false;
                else nextLoading = false;
                if (result == null || result.chapters == null || result.chapters.isEmpty()) {
                    onPageFailed(page, reset, new IOException("这一页没有章节"));
                    return;
                }
                applyPage(result, page);
                showState();
            }

            @Override
            public void fail(Exception e) {
                if (generation != loadGeneration || isFinishing()) return;
                if (reset) firstLoading = false;
                else nextLoading = false;
                onPageFailed(page, reset, e);
            }
        });
    }

    /**
     * 直接 am start 进来时订阅可能还没注册：先用本地缓存注册，再不行才联网刷一次。
     * 全部在后台线程执行，不阻塞 UI。
     */
    private void ensureSource() throws IOException {
        if (book.source.length() == 0) throw new IOException("缺少内容源参数");
        if (Sources.byId(book.source) != null) return;
        Subscriptions.reload();
        if (Sources.byId(book.source) != null) return;
        Subscriptions.refreshStale();
        Subscriptions.reload();
        if (Sources.byId(book.source) == null) {
            throw new IOException("这个内容源没有安装，请先在首页「内容源」里添加订阅");
        }
    }

    private void onPageFailed(int page, boolean reset, Exception e) {
        String reason = e == null || e.getMessage() == null || e.getMessage().length() == 0
                ? "目录加载失败" : e.getMessage();
        if (reset) {
            firstError = items.isEmpty() ? reason : reason + "（下面是已缓存的目录）";
            firstErrorPage = page;
        } else {
            nextError = reason;
        }
        showState();
        adapter.notifyDataSetChanged();
        paintRow(listPos);
    }

    private void applyPage(Source.ChapterPage page, int requestPage) {
        if (page.meta != null) mergeMeta(page.meta);
        boolean append = !items.isEmpty() && page.page > firstPage;
        if (!append) {
            items.clear();
            firstPage = page.page;
        }
        int before = items.size();
        for (int i = 0; i < page.chapters.size(); i++) {
            Chapter c = page.chapters.get(i);
            if (c != null) items.add(c);
        }
        hasMore = page.hasMore;
        if (page.total >= 0) total = page.total;
        else if (total < 0) total = items.size();
        nextError = null;

        adapter.notifyDataSetChanged();
        if (!append) {
            int at = indexOfPosition(continuation);
            listPos = at < 0 ? 0 : at;
            // 新一批接在后面时，光标留在原处（那一行现在是真实章节）。
            if (listPos >= rowCount()) listPos = Math.max(0, rowCount() - 1);
        } else if (listPos > rowCount() - 1) {
            listPos = Math.max(0, rowCount() - 1);
        }
        renderBookInfo();
        updateBottomLabels();
        paintBottom();
        ensureVisible(listPos);
        paintRow(listPos);
        if (append) paintRow(before);
    }

    private void mergeMeta(Book meta) {
        book.cover = prefer(meta.cover, book.cover);
        book.title = prefer(meta.title, book.title);
        book.author = prefer(meta.author, book.author);
        book.narrator = prefer(meta.narrator, book.narrator);
        book.desc = prefer(meta.desc, book.desc);
        book.status = prefer(meta.status, book.status);
        book.bookUrl = prefer(meta.bookUrl, book.bookUrl);
    }

    private static String prefer(String fresh, String old) {
        return fresh == null || fresh.trim().length() == 0 ? old : fresh;
    }

    private void renderBookInfo() {
        titleView.setText(book.title);
        titleView.setVisibility(book.title.length() == 0 ? View.GONE : View.VISIBLE);

        String who = book.author;
        if (book.narrator.length() > 0) {
            who = who.length() == 0 ? book.narrator : who + " · " + book.narrator;
        }
        whoView.setText(who);
        whoView.setVisibility(who.length() == 0 ? View.GONE : View.VISIBLE);

        statusView.setText(statusLine());
        statusView.setVisibility(statusLine().length() == 0 ? View.GONE : View.VISIBLE);

        String src = "";
        Source s = Sources.byId(book.source);
        if (s != null) src = s.name();
        srcView.setText(src);
        srcView.setVisibility(src.length() == 0 ? View.GONE : View.VISIBLE);

        descView.setText(book.desc);
        descBlock.setVisibility(book.desc.length() == 0 ? View.GONE : View.VISIBLE);
        if (cover != null && book.cover.length() > 0) cover.load(book.cover);

        countView.setText(total > 0
                ? "共 " + total + " 集 · 每页 " + Source.CHAPTER_PAGE_SIZE + " 集"
                : "每页 " + Source.CHAPTER_PAGE_SIZE + " 集");
        if (zone == ZONE_LEFT && leftPos == LEFT_DESC && !hasDesc()) leftPos = LEFT_INFO;
    }

    /** 源给的 status 有时是 "1" 这种状态码，不要直接甩到电视上。 */
    private String statusLine() {
        String raw = book.status == null ? "" : book.status.trim();
        if (raw.length() > 0 && TextUtils.isDigitsOnly(raw)) raw = "";
        if (total > 0) {
            String count = "共 " + total + " 集";
            if (raw.length() == 0) return count;
            return raw.indexOf("集") >= 0 ? raw : raw + " · " + count;
        }
        return raw;
    }

    private void showState() {
        boolean busy = firstLoading || nextLoading;
        String msg = "";
        if (firstLoading) {
            msg = items.isEmpty() ? "正在载入目录…" : "正在更新目录…";
        } else if (nextLoading) {
            msg = "正在载入下一批…";
        } else if (firstError != null) {
            msg = firstError;
        } else if (nextError != null) {
            msg = "下一批加载失败：" + nextError;
        }
        if (msg.length() > 0 && !busy) msg = msg + "（按确认重试）";
        stateBox.setVisibility(msg.length() > 0 ? View.VISIBLE : View.GONE);
        stateSpinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        stateText.setText(msg);
        stateText.setTextColor(busy ? Ui.DIM : Ui.RED);

        if (!items.isEmpty()) {
            emptyView.setVisibility(View.GONE);
        } else if (busy) {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText("正在载入目录…");
            emptyView.setTextColor(Ui.DIM2);
        } else if (firstError != null) {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText("目录没加载出来\n" + firstError + "\n\n按「确认」重试，返回键退出");
            emptyView.setTextColor(Ui.DIM);
        } else {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText("这一本还没有章节");
            emptyView.setTextColor(Ui.DIM2);
        }
    }

    private void retry() {
        if (nextError != null) loadPage(nextPage(), false);
        else loadPage(firstErrorPage, true);
    }

    /* ---------------- 续播 / 播放 ---------------- */

    private int completeContinuationIndex() {
        Player player = Player.get();
        Book active = player.book();
        if (active != null && book.source.equals(active.source) && book.id.equals(active.id)
                && (player.state() == Player.PLAYING || player.state() == Player.PAUSED
                || player.state() == Player.LOADING) && player.index() >= 0) {
            return player.index();
        }
        Prefs.Prog p = Prefs.progress(book.source, book.id);
        return p == null ? -1 : p.chapterIndex;
    }

    private void refreshPlaying() {
        continuation = completeContinuationIndex();
        playingIndex = continuation;
        Player player = Player.get();
        Book active = player.book();
        if (active != null && book.source.equals(active.source) && book.id.equals(active.id)) {
            playingIndex = player.index();
        }
    }

    private void updateBottomLabels() {
        if (bottomViews.size() < 3) return;
        bottomViews.get(ACT_CONTINUE).setText(continuation >= 0
                ? "▶ 继续第 " + ((long) continuation + 1) + " 集" : "▶ 播放选中集");
        bottomViews.get(ACT_FIRST).setText("▶ 从第 1 集播放");
        bottomViews.get(ACT_SHELF).setText(Prefs.inShelf(book) ? "已在书架" : "＋ 加入书架");
    }

    private void activate(int action) {
        if (action == ACT_SHELF) {
            boolean added = Prefs.toggleShelf(book);
            Ui.toast(this, added ? "已加入书架" : "已移出书架");
            updateBottomLabels();
            paintBottom();
            return;
        }
        if (action == ACT_FIRST) {
            playGlobal(0);
            return;
        }
        if (continuation >= 0) {
            playGlobal(continuation);
        } else if (!items.isEmpty()) {
            playGlobal(items.get(Math.min(listPos, items.size() - 1)).index);
        } else {
            Ui.toast(this, "目录还没载入完");
        }
    }

    /** 播放任意全局集号；不在当前批次里就先取那一页（命中详情缓存，通常不再发请求）。 */
    private void playGlobal(final int globalIndex) {
        if (globalIndex < 0) return;
        int base = firstPage * Source.CHAPTER_PAGE_SIZE;
        if (globalIndex >= base && globalIndex < base + items.size()) {
            startPlayback(currentPage(), globalIndex);
            return;
        }
        if (firstLoading) {
            Ui.toast(this, "正在载入目录，请稍候");
            return;
        }
        final int page = globalIndex / Source.CHAPTER_PAGE_SIZE;
        stateBox.setVisibility(View.VISIBLE);
        stateSpinner.setVisibility(View.VISIBLE);
        stateText.setTextColor(Ui.DIM);
        stateText.setText("正在定位第 " + ((long) globalIndex + 1) + " 集…");
        final int generation = ++loadGeneration;
        final Book request = book;
        Bg.run(new Callable<Source.ChapterPage>() {
            @Override
            public Source.ChapterPage call() throws Exception {
                ensureSource();
                return ChapterRepository.load(request, page);
            }
        }, new Bg.Cb<Source.ChapterPage>() {
            @Override
            public void ok(Source.ChapterPage result) {
                if (generation != loadGeneration || isFinishing()) return;
                stateBox.setVisibility(View.GONE);
                if (result == null || result.chapters == null || result.chapters.isEmpty()) {
                    Ui.toast(TvDetailActivity.this, "这一集暂时不可用");
                    return;
                }
                startPlayback(result, globalIndex);
            }

            @Override
            public void fail(Exception e) {
                if (generation != loadGeneration || isFinishing()) return;
                stateBox.setVisibility(View.GONE);
                Ui.toast(TvDetailActivity.this, e.getMessage() == null
                        ? "定位章节失败" : e.getMessage());
            }
        });
    }

    private Source.ChapterPage currentPage() {
        Source.ChapterPage page = new Source.ChapterPage();
        page.page = firstPage;
        page.total = total;
        page.hasMore = hasMore;
        page.meta = book;
        for (int i = 0; i < items.size(); i++) page.chapters.add(items.get(i));
        return page;
    }

    private void startPlayback(Source.ChapterPage page, int globalIndex) {
        Player player = Player.get();
        Prefs.Prog p = Prefs.progress(book.source, book.id);
        player.setChapterPage(this, book, page, globalIndex);
        if (p != null && p.chapterIndex == globalIndex && p.position > 3000) {
            player.seekTo(p.position);
        }
        continuation = globalIndex;
        playingIndex = globalIndex;
        updateBottomLabels();
        paintBottom();
        adapter.notifyDataSetChanged();
        paintRow(listPos);
        startActivity(TvPlayerActivity.intent(this, book));
    }

    /* ---------------- 遥控 ---------------- */

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
        return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
    }

    private boolean onRemoteKey(int key, int repeat) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return move(-1);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return move(1);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return cross(-1);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return cross(1);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (repeat > 0) return true;
                confirm();
                return true;
            default:
                return false;
        }
    }

    private boolean move(int dir) {
        if (zone == ZONE_LEFT) {
            if (!hasDesc()) {
                zone = ZONE_BOTTOM;
                bottomPos = 0;
            } else {
                int next = leftPos + dir;
                if (next < 0 || next > LEFT_DESC) {
                    zone = ZONE_BOTTOM;
                    bottomPos = 0;
                } else {
                    leftPos = next;
                }
            }
            renderAll();
            return true;
        }
        if (zone == ZONE_LIST) {
            int count = rowCount();
            if (count == 0) {
                if (dir < 0) {
                    zone = ZONE_LEFT;
                    leftPos = LEFT_INFO;
                    renderAll();
                }
                return true;
            }
            int next = listPos + dir;
            if (next < 0) {
                zone = ZONE_LEFT;
                leftPos = 0;
                renderAll();
                return true;
            }
            if (next >= count) {
                zone = ZONE_BOTTOM;
                bottomPos = 0;
                renderAll();
                return true;
            }
            int old = listPos;
            listPos = next;
            paintRow(old);
            paintRow(listPos);
            ensureVisible(listPos);
            maybeLoadNext();
            return true;
        }
        // ZONE_BOTTOM
        if (dir > 0) {
            zone = ZONE_LIST;
            if (rowCount() > 0 && listPos >= rowCount()) listPos = rowCount() - 1;
            if (rowCount() == 0) zone = ZONE_LEFT;
        } else {
            zone = ZONE_LEFT;
            leftPos = LEFT_INFO;
        }
        renderAll();
        return true;
    }

    private boolean cross(int dir) {
        if (zone == ZONE_BOTTOM) {
            int next = bottomPos + dir;
            if (next < 0 || next >= bottomViews.size()) return true;
            int old = bottomPos;
            bottomPos = next;
            paintBottomButton(old);
            paintBottomButton(bottomPos);
            return true;
        }
        if (zone == ZONE_LEFT) {
            if (dir > 0) {
                zone = ZONE_LIST;
                leftPos = LEFT_INFO;
                renderAll();
                ensureVisible(listPos);
            }
            return true;
        }
        // ZONE_LIST
        if (dir < 0) {
            zone = ZONE_LEFT;
            leftPos = hasDesc() ? LEFT_DESC : LEFT_INFO;
            renderAll();
        }
        return true;
    }

    private void confirm() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastConfirmAt < 120) return;
        lastConfirmAt = now;

        if (zone == ZONE_BOTTOM) {
            activate(bottomPos == ACT_FIRST ? ACT_FIRST
                    : bottomPos == ACT_SHELF ? ACT_SHELF : ACT_CONTINUE);
            return;
        }
        if (zone == ZONE_LEFT) {
            if (leftPos == LEFT_DESC) return; // 简介聚焦即展开，不重复触发
            activate(ACT_CONTINUE);
            return;
        }
        // ZONE_LIST
        if (items.isEmpty()) {
            if (firstError != null || nextError != null) retry();
            else Ui.toast(this, firstLoading ? "正在载入目录" : "还没有章节");
            return;
        }
        if (listPos >= items.size()) {
            if (nextLoading) Ui.toast(this, "正在载入下一批");
            else loadPage(nextPage(), false);
            return;
        }
        playGlobal(items.get(listPos).index);
    }

    /* ---------------- 列表 ---------------- */

    private boolean hasDesc() {
        return book.desc != null && book.desc.length() > 0;
    }

    private int rowCount() {
        if (items.isEmpty()) return 0;
        return items.size() + (hasMore ? 1 : 0);
    }

    private int nextPage() {
        return firstPage + (items.size() + Source.CHAPTER_PAGE_SIZE - 1)
                / Source.CHAPTER_PAGE_SIZE;
    }

    private int indexOfPosition(int globalIndex) {
        if (globalIndex < 0) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).index == globalIndex) return i;
        }
        return -1;
    }

    /** 光标停在最后一集（或「下一批」那行）时才取下一页；一页只会取一次。 */
    private void maybeLoadNext() {
        if (!hasMore || nextLoading || items.isEmpty()) return;
        if (listPos < items.size() - 1) return;
        if (nextError != null && listPos < items.size()) return;
        loadPage(nextPage(), false);
    }

    private void ensureVisible(int pos) {
        if (listView == null || pos < 0) return;
        int first = listView.getFirstVisiblePosition();
        int last = listView.getLastVisiblePosition();
        if (pos >= first && pos <= last) return;
        int offset = Math.max(0, listView.getHeight() / 3);
        listView.setSelectionFromTop(pos, offset);
    }

    private void paintRow(int pos) {
        if (listView == null || pos < 0 || pos >= rowCount()) return;
        int child = pos - listView.getFirstVisiblePosition();
        if (child < 0) return;
        View v = listView.getChildAt(child);
        if (v == null) return;
        Object tag = v.getTag();
        if (tag instanceof Holder) bind((Holder) tag, pos);
    }

    private int styleOf(int pos) {
        boolean focus = zone == ZONE_LIST && pos == listPos;
        if (pos >= items.size()) return focus ? ROW_MORE_FOCUS : ROW_MORE;
        boolean playing = items.get(pos).index == playingIndex;
        if (playing) return focus ? ROW_PLAYING_FOCUS : ROW_PLAYING;
        return focus ? ROW_FOCUS : ROW_NORMAL;
    }

    private void bind(Holder h, int pos) {
        h.pos = pos;
        int style = styleOf(pos);
        if (style != h.style) {
            h.style = style;
            h.row.setBackground(background(style));
        }
        if (pos >= items.size()) {
            h.no.setText("▼");
            h.no.setTextColor(Ui.TEAL);
            h.duration.setText("");
            h.title.setTypeface(Typeface.DEFAULT);
            if (nextLoading) {
                h.title.setText("正在载入第 " + (nextPage() + 1) + " 页…");
                h.title.setTextColor(Ui.DIM);
            } else if (nextError != null) {
                h.title.setText("下一批加载失败，按确认重试");
                h.title.setTextColor(Ui.RED);
            } else {
                int from = nextPage() * Source.CHAPTER_PAGE_SIZE + 1;
                int to = (nextPage() + 1) * Source.CHAPTER_PAGE_SIZE;
                if (total > 0 && to > total) to = total;
                h.title.setText("载入下一批 · 第 " + from + "–" + to + " 集");
                h.title.setTextColor(Ui.TEAL);
            }
            return;
        }
        Chapter c = items.get(pos);
        boolean playing = c.index == playingIndex;
        boolean strong = playing || style == ROW_FOCUS || style == ROW_PLAYING_FOCUS;
        h.no.setText(playing ? "▶" : String.valueOf((long) c.index + 1));
        h.no.setTextColor(strong ? Ui.AMBER : Ui.DIM2);
        h.title.setText(c.title == null ? "" : c.title);
        h.title.setTypeface(strong ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        h.title.setTextColor(playing ? Ui.AMBER : Ui.TEXT);
        h.duration.setText(c.duration > 0 ? Ui.time(c.duration) : "");
    }

    private Drawable background(int style) {
        switch (style) {
            case ROW_FOCUS:
                return Ui.roundStroke(0xFF232B3A, Ui.AMBER, 8, 1.5f, this);
            case ROW_PLAYING:
                return Ui.round(0xFF1A1408, 8, this);
            case ROW_PLAYING_FOCUS:
                return Ui.roundStroke(0xFF2A1E08, Ui.AMBER, 8, 1.5f, this);
            case ROW_MORE:
                return Ui.round(0x141F2A38, 8, this);
            case ROW_MORE_FOCUS:
                return Ui.roundStroke(0x232B3A, Ui.TEAL, 8, 1.5f, this);
            default:
                return null;
        }
    }

    private static final class Holder {
        LinearLayout row;
        TextView no;
        TextView title;
        TextView duration;
        int style = -1;
        int pos = -1;
    }

    private final class ChapterAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rowCount();
        }

        @Override
        public Object getItem(int position) {
            return position < items.size() ? items.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(TvDetailActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(9), dp(10), dp(9));
                row.setFocusable(false);

                TextView no = Ui.text(TvDetailActivity.this, "", 11.5f, Ui.DIM2);
                no.setGravity(Gravity.CENTER);
                no.setSingleLine(true);
                row.addView(no, new LinearLayout.LayoutParams(dp(38),
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView title = Ui.text(TvDetailActivity.this, "", 14f, Ui.TEXT);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tlp.leftMargin = dp(6);
                title.setLayoutParams(tlp);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(title);

                TextView duration = Ui.text(TvDetailActivity.this, "", 10.5f, Ui.DIM2);
                duration.setSingleLine(true);
                row.addView(duration);

                h = new Holder();
                h.row = row;
                h.no = no;
                h.title = title;
                h.duration = duration;
                row.setTag(h);
                convertView = row;
            } else {
                h = (Holder) convertView.getTag();
            }
            bind(h, position);
            return convertView;
        }
    }

    /* ---------------- 焦点绘制 ---------------- */

    private void renderAll() {
        paintLeft();
        paintBottom();
        adapter.notifyDataSetChanged();
        paintRow(listPos);
    }

    private void paintLeft() {
        boolean infoFocus = zone == ZONE_LEFT && leftPos == LEFT_INFO;
        boolean descFocus = zone == ZONE_LEFT && leftPos == LEFT_DESC;
        infoBox.setBackground(infoFocus
                ? Ui.roundStroke(0xFF202836, Ui.AMBER, 10, 1.5f, this) : null);
        descBlock.setBackground(descFocus
                ? Ui.roundStroke(0xFF202836, Ui.AMBER, 10, 1.5f, this) : null);
        boolean open = descFocus && hasDesc();
        if (cover != null) cover.setVisibility(open ? View.GONE : View.VISIBLE);
        descView.setMaxLines(open ? DESC_LINES_OPEN : DESC_LINES);
        descView.setTextColor(open ? Ui.TEXT : Ui.DIM);
    }

    private void paintBottom() {
        for (int i = 0; i < bottomViews.size(); i++) paintBottomButton(i);
    }

    private void paintBottomButton(int i) {
        if (i < 0 || i >= bottomViews.size()) return;
        TextView v = bottomViews.get(i);
        boolean focus = zone == ZONE_BOTTOM && bottomPos == i;
        if (focus) {
            v.setBackground(Ui.roundStroke(0xFF2A1E08, Ui.AMBER, 20, 1.5f, this));
            v.setTextColor(Ui.AMBER);
        } else {
            v.setBackground(Ui.roundStroke(Ui.BG3, i == 0 ? 0xFF3A4354 : 0xFF2A3242,
                    20, 1f, this));
            v.setTextColor(Ui.TEXT);
        }
    }
}

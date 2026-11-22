package com.shiyin.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.shiyin.app.kid.KidMode;
import com.shiyin.app.model.Book;
import com.shiyin.app.model.Cat;
import com.shiyin.app.source.JsonSource;
import com.shiyin.app.source.Source;
import com.shiyin.app.source.Sources;
import com.shiyin.app.source.Subscriptions;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.util.Bg;
import com.shiyin.app.util.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 拾音 TV 主界面：左侧竖向导航（首页 / 分类 / 书架 / 我的），右侧内容区。
 *
 * <p>遥控器约定：
 * <ul>
 *   <li>左栏 ↔ 内容区：DPAD_RIGHT 进内容区，DPAD_LEFT 在内容区最左侧时回到左栏；</li>
 *   <li>DPAD_CENTER / ENTER：打开详情、切分类、点按钮；</li>
 *   <li>BACK：分类子页 → 分类根页，其它 tab → 首页；</li>
 *   <li>MEDIA_PLAY_PAUSE / NEXT / PREVIOUS：转发给共享 Player（反射，未集成时不崩）。</li>
 * </ul>
 *
 * <p>首页按内容源分板块，切栏返回直接复用已渲染的 View 和磁盘缓存，不重复发请求；
 * 缓存策略与手机版一致：按源缓存 + 24 小时周期。
 */
public class TvMainActivity extends Activity {

    private static final int TAB_HOME = 0;
    private static final int TAB_CATS = 1;
    private static final int TAB_SHELF = 2;
    private static final int TAB_MINE = 3;
    private static final String[] TAB_NAMES = {"首页", "分类", "书架", "我的"};
    private static final String[] TAB_ICONS = {"⌂", "▤", "▥", "⚙"};
    private static final int GRID_COLUMNS = 5;
    private static final String DEFAULT_SUBSCRIPTION = "https://stock99.kdns.fr/shiyin/sources.json";

    private FrameLayout content;
    private LinearLayout navRail;
    private final View[] navItems = new View[4];
    private final TextView[] navIcons = new TextView[4];
    private final TextView[] navLabels = new TextView[4];
    private int tab = TAB_HOME;

    /* 首页 */
    private View homePage;
    private LinearLayout homeBody;
    private ScrollView homeScroll;
    private String homeVersion = "";
    private boolean homeLoading;
    private boolean firstResume = true;

    /* 分类 */
    private LinearLayout catsRootBody;
    private boolean catsLoading;
    private List<Cat> catRoots = new ArrayList<>();
    private Cat openRoot;
    private int catChipIndex;
    private Cat catLeaf;
    private final List<Book> catBooks = new ArrayList<>();
    private LinearLayout gridBody;
    private int catPage;
    private int catTotalPage = 1;
    private int catGeneration;
    private boolean catPageLoading;
    private TextView catHint;

    private static final class Sec {
        String label = "";
        List<Book> books = new ArrayList<>();
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildRoot());
        selectTab(TAB_HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        // 详情页可能刚改过书架/订阅，回来重建这两个轻量页面；首页保持原 View 不重发请求。
        if (tab == TAB_SHELF) showShelf();
        else if (tab == TAB_MINE) showMine();
    }

    /**
     * 平板旋转时 Activity 不重建（manifest 声明了 configChanges），必须自己重排。
     *
     * <p>只重建导航骨架和当前页：首页/分类已缓存的数据结构（homeVersion、catRoots 等）保留，
     * 重排后按原 tab 重新渲染，不重发网络请求。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int keepTab = tab;
        View keepHome = homePage;
        setContentView(buildRoot());
        homePage = keepHome;
        selectTab(keepTab);
    }

    /* ---------------- 骨架 ---------------- */

    /** 竖屏平板：导航移到顶部并横向排列；横屏电视/车机：保留左侧竖栏。 */
    private boolean isPortrait() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    private View buildRoot() {
        boolean portrait = isPortrait();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        navRail = new LinearLayout(this);
        navRail.setOrientation(portrait ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        navRail.setBackgroundColor(Ui.BG2);
        if (portrait) {
            navRail.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
            navRail.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            navRail.setPadding(Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18));
            navRail.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 184),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        buildNavRail(portrait);
        root.addView(navRail);

        content = new FrameLayout(this);
        content.setBackgroundColor(Ui.BG);
        content.setLayoutParams(portrait
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(content);
        return root;
    }

    private void buildNavRail(boolean portrait) {
        if (portrait) {
            // 竖屏：一行放品牌 + 四个 tab，省掉竖向说明文字（触摸屏有手势，不需要键位提示）。
            LinearLayout row = TvWidgets.hbox(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView brand = TvWidgets.bold(this, "拾音", 20f, Ui.AMBER);
            brand.setPadding(0, 0, Ui.dp(this, 6), 0);
            row.addView(brand);

            // 搜索入口：触摸和遥控都能到达
            TextView searchBtn = TvWidgets.text(this, "🔍", 18f, Ui.DIM);
            searchBtn.setGravity(Gravity.CENTER);
            searchBtn.setMinWidth(Ui.dp(this, 48));
            searchBtn.setMinHeight(Ui.dp(this, 48));
            searchBtn.setFocusable(true);
            searchBtn.setClickable(true);
            searchBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    TvSearchActivity.start(TvMainActivity.this);
                }
            });
            searchBtn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackground(hasFocus ? Ui.round(Ui.AMBER, 10, TvMainActivity.this)
                            : Ui.round(0x00000000, 10, TvMainActivity.this));
                    ((TextView) v).setTextColor(hasFocus ? 0xFF0B0E14 : Ui.DIM);
                }
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 48), Ui.dp(this, 48));
            slp.rightMargin = Ui.dp(this, 6);
            searchBtn.setLayoutParams(slp);
            row.addView(searchBtn);
            navRail.addView(row);

            for (int i = 0; i < 4; i++) {
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER);
                item.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
                item.setMinimumHeight(Ui.dp(this, 52));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = Ui.dp(this, 4);
                item.setLayoutParams(lp);

                TextView icon = TvWidgets.text(this, TAB_ICONS[i], 17f, Ui.DIM);
                icon.setGravity(Gravity.CENTER);
                item.addView(icon);

                TextView label = TvWidgets.text(this, TAB_NAMES[i], 13f, Ui.DIM);
                label.setGravity(Gravity.CENTER);
                item.addView(label);

                navIcons[i] = icon;
                navLabels[i] = label;
                navItems[i] = item;

                final int index = i;
                item.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectTab(index);
                    }
                });
                item.setFocusable(true);
                item.setClickable(true);
                item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        updateNavItem(index);
                        if (hasFocus) TvWidgets.reveal(v);
                    }
                });
                row.addView(item);
                updateNavItem(i);
            }
            return;
        }

        TextView brand = TvWidgets.bold(this, "拾音", 26f, Ui.AMBER);
        navRail.addView(brand);

        LinearLayout sub = TvWidgets.hbox(this);
        TextView tv = TvWidgets.text(this, "TV", 14f, Ui.TEXT);
        tv.setPadding(0, 0, 0, 0);
        tv.setBackground(Ui.round(Ui.BG3, 6, this));
        tv.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        sub.addView(tv);
        TextView mode = TvWidgets.text(this, "  电视 · 车机 · 平板", 14f, Ui.DIM2);
        sub.addView(mode);
        navRail.addView(sub);

        // 搜索入口：放在导航栏最上方，遥控按几下就到
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setMinimumHeight(Ui.dp(this, 52));
        searchRow.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.topMargin = Ui.dp(this, 10);
        searchRow.setLayoutParams(srlp);
        searchRow.setFocusable(true);
        searchRow.setClickable(true);
        searchRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TvSearchActivity.start(TvMainActivity.this);
            }
        });
        searchRow.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(hasFocus ? Ui.round(Ui.AMBER, 10, TvMainActivity.this)
                        : Ui.roundStroke(Ui.BG3, 0xFF2A3242, 10, 1, TvMainActivity.this));
                ((TextView) ((LinearLayout) v).getChildAt(1))
                        .setTextColor(hasFocus ? 0xFF0B0E14 : Ui.DIM);
            }
        });
        searchRow.setBackground(Ui.roundStroke(Ui.BG3, 0xFF2A3242, 10, 1, this));
        TextView sIcon = TvWidgets.text(this, "🔍", 16f, Ui.DIM);
        sIcon.setPadding(0, 0, Ui.dp(this, 8), 0);
        searchRow.addView(sIcon);
        TextView sLabel = TvWidgets.text(this, "搜索", 16f, Ui.DIM);
        searchRow.addView(sLabel);
        navRail.addView(searchRow);

        navRail.addView(TvWidgets.spacer(this, 14));

        for (int i = 0; i < 4; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 12), Ui.dp(this, 11));
            item.setMinimumHeight(Ui.dp(this, 58));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 4);
            item.setLayoutParams(lp);

            TextView icon = TvWidgets.text(this, TAB_ICONS[i], 20f, Ui.DIM);
            icon.setGravity(Gravity.CENTER);
            icon.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 36),
                    Ui.dp(this, 36)));
            item.addView(icon);

            TextView label = TvWidgets.text(this, TAB_NAMES[i], 17f, Ui.DIM);
            label.setPadding(Ui.dp(this, 12), 0, 0, 0);
            item.addView(label);

            navIcons[i] = icon;
            navLabels[i] = label;
            navItems[i] = item;

            final int index = i;
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index);
                }
            });
            item.setFocusable(true);
            item.setClickable(true);
            item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    updateNavItem(index);
                    if (hasFocus) TvWidgets.reveal(v);
                }
            });
            navRail.addView(item);
            updateNavItem(i);
        }

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        navRail.addView(gap);

        TextView hint = TvWidgets.text(this, "OK 确认\n← → 移动\nBACK 返回", 14f, Ui.DIM2);
        hint.setLineSpacing(Ui.dp(this, 3), 1f);
        hint.setPadding(Ui.dp(this, 8), 0, 0, 0);
        navRail.addView(hint);
    }

    private void updateNavItem(int index) {
        View item = navItems[index];
        if (item == null) return;
        boolean selected = index == tab;
        boolean focused = item.hasFocus();
        int fill = focused ? TvWidgets.FOCUS_FILL : (selected ? 0xFF202839 : 0x00000000);
        GradientDrawable bg = Ui.round(fill, 12, this);
        if (focused) bg.setStroke(Math.max(2, Ui.dp(this, 2f)), Ui.AMBER);
        TvWidgets.setBg(item, bg);
        int labelColor = selected ? Ui.AMBER : (focused ? Ui.TEXT : Ui.DIM);
        navLabels[index].setTextColor(labelColor);
        navIcons[index].setTextColor(selected ? Ui.AMBER : (focused ? Ui.TEXT : Ui.DIM2));
    }

    private void updateNav() {
        for (int i = 0; i < 4; i++) updateNavItem(i);
    }

    private void selectTab(int index) {
        tab = index;
        updateNav();
        if (index == TAB_HOME) {
            showHome();
        } else if (index == TAB_CATS) {
            if (openRoot != null) showCatBooks(openRoot, catChipIndex);
            else showCats();
        } else if (index == TAB_SHELF) {
            showShelf();
        } else {
            showMine();
        }
    }

    private void setPage(View page) {
        content.removeAllViews();
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void focusContentFirst() {
        content.post(new Runnable() {
            @Override
            public void run() {
                establishFocus();
            }
        });
    }

    /**
     * 把焦点放到内容区第一个可点控件。
     *
     * <p>用 requestFocusFromTouch() 而不是 requestFocus()：带触摸屏的车机 / 模拟器
     * 处于 touch mode，普通 focusable 视图此时 requestFocus 一定返回 false，
     * 遥控器第一次按键会没有落点。TV 真机不在 touch mode，两者等价。
     */
    private boolean establishFocus() {
        // 只在「整个窗口没有任何焦点」时接管。否则会把用户刚移到左栏的焦点抢回内容区，
        // 结果就是方向键切栏后按 OK 没反应（真实踩过的坑）。
        if (getCurrentFocus() != null) return true;
        View first = firstFocusable(content);
        return first != null && first.requestFocusFromTouch();
    }

    /** 明确要求把焦点送进内容区（用户按 RIGHT 从导航栏出来时用）。 */
    private void focusContentForced() {
        content.post(new Runnable() {
            @Override
            public void run() {
                View first = firstFocusable(content);
                boolean ok = first != null && first.requestFocusFromTouch();
                android.util.Log.i("TvNav", "forced focus first=" + first + " ok=" + ok
                        + " current=" + getCurrentFocus());
            }
        });
    }

    /**
     * 深度优先找第一个可聚焦且可见的子孙（FrameLayout.requestFocus 不会往下走）。
     * ScrollView 自己 focusable=true 但不可点，跳过它继续找里面的卡片。
     */
    private static View firstFocusable(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE) return null;
        if (root.isFocusable() && (!(root instanceof ViewGroup) || root.isClickable())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstFocusable(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void focusNav() {
        View target = navItems[tab];
        if (target != null) target.requestFocus();
    }

    private static boolean isInside(View ancestor, View child) {
        View cur = child;
        while (cur != null) {
            if (cur == ancestor) return true;
            android.view.ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /* ---------------- 页头 ---------------- */

    private View pageHeader(String title, String subtitle) {
        LinearLayout box = TvWidgets.vbox(this);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 6));
        box.addView(TvWidgets.bold(this, title, 26f, Ui.TEXT));
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = TvWidgets.text(this, subtitle, 14f, Ui.DIM2);
            s.setPadding(0, Ui.dp(this, 4), 0, 0);
            box.addView(s);
        }
        return box;
    }

    /* ---------------- 首页 ---------------- */

    private static String sourceHomeVersion(Source source) {
        String base = source instanceof JsonSource
                ? ((JsonSource) source).homeCacheVersion()
                : source.getClass().getName() + ":" + source.name();
        // 青少年模式的内容集与普通模式不同，缓存必须隔离，否则会互相污染。
        return KidMode.enabled() ? base + "+kid" : base;
    }

    private static String homeVersion(List<Source> sources) {
        StringBuilder key = new StringBuilder();
        for (Source source : sources) {
            key.append(source.id().length()).append(':').append(source.id())
                    .append(':').append(sourceHomeVersion(source)).append('\n');
        }
        return key.toString();
    }

    private static void appendHome(List<Sec> out, Source source, List<JsonSource.HomeSection> sections) {
        for (JsonSource.HomeSection cached : sections) {
            Sec sec = new Sec();
            sec.label = cached.label.length() == 0 ? source.name()
                    : source.name() + " · " + cached.label;
            sec.books = cached.books;
            out.add(sec);
        }
    }

    private void showHome() {
        final List<Source> sources = Sources.all();
        final String version = homeVersion(sources);
        if (homePage == null || !version.equals(homeVersion)) {
            homeVersion = version;
            homeBody = TvWidgets.vbox(this);
            homeScroll = new ScrollView(this);
            homeScroll.setFillViewport(true);
            homeScroll.setVerticalScrollBarEnabled(false);
            homeScroll.setFocusable(false);
            homeScroll.addView(homeBody, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout page = TvWidgets.vbox(this);
            page.addView(homeScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            homePage = page;

            List<Sec> cached = new ArrayList<>();
            for (Source source : sources) {
                Prefs.CachedHome data = Prefs.cachedHome(source.id(), sourceHomeVersion(source));
                if (data != null) appendHome(cached, source, data.sections);
            }
            if (!cached.isEmpty() || sources.isEmpty()) renderHome(cached, sources.isEmpty());
        }
        if (homePage.getParent() != content) setPage(homePage);
        if (sources.isEmpty()) {
            focusContentFirst();
            return;
        }
        if (homeLoading) {
            if (homeBody.getChildCount() == 0) {
                homeBody.addView(TvWidgets.loading(this, "正在加载推荐…"));
            }
            focusContentFirst();
            return;
        }
        boolean due = false;
        long now = System.currentTimeMillis();
        for (Source source : sources) {
            if (Prefs.homeRefreshDue(source.id(), sourceHomeVersion(source), now,
                    Subscriptions.CACHE_MAX_AGE_MS)) {
                due = true;
                break;
            }
        }
        if (!due) {
            if (homeBody.getChildCount() == 0) renderHome(new ArrayList<Sec>(), false);
            focusContentFirst();
            return;
        }
        homeLoading = true;
        final LinearLayout target = homeBody;
        final ScrollView scroll = homeScroll;
        if (target.getChildCount() == 0) {
            target.addView(TvWidgets.loading(this, "正在加载推荐…"));
        }
        Bg.run(new Callable<List<Sec>>() {
            @Override
            public List<Sec> call() {
                return loadHome(sources);
            }
        }, new Bg.Cb<List<Sec>>() {
            @Override
            public void ok(List<Sec> sections) {
                homeLoading = false;
                if (isFinishing()) return;
                if (target != homeBody || !version.equals(homeVersion(Sources.all()))) {
                    if (tab == TAB_HOME) showHome();
                    return;
                }
                int position = scroll.getScrollY();
                renderHome(sections, false);
                final int y = position;
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.scrollTo(0, y);
                    }
                });
            }

            @Override
            public void fail(Exception e) {
                homeLoading = false;
                if (isFinishing() || target != homeBody) return;
                List<Sec> cached = new ArrayList<>();
                for (Source source : sources) {
                    Prefs.CachedHome data = Prefs.cachedHome(source.id(), sourceHomeVersion(source));
                    if (data != null) appendHome(cached, source, data.sections);
                }
                renderHome(cached, false);
            }
        });
    }

    private static synchronized List<Sec> loadHome(List<Source> sources) {
        List<Sec> out = new ArrayList<>();
        boolean kid = KidMode.enabled();
        for (Source source : sources) {
            String version = sourceHomeVersion(source);
            Prefs.CachedHome cached = Prefs.cachedHome(source.id(), version);
            List<JsonSource.HomeSection> sections = new ArrayList<>();
            long now = System.currentTimeMillis();
            if (Prefs.homeRefreshDue(source.id(), version, now, Subscriptions.CACHE_MAX_AGE_MS)) {
                try {
                    if (kid) {
                        // 青少年模式：通用推荐没有白名单约束，改从过滤后的儿童分类取第一页。
                        if (source.hasCats()) {
                            List<Cat> allowed = KidMode.filterCategories(source.id(), source.cats());
                            for (Cat cat : allowed) {
                                List<Book> books = source.page(cat, 1).books;
                                if (books == null || books.isEmpty()) continue;
                                List<Book> safe = new ArrayList<>();
                                for (Book book : books) {
                                    if (KidMode.allowsBook(book)) safe.add(book);
                                }
                                if (!safe.isEmpty()) {
                                    sections.add(new JsonSource.HomeSection(cat.name, safe));
                                }
                            }
                        }
                    } else {
                        if (source instanceof JsonSource && ((JsonSource) source).hasHome()) {
                            sections.addAll(((JsonSource) source).home());
                        }
                        if (sections.isEmpty() && source.hasCats()) {
                            List<Cat> cats = source.cats();
                            if (!cats.isEmpty()) {
                                List<Book> books = source.page(cats.get(0), 1).books;
                                if (books != null && !books.isEmpty()) {
                                    sections.add(new JsonSource.HomeSection("", books));
                                }
                            }
                        }
                    }
                    Prefs.cacheHome(source.id(), version, sections, System.currentTimeMillis());
                } catch (Exception ignored) {
                    // 单个源失败保留它的旧推荐，不影响其它板块。
                } finally {
                    Prefs.markHomeAttempt(source.id(), version, System.currentTimeMillis());
                }
            }
            if (sections.isEmpty() && cached != null) sections = cached.sections;
            // 书架/历史等旁路的兜底：缓存里也可能混入非白名单书。
            if (kid) sections = kidOnly(sections);
            appendHome(out, source, sections);
        }
        return out;
    }

    /** 青少年模式下逐本过 allowsBook；非儿童源整段丢弃。 */
    private static List<JsonSource.HomeSection> kidOnly(List<JsonSource.HomeSection> sections) {
        List<JsonSource.HomeSection> out = new ArrayList<>();
        if (sections == null) return out;
        for (JsonSource.HomeSection section : sections) {
            List<Book> safe = new ArrayList<>();
            for (Book book : section.books) {
                if (KidMode.allowsBook(book)) safe.add(book);
            }
            if (!safe.isEmpty()) out.add(new JsonSource.HomeSection(section.label, safe));
        }
        return out;
    }

    private void renderHome(List<Sec> sections, boolean noSources) {
        homeBody.removeAllViews();
        if (KidMode.enabled()) {
            // 常驻提示条：让家长一眼看到当前处于受限模式。
            LinearLayout banner = TvWidgets.hbox(this);
            banner.setBackground(Ui.round(Ui.BG3, 10, this));
            banner.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = Ui.dp(this, 24);
            blp.rightMargin = Ui.dp(this, 24);
            blp.topMargin = Ui.dp(this, 8);
            banner.setLayoutParams(blp);
            banner.addView(TvWidgets.bold(this, "青少年模式", 14f, Ui.TEAL));
            TextView note = TvWidgets.text(this, "  · 仅显示儿童内容", 14f, Ui.DIM);
            banner.addView(note);
            homeBody.addView(banner);
        }
        homeBody.addView(pageHeader("推荐", "按内容源分板块 · 滑动翻书卡，点按或按 OK 打开"));
        focusContentFirst();
        if (noSources) {
            homeBody.addView(TvWidgets.empty(this, "还没有内容源\n到「我的」添加在线订阅后才会有内容"));
            LinearLayout box = TvWidgets.hbox(this);
            box.setGravity(Gravity.CENTER);
            box.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 32));
            box.addView(TvWidgets.button(this, "去添加订阅", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(TAB_MINE);
                }
            }));
            homeBody.addView(box);
            return;
        }
        if (!sections.isEmpty()) {
            for (Sec section : sections) addHomeSection(section);
            homeBody.addView(TvWidgets.spacer(this, 28));
            return;
        }
        if (!homeLoading) {
            homeBody.addView(TvWidgets.empty(this, "订阅源暂时没有返回内容\n可以到「我的」刷新订阅，或稍后重试"));
            LinearLayout box = TvWidgets.hbox(this);
            box.setGravity(Gravity.CENTER);
            box.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 32));
            box.addView(TvWidgets.button(this, "重试", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Prefs.expireHome();
                    homePage = null;
                    showHome();
                }
            }));
            homeBody.addView(box);
        }
        focusContentFirst();
    }

    private void addHomeSection(Sec section) {
        homeBody.addView(TvWidgets.sectionTitle(this, section.label, null, null));
        LinearLayout row = TvWidgets.hbox(this);
        for (int i = 0; i < section.books.size(); i++) {
            final Book book = section.books.get(i);
            row.addView(TvWidgets.bookCard(this, book, 128, 168, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Nav.openDetail(TvMainActivity.this, book);
                }
            }));
        }
        homeBody.addView(TvWidgets.hscroll(this, row));
    }

    /* ---------------- 分类 ---------------- */

    private List<Cat> collectCachedCats() {
        List<Cat> roots = new ArrayList<>();
        for (Source source : Sources.all()) {
            if (!source.hasCats()) continue;
            if (source instanceof JsonSource) {
                roots.addAll(((JsonSource) source).cachedCategories());
            }
        }
        return roots;
    }

    private void showCats() {
        if (Sources.isEmpty()) {
            LinearLayout body = TvWidgets.vbox(this);
            setPage(TvWidgets.verticalScroll(this, body));
            body.addView(pageHeader("分类", ""));
            body.addView(TvWidgets.empty(this, "还没有内容源\n到「我的」添加在线订阅"));
            LinearLayout box = TvWidgets.hbox(this);
            box.setGravity(Gravity.CENTER);
            box.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 32));
            box.addView(TvWidgets.button(this, "去添加订阅", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(TAB_MINE);
                }
            }));
            body.addView(box);
            focusContentFirst();
            return;
        }
        List<Cat> cached = collectCachedCats();
        renderCatsRoot(cached, cached.isEmpty());
        loadCatsAsync();
    }

    private void renderCatsRoot(List<Cat> roots, boolean showLoading) {
        catRoots = roots;
        LinearLayout body = TvWidgets.vbox(this);
        catsRootBody = body;
        setPage(TvWidgets.verticalScroll(this, body));
        body.addView(pageHeader("分类", "选中根分类 → 子分类 → 书目"));
        if (roots.isEmpty()) {
            if (showLoading) body.addView(TvWidgets.loading(this, "正在加载分类…"));
            else {
                body.addView(TvWidgets.empty(this, "订阅还没有提供分类\n可以到「我的」手动刷新订阅"));
                LinearLayout box = TvWidgets.hbox(this);
                box.setGravity(Gravity.CENTER);
                box.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 32));
                box.addView(TvWidgets.button(this, "重新加载", true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadCatsAsync();
                    }
                }));
                body.addView(box);
            }
        } else {
            for (Cat root : roots) body.addView(catRootRow(root));
            body.addView(TvWidgets.spacer(this, 24));
        }
        focusContentFirst();
    }

    private View catRootRow(final Cat root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 22), Ui.dp(this, 12));
        row.setMinimumHeight(Ui.dp(this, 76));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.util.Log.i("TvNav", "category root clicked: " + root.name);
                showCatBooks(root, 0);
            }
        });
        TvWidgets.focusable(row, Ui.BG, TvWidgets.FOCUS_FILL, 14);

        TextView icon = TvWidgets.text(this, root.icon.length() > 0 ? root.icon
                : (root.name.length() > 0 ? root.name.substring(0, 1) : "·"), 20f, Ui.AMBER);
        icon.setGravity(Gravity.CENTER);
        icon.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        TvWidgets.setBg(icon, Ui.round(0xFF1B2130, 12, this));
        row.addView(icon);

        LinearLayout col = TvWidgets.vbox(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(this, 16);
        col.setLayoutParams(clp);
        TextView name = TvWidgets.text(this, root.name, 18f, Ui.TEXT);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        col.addView(name);
        StringBuilder sub = new StringBuilder(TvWidgets.srcName(root.source));
        if (root.desc.length() > 0) sub.append(" · ").append(root.desc);
        if (root.children.size() > 0) sub.append(" · ").append(root.children.size()).append(" 个子分类");
        TextView d = TvWidgets.text(this, sub.toString(), 14f, Ui.DIM2);
        d.setMaxLines(1);
        d.setEllipsize(TextUtils.TruncateAt.END);
        d.setPadding(0, Ui.dp(this, 3), 0, 0);
        col.addView(d);
        row.addView(col);
        row.addView(TvWidgets.text(this, "›", 20f, Ui.DIM2));
        return row;
    }

    private void loadCatsAsync() {
        if (catsLoading) return;
        catsLoading = true;
        Bg.run(new Callable<List<Cat>>() {
            @Override
            public List<Cat> call() {
                List<Cat> all = new ArrayList<>();
                for (Source source : Sources.all()) {
                    if (!source.hasCats()) continue;
                    try {
                        List<Cat> roots = source.cats();
                        // 青少年模式：只保留儿童白名单分类（关闭时原样深拷贝返回）。
                        all.addAll(KidMode.filterCategories(source.id(), roots));
                    } catch (Exception ignored) {
                        // 单个源失败不影响其它源。
                    }
                }
                return all;
            }
        }, new Bg.Cb<List<Cat>>() {
            @Override
            public void ok(List<Cat> roots) {
                catsLoading = false;
                if (isFinishing() || tab != TAB_CATS || openRoot != null) return;
                boolean unchanged = roots.size() == catRoots.size();
                if (unchanged) {
                    for (int i = 0; i < roots.size(); i++) {
                        if (!roots.get(i).id.equals(catRoots.get(i).id)
                                || !roots.get(i).source.equals(catRoots.get(i).source)) {
                            unchanged = false;
                            break;
                        }
                    }
                }
                if (unchanged && !roots.isEmpty()) return;
                renderCatsRoot(roots, false);
            }

            @Override
            public void fail(Exception e) {
                catsLoading = false;
                if (isFinishing() || tab != TAB_CATS || openRoot != null) return;
                if (catRoots.isEmpty()) {
                    LinearLayout body = TvWidgets.vbox(TvMainActivity.this);
                    body.addView(TvWidgets.empty(TvMainActivity.this,
                            "分类加载失败：" + messageOf(e)));
                    LinearLayout box = TvWidgets.hbox(TvMainActivity.this);
                    box.setGravity(Gravity.CENTER);
                    box.setPadding(Ui.dp(TvMainActivity.this, 24), Ui.dp(TvMainActivity.this, 8),
                            Ui.dp(TvMainActivity.this, 24), Ui.dp(TvMainActivity.this, 32));
                    box.addView(TvWidgets.button(TvMainActivity.this, "重试", true,
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    loadCatsAsync();
                                }
                            }));
                    body.addView(box);
                    catsRootBody = body;
                    setPage(TvWidgets.verticalScroll(TvMainActivity.this, body));
                    focusContentFirst();
                }
            }
        });
    }

    private void showCatBooks(final Cat root, int chipIndex) {
        android.util.Log.i("TvNav", "showCatBooks " + root.name + " idx=" + chipIndex);
        openRoot = root;
        final List<Cat> chips = new ArrayList<>();
        if (root.children.isEmpty()) chips.add(root);
        else chips.addAll(root.children);
        if (chipIndex < 0 || chipIndex >= chips.size()) chipIndex = 0;
        catChipIndex = chipIndex;
        catLeaf = chips.get(chipIndex);

        LinearLayout page = TvWidgets.vbox(this);

        LinearLayout head = TvWidgets.hbox(this);
        head.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 6));
        TextView back = TvWidgets.text(this, "‹ 返回分类", 16f, Ui.TEXT);
        back.setGravity(Gravity.CENTER);
        back.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRoot = null;
                showCats();
            }
        });
        TvWidgets.focusable(back, Ui.BG3, TvWidgets.FOCUS_FILL, 20);
        head.addView(back);

        LinearLayout titleBox = TvWidgets.vbox(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 16);
        titleBox.setLayoutParams(tlp);
        titleBox.addView(TvWidgets.bold(this, root.name, 24f, Ui.TEXT));
        TextView src = TvWidgets.text(this, TvWidgets.srcName(root.source), 14f, Ui.DIM2);
        titleBox.addView(src);
        head.addView(titleBox);
        page.addView(head);

        LinearLayout chipBox = TvWidgets.hbox(this);
        for (int i = 0; i < chips.size(); i++) {
            final int idx = i;
            Cat chipCat = chips.get(i);
            TextView chip = TvWidgets.chip(this, chipCat.name, i == chipIndex,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showCatBooks(root, idx);
                        }
                    });
            chipBox.addView(chip);
        }
        page.addView(TvWidgets.hscroll(this, chipBox));

        catHint = TvWidgets.text(this, "", 14f, Ui.DIM2);
        catHint.setPadding(Ui.dp(this, 24), Ui.dp(this, 6), Ui.dp(this, 24), Ui.dp(this, 6));
        page.addView(catHint);

        gridBody = TvWidgets.vbox(this);
        ScrollView gridScroll = new ScrollView(this);
        gridScroll.setFillViewport(true);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setFocusable(false);
        gridScroll.addView(gridBody, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(gridScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setPage(page);
        focusContentFirst();

        catBooks.clear();
        catPage = 0;
        catTotalPage = 1;
        catPageLoading = false;
        catGeneration++;
        loadCatPage(1, false);
    }

    private void loadCatPage(final int pn, final boolean append) {
        if (catPageLoading) return;
        final Cat leaf = catLeaf;
        if (leaf == null) return;
        catPageLoading = true;
        final int generation = ++catGeneration;
        if (!append) {
            catBooks.clear();
            gridBody.removeAllViews();
            gridBody.addView(TvWidgets.loading(this, "正在加载书目…"));
            if (catHint != null) catHint.setText("");
        } else if (catHint != null) {
            catHint.setText("正在加载第 " + pn + " 页…");
        }
        Bg.run(new Callable<Source.Page>() {
            @Override
            public Source.Page call() throws Exception {
                Source source = Sources.byId(leaf.source);
                if (source == null) throw new java.io.IOException("内容源未安装，请检查订阅");
                return source.page(leaf, pn);
            }
        }, new Bg.Cb<Source.Page>() {
            @Override
            public void ok(Source.Page page) {
                catPageLoading = false;
                android.util.Log.i("TvNav", "cat page ok pn=" + pn + " books="
                        + (page.books == null ? -1 : page.books.size()));
                if (isFinishing() || generation != catGeneration || openRoot == null) return;
                if (pn == 1) catBooks.clear();
                if (page.books != null) catBooks.addAll(page.books);
                catPage = pn;
                catTotalPage = Math.max(page.totalPage, pn);
                if (catHint != null) catHint.setText("");
                renderGrid();
            }

            @Override
            public void fail(Exception e) {
                catPageLoading = false;
                android.util.Log.i("TvNav", "cat page fail pn=" + pn + " " + e);
                if (isFinishing() || generation != catGeneration || openRoot == null) return;
                if (catHint != null) catHint.setText("加载失败：" + messageOf(e));
                if (pn == 1) {
                    gridBody.removeAllViews();
                    gridBody.addView(TvWidgets.empty(TvMainActivity.this,
                            "书目加载失败\n" + messageOf(e)));
                    LinearLayout box = TvWidgets.hbox(TvMainActivity.this);
                    box.setGravity(Gravity.CENTER);
                    box.setPadding(Ui.dp(TvMainActivity.this, 24), Ui.dp(TvMainActivity.this, 8),
                            Ui.dp(TvMainActivity.this, 24), Ui.dp(TvMainActivity.this, 32));
                    box.addView(TvWidgets.button(TvMainActivity.this, "重试", true,
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    loadCatPage(1, false);
                                }
                            }));
                    gridBody.addView(box);
                    focusContentFirst();
                }
            }
        });
    }

    private void renderGrid() {
        gridBody.removeAllViews();
        if (catBooks.isEmpty()) {
            gridBody.addView(TvWidgets.empty(this, "这个分类暂时没内容"));
            return;
        }
        for (int start = 0; start < catBooks.size(); start += GRID_COLUMNS) {
            LinearLayout row = TvWidgets.hbox(this);
            row.setPadding(Ui.dp(this, 18), Ui.dp(this, 2), Ui.dp(this, 18), Ui.dp(this, 2));
            int count = 0;
            for (int i = start; i < Math.min(start + GRID_COLUMNS, catBooks.size()); i++) {
                final Book book = catBooks.get(i);
                View card = TvWidgets.bookCard(this, book, 128, 168, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Nav.openDetail(TvMainActivity.this, book);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = Ui.dp(this, 6);
                card.setLayoutParams(lp);
                row.addView(card);
                count++;
            }
            while (count++ < GRID_COLUMNS) {
                View empty = new View(this);
                empty.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
                row.addView(empty);
            }
            gridBody.addView(row);
        }
        if (catPage < catTotalPage) {
            LinearLayout box = TvWidgets.hbox(this);
            box.setGravity(Gravity.CENTER);
            box.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 24));
            box.addView(TvWidgets.button(this, "加载更多（" + catPage + "/" + catTotalPage + "）",
                    false, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            loadCatPage(catPage + 1, true);
                        }
                    }));
            gridBody.addView(box);
        } else if (catPage > 1) {
            gridBody.addView(TvWidgets.hint(this, "没有更多了"));
        }
        gridBody.addView(TvWidgets.spacer(this, 24));
    }

    /* ---------------- 书架 ---------------- */

    private void showShelf() {
        LinearLayout body = TvWidgets.vbox(this);
        setPage(TvWidgets.verticalScroll(this, body));
        body.addView(pageHeader("书架", "点按或按 OK 打开 · 详情页可加入书架"));

        List<Book> recent = Prefs.recent();
        List<Book> shelf = Prefs.shelf();

        if (!recent.isEmpty()) {
            body.addView(TvWidgets.sectionTitle(this, "继续收听", null, null));
            for (Book book : recent) body.addView(shelfRow(book));
        }

        body.addView(TvWidgets.sectionTitle(this, "我的书架",
                shelf.isEmpty() ? null : shelf.size() + " 本", null));
        if (shelf.isEmpty()) {
            body.addView(TvWidgets.empty(this, "书架还是空的\n在详情页按「加入书架」后会出现在这里"));
        } else {
            for (Book book : shelf) body.addView(shelfRow(book));
        }
        body.addView(TvWidgets.spacer(this, 28));
        focusContentFirst();
    }

    private View shelfRow(final Book book) {
        return TvWidgets.bookRow(this, book, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Nav.openDetail(TvMainActivity.this, book);
            }
        });
    }

    /* ---------------- 我的 ---------------- */

    private void showMine() {
        LinearLayout body = TvWidgets.vbox(this);
        setPage(TvWidgets.verticalScroll(this, body));
        body.addView(pageHeader("我的", "订阅、播放与版本"));

        body.addView(TvWidgets.sectionTitle(this, "青少年模式", null, null));
        body.addView(TvWidgets.actionRow(this,
                KidMode.enabled() ? "青少年模式已开启" : "青少年模式",
                KidMode.enabled()
                        ? "仅显示儿童内容 · 关闭需要密码"
                        : (KidMode.hasPassword() ? "开启需要密码" : "设置密码后开启"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        TvKidGate.open(TvMainActivity.this, new Runnable() {
                            @Override
                            public void run() {
                                // 模式变了：首页缓存 key 也变了，清掉已渲染视图后按当前 tab 重建。
                                homePage = null;
                                homeVersion = "";
                                selectTab(tab);
                            }
                        });
                    }
                }));

        body.addView(TvWidgets.sectionTitle(this, "内容源", null, null));
        List<Source> installed = Sources.all();
        if (installed.isEmpty()) {
            body.addView(TvWidgets.hint(this, "还没有内容源。添加在线订阅后，首页、分类和书架才会有内容。\n"
                    + "本应用不内置任何源，内容由你添加的订阅提供。"));
        } else {
            for (Source source : installed) {
                body.addView(TvWidgets.infoRow(this, source.name(),
                        (source.desc().length() > 0 ? source.desc() + " · " : "") + "订阅源 " + source.id()));
            }
        }
        body.addView(TvWidgets.actionRow(this, "添加订阅", "粘贴 sources.json 地址",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        promptAddSubscription();
                    }
                }));

        List<String> subs = Subscriptions.urls();
        if (!subs.isEmpty()) {
            body.addView(TvWidgets.sectionTitle(this, "已添加的订阅", null, null));
            for (final String url : subs) {
                body.addView(TvWidgets.actionRow(this, url, Subscriptions.cacheAge(url),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                showSubscriptionActions(url);
                            }
                        }));
            }
            body.addView(TvWidgets.actionRow(this, "刷新全部订阅",
                    "每 " + (Subscriptions.CACHE_MAX_AGE_MS / 3600000L) + " 小时自动更新",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            refreshAllSubscriptions();
                        }
                    }));
        }

        body.addView(TvWidgets.sectionTitle(this, "播放", null, null));
        body.addView(TvWidgets.actionRow(this, "音质",
                "320".equals(Prefs.quality()) ? "320 kbps" : "128 kbps",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.setQuality("320".equals(Prefs.quality()) ? "128" : "320");
                        Ui.toast(TvMainActivity.this, "已切换音质，下一集生效");
                        showMine();
                    }
                }));
        body.addView(TvWidgets.actionRow(this, "系统通知设置", "后台播放通知 / 权限",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openNotificationSettings();
                    }
                }));

        body.addView(TvWidgets.sectionTitle(this, "关于", null, null));
        body.addView(TvWidgets.infoRow(this, "拾音 TV", "版本 " + packageVersion() + " · 电视 / 车机 / 平板"));
        body.addView(TvWidgets.actionRow(this, "检查更新", "TV 版随构建分发",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        checkUpdate();
                    }
                }));
        body.addView(TvWidgets.hint(this,
                "应用本身不内置内容源，内容由你自行添加的订阅提供。\n仅供学习研究使用，请确保你有权访问相应内容。"));
        body.addView(TvWidgets.spacer(this, 28));
        focusContentFirst();
    }

    private void promptAddSubscription() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://…/sources.json");
        input.setHintTextColor(Ui.DIM2);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(16f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        // 系统默认 EditText 背景在深色主题下是浅色，配 TEXT 色会看不清，自己给深色底。
        TvWidgets.setBg(input, Ui.roundStroke(0xFF0F1420, 0xFF2A3242, 10, 1, this));
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));

        LinearLayout box = TvWidgets.vbox(this);
        int side = Ui.dp(this, 24);
        box.setPadding(side, Ui.dp(this, 10), side, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView tip = TvWidgets.text(this, "没有键盘时：OK 选「填入示例」自动填地址", 14f, Ui.DIM);
        tip.setPadding(0, Ui.dp(this, 10), Ui.dp(this, 4), 0);
        box.addView(tip);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加订阅源")
                .setView(box)
                .setPositiveButton("添加", null)
                .setNeutralButton("填入示例", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                input.setText(DEFAULT_SUBSCRIPTION);
                                input.setSelection(input.getText().length());
                            }
                        });
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                String url = input.getText().toString().trim();
                                if (url.length() == 0) {
                                    Ui.toast(TvMainActivity.this, "先填订阅地址，或点「填入示例」");
                                    return;
                                }
                                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                    Ui.toast(TvMainActivity.this, "地址必须以 http:// 或 https:// 开头");
                                    return;
                                }
                                dialog.dismiss();
                                installSubscription(url);
                            }
                        });
                // 默认焦点放在「填入示例」：车机没键盘时一次 OK 就能填好地址。
                final TextView fill = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                fill.post(new Runnable() {
                    @Override
                    public void run() {
                        fill.requestFocus();
                    }
                });
            }
        });
        dialog.show();
    }

    private void installSubscription(final String url) {
        Ui.toast(this, "正在下载订阅…");
        Bg.run(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                Subscriptions.install(url);
                return Sources.all().size();
            }
        }, new Bg.Cb<Integer>() {
            @Override
            public void ok(Integer count) {
                if (isFinishing()) return;
                Prefs.expireHome();
                homePage = null;
                Ui.toast(TvMainActivity.this, "订阅已添加，共 " + count + " 个源");
                showMine();
            }

            @Override
            public void fail(Exception e) {
                if (isFinishing()) return;
                new AlertDialog.Builder(TvMainActivity.this)
                        .setTitle("订阅添加失败")
                        .setMessage(messageOf(e))
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    private void showSubscriptionActions(final String url) {
        new AlertDialog.Builder(this)
                .setTitle(url)
                .setItems(new String[]{"立即刷新", "删除这个订阅"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) refreshSubscription(url);
                        else confirmRemoveSubscription(url);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshSubscription(final String url) {
        Ui.toast(this, "正在刷新…");
        Bg.run(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                Subscriptions.install(url);
                return Sources.all().size();
            }
        }, new Bg.Cb<Integer>() {
            @Override
            public void ok(Integer count) {
                if (isFinishing()) return;
                Prefs.expireHome();
                homePage = null;
                Ui.toast(TvMainActivity.this, "已刷新，共 " + count + " 个源");
                showMine();
            }

            @Override
            public void fail(Exception e) {
                if (isFinishing()) return;
                new AlertDialog.Builder(TvMainActivity.this)
                        .setTitle("刷新失败")
                        .setMessage(messageOf(e))
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    private void refreshAllSubscriptions() {
        if (Subscriptions.urls().isEmpty()) {
            Ui.toast(this, "还没有订阅");
            return;
        }
        Ui.toast(this, "正在刷新订阅…");
        Bg.run(new Callable<List<String>>() {
            @Override
            public List<String> call() {
                return Subscriptions.refreshAll();
            }
        }, new Bg.Cb<List<String>>() {
            @Override
            public void ok(List<String> failed) {
                if (isFinishing()) return;
                Prefs.expireHome();
                homePage = null;
                if (failed.isEmpty()) {
                    Ui.toast(TvMainActivity.this, "订阅已全部更新");
                } else {
                    new AlertDialog.Builder(TvMainActivity.this)
                            .setTitle("部分订阅刷新失败")
                            .setMessage(TextUtils.join("\n\n", failed))
                            .setPositiveButton("知道了", null)
                            .show();
                }
                showMine();
            }

            @Override
            public void fail(Exception e) {
                if (isFinishing()) return;
                Ui.toast(TvMainActivity.this, messageOf(e));
            }
        });
    }

    private void confirmRemoveSubscription(final String url) {
        new AlertDialog.Builder(this)
                .setTitle("删除这个订阅？")
                .setMessage(url + "\n\n该订阅提供的内容源会一起移除，书架里的书将无法继续播放。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Subscriptions.remove(url);
                        Prefs.expireHome();
                        homePage = null;
                        Ui.toast(TvMainActivity.this, "已删除");
                        showMine();
                    }
                })
                .show();
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            intent.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
            startActivity(intent);
            return;
        } catch (ActivityNotFoundException ignored) {
            // API26 以下没有这个页面，退到应用详情。
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS",
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable failure) {
            Ui.toast(this, "打不开系统设置");
        }
    }

    private void checkUpdate() {
        new AlertDialog.Builder(this)
                .setTitle("版本信息")
                .setMessage("拾音 TV " + packageVersion() + "\n"
                        + "TV 版按构建分发，暂不联网检查更新。\n"
                        + "订阅内容会每 24 小时自动刷新。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private String packageVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    private static String messageOf(Exception e) {
        if (e == null) return "未知错误";
        String msg = e.getMessage();
        return msg == null || msg.length() == 0 ? e.getClass().getSimpleName() : msg;
    }

    /* ---------------- 遥控器按键 ---------------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isMediaKey(keyCode)) {
            if (!Nav.mediaKey(this, keyCode)) {
                Ui.toast(this, "还没有开始播放：先打开一本书");
            }
            return true;
        }
        View focus = getCurrentFocus();
        if (focus == null && isDpadKey(keyCode)) {
            // 冷启动 / 从别的窗口回来时可能没有焦点：第一次方向键先把焦点落到内容区。
            if (establishFocus()) return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (focus != null && isInside(content, focus)) {
                View left = focus.focusSearch(View.FOCUS_LEFT);
                if (left == null || !isInside(content, left)) {
                    focusNav();
                    return true;
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (focus != null && isInside(navRail, focus)) {
                android.util.Log.i("TvNav", "RIGHT from nav, forcing content focus");
                focusContentForced();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (tab == TAB_CATS && openRoot != null) {
                openRoot = null;
                showCats();
                return true;
            }
            if (tab != TAB_HOME) {
                selectTab(TAB_HOME);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private static boolean isDpadKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
    }

    private static boolean isMediaKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK;
    }
}

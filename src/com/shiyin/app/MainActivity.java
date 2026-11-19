package com.shiyin.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.shiyin.app.model.Book;
import com.shiyin.app.model.Cat;
import com.shiyin.app.net.AppUpdate;
import com.shiyin.app.play.Player;
import com.shiyin.app.source.Source;
import com.shiyin.app.source.Sources;
import com.shiyin.app.source.Subscriptions;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.ui.BookAdapter;
import com.shiyin.app.ui.DetailActivity;
import com.shiyin.app.ui.PlayerActivity;
import com.shiyin.app.ui.Widgets;
import com.shiyin.app.util.Bg;
import com.shiyin.app.util.ImgView;
import com.shiyin.app.util.Ui;

import java.util.ArrayList;
import java.util.List;

/** 主界面：首页 / 分类 / 书架 / 我的 四个 tab + 底部迷你播放条。 */
public class MainActivity extends Activity implements Player.Listener {

    private FrameLayout content;
    private LinearLayout mini;
    private ImgView miniCover;
    private TextView miniTitle;
    private TextView miniSub;
    private TextView miniBtn;
    private final TextView[] tabs = new TextView[4];

    private int tab = 0;
    private volatile int updateRequestGeneration;
    private volatile boolean checkingUpdate;

    private static final class Section {
        String label = "";
        List<Book> books = new ArrayList<>();
    }

    private static final class CategoryState {
        int selectedIndex;
        int loadedPage;
        int totalPages = 1;
        int generation;
        boolean loading;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        Player.get().addListener(this);
        selectTab(0);

        // 外部唤起直接搜：am start -n com.shiyin.app/.MainActivity --es q "三国"
        String q = getIntent() == null ? null : getIntent().getStringExtra("q");
        if (q != null && q.length() > 0) {
            Intent i = new Intent(this, com.shiyin.app.ui.SearchActivity.class);
            i.putExtra("q", q);
            String src = getIntent().getStringExtra("src");
            if (src != null) i.putExtra("src", src);
            startActivity(i);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMini();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Player.get().saveProgress();
    }

    @Override
    protected void onDestroy() {
        Player.get().removeListener(this);
        super.onDestroy();
    }

    /* ---------------- 骨架 ---------------- */

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        root.addView(buildTopBar());

        content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(content);

        root.addView(buildMini());
        root.addView(buildTabs());
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 10));

        TextView brand = Ui.bold(this, "拾音", 20f, Ui.AMBER);
        brand.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        bar.addView(brand);

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(gap);

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(Ui.dp(this, 12), Ui.dp(this, 7),
                Ui.dp(this, 12), Ui.dp(this, 7));
        searchBox.setBackground(Ui.round(Ui.BG2, 18, this));
        searchBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, com.shiyin.app.ui.SearchActivity.class));
            }
        });
        TextView icon = Ui.text(this, "🔍", 12f, Ui.DIM);
        searchBox.addView(icon);
        TextView hint = Ui.text(this, "  搜索书名 / 作者 / RSS", 12.5f, Ui.DIM2);
        searchBox.addView(hint);
        bar.addView(searchBox);
        return bar;
    }

    private View buildMini() {
        mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.HORIZONTAL);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setPadding(Ui.dp(this, 10), Ui.dp(this, 7),
                Ui.dp(this, 12), Ui.dp(this, 7));
        mini.setBackground(Ui.roundTop(Ui.BG2, 12, this));
        mini.setVisibility(View.GONE);
        mini.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, PlayerActivity.class));
            }
        });

        miniCover = new ImgView(this);
        miniCover.radius(7);
        miniCover.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        mini.addView(miniCover);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(this, 10);
        col.setLayoutParams(clp);

        miniTitle = Ui.text(this, "", 13f, Ui.TEXT);
        miniTitle.setMaxLines(1);
        miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(miniTitle);

        miniSub = Ui.text(this, "", 11f, Ui.DIM2);
        miniSub.setMaxLines(1);
        col.addView(miniSub);
        mini.addView(col);

        miniBtn = Ui.text(this, "▶", 18f, Ui.AMBER);
        miniBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        miniBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Player.get().toggle(MainActivity.this);
            }
        });
        mini.addView(miniBtn);

        TextView next = Ui.text(this, "⏭", 16f, Ui.DIM);
        next.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Player.get().next(MainActivity.this);
            }
        });
        mini.addView(next);
        return mini;
    }

    private View buildTabs() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ui.BG2);
        bar.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

        String[] names = {"首页", "分类", "书架", "我的"};
        String[] icons = {"🏠", "🗂", "📚", "⚙"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });

            TextView ic = Ui.text(this, icons[i], 17f, Ui.DIM);
            ic.setGravity(Gravity.CENTER);
            item.addView(ic);

            TextView t = Ui.text(this, names[i], 10.5f, Ui.DIM);
            t.setGravity(Gravity.CENTER);
            item.addView(t);

            tabs[i] = t;
            item.setTag(ic);
            bar.addView(item);
        }
        return bar;
    }

    private void selectTab(int i) {
        tab = i;
        for (int k = 0; k < tabs.length; k++) {
            boolean on = k == i;
            tabs[k].setTextColor(on ? Ui.AMBER : Ui.DIM);
            tabs[k].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            View parent = (View) tabs[k].getParent();
            View ic = (View) parent.getTag();
            if (ic instanceof TextView) {
                ((TextView) ic).setAlpha(on ? 1f : 0.55f);
            }
        }
        if (i == 0) showHome();
        else if (i == 1) showCats();
        else if (i == 2) showShelf();
        else showMine();
    }

    private void setPage(View v) {
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openBook(Book b) {
        startActivity(DetailActivity.intent(this, b));
    }

    /* ---------------- 首页 ---------------- */

    private void showHome() {
        final LinearLayout page = Widgets.vbox(this);
        final ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final LinearLayout body = Widgets.vbox(this);
        sv.addView(body);
        page.addView(sv);

        body.addView(Widgets.loading(this, "正在加载推荐…"));
        setPage(page);

        if (Sources.isEmpty()) {
            body.removeAllViews();
            body.addView(Widgets.empty(this, "还没有内容源\n到「我的 → 内容源」添加在线订阅"));
            body.addView(retryButton());
            return;
        }

        Bg.run(new java.util.concurrent.Callable<List<Section>>() {
            @Override
            public List<Section> call() {
                List<Section> out = new ArrayList<>();
                for (Source s : Sources.all()) {
                    try {
                        int before = out.size();
                        if (s instanceof com.shiyin.app.source.JsonSource
                                && ((com.shiyin.app.source.JsonSource) s).hasHome()) {
                            for (com.shiyin.app.source.JsonSource.HomeSection hs
                                    : ((com.shiyin.app.source.JsonSource) s).home()) {
                                Section section = new Section();
                                section.label = hs.label.length() == 0
                                        ? s.name() : s.name() + " · " + hs.label;
                                section.books = hs.books;
                                out.add(section);
                            }
                        }
                        // home 没配或返回空，退回分类第一页
                        if (out.size() == before && s.hasCats()) {
                            List<Cat> cats = s.cats();
                            if (!cats.isEmpty()) {
                                List<Book> books = s.page(cats.get(0), 1).books;
                                if (books != null && !books.isEmpty()) {
                                    Section section = new Section();
                                    section.label = s.name();
                                    section.books = books;
                                    out.add(section);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // 单个源失败不影响其它源
                    }
                }
                return out;
            }
        }, new Bg.Cb<List<Section>>() {
            @Override
            public void ok(List<Section> sections) {
                if (tab != 0 || isFinishing() || isDestroyed()) return;
                body.removeAllViews();
                if (sections.isEmpty()) {
                    body.addView(Widgets.empty(MainActivity.this,
                            "订阅源暂时没有返回内容\n可以试试搜索，或稍后重试"));
                    body.addView(retryButton());
                    return;
                }
                for (Section section : sections) {
                    addBookSection(body, section.label, section.books, section.books.size());
                }
                body.addView(Ui.spacer(MainActivity.this, 24));
            }

            @Override
            public void fail(Exception e) {
                body.removeAllViews();
                body.addView(Widgets.empty(MainActivity.this, "加载失败：" + e.getMessage()));
                body.addView(retryButton());
            }
        });
    }

    private void addBookSection(LinearLayout body, String title, List<Book> books, int limit) {
        body.addView(Widgets.sectionHeader(this, title, null, null));
        LinearLayout row = Widgets.hbox(this);
        for (int i = 0; i < Math.min(limit, books.size()); i++) {
            final Book book = books.get(i);
            row.addView(Widgets.bookCard(this, book, 96, new View.OnClickListener() {
                @Override public void onClick(View view) { openBook(book); }
            }));
        }
        body.addView(Widgets.hscroll(this, row));
    }

    private View retryButton() {
        LinearLayout box = Widgets.vbox(this);
        box.setGravity(Gravity.CENTER);
        TextView b = Widgets.button(this, "重试", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHome();
            }
        });
        box.addView(b);
        return box;
    }

    /* ---------------- 分类 ---------------- */

    private void showCats() {
        LinearLayout page = Widgets.vbox(this);
        if (Sources.isEmpty()) {
            page.addView(Widgets.empty(this, "还没有内容源\n到「我的 → 内容源」添加在线订阅"));
            page.addView(retryButton());
            setPage(page);
            return;
        }
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final List<Cat> roots = new ArrayList<>();
        final TextView emptyHint = Ui.text(this, "正在加载分类…", 12.5f, Ui.DIM2);
        emptyHint.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        page.addView(emptyHint);

        // 分类用自定义行，直接建一个内部 adapter
        final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return roots.size();
            }

            @Override
            public Object getItem(int i) {
                return roots.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                final Cat c = roots.get(i);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, 14),
                        Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, 14));
                row.setBackground(Widgets.ripple(Ui.BG));

                TextView ic = Ui.text(MainActivity.this, c.icon, 20f, Ui.TEXT);
                ic.setGravity(Gravity.CENTER);
                ic.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 42),
                        Ui.dp(MainActivity.this, 42)));
                ic.setBackground(Ui.round(0xFF1B2130, 10, MainActivity.this));
                row.addView(ic);

                LinearLayout col = Widgets.vbox(MainActivity.this);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                clp.leftMargin = Ui.dp(MainActivity.this, 12);
                col.setLayoutParams(clp);

                TextView name = Ui.bold(MainActivity.this, c.name, 15f, Ui.TEXT);
                col.addView(name);

                String sub = Widgets.srcName(c.source)
                        + (c.desc.length() > 0 ? " · " + c.desc : "");
                TextView d = Ui.text(MainActivity.this, sub, 11.5f, Ui.DIM2);
                d.setMaxLines(1);
                d.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(d);
                row.addView(col);

                TextView arrow = Ui.text(MainActivity.this, "›", 18f, Ui.DIM2);
                row.addView(arrow);

                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showCatBooks(c);
                    }
                });
                return row;
            }
        };
        lv.setAdapter(adapter);
        page.addView(lv);
        setPage(page);

        Bg.run(() -> {
            List<Cat> loaded = new ArrayList<>();
            for (Source s : Sources.all()) {
                if (!s.hasCats()) continue;
                try {
                    loaded.addAll(s.cats());
                } catch (Exception ignored) {
                }
            }
            return loaded;
        }, new Bg.Cb<List<Cat>>() {
            @Override public void ok(List<Cat> v) {
                if (tab != 1 || isFinishing() || isDestroyed()) return;
                roots.clear();
                roots.addAll(v);
                adapter.notifyDataSetChanged();
                if (roots.isEmpty()) {
                    emptyHint.setText("这个订阅没有提供分类，可以直接用搜索");
                } else {
                    emptyHint.setVisibility(View.GONE);
                }
            }

            @Override public void fail(Exception e) {
                emptyHint.setText("分类加载失败：" + e.getMessage());
            }
        });
    }

    private void showCatBooks(final Cat root) {
        final LinearLayout page = Widgets.vbox(this);

        // 头部：返回 + 标题
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 6));
        TextView back = Ui.text(this, "‹ 返回", 14f, Ui.DIM);
        back.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCats();
            }
        });
        head.addView(back);
        head.addView(Ui.bold(this, root.name, 16f, Ui.TEXT));
        page.addView(head);

        // 子分类 chips
        final List<Cat> chips = new ArrayList<>();
        if (root.children.isEmpty()) chips.add(root);
        else chips.addAll(root.children);

        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout chipBox = Widgets.hbox(this);
        chipBox.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 8));
        hs.addView(chipBox);
        page.addView(hs);

        final ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(lv);

        final BookAdapter adapter = new BookAdapter(this, new BookAdapter.OnPick() {
            @Override
            public void onPick(Book book, int position) {
                openBook(book);
            }
        });
        lv.setAdapter(adapter);

        // 底部「加载更多」
        final TextView more = new TextView(this);
        more.setTextSize(13f);
        more.setTextColor(Ui.DIM);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 18));
        more.setVisibility(View.GONE);
        lv.addFooterView(more, null, false);

        final CategoryState state = new CategoryState();

        for (int i = 0; i < chips.size(); i++) {
            final int idx = i;
            TextView chip = Widgets.chip(this, chips.get(i).name, i == 0,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            state.selectedIndex = idx;
                            for (int k = 0; k < chipBox.getChildCount(); k++) {
                                TextView t = (TextView) chipBox.getChildAt(k);
                                boolean on = k == idx;
                                t.setTextColor(on ? 0xFF0B0E14 : Ui.DIM);
                                t.setBackground(on ? Ui.round(Ui.AMBER, 14, MainActivity.this)
                                        : Ui.round(Ui.BG3, 14, MainActivity.this));
                            }
                            loadCatPage(chips.get(idx), 1, adapter, state, more, lv);
                        }
                    });
            chipBox.addView(chip);
        }

        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Cat leaf = chips.get(Math.min(state.selectedIndex, chips.size() - 1));
                loadCatPage(leaf, state.loadedPage + 1, adapter, state, more, lv);
            }
        });

        loadCatPage(chips.get(0), 1, adapter, state, more, lv);
        setPage(page);
    }

    private void loadCatPage(final Cat leaf, final int pn, final BookAdapter adapter,
                             final CategoryState state, final TextView more, final ListView lv) {
        if (pn > 1 && (state.loading || pn > state.totalPages)) return;
        final int generation = ++state.generation;
        state.loading = true;
        more.setEnabled(false);
        if (pn == 1) {
            state.loadedPage = 0;
            state.totalPages = 1;
            adapter.set(null);
            more.setVisibility(View.GONE);
            more.setText("加载中…");
            more.setVisibility(View.VISIBLE);
        } else {
            more.setText("加载第 " + pn + " 页…");
        }

        Bg.run(new java.util.concurrent.Callable<Source.Page>() {
            @Override
            public Source.Page call() throws Exception {
                Source source = Sources.byId(leaf.source);
                if (source == null) throw new java.io.IOException("内容源未安装");
                return source.page(leaf, pn);
            }
        }, new Bg.Cb<Source.Page>() {
            @Override
            public void ok(Source.Page p) {
                if (generation != state.generation || isFinishing() || isDestroyed()) return;
                state.loading = false;
                more.setEnabled(true);
                if (pn == 1) adapter.set(p.books);
                else adapter.addAll(p.books);
                state.loadedPage = pn;
                state.totalPages = Math.max(p.totalPage, pn);

                if (p.books.isEmpty() && pn == 1) {
                    more.setText("这个分类暂时没内容");
                    more.setVisibility(View.VISIBLE);
                } else if (state.loadedPage < state.totalPages) {
                    more.setText("加载更多（" + state.loadedPage + "/" + state.totalPages + "）");
                    more.setVisibility(View.VISIBLE);
                } else {
                    more.setText("没有更多了");
                    more.setVisibility(pn == 1 ? View.GONE : View.VISIBLE);
                }
                if (pn > 1 && lv.getAdapter() != null) {
                    lv.setSelection(adapter.getCount() - Math.min(p.books.size(), 1));
                }
            }

            @Override
            public void fail(Exception e) {
                if (generation != state.generation || isFinishing() || isDestroyed()) return;
                state.loading = false;
                more.setEnabled(true);
                if (pn == 1) {
                    adapter.set(null);
                    more.setText("加载失败：" + e.getMessage() + "\n点这里重试");
                } else {
                    more.setText("加载失败，点这里重试");
                }
                more.setVisibility(View.VISIBLE);
            }
        });
    }

    /* ---------------- 书架 ---------------- */

    private void showShelf() {
        LinearLayout page = Widgets.vbox(this);
        List<Book> recent = Prefs.recent();
        List<Book> shelf = Prefs.shelf();

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Widgets.vbox(this);
        sv.addView(body);
        page.addView(sv);

        if (!recent.isEmpty()) {
            addBookSection(body, "继续收听", recent, recent.size());
        }

        body.addView(Widgets.sectionHeader(this, "我的书架",
                shelf.isEmpty() ? null : shelf.size() + " 本", null));

        if (shelf.isEmpty()) {
            body.addView(Widgets.empty(this, "书架还是空的\n去首页或分类里点开一本书，详情页点「加入书架」"));
        } else {
            for (final Book b : shelf) {
                body.addView(Widgets.bookRow(this, b, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openBook(b);
                    }
                }));
            }
        }
        body.addView(Ui.spacer(this, 30));
        setPage(page);
    }

    /* ---------------- 我的 ---------------- */

    private void showMine() {
        ++updateRequestGeneration;
        checkingUpdate = false;
        ScrollView sv = new ScrollView(this);
        LinearLayout body = Widgets.vbox(this);
        sv.addView(body);

        body.addView(Widgets.sectionHeader(this, "播放", null, null));
        body.addView(settingRow("后台播放设置", "通知 / 电池 / 自启动",
                view -> startActivity(new Intent(this, com.shiyin.app.ui.BackgroundActivity.class))));

        body.addView(settingRow("音质", "320".equals(Prefs.quality()) ? "320 kbps" : "128 kbps",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.setQuality("320".equals(Prefs.quality()) ? "128" : "320");
                        showMine();
                        Ui.toast(MainActivity.this, "已切换音质，下一集生效");
                    }
                }));

        body.addView(Widgets.sectionHeader(this, "内容源", null, null));
        List<Source> installed = Sources.all();
        if (installed.isEmpty()) {
            TextView tip = Ui.text(this, "还没有内容源。添加在线订阅后，"
                    + "首页、分类和搜索才会出现内容。", 12f, Ui.DIM2);
            tip.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 10));
            tip.setLineSpacing(Ui.dp(this, 3), 1f);
            body.addView(tip);
        } else {
            for (final Source s : installed) {
                body.addView(sourceRow(s));
            }
        }
        body.addView(settingRow("添加订阅", "粘贴订阅 JSON 地址",
                v -> promptAddSubscription()));
        List<String> subs = Subscriptions.urls();
        if (!subs.isEmpty()) {
            body.addView(settingRow("管理订阅", subs.size() + " 个地址",
                    v -> showSubscriptions()));
            body.addView(settingRow("刷新全部订阅",
                    "缓存 " + (Subscriptions.CACHE_MAX_AGE_MS / 3600000L) + " 小时，过期自动更新",
                    v -> refreshAllSubscriptions()));
        }

        body.addView(Widgets.sectionHeader(this, "其他", null, null));
        body.addView(settingRow("清空图片缓存", "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ImgView.clearCache();
                Ui.toast(MainActivity.this, "已清空");
            }
        }));

        body.addView(settingRow("清空搜索历史", "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.clearSearchHistory();
                Ui.toast(MainActivity.this, "已清空");
            }
        }));

        body.addView(Widgets.sectionHeader(this, "关于", null, null));
        String version = installedVersion();
        TextView about = Ui.text(this, "拾音 " + version + " · 原生版\n"
                        + "应用本身不内置内容源，内容由你自行添加的订阅提供。\n"
                        + "仅供学习研究使用，请确保你有权访问相应内容。",
                12f, Ui.DIM2);
        about.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 10));
        about.setLineSpacing(Ui.dp(this, 3), 1f);
        body.addView(about);
        body.addView(settingRow("检查更新", "GitHub 发布", v -> checkForUpdate(version)));
        body.addView(settingRow("GitHub 项目", "github.com/luopos/shiyin",
                v -> openProjectPage(AppUpdate.PROJECT_URL)));
        body.addView(Ui.spacer(this, 24));

        setPage(sv);
    }

    private void promptAddSubscription() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://…/sources.json");
        input.setHintTextColor(Ui.DIM2);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(14f);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(this);
        int side = Ui.dp(this, 20);
        box.setPadding(side, Ui.dp(this, 8), side, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle("添加订阅源").setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加", (dialog, which) ->
                        installSubscription(input.getText().toString().trim()))
                .show();
    }

    private void installSubscription(final String url) {
        if (url.length() == 0) {
            Ui.toast(this, "先填订阅地址");
            return;
        }
        Ui.toast(this, "正在下载订阅…");
        Bg.run(() -> {
            Subscriptions.install(url);
            return Sources.all().size();
        }, new Bg.Cb<Integer>() {
            @Override public void ok(Integer count) {
                if (isFinishing() || isDestroyed()) return;
                Ui.toast(MainActivity.this, "订阅已添加，共 " + count + " 个源");
                showMine();
            }

            @Override public void fail(Exception e) {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(MainActivity.this).setTitle("订阅添加失败")
                        .setMessage(e.getMessage() == null ? "下载或解析失败" : e.getMessage())
                        .setPositiveButton("知道了", null).show();
            }
        });
    }

    private void showSubscriptions() {
        final List<String> urls = Subscriptions.urls();
        if (urls.isEmpty()) {
            Ui.toast(this, "还没有订阅");
            return;
        }
        String[] labels = new String[urls.size()];
        for (int i = 0; i < urls.size(); i++) {
            labels[i] = urls.get(i) + "\n" + Subscriptions.cacheAge(urls.get(i));
        }
        new AlertDialog.Builder(this).setTitle("管理订阅")
                .setItems(labels, (dialog, which) ->
                        showSubscriptionActions(urls.get(which)))
                .setNegativeButton("关闭", null).show();
    }

    private void showSubscriptionActions(final String url) {
        new AlertDialog.Builder(this).setTitle(url)
                .setMessage(Subscriptions.cacheAge(url)
                        + "\n订阅清单有本地缓存，启动时不会重复下载。")
                .setItems(new String[]{"立即刷新", "删除"}, (dialog, which) -> {
                    if (which == 0) refreshSubscription(url);
                    else confirmRemoveSubscription(url);
                })
                .setNegativeButton("取消", null).show();
    }

    private void refreshSubscription(final String url) {
        Ui.toast(this, "正在刷新…");
        Bg.run(() -> {
            Subscriptions.install(url);
            return Sources.all().size();
        }, new Bg.Cb<Integer>() {
            @Override public void ok(Integer count) {
                if (isFinishing() || isDestroyed()) return;
                Ui.toast(MainActivity.this, "已刷新，共 " + count + " 个源");
                showMine();
            }

            @Override public void fail(Exception e) {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(MainActivity.this).setTitle("刷新失败")
                        .setMessage(e.getMessage() == null ? "下载或解析失败" : e.getMessage())
                        .setPositiveButton("知道了", null).show();
            }
        });
    }

    private void refreshAllSubscriptions() {
        if (Subscriptions.urls().isEmpty()) {
            Ui.toast(this, "还没有订阅");
            return;
        }
        Ui.toast(this, "正在刷新订阅…");
        Bg.run(Subscriptions::refreshAll, new Bg.Cb<List<String>>() {
            @Override public void ok(List<String> failed) {
                if (isFinishing() || isDestroyed()) return;
                if (failed.isEmpty()) {
                    Ui.toast(MainActivity.this, "订阅已全部更新");
                } else {
                    new AlertDialog.Builder(MainActivity.this).setTitle("部分订阅刷新失败")
                            .setMessage(android.text.TextUtils.join("\n\n", failed))
                            .setPositiveButton("知道了", null).show();
                }
                showMine();
            }

            @Override public void fail(Exception e) {
                if (isFinishing() || isDestroyed()) return;
                Ui.toast(MainActivity.this, e.getMessage() == null ? "刷新失败" : e.getMessage());
            }
        });
    }

    private void confirmRemoveSubscription(final String url) {
        new AlertDialog.Builder(this).setTitle("删除这个订阅？")
                .setMessage(url + "\n\n该订阅提供的内容源会一起移除，书架里的书将无法继续播放。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    Subscriptions.remove(url);
                    Ui.toast(this, "已删除");
                    showMine();
                }).show();
    }

    private String installedVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "未知";
        }
    }

    private void checkForUpdate(String version) {
        if (checkingUpdate) return;
        checkingUpdate = true;
        final int request = ++updateRequestGeneration;
        Ui.toast(this, "正在检查更新…");
        Bg.run(AppUpdate::latest, new Bg.Cb<AppUpdate.Release>() {
            @Override public void ok(AppUpdate.Release release) {
                if (!canShowUpdateResult(request)) return;
                final int comparison;
                try {
                    comparison = AppUpdate.compareVersions(release.version, version);
                } catch (IllegalArgumentException e) {
                    showUpdateDialog("无法比较版本", "当前版本：" + version
                            + "\nGitHub 正式版本：" + release.version, release.pageUrl);
                    return;
                }
                if (comparison > 0) {
                    showUpdateDialog("发现新版本", "当前版本：" + version + "\n可更新到："
                            + release.version, release.pageUrl);
                } else {
                    showUpdateDialog(comparison == 0 ? "已是最新版本" : "当前版本比正式发布更新",
                            "当前版本：" + version + "\nGitHub 正式版本：" + release.version,
                            release.pageUrl);
                }
            }

            @Override public void fail(Exception e) {
                if (canShowUpdateResult(request)) {
                    showUpdateDialog("检查更新失败", e.getMessage(), AppUpdate.RELEASES_URL);
                }
            }
        });
    }

    private boolean canShowUpdateResult(int request) {
        if (request != updateRequestGeneration) return false;
        checkingUpdate = false;
        return !isFinishing() && !isDestroyed() && tab == 3;
    }

    private void showUpdateDialog(String title, String message, String pageUrl) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("关闭", null)
                .setPositiveButton("打开发布页", (dialog, which) -> openProjectPage(pageUrl))
                .show();
    }

    private void openProjectPage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            new AlertDialog.Builder(this).setTitle("无法打开链接").setMessage(url)
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("复制链接", (dialog, which) -> {
                        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("GitHub", url));
                            Ui.toast(this, "已复制链接");
                        }
                    }).show();
        }
    }

    private View settingRow(String title, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        row.setBackground(Widgets.ripple(Ui.BG));
        row.setOnClickListener(onClick);

        TextView t = Ui.text(this, title, 14f, Ui.TEXT);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        TextView v = Ui.text(this, value, 12.5f, Ui.DIM2);
        v.setMaxLines(1);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(v);
        return row;
    }

    private View sourceRow(Source s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));

        LinearLayout col = Widgets.vbox(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(clp);

        col.addView(Ui.text(this, s.name(), 14f, Ui.TEXT));
        TextView d = Ui.text(this, s.desc(), 11f, Ui.DIM2);
        d.setMaxLines(2);
        col.addView(d);
        row.addView(col);

        TextView tag = Ui.text(this, "订阅", 11f, Ui.TEAL);
        tag.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
        tag.setBackground(Ui.round(0xFF10251F, 10, this));
        row.addView(tag);
        return row;
    }

    /* ---------------- 迷你条 ---------------- */

    private void refreshMini() {
        Player p = Player.get();
        Book b = p.book();
        if (b == null) {
            mini.setVisibility(View.GONE);
            return;
        }
        mini.setVisibility(View.VISIBLE);
        miniCover.load(b.cover);
        miniTitle.setText(b.title);
        com.shiyin.app.model.Chapter c = p.chapter();
        miniSub.setText(c == null ? "" : c.title);
        int st = p.state();
        if (st == Player.LOADING) miniBtn.setText("…");
        else if (st == Player.PLAYING) miniBtn.setText("❚❚");
        else miniBtn.setText("▶");
    }

    @Override
    public void onState(int state, String message) {
        refreshMini();
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
    }

    @Override
    public void onTrack(int index, com.shiyin.app.model.Chapter chapter) {
        refreshMini();
    }
}

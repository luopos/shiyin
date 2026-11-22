package com.shiyin.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.shiyin.app.kid.KidMode;
import com.shiyin.app.kid.KidSearch;
import com.shiyin.app.model.Book;
import com.shiyin.app.source.Source;
import com.shiyin.app.source.Sources;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.util.Bg;
import com.shiyin.app.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/** 搜索：并发打所有可搜源，合并结果。一个源挂了不影响别的。 */
public class SearchActivity extends Activity {

    private EditText input;
    private ListView lv;
    private LinearLayout chipBox;
    private TextView status;
    private LinearLayout historyBox;
    private BookAdapter adapter;

    private final List<Source> pool = new ArrayList<>();
    private String selected = "__all__";
    private int searchGeneration;

    @Override protected void onDestroy() {
        searchGeneration++;
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 青少年模式只列有白名单分类的源：没有儿童分类的源搜了也是空，列出来只会误导。
        for (Source source : Sources.searchable()) {
            if (!KidMode.enabled() || !KidMode.allowedLeafIds(source.id()).isEmpty()) {
                pool.add(source);
            }
        }
        setContentView(build());
        showHistory();

        // 调试/外部唤起：am start --es q "三国" 直接搜
        String q = getIntent() == null ? null : getIntent().getStringExtra("q");
        String src = getIntent() == null ? null : getIntent().getStringExtra("src");
        if (src != null && src.length() > 0) {
            selected = src;
            rebuildChips();
        }
        if (q != null && q.length() > 0) {
            input.setText(q);
            input.setSelection(q.length());
            Bg.uiDelayed(new Runnable() {
                @Override
                public void run() {
                    doSearch();
                }
            }, 250);
        }
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        /* 顶栏 */
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 8));

        TextView back = Ui.text(this, "‹", 24f, Ui.DIM);
        back.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 8), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("书名 / 作者 / RSS 链接");
        input.setHintTextColor(Ui.DIM2);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(14f);
        input.setBackground(Ui.round(Ui.BG2, 18, this));
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(ilp);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                doSearch();
                return true;
            }
        });
        bar.addView(input);

        TextView go = Ui.bold(this, "搜索", 13.5f, Ui.AMBER);
        go.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch();
            }
        });
        bar.addView(go);
        root.addView(bar);

        /* 源选择 */
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipBox = Widgets.hbox(this);
        chipBox.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 6));
        hs.addView(chipBox);
        root.addView(hs);
        rebuildChips();

        /* 状态 */
        status = Ui.text(this, "", 12f, Ui.DIM2);
        status.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 6));
        root.addView(status);

        /* 结果 */
        lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new BookAdapter(this, new BookAdapter.OnPick() {
            @Override
            public void onPick(Book book, int position) {
                openBook(book);
            }
        });
        lv.setAdapter(adapter);
        root.addView(lv);

        // 历史区：跟结果列表互斥显示。历史项是关键词，不该借用书目行的封面位。
        ScrollView historyScroll = new ScrollView(this);
        historyScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        historyBox.setVisibility(View.GONE);
        historyScroll.addView(historyBox);
        root.addView(historyScroll);

        return root;
    }

    private void rebuildChips() {
        chipBox.removeAllViews();
        chipBox.addView(Widgets.chip(this, "全部源", "__all__".equals(selected),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selected = "__all__";
                        rebuildChips();
                    }
                }));
        for (final Source s : pool) {
            chipBox.addView(Widgets.chip(this, s.name(), s.id().equals(selected),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            selected = s.id();
                            rebuildChips();
                        }
                    }));
        }
    }

    /**
     * 搜索历史：关键词列表，不是书目列表。
     *
     * <p>之前把每个关键词包成一个 {@code Book} 塞进书籍列表，
     * 于是每条都占一个封面位却没有任何封面 —— 用户看到的就是一排空白方块。
     * 现在直接用关键词行渲染，列表也换成独立模式，避免跟书目混在一起。
     */
    private void showHistory() {
        List<String> his = Prefs.searchHistory();
        if (his.isEmpty()) {
            status.setText("输入关键词，或者直接粘一个 RSS 链接");
            showHistoryRows(false);
            return;
        }
        status.setText("搜索历史 · 点一下直接搜");
        showHistoryRows(true);
    }

    private void showHistoryRows(boolean visible) {
        if (historyBox == null) return;
        historyBox.removeAllViews();
        if (!visible) {
            historyBox.setVisibility(View.GONE);
            lv.setVisibility(View.VISIBLE);
            adapter.set(null);
            return;
        }
        historyBox.setVisibility(View.VISIBLE);
        lv.setVisibility(View.GONE);
        for (final String kw : Prefs.searchHistory()) {
            historyBox.addView(Widgets.historyRow(this, kw, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    input.setText(kw);
                    doSearch();
                }
            }));
        }
    }

    private void openBook(Book b) {
        if ("__kw__".equals(b.bookUrl)) {
            input.setText(b.title);
            doSearch();
            return;
        }
        startActivity(DetailActivity.intent(this, b));
    }

    private void doSearch() {
        final String kw = input.getText().toString().trim();
        if (kw.length() == 0) {
            Ui.toast(this, "先输点东西");
            return;
        }
        if (isFinishing() || isDestroyed()) return;
        final int generation = ++searchGeneration;
        Prefs.addSearchHistory(kw);
        lv.setOnItemClickListener(null);
        hideKeyboard();

        List<Source> targets = new ArrayList<>();
        if ("__all__".equals(selected)) targets.addAll(pool);
        else {
            Source one = Sources.byId(selected);
            if (one != null) targets.add(one);
        }
        if (targets.isEmpty()) {
            boolean kid = KidMode.enabled();
            status.setText(kid
                    ? "青少年模式下没有可搜索的儿童分类\n去「我的 → 内容源」换一个带儿童分类的订阅"
                    : "还没有内容源。到「我的 → 内容源」添加订阅后再搜");
            adapter.set(null);
            return;
        }

        final boolean kid = KidMode.enabled();
        // 从历史态切回结果态，否则搜索完成后列表仍是隐藏的，用户看不到结果
        if (historyBox != null) historyBox.setVisibility(View.GONE);
        lv.setVisibility(View.VISIBLE);
        status.setText(kid
                ? "正在儿童分类里搜「" + kw + "」…"
                : "正在搜「" + kw + "」…");
        adapter.set(null);

        final List<Book> merged = Collections.synchronizedList(new ArrayList<Book>());
        final int total = targets.size();
        final int[] finished = {0};
        final int[] donePages = {0};
        final int[] totalPages = {0};
        final boolean[] anyError = {false};

        for (final Source s : targets) {
            if (kid) {
                searchKidSource(s, kw, generation, merged, donePages, totalPages, anyError,
                        finished, total);
            } else {
                searchWholeSource(s, kw, generation, merged, finished, total);
            }
        }
    }

    /**
     * 青少年模式：分类内增量搜索。
     *
     * <p>每翻完一页就把累计命中刷到列表上，用户能边搜边看；
     * 进度用「已扫页数 / 预计页数」表示，比原来干等一个转圈有用。
     */
    private void searchKidSource(final Source source, final String kw, final int generation,
                                 final List<Book> merged, final int[] donePages,
                                 final int[] totalPages, final boolean[] anyError,
                                 final int[] finished, final int total) {
        Bg.fire(new Runnable() {
            @Override
            public void run() {
                try {
                    KidSearch.searchSource(source, kw, KidSearch.MAX_PAGES_PER_CAT,
                            new KidSearch.Listener() {
                                @Override
                                public void onPage(List<Book> hits, int done, int pages,
                                                   boolean lastPage) {
                                    if (generation != searchGeneration) return;
                                    final int d = done;
                                    final int p = pages;
                                    Bg.ui(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (generation != searchGeneration || isFinishing()) {
                                                return;
                                            }
                                            merged.clear();
                                            merged.addAll(hits);
                                            donePages[0] = d;
                                            totalPages[0] = Math.max(p, d);
                                            adapter.set(new ArrayList<Book>(merged));
                                            if (merged.isEmpty()) {
                                                status.setText("正在儿童分类里搜「" + kw
                                                        + "」… 已扫 " + d + "/" + p + " 页");
                                            } else {
                                                status.setText("「" + kw + "」已找到 "
                                                        + merged.size() + " 本 · 已扫 "
                                                        + d + "/" + p + " 页");
                                            }
                                        }
                                    });
                                }

                                @Override
                                public void onDone(List<Book> hits, boolean err) {
                                    if (generation != searchGeneration) return;
                                    final boolean hadError = err;
                                    Bg.ui(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (generation != searchGeneration || isFinishing()) {
                                                return;
                                            }
                                            merged.clear();
                                            merged.addAll(hits);
                                            if (hadError) anyError[0] = true;
                                            finished[0]++;
                                            kidFinish(kw, merged, finished[0], total,
                                                    anyError[0], totalPages[0]);
                                        }
                                    });
                                }
                            });
                } catch (Exception e) {
                    if (generation != searchGeneration) return;
                    Bg.ui(new Runnable() {
                        @Override
                        public void run() {
                            if (generation != searchGeneration || isFinishing()) return;
                            anyError[0] = true;
                            finished[0]++;
                            kidFinish(kw, merged, finished[0], total, true, totalPages[0]);
                        }
                    });
                }
            }
        });
    }

    /** 普通模式：沿用全站搜索，一个源一次请求。 */
    private void searchWholeSource(final Source source, final String kw, final int generation,
                                   final List<Book> merged, final int[] finished, final int total) {
        Bg.run(new Callable<List<Book>>() {
            @Override
            public List<Book> call() throws Exception {
                return source.search(kw, 1);
            }
        }, new Bg.Cb<List<Book>>() {
            @Override
            public void ok(List<Book> v) {
                if (generation != searchGeneration || isFinishing()) return;
                if (v != null) merged.addAll(v);
                finished[0]++;
                flush(kw, merged, finished[0], total);
            }

            @Override
            public void fail(Exception e) {
                if (generation != searchGeneration || isFinishing()) return;
                finished[0]++;
                flush(kw, merged, finished[0], total);
            }
        });
    }

    /** 青少年模式收尾：所有源扫完才判定结果，0 命中必须给出明确反馈。 */
    private void kidFinish(String kw, List<Book> merged, int done, int total,
                           boolean anyError, int scannedPages) {
        List<Book> view = KidMode.filterSearchResults(new ArrayList<>(merged));
        adapter.set(view);
        if (done < total) {
            status.setText("已找到 " + view.size() + " 本 · 已搜 " + done + "/" + total + " 个源");
            return;
        }
        if (!view.isEmpty()) {
            status.setText("「" + kw + "」共 " + view.size() + " 本 · 仅儿童内容"
                    + (anyError ? "（部分分类未取到）" : ""));
            return;
        }
        // 0 命中：区分「扫过但没匹配」和「请求全挂了」，别让用户以为坏掉了
        if (anyError) {
            status.setText("「" + kw + "」没搜到，而且部分分类请求失败\n检查网络后重试，或换个关键词");
        } else {
            status.setText("「" + kw + "」在儿童分类里没有匹配的书\n青少年模式只搜儿童内容，换个关键词试试");
        }
    }

    private void flush(String kw, List<Book> merged, int done, int total) {
        List<Book> view = KidMode.filterSearchResults(new ArrayList<>(merged));
        adapter.set(view);
        boolean kid = KidMode.enabled();
        if (done < total) {
            status.setText("正在搜「" + kw + "」… " + done + "/" + total
                    + " 个源，已 " + view.size() + " 本");
        } else if (view.isEmpty()) {
            status.setText(kid
                    ? "「" + kw + "」在儿童分类里没有找到。青少年模式只搜儿童内容"
                    : "「" + kw + "」没搜到。换个关键词，或者切到别的源试试");
        } else {
            status.setText("「" + kw + "」共 " + view.size() + " 本（" + total + " 个源）"
                    + (kid ? " · 仅儿童内容" : ""));
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager im = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }
}

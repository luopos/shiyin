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
import android.widget.ProgressBar;
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
    /** 历史区外壳。它和 lv 都吃 weight=1，显隐必须作用在外壳上。 */
    private ScrollView historyScroll;
    /** 搜索进度：横条 + 文字，避免长搜索看起来像卡死。 */
    private LinearLayout progressRow;
    private ProgressBar progress;
    private TextView progressText;
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

        /* 进度：长搜索必须能看见「在动」，否则会被当成卡死 */
        progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 10));
        progressRow.setVisibility(View.GONE);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, Ui.dp(this, 6), 1f);
        plp.rightMargin = Ui.dp(this, 10);
        progress.setLayoutParams(plp);
        progressRow.addView(progress);

        // 进度文字要醒目：11.5sp 灰字太小，用户根本注意不到
        progressText = Ui.bold(this, "", 13.5f, Ui.AMBER);
        progressText.setSingleLine(true);
        progressRow.addView(progressText);
        root.addView(progressRow);

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
        // 关键：这个 ScrollView 与 lv 都是 weight=1，只隐藏 historyBox 的话
        // 外壳仍然占掉一半高度，结果列表会被压扁——看起来就像「被遮住了」。
        historyScroll = new ScrollView(this);
        historyScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        historyScroll.setVisibility(View.GONE);
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
    /* ---------------- 进度 ---------------- */

    /**
     * 更新进度条。
     *
     * <p>进度用「已完成工作 / 总工作」表示：多源搜索时按源数 + 当前源页数折算，
     * 这样进度条只会单调前进，不会因为各源各自从 0 数而卡在 50%。
     *
     * @param done  已完成的工作量
     * @param total 总工作量；<=0 时用不确定态（来回跑）明确表示在动
     */
    private void showProgress(int done, int total, String note) {
        if (progressRow == null) return;
        progressRow.setVisibility(View.VISIBLE);
        if (total <= 0) {
            progress.setIndeterminate(true);
            progressText.setText(note == null ? "" : note);
            return;
        }
        progress.setIndeterminate(false);
        int pct = (int) Math.min(100, Math.round(done * 100.0 / total));
        progress.setProgress(pct);
        String head = (note == null || note.length() == 0) ? "" : note + " · ";
        progressText.setText(head + pct + "%");
    }

    private void hideProgress() {
        if (progressRow == null) return;
        progressRow.setVisibility(View.GONE);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        progressText.setText("");
    }

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
        if (historyBox == null || historyScroll == null) return;
        historyBox.removeAllViews();
        if (!visible) {
            // 外壳一起藏，否则 weight=1 仍占半屏，把结果列表压扁。
            historyScroll.setVisibility(View.GONE);
            historyBox.setVisibility(View.GONE);
            lv.setVisibility(View.VISIBLE);
            adapter.set(null);
            return;
        }
        historyBox.setVisibility(View.VISIBLE);
        historyScroll.setVisibility(View.VISIBLE);
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
        final boolean kid = KidMode.enabled();
        if (targets.isEmpty()) {
            status.setText(kid
                    ? "青少年模式下没有可搜索的儿童分类\n去「我的 → 内容源」换一个带儿童分类的订阅"
                    : "还没有内容源。到「我的 → 内容源」添加订阅后再搜");
            adapter.set(null);
            hideProgress();
            return;
        }

        // 从历史态切回结果态。外壳也一起藏，否则 weight=1 会压扁结果列表。
        if (historyScroll != null) historyScroll.setVisibility(View.GONE);
        if (historyBox != null) historyBox.setVisibility(View.GONE);
        lv.setVisibility(View.VISIBLE);
        adapter.set(null);
        status.setText(kid
                ? "正在儿童分类里搜「" + kw + "」…"
                : "正在搜「" + kw + "」…");
        // 立刻给「在动」的信号，等第一个分类回来之前也不会像卡死
        showProgress(0, 0, kid ? "正在准备儿童分类" : "正在搜索");

        final List<Book> merged = Collections.synchronizedList(new ArrayList<Book>());
        final int total = targets.size();
        // 进度用「已完成源数 × 100 + 当前源页进度」折算成统一刻度，
        // 避免多个源各自从 0 数导致进度条卡住或倒退。
        final int[] doneSources = {0};
        final int[] curDone = {0};
        final int[] curTotal = {0};
        final boolean[] anyError = {false};

        for (final Source s : targets) {
            if (kid) {
                searchKidSource(s, kw, generation, merged, doneSources, curDone, curTotal,
                        anyError, total);
            } else {
                searchWholeSource(s, kw, generation, merged, doneSources, total);
            }
        }
    }

    /**
     * 全局进度百分比：已完成的源占整数刻度，当前源按页数占一个小数刻度。
     * 只增不减，多源搜索时进度条会平稳往前走。
     */
    private void pushProgress(int doneSources, int totalSources,
                              int curDone, int curTotal, String note) {
        if (totalSources <= 0) {
            showProgress(0, 0, note);
            return;
        }
        // 每源刻度 = 1000，当前源内部按页比例填充
        int base = doneSources * 1000;
        int inner = 0;
        if (curTotal > 0) {
            inner = (int) Math.min(999, Math.round(curDone * 1000.0 / curTotal));
        }
        showProgress(base + inner, totalSources * 1000, note);
    }

    /** 青少年模式：分类内增量搜索，每翻完一页刷新结果 + 进度。 */
    private void searchKidSource(final Source source, final String kw, final int generation,
                                 final List<Book> merged, final int[] doneSources,
                                 final int[] curDone, final int[] curTotal,
                                 final boolean[] anyError, final int totalSources) {
        Bg.fire(new Runnable() {
            @Override
            public void run() {
                try {
                    KidSearch.searchSource(source, kw, KidSearch.MAX_PAGES_PER_CAT,
                            new KidSearch.Listener() {
                                @Override
                                public void onPage(List<Book> hits, int done, int pages,
                                                   boolean hasNew) {
                                    if (generation != searchGeneration) return;
                                    final List<Book> snapshot = new ArrayList<Book>(hits);
                                    final int d = done;
                                    final int pg = pages;
                                    Bg.ui(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (generation != searchGeneration || isFinishing()) {
                                                return;
                                            }
                                            merged.clear();
                                            merged.addAll(snapshot);
                                            adapter.set(new ArrayList<Book>(merged));
                                            curDone[0] = d;
                                            curTotal[0] = pg;
                                            pushProgress(doneSources[0], totalSources, d, pg,
                                                    merged.size() + " 本");
                                            status.setText("「" + kw + "」已找到 "
                                                    + merged.size() + " 本 · 已扫 "
                                                    + d + "/" + pg + " 页");
                                        }
                                    });
                                }

                                @Override
                                public void onDone(List<Book> hits, boolean err) {
                                    if (generation != searchGeneration) return;
                                    final List<Book> snapshot = new ArrayList<Book>(hits);
                                    final boolean hadError = err;
                                    Bg.ui(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (generation != searchGeneration || isFinishing()) {
                                                return;
                                            }
                                            merged.clear();
                                            merged.addAll(snapshot);
                                            if (hadError) anyError[0] = true;
                                            doneSources[0]++;
                                            curDone[0] = 0;
                                            curTotal[0] = 0;
                                            kidFinish(kw, merged, doneSources[0], totalSources,
                                                    anyError[0]);
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
                            doneSources[0]++;
                            kidFinish(kw, merged, doneSources[0], totalSources, true);
                        }
                    });
                }
            }
        });
    }

    /** 普通模式：全站搜索，一个源一次请求。 */
    private void searchWholeSource(final Source source, final String kw, final int generation,
                                   final List<Book> merged, final int[] doneSources,
                                   final int totalSources) {
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
                doneSources[0]++;
                flush(kw, merged, doneSources[0], totalSources);
            }

            @Override
            public void fail(Exception e) {
                if (generation != searchGeneration || isFinishing()) return;
                doneSources[0]++;
                flush(kw, merged, doneSources[0], totalSources);
            }
        });
    }

    /** 青少年模式收尾：0 命中必须有明确反馈，并区分「没匹配」与「请求失败」。 */
    private void kidFinish(String kw, List<Book> merged, int done, int total, boolean anyError) {
        List<Book> view = KidMode.filterSearchResults(new ArrayList<>(merged));
        adapter.set(view);
        if (done < total) {
            pushProgress(done, total, 0, 0, view.size() + " 本");
            status.setText("已找到 " + view.size() + " 本 · 已搜 " + done + "/" + total + " 个源");
            return;
        }
        hideProgress();
        if (!view.isEmpty()) {
            status.setText("「" + kw + "」共 " + view.size() + " 本 · 仅儿童内容"
                    + (anyError ? "（部分分类未取到）" : ""));
            return;
        }
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
            pushProgress(done, total, 0, 0, view.size() + " 本");
            status.setText("正在搜「" + kw + "」… " + done + "/" + total
                    + " 个源，已 " + view.size() + " 本");
        } else {
            hideProgress();
            if (view.isEmpty()) {
                status.setText(kid
                        ? "「" + kw + "」在儿童分类里没有找到。青少年模式只搜儿童内容"
                        : "「" + kw + "」没搜到。换个关键词，或者切到别的源试试");
            } else {
                status.setText("「" + kw + "」共 " + view.size() + " 本（" + total + " 个源）"
                        + (kid ? " · 仅儿童内容" : ""));
            }
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

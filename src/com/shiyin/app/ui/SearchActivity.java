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
import android.widget.TextView;

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
        pool.addAll(Sources.searchable());
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

    private void showHistory() {
        List<String> his = Prefs.searchHistory();
        if (his.isEmpty()) {
            status.setText("输入关键词，或者直接粘一个 RSS 链接");
            adapter.set(null);
            return;
        }
        status.setText("搜索历史");
        List<Book> rows = new ArrayList<>();
        for (String h : his) {
            Book b = new Book("", "", h);
            b.bookUrl = "__kw__";
            rows.add(b);
        }
        adapter.set(rows);
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Book b = adapter.getItem(pos);
                if ("__kw__".equals(b.bookUrl)) {
                    input.setText(b.title);
                    doSearch();
                }
            }
        });
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
        else targets.add(Sources.byId(selected));

        status.setText("正在搜 " + kw + " …");
        adapter.set(null);

        final List<Book> merged = Collections.synchronizedList(new ArrayList<Book>());
        final int[] done = {0};
        final int total = targets.size();

        for (final Source s : targets) {
            Bg.run(new Callable<List<Book>>() {
                @Override
                public List<Book> call() throws Exception {
                    return s.search(kw, 1);
                }
            }, new Bg.Cb<List<Book>>() {
                @Override
                public void ok(List<Book> v) {
                    if (generation != searchGeneration || isFinishing()) return;
                    if (v != null) merged.addAll(v);
                    done[0]++;
                    flush(kw, merged, done[0], total);
                }

                @Override
                public void fail(Exception e) {
                    if (generation != searchGeneration || isFinishing()) return;
                    done[0]++;
                    flush(kw, merged, done[0], total);
                }
            });
        }
    }

    private void flush(String kw, List<Book> merged, int done, int total) {
        List<Book> view = new ArrayList<>(merged);
        adapter.set(view);
        if (done < total) {
            status.setText("正在搜「" + kw + "」… " + done + "/" + total
                    + " 个源，已 " + view.size() + " 本");
        } else if (view.isEmpty()) {
            status.setText("「" + kw + "」没搜到。换个关键词，或者切到别的源试试");
        } else {
            status.setText("「" + kw + "」共 " + view.size() + " 本（" + total + " 个源）");
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

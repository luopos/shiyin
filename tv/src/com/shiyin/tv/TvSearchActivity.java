package com.shiyin.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.shiyin.app.kid.KidMode;
import com.shiyin.app.kid.KidSearch;
import com.shiyin.app.model.Book;
import com.shiyin.app.source.Source;
import com.shiyin.app.source.Sources;
import com.shiyin.app.util.Bg;
import com.shiyin.app.util.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * TV / 车机 / 平板上的搜索页。
 *
 * <p>电视没有实体键盘，也没有可靠的软键盘（部分车机干脆没有输入法），
 * 所以这里内置一块屏幕键盘：字母 A-Z、数字、空格、退格、清空、搜索。
 * 遥控器方向键在键盘上移动、OK 输入一个字符；平板上也能直接手指点。
 * 同时保留 {@link EditText}，接得上外接键盘或系统输入法的场景也能用。
 *
 * <p>青少年模式下搜索结果逐本过 {@link KidMode#filterSearchResults(List)}，
 * 只显示白名单内的书，并给出明确提示。
 */
public class TvSearchActivity extends Activity {

    private static final String[] KEY_ROWS = {
            "1234567890",
            "QWERTYUIOP",
            "ASDFGHJKL",
            "ZXCVBNM"
    };

    private final StringBuilder query = new StringBuilder();
    private TextView inputView;
    private TextView statusView;
    private LinearLayout resultsBody;
    private ScrollView resultsScroll;
    private EditText hiddenInput;

    private int searchGeneration;
    private boolean searching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());
        focusFirst();
    }

    /* ---------------- 布局 ---------------- */

    private View build() {
        boolean portrait = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 18), Ui.dp(this, 24), Ui.dp(this, 18));
        root.setFitsSystemWindows(true);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(portrait
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(Ui.dp(this, 520),
                        ViewGroup.LayoutParams.MATCH_PARENT));

        left.addView(TvWidgets.bold(this, "搜索", 26f, Ui.TEXT));

        // 输入框：既显示已输入的词，也让外接键盘/输入法能直接打字
        inputView = TvWidgets.text(this, "", 22f, Ui.TEXT);
        inputView.setMinHeight(Ui.dp(this, 52));
        inputView.setGravity(Gravity.CENTER_VERTICAL);
        inputView.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        inputView.setBackground(Ui.roundStroke(Ui.BG2, 0xFF2A3242, 12, 1, this));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = Ui.dp(this, 14);
        inputView.setLayoutParams(ilp);
        inputView.setFocusable(true);
        inputView.setClickable(true);
        inputView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openIme();
            }
        });
        inputView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(Ui.roundStroke(Ui.BG2,
                        hasFocus ? Ui.AMBER : 0xFF2A3242, 12, 1, TvSearchActivity.this));
            }
        });
        left.addView(inputView);

        // 透明 EditText 承接系统输入法/实体键盘，不参与视觉
        hiddenInput = new EditText(this);
        hiddenInput.setLayoutParams(new LinearLayout.LayoutParams(2, 2));
        hiddenInput.setAlpha(0f);
        // 关键：让系统输入法能把中文写进来（平板的拼音键盘、外接键盘都走这里）
        hiddenInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                String text = e == null ? "" : e.toString();
                if (text.equals(query.toString())) return;
                query.setLength(0);
                query.append(text);
                renderInput();
            }
        });
        // setShowSoftInputOnFocus 是 API21+，API16 上只能靠不弹输入法。
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            hiddenInput.setShowSoftInputOnFocus(false);
        }
        hiddenInput.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    doSearch();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    backspace();
                    return true;
                }
                return false;
            }
        });
        left.addView(hiddenInput);

        statusView = TvWidgets.text(this, "", 14f, Ui.DIM2);
        statusView.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        statusView.setLineSpacing(Ui.dp(this, 3), 1f);
        left.addView(statusView);

        left.addView(TvWidgets.button(this, "搜索", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch();
            }
        }));

        if (portrait) {
            root.addView(left);
            View keyboard = buildKeyboard();
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            klp.topMargin = Ui.dp(this, 14);
            keyboard.setLayoutParams(klp);
            root.addView(keyboard);

            resultsScroll = new ScrollView(this);
            resultsScroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            resultsBody = TvWidgets.vbox(this);
            resultsScroll.addView(resultsBody);
            root.addView(resultsScroll);
        } else {
            root.addView(left);
            View keyboard = buildKeyboard();
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            klp.leftMargin = Ui.dp(this, 20);
            keyboard.setLayoutParams(klp);
            root.addView(keyboard);

            resultsScroll = new ScrollView(this);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            rlp.leftMargin = Ui.dp(this, 20);
            resultsScroll.setLayoutParams(rlp);
            resultsBody = TvWidgets.vbox(this);
            resultsScroll.addView(resultsBody);
            root.addView(resultsScroll);
        }
        return root;
    }

    private View buildKeyboard() {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);

        for (int r = 0; r < KEY_ROWS.length; r++) {
            pad.addView(buildKeyRow(KEY_ROWS[r]));
        }

        // 最后一排：空格 / 退格 / 清空
        LinearLayout last = new LinearLayout(this);
        last.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 62));
        llp.topMargin = Ui.dp(this, 6);
        last.setLayoutParams(llp);
        last.addView(keyButton("空格", 2f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                append(' ');
            }
        }));
        last.addView(keyButton("退格", 1.4f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backspace();
            }
        }));
        last.addView(keyButton("清空", 1.4f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                query.setLength(0);
                renderInput();
            }
        }));
        pad.addView(last);
        return pad;
    }

    private View buildKeyRow(String keys) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 62));
        rlp.topMargin = Ui.dp(this, 6);
        row.setLayoutParams(rlp);
        for (int i = 0; i < keys.length(); i++) {
            final char ch = keys.charAt(i);
            row.addView(keyButton(String.valueOf(ch), 1f, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    append(ch);
                }
            }));
        }
        return row;
    }

    private TextView keyButton(String label, float weight, View.OnClickListener onClick) {
        TextView key = TvWidgets.bold(this, label, label.length() > 1 ? 18f : 22f, Ui.TEXT);
        key.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.rightMargin = Ui.dp(this, 5);
        key.setLayoutParams(lp);
        key.setFocusable(true);
        key.setClickable(true);
        key.setBackground(keyBg(false));
        key.setOnClickListener(onClick);
        key.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(keyBg(hasFocus));
                ((TextView) v).setTextColor(hasFocus ? 0xFF0B0E14 : Ui.TEXT);
                if (hasFocus) TvWidgets.reveal(v);
            }
        });
        return key;
    }

    private GradientDrawable keyBg(boolean focused) {
        return focused ? Ui.round(Ui.AMBER, 10, this)
                : Ui.roundStroke(Ui.BG3, 0xFF2A3242, 10, 1, this);
    }

    /* ---------------- 输入 ---------------- */

    private void append(char ch) {
        if (query.length() >= 40) return;
        query.append(ch);
        renderInput();
    }

    private void backspace() {
        if (query.length() == 0) return;
        query.setLength(query.length() - 1);
        renderInput();
    }

    private void renderInput() {
        String text = query.toString();
        inputView.setText(text.length() == 0 ? "输入关键词" : text);
        inputView.setTextColor(text.length() == 0 ? Ui.DIM2 : Ui.TEXT);
        // 屏幕键盘改了词，同步回隐藏输入框，避免两个输入源状态分叉。
        if (hiddenInput != null && !text.equals(hiddenInput.getText().toString())) {
            hiddenInput.setText(text);
            hiddenInput.setSelection(text.length());
        }
        // 一开始输入就把上一次的提示清掉，避免「先输入关键词」和输入内容同时出现。
        if (text.length() > 0 && statusView != null) {
            statusView.setText("");
        }
    }

    /** 把焦点交给隐藏 EditText 并唤起系统输入法；没有输入法的电视上静默失败。 */
    private void openIme() {
        try {
            hiddenInput.requestFocus();
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(hiddenInput, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {
            // 无输入法设备走屏幕键盘。
        }
    }

    private void focusFirst() {
        renderInput();
        // 不主动弹输入法：电视上会白弹一次；平板/车机点输入框时再唤起（openIme）。
    }

    /* ---------------- 搜索 ---------------- */

    private void doSearch() {
        String kw = query.toString().trim();
        if (kw.length() == 0) {
            statusView.setText("先输入关键词");
            statusView.setTextColor(Ui.RED);
            return;
        }
        if (searching) return;
        searching = true;

        boolean kid = KidMode.enabled();
        List<Source> pool = new ArrayList<Source>();
        for (Source s : Sources.searchable()) {
            // 青少年模式只保留有儿童分类的源
            if (!kid || !KidMode.allowedLeafIds(s.id()).isEmpty()) pool.add(s);
        }
        if (pool.isEmpty()) {
            searching = false;
            statusView.setText("当前没有可搜索的内容源，先到「我的」添加订阅");
            statusView.setTextColor(Ui.RED);
            return;
        }

        final int generation = ++searchGeneration;
        resultsBody.removeAllViews();
        resultsBody.addView(TvWidgets.loading(this, "正在搜索「" + kw + "」…"));
        statusView.setText("搜索中…");
        statusView.setTextColor(Ui.DIM2);

        final List<Book> merged = new ArrayList<Book>();
        final int[] done = {0};
        final int total = pool.size();

        for (final Source source : pool) {
            Bg.run(new Callable<List<Book>>() {
                @Override
                public List<Book> call() throws Exception {
                    // 青少年模式只翻白名单分类做本地匹配，杜绝跨分类内容
                    return KidMode.enabled() ? KidSearch.searchSource(source, kw)
                            : source.search(kw, 1);
                }
            }, new Bg.Cb<List<Book>>() {
                @Override
                public void ok(List<Book> value) {
                    if (generation != searchGeneration || isFinishing()) return;
                    if (value != null) merged.addAll(value);
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
        if (isFinishing()) return;
        boolean kid = KidMode.enabled();
        List<Book> view = KidMode.filterSearchResults(new ArrayList<Book>(merged));

        if (done < total) {
            statusView.setText("已搜 " + done + "/" + total + " 个源，命中 " + view.size() + " 本");
            statusView.setTextColor(Ui.DIM2);
            return;
        }

        searching = false;
        resultsBody.removeAllViews();
        if (view.isEmpty()) {
            statusView.setText(kid
                    ? "「" + kw + "」在儿童分类里没有找到。青少年模式只搜儿童内容"
                    : "「" + kw + "」没有结果，换个关键词试试");
            statusView.setTextColor(kid ? Ui.TEAL : Ui.DIM2);
            resultsBody.addView(TvWidgets.empty(this, kid
                    ? "青少年模式下\n只显示儿童内容"
                    : "换个关键词试试"));
            return;
        }

        statusView.setText("「" + kw + "」共 " + view.size() + " 本"
                + (kid ? " · 仅儿童内容" : ""));
        statusView.setTextColor(kid ? Ui.TEAL : Ui.DIM2);

        LinearLayout list = TvWidgets.vbox(this);
        for (final Book book : view) {
            list.addView(TvWidgets.bookRow(this, book, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Nav.openDetail(TvSearchActivity.this, book);
                }
            }));
        }
        resultsBody.addView(list);
        resultsScroll.post(new Runnable() {
            @Override
            public void run() {
                resultsScroll.scrollTo(0, 0);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 有已提交的结果时，BACK 先退回「改关键词」的状态，再退一次才离开，
        // 避免遥控用户一不小心就退出搜索、得从头再输入一遍。
        if (keyCode == KeyEvent.KEYCODE_BACK && !searching
                && resultsBody != null && resultsBody.getChildCount() > 0) {
            resultsBody.removeAllViews();
            statusView.setText("");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 供主界面跳转用。 */
    public static void start(Context context) {
        if (context == null) return;
        context.startActivity(new android.content.Intent(context, TvSearchActivity.class));
    }
}
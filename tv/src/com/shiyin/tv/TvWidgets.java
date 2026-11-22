package com.shiyin.tv;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.shiyin.app.model.Book;
import com.shiyin.app.util.Ui;

/**
 * TV 专用控件库。和手机版 ui.Widgets 是两套东西：
 * 手机版用 RippleDrawable（API21），TV 版必须兼容 API16，只用 GradientDrawable 切换焦点态。
 *
 * <p>所有可点控件统一：
 * <ul>
 *   <li>focusable，D-pad 可聚焦；</li>
 *   <li>获得焦点时琥珀色描边 + 提亮底色，一眼能看出焦点在哪；</li>
 *   <li>获得焦点时主动请求父容器滚动到可见区域（遥控器长列表必须）。</li>
 * </ul>
 */
public final class TvWidgets {

    public static final int FOCUS_FILL = 0xFF26324A;
    public static final int FOCUS_BORDER = Ui.AMBER;

    private TvWidgets() {
    }

    /* ---------------- 基础 ---------------- */

    public static void setBg(View v, Drawable d) {
        if (Build.VERSION.SDK_INT >= 16) v.setBackground(d);
        else v.setBackgroundDrawable(d);
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, String s, float sp, int color) {
        return Ui.text(c, s, sp, color);
    }

    public static TextView bold(Context c, String s, float sp, int color) {
        TextView t = Ui.text(c, s, sp, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static View spacer(Context c, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(c, hDp)));
        return v;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0xFF202634);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 1)));
        lp.leftMargin = Ui.dp(c, 20);
        lp.rightMargin = Ui.dp(c, 20);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * 把一个 View 变成 D-pad 可聚焦控件，并在焦点变化时切换背景。
     */
    public static void focusable(final View v, int normalFill, int focusedFill, float radiusDp) {
        final Context c = v.getContext();
        final GradientDrawable normal = Ui.round(normalFill, radiusDp, c);
        final GradientDrawable focused = Ui.roundStroke(focusedFill, FOCUS_BORDER, radiusDp, 2f, c);
        v.setFocusable(true);
        v.setClickable(true);
        setBg(v, normal);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                setBg(view, hasFocus ? focused : normal);
                if (hasFocus) reveal(view);
            }
        });
    }

    /** 让焦点控件在滚动容器里可见（API16 上 ScrollView/HorizontalScrollView 都认这个调用）。 */
    public static void reveal(View v) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        v.requestRectangleOnScreen(new Rect(0, 0, v.getWidth(), v.getHeight()), false);
    }

    /* ---------------- 文本块 ---------------- */

    public static View sectionTitle(Context c, String title, String action, View.OnClickListener onAction) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 24), Ui.dp(c, 22), Ui.dp(c, 24), Ui.dp(c, 10));

        View bar = new View(c);
        setBg(bar, Ui.round(Ui.AMBER, 2, c));
        bar.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 4), Ui.dp(c, 20)));
        row.addView(bar);

        TextView t = bold(c, title, 18f, Ui.TEXT);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(c, 10);
        t.setLayoutParams(tlp);
        row.addView(t);

        if (action != null && onAction != null) {
            TextView a = text(c, action, 14f, Ui.TEXT);
            a.setPadding(Ui.dp(c, 18), Ui.dp(c, 8), Ui.dp(c, 18), Ui.dp(c, 8));
            a.setMinHeight(Ui.dp(c, 48));
            a.setGravity(Gravity.CENTER);
            a.setOnClickListener(onAction);
            focusable(a, 0xFF1C2230, FOCUS_FILL, 22);
            row.addView(a);
        }
        return row;
    }

    public static TextView hint(Context c, String s) {
        TextView t = Ui.text(c, s, 14f, Ui.DIM2);
        t.setLineSpacing(Ui.dp(c, 4), 1f);
        t.setPadding(Ui.dp(c, 24), Ui.dp(c, 2), Ui.dp(c, 24), Ui.dp(c, 10));
        return t;
    }

    public static View empty(Context c, String msg) {
        LinearLayout box = vbox(c);
        box.setGravity(Gravity.CENTER);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setPadding(Ui.dp(c, 32), Ui.dp(c, 48), Ui.dp(c, 32), Ui.dp(c, 24));
        TextView t = Ui.text(c, msg, 16f, Ui.DIM);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(Ui.dp(c, 6), 1f);
        box.addView(t);
        return box;
    }

    public static View loading(Context c, String msg) {
        TextView t = Ui.text(c, msg, 16f, Ui.DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(c, 48), 0, Ui.dp(c, 48));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /* ---------------- 按钮 ---------------- */

    public static TextView button(Context c, String label, boolean primary, View.OnClickListener onClick) {
        TextView t = Ui.text(c, label, 16f, primary ? 0xFF0B0E14 : Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(c, 26), Ui.dp(c, 12), Ui.dp(c, 26), Ui.dp(c, 12));
        t.setMinHeight(Ui.dp(c, 52));
        t.setMinWidth(Ui.dp(c, 120));
        int fill = primary ? Ui.AMBER : Ui.BG3;
        if (primary) {
            setBg(t, Ui.round(fill, 26, c));
            t.setFocusable(true);
            t.setClickable(true);
            t.setOnClickListener(onClick);
            focusablePrimary(t, fill);
        } else {
            t.setOnClickListener(onClick);
            focusable(t, fill, FOCUS_FILL, 26);
        }
        return t;
    }

    private static void focusablePrimary(final TextView t, final int fill) {
        final Context c = t.getContext();
        final GradientDrawable normal = Ui.round(fill, 26, c);
        final GradientDrawable focused = Ui.roundStroke(0xFFFFC24B, Ui.TEXT, 26, 3f, c);
        setBg(t, normal);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                setBg(v, hasFocus ? focused : normal);
                if (hasFocus) reveal(v);
            }
        });
    }

    public static TextView chip(Context c, String label, boolean selected, View.OnClickListener onClick) {
        TextView t = Ui.text(c, label, 15f, selected ? 0xFF0B0E14 : Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(c, 20), Ui.dp(c, 10), Ui.dp(c, 20), Ui.dp(c, 10));
        t.setMinHeight(Ui.dp(c, 48));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setOnClickListener(onClick);
        focusable(t, selected ? Ui.AMBER : Ui.BG3, selected ? 0xFFFFC24B : FOCUS_FILL, 24);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(c, 10);
        t.setLayoutParams(lp);
        return t;
    }

    /* ---------------- 书卡 ---------------- */

    public static View bookCard(Context c, Book b, int widthDp, int coverHeightDp,
                                View.OnClickListener onClick) {
        LinearLayout box = vbox(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(c, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(c, 6);
        box.setLayoutParams(lp);
        box.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 12));
        box.setOnClickListener(onClick);
        focusable(box, 0xFF121722, FOCUS_FILL, 14);

        TvCover cover = new TvCover(c);
        cover.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, coverHeightDp)));
        cover.load(b.cover);
        box.addView(cover);

        TextView t = Ui.text(c, b.title, 15f, Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, Ui.dp(c, 10), 0, 0);
        t.setMinHeight(Ui.dp(c, 40));
        box.addView(t);

        TextView sub = Ui.text(c, subtitle(b), 14f, Ui.DIM);
        sub.setMaxLines(1);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, Ui.dp(c, 3), 0, 0);
        box.addView(sub);
        return box;
    }

    public static View bookRow(Context c, Book b, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 22), Ui.dp(c, 12), Ui.dp(c, 22), Ui.dp(c, 12));
        row.setMinimumHeight(Ui.dp(c, 96));
        row.setOnClickListener(onClick);
        focusable(row, Ui.BG, FOCUS_FILL, 14);

        TvCover cover = new TvCover(c);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 76));
        cover.setLayoutParams(clp);
        cover.load(b.cover);
        row.addView(cover);

        LinearLayout col = vbox(c);
        LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colp.leftMargin = Ui.dp(c, 16);
        col.setLayoutParams(colp);

        TextView title = Ui.text(c, b.title, 17f, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);

        String line2 = join(b.narrator, b.author);
        if (line2.length() > 0) {
            TextView s = Ui.text(c, line2, 14f, Ui.DIM);
            s.setMaxLines(1);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setPadding(0, Ui.dp(c, 4), 0, 0);
            col.addView(s);
        }

        String line3 = join(b.status, TvWidgets.srcName(b.source));
        if (line3.length() > 0) {
            TextView s = Ui.text(c, line3, 14f, Ui.DIM2);
            s.setMaxLines(1);
            s.setPadding(0, Ui.dp(c, 4), 0, 0);
            col.addView(s);
        }
        row.addView(col);
        row.addView(Ui.text(c, "›", 20f, Ui.DIM2));
        return row;
    }

    /* ---------------- 设置行 ---------------- */

    public static View actionRow(Context c, String title, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 22), Ui.dp(c, 12), Ui.dp(c, 22), Ui.dp(c, 12));
        row.setMinimumHeight(Ui.dp(c, 60));
        row.setOnClickListener(onClick);
        focusable(row, Ui.BG, FOCUS_FILL, 12);

        TextView t = Ui.text(c, title, 16f, Ui.TEXT);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        if (value != null && value.length() > 0) {
            TextView v = Ui.text(c, value, 14f, Ui.DIM2);
            v.setMaxLines(1);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0);
            row.addView(v);
        }
        row.addView(Ui.text(c, "›", 20f, Ui.DIM2));
        return row;
    }

    public static View infoRow(Context c, String title, String value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 22), Ui.dp(c, 12), Ui.dp(c, 22), Ui.dp(c, 12));
        row.setMinimumHeight(Ui.dp(c, 56));

        LinearLayout col = vbox(c);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = Ui.text(c, title, 16f, Ui.TEXT);
        col.addView(t);
        if (value != null && value.length() > 0) {
            TextView v = Ui.text(c, value, 14f, Ui.DIM2);
            v.setPadding(0, Ui.dp(c, 3), 0, 0);
            col.addView(v);
        }
        row.addView(col);
        return row;
    }

    /* ---------------- 容器 ---------------- */

    public static LinearLayout verticalScroll(Context c, LinearLayout body) {
        LinearLayout page = vbox(c);
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        // ScrollView 自己 focusable=true，焦点会停在容器上导致确认键没反应。
        sv.setFocusable(false);
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    public static HorizontalScrollView hscroll(Context c, View content) {
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        h.setFocusable(false);
        h.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
        h.addView(content, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return h;
    }

    /* ---------------- 杂项 ---------------- */

    public static String srcName(String id) {
        try {
            com.shiyin.app.source.Source s = com.shiyin.app.source.Sources.byId(id);
            return s == null ? "" : s.name();
        } catch (Exception e) {
            return "";
        }
    }

    private static String subtitle(Book b) {
        String s = join(b.narrator, b.author);
        if (s.length() == 0) s = b.status;
        if (s.length() == 0) s = srcName(b.source);
        return s;
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.length() > 0 && x.equals(y)) return x;
        if (x.length() > 0 && y.length() > 0) return x + " · " + y;
        return x.length() > 0 ? x : y;
    }
}

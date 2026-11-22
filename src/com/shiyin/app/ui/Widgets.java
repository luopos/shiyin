package com.shiyin.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shiyin.app.model.Book;
import com.shiyin.app.source.Source;
import com.shiyin.app.source.Sources;
import com.shiyin.app.util.ImgView;
import com.shiyin.app.util.Ui;

/** 全代码建 UI 的零件库：书卡、书行、标题、标签。 */
public final class Widgets {

    private Widgets() {
    }

    /* ---------------- 封面 ---------------- */

    public static ImgView cover(Context c, String url, int wDp, int hDp, float radius) {
        ImgView iv = new ImgView(c);
        iv.radius(radius);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(c, wDp), Ui.dp(c, hDp));
        iv.setLayoutParams(lp);
        iv.load(url);
        return iv;
    }

    /* ---------------- 竖版书卡（横向滚动里用） ---------------- */

    public static View bookCard(Context c, Book b, int widthDp, View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new ViewGroup.LayoutParams(
                Ui.dp(c, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setPadding(Ui.dp(c, 5), Ui.dp(c, 5), Ui.dp(c, 5), Ui.dp(c, 5));
        box.setOnClickListener(onClick);
        box.setClickable(true);

        ImgView iv = new ImgView(c);
        iv.radius(10);
        iv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, widthDp * 1.32f)));
        iv.load(b.cover);
        box.addView(iv);

        TextView t = Ui.text(c, b.title, 12.5f, Ui.TEXT);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(0, Ui.dp(c, 6), 0, 0);
        box.addView(t);

        String sub = b.narrator.length() > 0 ? b.narrator : b.author;
        if (sub.length() > 0) {
            TextView s = Ui.text(c, sub, 10.5f, Ui.DIM2);
            s.setMaxLines(1);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(s);
        }
        return box;
    }

    /* ---------------- 横版书行（列表里用） ---------------- */

    /**
     * 搜索历史行：只有关键词，没有封面。
     *
     * <p>历史项不是书目，之前复用 {@link #bookRow} 会占一个 58x77 的封面位、
     * 显示成空白方块。这里改成放大镜图标 + 关键词，语义和视觉都对齐。
     */
    public static View historyRow(Context c, String keyword, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setClickable(true);
        row.setOnClickListener(onClick);
        row.setBackground(ripple(Ui.BG));

        TextView icon = Ui.text(c, "\uD83D\uDD0D", 13f, Ui.DIM2);
        icon.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 24),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(icon);

        TextView t = Ui.text(c, keyword, 14.5f, Ui.TEXT);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(c, 8);
        t.setLayoutParams(lp);
        row.addView(t);

        TextView arrow = Ui.text(c, "\u203A", 15f, Ui.DIM2);
        row.addView(arrow);
        return row;
    }

    public static View bookRow(Context c, Book b, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, 9), Ui.dp(c, 14), Ui.dp(c, 9));
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setClickable(true);
        row.setOnClickListener(onClick);
        row.setBackground(ripple(Ui.BG));

        ImgView iv = new ImgView(c);
        iv.radius(8);
        iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 58), Ui.dp(c, 77)));
        iv.load(b.cover);
        row.addView(iv);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(c, 11);
        col.setLayoutParams(clp);

        TextView t = Ui.text(c, b.title, 14.5f, Ui.TEXT);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);

        String line2 = join(b.narrator, b.author);
        if (line2.length() > 0) {
            TextView s = Ui.text(c, line2, 11.5f, Ui.DIM);
            s.setMaxLines(1);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            s.setPadding(0, Ui.dp(c, 3), 0, 0);
            col.addView(s);
        }

        String line3 = join(b.status, srcName(b.source));
        if (line3.length() > 0) {
            TextView s = Ui.text(c, line3, 11f, Ui.DIM2);
            s.setMaxLines(1);
            s.setPadding(0, Ui.dp(c, 3), 0, 0);
            col.addView(s);
        }
        row.addView(col);
        return row;
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.length() > 0 && y.length() > 0) return x + " · " + y;
        return x.length() > 0 ? x : y;
    }

    public static String srcName(String id) {
        try {
            Source s = Sources.byId(id);
            return s == null ? "" : s.name();
        } catch (Exception e) {
            return "";
        }
    }

    /* ---------------- 区块标题 ---------------- */

    public static View sectionHeader(Context c, String title, String action,
                                     View.OnClickListener onAction) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 8));

        View bar = new View(c);
        bar.setBackground(Ui.round(Ui.AMBER, 2, c));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                Ui.dp(c, 3), Ui.dp(c, 15));
        bar.setLayoutParams(blp);
        row.addView(bar);

        TextView t = Ui.bold(c, title, 16f, Ui.TEXT);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(c, 8);
        t.setLayoutParams(tlp);
        row.addView(t);

        if (action != null) {
            TextView a = Ui.text(c, action, 12f, Ui.DIM);
            a.setPadding(Ui.dp(c, 8), 0, 0, 0);
            a.setOnClickListener(onAction);
            row.addView(a);
        }
        return row;
    }

    /* ---------------- 横向滚动容器 ---------------- */

    public static HorizontalScrollView hscroll(Context c, View content) {
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        h.setPadding(Ui.dp(c, 11), 0, Ui.dp(c, 11), 0);
        h.addView(content);
        return h;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        return box;
    }

    /* ---------------- 标签 chip ---------------- */

    public static TextView chip(Context c, String text, boolean selected,
                                View.OnClickListener onClick) {
        TextView t = Ui.text(c, text, 12.5f, selected ? 0xFF0B0E14 : Ui.DIM);
        t.setPadding(Ui.dp(c, 13), Ui.dp(c, 6), Ui.dp(c, 13), Ui.dp(c, 6));
        t.setBackground(selected ? Ui.round(Ui.AMBER, 14, c) : Ui.round(Ui.BG3, 14, c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(c, 7);
        t.setLayoutParams(lp);
        t.setOnClickListener(onClick);
        return t;
    }

    /* ---------------- 按钮 ---------------- */

    public static TextView button(Context c, String text, boolean primary,
                                  View.OnClickListener onClick) {
        TextView t = Ui.text(c, text, 13.5f, primary ? 0xFF0B0E14 : Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(c, 18), Ui.dp(c, 10), Ui.dp(c, 18), Ui.dp(c, 10));
        t.setBackground(primary ? Ui.round(Ui.AMBER, 20, c)
                : Ui.roundStroke(Ui.BG3, 0xFF2A3242, 20, 1, c));
        t.setOnClickListener(onClick);
        return t;
    }

    /* ---------------- 杂项 ---------------- */

    public static android.graphics.drawable.Drawable ripple(int color) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF),
                new android.graphics.drawable.ColorDrawable(color), null);
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout fill(Context c, View v) {
        LinearLayout l = vbox(c);
        l.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        l.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    public static TextView empty(Context c, String msg) {
        TextView t = Ui.text(c, msg, 13f, Ui.DIM2);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(c, 20), Ui.dp(c, 60), Ui.dp(c, 20), Ui.dp(c, 60));
        return t;
    }

    public static TextView loading(Context c, String msg) {
        TextView t = Ui.text(c, msg, 13f, Ui.DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(c, 60), 0, Ui.dp(c, 60));
        return t;
    }
}

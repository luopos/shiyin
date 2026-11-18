package com.shiyin.app.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

/** 颜色、尺寸、圆角背景、Toast —— 全代码建 UI 要用的零碎。 */
public final class Ui {

    public static final int BG = 0xFF0B0E14;
    public static final int BG2 = 0xFF141922;
    public static final int BG3 = 0xFF1C2230;
    public static final int AMBER = 0xFFF5A524;
    public static final int TEAL = 0xFF4FD1C5;
    public static final int TEXT = 0xFFE9EEF7;
    public static final int DIM = 0xFF8A96A8;
    public static final int DIM2 = 0xFF5C6675;
    public static final int RED = 0xFFF0555A;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int sp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics());
    }

    /** 圆角纯色背景。 */
    public static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** 圆角描边背景。 */
    public static GradientDrawable roundStroke(int fill, int strokeColor, float radiusDp,
                                               float strokeDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    /** 顶部/底部圆角。 */
    public static GradientDrawable roundTop(int color, float radiusDp, Context c) {
        float r = dp(c, radiusDp);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return d;
    }

    public static TextView text(Context c, String s, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    public static TextView bold(Context c, String s, float sizeSp, int color) {
        TextView t = text(c, s, sizeSp, color);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    public static View spacer(Context c, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new ViewGroup.LayoutParams(1, dp(c, hDp)));
        return v;
    }

    public static View hline(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f))));
        return v;
    }

    public static void toast(Context c, String s) {
        if (c == null || s == null) return;
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    /** 从 #RRGGBB 解析，失败给 fallback。 */
    public static int parseColor(String s, int fallback) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 把秒数格式化成 mm:ss / h:mm:ss。 */
    public static String time(int seconds) {
        if (seconds < 0) seconds = 0;
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    /** 毫秒版。 */
    public static String timeMs(int ms) {
        return time(ms / 1000);
    }
}

package com.shiyin.tv;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.shiyin.app.kid.KidMode;
import com.shiyin.app.util.Ui;

/**
 * TV / 车机 / 平板上的青少年模式管理面板。
 *
 * <p>电视没有软键盘，所以密码一律用屏幕数字键盘输入：九宫格 0-9 + 清除 + 确认，
 * 每个键既可遥控聚焦确认，也可手指直接点。掩码只显示位数，不回显明文。
 *
 * <p>流程严格跟随 {@link KidMode} 的真实语义：
 * <ul>
 *   <li>没有密码 → 设置新密码（输两次确认）；</li>
 *   <li>有密码但未开启 → 输密码开启；</li>
 *   <li>已开启 → 输密码关闭，或修改密码（旧 + 新 + 确认）。</li>
 * </ul>
 *
 * <p>注意 {@code setPassword} 在已有密码时返回 false（刻意设计，防止不验证就重置），
 * 所以「设置」只在 {@code hasPassword()==false} 时进入。
 */
public final class TvKidGate {

    private static final int MIN_LEN = 4;
    private static final int MAX_LEN = 16;

    private enum Step {
        SET_NEW,      // 首次设置：输入新密码
        SET_CONFIRM,  // 首次设置：再输一次确认
        UNLOCK,       // 关闭 / 开启：验证密码
        OLD_PWD,      // 改密：验证旧密码
        NEW_PWD,      // 改密：输入新密码
        NEW_CONFIRM   // 改密：确认新密码
    }

    private final Activity activity;
    private final Runnable onModeChanged;
    private final Dialog dialog;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Step step = Step.UNLOCK;
    private final StringBuilder buffer = new StringBuilder();
    private String staged;             // SET_CONFIRM / NEW_CONFIRM 阶段暂存第一次输入
    private String verifiedOld;        // 改密流程里已校验通过的旧密码

    private TextView maskView;
    private TextView statusView;
    private TextView titleView;
    private LinearLayout optionBox;
    private LinearLayout padBox;
    private final TextView[] digits = new TextView[10];
    private TextView clearKey;
    private TextView okKey;

    private final KidMode.Listener listener = new KidMode.Listener() {
        @Override
        public void onKidModeChanged(boolean enabled) {
            // 模式由别的入口改变时，本面板直接收掉，避免状态错位。
            if (dialog.isShowing()) dismiss(true);
        }
    };

    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            int left = KidMode.lockRemainingSeconds();
            if (left > 0) {
                status("密码错误次数过多，请 " + left + " 秒后再试", Ui.RED);
                setPadEnabled(false);
                handler.postDelayed(this, 1000);
            } else {
                setPadEnabled(true);
                if (statusView != null) status("", Ui.DIM);
            }
        }
    };

    private TvKidGate(Activity activity, Runnable onModeChanged) {
        this.activity = activity;
        this.onModeChanged = onModeChanged;
        this.dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    }

    /** 打开管理面板；模式变化后回调 onModeChanged，由调用方刷新界面。 */
    public static void open(Activity activity, Runnable onModeChanged) {
        if (activity == null || activity.isFinishing()) return;
        new TvKidGate(activity, onModeChanged).show();
    }

    /* ---------------- 面板骨架 ---------------- */

    private void show() {
        dialog.setContentView(build());
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        KidMode.addListener(listener);
        chooseEntry();
    }

    private View build() {
        boolean portrait = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(activity, 24), Ui.dp(activity, 20),
                Ui.dp(activity, 24), Ui.dp(activity, 20));

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(portrait
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        if (!portrait) info.setPadding(0, 0, Ui.dp(activity, 24), 0);

        titleView = TvWidgets.bold(activity, "青少年模式", 24f, Ui.AMBER);
        info.addView(titleView);

        maskView = TvWidgets.text(activity, "", 30f, Ui.TEXT);
        maskView.setPadding(0, Ui.dp(activity, 16), 0, 0);
        maskView.setMinHeight(Ui.dp(activity, 44));
        info.addView(maskView);

        statusView = TvWidgets.text(activity, "", 15f, Ui.DIM);
        statusView.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 14));
        statusView.setLineSpacing(Ui.dp(activity, 3), 1f);
        info.addView(statusView);

        // 「关闭 / 改密」二选一的入口区（仅已开启时显示）
        optionBox = new LinearLayout(activity);
        optionBox.setOrientation(LinearLayout.VERTICAL);
        optionBox.setVisibility(View.GONE);
        info.addView(optionBox);

        TextView cancel = TvWidgets.button(activity, "返回", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss(false);
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(activity, 12);
        cancel.setLayoutParams(clp);
        info.addView(cancel);

        root.addView(info);

        ScrollView padScroll = new ScrollView(activity);
        padScroll.setLayoutParams(portrait
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                : new LinearLayout.LayoutParams(Ui.dp(activity, 360),
                        ViewGroup.LayoutParams.MATCH_PARENT));
        padBox = buildPad();
        padScroll.addView(padBox);
        root.addView(padScroll);
        return root;
    }

    private LinearLayout buildPad() {
        LinearLayout pad = new LinearLayout(activity);
        pad.setOrientation(LinearLayout.VERTICAL);
        String[][] layout = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"清除", "0", "确认"}
        };
        for (int r = 0; r < layout.length; r++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // 注意：父容器是 ScrollView，子项高度用 0 + weight 会塌陷成 0，
            // 必须给固定高度，由键位自身的最小高度撑开。
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 76));
            rlp.bottomMargin = Ui.dp(activity, 8);
            row.setLayoutParams(rlp);
            for (int c = 0; c < layout[r].length; c++) {
                final String label = layout[r][c];
                TextView key = TvWidgets.bold(activity, label, 26f, Ui.TEXT);
                key.setGravity(Gravity.CENTER);
                key.setMinimumHeight(Ui.dp(activity, 64));
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                klp.rightMargin = c < 2 ? Ui.dp(activity, 8) : 0;
                key.setLayoutParams(klp);
                key.setFocusable(true);
                key.setClickable(true);
                key.setBackground(keyBg(false));
                final TextView self = key;
                key.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onKey(label, self);
                    }
                });
                key.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        self.setBackground(keyBg(hasFocus));
                        self.setTextColor(hasFocus ? 0xFF0B0E14 : Ui.TEXT);
                        if (hasFocus) TvWidgets.reveal(v);
                    }
                });
                if ("清除".equals(label)) clearKey = key;
                else if ("确认".equals(label)) okKey = key;
                else digits[Integer.parseInt(label)] = key;
                row.addView(key);
            }
            pad.addView(row);
        }
        return pad;
    }

    private GradientDrawable keyBg(boolean focused) {
        return focused ? Ui.round(Ui.AMBER, 12, activity)
                : Ui.roundStroke(Ui.BG3, 0xFF2A3242, 12, 1, activity);
    }

    /* ---------------- 流程 ---------------- */

    private void chooseEntry() {
        if (!KidMode.hasPassword()) {
            step = Step.SET_NEW;
            title("青少年模式 · 设置密码");
            status("设置一个 4-16 位密码。开启和关闭都需要它。", Ui.DIM);
            showPad(true);
            showOptions(false);
            return;
        }
        if (KidMode.enabled()) {
            title("青少年模式已开启");
            status("只显示儿童分类内容。关闭需要密码。", Ui.DIM);
            showEnterOptions();
            return;
        }
        step = Step.UNLOCK;
        title("开启青少年模式");
        status("输入密码后开启。开启后只显示儿童内容。", Ui.DIM);
        showPad(true);
        showOptions(false);
    }

    private void showEnterOptions() {
        optionBox.removeAllViews();
        optionBox.setVisibility(View.VISIBLE);
        optionBox.addView(TvWidgets.actionRow(activity, "关闭青少年模式", "需要输入密码", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step = Step.UNLOCK;
                title("关闭青少年模式");
                status("输入密码以关闭。", Ui.DIM);
                showPad(true);
                showOptions(false);
            }
        }));
        optionBox.addView(TvWidgets.actionRow(activity, "修改密码", "需要先验证旧密码", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step = Step.OLD_PWD;
                title("修改密码");
                status("先输入旧密码。", Ui.DIM);
                showPad(true);
                showOptions(false);
            }
        }));
        showPad(false);
    }

    private void showPad(boolean visible) {
        padBox.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            clearBuffer();
            setPadEnabled(KidMode.lockRemainingSeconds() <= 0);
        }
    }

    private void showOptions(boolean visible) {
        optionBox.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void onKey(String label, TextView self) {
        if ("清除".equals(label)) {
            if (buffer.length() > 0) buffer.setLength(buffer.length() - 1);
            renderMask();
            return;
        }
        if ("确认".equals(label)) {
            submit();
            return;
        }
        if (buffer.length() >= MAX_LEN) {
            status("密码最长 " + MAX_LEN + " 位", Ui.RED);
            return;
        }
        buffer.append(label);
        renderMask();
        // 触摸/遥控都要有可见反馈
        self.setBackground(Ui.round(Ui.AMBER, 12, activity));
        self.setTextColor(0xFF0B0E14);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (self.hasFocus()) {
                    self.setBackground(keyBg(true));
                    self.setTextColor(0xFF0B0E14);
                } else {
                    self.setBackground(keyBg(false));
                    self.setTextColor(Ui.TEXT);
                }
            }
        }, 120);
    }

    private void renderMask() {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < buffer.length(); i++) dots.append('●');
        maskView.setText(dots.length() == 0 ? "—" : dots.toString());
    }

    private void clearBuffer() {
        buffer.setLength(0);
        renderMask();
    }

    private void submit() {
        String input = buffer.toString();
        if (input.length() < MIN_LEN) {
            status("密码至少 " + MIN_LEN + " 位", Ui.RED);
            return;
        }
        switch (step) {
            case SET_NEW:
                staged = input;
                step = Step.SET_CONFIRM;
                clearBuffer();
                title("青少年模式 · 确认密码");
                status("再输入一次以确认。", Ui.DIM);
                return;
            case SET_CONFIRM:
                if (!input.equals(staged)) {
                    staged = null;
                    step = Step.SET_NEW;
                    clearBuffer();
                    title("青少年模式 · 设置密码");
                    status("两次输入不一致，请重新设置。", Ui.RED);
                    return;
                }
                if (!KidMode.setPassword(input)) {
                    // 已有密码时 setPassword 拒绝，属正常防护，引导去改密。
                    title("青少年模式");
                    status("已存在密码。请关闭面板后用「修改密码」。", Ui.RED);
                    showPad(false);
                    showOptions(false);
                    return;
                }
                staged = null;
                clearBuffer();
                askEnableNow(input);
                return;
            case UNLOCK:
                boolean wantEnable = !KidMode.enabled();
                boolean ok = wantEnable ? KidMode.enableWith(input) : KidMode.disableWith(input);
                if (!ok) {
                    onWrongPassword();
                    return;
                }
                clearBuffer();
                banner(wantEnable ? "已开启青少年模式，仅显示儿童内容。" : "已关闭青少年模式。");
                return;
            case OLD_PWD:
                if (!KidMode.verify(input)) {
                    onWrongPassword();
                    return;
                }
                verifiedOld = input;
                step = Step.NEW_PWD;
                clearBuffer();
                title("修改密码");
                status("输入新密码。", Ui.DIM);
                return;
            case NEW_PWD:
                staged = input;
                step = Step.NEW_CONFIRM;
                clearBuffer();
                title("修改密码");
                status("再输入一次新密码。", Ui.DIM);
                return;
            case NEW_CONFIRM:
                if (!input.equals(staged)) {
                    staged = null;
                    step = Step.NEW_PWD;
                    clearBuffer();
                    title("修改密码");
                    status("两次输入不一致，请重新输入新密码。", Ui.RED);
                    return;
                }
                if (!KidMode.changePassword(verifiedOld, input)) {
                    verifiedOld = null;
                    staged = null;
                    clearBuffer();
                    title("修改密码");
                    status("修改失败，请重新验证旧密码。", Ui.RED);
                    step = Step.OLD_PWD;
                    return;
                }
                verifiedOld = null;
                staged = null;
                clearBuffer();
                banner("密码已修改。");
                return;
            default:
                dismiss(false);
        }
    }

    private void askEnableNow(final String password) {
        showPad(false);
        optionBox.removeAllViews();
        optionBox.setVisibility(View.VISIBLE);
        title("密码已设置");
        status("要现在开启青少年模式吗？", Ui.DIM);
        optionBox.addView(TvWidgets.actionRow(activity, "立即开启", "只显示儿童内容", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (KidMode.enableWith(password)) banner("已开启青少年模式，仅显示儿童内容。");
                else banner("开启失败，请重试。");
            }
        }));
        optionBox.addView(TvWidgets.actionRow(activity, "稍后再说", "下次从「我的」进入", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss(true);
            }
        }));
    }

    private void onWrongPassword() {
        buffer.setLength(0);
        renderMask();
        int left = KidMode.lockRemainingSeconds();
        if (left > 0) {
            status("密码错误次数过多，请 " + left + " 秒后再试", Ui.RED);
            setPadEnabled(false);
            handler.removeCallbacks(countdown);
            handler.postDelayed(countdown, 1000);
        } else {
            status("密码错误，请重试。", Ui.RED);
        }
    }

    private void banner(String text) {
        status(text, Ui.TEAL);
        showPad(false);
        showOptions(false);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dismiss(true);
            }
        }, 1200);
    }

    private void setPadEnabled(boolean enabled) {
        for (TextView key : digits) if (key != null) key.setEnabled(enabled);
        if (clearKey != null) clearKey.setEnabled(enabled);
        if (okKey != null) okKey.setEnabled(enabled);
        float alpha = enabled ? 1f : 0.4f;
        if (padBox != null) padBox.setAlpha(alpha);
    }

    private void title(String text) {
        if (titleView != null) titleView.setText(text);
    }

    private void status(String text, int color) {
        if (statusView == null) return;
        statusView.setText(text);
        statusView.setTextColor(color);
    }

    private void dismiss(boolean changed) {
        handler.removeCallbacks(countdown);
        KidMode.removeListener(listener);
        if (dialog.isShowing()) dialog.dismiss();
        if (changed && onModeChanged != null) onModeChanged.run();
    }
}
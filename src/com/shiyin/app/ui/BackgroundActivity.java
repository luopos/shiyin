package com.shiyin.app.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.shiyin.app.store.Prefs;
import com.shiyin.app.util.Ui;

/** 后台播放检查与系统授权入口。厂商自启动状态没有统一读取接口，不伪报已开启。 */
public final class BackgroundActivity extends Activity {
    private static final int NOTIFICATION_REQUEST = 101;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    public static void showHintOnce(final Activity activity) {
        if (Prefs.backgroundHintShown() || activity.isFinishing() || activity.isDestroyed()) return;
        Prefs.markBackgroundHintShown();
        new AlertDialog.Builder(activity).setTitle("保持后台收听")
                .setMessage("播放时会显示通知栏控制。若锁屏后中断，请在后台播放设置中开启通知、放行电池优化，并按手机系统提示允许自启动和后台运行。\n\n设置可随时从“我的 → 后台播放设置”打开。")
                .setPositiveButton("去设置", (dialog, which) ->
                        activity.startActivity(new Intent(activity, BackgroundActivity.class)))
                .setNegativeButton("稍后", null).show();
    }

    private boolean notificationEnabled() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel("playback");
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    private boolean batteryAllowed() {
        PowerManager manager = getSystemService(PowerManager.class);
        return manager != null && manager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout body = Widgets.vbox(this);
        body.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 24));
        scroll.addView(body);
        body.addView(Widgets.button(this, "‹ 返回", false, view -> finish()));
        body.addView(Ui.spacer(this, 14));
        body.addView(Ui.bold(this, "后台播放设置", 21f, Ui.TEXT));
        paragraph(body, "播放时启用前台服务和系统媒体卡片。退出页面、返回桌面或锁屏可以继续收听。", Ui.DIM);
        row(body, "通知栏播放控制", notificationEnabled() ? "已允许" : "未允许 / 已关闭",
                notificationEnabled(), view -> requestNotifications());
        row(body, "电池优化白名单", batteryAllowed() ? "已放行" : "未放行，可申请",
                batteryAllowed(), view -> requestBattery());
        row(body, "自启动 / 后台运行", "需在系统设置中确认", false,
                view -> new AlertDialog.Builder(this).setTitle("允许后台运行")
                        .setMessage("当前设备：" + Build.MANUFACTURER + " " + Build.MODEL
                                + "\n\n打开应用设置，查找“自启动”“后台启动”“应用启动管理”或“电池 → 不限制”。不同系统入口不同；部分设备需要在手机管家中设置。\n\n应用不能可靠读取厂商自启动开关，请以系统页面为准。")
                        .setPositiveButton("打开应用设置", (dialog, which) -> appSettings())
                        .setNegativeButton("知道了", null).show());
        row(body, "开机续听提醒", Prefs.bootReminder() ? "已开启" : "已关闭，点击开启",
                Prefs.bootReminder(), view -> {
                    Prefs.setBootReminder(!Prefs.bootReminder());
                    if (Prefs.bootReminder() && !notificationEnabled()) requestNotifications();
                    render();
                });
        paragraph(body, "开启后，手机重启会显示上次书籍的续听通知；点击后自行开始收听。开机不会自动播放。系统限制可能使提醒延后或不出现。", Ui.DIM2);
        paragraph(body, "建议在最近任务中锁定拾音，并允许后台联网。此项由手机系统提供，需要手动设置。", Ui.DIM);
        paragraph(body, "每 5 秒保存收听进度。暂停 30 分钟后释放播放服务；再次打开书籍可继续。手动强制停止、系统清理或设备重启会结束当前播放，以上设置无法保证永久常驻。", Ui.DIM2);
        setContentView(scroll);
    }

    private void row(LinearLayout body, String title, String status, boolean allowed, View.OnClickListener click) {
        LinearLayout row = Widgets.vbox(this);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        row.setBackground(Widgets.ripple(Ui.BG2));
        row.addView(Ui.text(this, title, 15f, Ui.TEXT));
        row.addView(Ui.text(this, status, 12f, allowed ? Ui.TEAL : Ui.AMBER));
        row.setOnClickListener(click);
        body.addView(row);
        body.addView(Ui.spacer(this, 8));
    }

    private void paragraph(LinearLayout body, String text, int color) {
        TextView paragraph = Ui.text(this, text, 12.5f, color);
        paragraph.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        paragraph.setLineSpacing(Ui.dp(this, 3), 1f);
        body.addView(paragraph);
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            return;
        }
        Intent intent = new Intent(Build.VERSION.SDK_INT >= 26
                ? Settings.ACTION_APP_NOTIFICATION_SETTINGS : Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        if (Build.VERSION.SDK_INT >= 26) intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        else intent.setData(Uri.parse("package:" + getPackageName()));
        launchSettings(intent);
    }

    private void requestBattery() {
        if (batteryAllowed()) {
            launchSettings(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        }
        new AlertDialog.Builder(this).setTitle("允许后台收听")
                .setMessage("放行电池优化可减少熄屏后的收听中断，但持续播放会消耗电量。是否打开系统授权页面？")
                .setPositiveButton("申请放行", (dialog, which) -> launchSettings(
                        new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("暂不", null).show();
    }

    private void appSettings() {
        launchSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + getPackageName())));
    }

    private void launchSettings(Intent intent) {
        try { startActivity(intent); }
        catch (RuntimeException unavailable) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (RuntimeException ignored) { Ui.toast(this, "请手动打开手机设置，找到拾音的后台运行权限"); }
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        render();
    }
}

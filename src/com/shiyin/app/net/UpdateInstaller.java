package com.shiyin.app.net;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import com.shiyin.app.util.Ui;

import java.io.File;

/**
 * 应用内更新下载。
 *
 * <p>用系统 {@link DownloadManager} 而不是自己写 HTTP：
 * 它自带通知栏进度、断点续传、失败重试，用户切走 App 也能继续下；
 * 而且从 API 9 就有，满足 minSdk 16 的要求。
 *
 * <p>下载完成后通过广播拉起系统安装器。Android 8.0 起允许「安装未知应用」
 * 需要用户授权，这里在拉起前检查一次，没授权就提示去设置。
 */
public final class UpdateInstaller {

    private static final String KEY_ID = "shiyin_update_download_id";
    private static final String KEY_VERSION = "shiyin_update_version";

    private UpdateInstaller() {
    }

    /**
     * 开始下载并在完成后拉起安装。
     *
     * @return 是否成功排入下载队列（false 时调用方应退回「打开发布页」）
     */
    public static boolean start(Activity activity, AppUpdate.Release release) {
        if (activity == null || release == null || !release.hasApk()) return false;
        try {
            DownloadManager dm = (DownloadManager)
                    activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return false;

            String name = "shiyin-" + release.version + ".apk";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.apkUrl));
            request.setTitle("拾音 " + release.version);
            request.setDescription("正在下载更新");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(activity,
                    Environment.DIRECTORY_DOWNLOADS, name);
            // 允许在计费网络下也继续，否则用户看到「等待 WLAN」会以为卡住
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);

            long id = dm.enqueue(request);
            activity.getSharedPreferences("shiyin", Context.MODE_PRIVATE).edit()
                    .putLong(KEY_ID, id).putString(KEY_VERSION, release.version).apply();

            registerReceiver(activity, dm, id);
            Ui.toast(activity, "已开始下载 " + release.version + "，完成后会自动提示安装");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void registerReceiver(final Activity activity, final DownloadManager dm,
                                         final long id) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long finished = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (finished != id) return;
                try {
                    activity.unregisterReceiver(this);
                } catch (Throwable ignored) {
                }
                onDownloadFinished(activity, dm, id);
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(receiver, filter);
        }
    }

    private static void onDownloadFinished(Activity activity, DownloadManager dm, long id) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        Uri fileUri;
        try {
            fileUri = dm.getUriForDownloadedFile(id);
        } catch (Throwable t) {
            fileUri = null;
        }
        if (fileUri == null) {
            Ui.toast(activity, "下载失败，请重试或打开发布页手动下载");
            return;
        }

        // Android 8.0+ 需要「安装未知应用」权限；没开就先引导
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            Ui.toast(activity, "请允许安装未知应用后再试");
            try {
                Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
            } catch (Throwable ignored) {
            }
            return;
        }

        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(fileUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        } catch (Throwable t) {
            Ui.toast(activity, "无法启动安装器，请到下载目录手动安装");
        }
    }

    /** 下载到本地后的路径，供「已下载」场景复用。 */
    public static File targetFile(Context context, String version) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return dir == null ? null : new File(dir, "shiyin-" + version + ".apk");
    }
}
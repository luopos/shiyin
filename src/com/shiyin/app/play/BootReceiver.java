package com.shiyin.app.play;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.shiyin.app.model.Book;
import com.shiyin.app.store.Prefs;
import com.shiyin.app.ui.DetailActivity;

/** 可选开机续听提醒。开机不启动播放服务，也不自动播放音频。 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "resume";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
        Prefs.init(context);
        if (!Prefs.bootReminder()) return;
        Book book = Prefs.lastBook();
        if (book == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "继续收听",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Intent open = DetailActivity.intent(context, book).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(context, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
        manager.notify(0x5EEE, builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("继续收听 · " + book.title)
                .setContentText("点击回到上次的书籍，收听进度已保存")
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build());
    }
}

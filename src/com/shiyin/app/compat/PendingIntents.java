package com.shiyin.app.compat;

import android.app.PendingIntent;
import android.os.Build;

/** PendingIntent 标志位兼容：FLAG_IMMUTABLE 是 API23 才有的常量。 */
public final class PendingIntents {

    /** PendingIntent.FLAG_IMMUTABLE，API23 常量；API16 基准里没有，只能用字面量。 */
    private static final int FLAG_IMMUTABLE = 0x04000000;

    private PendingIntents() {
    }

    /**
     * 更新已有 PendingIntent，并在系统支持时声明为不可变。
     *
     * <p>API23 以下不传 FLAG_IMMUTABLE；API31 起不传会抛 IllegalArgumentException，
     * 所以这个组合是唯一能同时跑通 4.1 和 12+ 的写法。
     */
    public static int updateCurrent() {
        return PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? FLAG_IMMUTABLE : 0);
    }
}

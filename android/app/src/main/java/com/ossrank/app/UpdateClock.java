package com.ossrank.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * 每日一次的应用更新检查闹钟。
 * 用 AlarmManager 而不是 WorkManager：不引入额外依赖（APK 体积基本不变），
 * setInexactRepeating 自动对齐系统维护窗口，省电；重启 / 升级后由 BootReceiver 重新注册。
 */
public final class UpdateClock {

    private static final String SP = "ossrank_upd";
    private static final String K_LAST = "lastCheck";    // 上次检查时间（毫秒）
    private static final String K_SEEN = "seenVersion";  // 已提醒过的 versionCode，同一版不重复提醒
    static final String ACTION_CHECK = "com.ossrank.app.CHECK_UPDATE";

    private UpdateClock() {}

    /** 每次进 App 都调一次：重复设置只是替换，代价极低 */
    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long interval = AlarmManager.INTERVAL_DAY;
        // 首次：1 小时后跑一次；之后每天一次
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 3600_000L, interval, pending(ctx));
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(ctx));
    }

    static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    static long lastCheck(Context ctx) { return sp(ctx).getLong(K_LAST, 0); }
    static void markChecked(Context ctx) { sp(ctx).edit().putLong(K_LAST, System.currentTimeMillis()).apply(); }
    static int seenVersion(Context ctx) { return sp(ctx).getInt(K_SEEN, 0); }
    static void markSeen(Context ctx, int code) { sp(ctx).edit().putInt(K_SEEN, code).apply(); }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, UpdateCheckReceiver.class).setAction(ACTION_CHECK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, 71, i, flags);
    }
}

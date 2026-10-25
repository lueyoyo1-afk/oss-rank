package com.ossrank.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 闹钟到点：后台线程拉一次 version.json，发现比本机新的版本就发通知。
 * goAsync() 给网络请求争取约 10 秒的处理窗口；失败静默，明天再查。
 * 已提醒过的 versionCode 不重复提醒（存 SharedPreferences）。
 */
public class UpdateCheckReceiver extends BroadcastReceiver {

    static final String VERSION_URL =
            "https://mdkvbklgkmwtfscderrc.supabase.co/storage/v1/object/public/app/version.json";
    static final String EXTRA_OPEN_UPDATE = "open_update";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && !UpdateClock.ACTION_CHECK.equals(intent.getAction())
                && !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try { check(app); } catch (Throwable ignored) {
            } finally { try { pr.finish(); } catch (Throwable ignored) {} }
        }, "ossrank-updchk").start();
    }

    private void check(Context ctx) {
        // 离线就别折腾了
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || cm.getActiveNetworkInfo() == null
                    || !cm.getActiveNetworkInfo().isConnected()) return;
        } catch (Throwable ignored) {}

        JSONObject info = fetchVersion();
        if (info == null) return;
        int remote = info.optInt("versionCode", 0);
        if (remote <= BuildConfig.VERSION_CODE) { UpdateClock.markChecked(ctx); return; }
        if (remote <= UpdateClock.seenVersion(ctx)) return;      // 这版提醒过，闭嘴

        String vn = info.optString("versionName", "");
        String notes = info.optString("notes", "");
        notifyUpdate(ctx, remote, vn, notes);
        UpdateClock.markSeen(ctx, remote);
        UpdateClock.markChecked(ctx);
    }

    private JSONObject fetchVersion() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(VERSION_URL + "?t=" + System.currentTimeMillis()).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                char[] buf = new char[2048];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            return new JSONObject(sb.toString());
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void notifyUpdate(Context ctx, int code, String vn, String notes) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(EXTRA_OPEN_UPDATE, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 81, i, flags);
        Notification n = new NotificationCompat.Builder(ctx, App.CH_UPD)
                .setSmallIcon(R.drawable.ic_stat_ossrank)
                .setContentTitle("OSS Rank 有新版本 v" + vn)
                .setContentText(notes.isEmpty() ? "点此查看并更新" : notes)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(notes))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        try { nm.notify(901, n); } catch (Throwable ignored) {}   // 未授权通知时抛异常，忽略
    }
}

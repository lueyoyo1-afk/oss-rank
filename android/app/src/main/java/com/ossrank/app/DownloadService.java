package com.ossrank.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 原生下载：前台服务 + 通知栏真实进度 + 暂停 / 取消 / 断点续传。
 * 进度通过 {@link DlBus} 回传网页；状态用 {@link #state()} 供页面重新打开时恢复 UI。
 */
public class DownloadService extends Service {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_NAME = "name";

    public static final String ST_IDLE = "idle";
    public static final String ST_DOWNLOADING = "downloading";
    public static final String ST_PAUSED = "paused";
    public static final String ST_DONE = "done";
    public static final String ST_ERROR = "error";

    private static final int NID_PROGRESS = 7301;
    private static final int NID_DONE = 7302;

    private static volatile String sState = ST_IDLE;
    private static volatile String sName = "";
    private static volatile String sPath = "";
    private static volatile long sGot = 0, sTotal = 0;
    private static volatile String sUrl = "";
    private static volatile boolean paused = false;
    private static volatile boolean cancelled = false;
    private static volatile HttpURLConnection liveConn;

    public static String state() { return sState; }
    public static String name() { return sName; }
    public static String path() { return sPath; }
    public static long got() { return sGot; }
    public static long total() { return sTotal; }
    public static String url() { return sUrl; }

    public static void start(Context ctx, String url, String name) {
        paused = false; cancelled = false;
        Intent i = new Intent(ctx, DownloadService.class);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_NAME, name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    /** 暂停：断开连接但保留 .part，恢复时用 Range 续传 */
    public static void pause() {
        if (!ST_DOWNLOADING.equals(sState)) return;
        paused = true;
        HttpURLConnection c = liveConn;
        if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
    }

    /** 恢复：用同一 url/name 重新发起，自动从 .part 续传 */
    public static void resume(Context ctx) {
        if (sUrl == null || sUrl.isEmpty()) return;
        paused = false;
        Intent i = new Intent(ctx, DownloadService.class);
        i.putExtra(EXTRA_URL, sUrl);
        i.putExtra(EXTRA_NAME, sName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    /** 取消：断连 + 删除已下载的部分 */
    public static void cancel(Context ctx) {
        cancelled = true;
        paused = false;
        HttpURLConnection c = liveConn;
        if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        try {
            File dir = new File(ctx.getExternalFilesDir(null), "Downloads");
            if (sName != null && !sName.isEmpty()) {
                new File(dir, sName + ".part").delete();
                new File(dir, sName).delete();
            }
        } catch (Throwable ignored) {}
        sState = ST_IDLE; sGot = 0; sTotal = 0; sPath = ""; sUrl = "";
        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NID_PROGRESS);
        DlBus.L l = DlBus.listener;
        if (l != null) l.onState(ST_IDLE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String url = intent.getStringExtra(EXTRA_URL);
        String name = intent.getStringExtra(EXTRA_NAME);
        if (url == null || url.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        if (name == null || name.trim().isEmpty()) name = "download.bin";
        name = safeName(name);

        sUrl = url; sName = name; sState = ST_DOWNLOADING; sPath = "";
        cancelled = false;

        startForeground(NID_PROGRESS, buildProgress(sGot, sTotal, name, false));

        final String fname = name;
        Thread t = new Thread(() -> run(url, fname), "ossrank-dl");
        t.start();
        return START_NOT_STICKY;
    }

    private static String safeName(String name) {
        String n = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "download.bin";
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, Math.min(100, n.length() - ext.length())) + ext;
        }
        return n;
    }

    private void run(String url, String name) {
        File dir = new File(getExternalFilesDir(null), "Downloads");
        if (!dir.exists() && !dir.mkdirs()) { finishError("无法创建下载目录", name); return; }
        File part = new File(dir, name + ".part");
        File dest = new File(dir, name);

        long already = part.exists() ? part.length() : 0;
        long got = already, total = -1, lastPush = 0;
        HttpURLConnection conn = null;

        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            liveConn = conn;
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) OSSRank/1.0");
            conn.setRequestProperty("Accept", "*/*");
            if (already > 0) conn.setRequestProperty("Range", "bytes=" + already + "-");

            int code = conn.getResponseCode();
            boolean resumed = (code == 206);
            if (code == 416) { finishOk(part, dest, name); return; }
            if (code >= 400) throw new Exception("服务器返回 HTTP " + code);

            long len = conn.getContentLengthLong();
            total = len > 0 ? (resumed ? already + len : len) : -1;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, resumed)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) throw new Cancelled();
                    out.write(buf, 0, n);
                    got += n;
                    sGot = got; sTotal = total;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastPush > 300) {
                        lastPush = now;
                        pushProgress(got, total, name);
                    }
                }
                out.flush();
            }
            finishOk(part, dest, name);
        } catch (Cancelled ce) {
            sState = ST_IDLE;
        } catch (Exception e) {
            if (paused) {
                sState = ST_PAUSED;
                sGot = got; sTotal = total;
                pushPaused(got, total, name);
                DlBus.L l = DlBus.listener;
                if (l != null) l.onState(ST_PAUSED);
            } else if (cancelled) {
                sState = ST_IDLE;
            } else {
                String msg = e.getMessage();
                if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
                finishError(msg, name);
            }
        } finally {
            liveConn = null;
            if (conn != null) conn.disconnect();
        }
    }

    private void finishOk(File part, File dest, String name) {
        if (dest.exists() && !dest.delete()) { finishError("无法覆盖同名文件", name); return; }
        if (!part.renameTo(dest)) { finishError("无法保存文件", name); return; }
        sState = ST_DONE;
        sPath = dest.getAbsolutePath();
        sGot = dest.length(); sTotal = dest.length();

        DlBus.L l = DlBus.listener;
        if (l != null) l.onDone(sPath, name);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NID_PROGRESS);
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            open.putExtra(MainActivity.EXTRA_INSTALL, sPath);
            PendingIntent pi = PendingIntent.getActivity(this, 2, open, pendingFlags());
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, App.CH_ID)
                    .setSmallIcon(R.drawable.ic_stat_ossrank)
                    .setContentTitle("下载完成")
                    .setContentText(name + " · 点此安装")
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .addAction(0, "安装", pi)          // 通知栏直接带安装按钮，不用先进 App
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            nm.notify(NID_DONE, b.build());
        }
        stopForegroundCompat();
        stopSelf();
    }

    private void finishError(String msg, String name) {
        sState = ST_ERROR;
        DlBus.L l = DlBus.listener;
        if (l != null) l.onError(msg);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NID_PROGRESS);
            nm.notify(NID_DONE, new NotificationCompat.Builder(this, App.CH_ID)
                    .setSmallIcon(R.drawable.ic_stat_ossrank)
                    .setContentTitle("下载失败")
                    .setContentText(name + " · " + msg)
                    .setAutoCancel(true)
                    .build());
        }
        stopForegroundCompat();
        stopSelf();
    }

    private void pushProgress(long got, long total, String name) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NID_PROGRESS, buildProgress(got, total, name, false));
        DlBus.L l = DlBus.listener;
        if (l != null) l.onProgress(got, total);
    }

    private void pushPaused(long got, long total, String name) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NID_PROGRESS, buildProgress(got, total, name, true));
    }

    private void stopForegroundCompat() {
        try { stopForeground(true); } catch (Throwable ignored) {}
    }

    private static int pendingFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    private Notification buildProgress(long got, long total, String name, boolean isPaused) {
        int pct = total > 0 ? (int) (got * 100 / total) : 0;
        String text = (isPaused ? "已暂停 · " : "")
                + (total > 0 ? pct + "% · " + mb(got) + " / " + mb(total) : mb(got));
        return new NotificationCompat.Builder(this, App.CH_ID)
                .setSmallIcon(R.drawable.ic_stat_ossrank)
                .setContentTitle((isPaused ? "已暂停 " : "正在下载 ") + name)
                .setContentText(text)
                .setProgress(100, pct, total <= 0 && !isPaused)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private static String mb(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** 用户主动 cancel 时抛这个，避免被当成网络错误 */
    static class Cancelled extends Exception {}

    @Override
    public void onDestroy() {
        liveConn = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

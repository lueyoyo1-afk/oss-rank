package com.ossrank.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class App extends Application {

    public static final String CH_ID = "ossrank_dl";     // 下载进度（低优先级，无声音）
    public static final String CH_UPD = "ossrank_upd";   // 有新版本提醒（默认优先级，会响）

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("安装包下载进度");
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);

                NotificationChannel up = new NotificationChannel(
                        CH_UPD, "新版本提醒", NotificationManager.IMPORTANCE_DEFAULT);
                up.setDescription("每天后台检查一次，发现新版本时提醒");
                nm.createNotificationChannel(up);
            }
        }
        // 每天一次的后台更新检查（重复调用只是替换闹钟）
        UpdateClock.schedule(this);
    }
}

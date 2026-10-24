package com.ossrank.app;

/**
 * 下载进度总线：Service 下载时回调给当前 Activity，再由 Activity 注入网页，
 * 让网页面板能显示真实百分比（网页自身受 CORS 限制拿不到字节流）。
 */
public final class DlBus {

    public interface L {
        void onProgress(long got, long total);
        void onDone(String path, String name);
        void onError(String message);
        /** 下载状态变化：downloading / paused / done / error / idle */
        default void onState(String state) {}
    }

    public static volatile L listener;

    private DlBus() {}
}

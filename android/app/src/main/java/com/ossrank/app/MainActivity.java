package com.ossrank.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 单 Activity + WebView 壳。
 * - 沉浸式：内容绘制到状态栏 / 导航栏之下，实际安全区通过 CSS 变量注入网页
 * - 离线：网页打进 assets，用 WebViewAssetLoader 映射成 https 源（保留 localStorage / clipboard 等能力）
 * - 下载：交给原生 DownloadService（真实进度 + 通知栏 + 下完直接安装）
 */
public class MainActivity extends Activity {

    public static final String EXTRA_INSTALL = "install_path";

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";

    /** 只允许从这些域名下载，避免页面被注入后拉任意文件 */
    private static final String[] ALLOWED = {
            "github.com", "api.github.com", "codeload.github.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com",
            "raw.githubusercontent.com",
            "supabase.co"          // 应用自身更新包的托管域名
    };

    private WebView web;
    private WebViewAssetLoader assetLoader;
    private int lastTop = -1, lastBottom = -1;
    private long lastBack = 0;
    private String pendingRepo = null;      // 分享链接带进来的 owner/repo，等页面就绪再打开

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setupEdgeToEdge();

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        setupWebView();
        setupInsets();
        warnIfWebViewOld();
        web.loadUrl(START_URL);

        bindDownloadBus();
        askNotificationPermission();
        handleInstall(getIntent());
        handleView(getIntent());
    }

    /* ---------------- 沉浸式 ---------------- */

    private void setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        applyBarIcons(false);
    }

    /** 状态栏 / 导航栏图标颜色：浅色主题下用深色图标，否则白字看不见 */
    private void applyBarIcons(boolean light) {
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setAppearanceLightStatusBars(light);
        c.setAppearanceLightNavigationBars(light);
    }

    private void setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(web, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            if (top != lastTop || bottom != lastBottom) {
                lastTop = top;
                lastBottom = bottom;
                injectSafeArea(top, bottom);
            }
            // 键盘弹出时把可视区抬高，输入框不会被遮住
            v.setPadding(0, 0, 0, ime);
            // 返回 CONSUMED：WebView 默认也会把 systemBars 当 padding 再套一层，
            // 那样顶部安全区就被算两遍（状态栏 32dp + 页面 32dp），看起来空一大块。
            // 这里自己接管：页面从 y=0 开始画，只用 CSS 变量避让。
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(web);
    }

    /**
     * 把系统栏高度写进 CSS 变量。insets 可能在页面还没加载完时就回调，
     * 那一次 evaluateJavascript 会打到空白文档上，所以 onPageFinished 里还要补一次。
     */
    private void injectSafeArea(int topPx, int bottomPx) {
        // WindowInsets 的单位是物理像素；CSS 的 px 是 dp（viewport 未缩放时）。
        // 不换算的话，2.75 倍屏上会把 42dp 的状态栏写成 "115px"，顶部凭空多出 3 倍空白。
        float d = getResources().getDisplayMetrics().density;
        int top = Math.round(topPx / d);
        int bottom = Math.round(bottomPx / d);
        eval("document.documentElement.style.setProperty('--safe-top','" + top + "px');"
                + "document.documentElement.style.setProperty('--safe-bottom','" + bottom + "px');");
    }

    /* ---------------- WebView ---------------- */

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return assetLoader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                eval("document.documentElement.classList.add('in-app')");
                if (lastTop >= 0) injectSafeArea(lastTop, lastBottom);   // 补一次，防止首次回调早于页面加载
                flushPendingRepo();
            }
        });

        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "OssRankNative");

        // 兜底：页面没走 JS 桥时，链接触发的下载也交给原生
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (!isAllowed(url)) {
                toast("已拦截不支持的下载地址");
                return;
            }
            String name = URLUtil.guessFileName(url, cd, mime);
            DownloadService.start(this, url, name);
        });
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            toast("没有可打开该链接的应用");
        }
    }

    private void eval(String js) {
        if (web == null) return;
        web.post(() -> {
            try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
        });
    }

    /**
     * 页面用了可选链等较新语法，Android System WebView 太旧会白屏。
     * 这里提前提醒，避免用户以为 App 坏了。
     */
    private void warnIfWebViewOld() {
        try {
            android.content.pm.PackageInfo pi = androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this);
            if (pi == null) return;
            String vn = pi.versionName == null ? "" : pi.versionName;
            int major = 0;
            int dot = vn.indexOf('.');
            if (dot > 0) {
                try { major = Integer.parseInt(vn.substring(0, dot)); } catch (Exception ignored) {}
            }
            if (major > 0 && major < 90) {
                Toast.makeText(this,
                        "系统 WebView 版本较旧（" + vn + "），可能显示异常。请到应用商店更新「Android System WebView」或 Chrome。",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignored) {}
    }

    /* ---------------- 下载进度回传 ---------------- */

    private void bindDownloadBus() {
        DlBus.listener = new DlBus.L() {
            @Override
            public void onProgress(long got, long total) {
                eval("window.__ossDl&&window.__ossDl({state:'progress',got:" + got + ",total:" + total + "})");
            }

            @Override
            public void onDone(String path, String name) {
                eval("window.__ossDl&&window.__ossDl({state:'done',name:'" + esc(name)
                        + "',path:'" + esc(path) + "'})");
            }

            @Override
            public void onError(String message) {
                eval("window.__ossDl&&window.__ossDl({state:'error',msg:'" + esc(message) + "'})");
            }

            @Override
            public void onState(String state) {
                eval("window.__ossDl&&window.__ossDl({state:'" + esc(state) + "'})");
            }
        };
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
    }

    /* ---------------- 安装 ---------------- */

    static void installApk(Context ctx, String path) {
        File f = new File(path);
        if (!f.exists()) {
            Toast.makeText(ctx, "安装包不存在，请重新下载", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(ctx, "请先允许本应用安装未知来源应用", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "系统没有可用的安装器", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "无法启动安装：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /* ---------------- APK 元信息解析 ---------------- */

    private static int pkgFlags(boolean perms) {
        int f = 0;
        if (perms) f |= PackageManager.GET_PERMISSIONS;
        if (Build.VERSION.SDK_INT >= 28) f |= PackageManager.GET_SIGNING_CERTIFICATES;
        else f |= PackageManager.GET_SIGNATURES;
        return f;
    }

    /** 签名指纹：SHA-256 前 6 字节。只用来比较「是不是同一个签名的包」，不是安全用途 */
    private static String sigOf(PackageInfo pi) {
        try {
            byte[] b = null;
            if (Build.VERSION.SDK_INT >= 28) {
                SigningInfo si = pi.signingInfo;
                if (si != null) {
                    Signature[] arr = si.hasMultipleSigners()
                            ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
                    if (arr != null && arr.length > 0) b = arr[0].toByteArray();
                }
            } else {
                Signature[] arr = pi.signatures;
                if (arr != null && arr.length > 0) b = arr[0].toByteArray();
            }
            if (b == null) return "";
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format(Locale.US, "%02x", d[i]));
            return sb.toString();
        } catch (Throwable e) { return ""; }
    }

    /** 从 APK 的 lib/ 目录读出它支持哪些 ABI */
    private static TreeSet<String> abisOfApk(String path) {
        TreeSet<String> set = new TreeSet<>();
        try (ZipFile zf = new ZipFile(path)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.startsWith("lib/")) continue;
                String[] p = n.split("/");
                if (p.length > 2 && !p[1].isEmpty()) set.add(p[1]);
            }
        } catch (Throwable ignored) {}
        return set;
    }

    private void handleInstall(Intent it) {
        if (it == null) return;
        String p = it.getStringExtra(EXTRA_INSTALL);
        if (p != null && !p.isEmpty()) {
            it.removeExtra(EXTRA_INSTALL);
            installApk(this, p);
        }
    }

    /* ---------------- 分享链接：github.com/owner/repo 直接用 App 打开 ---------------- */

    private void handleView(Intent it) {
        if (it == null || !Intent.ACTION_VIEW.equals(it.getAction())) return;
        Uri u = it.getData();
        if (u == null) return;
        String host = u.getHost();
        if (!"github.com".equals(host) && !"www.github.com".equals(host)) return;
        String path = u.getPath();
        if (path == null) return;
        String[] seg = path.split("/");
        if (seg.length < 3) return;
        String owner = seg[1], repo = seg[2];
        if (owner.isEmpty() || repo.isEmpty()) return;
        pendingRepo = owner + "/" + repo;
        flushPendingRepo();
    }

    private void flushPendingRepo() {
        if (pendingRepo == null) return;
        final String r = pendingRepo;
        pendingRepo = null;
        // 页面脚本执行完再调，避免 onPageFinished 早于脚本初始化
        if (web != null) web.postDelayed(() ->
                eval("window.__ossOpenRepo&&window.__ossOpenRepo('" + r.replace("'", "") + "')"), 400);
    }

    /* ---------------- 生命周期 / 返回键 ---------------- */

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleInstall(intent);
        handleView(intent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null) {
            // 先让页面处理（详情弹窗 / 抽屉 / 排序面板），再考虑退出
            web.evaluateJavascript("(window.__ossBack&&window.__ossBack())===true", val -> {
                if (!"true".equals(val)) {
                    long now = System.currentTimeMillis();
                    if (now - lastBack < 2000) {
                        finish();
                    } else {
                        lastBack = now;
                        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统安装器 / 设置页回到本页：让页面重新判定「装没装好」，别卡在下载中
        eval("window.__ossResume&&window.__ossResume()");
    }

    @Override
    protected void onDestroy() {
        DlBus.listener = null;
        if (web != null) {
            try {
                web.removeJavascriptInterface("OssRankNative");
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignored) {}
            web = null;
        }
        super.onDestroy();
    }

    /* ---------------- 工具 ---------------- */

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
    }

    static boolean isAllowed(String url) {
        if (url == null) return false;
        try {
            Uri u = Uri.parse(url);
            if (!"https".equalsIgnoreCase(u.getScheme())) return false;
            String h = u.getHost();
            if (h == null) return false;
            for (String a : ALLOWED) {
                if (h.equals(a) || h.endsWith("." + a)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 暴露给网页的原生能力 */
    public class Bridge {

        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public String version() { return BuildConfig.VERSION_NAME; }

        @JavascriptInterface
        public int sdk() { return Build.VERSION.SDK_INT; }

        @JavascriptInterface
        public void download(String url, String name) {
            if (!isAllowed(url)) { toast("已拦截不安全的下载地址"); return; }
            String n = (name == null || name.trim().isEmpty())
                    ? URLUtil.guessFileName(url, null, null) : name;
            final String fname = n;
            runOnUiThread(() -> DownloadService.start(MainActivity.this, url, fname));
        }

        @JavascriptInterface
        public void install(String path) {
            runOnUiThread(() -> installApk(MainActivity.this, path));
        }

        @JavascriptInterface
        public boolean canInstall() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return getPackageManager().canRequestPackageInstalls();
            }
            return true;
        }

        @JavascriptInterface
        public void openInstallSettings() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        toast("无法打开安装权限设置");
                    }
                }
            });
        }

        @JavascriptInterface
        public void setLightBars(final boolean light) {
            runOnUiThread(() -> applyBarIcons(light));
        }

        /** 浅色主题时窗口底色也要跟着变，否则边缘/回弹会露黑底 */
        @JavascriptInterface
        public void setBackground(final String color) {
            runOnUiThread(() -> {
                try {
                    getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor(color)));
                } catch (Exception ignored) {}
            });
        }

        /** WebView 里 password 输入框长按可能弹不出「粘贴」，给页面一个可靠的入口 */
        @JavascriptInterface
        public String readClipboard() {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                android.content.ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence t = clip.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Throwable e) {
                return "";
            }
        }

        /* ---------- 下载控制 ---------- */
        @JavascriptInterface
        public void pauseDownload() { DownloadService.pause(); }

        @JavascriptInterface
        public void resumeDownload() {
            runOnUiThread(() -> DownloadService.resume(MainActivity.this));
        }

        @JavascriptInterface
        public void cancelDownload() {
            runOnUiThread(() -> DownloadService.cancel(MainActivity.this));
        }

        /** 当前下载状态，页面重新打开详情时用它恢复进度条 */
        @JavascriptInterface
        public String dlState() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("state", DownloadService.state());
                o.put("name", DownloadService.name());
                o.put("path", DownloadService.path());
                o.put("got", DownloadService.got());
                o.put("total", DownloadService.total());
                o.put("url", DownloadService.url());
                return o.toString();
            } catch (Throwable e) { return "{}"; }
        }

        /* ---------- APK / 安装检测 ---------- */
        /**
         * 读 APK 文件里的信息（不安装也能读）：包名 / 版本 / 最低系统 / 架构 / 权限数 / 签名指纹。
         * 页面在「下载完成」面板里把它摊给用户看，装之前就知道要装的是什么。
         */
        @JavascriptInterface
        public String apkInfo(String path) {
            try {
                if (path == null || path.isEmpty()) return "";
                PackageManager pm = getPackageManager();
                PackageInfo pi = pm.getPackageArchiveInfo(path, pkgFlags(true));
                if (pi == null) return "";
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("pkg", pi.packageName);
                o.put("vc", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
                o.put("vn", pi.versionName == null ? "" : pi.versionName);
                if (pi.applicationInfo != null) {
                    pi.applicationInfo.sourceDir = path;
                    pi.applicationInfo.publicSourceDir = path;
                    o.put("minSdk", pi.applicationInfo.minSdkVersion);
                    CharSequence lb = null;
                    try { lb = pm.getApplicationLabel(pi.applicationInfo); } catch (Throwable ignored) {}
                    o.put("label", lb == null ? "" : lb.toString());
                }
                o.put("perms", pi.requestedPermissions == null ? 0 : pi.requestedPermissions.length);
                o.put("sig", sigOf(pi));
                o.put("abis", TextUtils.join(",", abisOfApk(path)));
                o.put("size", new File(path).length());
                return o.toString();
            } catch (Throwable e) { return ""; }
        }

        /** 该包名是否还装在手机上（顺带给出已装版本与签名，用来判断能不能覆盖安装） */
        @JavascriptInterface
        public String installedInfo(String pkg) {
            try {
                PackageManager pm = getPackageManager();
                PackageInfo pi = pm.getPackageInfo(pkg, pkgFlags(false));
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("installed", true);
                o.put("vc", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
                o.put("vn", pi.versionName == null ? "" : pi.versionName);
                o.put("sig", sigOf(pi));
                return o.toString();
            } catch (Throwable e) {
                return "{\"installed\":false}";
            }
        }

        /**
         * 已安装应用的完整档案：版本 / 最低系统 / 架构 / 签名 / 首装与更新时间 / 是否系统应用。
         * 页面的「包检测」面板用它和待装 APK 对比，提前发现签名冲突、架构不符、版本降级。
         */
        @JavascriptInterface
        public String pkgFull(String pkg) {
            try {
                if (pkg == null || pkg.isEmpty()) return "{\"installed\":false}";
                PackageManager pm = getPackageManager();
                PackageInfo pi = pm.getPackageInfo(pkg, pkgFlags(true));
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("installed", true);
                o.put("vc", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
                o.put("vn", pi.versionName == null ? "" : pi.versionName);
                o.put("sig", sigOf(pi));
                o.put("first", pi.firstInstallTime);
                o.put("last", pi.lastUpdateTime);
                if (pi.applicationInfo != null) {
                    o.put("sdk", pi.applicationInfo.minSdkVersion);
                    o.put("label", String.valueOf(pm.getApplicationLabel(pi.applicationInfo)));
                    o.put("sys", (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    o.put("apis", TextUtils.join(",", pi.applicationInfo.nativeLibraryDir == null
                            ? new String[]{} : new String[]{pi.applicationInfo.nativeLibraryDir}));
                }
                o.put("perms", pi.requestedPermissions == null ? 0 : pi.requestedPermissions.length);
                return o.toString();
            } catch (Throwable e) {
                return "{\"installed\":false}";
            }
        }

        /** 本机 CPU 架构（优先顺序），页面用它从多个 APK 里挑对的包 */
        @JavascriptInterface
        public String abis() {
            try {
                org.json.JSONArray a = new org.json.JSONArray();
                for (String s : Build.SUPPORTED_ABIS) a.put(s);
                return a.toString();
            } catch (Throwable e) { return "[]"; }
        }

        /** 安装包占多大 */
        @JavascriptInterface
        public long fileSize(String path) {
            try { return path == null ? 0 : new File(path).length(); } catch (Throwable e) { return 0; }
        }

        /** 清理下载下来的安装包（只删 Downloads 目录里的，别的路径一律不动） */
        @JavascriptInterface
        public boolean deleteApk(String path) {
            try {
                if (path == null || path.isEmpty()) return false;
                File f = new File(path);
                if (!f.exists()) return true;
                if (!f.getAbsolutePath().contains("/Downloads/")) return false;
                return f.delete();
            } catch (Throwable e) { return false; }
        }

        /** 已安装应用列表（包名 + 应用名），页面用仓库名做模糊匹配 */
        @JavascriptInterface
        public String installedApps() {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                java.util.List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
                org.json.JSONArray arr = new org.json.JSONArray();
                for (android.content.pm.ApplicationInfo ai : apps) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("pkg", ai.packageName);
                    CharSequence lb = pm.getApplicationLabel(ai);
                    o.put("label", lb == null ? "" : lb.toString());
                    arr.put(o);
                }
                return arr.toString();
            } catch (Throwable e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public boolean fileExists(String path) {
            try { return path != null && new File(path).exists(); } catch (Throwable e) { return false; }
        }

        @JavascriptInterface
        public void launchApp(String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) { toast("没有找到该应用"); return; }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable e) { toast("无法打开：" + e.getMessage()); }
        }

        /* ---------- 自身版本（App 内更新用） ---------- */
        @JavascriptInterface
        public int appVersionCode() { return BuildConfig.VERSION_CODE; }

        @JavascriptInterface
        public String appVersionName() { return BuildConfig.VERSION_NAME; }

        @JavascriptInterface
        public void toast(String msg) { MainActivity.this.toast(msg); }

        @JavascriptInterface
        public boolean downloading() { return DownloadService.ST_DOWNLOADING.equals(DownloadService.state()); }
    }
}

# OSS Rank Android App

自己构建的 Android 壳，把单文件 PWA 包成真正的 APK —— 补上浏览器做不到的两件事：**真实下载进度** 和 **下载完直接安装**。

## 装到手机

1. 把 `OSSRank-1.8.apk` 传到手机（微信传文件选「用其他应用打开」→ 安装；或数据线拷贝）
2. 点击安装，系统提示「未知来源」时点「允许」
3. 首次打开点一次「获取」下载 APK，会再提示一次「允许安装未知应用」，允许后即可一键安装后续包

**系统要求**：Android 7.0（API 24）及以上。包名 `com.ossrank.app`，版本 1.8。

## 和网页版相比多了什么

| 能力 | 网页版 | App |
|---|---|---|
| 下载进度 | 只能看系统通知栏（GitHub 下载域名无 CORS，网页读不到字节流） | **应用内真实百分比** + 通知栏进度 |
| 下载/安装状态 | 无 | **下载完成 → 立即安装 → 安装完成**，状态跟着走，点一下收起面板 |
| 状态栏 | Safari/Chrome 的地址栏 | **沉浸式**：内容画到状态栏下，顶栏背景延伸到屏幕顶端 |
| 返回键 | 系统处理 | 先关详情 / 抽屉 / 排序面板，再退出（双击退出） |
| 网络 | 首次要联网拉列表 | 网页打进包里，**首屏完全离线** |

## 沉浸式怎么做的

```
WindowCompat.setDecorFitsSystemWindows(window, false)   // 内容延伸到系统栏下
setStatusBarColor(TRANSPARENT) / setNavigationBarColor(TRANSPARENT)
layoutInDisplayCutoutMode = SHORT_EDGES                 // 刘海屏也能铺满
```

安全区不靠 `env(safe-area-inset-*)`（Android WebView 支持不稳定），而是原生读
`WindowInsets` 后把像素值写进 CSS 变量：

```java
document.documentElement.style.setProperty('--safe-top', '28px')
document.documentElement.style.setProperty('--safe-bottom', '20px')
```

页面侧：`header { padding-top: var(--safe-top) }`（背景仍从 y=0 开始，所以状态栏区域是顶栏背景色），
抽屉 / 底部面板 / toast / 下拉提示 全部避让；`body { padding-bottom: var(--safe-bottom) }` 给手势条留位。

键盘弹出时用 `ime` inset 给 WebView 加底部 padding，输入框不会被遮。

## 下载为什么能拿到真实进度

网页里 `fetch` 读不到 GitHub 的下载字节（`release-assets.githubusercontent.com` 不返回 CORS 头）。
App 里绕开了这个限制：`WebView.setDownloadListener` + JS 桥拦截下载，交给原生服务：

```
DownloadService（前台服务，foregroundServiceType=dataSync）
  ├─ HttpURLConnection 下载到 getExternalFilesDir()/Downloads
  ├─ 每 300ms：更新通知栏 + 通过 DlBus 回调 → evaluateJavascript(__ossDl)
  └─ 完成：发可点击通知 → PendingIntent → MainActivity → FileProvider + ACTION_VIEW 安装
```

`__ossDl({state:'progress', got, total})` 让网页面板显示真实百分比；`state:'done'` 则把面板换成「立即安装」。

## 安全边界

- 下载地址**白名单**：只允许 `github.com`、`api.github.com`、`codeload.github.com`、
  `objects.githubusercontent.com`、`release-assets.githubusercontent.com`、`raw.githubusercontent.com`，
  且必须是 https —— 页面被注入也拉不了任意文件
- 文件名过滤 `\ / : * ? " < > |` 和 `..`，杜绝路径穿越
- 页面资源用 `WebViewAssetLoader` 映射到 `https://appassets.androidplatform.net/`，
  既是安全上下文（localStorage / clipboard 可用），又不走文件协议
- App 内**不注册 Service Worker**，避免内置页面被旧缓存顶掉

## 目录

```
android/
├─ app/src/main/
│  ├─ java/com/ossrank/app/
│  │  ├─ MainActivity.java     # WebView 壳 + 沉浸式 + JS 桥 + 安装
│  │  ├─ DownloadService.java  # 前台服务下载 + 进度 + 完成通知
│  │  ├─ DlBus.java            # 进度回调总线
│  │  └─ App.java              # 通知渠道
│  ├─ assets/                  # 网页（index.html 等，构建时同步）
│  ├─ res/                     # 图标 / 主题 / FileProvider 路径
│  └─ AndroidManifest.xml
├─ keystore/ossrank.jks        # 固定签名（密码 ossrank2026）
├─ build.gradle / settings.gradle / gradle.properties
└─ gradlew                     # Gradle 8.7 wrapper
```

## 自己重新构建

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk17
cd android
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

改了网页后要重新同步 assets：

```bash
cp index.html manifest.webmanifest sw.js icon-*.png apple-touch-icon.png android/app/src/main/assets/
```

## 两个已知限制

1. **改网页要重新出包**。当前是「网页打包进 assets」，离线最快、最稳。
   如果想改完即时生效，可以改成壳加载线上地址（`loadUrl("https://你的域名/")`），
   代价是首屏必须联网、且要自己处理离线缓存 —— 需要的话告诉我。
2. **签名固定**。`keystore/ossrank.jks` 密码写在 `build.gradle` 里，仅用于内部自用分发。
   要上架应用商店必须换成自己的正式签名并妥善保管。

## 技术栈

AGP 8.5.2 · Gradle 8.7 · JDK 17 · compileSdk 34 / targetSdk 34 / minSdk 24 · androidx.webkit 1.11.0

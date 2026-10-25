# 部署到 GitHub Pages（5 分钟）

## 方式一：网页上传（手机也能做）

1. 打开 <https://github.com/new>
   - Repository name：`ossrank`
   - 选 **Public**（Pages 免费版需要公开仓库）
   - 点 **Create repository**

2. 进入仓库 → **Add file** → **Upload files**
   把本压缩包里的这些文件**全部**拖/选进去（不要带文件夹层级）：
   `index.html`、`manifest.webmanifest`、`sw.js`、`icon-192.png`、`icon-512.png`、`icon.svg`、`apple-touch-icon.png`
   然后 **Commit changes**

3. 仓库 → **Settings** → 左侧 **Pages**
   - Source 选 **Deploy from a branch**
   - Branch 选 **main**，目录选 **/ (root)** → **Save**

4. 等 1 分钟左右，访问：
   `https://你的用户名.github.io/ossrank/`

## 方式二：git 命令

```bash
git init
git add .
git commit -m "OSS Rank"
git branch -M main
git remote add origin https://github.com/你的用户名/ossrank.git
git push -u origin main
```

然后同上第 3 步开启 Pages。

## 登录（可选，提高接口额度）

部署后用手机打开应用 → 左上角来源芯片 → 账号 → GitHub 登录 → 粘贴 Token：

1. 打开 <https://github.com/settings/tokens?type=beta> → Generate new token
2. 只勾 **Public Repositories (read-only)**，生成后复制 `github_pat_...`
3. 回到应用粘贴 → 保存并验证

额度从「搜索 10 次/分、接口 60 次/时」提升到「30 次/分、5000 次/时」。

---

## Supabase 更新通道（App 自更新发版流程）

> ⚠️ **安全模型（2026-10-24 收紧后）**：存储桶 `app` 的匿名角色**只有读取权限**，
> 写入（INSERT/UPDATE/DELETE）已全部撤销——防止任何拿到公开 publishable key 的人
> 篡改 `version.json` 诱导用户下载恶意包。发版时按下面流程**临时开权限、传完立刻关**。

### 发版步骤

0. **构建并计算 SHA-256**（v2.4 起更新包会被完整性校验，漏了这步 App 会拦截安装）：

   ```bash
   gradle assembleRelease
   cp app/build/outputs/apk/release/app-release.apk OSSRank-vX.Y.apk
   sha256sum OSSRank-vX.Y.apk     # 记下哈希，写进 version.json 的 sha256 字段
   # APK 体积（字节数）也要写进 version.json 的 size 字段：stat -c %s OSSRank-vX.Y.apk
   ```

   `version.json` 完整字段示例：

   ```json
   {"versionCode":15, "versionName":"2.4", "file":"OSSRank-v2.4.apk",
    "size":1751110, "url":"https://<项目>.supabase.co/storage/v1/object/public/app/OSSRank-v2.4.apk",
    "sha256":"<sha256sum 的输出>", "notes":"一句话更新说明"}
   ```

   旧版 App（< 2.4）不认识 sha256 字段会自动忽略，兼容无害。

1. **临时开启写入**（Supabase Dashboard → SQL Editor 执行）：

   ```sql
   CREATE POLICY "app bucket temp write" ON storage.objects
     FOR INSERT TO anon, authenticated WITH CHECK (bucket_id = 'app');
   CREATE POLICY "app bucket temp update" ON storage.objects
     FOR UPDATE TO anon, authenticated USING (bucket_id = 'app');
   CREATE POLICY "app bucket temp delete" ON storage.objects
     FOR DELETE TO anon, authenticated USING (bucket_id = 'app');
   ```

2. **上传**（`x-upsert` 已存在文件时需要；key 只放 `apikey` 头，不要放 Authorization）：

   ```bash
   KEY='sb_publishable_...'
   # 新 APK
   curl -X POST 'https://<项目>.supabase.co/storage/v1/object/app/OSSRank-vX.Y.apk' \
     -H "apikey: $KEY" -H 'x-upsert: true' \
     -H 'content-type: application/vnd.android.package-archive' \
     --data-binary @app-release.apk
   # 更新 version.json（versionCode/versionName/size/url/notes）
   curl -X POST 'https://<项目>.supabase.co/storage/v1/object/app/version.json' \
     -H "apikey: $KEY" -H 'x-upsert: true' -H 'content-type: application/json' \
     --data-binary @version.json
   # 网页版（content-type 必须大写 TEXT/HTML，小写会被网关改写成 text/plain）
   curl -X POST 'https://<项目>.supabase.co/storage/v1/object/app/index.html' \
     -H "apikey: $KEY" -H 'x-upsert: true' -H 'content-type: TEXT/HTML; charset=utf-8' \
     --data-binary @index.html
   ```

3. **立刻关闭写入**：

   ```sql
   DROP POLICY IF EXISTS "app bucket temp write"  ON storage.objects;
   DROP POLICY IF EXISTS "app bucket temp update" ON storage.objects;
   DROP POLICY IF EXISTS "app bucket temp delete" ON storage.objects;
   ```

4. **验证**：匿名上传应返回 403；`/object/public/app/version.json` 应返回 200 新版本。

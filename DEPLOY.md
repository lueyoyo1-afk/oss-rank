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

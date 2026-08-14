# DSH-Mobile 📱

> 仓库：https://github.com/juanlian583/DSH-Mobile · Release：https://github.com/juanlian583/DSH-Mobile/releases

把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DSH）智能体完整装进安卓手机：
**不依赖远程服务器、不打开浏览器**。手机内部用 [proot](https://github.com/proot-me/proot) 运行一个
Ubuntu(arm64) 用户态，里面跑 `dsh web`，App 用全屏 WebView 嵌入界面 ——
bash / 文件 / 子代理 / 网页搜索等全部工具都在手机上真实运行。

> 目标：把"终端里起服务 + 浏览器开界面"变成 **一个 App 图标点开即用**。

## 特性

- ✅ 完全自包含：rootfs + dsh 全部在手机内运行，无需电脑/服务器
- ✅ 桌面模式：默认以桌面视口(1280px) + Chrome UA 渲染网页端，三栏布局
- ✅ 浏览器式体验：捏合/双击/＋－缩放、刷新键、深色现代 UI
- ✅ 密钥安全：API Key 首次运行时粘贴，存 App 私有目录，不打包进 APK
- ✅ targetSdk 28（规避 Android 10+ SELinux W^X 对应用数据目录的执行限制）

## 下载

在 **Releases** 页面下载两个文件：

| 文件 | 说明 |
|---|---|
| `DSH-Mobile-<版本>.apk` | 安卓安装包（arm64，Android 8.0+），**装上即用**（运行时地址已内置） |
| `dsh-runtime-arm64.tar.gz` | 运行时（Ubuntu 24.04 + Node 24 + dsh），约 267MB |

## 安装与首次运行

1. 安装 APK（允许"未知来源"）。
2. 打开 App，**只需粘贴 DeepSeek API Key**（`sk-...`，DeepSeek 开放平台获取）。
   运行时包下载地址已内置（默认指向本仓库最新 Release 的运行时），无需填写。
3. 点「开始安装并启动」：自动下载 → 解压（需约 2GB 空间）→ 自动启动进入界面。

## 从源码构建

```bash
# 1) 构建 APK（依赖 JDK17+ / Android SDK 35 / python3；arm64 主机需 qemu-user）
cd app && ./build.sh            # 输出 ../releases/DSH-Mobile-<版本>.apk

# 2) 构建运行时（可选；arm64 Linux + root，产物直接上传 Releases）
cd runtime && sudo ./build-rootfs.sh ../releases
```

构建脚本会自动获取并编译 proot 静态二进制（见 THIRD_PARTY_NOTICES.md）。

## 发布到 GitHub Releases（维护者）

```bash
# 1) 生成专属签名密钥（务必保密，勿提交仓库；升级版本用同一密钥签名）
keytool -genkeypair -keystore dsh.keystore -alias dshkey -keyalg RSA -keysize 2048 -validity 10000
# 2) 构建产物
cd app && ./build.sh
# 3) 到 GitHub 仓库页 → Releases → 新建 Release（打 tag）
#   上传：DSH-Mobile-<版本>.apk 和 dsh-runtime-arm64.tar.gz
#   在 Release 说明里给出运行时直链，供用户填入 App
```

## 常见问题

- **启动失败/黑屏**：App 内「复制日志」把日志发出来排查；或 ⚙ → 重启服务。
- **error=13 Permission denied（旧版本）**：已通过 targetSdk 28 修复，请升级 APK。
- **界面显示手机版布局**：⚙ → 桌面模式 保持"开"。
- **首次下载失败**：确认"运行时包下载地址"可访问（用手机浏览器先打开试试）。

## 架构

```
App ──▶ proot(静态, 内置于 APK) ──▶ Ubuntu(arm64) rootfs ──▶ dsh web :3080 ──▶ WebView(127.0.0.1)
```

- `app/`：安卓壳（纯 Java，手动 aapt2/javac/d8 管线，无第三方依赖）
- `runtime/`：运行时构建脚本与说明
- `releases/`：构建产物（.gitignore，上传到 GitHub Releases 而非入库）

## 许可证

- 本仓库 App 源码：MIT（见 LICENSE）
- 内置二进制与依赖：见 THIRD_PARTY_NOTICES.md（注意 proot 为 GPL-2.0）

# 运行时（rootfs）说明

`dsh-runtime-arm64.tar.gz` 是手机里运行的核心：Ubuntu 24.04 (arm64) 用户态 +
Node.js 24 + `@deepseek-ai/dsh`（npm 全局安装），启动即跑 `dsh web --port 3080`。

- App 首次运行会下载并解压它（约 267MB，解压后约 1.5GB）。
- 版本要求：**必须与 APK 配套**（当前对应 dsh 0.1.0-rc.6）。
- 可用 `./build-rootfs.sh` 从零构建（arm64 Linux，需要 root）。
- 它**不包含任何 API Key**（App 首次运行由用户粘贴，写入 App 私有目录）。

## 托管方式（二选一）

1. **GitHub Releases**：把 `dsh-runtime-arm64.tar.gz` 作为 Release 附件上传，
   用户把 App 里的"运行时包下载地址"填成 Release 附件直链：
   `https://github.com/<你>/<仓库>/releases/download/<tag>/dsh-runtime-arm64.tar.gz`
2. **自建/对象存储**：放到任意 HTTPS 服务器，填对应 URL 即可。

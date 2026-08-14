#!/bin/bash
# 构建 DSH-Mobile 运行时（Ubuntu arm64 rootfs + Node 24 + @deepseek-ai/dsh）
#
# 用法（需要 root）：./build-rootfs.sh [输出目录]
# 产物：<输出目录>/dsh-runtime-arm64.tar.gz
#
# 注：本脚本在常规 Linux(arm64) 环境即可复现发布用的运行时包。
# 依赖：proot 或 chroot 可用、curl、tar；rootfs 内会安装 build-essential 用于编译 node-pty。
set -eu

OUT=${1:-$(cd "$(dirname "$0")/../releases" && pwd)}
mkdir -p "$OUT"
WORK=$(mktemp -d)
ROOTFS=$WORK/rootfs

UBUNTU_BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
NODE_MAJOR=24

echo "== [1] 下载 ubuntu-base (arm64) =="
curl -sL -o "$WORK/ubuntu-base.tar.gz" "$UBUNTU_BASE_URL"
mkdir -p "$ROOTFS"
tar xzf "$WORK/ubuntu-base.tar.gz" -C "$ROOTFS"

echo "== [2] 配置 DNS / hosts =="
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > "$ROOTFS/etc/resolv.conf"
printf '127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n' > "$ROOTFS/etc/hosts"

enter() { chroot "$ROOTFS" /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME=/root "$@"; }

echo "== [3] 安装 Node $NODE_MAJOR (官方 arm64 二进制) =="
NODE_VER=$(curl -s https://nodejs.org/dist/latest-v${NODE_MAJOR}.x/ \
  | grep -oE "node-v${NODE_MAJOR}\.[0-9]+\.[0-9]+-linux-arm64\.tar\.xz" | head -1)
curl -sL -o "$WORK/node.tar.xz" "https://nodejs.org/dist/latest-v${NODE_MAJOR}.x/$NODE_VER"
tar xf "$WORK/node.tar.xz" -C "$WORK"
cp -a "$WORK/${NODE_VER%.tar.xz}"/{bin,include,lib,share} "$ROOTFS/usr/local/"

echo "== [4] apt 基础包 =="
enter /bin/bash -c "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ca-certificates curl build-essential python3 >/dev/null"

echo "== [5] 安装 @deepseek-ai/dsh（npm≥11 需 --allow-scripts 编译 node-pty 等原生模块） =="
enter /bin/bash -c "npm install -g --allow-scripts=@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs @deepseek-ai/dsh@0.1.0-rc.6 2>&1 | tail -2"
enter /bin/bash -c "test -f /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/build/Release/pty.node && echo 'node-pty OK'"

echo "== [6] 预置 boot 脚本与凭据占位 =="
cat > "$ROOTFS/root/boot.sh" <<'BOOT'
#!/bin/bash
# DSH-Mobile 启动脚本（在 proot 内运行）
set -u
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export DSH_HOME=/root/.dsh
export DSH_TELEMETRY_DISABLED=1
mkdir -p "$DSH_HOME"
if [ -f /root/dsh.pid ]; then kill "$(cat /root/dsh.pid)" 2>/dev/null || true; rm -f /root/dsh.pid; fi
echo "=== dsh mobile boot: $(date) ===" >> /root/dsh.log
dsh web --port 3080 >> /root/dsh.log 2>&1 &
DPID=$!
echo $DPID > /root/dsh.pid
wait $DPID
BOOT
chmod +x "$ROOTFS/root/boot.sh"
mkdir -p "$ROOTFS/root/.dsh"
printf 'DEEPSEEK_API_KEY: __PHONE_KEY_PLACEHOLDER__\n' > "$ROOTFS/root/.dsh/.credentials.yaml"

echo "== [7] 清理（移除编译工具与缓存，减小体积） =="
enter /bin/bash -c "DEBIAN_FRONTEND=noninteractive apt-get purge -y -qq g++ gcc cpp >/dev/null 2>&1 || true; apt-get clean >/dev/null 2>&1 || true"
rm -rf "$ROOTFS/root/.npm" "$ROOTFS/root/.cache" "$ROOTFS/var/lib/apt/lists" "$ROOTFS/var/cache/apt" "$ROOTFS/tmp"/* 2>/dev/null || true
rm -rf "$ROOTFS/root/.dsh" "$ROOTFS/root/dsh"*.log "$ROOTFS/root/dsh.pid" 2>/dev/null || true

echo "== [8] 打包 =="
cd "$ROOTFS"
tar czf "$OUT/dsh-runtime-arm64.tar.gz" \
  --exclude='./root/.dsh' --exclude='./root/dsh*.log' --exclude='./root/.bash_history' \
  --exclude='./proc/*' --exclude='./sys/*' --exclude='./dev/*' \
  --exclude='./tmp/*' --exclude='./var/cache/*' --exclude='./var/lib/apt/lists/*' \
  --exclude='./root/.npm' --exclude='./root/.cache' .
rm -rf "$WORK"
ls -la "$OUT/dsh-runtime-arm64.tar.gz"
echo "DONE"

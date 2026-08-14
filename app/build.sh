#!/bin/bash
# DSH-Mobile APK 构建脚本（自包含）
#
# 依赖：
#   - JDK 17+（javac / keytool / jar）
#   - Android SDK：build-tools 35.0.0 + platforms/android-35（环境变量 ANDROID_HOME）
#   - python3（生成图标）
#   - proot 编译依赖：git / gcc / make / libtalloc-dev（仅首次构建 proot 时需要）
#   - 本机为 arm64 且 SDK 工具是 x86_64 时需要 qemu-user + libc6:amd64（脚本自动用 qemu 包装）
#
# 输出：../releases/DSH-Mobile-<version>.apk（已签名）
set -eu

SDK=${ANDROID_HOME:-/root/android-sdk}
BT=$SDK/build-tools/35.0.0
PLATFORM=$SDK/platforms/android-35/android.jar
SRC=$(cd "$(dirname "$0")" && pwd)
WORK=$SRC/build
OUT=$(cd "$SRC/../releases" && pwd)
KS=${DSH_KEYSTORE:-$SRC/../dsh.keystore}
KS_PASS=${DSH_KEYSTORE_PASS:-dsh123456}
KS_ALIAS=dshkey

VERSION_NAME=1.19
VERSION_CODE=20
APK_NAME="DSH-Mobile-${VERSION_NAME}.apk"

# 架构检测：x86_64 原生跑 SDK 工具；其他（arm64）用 qemu-x86_64
if [ "$(uname -m)" = "x86_64" ]; then
  AAPT2="$BT/aapt2"
  ZIPALIGN="$BT/zipalign"
else
  AAPT2="qemu-x86_64 $BT/aapt2"
  ZIPALIGN="qemu-x86_64 $BT/zipalign"
fi

echo "=== [0] 准备资源 ==="
mkdir -p $SRC/assets $OUT

# 内置运行时包（搜索 releases/ 或 runtime/）
RUNTIME=""
for cand in "$SRC/../releases/dsh-runtime-arm64.tar.gz" "$SRC/../runtime/dsh-runtime-arm64.tar.gz"; do
  if [ -f "$cand" ]; then RUNTIME="$cand"; break; fi
done
if [ -z "$RUNTIME" ]; then
  echo "ERROR: 找不到运行时包 dsh-runtime-arm64.tar.gz"
  echo "  请用 runtime/build-rootfs.sh 构建，或从 GitHub Releases 下载后放到 releases/ 或 runtime/"
  exit 1
fi
echo "   内置运行时: $RUNTIME ($(du -h "$RUNTIME" | cut -f1))"
cp "$RUNTIME" "$SRC/assets/runtime.tar.gz"

# 构建静态 proot（来自 proot-me/proot 源码，GPL-2.0，见 THIRD_PARTY_NOTICES.md）
if [ ! -f $SRC/assets/proot ]; then
  echo "   首次构建 proot 静态二进制（约 1 分钟）…"
  PS=$(mktemp -d)
  git clone --depth 1 https://github.com/proot-me/proot.git "$PS" >/dev/null 2>&1
  make -C "$PS/src" -j"$(nproc)" LDFLAGS="-static -Wl,-z,noexecstack -ltalloc" >/dev/null 2>&1
  cp "$PS/src/proot" $SRC/assets/proot
  rm -rf "$PS"
fi
ls -la $SRC/assets/proot

(cd "$SRC" && python3 gen_icon.py)

echo "=== [1/6] aapt2 compile 资源 ==="
rm -rf $WORK; mkdir -p $WORK/gen $WORK/obj $WORK/dex $OUT
$AAPT2 compile --dir $SRC/res -o $WORK/res.zip

echo "=== [2/6] aapt2 link ==="
$AAPT2 link \
  -o $WORK/base.apk \
  -I $PLATFORM \
  --manifest $SRC/AndroidManifest.xml \
  -R $WORK/res.zip \
  --java $WORK/gen \
  -A $SRC/assets \
  --auto-add-overlay \
  -0 gz \
  --min-sdk-version 26 \
  --target-sdk-version 28 \
  --version-code $VERSION_CODE \
  --version-name "$VERSION_NAME"

echo "=== [3/6] javac ==="
find $SRC/src $WORK/gen -name "*.java" > $WORK/sources.txt
javac -source 8 -target 8 -classpath $PLATFORM -d $WORK/obj @$WORK/sources.txt

echo "=== [4/6] d8 → dex ==="
find $WORK/obj -name "*.class" > $WORK/classes.txt
$BT/d8 --release --lib $PLATFORM --output $WORK/dex @$WORK/classes.txt

echo "=== [5/6] 打包 + zipalign ==="
cp $WORK/base.apk $WORK/unsigned.apk
(cd $WORK && jar uf unsigned.apk -C dex classes.dex)
$ZIPALIGN -f 4 $WORK/unsigned.apk $WORK/aligned.apk

echo "=== [6/6] 签名 ==="
if [ ! -f "$KS" ]; then
  echo "   生成签名密钥 $KS（请妥善保管，勿提交到仓库！）"
  keytool -genkeypair -v \
    -keystore "$KS" -alias $KS_ALIAS -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=DSH Mobile, OU=DSH, O=DSH, L=City, S=State, C=CN" \
    >/dev/null 2>&1
fi
$BT/apksigner sign \
  --ks "$KS" --ks-key-alias $KS_ALIAS \
  --ks-pass pass:"$KS_PASS" --key-pass pass:"$KS_PASS" \
  --out "$OUT/$APK_NAME" \
  $WORK/aligned.apk

echo "=== 校验 ==="
$BT/apksigner verify "$OUT/$APK_NAME" && echo "signature OK"
ls -la "$OUT/$APK_NAME"
echo "BUILD OK → $OUT/$APK_NAME"

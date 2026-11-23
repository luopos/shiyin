#!/usr/bin/env bash
# 拾音 TV 独立构建：aapt2 -> javac -> d8 -> zipalign -> apksigner。
# 与手机版共用同一份共享层源码（不复制），产物 com.shiyin.tv，可与 com.shiyin.app 共存。
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/d/androidbuild/sdk}}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/android-14}"
PLATFORM="${ANDROID_PLATFORM:-$SDK/platforms/android-13}"
export JAVA_HOME="${JAVA_HOME:-/d/androidbuild/jdk-17.0.2}"
EXE=""
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
esac
JAVAC="$JAVA_HOME/bin/javac$EXE"
JAVA="$JAVA_HOME/bin/java$EXE"
JAR="$JAVA_HOME/bin/jar$EXE"
KEYTOOL="$JAVA_HOME/bin/keytool$EXE"
AAPT2="$BT/aapt2$EXE"
ZIPALIGN="$BT/zipalign$EXE"
APKSIGNER="$BT/apksigner.bat"
[ -f "$APKSIGNER" ] || APKSIGNER="$BT/apksigner"

for file in "$AAPT2" "$BT/lib/d8.jar" "$ZIPALIGN" "$APKSIGNER" \
    "$PLATFORM/android.jar" "$JAVAC" "$JAVA" "$JAR" "$KEYTOOL"; do
    [ -f "$file" ] || { echo "缺少构建依赖：$file" >&2; exit 1; }
done
for tool in find sort grep; do
    command -v "$tool" >/dev/null || { echo "缺少命令：$tool" >&2; exit 1; }
done
# Ubuntu 24.04 只有 python3；Windows 上通常两者都有，优先 python。
PYTHON=python
command -v python >/dev/null 2>&1 || PYTHON=python3
command -v "$PYTHON" >/dev/null 2>&1 || { echo "缺少 python/python3" >&2; exit 1; }
# AppUpdate 的 HTML 兜底依赖 jsoup，TV 端复用同一份实现，classpath 也要带上
if command -v cygpath >/dev/null; then
    CP="$(cygpath -m "$PLATFORM/android.jar");$(cygpath -m "$PWD/../libs/jsoup.jar")"
else
    CP="$PLATFORM/android.jar:$PWD/../libs/jsoup.jar"
fi
abspath() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

OUT="${TV_BUILD_DIR:-build}"
rm -rf -- "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 0/7 收集源码（TV + 共享层，不复制文件） =="
# 共享层：手机版源码路径直接编译。用相对路径，Windows 上 javac 才认 /d/... 这种 MSYS 路径。
SHARED=(
    "../src/com/shiyin/app/model/Book.java"
    "../src/com/shiyin/app/model/Cat.java"
    "../src/com/shiyin/app/model/Chapter.java"
    "../src/com/shiyin/app/source/Source.java"
    "../src/com/shiyin/app/source/JsonSource.java"
    "../src/com/shiyin/app/source/Sources.java"
    "../src/com/shiyin/app/source/Subscriptions.java"
    "../src/com/shiyin/app/source/ChapterRepository.java"
    "../src/com/shiyin/app/store/Prefs.java"
    "../src/com/shiyin/app/net/Http.java"
    "../src/com/shiyin/app/net/AppUpdate.java"
    "../src/com/shiyin/app/net/UpdateInstaller.java"
    "../src/com/shiyin/app/util/Bg.java"
    "../src/com/shiyin/app/util/Ui.java"
    "../src/com/shiyin/app/util/ImgView.java"
)
# 播放层不能依赖手机版页面：TV 包没有 ui 包，依赖了就一定编不过。
# 宁可在这里明确失败，也不要悄悄跳过 —— 跳过会产出「能装但不能播」的 APK。
for f in "../src/com/shiyin/app/play/Player.java" \
         "../src/com/shiyin/app/play/PlayerService.java"; do
    if grep -qE '^[[:space:]]*import[[:space:]]+com\.shiyin\.app\.ui\.' "$f"; then
        echo "错误：$f 仍 import com.shiyin.app.ui，无法编进独立 TV 包。" >&2
        exit 1
    fi
done
SHARED+=(
    "../src/com/shiyin/app/play/Player.java"
    "../src/com/shiyin/app/play/PlayerService.java"
)

{
    find src -name "*.java"
    for f in "${SHARED[@]}"; do
        [ -f "$f" ] || { echo "缺少共享层文件：$PWD/$f" >&2; exit 1; }
        printf '%s\n' "$f"
    done
    # impl-a 新增的兼容层：目录存在才加。
    if [ -d ../src/com/shiyin/app/compat ]; then
        find ../src/com/shiyin/app/compat -name "*.java"
    fi
    # 青少年模式内核（手机与 TV 共用）
    if [ -d ../src/com/shiyin/app/kid ]; then
        find ../src/com/shiyin/app/kid -name "*.java"
    fi
} | LC_ALL=C sort > "$OUT/sources.txt"
echo "源码 $(wc -l < "$OUT/sources.txt") 个（TV + 共享层 + 播放层）"

# 低版本硬约束：这几个类属于手机包专有（开机提醒、活动窗适配）或引用 API>16，
# 一旦被通配进来，TV 包会在 Android 4.1/4.4 上 NoClassDefFoundError。
for banned in "play/BootReceiver.java" "ui/" "phone/" "/MainActivity.java"; do
    if grep -qF "$banned" "$OUT/sources.txt"; then
        echo "错误：TV 构建混入了手机包专有源码：$banned" >&2
        exit 1
    fi
done
if ! grep -q "kid/KidMode.java" "$OUT/sources.txt"; then
    echo "错误：缺少 app/kid/KidMode.java（青少年模式内核）。" >&2
    exit 1
fi

echo "== 1/7 编译资源 =="
"$AAPT2" compile --dir res -o "$OUT/res.zip"

echo "== 2/7 链接资源（minSdk 16 / targetSdk 33） =="
"$AAPT2" link -o "$OUT/base.apk" -I "$PLATFORM/android.jar" \
    --manifest AndroidManifest.xml -R "$OUT/res.zip" --java "$OUT/gen" \
    --auto-add-overlay --min-sdk-version 16 --target-sdk-version 33

echo "== 3/7 编译 Java（-source 8，API16 可运行） =="
"$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 \
    -source 8 -target 8 -nowarn -Xlint:-options -classpath "$CP" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "== 4/7 生成 DEX（d8 --min-api 16） =="
"$JAR" cf "$OUT/program.jar" -C "$OUT/classes" .
# jsoup 也要一起进 dex：AppUpdate 的 HTML 兜底在运行时需要它
"$JAVA" -Xmx1536M -cp "$(abspath "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --lib "$(abspath "$PLATFORM/android.jar")" --min-api 16 \
    --output "$(abspath "$PWD/$OUT/dex")" \
    "$(abspath "$PWD/$OUT/program.jar")" "$(abspath "$PWD/../libs/jsoup.jar")"

echo "== 5/7 封装 APK =="
TV_OUT_DIR="$OUT" "$PYTHON" - <<'PY'
import os
from pathlib import Path
import shutil
import zipfile
out = Path(os.environ['TV_OUT_DIR'])
shutil.copyfile(out / 'base.apk', out / 'unsigned.apk')
dex_files = sorted((out / 'dex').glob('classes*.dex'))
if not dex_files:
    raise SystemExit('DEX 生成失败')
with zipfile.ZipFile(out / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    for dex in dex_files:
        archive.write(dex, dex.name)
PY

echo "== 6/7 对齐 =="
"$ZIPALIGN" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== 7/7 签名并验证 =="
# 与手机版同一 keystore，保证两个 APK 证书指纹一致。
KEYSTORE="${SHIYIN_KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${SHIYIN_KEYSTORE_PASS:-android}"
KS_ALIAS="${SHIYIN_KEYSTORE_ALIAS:-androiddebugkey}"
if [ ! -f "$KEYSTORE" ]; then
    echo "未找到 keystore，生成 debug 签名：$KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair -v -keystore "$KEYSTORE" -storepass "$KS_PASS" \
        -keypass "$KS_PASS" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
mkdir -p "$OUT/signed"
APK="$OUT/signed/shiyin-tv.apk"
cp "$OUT/aligned.apk" "$APK"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" "$APK"
"$APKSIGNER" verify --verbose "$APK" | head -5
"$ZIPALIGN" -c -p 4 "$APK"
echo "== badging =="
"$AAPT2" dump badging "$APK" | head -6
echo "产物：$PWD/$APK"

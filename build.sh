#!/usr/bin/env bash
# 无 Gradle 构建：aapt2 -> javac -> d8 -> zipalign -> apksigner。
# 只依赖 Android SDK + JDK + Python，签名用 SDK 自带的 apksigner 与本机 debug keystore。
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

# 依赖在清理旧产物之前一次检查完，避免编到一半才报错。
for file in "$AAPT2" "$BT/lib/d8.jar" "$ZIPALIGN" "$APKSIGNER" \
    "$PLATFORM/android.jar" "$JAVAC" "$JAVA" "$JAR" "$KEYTOOL" libs/jsoup.jar; do
    [ -f "$file" ] || { echo "缺少构建依赖：$file" >&2; exit 1; }
done
for tool in python find sort; do
    command -v "$tool" >/dev/null || { echo "缺少命令：$tool" >&2; exit 1; }
done
# 非 Windows 才需要 cygpath；Windows 上 javac 的 classpath 必须用 Windows 路径 + 分号。
if command -v cygpath >/dev/null; then CP="$(cygpath -m "$PLATFORM/android.jar");$(cygpath -m "$PWD/libs/jsoup.jar")"
else CP="$PLATFORM/android.jar:$PWD/libs/jsoup.jar"; fi
abspath() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

OUT=build
rm -rf -- "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1/7 编译资源 =="
"$AAPT2" compile --dir res -o "$OUT/res.zip"
echo "== 2/7 链接资源 =="
"$AAPT2" link -o "$OUT/base.apk" -I "$PLATFORM/android.jar" \
    --manifest AndroidManifest.xml -R "$OUT/res.zip" --java "$OUT/gen" \
    --auto-add-overlay -A assets

echo "== 3/7 编译 Java =="
find src "$OUT/gen" -name "*.java" | LC_ALL=C sort > "$OUT/sources.txt"
"$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 \
    -source 11 -target 11 -nowarn -classpath "$CP" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "== 4/7 生成 DEX =="
# 直接调 D8，不经过 d8.bat：Windows 批处理会按自己的 cwd 解析相对路径。
"$JAR" cf "$OUT/program.jar" -C "$OUT/classes" .
"$JAVA" -Xmx1024M -cp "$(abspath "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --lib "$(abspath "$PLATFORM/android.jar")" --min-api 24 \
    --output "$(abspath "$PWD/$OUT/dex")" \
    "$(abspath "$PWD/$OUT/program.jar")" "$(abspath "$PWD/libs/jsoup.jar")"

echo "== 5/7 封装 APK =="
python - <<'PY'
from pathlib import Path
import shutil
import zipfile
out = Path('build')
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
# 没配正式签名就用 debug keystore；本机没有就现生成一个，保证仓库开箱可编。
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
APK="$OUT/signed/shiyin.apk"
cp "$OUT/aligned.apk" "$APK"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" "$APK"
"$APKSIGNER" verify --verbose "$APK" | head -5
"$ZIPALIGN" -c -p 4 "$APK"
echo "产物：$PWD/$APK"

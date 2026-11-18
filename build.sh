#!/usr/bin/env bash
# 无 Gradle 构建：aapt2 -> javac -> d8 -> zipalign -> 签名及校验。
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-/d/androidbuild/sdk}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/android-14}"
PLATFORM="${ANDROID_PLATFORM:-$SDK/platforms/android-13}"
export JAVA_HOME="${JAVA_HOME:-/d/androidbuild/jdk-17.0.2}"
JAVAC="$JAVA_HOME/bin/javac.exe"
JAVA="$JAVA_HOME/bin/java.exe"
SIGNER=../android/uber-apk-signer.jar
[ -f "$SIGNER" ] || SIGNER=../uber-apk-signer.jar

# 用户侧环境在清理旧产物之前一次检查完毕。
for file in "$BT/aapt2.exe" "$BT/lib/d8.jar" "$BT/zipalign.exe" \
    "$PLATFORM/android.jar" "$JAVAC" "$JAVA" "$JAVA_HOME/bin/jar.exe" "$SIGNER" libs/jsoup.jar; do
    [ -f "$file" ] || { echo "缺少构建依赖：$file" >&2; exit 1; }
done
for tool in python cygpath find sort; do
    command -v "$tool" >/dev/null || { echo "缺少命令：$tool" >&2; exit 1; }
done

# Windows javac 的 classpath 统一使用 Windows 路径和分号。
CP="$(cygpath -m "$PLATFORM/android.jar");$(cygpath -m "$PWD/libs/jsoup.jar")"
OUT=build
rm -rf -- "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1/7 编译资源 =="
"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip"
echo "== 2/7 链接资源 =="
"$BT/aapt2.exe" link -o "$OUT/base.apk" -I "$PLATFORM/android.jar" \
    --manifest AndroidManifest.xml -R "$OUT/res.zip" --java "$OUT/gen" \
    --auto-add-overlay -A assets

echo "== 3/7 编译 Java =="
find src "$OUT/gen" -name "*.java" | LC_ALL=C sort > "$OUT/sources.txt"
"$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 \
    -source 11 -target 11 -nowarn -classpath "$CP" \
    -d "$OUT/classes" @"$OUT/sources.txt"
echo "== 4/7 生成 DEX =="
"$JAVA_HOME/bin/jar.exe" cf "$OUT/program.jar" -C "$OUT/classes" .
"$JAVA" -Xmx1024M -cp "$(cygpath -m "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --lib "$(cygpath -m "$PLATFORM/android.jar")" --min-api 24 \
    --output "$(cygpath -m "$PWD/$OUT/dex")" \
    "$(cygpath -m "$PWD/$OUT/program.jar")" "$(cygpath -m "$PWD/libs/jsoup.jar")"

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
"$BT/zipalign.exe" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
echo "== 7/7 签名并验证 =="
"$JAVA" -jar "$SIGNER" -a "$OUT/aligned.apk" --allowResign -o "$OUT/signed"
apks=("$OUT"/signed/*.apk)
[ -f "${apks[0]}" ] || { echo "签名未生成 APK" >&2; exit 1; }
for apk in "${apks[@]}"; do
    "$BT/zipalign.exe" -c -p 4 "$apk"
    echo "产物：$PWD/$apk"
done

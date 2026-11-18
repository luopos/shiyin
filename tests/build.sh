#!/usr/bin/env bash
# 独立 instrumentation APK，生产构建不包含 tests/。
# 依赖主包先构建（需要 build/classes 与 build/program.jar）。
set -euo pipefail
cd "$(dirname "$0")/.."

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
AAPT2="$BT/aapt2$EXE"
APKSIGNER="$BT/apksigner.bat"
[ -f "$APKSIGNER" ] || APKSIGNER="$BT/apksigner"

[ -f build/program.jar ] || { echo "先运行 build.sh 生成主包" >&2; exit 1; }

if command -v cygpath >/dev/null; then
    CP="$(cygpath -m "$PLATFORM/android.jar");$(cygpath -m "$PWD/build/classes")"
else
    CP="$PLATFORM/android.jar:$PWD/build/classes"
fi
abspath() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

OUT=tests/build
rm -rf -- "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"
"$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 \
    -source 11 -target 11 -nowarn -classpath "$CP" -d "$OUT/classes" \
    tests/src/com/shiyin/test/PlaybackTest.java
"$JAR" cf "$OUT/program.jar" -C "$OUT/classes" .
"$JAVA" -Xmx1024M -cp "$(abspath "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --lib "$(abspath "$PLATFORM/android.jar")" --classpath "$(abspath "$PWD/build/program.jar")" \
    --min-api 24 --output "$(abspath "$PWD/$OUT/dex")" "$(abspath "$PWD/$OUT/program.jar")"
"$AAPT2" link -I "$PLATFORM/android.jar" --manifest tests/AndroidManifest.xml -o "$OUT/test-unsigned.apk"
python - <<'PY'
import zipfile
with zipfile.ZipFile('tests/build/test-unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    archive.write('tests/build/dex/classes.dex', 'classes.dex')
PY

KEYSTORE="${SHIYIN_KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${SHIYIN_KEYSTORE_PASS:-android}"
KS_ALIAS="${SHIYIN_KEYSTORE_ALIAS:-androiddebugkey}"
[ -f "$KEYSTORE" ] || { echo "缺少 keystore，先运行 build.sh 生成" >&2; exit 1; }
cp "$OUT/test-unsigned.apk" "$OUT/test.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" "$OUT/test.apk"
"$APKSIGNER" verify "$OUT/test.apk" >/dev/null
echo "产物：$PWD/$OUT/test.apk"

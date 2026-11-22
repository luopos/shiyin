#!/usr/bin/env bash
# TV 回归测试 instrumentation APK。先跑 tv/build.sh，再用这个。
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

MAIN="${TV_BUILD_DIR:-build}"
[ -f "$MAIN/program.jar" ] || { echo "先运行 tv/build.sh 生成主包（$MAIN/program.jar 不存在）" >&2; exit 1; }

if command -v cygpath >/dev/null; then
    CP="$(cygpath -m "$PLATFORM/android.jar");$(cygpath -m "$PWD/$MAIN/classes")"
else
    CP="$PLATFORM/android.jar:$PWD/$MAIN/classes"
fi
abspath() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

OUT=tests/build
rm -rf -- "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"
"$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 \
    -source 8 -target 8 -nowarn -Xlint:-options -classpath "$CP" -d "$OUT/classes" \
    tests/src/com/shiyin/tv/test/TvRegressionTest.java
"$JAR" cf "$OUT/program.jar" -C "$OUT/classes" .
"$JAVA" -Xmx1024M -cp "$(abspath "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --lib "$(abspath "$PLATFORM/android.jar")" \
    --classpath "$(abspath "$PWD/$MAIN/program.jar")" \
    --min-api 16 --output "$(abspath "$PWD/$OUT/dex")" "$(abspath "$PWD/$OUT/program.jar")"
"$AAPT2" link -I "$PLATFORM/android.jar" --manifest tests/AndroidManifest.xml \
    --min-sdk-version 16 --target-sdk-version 33 -o "$OUT/test-unsigned.apk"
python - <<'PY'
import zipfile
with zipfile.ZipFile('tests/build/test-unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    archive.write('tests/build/dex/classes.dex', 'classes.dex')
PY

KEYSTORE="${SHIYIN_KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${SHIYIN_KEYSTORE_PASS:-android}"
KS_ALIAS="${SHIYIN_KEYSTORE_ALIAS:-androiddebugkey}"
[ -f "$KEYSTORE" ] || { echo "缺少 keystore，先运行 tv/build.sh 生成" >&2; exit 1; }
cp "$OUT/test-unsigned.apk" "$OUT/shiyin-tv-test.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" "$OUT/shiyin-tv-test.apk"
"$APKSIGNER" verify "$OUT/shiyin-tv-test.apk" >/dev/null
echo "产物：$PWD/$OUT/shiyin-tv-test.apk"

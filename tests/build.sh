#!/usr/bin/env bash
# 独立 instrumentation APK，生产构建不包含 tests/。
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_SDK_ROOT:-/d/androidbuild/sdk}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/android-14}"
PLATFORM="${ANDROID_PLATFORM:-$SDK/platforms/android-13}"
export JAVA_HOME="${JAVA_HOME:-/d/androidbuild/jdk-17.0.2}"
OUT=tests/build
mkdir -p "$OUT/classes" "$OUT/dex"
CP="$(cygpath -m "$PLATFORM/android.jar");$(cygpath -m "$PWD/build/classes")"
"$JAVA_HOME/bin/javac.exe" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 -source 11 -target 11 -nowarn \
    -classpath "$CP" -d "$OUT/classes" tests/src/com/shiyin/test/PlaybackTest.java
"$JAVA_HOME/bin/jar.exe" cf "$OUT/program.jar" -C "$OUT/classes" .
"$JAVA_HOME/bin/java.exe" -Xmx1024M -cp "$(cygpath -m "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --lib "$(cygpath -m "$PLATFORM/android.jar")" --classpath "$(cygpath -m "$PWD/build/program.jar")" \
    --min-api 24 --output "$(cygpath -m "$PWD/$OUT/dex")" "$(cygpath -m "$PWD/$OUT/program.jar")"
"$BT/aapt2.exe" link -I "$PLATFORM/android.jar" --manifest tests/AndroidManifest.xml -o "$OUT/test.apk"
python - <<'PY'
import zipfile
with zipfile.ZipFile('tests/build/test.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    archive.write('tests/build/dex/classes.dex', 'classes.dex')
PY
SIGNER=../android/uber-apk-signer.jar
[ -f "$SIGNER" ] || SIGNER=../uber-apk-signer.jar
"$JAVA_HOME/bin/java.exe" -jar "$SIGNER" -a "$OUT/test.apk" --allowResign --overwrite

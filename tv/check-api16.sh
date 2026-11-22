#!/usr/bin/env bash
# TV 包低版本兼容关卡：编译期 + 字节码期双重校验。
#
#   1) 用 API16 bootclasspath 重新编译 TV 全部源码（含共享层），API>16 的符号直接编不过。
#   2) 对真实构建产物做字节码扫描，确认 dex 里没有 API>16 引用。
#
# 用法：bash tv/check-api16.sh
# 退出：0 通过；非 0 失败（会指出具体符号）。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# 仓库 work/native 的上一级是工作目录，工具与 API 基准都在 <workspace>/work/api16。
WORK="$(cd "$ROOT/.." && pwd)"
TOOLS="$(cd "$WORK/.." && pwd)/work/api16"
cd "$ROOT"

MAX_API=16
JAVA_HOME="${JAVA_HOME:-/d/androidbuild/jdk-17.0.2}"
EXE=""
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
esac
JAVAC="$JAVA_HOME/bin/javac$EXE"
BOOT="$WORK/api16/build/api16boot.jar"
API_XML="${ANDROID_PLATFORM:-/d/androidbuild/sdk/platforms/android-13}/data/api-versions.xml"
CHECKER="$WORK/api16/apicheck.py"
APK="$HERE/build/signed/shiyin-tv.apk"

fail=0
say() { printf '%s\n' "$*"; }

if [ ! -f "$BOOT" ]; then
    say "缺少 API16 基准 jar：$BOOT"
    say "先生成：python \"$WORK/api16/make_api16_boot.py\""
    exit 2
fi

# ---------- 1) 编译期校验 ----------
say "== [1/2] API$MAX_API 编译期校验 =="
OUT="$HERE/build/api16check"
rm -rf -- "$OUT"
mkdir -p "$OUT/classes"

# 复用构建脚本产出的源码清单，保证校验范围与真实构建完全一致。
SRC_LIST="$HERE/build/sources.txt"
if [ ! -f "$SRC_LIST" ]; then
    say "缺少源码清单（先跑一次 tv/build.sh）：$SRC_LIST"
    exit 2
fi
# sources.txt 里的路径是相对 tv/ 的，这里统一在 tv/ 目录下解析。
( cd "$HERE" && "$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 \
    -source 8 -target 8 -nowarn -Xlint:-options \
    -bootclasspath "$(cygpath -m "$BOOT" 2>/dev/null || printf '%s' "$BOOT")" \
    -classpath "$(cygpath -m "$ROOT/libs/jsoup.jar" 2>/dev/null || printf '%s' "$ROOT/libs/jsoup.jar")" \
    -d "$OUT/classes" @"$SRC_LIST" ) || {
    say "编译期校验失败：源码用到了 API > $MAX_API 的类型。"
    fail=1
}

# ---------- 2) 字节码校验 ----------
say "== [2/2] 构建产物字节码校验 =="
if [ -f "$APK" ]; then
    python "$CHECKER" --api-xml "$API_XML" --max-api "$MAX_API" \
        --apk "$APK" --dex-strings || fail=1
else
    say "未找到 $APK（先跑 tv/build.sh）。仅完成编译期校验。"
fi

if [ "$fail" -ne 0 ]; then
    say ""
    say "结果：FAIL —— 存在 API > $MAX_API 的引用，在 Android 4.1/4.4 上会 NoSuchMethodError/NoClassDefFoundError。"
    exit 1
fi
say ""
say "结果：PASS —— 可在 API $MAX_API (Android 4.1) 及以上运行。"
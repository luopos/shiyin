#!/usr/bin/env bash
# API16 静态编译校验：把 android.* 全部按 Android 4.1.1（API16）解析编译
# TV 源码 + 共享层 + compat 层。必须先跑 tv/build.sh 生成 build/sources.txt。
set -euo pipefail
cd "$(dirname "$0")/.."

API16="${API16_JAR:-D:/WorkBuddy/2026-11-17-22-19-54/work/api16/android-4.1.1.4.jar}"
export JAVA_HOME="${JAVA_HOME:-/d/androidbuild/jdk-17.0.2}"
EXE=""
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
esac
JAVAC="$JAVA_HOME/bin/javac$EXE"

[ -f "$API16" ] || { echo "缺少 API16 基准 jar：$API16" >&2; exit 1; }
MAIN="${TV_BUILD_DIR:-build}"
[ -f "$MAIN/sources.txt" ] || { echo "先运行 tv/build.sh 生成 $MAIN/sources.txt" >&2; exit 1; }

OUT="$MAIN/api16check"
rm -rf -- "$OUT"
mkdir -p "$OUT"
# R.java 由 aapt2 按 API33 生成，只有 int 常量，不属于源码校验范围。
grep -v "/gen/" "$MAIN/sources.txt" > "$MAIN/api16-sources.txt"
# 可选：EXCLUDE=正则 跳过并行开发中的文件（默认不跳过任何文件）。
if [ -n "${EXCLUDE:-}" ]; then
    echo "注意：按 EXCLUDE=$EXCLUDE 排除文件（仅用于并行开发期的局部校验）"
    grep -vE "$EXCLUDE" "$MAIN/api16-sources.txt" > "$MAIN/api16-sources-filtered.txt"
    mv "$MAIN/api16-sources-filtered.txt" "$MAIN/api16-sources.txt"
fi

if command -v cygpath >/dev/null; then
    BOOT="$(cygpath -m "$API16")"
else
    BOOT="$API16"
fi

# com.google.android:android:4.1.1.4 里没有 org.json（Maven 老包裁剪过），
# 从平台 android.jar 只抽出 org/json/* 做一个补充 jar：android.* 仍然只从 API16 jar 解析，
# 避免 NotificationChannel 这种新 API 被 API33 的 jar 意外放行。
PLATFORM="${ANDROID_PLATFORM:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/d/androidbuild/sdk}}/platforms/android-13}"
ORGJSON="$MAIN/orgjson-only.jar"
PYTHON=python
command -v python >/dev/null 2>&1 || PYTHON=python3
"$PYTHON" - "$PLATFORM/android.jar" "$ORGJSON" <<'PYJAR'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as fin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as fout:
    for item in fin.infolist():
        if item.filename.startswith('org/json/') and item.filename.endswith('.class'):
            fout.writestr(item, fin.read(item.filename))
PYJAR
if command -v cygpath >/dev/null; then
    ORGJSON="$(cygpath -m "$ORGJSON")"
fi

# 说明：com.google.android:android:4.1.1.4 只有 android.* / dalvik.*，没有 java.lang
#（java.* 由 JDK 提供），所以用 -classpath 而不是 -bootclasspath：
# android.* 全部按 API16 解析；java.* 的新 API 用下面的关键字扫描兜底。
echo "== API16 静态编译：$(wc -l < "$MAIN/api16-sources.txt") 个源文件 =="
if "$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 \
    -source 8 -target 8 -nowarn -Xlint:-options -classpath "$BOOT;$ORGJSON" \
    -d "$OUT" @"$MAIN/api16-sources.txt"; then
    echo "API16 编译通过：0 error"
else
    echo "API16 编译失败：见上面的 javac 错误" >&2
    exit 1
fi

echo "== API16 java.* 高危 API 扫描（命中即失败） =="
FORBIDDEN='java\.util\.function\.|java\.util\.stream\.|java\.nio\.charset\.StandardCharsets|java\.util\.Objects|\.removeIf\(|String\.join\(|Long\.compare\(|Integer\.compare\(|Double\.compare\(|getSystemService\([A-Za-z0-9_]+\.[Cc]lass'
HITS=0
while IFS= read -r f; do
    # 注释里提到类名不算使用：过滤掉 // 与 * 开头的说明行。
    MATCHES=$(grep -nE "$FORBIDDEN" "$f" 2>/dev/null         | grep -vE '^[0-9]+:[[:space:]]*(\*|/\*|//)' || true)
    if [ -n "$MATCHES" ]; then
        echo "!! $f"
        printf '%s
' "$MATCHES" | head -5
        HITS=$((HITS + 1))
    fi
done < "$MAIN/api16-sources.txt"
if [ "$HITS" -gt 0 ]; then
    echo "API16 java.* 扫描失败：$HITS 个文件命中" >&2
    exit 1
fi
echo "API16 java.* 扫描通过：0 命中"

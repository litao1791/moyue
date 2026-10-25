#!/usr/bin/env bash
# 在桌面 JVM 上跑全部测试（不需要模拟器、不需要 Android SDK）。
# 用法：bash tools/run_tests.sh
set -euo pipefail

cd "$(dirname "$0")/.."

OUT="${TMPDIR:-/tmp}/moyue-test"
rm -rf "$OUT"
mkdir -p "$OUT"

SRC=app/src/main/java/com/moyue/reader

javac -encoding UTF-8 -d "$OUT" \
    test/jvm/android/graphics/*.java \
    test/jvm/*.java \
    "$SRC"/text/*.java \
    "$SRC"/epub/*.java \
    "$SRC"/book/*.java \
    "$SRC"/model/*.java \
    "$SRC"/tts/VoiceStyle.java \
    "$SRC"/ui/CurlGeometry.java \
    "$SRC"/ui/PageCache.java

TOTAL=0
FAIL=0
for T in CurlTest ParseTest ParseTest2 TxtTest ReadTest PageCacheTest TextureTest; do
    printf '%-16s ' "$T"
    LINE=$(java -cp "$OUT" "$T" | grep -E '通过 [0-9]+ 项' | tail -1 || true)
    echo "$LINE"
    N=$(echo "$LINE" | sed -n 's/.*通过 \([0-9]*\) 项.*/\1/p')
    F=$(echo "$LINE" | sed -n 's/.*失败 \([0-9]*\) 项.*/\1/p')
    TOTAL=$((TOTAL + ${N:-0}))
    FAIL=$((FAIL + ${F:-0}))
done

echo "----------------------------------------"
echo "合计：通过 $TOTAL 项，失败 $FAIL 项"
[ "$FAIL" -eq 0 ]

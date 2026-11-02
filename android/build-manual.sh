#!/usr/bin/env bash
# 手工构建链：aapt2 → javac → d8 → 打包 → zipalign → apksigner。
# 这台机器只有 1 核 500MB 可用内存，Gradle + AGP 跑不动，也不需要用。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-35.0.0}"
PLATFORM="${ANDROID_PLATFORM:-android-35}"
BT="$SDK/build-tools/$BUILD_TOOLS"
PLATFORM_JAR="$SDK/platforms/$PLATFORM/android.jar"

VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$ROOT/AndroidManifest.xml" | head -1)"
NAME="${1:-PyMoney-v${VERSION}.apk}"
OUT_APK="${OUT_APK:-$ROOT/../$NAME}"
KS="$ROOT/pymoney.jks"
export PYMONEY_KS_PASS="${PYMONEY_KS_PASS:-pymoney}"
export PYMONEY_KS_ALIAS="${PYMONEY_KS_ALIAS:-pymoney}"
KS_PASS="$PYMONEY_KS_PASS"
KS_ALIAS="$PYMONEY_KS_ALIAS"

MIN_API=24
WORK="$ROOT/build"
GEN="$WORK/gen"
OBJ="$WORK/obj"
CLS="$WORK/classes"
DEX="$WORK/dex"

rm -rf "$GEN" "$OBJ" "$CLS" "$DEX"
mkdir -p "$GEN" "$OBJ" "$CLS" "$DEX"

echo "==> 1/8 编译资源"
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OBJ/res.zip"

echo "==> 2/8 链接资源"
"$BT/aapt2" link \
    --auto-add-overlay \
    -I "$PLATFORM_JAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    --java "$GEN" \
    -o "$WORK/base.apk" \
    "$OBJ/res.zip"

echo "==> 3/8 编译 Java"
find "$ROOT/src" "$GEN" -name '*.java' > "$WORK/srcs.txt"
set +e
javac -nowarn -encoding UTF-8 \
    -source 8 -target 8 \
    -bootclasspath "$PLATFORM_JAR" \
    -d "$CLS" @"$WORK/srcs.txt" 2>&1 | grep -v 'bootstrap class path'
JAVAC_STATUS=${PIPESTATUS[0]}
set -e
if [ "$JAVAC_STATUS" -ne 0 ]; then
    echo "编译失败：javac 退出码 $JAVAC_STATUS" >&2
    exit 1
fi
if [ ! -f "$CLS/com/pymoney/ui/MainActivity.class" ]; then
    echo "编译失败：没有产出 MainActivity.class" >&2
    exit 1
fi

echo "==> 4/8 转 dex"
find "$CLS" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api "$MIN_API" --lib "$PLATFORM_JAR" \
    --output "$DEX" $(cat "$WORK/classes.txt")
mv "$DEX/classes.dex" "$WORK/classes.dex"

echo "==> 5/8 打包"
cp "$WORK/base.apk" "$WORK/unsigned.apk"
python3 - "$WORK/unsigned.apk" "$WORK/classes.dex" <<'PY'
import shutil, sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
shutil.copyfile(apk, tmp)
with zipfile.ZipFile(tmp, "a") as z:
    z.write(dex, "classes.dex")          # 存成不压缩，签名和加载都更稳
shutil.move(tmp, apk)
PY

echo "==> 6/8 对齐"
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

echo "==> 7/8 签名"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -v -keystore "$KS" -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=PyMoney, OU=personal, O=PyMoney, L=Beijing, C=CN"
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass env:PYMONEY_KS_PASS --key-pass env:PYMONEY_KS_PASS \
    --min-sdk-version "$MIN_API" --out "$OUT_APK" "$WORK/aligned.apk"

echo "==> 8/8 结构自检"
FAIL=""
# ① 源码里不许出现 lambda：android.jar 的 LambdaMetafactory 是 stub，javac 会 fatal
if grep -RnE '\)\s*->|[A-Za-z_]\s*::\s*[A-Za-z_]+' "$ROOT/src" --include='*.java' >/dev/null 2>&1; then
    echo "  ✗ 源码里出现了 lambda 或方法引用："
    grep -RnE '\)\s*->|[A-Za-z_]\s*::\s*[A-Za-z_]+' "$ROOT/src" --include='*.java' | head -5
    FAIL="lambda"
fi
if find "$CLS" -name '*lambda*' -print -quit | grep -q .; then
    echo "  ✗ 编译产物里有 *lambda* 类，说明匿名内部类被换成 invokedynamic 了"
    FAIL="$FAIL lambda-class"
fi
# ② dex 层面再确认一次
"$BT/dexdump" -d "$WORK/classes.dex" > "$WORK/dex.txt" 2>/dev/null || true
DYN=$(grep -c 'LambdaMetafactory\|invokedynamic' "$WORK/dex.txt" || true)
if [ "${DYN:-1}" != "0" ]; then
    echo "  ✗ dex 里还有 $DYN 处 invokedynamic/LambdaMetafactory"
    FAIL="$FAIL dex-dynamic"
fi
for CL in BackupTarget PlainCopy ui/SecurityActivity ui/AppearanceActivity ui/Theme \
            db/Fingerprint db/Migrator Storage \
            core/BackupLog core/BackupRecord PyMoneyApp \
            core/B64 core/Pbkdf2 core/KeyEnvelope core/AesGcmCodec core/CryptoState; do
    if ! grep -q "Lcom/pymoney/$CL;" "$WORK/dex.txt"; then
        echo "  ✗ dex 里找不到 com.pymoney.$CL，改动可能没进包"
        FAIL="$FAIL missing-$CL"
    fi
done
# ③ 零依赖红线：包里不许出现任何第三方库
python3 - "$OUT_APK" "$WORK/dex.txt" <<'PY' || FAIL="$FAIL deps"
import re, sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
bad = [n for n in names if re.search(r'androidx|kotlin|okhttp|sqlite|sqlcipher', n, re.I)]
text = open(sys.argv[2], encoding='utf-8', errors='replace').read()
bad += sorted({m.group(0) for m in re.finditer(r'L(androidx|kotlin|okhttp3|org/sqlite)[/a-zA-Z0-9_]*;', text)})
if bad:
    print("  ✗ 包里出现了第三方依赖：" + ", ".join(bad[:5]))
    sys.exit(1)
print("  ✓ 零第三方依赖，条目 %d" % len(names))
PY
# ④ manifest 与 minSdk
BADGE="$("$BT/aapt2" dump badging "$OUT_APK" 2>/dev/null)"
for WANT in "minSdkVersion:'24'" "targetSdkVersion:'34'"; do
    if ! grep -q "$WANT" <<<"$BADGE"; then
        echo "  ✗ badging 里缺 $WANT"
        FAIL="$FAIL badging"
    fi
done
grep -o "package: name='[^']*' versionCode='[0-9]*' versionName='[^']*'" <<<"$BADGE" \
    | sed 's/^/  ✓ /'
# ⑤ 签名证书必须还是原来那张，换了就装不上老版本（会丢数据）
CERT=$("$BT/apksigner" verify --print-certs "$OUT_APK" | sed -n 's/.*SHA-256 digest: //p' | head -1)
if [ "$CERT" != "451db9489eaabd74c63e57e35593af3a8a567d5ebc0d76a4e754e4d7884c3322" ]; then
    echo "  ✗ 签名证书变了：$CERT（覆盖安装会失败并丢数据）"
    FAIL="$FAIL cert"
fi
if [ -n "$FAIL" ]; then
    echo "自检未通过：$FAIL" >&2
    exit 1
fi
echo "  ✓ 无 lambda / 无 invokedynamic / 关键类在包内 / min24 target34 / 证书未变"

echo
"$BT/apksigner" verify --print-certs "$OUT_APK" | tail -3
python3 - "$OUT_APK" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    names = set(z.namelist())
missing = {"classes.dex", "resources.arsc", "AndroidManifest.xml"} - names
if missing:
    raise SystemExit("APK 缺少: " + ", ".join(sorted(missing)))
print("APK 条目 %d，classes.dex/resources.arsc/AndroidManifest.xml 齐全" % len(names))
PY
ls -lh "$OUT_APK"
echo "完成：$OUT_APK"

#!/usr/bin/env bash
# 数字对拍：App 核心层（JVM 上跑）vs 原 Python 程序，逐字符 diff。
# 用法：harness/run.sh [--crypto] [money.db 路径]
#   --crypto  额外跑加密核心自检，并让 Python 独立解一遍 Java 写的密文
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CRYPTO=0
DB=""
for arg in "$@"; do
    case "$arg" in
        --crypto) CRYPTO=1 ;;
        *) DB="$arg" ;;
    esac
done
DB="${DB:-$ROOT/../money.db}"
OUT="$ROOT/build/compare"
CP="$ROOT/harness/lib/sqlite-jdbc.jar:$ROOT/harness/lib/slf4j-api.jar"

rm -rf "$OUT"
mkdir -p "$OUT/java"

# 写入自检会改库，先在临时副本上做，绝不动用户真正的 money.db
cp "$DB" "$OUT/copy.db"
DB="$OUT/copy.db"

# 只编译与 Android 无关的核心层 + harness（AppDb/AndroidSql/Storage/UI 都依赖 android.jar，排除）
find "$ROOT/src/com/pymoney/calc" "$ROOT/src/com/pymoney/core" "$ROOT/src/com/pymoney/model" \
     "$ROOT/harness/src" -name '*.java' > "$OUT/srcs.txt"
for f in AggQuery RecordDao Schema TypeDao Fingerprint Migrator; do
    echo "$ROOT/src/com/pymoney/db/$f.java" >> "$OUT/srcs.txt"
done
echo "$ROOT/src/com/pymoney/Csv.java" >> "$OUT/srcs.txt"
# 图表数据层也不依赖 Android，一起编进来校验
for f in ChartData ChartModels; do
    echo "$ROOT/src/com/pymoney/ui/chart/$f.java" >> "$OUT/srcs.txt"
done

javac -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT/java" @"$OUT/srcs.txt"

java -cp "$CP:$OUT/java" com.pymoney.harness.Compare "$DB" --write 2>/dev/null > "$OUT/java.txt"
python3 "$ROOT/harness/py_reference.py" "$DB" > "$OUT/python.txt"

# assert 行是 Java 侧的写入自检，参照端没有，diff 前去掉
grep -v '^assert' "$OUT/java.txt" > "$OUT/java_only.txt"
echo "Java 数值 $(grep -c '' "$OUT/java_only.txt") 行，写入自检 $(grep -c '^assert' "$OUT/java.txt") 项，Python 参照 $(grep -c '' "$OUT/python.txt") 行"
if diff -u "$OUT/python.txt" "$OUT/java_only.txt" > "$OUT/diff.txt"; then
    echo "对拍一致：$(grep -c '' "$OUT/python.txt") 个数值零差异"
else
    echo "发现差异，见 $OUT/diff.txt"
    head -40 "$OUT/diff.txt"
    exit 1
fi
java -cp "$CP:$OUT/java" com.pymoney.harness.ChartCheck "$OUT/copy.db" 2>/dev/null > "$OUT/chart.txt"
if grep -q 'FAIL' "$OUT/chart.txt"; then
    echo "图表数据校验失败："
    grep 'FAIL' "$OUT/chart.txt"
    exit 1
fi
echo "图表校验：$(grep -c '^ok' "$OUT/chart.txt") 项合计一致，$(grep -c '^kind' "$OUT/chart.txt") 个图种有数据"

if grep -q 'FAIL' "$OUT/java.txt"; then
    echo "写入自检有失败项"
    grep 'FAIL' "$OUT/java.txt"
    exit 1
fi

# 备份校验三件套（指纹 + 历史）放在最后跑，它会往副本里插一条自检记录再删掉
java -cp "$CP:$OUT/java" com.pymoney.harness.CoreCheck "$OUT/copy.db" 2>/dev/null > "$OUT/core.txt"
if grep -q 'FAIL' "$OUT/core.txt"; then
    echo "备份自检失败："
    grep 'FAIL' "$OUT/core.txt"
    exit 1
fi
echo "备份自检：$(grep -c '^ok' "$OUT/core.txt") 项通过（$(grep '^ok|指纹|' "$OUT/core.txt" | head -1)）"

if [ "$CRYPTO" = 1 ]; then
    # 加密核心：Java 侧自检 + 固定向量，再交给 Python 的 hashlib/cryptography 独立验一遍
    java -cp "$CP:$OUT/java" com.pymoney.harness.CryptoCheck "$DB" "$OUT/crypto_vector.txt" \
        2>/dev/null > "$OUT/crypto.txt" || true
    if grep -q 'FAIL' "$OUT/crypto.txt"; then
        echo "加密核心自检失败："
        grep 'FAIL' "$OUT/crypto.txt"
        exit 1
    fi
    echo "加密自检：$(grep -c '^ok' "$OUT/crypto.txt") 项通过（Java 侧）"
    python3 "$ROOT/harness/crypto_crosscheck.py" "$OUT/crypto_vector.txt" \
        > "$OUT/crosscheck.txt" || true
    if grep -q 'FAIL' "$OUT/crosscheck.txt"; then
        echo "跨实现校验失败（Python 解不开 Java 写的密文）："
        grep 'FAIL' "$OUT/crosscheck.txt"
        exit 1
    fi
    echo "跨实现校验：$(grep -c '^ok' "$OUT/crosscheck.txt") 项一致（Python 与 Java 互解）"

    # 迁移端到端：加密 → 逐条比对 → 打断续跑 → 还原成明文，全程在副本上做
    java -cp "$CP:$OUT/java" com.pymoney.harness.MigrateCheck "$DB" "$OUT/mig" \
        2>/dev/null > "$OUT/migrate.txt" || true
    if grep -q 'FAIL' "$OUT/migrate.txt"; then
        echo "迁移自检失败："
        grep 'FAIL' "$OUT/migrate.txt"
        exit 1
    fi
    PASS="$(cat "$OUT/mig/mig.pass")"
    RECOVERY="$(cat "$OUT/mig/mig.rec")"
    echo "迁移自检：$(grep -c '^ok' "$OUT/migrate.txt") 项通过，库已迁成密文（$(grep '^ok|Migrate|迁完的条数' "$OUT/migrate.txt" | tail -1)）"

    # 对拍体系不死：同一套数值/图表在加密库上再算一遍，必须和明文库一字不差
    java -cp "$CP:$OUT/java" com.pymoney.harness.Compare "$OUT/mig/mig.db" --crypto "$PASS" \
        2>/dev/null > "$OUT/enc.txt" || true
    if diff -u "$OUT/java_only.txt" "$OUT/enc.txt" > "$OUT/enc_diff.txt"; then
        echo "加密库对拍：$(grep -c '' "$OUT/enc.txt") 个数值与明文库零差异（税负、CPI 都算进去了）"
    else
        echo "加密库对拍有差异，见 $OUT/enc_diff.txt"
        head -40 "$OUT/enc_diff.txt"
        exit 1
    fi
    java -cp "$CP:$OUT/java" com.pymoney.harness.ChartCheck "$OUT/mig/mig.db" --crypto "$PASS" \
        2>/dev/null > "$OUT/chart_enc.txt" || true
    if diff -u "$OUT/chart.txt" "$OUT/chart_enc.txt" > "$OUT/chart_enc_diff.txt"; then
        echo "加密库图表：$(grep -c '' "$OUT/chart_enc.txt") 行与明文库一致"
    else
        echo "加密库图表有差异，见 $OUT/chart_enc_diff.txt"
        head -20 "$OUT/chart_enc_diff.txt"
        exit 1
    fi

    # Python 独立解开 Java 写的密文，再拿原参照程序跑一遍——两边真的互通才算数
    python3 "$ROOT/harness/py_decrypt.py" "$OUT/mig/mig.db" "$PASS" "$OUT/mig/dec.db" \
        > "$OUT/decrypt.txt" 2>&1 || true
    python3 "$ROOT/harness/py_reference.py" "$OUT/mig/dec.db" > "$OUT/python_dec.txt"
    if diff -u "$OUT/python.txt" "$OUT/python_dec.txt" > "$OUT/dec_diff.txt"; then
        echo "Python 解密后参照：$(grep -c '' "$OUT/python_dec.txt") 个数值与原始明文库零差异"
    else
        echo "Python 解出来的库和原库数值不同，见 $OUT/dec_diff.txt"
        head -20 "$OUT/dec_diff.txt"
        exit 1
    fi

    # 交给用户的那个 Termux 工具也走一遍：它还原的明文库必须算出同一批数值
    PYMONEY_PASS="$PASS" python3 "$ROOT/tools/pymoney_dump.py" "$OUT/mig/mig.db" \
        --plain "$OUT/mig/dump.db" > "$OUT/dump.txt" 2>&1 || true
    if [ ! -f "$OUT/mig/dump.db" ]; then
        echo "tools/pymoney_dump.py 没能还原出明文库："
        cat "$OUT/dump.txt"
        exit 1
    fi
    python3 "$ROOT/harness/py_reference.py" "$OUT/mig/dump.db" > "$OUT/python_dump.txt"
    PYMONEY_PASS="$PASS" python3 "$ROOT/tools/pymoney_dump.py" "$OUT/mig/mig.db" --sum \
        > "$OUT/dump_sum.txt"
    if diff -u "$OUT/python.txt" "$OUT/python_dump.txt" > "$OUT/dump_diff.txt"; then
        echo "Termux 工具：$(grep -c '' "$OUT/python_dump.txt") 个数值与原始明文库零差异，$(tr '\n' ' ' < "$OUT/dump_sum.txt")"
    else
        echo "tools/pymoney_dump.py 还原的库和原库数值不同，见 $OUT/dump_diff.txt"
        head -20 "$OUT/dump_diff.txt"
        exit 1
    fi
    # 恢复码必须顶替口令（这是忘记口令的唯一出路，不能只在 Java 侧自证）
    if PYMONEY_PASS="$RECOVERY" python3 "$ROOT/tools/pymoney_dump.py" "$OUT/mig/mig.db" --sum \
        > "$OUT/dump_rec.txt" 2>&1; then
        echo "Termux 工具用恢复码：可解（$(head -1 "$OUT/dump_rec.txt")）"
    else
        echo "tools/pymoney_dump.py 用恢复码解不开："
        head -5 "$OUT/dump_rec.txt"
        exit 1
    fi
fi

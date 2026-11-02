package com.pymoney.db;

import com.pymoney.core.Codec;
import com.pymoney.core.CryptoState;
import com.pymoney.core.PlainCodec;
import com.pymoney.core.Sql;
import com.pymoney.model.Entry;
import com.pymoney.model.Stat;

import java.util.ArrayList;
import java.util.List;

/**
 * 各类合计。对应 tools.py 的 GetSumIncome/GetSumExpense/GetSum/GetSumByTypeID/GetItemTotal，
 * 另加图表要用的按年、按月、按类型聚合。
 * Time 列是 YYYYMMDD 整数，所以取年份用 Time/10000、取年月用 Time/100，不能用 strftime。
 */
public class AggQuery {

    private static final Codec PLAIN = new PlainCodec();

    private final Sql sql;
    private final CryptoState crypto;

    public AggQuery(Sql sql) {
        this(sql, null);
    }

    public AggQuery(Sql sql, CryptoState crypto) {
        this.sql = sql;
        this.crypto = crypto;
    }

    /** 每次现取，理由同 RecordDao：锁定/解锁后不必重建查询对象 */
    private Codec codec() {
        return crypto == null ? PLAIN : crypto.codec();
    }

    private long scalar(String statement, Object... args) {
        List<Object[]> rows = sql.select(statement, args);
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return 0L;
        }
        return RecordDao.asLong(rows.get(0)[0]);
    }

    /** 区间内收入合计（正数） */
    public long sumIncome(int start, int end) {
        return scalar("SELECT COALESCE(SUM(Amount),0) FROM Income "
                + "WHERE Time >= ? AND Time <= ? AND Amount > 0", start, end);
    }

    /** 区间内支出合计（负数），与原程序保持同一符号 */
    public long sumExpense(int start, int end) {
        return scalar("SELECT COALESCE(SUM(Amount),0) FROM Income "
                + "WHERE Time >= ? AND Time <= ? AND Amount < 0", start, end);
    }

    /** 区间净额 */
    public long sumAll(int start, int end) {
        return scalar("SELECT COALESCE(SUM(Amount),0) FROM Income WHERE Time >= ? AND Time <= ?", start, end);
    }

    /** 某类型在区间内的合计，符号看该类型净是收入还是支出 */
    public long sumByType(int start, int end, int typeId) {
        return scalar("SELECT COALESCE(SUM(Amount),0) FROM Income "
                + "WHERE Time >= ? AND Time <= ? AND TypeID = ?", start, end, typeId);
    }

    /** 区间内所有有记录的类型（含只出现一次的），顺序按 TypeID */
    public List<Stat> byType(int start, int end) {
        List<Stat> result = new ArrayList<>();
        for (Object[] row : sql.select(
                "SELECT i.TypeID, t.TypeName, "
                        + "SUM(CASE WHEN i.Amount > 0 THEN i.Amount ELSE 0 END), "
                        + "SUM(CASE WHEN i.Amount < 0 THEN i.Amount ELSE 0 END), COUNT(*) "
                        + "FROM Income i LEFT JOIN IncomeType t ON t.TypeID = i.TypeID "
                        + "WHERE i.Time >= ? AND i.Time <= ? GROUP BY i.TypeID ORDER BY i.TypeID", start, end)) {
            Stat stat = new Stat();
            stat.key = (int) RecordDao.asLong(row[0]);
            // 只有类别名要解密，金额和条数还是 SQL 直接聚合出来的——这是加密方案成立的关键
            stat.name = codec().open(Codec.TYPE_NAME, RecordDao.asString(row[1]));
            stat.income = RecordDao.asLong(row[2]);
            stat.expense = RecordDao.asLong(row[3]);
            stat.count = RecordDao.asLong(row[4]);
            stat.total = stat.income + stat.expense;
            result.add(stat);
        }
        return result;
    }

    /** 按年聚合，key 是年份 */
    public List<Stat> byYear() {
        return bucket("Time / 10000");
    }

    /** 按月聚合，key 是 YYYYMM */
    public List<Stat> byMonth(int start, int end) {
        return bucket("Time / 100", start, end);
    }

    private List<Stat> bucket(String expression, Object... rangeArgs) {
        List<Object> args = new ArrayList<>();
        String where = "";
        if (rangeArgs.length == 2) {
            where = "WHERE Time >= ? AND Time <= ? ";
            args.add(rangeArgs[0]);
            args.add(rangeArgs[1]);
        }
        List<Stat> result = new ArrayList<>();
        for (Object[] row : sql.select("SELECT " + expression + " AS key, "
                + "SUM(CASE WHEN Amount > 0 THEN Amount ELSE 0 END), "
                + "SUM(CASE WHEN Amount < 0 THEN Amount ELSE 0 END), COUNT(*) "
                + "FROM Income " + where + "GROUP BY key ORDER BY key", args.toArray())) {
            Stat stat = new Stat();
            stat.key = (int) RecordDao.asLong(row[0]);
            stat.income = RecordDao.asLong(row[1]);
            stat.expense = RecordDao.asLong(row[2]);
            stat.count = RecordDao.asLong(row[3]);
            stat.total = stat.income + stat.expense;
            result.add(stat);
        }
        return result;
    }

    /** 按年 × 类型，堆叠图用 */
    public List<Object[]> byYearAndType() {
        List<Object[]> result = new ArrayList<>();
        for (Object[] row : sql.select(
                "SELECT Time / 10000 AS y, TypeID, SUM(Amount), COUNT(*) FROM Income "
                        + "WHERE Amount < 0 GROUP BY y, TypeID ORDER BY y")) {
            result.add(row);
        }
        return result;
    }

    /** 大额支出排行（默认前 20） */
    public List<Entry> topExpenses(int start, int end, int limit) {
        List<Entry> result = new ArrayList<>();
        for (Object[] row : sql.select(
                "SELECT i.ID, i.Time, i.Name, i.Amount, i.TypeID, t.TypeName FROM Income i "
                        + "LEFT JOIN IncomeType t ON t.TypeID = i.TypeID "
                        + "WHERE i.Time >= ? AND i.Time <= ? AND i.Amount < 0 "
                        + "ORDER BY i.Amount ASC LIMIT ?", start, end, limit)) {
            Codec codec = codec();
            result.add(new Entry(RecordDao.asLong(row[0]), (int) RecordDao.asLong(row[1]),
                    codec.open(Codec.INCOME_NAME, RecordDao.asString(row[2])), RecordDao.asLong(row[3]),
                    (int) RecordDao.asLong(row[4]),
                    codec.open(Codec.TYPE_NAME, RecordDao.asString(row[5]))));
        }
        return result;
    }

    /** 区间内的记录条数 */
    public long countInRange(int start, int end) {
        return scalar("SELECT COUNT(*) FROM Income WHERE Time >= ? AND Time <= ?", start, end);
    }
}

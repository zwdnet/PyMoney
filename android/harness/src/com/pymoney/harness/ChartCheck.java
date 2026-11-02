package com.pymoney.harness;

import com.pymoney.calc.MoneyFormat;
import com.pymoney.core.Sql;
import com.pymoney.core.CryptoState;
import com.pymoney.db.AggQuery;
import com.pymoney.db.RecordDao;
import com.pymoney.db.Schema;
import com.pymoney.db.TypeDao;
import com.pymoney.model.Stat;
import com.pymoney.ui.chart.ChartData;
import com.pymoney.ui.chart.ChartModels;

import java.util.List;
import java.util.Locale;

/**
 * 图表数据校验：ChartModels 只用 Android 无关的那层，所以能在 JVM 上跑真实数据。
 * 逐图种检查项数、以及「图上画出来的合计」是否等于 SQL 直接算出来的合计。
 * 用法：java com.pymoney.harness.ChartCheck <money.db>
 */
public class ChartCheck {

    private static int failures;

    private static final int[][] RANGES = {
            {20180101, 20181231},
            {20240301, 20240331},
            {20260101, 20261102},
            {1, 99999999},
    };

    public static void main(String[] argv) {
        Locale.setDefault(Locale.US);
        if (argv.length < 1) {
            System.err.println("用法: ChartCheck <money.db>");
            System.exit(2);
        }
        Sql sql = new JdbcSql(argv[0]);
        Schema.ensure(sql);
        CryptoState crypto = CryptoState.resolve(sql);
        String passphrase = Compare.option(argv, "--crypto");
        if (passphrase != null && !crypto.unlock(passphrase)) {
            System.err.println("口令解不开这个库");
            System.exit(2);
        }
        AggQuery agg = new AggQuery(sql, crypto);
        TypeDao types = new TypeDao(sql, crypto);
        RecordDao records = new RecordDao(sql, crypto);
        ChartModels models = new ChartModels(agg, types);

        System.out.println("records|" + records.count());
        for (int[] range : RANGES) {
            String tag = range[0] + "-" + range[1];
            double income = MoneyFormat.fenToYuanDouble(agg.sumIncome(range[0], range[1]));
            double expense = MoneyFormat.fenToYuanDouble(Math.abs(agg.sumExpense(range[0], range[1])));

            List<Stat> years = ChartModels.yearsInRange(agg.byYear(), range[0], range[1]);
            for (int kind = 0; kind < ChartModels.KINDS.length; kind++) {
                ChartData data = models.build(kind, range[0], range[1]);
                if (data == null) {
                    System.out.println("empty|" + tag + "|" + kind + "|" + ChartModels.KINDS[kind]);
                    continue;
                }
                System.out.println("kind|" + tag + "|" + kind + "|" + data.pointCount()
                        + "|" + data.series.length);
                if (data.pointCount() == 0) {
                    fail(tag + " 图" + kind + " 有数据但点数为 0");
                }
                for (int p = 0; p < data.pointCount(); p++) {
                    for (int s = 0; s < data.series.length; s++) {
                        if (Double.isNaN(data.value(s, p)) || Double.isInfinite(data.value(s, p))) {
                            fail(tag + " 图" + kind + " 出现 NaN/Inf");
                        }
                    }
                }
                checkTotals(tag, kind, data, income, expense, years);
            }
        }
        sql.close();
        System.out.println(failures == 0 ? "图表数据校验全部通过" : "失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static double expectedYearExpense(List<Stat> years, int year) {
        for (Stat stat : years) {
            if (stat.key == year) {
                return MoneyFormat.fenToYuanDouble(Math.abs(stat.expense));
            }
        }
        return 0;
    }

    private static void checkTotals(String tag, int kind, ChartData data,
                                    double income, double expense, List<Stat> years) {
        if (kind == 3) {
            // 支出占比：饼图各扇区相加必须等于总支出（合成「其他」也不该丢钱）
            equal(tag, "支出饼图合计", sumAll(data), expense);
        } else if (kind == 4) {
            equal(tag, "收入饼图合计", sumAll(data), income);
        } else if (kind == 0) {
            double incomeLine = 0;
            double expenseLine = 0;
            double balance = 0;
            for (int p = 0; p < data.pointCount(); p++) {
                incomeLine += data.value(0, p);
                expenseLine += data.value(1, p);
                balance += data.value(2, p);
            }
            equal(tag, "月度图收入合计", incomeLine, income);
            equal(tag, "月度图支出合计", expenseLine, expense);
            equal(tag, "月度图结余合计", balance, income - expense);
        } else if (kind == 2) {
            // 按年分桶，落在区间内的整年加起来才等于图上的和
            double expectedIncome = 0;
            double expectedExpense = 0;
            for (Stat stat : years) {
                expectedIncome += MoneyFormat.fenToYuanDouble(stat.income);
                expectedExpense += MoneyFormat.fenToYuanDouble(Math.abs(stat.expense));
            }
            double incomeLine = 0;
            double expenseLine = 0;
            for (int p = 0; p < data.pointCount(); p++) {
                incomeLine += data.value(0, p);
                expenseLine += data.value(1, p);
            }
            equal(tag, "年度图收入合计", incomeLine, expectedIncome);
            equal(tag, "年度图支出合计", expenseLine, expectedExpense);
        } else if (kind == 1) {
            double last = data.value(0, data.pointCount() - 1);
            equal(tag, "累计结余末点", last, income - expense);
        } else if (kind == 7) {
            // 堆叠图：每年各列相加 = 该年总支出，且列数等于年份数
            if (data.pointCount() > years.size()) {
                fail(tag + " 堆叠图年份数超出");
            }
            double total = 0;
            for (int i = 0; i < data.pointCount(); i++) {
                double column = 0;
                for (int s = 0; s < data.series.length; s++) {
                    column += data.value(s, i);
                }
                total += column;
            }
            double sqlTotal = 0;
            for (int i = 0; i < data.pointCount(); i++) {
                sqlTotal += expectedYearExpense(years, Integer.parseInt(data.labels[i]));
            }
            equal(tag, "年度堆叠合计", total, sqlTotal);
        } else if (kind == 6) {
            if (data.pointCount() > 20) {
                fail(tag + " Top20 超出 20 项");
            }
            if (data.details == null || data.details.length != data.pointCount()) {
                fail(tag + " Top20 缺少逐条明细说明");
            } else {
                for (int p = 0; p < data.pointCount(); p++) {
                    if (data.details[p] == null || !data.details[p].matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                        fail(tag + " Top20 明细没有完整日期：" + data.details[p]);
                        break;
                    }
                }
            }
            double previous = Double.MAX_VALUE;
            for (int p = 0; p < data.pointCount(); p++) {
                double value = data.value(0, p);
                if (value > previous + 1e-9) {
                    fail(tag + " Top20 没有降序");
                    break;
                }
                previous = value;
            }
        } else if (kind == 5) {
            if (data.pointCount() > 12) {
                fail(tag + " 类型排行超出 12 项");
            }
            double previous = Double.MAX_VALUE;
            for (int p = 0; p < data.pointCount(); p++) {
                double value = data.value(0, p);
                if (value > previous + 1e-9) {
                    fail(tag + " 类型排行没有降序");
                    break;
                }
                previous = value;
            }
        } else if (kind == 8) {
            for (int p = 0; p < data.pointCount(); p++) {
                if (Math.abs(data.value(0, p)) > 1000) {
                    fail(tag + " 储蓄率超出合理范围：" + data.value(0, p));
                }
            }
        }
    }

    private static double sumAll(ChartData data) {
        double total = 0;
        for (int p = 0; p < data.pointCount(); p++) {
            total += Math.abs(data.value(0, p));
        }
        return total;
    }

    private static void equal(String tag, String label, double actual, double expected) {
        if (Math.abs(actual - expected) > 0.005) {
            fail(tag + " " + label + " 期望 " + fmt(expected) + " 实得 " + fmt(actual));
        } else {
            System.out.println("ok|" + tag + "|" + label + "|" + fmt(actual));
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL|" + message);
    }
}

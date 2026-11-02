package com.pymoney.harness;

import com.pymoney.calc.Cpi;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.calc.Tax;
import com.pymoney.core.Sql;
import com.pymoney.core.CryptoState;
import com.pymoney.db.AggQuery;
import com.pymoney.db.RecordDao;
import com.pymoney.db.Schema;
import com.pymoney.db.TypeDao;
import com.pymoney.model.Entry;
import com.pymoney.model.Stat;
import com.pymoney.model.Type;

import java.util.List;
import java.util.Locale;

/**
 * 数字对拍：把 App 核心层算出的每个量打印成一行，与 py_reference.py（直接调用原程序
 * tools.py/functions.py 的取数函数）打印的结果逐字符 diff。
 * 金额一律按 double 打印成 %.6f，好跟 Python 的 float 累加结果对齐。
 * 用法：java com.pymoney.harness.Compare <money.db> [--write]
 */
public class Compare {

    static final int[][] RANGES = {
            {20180101, 20181231},
            {20230101, 20231231},
            {20240301, 20240331},
            {20250101, 20251231},
            {20260101, 20261102},
            {20110101, 20261231},
    };

    private static int failures;

    /** 取 --xxx <值> 这种成对的开关，位置随意 */
    static String option(String[] argv, String name) {
        for (int i = 1; i + 1 < argv.length; i++) {
            if (name.equals(argv[i])) {
                return argv[i + 1];
            }
        }
        return null;
    }

    public static void main(String[] argv) {
        Locale.setDefault(Locale.US);
        if (argv.length < 1) {
            System.err.println("用法: Compare <money.db> [--write]");
            System.exit(2);
        }
        Sql sql = new JdbcSql(argv[0]);
        Schema.ensure(sql);
        // --crypto <口令>：同一套数值在加密库上再算一遍，和明文库的输出一字不差才算数
        CryptoState crypto = CryptoState.resolve(sql);
        String passphrase = option(argv, "--crypto");
        if (passphrase != null && !crypto.unlock(passphrase)) {
            System.err.println("口令解不开这个库");
            System.exit(2);
        }
        RecordDao records = new RecordDao(sql, crypto);
        TypeDao types = new TypeDao(sql, crypto);
        AggQuery agg = new AggQuery(sql, crypto);
        Tax tax = new Tax(agg, types);

        out("count", records.count());
        out("earliest", records.earliestTime());
        out("latest", records.latestTime());

        for (int[] range : RANGES) {
            String tag = range[0] + "-" + range[1];
            out("income", tag, (double) agg.sumIncome(range[0], range[1]));
            out("expense", tag, (double) agg.sumExpense(range[0], range[1]));
            out("net", tag, (double) agg.sumAll(range[0], range[1]));
            for (Type type : types.all()) {
                out("type", tag, type.id, (double) agg.sumByType(range[0], range[1], type.id));
            }
            Tax.Result t = tax.compute(range[0], range[1]);
            out("tax6", tag, t.vatBase6);
            out("tax9", tag, t.vatBase9);
            out("tax13", tag, t.vatBase13);
            out("vat", tag, t.vat);
            out("oilTax", tag, t.oilTax);
            out("cityTax", tag, t.cityTax);
            out("eduTax", tag, t.eduTax);
            out("incomeTax", tag, t.incomeTax);
            out("otherTax", tag, t.otherTax);
            out("taxTotal", tag, t.total);
            out("taxRate", tag, t.rate);
        }

        for (Stat stat : agg.byYear()) {
            out("year", stat.key, (double) stat.income, (double) stat.expense);
        }
        for (int year = 2012; year <= 2026; year++) {
            for (Stat stat : agg.byMonth(year * 10000 + 101, year * 10000 + 1231)) {
                out("month", stat.key, (double) stat.income, (double) stat.expense);
            }
        }

        Cpi cpi = new Cpi(agg, types);
        List<Cpi.Row> rows = cpi.compute(2011, 2026);
        for (Cpi.Row row : rows) {
            out("cpi", row.year, row.cpi);
            out("cpiIncome", row.year, row.incomeGrowth);
        }
        double[] averages = Cpi.average(rows);
        out("avgCpi", averages[0]);
        out("avgIncome", averages[1]);

        if (argv.length > 1 && "--write".equals(argv[1])) {
            writeChecks(records, types, agg);
            System.err.println(failures == 0 ? "写入路径自检全部通过" : "写入路径自检失败 " + failures + " 项");
        }
        sql.close();
    }

    /** 写入路径 + 换算的自检，不与 Python 比对（Python 侧 int(x*100) 的截断写法我们刻意修掉了） */
    private static void writeChecks(RecordDao records, TypeDao types, AggQuery agg) {
        long before = records.count();
        long newTypeId = types.ensureType("对拍测试类型");
        Entry entry = new Entry(0, 20260101, "对拍写入", MoneyFormat.yuanToFen("-12.34"), (int) newTypeId, null);
        long id = records.insert(entry);
        check("插入返回自增ID", id > 0, "id=" + id);
        check("插入后多一条", records.count() - before == 1, "delta=" + (records.count() - before));

        Entry read = records.findById(id);
        check("金额按四舍五入存分", read.amount == -1234L, "amount=" + read.amount);
        check("日期原样存回", read.time == 20260101, "time=" + read.time);
        check("名称含中文不截断", "对拍写入".equals(read.name), "name=" + read.name);
        check("类型关联名正确", "对拍测试类型".equals(read.typeName), "typeName=" + read.typeName);

        read.amount = MoneyFormat.yuanToFen("99.99");
        read.name = "对拍改名";
        records.update(read);
        Entry reread = records.findById(id);
        check("编辑生效", reread.amount == 9999L && "对拍改名".equals(reread.name),
                reread.amount + "/" + reread.name);
        long typeSum = agg.sumByType(20260101, 20260131, (int) newTypeId);
        check("按类型合计跟着变", typeSum == 9999L, String.valueOf(typeSum));

        records.delete(id);
        check("删除后条数还原", records.count() == before, "count=" + records.count());
        types.delete((int) newTypeId);
        check("类型可删除", types.idByName("对拍测试类型") == null, "");

        check("元转分 0.07", MoneyFormat.yuanToFen("0.07") == 7L, "");
        check("元转分 1234.56", MoneyFormat.yuanToFen("1234.56") == 123456L, "");
        check("元转分 -0.01", MoneyFormat.yuanToFen("-0.01") == -1L, "");
        check("元转分 3.5 等于 350", MoneyFormat.yuanToFen("3.5") == 350L, "");
        check("分转元显示带千分位", "-1,234,567.89".equals(MoneyFormat.fenToYuanText(-123456789L)),
                MoneyFormat.fenToYuanText(-123456789L));
        check("小额显示不加千分位", "3.00".equals(MoneyFormat.fenToYuanText(300L)),
                MoneyFormat.fenToYuanText(300L));

        check("2020 闰年合法", MoneyFormat.isValidDate(20200229), "");
        check("2021 非闰年非法", !MoneyFormat.isValidDate(20210229), "");
        check("2 月 30 日非法", !MoneyFormat.isValidDate(20240230), "");
        check("早于 1980 非法", !MoneyFormat.isValidDate(19791231), "");
        check("月末 2024-02", MoneyFormat.lastDayOfMonth(202402) == 20240229,
                String.valueOf(MoneyFormat.lastDayOfMonth(202402)));
        check("月末 2023-02", MoneyFormat.lastDayOfMonth(202302) == 20230228,
                String.valueOf(MoneyFormat.lastDayOfMonth(202302)));
        check("日期整数换算", MoneyFormat.toDateInt(2026, 11, 2) == 20261102, "");

        Tax tax = new Tax(agg, types);
        Tax.Result empty = tax.compute(19900101, 19901231);
        check("无数据区间税负为 0", empty.total == 0.0, String.valueOf(empty.total));
        check("无收入时不算税负率", Double.isNaN(empty.rate), String.valueOf(empty.rate));
        Cpi cpi = new Cpi(agg, types);
        List<Cpi.Row> far = cpi.compute(1989, 1991);
        check("无数据年份 CPI 为 NaN", Double.isNaN(far.get(0).cpi), "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        System.out.println("assert|" + (ok ? "PASS" : "FAIL") + "|" + name + "|" + detail);
    }

    private static void out(Object... parts) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            line.append(i == 0 ? "" : "|").append(format(parts[i]));
        }
        System.out.println(line);
    }

    private static String format(Object value) {
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number)) {
                return "nan";
            }
            return String.format(Locale.US, "%.6f", number);
        }
        return String.valueOf(value);
    }
}

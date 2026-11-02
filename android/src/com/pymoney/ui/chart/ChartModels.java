package com.pymoney.ui.chart;

import com.pymoney.calc.MoneyFormat;
import com.pymoney.db.AggQuery;
import com.pymoney.db.TypeDao;
import com.pymoney.model.Entry;
import com.pymoney.model.Stat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 每种图表要画什么，都在这里算出来。不依赖 Android，方便在 JVM 上拿真实数据校验。
 * 单位统一是「元」。
 */
public class ChartModels {

    public static final String[] KINDS = {
            "月度收支趋势", "累计结余曲线", "年度收支与结余", "支出类型占比",
            "收入类型占比", "类型支出排行", "大额支出 Top20", "年度支出构成", "年度储蓄率",
    };

    /** 需要用户选时间范围的图；按年聚合的那几张也按所选年份过滤 */
    public static boolean usesRange(int kind) {
        return kind != 2 && kind != 7 && kind != 8;
    }

    private final AggQuery agg;
    private final TypeDao types;

    public ChartModels(AggQuery agg, TypeDao types) {
        this.agg = agg;
        this.types = types;
    }

    public ChartData build(int kind, int start, int end) {
        switch (kind) {
            case 0:
                return line(ChartData.ofIncomeExpense(agg.byMonth(start, end), KINDS[0], true));
            case 1:
                return cumulative(start, end);
            case 2:
                return line(ChartData.ofIncomeExpense(
                        yearsInRange(agg.byYear(), start, end), KINDS[2], false));
            case 3:
            case 4:
                return pieByType(kind, start, end);
            case 5:
                return rankByType(start, end);
            case 6:
                return topExpenses(start, end);
            case 7:
                return yearStack(start, end);
            default:
                return savingRate(start, end);
        }
    }

    private ChartData line(ChartData data) {
        return data == null || data.isEmpty() ? null : data;
    }

    private ChartData cumulative(int start, int end) {
        List<Stat> months = agg.byMonth(start, end);
        if (months.isEmpty()) {
            return null;
        }
        String[] labels = new String[months.size()];
        double[] acc = new double[months.size()];
        double running = 0;
        for (int i = 0; i < months.size(); i++) {
            labels[i] = MoneyFormat.formatYearMonth(months.get(i).key);
            running += MoneyFormat.fenToYuanDouble(months.get(i).balance());
            acc[i] = running;
        }
        return new ChartData(KINDS[1], labels, new String[]{"累计结余"}, new double[][]{acc});
    }

    /** 饼图：只取单方向的金额，前 8 项之外合成「其他」 */
    private ChartData pieByType(int kind, int start, int end) {
        List<Stat> picked = new ArrayList<>();
        for (Stat stat : agg.byType(start, end)) {
            long value = kind == 3 ? stat.expense : stat.income;
            if (value != 0) {
                picked.add(named(stat, Math.abs(value)));
            }
        }
        if (picked.isEmpty()) {
            return null;
        }
        return fold(ChartData.ofSingle(picked, KINDS[kind], "金额", true), 8, "其他");
    }

    private ChartData rankByType(int start, int end) {
        List<Stat> picked = new ArrayList<>();
        for (Stat stat : agg.byType(start, end)) {
            if (stat.expense != 0) {
                picked.add(named(stat, Math.abs(stat.expense)));
            }
        }
        return picked.isEmpty() ? null
                : sorted(ChartData.ofSingle(picked, KINDS[5], "支出", true), 12);
    }

    private static Stat named(Stat source, long total) {
        Stat copy = new Stat();
        copy.key = source.key;
        copy.name = source.name == null ? "未分类" : source.name;
        copy.total = total;
        return copy;
    }

    private ChartData topExpenses(int start, int end) {
        List<Entry> tops = agg.topExpenses(start, end, 20);
        if (tops.isEmpty()) {
            return null;
        }
        String[] labels = new String[tops.size()];
        String[] details = new String[tops.size()];
        double[] values = new double[tops.size()];
        for (int i = 0; i < tops.size(); i++) {
            Entry entry = tops.get(i);
            String name = entry.name == null ? "" : entry.name;
            labels[i] = name + " " + monthDay(entry.time);
            details[i] = MoneyFormat.formatDate(entry.time) + " " + name
                    + (entry.typeName == null ? "" : "（" + entry.typeName + "）");
            values[i] = MoneyFormat.fenToYuanDouble(Math.abs(entry.amount));
        }
        ChartData data = new ChartData(KINDS[6], labels, new String[]{"金额"}, new double[][]{values});
        data.details = details;
        return data;
    }

    /** 20240315 → 03-15，条形图左列只留月日，完整日期在选中说明行里 */
    private static String monthDay(int time) {
        return String.format(java.util.Locale.US, "%02d-%02d", (time / 100) % 100, time % 100);
    }

    /** 年度支出构成：总支出前 7 的类别单列，其余合成「其他」 */
    private ChartData yearStack(int start, int end) {
        List<Object[]> rows = agg.byYearAndType();
        if (rows.isEmpty()) {
            return null;
        }
        Map<Integer, Long> totals = new LinkedHashMap<>();
        Map<String, Double> grid = new HashMap<>();
        int from = MoneyFormat.yearOf(start);
        int to = MoneyFormat.yearOf(end);
        TreeSet<Integer> years = new TreeSet<>();
        for (Object[] row : rows) {
            int year = (int) longOf(row[0]);
            int typeId = (int) longOf(row[1]);
            long amount = Math.abs(longOf(row[2]));
            if (year < from || year > to) {
                continue;
            }
            years.add(year);
            Long acc = totals.get(typeId);
            totals.put(typeId, acc == null ? amount : acc + amount);
            grid.put(year + "#" + typeId, MoneyFormat.fenToYuanDouble(amount));
        }
        List<Integer> top = topKeys(totals, 7);
        boolean hasOther = totals.size() > top.size();

        List<String> seriesNames = new ArrayList<>();
        for (Integer typeId : top) {
            String name = types.nameById(typeId);
            seriesNames.add(name == null ? "类型" + typeId : name);
        }
        if (hasOther) {
            seriesNames.add("其他");
        }

        String[] labels = new String[years.size()];
        double[][] values = new double[seriesNames.size()][years.size()];
        int column = 0;
        for (Integer year : years) {
            labels[column] = String.valueOf(year);
            double all = 0;
            for (Integer typeId : totals.keySet()) {
                Double cell = grid.get(year + "#" + typeId);
                if (cell != null) {
                    all += cell;
                }
            }
            double listed = 0;
            for (int s = 0; s < top.size(); s++) {
                Double cell = grid.get(year + "#" + top.get(s));
                if (cell != null) {
                    values[s][column] = cell;
                    listed += cell;
                }
            }
            if (hasOther) {
                values[top.size()][column] = Math.max(0, all - listed);
            }
            column++;
        }
        return new ChartData(KINDS[7], labels, seriesNames.toArray(new String[0]), values);
    }

    private ChartData savingRate(int start, int end) {
        List<String> labels = new ArrayList<>();
        List<Double> rates = new ArrayList<>();
        for (Stat stat : yearsInRange(agg.byYear(), start, end)) {
            double rate = stat.savingRate();
            if (Double.isNaN(rate)) {
                continue;    // 当年没有收入，储蓄率没有意义
            }
            labels.add(String.valueOf(stat.key));
            rates.add(rate);
        }
        if (labels.isEmpty()) {
            return null;
        }
        double[] values = new double[rates.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = rates.get(i);
        }
        return new ChartData(KINDS[8], labels.toArray(new String[0]),
                new String[]{"储蓄率 %"}, new double[][]{values});
    }

    // ---------- 通用整理 ----------

    /** 只留下落在所选年份区间里的桶（起止传的是 YYYYMMDD） */
    public static List<Stat> yearsInRange(List<Stat> stats, int start, int end) {
        int from = MoneyFormat.yearOf(start);
        int to = MoneyFormat.yearOf(end);
        List<Stat> kept = new ArrayList<>();
        for (Stat stat : stats) {
            if (stat.key >= from && stat.key <= to) {
                kept.add(stat);
            }
        }
        return kept;
    }

    private static long longOf(Object value) {
        return value instanceof Number ? ((Number) value).longValue()
                : Long.parseLong(value.toString());
    }

    private static List<Integer> topKeys(Map<Integer, Long> totals, final int limit) {
        List<Integer> ids = new ArrayList<>(totals.keySet());
        Collections.sort(ids, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Long.compare(totals.get(b), totals.get(a));
            }
        });
        return ids.size() > limit ? ids.subList(0, limit) : ids;
    }

    /** 按数值降序排，取前 n 项 */
    private static ChartData sorted(ChartData data, int n) {
        final double[] source = data.values[0];
        Integer[] order = order(source);
        int keep = Math.min(n, order.length);
        String[] labels = new String[keep];
        double[] values = new double[keep];
        for (int i = 0; i < keep; i++) {
            labels[i] = data.labels[order[i]];
            values[i] = source[order[i]];
        }
        return new ChartData(data.title, labels, data.series, new double[][]{values});
    }

    /** 保留前 keep 项，其余合成一项，饼图和堆叠图不至于碎成一地 */
    static ChartData fold(ChartData data, int keep, String otherName) {
        int count = data.pointCount();
        if (count <= keep) {
            return data;
        }
        final double[] source = data.values[0];
        Integer[] order = order(source);
        String[] labels = new String[keep + 1];
        double[] values = new double[keep + 1];
        double rest = 0;
        for (int i = 0; i < count; i++) {
            if (i < keep) {
                labels[i] = data.labels[order[i]];
                values[i] = source[order[i]];
            } else {
                rest += source[order[i]];
            }
        }
        labels[keep] = otherName;
        values[keep] = rest;
        return new ChartData(data.title, labels, data.series, new double[][]{values});
    }

    private static Integer[] order(final double[] source) {
        Integer[] order = new Integer[source.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(source[b], source[a]);
            }
        });
        return order;
    }
}

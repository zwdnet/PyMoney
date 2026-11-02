package com.pymoney.calc;

import com.pymoney.db.AggQuery;
import com.pymoney.db.TypeDao;

import java.util.ArrayList;
import java.util.List;

/**
 * 个人年度通胀率与收入增长率，对应 functions.py 的 CPI()。
 * 通胀率 = 本年在总支出(剔除「给李娅钱」) / 上年同口径 - 1；
 * 年均值用 scipy.stats.gmean 的算法：先整体平移到正区间求几何平均，再平移回来。
 */
public class Cpi {

    /** 原程序从这个口径里剔除掉的类型 */
    private static final String EXCLUDED_TYPE = "给李娅钱";

    public static class Row {
        public int year;
        public double cpi = Double.NaN;
        public double incomeGrowth = Double.NaN;
        public String note;      // 数据缺失时给个说明，原程序是直接 ZeroDivisionError

        public Row(int year) {
            this.year = year;
        }
    }

    private final AggQuery agg;
    private final TypeDao types;

    public Cpi(AggQuery agg, TypeDao types) {
        this.agg = agg;
        this.types = types;
    }

    /** 计算 [beginYear+1, endYear]，和原程序 range(begin+1, end+1) 一致 */
    public List<Row> compute(int beginYear, int endYear) {
        List<Row> rows = new ArrayList<>();
        Integer excludedId = types.idByName(EXCLUDED_TYPE);
        for (int year = beginYear + 1; year <= endYear; year++) {
            Row row = new Row(year);
            int prevStart = (year - 1) * 10000 + 101;
            int prevEnd = (year - 1) * 10000 + 1231;
            int nowStart = year * 10000 + 101;
            int nowEnd = year * 10000 + 1231;
            long expensePrev = netExpense(prevStart, prevEnd, excludedId);
            long expenseNow = netExpense(nowStart, nowEnd, excludedId);
            long incomePrev = agg.sumIncome(prevStart, prevEnd);
            long incomeNow = agg.sumIncome(nowStart, nowEnd);

            if (Math.abs(expensePrev) == 0) {
                row.note = "上一年无支出数据，通胀率无法计算";
            } else {
                row.cpi = Math.abs((double) expenseNow) / Math.abs((double) expensePrev) - 1.0;
            }
            if (incomePrev == 0) {
                if (row.note == null) {
                    row.note = "上一年无收入数据，增长率无法计算";
                } else {
                    row.note += "；上一年无收入数据，增长率无法计算";
                }
            } else {
                row.incomeGrowth = (double) incomeNow / incomePrev - 1.0;
            }
            rows.add(row);
        }
        return rows;
    }

    private long netExpense(int start, int end, Integer excludedId) {
        long expense = agg.sumExpense(start, end);
        if (excludedId != null) {
            expense -= agg.sumByType(start, end, excludedId);
        }
        return expense;
    }

    /** 年均通胀率与年均收入增长率，返回 {平均通胀率, 平均收入增长率} */
    public static double[] average(List<Row> rows) {
        double[] cpi = collect(rows, true);
        double[] income = collect(rows, false);
        return new double[]{shiftedGeometricMean(cpi), shiftedGeometricMean(income)};
    }

    private static double[] collect(List<Row> rows, boolean wantCpi) {
        List<Double> values = new ArrayList<>();
        for (Row row : rows) {
            double value = wantCpi ? row.cpi : row.incomeGrowth;
            if (!Double.isNaN(value)) {
                values.add(value);
            }
        }
        double[] array = new double[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /**
     * 复刻 scipy.stats.gmean(values - (min - 0.01)) + (min - 0.01)：
     * 减掉最小值再减 0.01，保证全部为正，求几何平均后加回来。
     */
    public static double shiftedGeometricMean(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double min = values[0];
        for (double value : values) {
            if (value < min) {
                min = value;
            }
        }
        double shift = min - 0.01;
        double logSum = 0.0;
        for (double value : values) {
            double moved = value - shift;
            if (moved <= 0 || !Double.isFinite(moved)) {
                return arithmeticFallback(values);
            }
            logSum += Math.log(moved);
        }
        return Math.exp(logSum / values.length) + shift;
    }

    private static double arithmeticFallback(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    /** 把 0.0834 这样的比率写成 8.34% */
    public static String percent(double ratio) {
        if (Double.isNaN(ratio)) {
            return "—";
        }
        return String.format("%.2f%%", ratio * 100.0);
    }
}

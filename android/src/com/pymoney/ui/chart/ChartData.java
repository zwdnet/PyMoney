package com.pymoney.ui.chart;

import com.pymoney.calc.MoneyFormat;
import com.pymoney.model.Stat;

import java.util.List;

/** 图表数据：一组长标签（月份/年份/类型名）+ 若干条数值序列，单位都是元。 */
public class ChartData {

    public String title;
    public String[] labels;
    public String[] series;
    public double[][] values;
    public boolean[] visible;
    /** 选中某点时显示的完整说明，长标签的截断版本放不下时用它；与 labels 等长，可为 null */
    public String[] details;

    public ChartData(String title, String[] labels, String[] series, double[][] values) {
        this.title = title;
        this.labels = labels;
        this.series = series;
        this.values = values;
        this.visible = new boolean[series.length];
        for (int i = 0; i < visible.length; i++) {
            visible[i] = true;
        }
    }

    public boolean isEmpty() {
        return labels == null || labels.length == 0;
    }

    public int pointCount() {
        return labels == null ? 0 : labels.length;
    }

    /** 序列 i 在第 p 个点的值，越界返回 0 */
    public double value(int i, int p) {
        if (i >= values.length || p >= values[i].length) {
            return 0;
        }
        return values[i][p];
    }

    /** 第 p 个点的完整说明，没提供 details 时退回 labels */
    public String detail(int p) {
        if (details != null && p >= 0 && p < details.length && details[p] != null) {
            return details[p];
        }
        return p >= 0 && p < pointCount() ? labels[p] : null;
    }

    /** 把 Stat 列表变成「收入 / 支出 / 结余」三序列，支出按正数画 */
    public static ChartData ofIncomeExpense(List<Stat> stats, String title, boolean monthLabels) {
        int n = stats.size();
        String[] labels = new String[n];
        double[][] values = new double[3][n];
        for (int i = 0; i < n; i++) {
            Stat stat = stats.get(i);
            labels[i] = monthLabels ? MoneyFormat.formatYearMonth(stat.key) : String.valueOf(stat.key);
            values[0][i] = MoneyFormat.fenToYuanDouble(stat.income);
            values[1][i] = MoneyFormat.fenToYuanDouble(Math.abs(stat.expense));
            values[2][i] = MoneyFormat.fenToYuanDouble(stat.balance());
        }
        return new ChartData(title, labels, new String[]{"收入", "支出", "结余"}, values);
    }

    /** 单序列，用于排行、储蓄率、大额支出 */
    public static ChartData ofSingle(List<Stat> stats, String title, String seriesName, boolean absolute) {
        int n = stats.size();
        String[] labels = new String[n];
        double[][] values = new double[1][n];
        for (int i = 0; i < n; i++) {
            Stat stat = stats.get(i);
            labels[i] = stat.name != null ? stat.name : String.valueOf(stat.key);
            double amount = MoneyFormat.fenToYuanDouble(stat.total);
            values[0][i] = absolute ? Math.abs(amount) : amount;
        }
        return new ChartData(title, labels, new String[]{seriesName}, values);
    }

    /** 两序列百分比（CPI 页用） */
    public static ChartData ofRates(int[] years, double[] rates1, double[] rates2,
                                    String name1, String name2, String title) {
        String[] labels = new String[years.length];
        double[][] values = new double[2][years.length];
        for (int i = 0; i < years.length; i++) {
            labels[i] = String.valueOf(years[i]);
            values[0][i] = Double.isNaN(rates1[i]) ? 0 : rates1[i] * 100.0;
            values[1][i] = Double.isNaN(rates2[i]) ? 0 : rates2[i] * 100.0;
        }
        return new ChartData(title, labels, new String[]{name1, name2}, values);
    }
}

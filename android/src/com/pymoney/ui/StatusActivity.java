package com.pymoney.ui;

import com.pymoney.R;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.calc.Tax;
import com.pymoney.model.Stat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 现金流量表，对应原程序的 Status()。 */
public class StatusActivity extends RangeReportActivity {

    @Override
    protected CharSequence buildReport(int start, int end) {
        long income = agg().sumIncome(start, end);
        long expense = agg().sumExpense(start, end);
        long net = income + expense;
        long count = agg().countInRange(start, end);

        StringBuilder text = new StringBuilder();
        text.append(String.format(getString(R.string.status_header),
                MoneyFormat.formatDate(start), MoneyFormat.formatDate(end)));
        text.append("    共 ").append(count).append(" 条\n\n");

        if (count == 0) {
            text.append(getString(R.string.status_none));
            return text;
        }

        List<Stat> stats = agg().byType(start, end);
        List<Stat> incomeTypes = new ArrayList<>();
        List<Stat> expenseTypes = new ArrayList<>();
        for (Stat stat : stats) {
            // 和原程序一样：按类型的净额正负归类，净额为 0 的类型不显示
            if (stat.total > 0) {
                incomeTypes.add(stat);
            } else if (stat.total < 0) {
                expenseTypes.add(stat);
            }
        }
        sortByAmount(incomeTypes);
        sortByAmount(expenseTypes);

        text.append(String.format(getString(R.string.status_income),
                MoneyFormat.fenToYuanText(income))).append('\n');
        appendBreakdown(text, incomeTypes, income);
        text.append('\n');
        text.append(String.format(getString(R.string.status_expense),
                MoneyFormat.fenToYuanText(Math.abs(expense)))).append('\n');
        appendBreakdown(text, expenseTypes, Math.abs(expense));
        text.append('\n');
        text.append(String.format(getString(R.string.status_net),
                MoneyFormat.fenToYuanText(net))).append('\n');
        if (income > 0) {
            text.append("储蓄率 ").append(String.format(java.util.Locale.US,
                    "%.1f%%", net * 100.0 / income)).append('\n');
        }

        text.append('\n');
        Tax.Result tax = new Tax(agg(), types()).compute(start, end);
        text.append(String.format(getString(R.string.tax_total),
                MoneyFormat.fenToYuanText(Math.round(Math.abs(tax.total))))).append('\n');
        if (Double.isNaN(tax.rate)) {
            text.append(getString(R.string.tax_rate_na)).append('\n');
        } else {
            text.append(String.format(java.util.Locale.US,
                    getString(R.string.tax_rate), String.format(java.util.Locale.US, "%.2f", tax.rate)))
                    .append('\n');
        }
        text.append("\n点上面的「税负估算」可以看明细。");
        return text;
    }

    private void appendBreakdown(StringBuilder text, List<Stat> stats, long total) {
        if (stats.isEmpty()) {
            return;
        }
        for (Stat stat : stats) {
            String name = stat.name == null ? "未分类" : stat.name;
            long amount = Math.abs(stat.total);
            text.append("    ").append(name);
            int pad = Math.max(2, 18 - displayWidth(name));
            for (int i = 0; i < pad; i++) {
                text.append(' ');
            }
            text.append(MoneyFormat.fenToYuanText(amount));
            if (total > 0) {
                text.append(String.format(java.util.Locale.US, " (%.1f%%)", amount * 100.0 / total));
            }
            text.append('\n');
        }
    }

    /** 中文按两个字符宽度算，让金额大致对齐 */
    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) > 127 ? 2 : 1;
        }
        return width;
    }

    private static void sortByAmount(List<Stat> stats) {
        Collections.sort(stats, new Comparator<Stat>() {
            @Override
            public int compare(Stat left, Stat right) {
                return Long.compare(Math.abs(right.total), Math.abs(left.total));
            }
        });
    }
}

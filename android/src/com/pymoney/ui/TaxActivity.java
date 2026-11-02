package com.pymoney.ui;

import com.pymoney.R;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.calc.Tax;

import java.util.Locale;

/** 税负估算，对应原程序的 Tax()，公式和分类名单一字未改。 */
public class TaxActivity extends RangeReportActivity {

    @Override
    protected CharSequence buildReport(int start, int end) {
        Tax.Result t = new Tax(agg(), types()).compute(start, end);
        StringBuilder text = new StringBuilder();
        text.append(String.format(getString(R.string.status_header),
                MoneyFormat.formatDate(start), MoneyFormat.formatDate(end))).append("\n\n");
        text.append(String.format(getString(R.string.tax_total),
                yuan(t.total))).append('\n');
        if (Double.isNaN(t.rate)) {
            text.append(getString(R.string.tax_rate_na)).append('\n');
        } else {
            text.append(String.format(getString(R.string.tax_rate),
                    String.format(Locale.US, "%.2f", t.rate))).append('\n');
        }
        text.append('\n');
        text.append(String.format(getString(R.string.tax_detail),
                yuan(t.vat), yuan(t.oilTax), yuan(t.cityTax),
                yuan(t.eduTax), yuan(t.incomeTax), yuan(t.otherTax)));
        text.append("\n\n应税基数（按原程序的分类归档）\n");
        text.append("    6% 档  ").append(yuan(t.vatBase6)).append(" 元\n");
        text.append("    9% 档  ").append(yuan(t.vatBase9)).append(" 元\n");
        text.append("    13% 档 ").append(yuan(t.vatBase13)).append(" 元\n");
        text.append("\n总收入 ").append(yuan(t.totalIncome)).append(" 元");
        return text;
    }

    private static String yuan(double fen) {
        return MoneyFormat.fenToYuanText(Math.round(Math.abs(fen)));
    }
}

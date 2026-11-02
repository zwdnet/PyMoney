package com.pymoney.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.pymoney.R;
import com.pymoney.calc.Cpi;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.db.AppDb;
import com.pymoney.model.Stat;
import com.pymoney.ui.chart.ChartData;
import com.pymoney.ui.chart.LineChartView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 个人 CPI：年度支出/收入涨幅，口径和原函数一模一样（剔除「给李娅钱」）。 */
public class CpiActivity extends Activity {

    private AppDb db;
    private EditText fromYear;
    private EditText toYear;
    private TextView summary;
    private LinearLayout chartBox;
    private TableLayout table;
    private LineChartView chart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cpi);
        db = AppDb.get(this);
        fromYear = (EditText) findViewById(R.id.from_year);
        toYear = (EditText) findViewById(R.id.to_year);
        summary = (TextView) findViewById(R.id.cpi_summary);
        chartBox = (LinearLayout) findViewById(R.id.cpi_chart_box);
        table = (TableLayout) findViewById(R.id.cpi_table);

        List<Stat> years = db.agg().byYear();
        int earliest = years.isEmpty()
                ? MoneyFormat.yearOf(MoneyFormat.today()) : years.get(0).key;
        fromYear.setText(String.valueOf(Math.max(earliest, 1980)));
        toYear.setText(String.valueOf(MoneyFormat.yearOf(MoneyFormat.today())));

        chart = new LineChartView(this);
        chartBox.addView(chart, new LinearLayout.LayoutParams(-1, -1, 1f));

        findViewById(R.id.calc_button).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                compute();
            }
        });
        compute();
    }

    private void compute() {
        int from = parseInt(fromYear.getText().toString());
        int to = parseInt(toYear.getText().toString());
        if (from <= 0 || to <= 0 || from > to) {
            Ui.toast(this, getString(R.string.cpi_err_year));
            return;
        }
        List<Cpi.Row> rows = new Cpi(db.agg(), db.types()).compute(from, to);
        fillTable(rows);
        fillChart(rows);

        double[] average = Cpi.average(rows);
        summary.setText(getString(R.string.cpi_avg, Cpi.percent(average[0]), Cpi.percent(average[1]))
                + "\n" + String.format(Locale.US, "区间 %d ~ %d，共 %d 年", from, to, rows.size()));
    }

    private void fillTable(List<Cpi.Row> rows) {
        table.removeAllViews();
        table.addView(headerRow(getString(R.string.cpi_col_year),
                getString(R.string.cpi_col_cpi), getString(R.string.cpi_col_income)));
        for (Cpi.Row row : rows) {
            TableRow tr = new TableRow(this);
            tr.addView(cell(String.valueOf(row.year), false));
            tr.addView(cell(text(row.cpi, row.note), false));
            tr.addView(cell(text(row.incomeGrowth, row.note), false));
            table.addView(tr);
        }
    }

    private String text(double value, String note) {
        if (Double.isNaN(value)) {
            return note == null ? getString(R.string.cpi_no_data) : note;
        }
        return Cpi.percent(value);
    }

    private TableRow headerRow(String... titles) {
        TableRow row = new TableRow(this);
        for (String title : titles) {
            row.addView(cell(title, true));
        }
        return row;
    }

    private TextView cell(String text, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setGravity(Gravity.END);
        view.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        view.setTextColor(getColor(bold ? R.color.text_primary : R.color.text_secondary));
        view.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));
        return view;
    }

    private void fillChart(List<Cpi.Row> rows) {
        List<Cpi.Row> usable = new ArrayList<>();
        for (Cpi.Row row : rows) {
            if (!Double.isNaN(row.cpi)) {
                usable.add(row);
            }
        }
        if (usable.isEmpty()) {
            chart.setData(null);
            return;
        }
        int[] years = new int[usable.size()];
        double[] cpi = new double[usable.size()];
        double[] income = new double[usable.size()];
        for (int i = 0; i < usable.size(); i++) {
            Cpi.Row row = usable.get(i);
            years[i] = row.year;
            cpi[i] = row.cpi;
            income[i] = row.incomeGrowth;
        }
        chart.setData(ChartData.ofRates(years, cpi, income, "支出涨幅", "收入涨幅", "逐年涨幅 %"));
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

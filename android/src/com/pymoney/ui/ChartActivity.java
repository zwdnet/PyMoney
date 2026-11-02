package com.pymoney.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.pymoney.Prefs;
import com.pymoney.R;
import com.pymoney.Storage;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.db.AppDb;
import com.pymoney.ui.chart.BarChartView;
import com.pymoney.ui.chart.ChartData;
import com.pymoney.ui.chart.ChartModels;
import com.pymoney.ui.chart.ChartView;
import com.pymoney.ui.chart.HBarChartView;
import com.pymoney.ui.chart.LineChartView;
import com.pymoney.ui.chart.PieChartView;
import com.pymoney.ui.chart.StackBarChartView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 统计图表页。图种来自 analyze_finance.py 的那 7 张图，改成 Canvas 自绘：
 * 点图例可以隐藏序列，点柱子/扇区看明细，右上角能存图或分享。
 */
public class ChartActivity extends Activity {

    private static final String[] MONTH_PRESETS = {"last_12m", "last_24m", "this_year", "all"};
    private static final String[] YEAR_PRESETS = {"this_year", "last_year", "all"};

    private AppDb db;
    private ChartModels models;
    private RangeBarView rangeBar;
    private FrameLayout box;
    private TextView note;
    private ChartView chart;
    private int kind;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chart);
        db = AppDb.get(this);
        models = new ChartModels(db.agg(), db.types());
        box = (FrameLayout) findViewById(R.id.chart_box);
        note = (TextView) findViewById(R.id.chart_note);

        Spinner spinner = (Spinner) findViewById(R.id.chart_kind);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, ChartModels.KINDS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        kind = Math.min(new Prefs(this).chartKind(), ChartModels.KINDS.length - 1);
        spinner.setSelection(kind);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                kind = position;
                new Prefs(ChartActivity.this).setChartKind(kind);
                rangeBar.setPresets(ChartModels.usesRange(kind) ? MONTH_PRESETS : YEAR_PRESETS);
                rangeBar.applyPreset(defaultPreset());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        rangeBar = new RangeBarView(this, MONTH_PRESETS);
        ((LinearLayout) findViewById(R.id.range_bar)).addView(rangeBar,
                new LinearLayout.LayoutParams(-1, -2));
        rangeBar.setListener(new RangeBarView.Listener() {
            @Override
            public void onRangeChanged(int start, int end) {
                render();
            }
        });

        findViewById(R.id.save_image).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(false);
            }
        });
        findViewById(R.id.share_image).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(true);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        rangeBar.setPresets(ChartModels.usesRange(kind) ? MONTH_PRESETS : YEAR_PRESETS);
        rangeBar.applyPreset(defaultPreset());
    }

    private String defaultPreset() {
        if (!ChartModels.usesRange(kind)) {
            return "all";
        }
        return kind == 0 ? "last_24m" : "this_year";
    }

    private void render() {
        ChartData data = models.build(kind, rangeBar.start(), rangeBar.end());
        if (data == null || data.isEmpty()) {
            note.setText(R.string.chart_no_data);
            ensureView();
            chart.setData(new ChartData(null, new String[0], new String[]{""}, new double[1][0]));
            return;
        }
        note.setText(describe(data));
        ensureView();
        chart.setData(data);
    }

    private void ensureView() {
        Class<? extends ChartView> expected = viewClass();
        if (chart != null && chart.getClass() == expected) {
            return;
        }
        ChartView view;
        if (expected == LineChartView.class) {
            view = new LineChartView(this);
        } else if (expected == BarChartView.class) {
            view = new BarChartView(this);
        } else if (expected == PieChartView.class) {
            view = new PieChartView(this);
        } else if (expected == HBarChartView.class) {
            view = new HBarChartView(this);
        } else {
            view = new StackBarChartView(this);
        }
        box.removeAllViews();
        box.addView(view, new FrameLayout.LayoutParams(-1, -1));
        chart = view;
    }

    private Class<? extends ChartView> viewClass() {
        switch (kind) {
            case 0:
            case 1:
                return LineChartView.class;
            case 2:
            case 8:
                return BarChartView.class;
            case 3:
            case 4:
                return PieChartView.class;
            case 5:
            case 6:
                return HBarChartView.class;
            default:
                return StackBarChartView.class;
        }
    }

    private String describe(ChartData data) {
        StringBuilder text = new StringBuilder();
        text.append("范围 ").append(MoneyFormat.formatDate(rangeBar.start()))
                .append(" ~ ").append(MoneyFormat.formatDate(rangeBar.end()))
                .append("，共 ").append(data.pointCount()).append(" 项");
        if (kind == 3 || kind == 4 || kind == 5) {
            double total = 0;
            for (int p = 0; p < data.pointCount(); p++) {
                total += Math.abs(data.value(0, p));
            }
            text.append("，合计 ").append(String.format(Locale.US, "%,.2f", total)).append(" 元");
        }
        text.append("。点图例可隐藏序列，点柱子看明细。");
        return text.toString();
    }

    // ---------- 导出 ----------

    private void export(boolean share) {
        if (chart == null || chart.getData() == null || chart.getData().isEmpty()) {
            Ui.toast(this, getString(R.string.chart_no_data));
            return;
        }
        Bitmap bitmap = chart.snapshot();
        File temp = new File(getCacheDir(), "chart.png");
        try {
            FileOutputStream out = new FileOutputStream(temp);
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                out.close();
            }
            String name = "chart_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".png";
            Uri uri = Storage.savePicture(this, name, temp).uri;
            if (share) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/png");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, getString(R.string.chart_share)));
            } else {
                Ui.toast(this, getString(R.string.chart_saved));
            }
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.chart_save_failed, String.valueOf(e.getMessage())));
        } finally {
            bitmap.recycle();
        }
    }
}

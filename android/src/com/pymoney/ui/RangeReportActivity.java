package com.pymoney.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pymoney.R;
import com.pymoney.db.AppDb;
import com.pymoney.db.AggQuery;
import com.pymoney.db.TypeDao;

/** 现金流量表 / 税负估算的公共外壳：一个时间区间 + 一段报告文字。 */
public abstract class RangeReportActivity extends Activity {

    protected RangeBarView rangeBar;
    protected TextView report;
    protected AppDb db;

    protected abstract CharSequence buildReport(int start, int end);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status);
        db = AppDb.get(this);
        report = (TextView) findViewById(R.id.report);

        rangeBar = new RangeBarView(this, new String[]{"this_month", "last_month",
                "this_year", "last_year", "all"});
        ((LinearLayout) findViewById(R.id.range_bar)).addView(rangeBar,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        rangeBar.setListener(new RangeBarView.Listener() {
            @Override
            public void onRangeChanged(int start, int end) {
                render();
            }
        });
        findViewById(R.id.goto_tax).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                startActivity(new android.content.Intent(v.getContext(), TaxActivity.class));
            }
        });
        findViewById(R.id.goto_charts).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                startActivity(new android.content.Intent(v.getContext(), ChartActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        rangeBar.applyPreset("this_year");
    }

    protected void render() {
        report.setText(buildReport(rangeBar.start(), rangeBar.end()));
    }

    protected AggQuery agg() {
        return db.agg();
    }

    protected TypeDao types() {
        return db.types();
    }
}

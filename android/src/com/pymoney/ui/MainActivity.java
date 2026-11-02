package com.pymoney.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pymoney.R;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.db.AggQuery;
import com.pymoney.db.AppDb;
import com.pymoney.db.RecordDao;

/** 首页：本月概览 + 功能入口。原来的 7 项菜单都在下面。 */
public class MainActivity extends Activity {

    private static final String[][] MENU = {
            {"记一笔", "添加一条收入或支出"},
            {"查账", "按时间、类型、关键字翻记录"},
            {"现金流量表", "区间内收支与分类汇总"},
            {"税负估算", "这段日子一共交了多少税"},
            {"个人CPI", "支出与收入的年涨幅"},
            {"统计图表", "饼图、柱状图、趋势线"},
            {"类型管理", "新增、改名、删除类型"},
            {"数据导入导出", "导入 money.db、备份、CSV"},
            {"安全与加密", "口令、恢复码、明文副本"},
            {"外观", "浅色 / 深色 / 跟随系统"},
    };

    private static final Class<?>[] TARGETS = {
            EditActivity.class,
            SearchActivity.class,
            StatusActivity.class,
            TaxActivity.class,
            CpiActivity.class,
            ChartActivity.class,
            TypeManageActivity.class,
            ImportExportActivity.class,
            SecurityActivity.class,
            AppearanceActivity.class,
    };

    private TextView incomeView;
    private TextView expenseView;
    private TextView netView;
    private TextView countView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        incomeView = (TextView) findViewById(R.id.overview_income);
        expenseView = (TextView) findViewById(R.id.overview_expense);
        netView = (TextView) findViewById(R.id.overview_net);
        countView = (TextView) findViewById(R.id.overview_count);
        buildMenu();
    }

    private void buildMenu() {
        LinearLayout container = (LinearLayout) findViewById(R.id.menu_container);
        LayoutInflater inflater = LayoutInflater.from(this);
        LinearLayout row = null;
        for (int i = 0; i < MENU.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.topMargin = Ui.dp(this, 8);
                container.addView(row, params);
            }
            final int index = i;
            View cell = inflater.inflate(R.layout.item_menu, row, false);
            ((TextView) cell.findViewById(R.id.menu_title)).setText(MENU[i][0]);
            ((TextView) cell.findViewById(R.id.menu_desc)).setText(MENU[i][1]);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 要读记录文字的页都得先过锁；首页只有数字，安全页和外观页本来就不碰记录
                    final Class<?> target = TARGETS[index];
                    if (target == SecurityActivity.class || target == AppearanceActivity.class) {
                        // 安全页锁着也得进得去：看状态、输口令解锁、改口令都在它上面
                        startActivity(new Intent(MainActivity.this, target));
                        return;
                    }
                    LockGate.ensure(MainActivity.this, new LockGate.Ready() {
                        @Override
                        public void onReady() {
                            startActivity(new Intent(MainActivity.this, target));
                        }
                    });
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = Ui.dp(this, 4);
            params.rightMargin = Ui.dp(this, 4);
            row.addView(cell, params);
        }
        if (MENU.length % 2 != 0) {
            // 奇数项时补个占位，免得最后一格自己撑满整行
            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppDb db = AppDb.get(this);
        RecordDao records = db.records();
        AggQuery agg = db.agg();
        int today = MoneyFormat.today();
        int yearMonth = MoneyFormat.yearMonthOf(today);
        int start = MoneyFormat.firstDayOfMonth(yearMonth);
        int end = MoneyFormat.lastDayOfMonth(yearMonth);
        long income = agg.sumIncome(start, end);
        long expense = agg.sumExpense(start, end);

        incomeView.setText("收入\n" + MoneyFormat.fenToYuanText(income));
        incomeView.setTextColor(getColor(R.color.income));
        expenseView.setText("支出\n" + MoneyFormat.fenToYuanText(Math.abs(expense)));
        expenseView.setTextColor(getColor(R.color.expense));
        netView.setText("净额\n" + MoneyFormat.fenToYuanText(income + expense));
        netView.setTextColor(getColor(R.color.text_primary));

        long count = records.count();
        if (count == 0) {
            countView.setText("还没有记录。可以从「数据导入导出」里导入你原来的 money.db。");
            Ui.toast(this, "数据库是空的，先导入 money.db 吧");
        } else {
            countView.setText(String.format(getString(R.string.records_count), count,
                    MoneyFormat.formatDate(records.earliestTime()),
                    MoneyFormat.formatDate(records.latestTime())));
        }
    }
}

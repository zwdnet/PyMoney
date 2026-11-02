package com.pymoney.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.pymoney.R;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.db.AppDb;
import com.pymoney.ui.EditActivity;

/** 桌面小组件：显示本月支出，点一下直接进「记一笔」。 */
public class QuickAddWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context, manager, ids);
    }

    /** 记完账后刷新桌面上的数字 */
    public static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName name = new ComponentName(context, QuickAddWidget.class);
        int[] ids = manager.getAppWidgetIds(name);
        if (ids.length > 0) {
            render(context.getApplicationContext(), manager, ids);
        }
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick_add);
        int today = MoneyFormat.today();
        int start = MoneyFormat.firstDayOfMonth(MoneyFormat.yearMonthOf(today));
        int end = MoneyFormat.lastDayOfMonth(MoneyFormat.yearMonthOf(today));
        String sub;
        try {
            AppDb db = AppDb.get(context);
            long income = db.agg().sumIncome(start, end);
            long expense = db.agg().sumExpense(start, end);
            sub = "本月 " + MoneyFormat.fenToYuanText(income) + " / "
                    + MoneyFormat.fenToYuanText(Math.abs(expense));
        } catch (Exception e) {
            sub = context.getString(R.string.widget_hint);
        }
        views.setTextViewText(R.id.widget_sub, sub);

        Intent intent = new Intent(context, EditActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        views.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(context, 0, intent, flags));

        for (int id : ids) {
            manager.updateAppWidget(id, views);
        }
    }
}

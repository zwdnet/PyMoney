package com.pymoney.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pymoney.R;
import com.pymoney.calc.MoneyFormat;

import java.util.Calendar;

/**
 * 时间区间选择条：一排快捷区间 + 起止日期按钮。
 * 查账、现金流量表、税负、图表页共用，代码里动态搭，省掉几份重复布局。
 */
public class RangeBarView extends LinearLayout {

    public interface Listener {
        void onRangeChanged(int start, int end);
    }

    private final Context context;
    private final LinearLayout presetRow;
    private final Button fromButton;
    private final Button toButton;
    private Listener listener;
    private int start;
    private int end;
    private String activePreset;

    public RangeBarView(Context context, String[] presets) {
        super(context);
        this.context = context;
        setOrientation(VERTICAL);

        presetRow = new LinearLayout(context);
        presetRow.setOrientation(HORIZONTAL);
        addView(presetRow, matchWrap());

        fromButton = smallButton("起");
        toButton = smallButton("止");
        LinearLayout rangeRow = new LinearLayout(context);
        rangeRow.setOrientation(HORIZONTAL);
        rangeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView fromLabel = label("起");
        TextView toLabel = label("止");
        rangeRow.addView(fromLabel);
        rangeRow.addView(fromButton);
        rangeRow.addView(toLabel);
        rangeRow.addView(toButton);
        LayoutParams params = matchWrap();
        params.topMargin = Ui.dp(context, 4);
        addView(rangeRow, params);

        setPresets(presets);
        fromButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(true);
            }
        });
        toButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(false);
            }
        });
    }

    private static LayoutParams matchWrap() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private TextView label(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(13);
        view.setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 2), 0);
        return view;
    }

    private Button smallButton(String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0);
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.weight = 0;
        return button;
    }

    /** 换一批快捷按钮 */
    public final void setPresets(String[] presets) {
        presetRow.removeAllViews();
        for (final String key : presets) {
            Button button = smallButton(presetName(key));
            button.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyPreset(key);
                }
            });
            LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            presetRow.addView(button, params);
        }
    }

    private static String presetName(String key) {
        switch (key) {
            case "this_month":
                return "本月";
            case "last_month":
                return "上月";
            case "this_year":
                return "本年";
            case "last_year":
                return "去年";
            case "last_12m":
                return "近12月";
            case "last_24m":
                return "近24月";
            default:
                return "全部";
        }
    }

    private void pick(final boolean whichStart) {
        final int initial = whichStart ? start : end;
        Ui.pickDate(context, initial == 0 ? MoneyFormat.today() : initial,
                new Ui.DateCallback() {
                    @Override
                    public void onDate(int year, int month, int day) {
                        int value = MoneyFormat.toDateInt(year, month, day);
                        if (whichStart) {
                            start = value;
                            if (end == 0 || end < start) {
                                end = value;
                            }
                        } else {
                            end = value;
                            if (start == 0 || start > end) {
                                start = value;
                            }
                        }
                        activePreset = null;
                        refresh();
                    }
                });
    }

    public void applyPreset(String key) {
        Calendar calendar = Calendar.getInstance();
        int today = MoneyFormat.today();
        int year = MoneyFormat.yearOf(today);
        int month = MoneyFormat.yearMonthOf(today) % 100;
        if ("this_month".equals(key)) {
            int ym = year * 100 + month;
            start = MoneyFormat.firstDayOfMonth(ym);
            end = MoneyFormat.lastDayOfMonth(ym);
        } else if ("last_month".equals(key)) {
            calendar.add(Calendar.MONTH, -1);
            int ym = calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.MONTH) + 1;
            start = MoneyFormat.firstDayOfMonth(ym);
            end = MoneyFormat.lastDayOfMonth(ym);
        } else if ("this_year".equals(key)) {
            start = year * 10000 + 101;
            end = year * 10000 + 1231;
        } else if ("last_year".equals(key)) {
            start = (year - 1) * 10000 + 101;
            end = (year - 1) * 10000 + 1231;
        } else if ("last_12m".equals(key)) {
            calendar.add(Calendar.MONTH, -11);
            start = calendar.get(Calendar.YEAR) * 10000
                    + (calendar.get(Calendar.MONTH) + 1) * 100 + 1;
            end = today;
        } else if ("last_24m".equals(key)) {
            calendar.add(Calendar.MONTH, -23);
            start = calendar.get(Calendar.YEAR) * 10000
                    + (calendar.get(Calendar.MONTH) + 1) * 100 + 1;
            end = today;
        } else {
            start = 1;
            end = 99999999;
        }
        activePreset = key;
        refresh();
    }

    private void refresh() {
        fromButton.setText(start <= 1 ? "最早" : MoneyFormat.formatDate(start));
        toButton.setText(end >= 99999999 ? "最新" : MoneyFormat.formatDate(end));
        int childCount = presetRow.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = presetRow.getChildAt(i);
            if (child instanceof Button) {
                Button button = (Button) child;
                boolean active = activePreset != null && presetName(activePreset).equals(button.getText());
                button.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
                button.setTextColor(context.getResources().getColor(
                        active ? R.color.primary : R.color.text_secondary));
            }
        }
        if (listener != null) {
            listener.onRangeChanged(start, end);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    /** 初始区间，不触发回调 */
    public void setRange(int from, int to, String preset) {
        start = from;
        end = to;
        activePreset = preset;
        refresh();
    }
}

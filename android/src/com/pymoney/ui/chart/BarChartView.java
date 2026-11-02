package com.pymoney.ui.chart;

import android.content.Context;
import android.graphics.Canvas;

/** 分组柱状图：年度收入/支出/结余、类型金额对比 */
public class BarChartView extends ChartView {

    public BarChartView(Context context) {
        super(context);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (data == null || data.isEmpty()) {
            drawEmpty(canvas);
            return;
        }
        computeScale();
        drawFrame(canvas);

        int count = data.pointCount();
        int seriesCount = 0;
        for (int i = 0; i < data.series.length; i++) {
            if (data.visible[i]) {
                seriesCount++;
            }
        }
        if (seriesCount == 0) {
            return;
        }
        float slot = plot.width() / count;
        float group = slot * 0.7f;
        float barWidth = group / seriesCount;
        float base = yOf(0, plot.top, plot.bottom);

        int visibleIndex = 0;
        for (int i = 0; i < data.series.length; i++) {
            if (!data.visible[i]) {
                continue;
            }
            fillPaint.setColor(Palette.color(i));
            for (int p = 0; p < count; p++) {
                double value = data.value(i, p);
                if (selected >= 0) {
                    fillPaint.setAlpha(p == selected ? 255 : 110);
                }
                float y = yOf(value, plot.top, plot.bottom);
                float left = plot.left + slot * p + (slot - group) / 2 + visibleIndex * barWidth;
                canvas.drawRect(left, Math.min(y, base), left + barWidth * 0.92f,
                        Math.max(y, base), fillPaint);
            }
            fillPaint.setAlpha(255);
            visibleIndex++;
        }

        drawXLabels(canvas, count);
        if (selected >= 0 && selected < count) {
            String[] lines = new String[data.series.length + 1];
            lines[0] = data.labels[selected];
            for (int i = 0; i < data.series.length; i++) {
                lines[i + 1] = data.visible[i]
                        ? data.series[i] + " " + formatValue(data.value(i, selected)) : null;
            }
            drawSelection(canvas, lines);
        }
    }

    @Override
    protected int indexAt(float x, float y) {
        return data == null ? -1 : indexBySlice(x, data.pointCount());
    }
}

package com.pymoney.ui.chart;

import android.content.Context;
import android.graphics.Canvas;

/** 堆叠柱状图：年度支出按类别构成 */
public class StackBarChartView extends ChartView {

    public StackBarChartView(Context context) {
        super(context);
    }

    @Override
    protected void computeScale() {
        double max = 0;
        if (data != null) {
            for (int p = 0; p < data.pointCount(); p++) {
                double sum = 0;
                for (int i = 0; i < data.series.length; i++) {
                    if (data.visible[i]) {
                        sum += Math.max(0, data.value(i, p));
                    }
                }
                max = Math.max(max, sum);
            }
        }
        if (max <= 0) {
            max = 1;
        }
        tickStep = niceStep(max / TARGET_TICKS);
        minValue = 0;
        maxValue = Math.ceil(max / tickStep) * tickStep;
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
        float slot = plot.width() / count;
        float barWidth = slot * 0.68f;
        float base = yOf(0, plot.top, plot.bottom);

        for (int p = 0; p < count; p++) {
            float stack = 0;
            for (int i = 0; i < data.series.length; i++) {
                if (!data.visible[i]) {
                    continue;
                }
                double value = Math.max(0, data.value(i, p));
                if (value <= 0) {
                    continue;
                }
                float top = yOf(stack + value, plot.top, plot.bottom);
                float bottom = yOf(stack, plot.top, plot.bottom);
                float left = plot.left + slot * p + (slot - barWidth) / 2;
                fillPaint.setColor(Palette.color(i));
                fillPaint.setAlpha(selected < 0 || selected == p ? 255 : 110);
                canvas.drawRect(left, top, left + barWidth, bottom, fillPaint);
                stack += value;
            }
        }
        fillPaint.setAlpha(255);
        canvas.drawLine(plot.left, base, plot.right, base, axisPaint);

        drawXLabels(canvas, count);
        if (selected >= 0 && selected < count) {
            String[] lines = new String[data.series.length + 1];
            lines[0] = data.labels[selected];
            for (int i = 0; i < data.series.length; i++) {
                lines[i + 1] = data.visible[i] && data.value(i, selected) > 0
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

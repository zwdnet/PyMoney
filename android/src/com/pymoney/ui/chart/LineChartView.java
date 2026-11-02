package com.pymoney.ui.chart;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;

/** 折线图：月度收支趋势、CPI 与收入增长率 */
public class LineChartView extends ChartView {

    private final Path path = new Path();

    public LineChartView(Context context) {
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
        float slot = plot.width() / count;
        for (int i = 0; i < data.series.length; i++) {
            if (!data.visible[i]) {
                continue;
            }
            int color = Palette.color(i);
            path.reset();
            for (int p = 0; p < count; p++) {
                float x = plot.left + slot * (p + 0.5f);
                float y = yOf(data.value(i, p), plot.top, plot.bottom);
                if (p == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            strokePaint.setColor(color);
            canvas.drawPath(path, strokePaint);

            fillPaint.setColor(color);
            for (int p = 0; p < count; p++) {
                float x = plot.left + slot * (p + 0.5f);
                float y = yOf(data.value(i, p), plot.top, plot.bottom);
                float radius = (selected == p) ? 4.5f * density : 2.4f * density;
                canvas.drawCircle(x, y, radius, fillPaint);
            }
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

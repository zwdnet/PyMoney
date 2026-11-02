package com.pymoney.ui.chart;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

/** 横向条形图：排行用（大额支出 Top20、类型金额排行），左边的长标签不会被截断 */
public class HBarChartView extends ChartView {

    private final float labelWidth = 92 * density;

    public HBarChartView(Context context) {
        super(context);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (data == null || data.isEmpty()) {
            drawEmpty(canvas);
            return;
        }
        double max = 0;
        for (int p = 0; p < data.pointCount(); p++) {
            max = Math.max(max, Math.abs(data.value(0, p)));
        }
        if (max <= 0) {
            max = 1;
        }

        float top = 46 * density;
        float bottom = getHeight() - 12 * density;
        float left = labelWidth + 6 * density;
        float right = getWidth() - 8 * density;
        plot.set(left, top, right, bottom);

        if (data.title != null) {
            textPaint.setFakeBoldText(true);
            canvas.drawText(data.title, 8 * density, 18 * density, textPaint);
            textPaint.setFakeBoldText(false);
        }
        drawDetail(canvas, right - 8 * density);

        int count = data.pointCount();
        float row = (bottom - top) / count;
        float barHeight = Math.min(row * 0.62f, 18 * density);
        smallPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        for (int p = 0; p < count; p++) {
            float centerY = top + row * (p + 0.5f);
            String label = data.labels[p];
            smallPaint.setColor(selected == p ? colorText : colorTextSecondary);
            canvas.drawText(clipTo(smallPaint, label, labelWidth - 4 * density),
                    labelWidth, centerY + 4 * density, smallPaint);

            double value = Math.abs(data.value(0, p));
            float width = (float) (value / max * (right - left));
            fillPaint.setColor(Palette.alpha(Palette.color(0), selected < 0 || selected == p ? 235 : 90));
            canvas.drawRoundRect(left, centerY - barHeight / 2, left + Math.max(width, 2f * density),
                    centerY + barHeight / 2, 3 * density, 3 * density, fillPaint);

            textPaint.setColor(colorText);
            textPaint.setTextSize(11 * density);
            String text = formatValue(data.value(0, p));
            if (selected == p) {
                textPaint.setFakeBoldText(true);
            }
            canvas.drawText(text, right, centerY + 4 * density, textPaint);
            textPaint.setFakeBoldText(false);
            textPaint.setTextSize(12 * density);
        }
        smallPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    /** 标题下面那一行：选中哪条就把那条的完整名称和金额写出来 */
    private void drawDetail(Canvas canvas, float maxWidth) {
        textPaint.setTextSize(11 * density);
        String detail;
        if (selected >= 0 && selected < data.pointCount()) {
            detail = (selected + 1) + ". " + data.detail(selected) + "　"
                    + formatValue(data.value(0, selected)) + " 元";
            textPaint.setColor(colorText);
        } else {
            detail = "点一条看完整说明";
            textPaint.setColor(colorTextDim);
        }
        canvas.drawText(clipTo(textPaint, detail, maxWidth), 8 * density, 34 * density, textPaint);
        textPaint.setTextSize(12 * density);
    }

    @Override
    protected int indexAt(float x, float y) {
        if (data == null || y < plot.top || y > plot.bottom) {
            return -1;
        }
        int index = (int) ((y - plot.top) / (plot.height() / data.pointCount()));
        return index < 0 ? 0 : Math.min(index, data.pointCount() - 1);
    }
}

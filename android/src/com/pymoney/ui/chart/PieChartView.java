package com.pymoney.ui.chart;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.pymoney.R;

/** 环形图：收入/支出类型占比 */
public class PieChartView extends ChartView {

    private final RectF oval = new RectF();
    private float centerX;
    private float centerY;
    private float radius;
    private float innerRadius;

    public PieChartView(Context context) {
        super(context);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (data == null || data.isEmpty()) {
            drawEmpty(canvas);
            return;
        }
        double total = 0;
        for (int p = 0; p < data.pointCount(); p++) {
            total += Math.abs(data.value(0, p));
        }
        if (total <= 0) {
            drawEmpty(canvas);
            return;
        }

        if (data.title != null) {
            textPaint.setFakeBoldText(true);
            canvas.drawText(data.title, 8 * density, 18 * density, textPaint);
            textPaint.setFakeBoldText(false);
        }

        float top = 32 * density;
        float bottom = getHeight() - 10 * density;
        int count = data.pointCount();
        float legendWidth = Math.min(getWidth() * 0.42f, 150 * density);
        radius = Math.min((bottom - top) / 2f, (getWidth() - legendWidth) / 2f) - 6 * density;
        centerX = 10 * density + radius;
        centerY = (top + bottom) / 2f;
        innerRadius = radius * 0.55f;

        float start = -90f;
        for (int p = 0; p < count; p++) {
            double value = Math.abs(data.value(0, p));
            float sweep = (float) (value / total * 360.0);
            fillPaint.setColor(Palette.alpha(Palette.color(p), selected < 0 || selected == p ? 245 : 110));
            float outer = selected == p ? radius + 4 * density : radius;
            drawSector(canvas, start, sweep, outer);
            start += sweep;
        }
        // 中空（跟卡片底色一致，深色主题下才不会挖出一个白洞）
        fillPaint.setColor(getContext().getColor(R.color.chart_hole));
        canvas.drawCircle(centerX, centerY, innerRadius, fillPaint);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(11 * density);
        boolean picked = selected >= 0 && selected < count;
        textPaint.setColor(colorTextSecondary);
        canvas.drawText(picked ? truncate(data.labels[selected], 6) : "合计",
                centerX, centerY - 4 * density, textPaint);
        textPaint.setColor(colorText);
        textPaint.setTextSize(12 * density);
        textPaint.setFakeBoldText(true);
        double center = picked ? Math.abs(data.value(0, selected)) : total;
        canvas.drawText(abbreviate(center), centerX, centerY + 12 * density, textPaint);
        textPaint.setFakeBoldText(false);
        textPaint.setTextAlign(Paint.Align.LEFT);

        float x = centerX + radius + 12 * density;
        float rowHeight = Math.min(20 * density, (bottom - top) / count);
        smallPaint.setTextAlign(Paint.Align.LEFT);
        for (int p = 0; p < count; p++) {
            float y = top + rowHeight * (p + 0.6f);
            fillPaint.setColor(Palette.color(p));
            canvas.drawRoundRect(x, y - 8 * density, x + 9 * density, y - 1,
                    2 * density, 2 * density, fillPaint);
            String label = data.labels[p];
            if (label != null && label.length() > 7) {
                label = label.substring(0, 7) + "…";
            }
            double share = Math.abs(data.value(0, p)) / total * 100.0;
            smallPaint.setColor(selected == p ? colorText : colorTextSecondary);
            canvas.drawText(label + " " + String.format(java.util.Locale.US, "%.1f%%", share),
                    x + 12 * density, y, smallPaint);
        }
    }

    private void drawSector(Canvas canvas, float start, float sweep, float outer) {
        oval.set(centerX - outer, centerY - outer, centerX + outer, centerY + outer);
        if (sweep >= 359.9f) {
            canvas.drawCircle(centerX, centerY, outer, fillPaint);
            return;
        }
        Path path = new Path();
        path.moveTo(centerX, centerY);
        path.arcTo(oval, start, sweep);
        path.close();
        canvas.drawPath(path, fillPaint);
    }

    private String abbreviate(double total) {
        if (Math.abs(total) >= 10000) {
            return String.format(java.util.Locale.US, "%.2f万", total / 10000.0);
        }
        return String.format(java.util.Locale.US, "%,.0f", total);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    @Override
    protected int indexAt(float x, float y) {
        if (data == null || radius <= 0) {
            return -1;
        }
        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance > radius + 6 * density || distance < innerRadius) {
            return -1;
        }
        double angle = Math.toDegrees(Math.atan2(dy, dx)) + 90;
        if (angle < 0) {
            angle += 360;
        }
        double total = 0;
        for (int p = 0; p < data.pointCount(); p++) {
            total += Math.abs(data.value(0, p));
        }
        double acc = 0;
        for (int p = 0; p < data.pointCount(); p++) {
            acc += Math.abs(data.value(0, p)) / total * 360.0;
            if (angle <= acc) {
                return p;
            }
        }
        return data.pointCount() - 1;
    }
}

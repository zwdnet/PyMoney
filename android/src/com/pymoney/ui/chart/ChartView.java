package com.pymoney.ui.chart;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.pymoney.R;

import java.util.ArrayList;
import java.util.List;

/** 自绘图表的公共部分：坐标轴、刻度、图例、触摸选择、导出成图。 */
public abstract class ChartView extends View {

    protected static final int TARGET_TICKS = 4;

    protected final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint gridPaint = new Paint();
    protected final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    protected ChartData data;
    protected final List<RectF> legendRects = new ArrayList<>();
    protected int selected = -1;
    protected final float density;

    /** 自绘的图不走主题，颜色只能自己查资源，深色/浅色各一套（见 res/values-night） */
    protected final int colorBg;
    protected final int colorText;
    protected final int colorTextSecondary;
    protected final int colorTextDim;

    /** 画布上的绘图区（不含坐标轴文字） */
    protected final RectF plot = new RectF();
    protected double minValue;
    protected double maxValue;
    protected double tickStep;

    public ChartView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        colorBg = context.getColor(R.color.chart_bg);
        colorText = context.getColor(R.color.chart_text);
        colorTextSecondary = context.getColor(R.color.chart_text_secondary);
        colorTextDim = context.getColor(R.color.chart_text_dim);
        setBackgroundColor(colorBg);
        axisPaint.setColor(context.getColor(R.color.chart_axis));
        axisPaint.setStrokeWidth(1f);
        gridPaint.setColor(context.getColor(R.color.chart_grid));
        gridPaint.setStrokeWidth(1f);
        textPaint.setColor(colorText);
        textPaint.setTextSize(12 * density);
        smallPaint.setColor(colorTextSecondary);
        smallPaint.setTextSize(11 * density);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2f * density);
    }

    public void setData(ChartData newData) {
        this.data = newData;
        this.selected = -1;
        invalidate();
    }

    public ChartData getData() {
        return data;
    }

    /** 把当前图导出成位图，供保存到相册或分享 */
    public Bitmap snapshot() {
        int width = getWidth() > 0 ? getWidth() : (int) (360 * density);
        int height = getHeight() > 0 ? getHeight() : (int) (260 * density);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        // 存进相册的图也按当前主题配色，免得深色界面里存出一张白底图
        canvas.drawColor(colorBg);
        int previousWidth = getWidth();
        int previousHeight = getHeight();
        if (previousWidth != width || previousHeight != height) {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            layout(getLeft(), getTop(), width, height);
        }
        draw(canvas);
        return bitmap;
    }

    // ---------- 刻度与轴 ----------

    protected void computeScale() {
        double min = 0;
        double max = 0;
        boolean first = true;
        if (data != null) {
            for (int i = 0; i < data.series.length; i++) {
                if (!data.visible[i]) {
                    continue;
                }
                for (int p = 0; p < data.pointCount(); p++) {
                    double value = data.value(i, p);
                    if (first) {
                        min = max = value;
                        first = false;
                    } else {
                        min = Math.min(min, value);
                        max = Math.max(max, value);
                    }
                }
            }
        }
        if (first) {
            min = 0;
            max = 1;
        }
        if (min == max) {
            max = min + 1;
        }
        tickStep = niceStep((max - min) / TARGET_TICKS);
        minValue = Math.floor(min / tickStep) * tickStep;
        maxValue = Math.ceil(max / tickStep) * tickStep;
        if (minValue > 0) {
            minValue = 0;
        }
        if (maxValue < 0) {
            maxValue = 0;
        }
    }

    protected static double niceStep(double raw) {
        if (raw <= 0 || Double.isNaN(raw)) {
            return 1;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double[] candidates = {1, 2, 2.5, 5, 10};
        for (double candidate : candidates) {
            if (candidate * magnitude >= raw) {
                return candidate * magnitude;
            }
        }
        return 10 * magnitude;
    }

    protected float yOf(double value, float top, float bottom) {
        double span = maxValue - minValue;
        if (span <= 0) {
            return bottom;
        }
        return (float) (bottom - (value - minValue) / span * (bottom - top));
    }

    /** 轴标签：大数用万，小数保留一位 */
    protected String formatTick(double value) {
        double absolute = Math.abs(value);
        if (absolute >= 100000000) {
            return String.format(java.util.Locale.US, "%.1f亿", value / 100000000.0);
        }
        if (absolute >= 10000) {
            return String.format(java.util.Locale.US, "%.1f万", value / 10000.0);
        }
        if (tickStep < 1) {
            return String.format(java.util.Locale.US, "%.1f", value);
        }
        return String.format(java.util.Locale.US, "%.0f", value);
    }

    /** 数值标签：带千分位的元 */
    protected String formatValue(double value) {
        return String.format(java.util.Locale.US, "%,.2f", value);
    }

    /** 画网格、y 轴刻度文字、标题、图例，返回绘图区 */
    protected RectF drawFrame(Canvas canvas) {
        int legendRows = layoutLegend();
        float left = 52 * density;
        float top = 26 * density + (legendRows - 1) * 14 * density;
        float right = getWidth() - 8 * density;
        float bottom = getHeight() - 30 * density;
        plot.set(left, top, right, bottom);

        if (data != null && data.title != null) {
            textPaint.setTextSize(13 * density);
            textPaint.setFakeBoldText(true);
            canvas.drawText(data.title, left, 16 * density, textPaint);
            textPaint.setFakeBoldText(false);
            textPaint.setTextSize(12 * density);
        }
        drawLegend(canvas);

        int ticks = (int) Math.round((maxValue - minValue) / tickStep);
        for (int i = 0; i <= ticks; i++) {
            double value = minValue + i * tickStep;
            float y = yOf(value, top, bottom);
            canvas.drawLine(left, y, right, y, Math.abs(value) < tickStep / 10 ? axisPaint : gridPaint);
            smallPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(formatTick(value), left - 6 * density, y + 4 * density, smallPaint);
            smallPaint.setTextAlign(Paint.Align.LEFT);
        }
        return plot;
    }

    /** 图例一排排不下就折行，返回占了几行，绘图区据此下移 */
    private int layoutLegend() {
        legendRects.clear();
        if (data == null || data.series.length < 2) {
            return 1;
        }
        float right = getWidth() - 8 * density;
        float x = right;
        float y = 16 * density;
        int rows = 1;
        for (int i = data.series.length - 1; i >= 0; i--) {
            float width = smallPaint.measureText(data.series[i]) + 16 * density;
            float boxLeft = x - width;
            if (boxLeft < 8 * density) {
                rows++;
                x = right;
                y += 14 * density;
                boxLeft = x - width;
            }
            legendRects.add(0, new RectF(boxLeft, y - 12 * density, x, y + 3 * density));
            x = boxLeft - 8 * density;
        }
        return rows;
    }

    private void drawLegend(Canvas canvas) {
        for (int i = 0; i < legendRects.size(); i++) {
            RectF rect = legendRects.get(i);
            boolean on = data.visible[i];
            fillPaint.setColor(Palette.color(i));
            fillPaint.setAlpha(on ? 255 : 70);
            canvas.drawRoundRect(rect.left, rect.top + 3 * density, rect.left + 10 * density,
                    rect.bottom - 4 * density, 2 * density, 2 * density, fillPaint);
            smallPaint.setColor(on ? colorText : colorTextDim);
            canvas.drawText(data.series[i], rect.left + 13 * density, rect.bottom - 3 * density,
                    smallPaint);
            smallPaint.setColor(colorTextSecondary);
        }
        fillPaint.setAlpha(255);
    }

    protected void toggleSeries(int index) {
        if (data != null && index >= 0 && index < data.visible.length) {
            data.visible[index] = !data.visible[index];
            invalidate();
        }
    }

    // ---------- 触摸 ----------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            for (int i = 0; i < legendRects.size(); i++) {
                if (legendRects.get(i).contains(event.getX(), event.getY())) {
                    toggleSeries(i);
                    return true;
                }
            }
            int index = indexAt(event.getX(), event.getY());
            if (index != selected) {
                selected = index;
                invalidate();
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    /** 子类用来把触点换算成数据点下标，返回 -1 表示没命中 */
    protected int indexAt(float x, float y) {
        return -1;
    }

    /** 按 x 均分的数据点下标（柱状、折线、堆叠通用） */
    protected int indexBySlice(float x, int count) {
        if (count <= 0 || x < plot.left || x > plot.right) {
            return -1;
        }
        float slot = plot.width() / count;
        int index = (int) ((x - plot.left) / slot);
        if (index < 0) {
            index = 0;
        }
        if (index >= count) {
            index = count - 1;
        }
        return index;
    }

    /** 在绘图区下沿画当前选中点的数值，过宽时截断 */
    protected void drawSelection(Canvas canvas, String... lines) {
        if (selected < 0 || lines == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (line != null) {
                text.append(line).append("  ");
            }
        }
        if (text.length() == 0) {
            return;
        }
        textPaint.setTextSize(11 * density);
        textPaint.setColor(colorText);
        canvas.drawText(ellipsize(text.toString(), plot.width()), plot.left,
                plot.bottom - 4 * density, textPaint);
        textPaint.setTextSize(12 * density);
    }

    private String ellipsize(String text, float maxWidth) {
        if (textPaint.measureText(text) <= maxWidth) {
            return text;
        }
        for (int i = text.length() - 1; i > 0; i--) {
            String candidate = text.substring(0, i) + "…";
            if (textPaint.measureText(candidate) <= maxWidth) {
                return candidate;
            }
        }
        return "…";
    }

    /** 按像素宽度截断，省略号放中间（前半保开头、后半保结尾）；调用前先把 paint 字号设好 */
    protected static String clipTo(Paint paint, String text, float maxWidth) {
        if (text == null) {
            return "";
        }
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        for (int keep = text.length() - 1; keep >= 1; keep--) {
            int head = (keep + 1) / 2;
            int tail = keep - head;
            String candidate = text.substring(0, head) + "…"
                    + (tail > 0 ? text.substring(text.length() - tail) : "");
            if (paint.measureText(candidate) <= maxWidth) {
                return candidate;
            }
        }
        return "…";
    }

    protected void drawEmpty(Canvas canvas) {
        smallPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("这段时间没有数据", getWidth() / 2f, getHeight() / 2f, smallPaint);
        smallPaint.setTextAlign(Paint.Align.LEFT);
    }

    /** 横轴标签，太密时自动隔几个画一个 */
    protected void drawXLabels(Canvas canvas, int count) {
        if (count <= 0) {
            return;
        }
        float slot = plot.width() / count;
        smallPaint.setTextAlign(Paint.Align.CENTER);
        int stride = (int) Math.ceil(Math.max(1, 34 * density) / Math.max(1, slot));
        for (int i = 0; i < count; i += stride) {
            float x = plot.left + slot * (i + 0.5f);
            String label = data.labels[i];
            if (label != null && label.length() > 9) {
                label = label.substring(0, 9) + "…";
            }
            canvas.drawText(label, x, getHeight() - 12 * density, smallPaint);
        }
        smallPaint.setTextAlign(Paint.Align.LEFT);
    }
}

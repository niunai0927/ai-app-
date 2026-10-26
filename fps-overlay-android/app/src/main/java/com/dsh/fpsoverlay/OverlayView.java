package com.dsh.fpsoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * 悬浮窗里自绘的那个小面板：大号帧率数字 + 帧时间 + 最近 90 次采样的曲线。
 * 全部用 Canvas 画，不依赖任何布局/资源，尺寸随 scale 缩放，方便小屏平板。
 */
public class OverlayView extends View {

    private static final int MAX_HISTORY = 90;

    private static final int COLOR_OK = 0xFF4CD964;
    private static final int COLOR_WARN = 0xFFFFCC00;
    private static final int COLOR_BAD = 0xFFFF3B30;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUB = 0xFF8D98A3;
    private static final int COLOR_BG = 0xCC0B0F14;

    private final float density;
    private float scale = 1f;
    private boolean graph = true;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path path = new Path();
    private final ArrayDeque<Float> history = new ArrayDeque<Float>();

    private double fps;
    private double avgMs;
    private double lowFps;
    private double refreshHz;
    private String message = "初始化…";
    private int activeColor = COLOR_OK;

    public OverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        bgPaint.setColor(COLOR_BG);

        numPaint.setColor(COLOR_TEXT);
        numPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        numPaint.setTextAlign(Paint.Align.LEFT);

        tagPaint.setColor(COLOR_SUB);
        tagPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tagPaint.setTextAlign(Paint.Align.LEFT);

        subPaint.setColor(COLOR_SUB);
        subPaint.setTextAlign(Paint.Align.LEFT);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setStrokeWidth(dp(0.8f));
    }

    public void setScale(float s) {
        if (s < 0.7f) {
            s = 0.7f;
        }
        if (s > 1.8f) {
            s = 1.8f;
        }
        scale = s;
        requestLayout();
    }

    public boolean isGraph() {
        return graph;
    }

    public void setGraph(boolean g) {
        graph = g;
        requestLayout();
    }

    public void pushHistory(double v) {
        history.addLast((float) v);
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

    public void setStats(FpsEngine.Stats s) {
        message = s.message;
        fps = s.fps;
        avgMs = s.avgMs;
        lowFps = s.lowFps;
        refreshHz = s.refreshHz;
        double ref = refreshHz > 1 ? refreshHz : 60.0;
        if (fps <= 0) {
            activeColor = COLOR_SUB;
        } else if (fps >= ref * 0.9) {
            activeColor = COLOR_OK;
        } else if (fps >= ref * 0.6) {
            activeColor = COLOR_WARN;
        } else {
            activeColor = COLOR_BAD;
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = (int) (dp(188) * scale);
        int h = (int) (dp(graph ? 116 : 76) * scale);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float pad = dp(12) * scale;
        float radius = dp(14) * scale;

        box.set(0, 0, w, h);
        canvas.drawRoundRect(box, radius, radius, bgPaint);

        if (fps <= 0) {
            // 没有数据时只显示一行说明
            subPaint.setTextSize(dp(12) * scale);
            subPaint.setColor(COLOR_WARN);
            String msg = message == null ? "等待画面数据…" : message;
            float maxWidth = w - pad * 2;
            float size = dp(12) * scale;
            while (subPaint.measureText(msg) > maxWidth && size > dp(7) * scale) {
                size -= dp(0.5f) * scale;
                subPaint.setTextSize(size);
            }
            Paint.FontMetrics fm = subPaint.getFontMetrics();
            float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(msg, pad, baseline, subPaint);
            return;
        }

        // 帧率大数字
        float numSize = dp(graph ? 40 : 34) * scale;
        numPaint.setTextSize(numSize);
        numPaint.setColor(activeColor);
        String num = fps < 20 ? String.format(Locale.US, "%.1f", fps)
                : String.valueOf(Math.round(fps));
        Paint.FontMetrics nfm = numPaint.getFontMetrics();
        float numBaseline = pad - nfm.ascent;
        canvas.drawText(num, pad, numBaseline, numPaint);

        // FPS 角标
        float numWidth = numPaint.measureText(num);
        tagPaint.setTextSize(dp(10) * scale);
        canvas.drawText("FPS", pad + numWidth + dp(4) * scale,
                numBaseline - dp(1.5f) * scale, tagPaint);

        // 底部说明行
        float subSize = dp(10.5f) * scale;
        subPaint.setTextSize(subSize);
        subPaint.setColor(COLOR_SUB);
        String sub;
        if (message != null) {
            sub = message;
        } else {
            sub = String.format(Locale.US, "%.1f ms   ·   1%% %.0f", avgMs, lowFps);
            if (refreshHz > 1) {
                sub = sub + String.format(Locale.US, "   ·   %.0fHz", refreshHz);
            }
        }

        float graphTop = h - dp(38) * scale;
        float subBaseline = graph ? (graphTop - dp(8) * scale) : (h - pad);
        Paint.FontMetrics sfm = subPaint.getFontMetrics();
        subBaseline = subBaseline - sfm.descent;
        canvas.drawText(sub, pad, subBaseline, subPaint);

        if (graph) {
            drawGraph(canvas, pad, graphTop, w - pad, h - dp(8) * scale);
        }
    }

    private void drawGraph(Canvas canvas, float left, float top, float right, float bottom) {
        canvas.drawLine(left, bottom, right, bottom, gridPaint);
        if (history.size() < 2 || bottom <= top) {
            return;
        }
        float maxV = 60f;
        for (Float v : history) {
            if (v > maxV) {
                maxV = v;
            }
        }
        maxV = maxV * 1.1f;

        int n = history.size();
        float step = (right - left) / (MAX_HISTORY - 1);
        path.reset();
        int i = 0;
        for (Float v : history) {
            float x = right - (n - 1 - i) * step;
            float y = bottom - (v / maxV) * (bottom - top);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
            i++;
        }
        linePaint.setColor(activeColor);
        linePaint.setStrokeWidth(dp(1.4f) * scale);
        canvas.drawPath(path, linePaint);
    }

    private float dp(float v) {
        return v * density;
    }
}

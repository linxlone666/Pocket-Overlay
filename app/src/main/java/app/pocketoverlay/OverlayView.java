package app.pocketoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

final class OverlayView extends View {
    static final float HEIGHT = 54f;
    private static final float SEGMENT_PITCH = 12f;
    private static final float GAP = 10f;
    private static final int PANEL = 0xde38353e;
    private static final int TRACK = 0xff56525d;
    private static final int PINK = 0xffd5a4b5;
    private static final int LAVENDER = 0xffbbabdf;
    private static final int TEXT = 0xffbcb7c3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path symbol = new Path();
    private final CornerPathEffect starCorners = new CornerPathEffect(1.5f);
    private boolean brightness;
    private float level;
    private int segments;
    private String time;
    private int batteryPercent;
    private boolean charging;

    OverlayView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(18f);
    }

    void update(boolean brightness, float level, int segments, String time,
            int batteryPercent, boolean charging) {
        this.brightness = brightness;
        this.level = Math.max(0f, Math.min(1f, level));
        this.segments = segments;
        this.time = time;
        this.batteryPercent = batteryPercent;
        this.charging = charging;
        invalidate();
    }

    float designWidth() {
        return meterWidth() + GAP + Math.max(152f, paint.measureText(time) + 82f);
    }

    private float meterWidth() {
        return 70f + SEGMENT_PITCH * segments;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        float scale = getWidth() / designWidth();
        canvas.scale(scale, scale);

        float statusLeft = meterWidth() + GAP;
        paint.setColor(PANEL);
        canvas.drawRoundRect(0, 0, meterWidth(), HEIGHT, 11, 11, paint);
        canvas.drawRoundRect(statusLeft, 2, designWidth(), HEIGHT - 2, 11, 11, paint);

        paint.setColor(brightness ? PINK : LAVENDER);
        drawMeterIcon(canvas);
        int filled = Math.round(level * segments);
        for (int index = 0; index < segments; index++) {
            paint.setColor(index < filled ? (brightness ? PINK : LAVENDER) : TRACK);
            float x = 54f + SEGMENT_PITCH * index;
            canvas.drawRoundRect(x, 14, x + 10, 40, 2, 2, paint);
        }

        paint.setColor(TEXT);
        float baseline = HEIGHT / 2f - (paint.ascent() + paint.descent()) / 2f;
        canvas.drawText(time, statusLeft + 15, baseline, paint);
        drawBattery(canvas, designWidth() - 55f);
        canvas.restore();
    }

    private void drawMeterIcon(Canvas canvas) {
        symbol.reset();
        if (brightness) {
            for (int point = 0; point < 10; point++) {
                double angle = point * Math.PI / 5 - Math.PI / 2;
                float radius = point % 2 == 0 ? 11f : 5.8f;
                float x = 32f + radius * (float) Math.cos(angle);
                float y = 27f + radius * (float) Math.sin(angle);
                if (point == 0) {
                    symbol.moveTo(x, y);
                } else {
                    symbol.lineTo(x, y);
                }
            }
        } else {
            symbol.moveTo(23, 23);
            symbol.lineTo(27, 23);
            symbol.lineTo(33, 18);
            symbol.lineTo(33, 36);
            symbol.lineTo(27, 31);
            symbol.lineTo(23, 31);
        }
        symbol.close();
        if (brightness) {
            paint.setPathEffect(starCorners);
        }
        canvas.drawPath(symbol, paint);
        paint.setPathEffect(null);
        if (!brightness && level > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            canvas.drawArc(31, 22, 39, 32, -60, 120, false, paint);
            canvas.drawArc(29, 18, 44, 36, -53, 106, false, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawBattery(Canvas canvas, float left) {
        paint.setColor(TEXT);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        canvas.drawRoundRect(left, 17, left + 37, 37, 4, 4, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(left + 38, 23, left + 40.5f, 31, 1, 1, paint);

        if (batteryPercent > 0 && batteryPercent <= 100) {
            paint.setColor(0xff897ba6);
            canvas.drawRoundRect(left + 3, 20, left + 3 + 31f * batteryPercent / 100f,
                    34, 2, 2, paint);
        }
        if (charging) {
            paint.setColor(LAVENDER);
            symbol.reset();
            symbol.moveTo(left + 21, 19);
            symbol.lineTo(left + 14, 28);
            symbol.lineTo(left + 18, 28);
            symbol.lineTo(left + 16, 35);
            symbol.lineTo(left + 24, 25);
            symbol.lineTo(left + 20, 25);
            symbol.close();
            canvas.drawPath(symbol, paint);
        }
    }
}

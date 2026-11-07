package app.pocketoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;

final class OverlayView extends View {
    // Dimensions measured in landscape at the Pocket 6's default display density.
    static final int REFERENCE_DENSITY_DPI = 369;
    static final float HEIGHT = 100f;
    static final float TOP_INSET = 36f;
    private static final float SEGMENT_PITCH = 22f;
    private static final float GAP = 18f;
    private static final float STATUS_WIDTH = 280f;
    private static final float CLOCK_INSET = 27f;
    private static final float BATTERY_INSET = 102f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path symbol = new Path();
    private final CornerPathEffect starCorners = new CornerPathEffect(3f);
    private boolean brightness;
    private float level;
    private int segments;
    private String time;
    private int batteryPercent;
    private boolean charging;
    private int panelColor;
    private int trackColor;
    private int brightnessColor;
    private int volumeColor;
    private int statusColor;
    private int batteryColor;

    OverlayView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(33f);
    }

    void applyTheme(int theme) {
        Context context = getContext();
        boolean dark = OverlaySettings.usesDarkColors(context, theme);
        if (theme == OverlaySettings.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int surface = context.getColor(dark
                    ? android.R.color.system_neutral1_800 : android.R.color.system_neutral1_50);
            panelColor = 0xde000000 | (surface & 0x00ffffff);
            trackColor = context.getColor(dark
                    ? android.R.color.system_neutral2_600 : android.R.color.system_neutral2_200);
            brightnessColor = context.getColor(dark
                    ? android.R.color.system_accent3_200 : android.R.color.system_accent3_700);
            volumeColor = context.getColor(dark
                    ? android.R.color.system_accent1_200 : android.R.color.system_accent1_700);
            statusColor = context.getColor(dark
                    ? android.R.color.system_neutral1_100 : android.R.color.system_neutral1_900);
            batteryColor = context.getColor(dark
                    ? android.R.color.system_accent1_500 : android.R.color.system_accent1_300);
        } else {
            panelColor = dark ? 0xde38353e : 0xdef8f0fc;
            trackColor = dark ? 0xff56525d : 0xffd5cadc;
            brightnessColor = dark ? 0xffd5a4b5 : 0xff8f4965;
            volumeColor = dark ? 0xffbbabdf : 0xff624785;
            statusColor = dark ? 0xffbcb7c3 : 0xff362c40;
            batteryColor = dark ? 0xff897ba6 : 0xffb9a5cd;
        }
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
        return meterWidth() + GAP + STATUS_WIDTH;
    }

    private float meterWidth() {
        return 132f + SEGMENT_PITCH * segments;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        float scale = getWidth() / designWidth();
        canvas.scale(scale, scale);

        float statusLeft = meterWidth() + GAP;
        paint.setColor(panelColor);
        canvas.drawRoundRect(0, 0, meterWidth(), HEIGHT, 20, 20, paint);
        canvas.drawRoundRect(statusLeft, 3, designWidth(), HEIGHT - 4, 20, 20, paint);

        int meterColor = brightness ? brightnessColor : volumeColor;
        paint.setColor(meterColor);
        drawMeterIcon(canvas);
        int filled = Math.round(level * segments);
        for (int index = 0; index < segments; index++) {
            paint.setColor(index < filled ? meterColor : trackColor);
            float x = 100f + SEGMENT_PITCH * index;
            canvas.drawRoundRect(x, 26, x + 18, 74, 4, 4, paint);
        }

        paint.setColor(statusColor);
        float baseline = HEIGHT / 2f - (paint.ascent() + paint.descent()) / 2f;
        float clockWidth = STATUS_WIDTH - CLOCK_INSET - BATTERY_INSET - 12;
        paint.setTextScaleX(Math.min(1f, clockWidth / paint.measureText(time)));
        canvas.drawText(time, statusLeft + CLOCK_INSET, baseline, paint);
        paint.setTextScaleX(1f);
        drawBattery(canvas, designWidth() - BATTERY_INSET);
        canvas.restore();
    }

    private void drawMeterIcon(Canvas canvas) {
        symbol.reset();
        if (brightness) {
            for (int point = 0; point < 10; point++) {
                double angle = point * Math.PI / 5 - Math.PI / 2;
                float radius = point % 2 == 0 ? 20f : 11f;
                float x = 59f + radius * (float) Math.cos(angle);
                float y = 50f + radius * (float) Math.sin(angle);
                if (point == 0) {
                    symbol.moveTo(x, y);
                } else {
                    symbol.lineTo(x, y);
                }
            }
        } else {
            symbol.moveTo(43, 43);
            symbol.lineTo(50, 43);
            symbol.lineTo(61, 33);
            symbol.lineTo(61, 67);
            symbol.lineTo(50, 57);
            symbol.lineTo(43, 57);
        }
        symbol.close();
        if (brightness) {
            paint.setPathEffect(starCorners);
        }
        canvas.drawPath(symbol, paint);
        paint.setPathEffect(null);
        if (!brightness && level > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            canvas.drawArc(57, 41, 72, 59, -60, 120, false, paint);
            canvas.drawArc(54, 33, 81, 67, -53, 106, false, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawBattery(Canvas canvas, float left) {
        canvas.save();
        canvas.translate(left, 0);
        paint.setColor(statusColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        canvas.drawRoundRect(0, 31, 68, 69, 7, 7, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(70, 43, 75, 57, 2, 2, paint);

        if (batteryPercent > 0 && batteryPercent <= 100) {
            paint.setColor(batteryColor);
            canvas.drawRoundRect(6, 37, 6 + 56f * batteryPercent / 100f, 63, 4, 4, paint);
        }
        if (charging) {
            paint.setColor(volumeColor);
            symbol.reset();
            symbol.moveTo(39, 35);
            symbol.lineTo(26, 52);
            symbol.lineTo(33, 52);
            symbol.lineTo(30, 65);
            symbol.lineTo(44, 46);
            symbol.lineTo(37, 46);
            symbol.close();
            canvas.drawPath(symbol, paint);
        }
        canvas.restore();
    }
}

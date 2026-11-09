package app.pocketoverlay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
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
    private boolean brightness;
    private float level;
    private float meterFill;
    private ValueAnimator meterMotion;
    private int segments;
    private String time;
    private int batteryPercent;
    private boolean showChargingIndicator;
    private boolean showBatteryFill;
    private String batteryLabel;
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
            trackColor = context.getColor(dark
                    ? android.R.color.system_neutral2_600 : android.R.color.system_neutral2_200);
            brightnessColor = context.getColor(dark
                    ? android.R.color.system_accent3_200 : android.R.color.system_accent3_700);
            volumeColor = context.getColor(dark
                    ? android.R.color.system_accent1_200 : android.R.color.system_accent1_700);
            batteryColor = context.getColor(dark
                    ? android.R.color.system_accent1_500 : android.R.color.system_accent1_300);
        } else {
            trackColor = dark ? 0xff56525d : 0xffd5cadc;
            brightnessColor = dark ? 0xffd5a4b5 : 0xff8f4965;
            volumeColor = dark ? 0xffbbabdf : 0xff624785;
            batteryColor = dark ? 0xff897ba6 : 0xffb9a5cd;
        }
        panelColor = panelColor(context, theme, dark);
        statusColor = statusColor(context, theme, dark);
    }

    static int panelColor(Context context, int theme, boolean dark) {
        if (theme == OverlaySettings.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int surface = context.getColor(dark
                    ? android.R.color.system_neutral1_800 : android.R.color.system_neutral1_50);
            return 0xde000000 | (surface & 0x00ffffff);
        }
        return dark ? 0xde38353e : 0xdef8f0fc;
    }

    static int statusColor(Context context, int theme, boolean dark) {
        if (theme == OverlaySettings.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getColor(dark
                    ? android.R.color.system_neutral1_100 : android.R.color.system_neutral1_900);
        }
        return dark ? 0xffbcb7c3 : 0xff362c40;
    }

    void update(boolean brightness, float level, int segments, String time,
            int batteryPercent, boolean charging, SharedPreferences settings) {
        boolean sameMeter = this.brightness == brightness && this.segments == segments;
        float nextLevel = Math.max(0f, Math.min(1f, level));
        if (!sameMeter || nextLevel != this.level) {
            if (meterMotion != null) meterMotion.cancel();
            int filled = Math.round(nextLevel * segments);
            if (sameMeter && isAttachedToWindow() && UiMotion.enabled(getContext())
                    && settings.getBoolean(OverlaySettings.SLIDE, true)) {
                meterMotion = ValueAnimator.ofFloat(meterFill, filled);
                meterMotion.setDuration(110);
                meterMotion.setInterpolator(UiMotion.ENTER);
                meterMotion.addUpdateListener(animation -> {
                    meterFill = (float) animation.getAnimatedValue();
                    invalidate();
                });
                meterMotion.start();
            } else {
                meterFill = filled;
            }
        }
        this.brightness = brightness;
        this.level = nextLevel;
        this.segments = segments;
        this.time = time;
        this.batteryPercent = batteryPercent;
        showChargingIndicator = charging && settings.getBoolean(OverlaySettings.OVERLAY_CHARGING_INDICATOR, true);
        showBatteryFill = settings.getBoolean(OverlaySettings.OVERLAY_BATTERY_FILL, true);
        boolean showBatteryPercent = settings.getBoolean(OverlaySettings.OVERLAY_BATTERY_PERCENT, false);
        batteryLabel = showBatteryPercent && batteryPercent >= 0 && batteryPercent <= 100
                ? getContext().getString(R.string.battery_percentage_value, batteryPercent) : null;
        invalidate();
    }

    float designWidth() {
        return meterWidth() + GAP + STATUS_WIDTH;
    }

    private float meterWidth() {
        return 132f + SEGMENT_PITCH * segments;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (meterMotion != null) {
            meterMotion.cancel();
            meterMotion = null;
        }
        meterFill = Math.round(level * segments);
        super.onDetachedFromWindow();
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
        for (int index = 0; index < segments; index++) {
            float fill = meterFill - index;
            float x = 100f + SEGMENT_PITCH * index;
            if (fill < 1f) {
                paint.setColor(trackColor);
                canvas.drawRoundRect(x, 26, x + 18, 74, 4, 4, paint);
            }
            if (fill > 0f) {
                paint.setColor(meterColor);
                paint.setAlpha(Math.round(Color.alpha(meterColor) * Math.min(fill, 1f)));
                canvas.drawRoundRect(x, 26, x + 18, 74, 4, 4, paint);
            }
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
        if (brightness) {
            drawSun(canvas);
            return;
        }

        symbol.reset();
        symbol.moveTo(38, 43);
        symbol.lineTo(45, 43);
        symbol.lineTo(56, 33);
        symbol.lineTo(56, 67);
        symbol.lineTo(45, 57);
        symbol.lineTo(38, 57);
        symbol.close();
        canvas.drawPath(symbol, paint);

        paint.setStrokeWidth(3f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (level == 0f) {
            canvas.drawLine(64, 45, 74, 55, paint);
            canvas.drawLine(64, 55, 74, 45, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            int waves = (int) Math.ceil(level * 3);
            for (int wave = 0; wave < waves; wave++) {
                float radius = 10f + 7f * wave;
                canvas.drawArc(55 - radius, 50 - radius, 55 + radius, 50 + radius,
                        -47, 94, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawSun(Canvas canvas) {
        float radius = 8f + 4f * level;
        canvas.drawCircle(59, 50, radius, paint);
        paint.setStrokeWidth(3f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.save();
        for (int ray = 0; ray < 8; ray++) {
            canvas.drawLine(59, 50 - radius - 4, 59, 50 - radius - 7 - 4 * level, paint);
            canvas.rotate(45, 59, 50);
        }
        canvas.restore();
        paint.setStrokeCap(Paint.Cap.BUTT);
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

        boolean filled = showBatteryFill && batteryPercent > 0 && batteryPercent <= 100;
        if (filled) {
            paint.setColor(batteryColor);
            canvas.drawRoundRect(5, 35, 5 + 58f * batteryPercent / 100f, 65, 4, 4, paint);
        }
        if (batteryLabel != null) {
            drawBatteryLabel(canvas, filled);
        }
        if (showChargingIndicator) {
            paint.setColor(volumeColor);
            symbol.reset();
            symbol.moveTo(94, 37);
            symbol.lineTo(81, 52);
            symbol.lineTo(88, 52);
            symbol.lineTo(85, 63);
            symbol.lineTo(99, 47);
            symbol.lineTo(92, 47);
            symbol.close();
            canvas.drawPath(symbol, paint);
        }
        canvas.restore();
    }

    private void drawBatteryLabel(Canvas canvas, boolean filled) {
        paint.setTextSize(24f);
        float textWidth = paint.measureText(batteryLabel);
        float textScale = Math.min(1f, 58f / textWidth);
        paint.setTextScaleX(textScale);
        float left = (68f - textWidth * textScale) / 2f;
        float baseline = HEIGHT / 2f - (paint.ascent() + paint.descent()) / 2f;
        if (filled) {
            // Keep the text legible where it crosses the boundary of the colored fill.
            paint.setColor(panelColor | 0xff000000);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            canvas.drawText(batteryLabel, left, baseline, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        paint.setColor(statusColor);
        canvas.drawText(batteryLabel, left, baseline, paint);
        paint.setTextScaleX(1f);
        paint.setTextSize(33f);
    }
}

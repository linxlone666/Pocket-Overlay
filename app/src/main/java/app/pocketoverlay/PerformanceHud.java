package app.pocketoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.widget.TextView;

final class PerformanceHud {
    private static final PerformanceMetric[] BATTERY_METRICS = {
            PerformanceMetric.BATTERY, PerformanceMetric.BATTERY_TEMPERATURE,
            PerformanceMetric.POWER, PerformanceMetric.BATTERY_TIME
    };

    private PerformanceHud() {}

    static void setup(TextView panel) {
        panel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        panel.setEllipsize(TextUtils.TruncateAt.END);
    }

    static void render(TextView panel, SharedPreferences settings, PerformanceReadings readings,
            Intent battery, Bundle sample, int metrics, int style, float scale, int opacity, int width) {
        Context context = panel.getContext();
        int theme = settings.getInt(OverlaySettings.THEME, OverlaySettings.DARK);
        boolean dark = OverlaySettings.usesDarkColors(context, theme);
        int background = OverlayView.panelColor(context, theme, dark);
        int alpha = Math.round(opacity * 255 / 100f);
        int fill = (alpha << 24) | (background & 0x00ffffff);
        if (panel.getBackground() instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) panel.getBackground();
            if (shape.getColor().getDefaultColor() != fill) {
                shape.setColor(fill);
            }
        } else {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(fill);
            shape.setCornerRadius(dp(context, 8));
            panel.setBackground(shape);
        }
        panel.setTextColor(OverlayView.statusColor(context, theme, dark));

        boolean inline = style == OverlaySettings.PERFORMANCE_INLINE;
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (PerformanceGroup group : PerformanceGroup.order(settings)) {
            if (group == PerformanceGroup.CHARGE) {
                StringBuilder values = new StringBuilder();
                boolean hasPowerOrRuntime = false;
                for (PerformanceMetric metric : BATTERY_METRICS) {
                    if (metric.selected(metrics)) {
                        boolean isPowerOrRuntime = metric == PerformanceMetric.POWER
                                || metric == PerformanceMetric.BATTERY_TIME;
                        if (values.length() > 0) {
                            if (!inline && isPowerOrRuntime && !hasPowerOrRuntime) {
                                values.append("\n     ");
                            } else {
                                values.append(inline ? " " : "   ");
                            }
                        }
                        values.append(readings.value(metric, battery, sample));
                        hasPowerOrRuntime |= isPowerOrRuntime;
                    }
                }
                if (values.length() > 0) {
                    append(text, panel, PerformanceMetric.BATTERY, values.toString(), inline, dark);
                }
                continue;
            }
            PerformanceMetric primary = group.primary;
            PerformanceMetric secondary = group.secondary;
            boolean showPrimary = primary.selected(metrics);
            boolean showSecondary = secondary != null && secondary.selected(metrics);
            if (showPrimary) {
                append(text, panel, primary, readings.value(primary, battery, sample), inline, dark);
            }
            if (showSecondary) {
                boolean together = showPrimary && style != OverlaySettings.PERFORMANCE_FULL_LIST;
                String value = readings.value(secondary, battery, sample);
                if (together) {
                    text.append(inline ? " " : "   ").append(value);
                } else {
                    append(text, panel, secondary, value, inline, dark);
                }
            }
        }
        if (text.length() == 0) {
            text.append(context.getString(R.string.performance_preview_empty));
        }
        int lines = inline ? 1 : Integer.MAX_VALUE;
        if (panel.getMaxLines() != lines) {
            panel.setMaxLines(lines);
            panel.setHorizontallyScrolling(inline);
        }
        float size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12 * scale / 100f,
                context.getResources().getDisplayMetrics());
        if (inline && width > 0) {
            int available = Math.max(1, width - panel.getPaddingLeft() - panel.getPaddingRight() - dp(context, 3));
            float minimum = Math.min(size, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8,
                    context.getResources().getDisplayMetrics()));
            TextPaint paint = new TextPaint(panel.getPaint());
            // Measure again at the fitted size because hinted glyph widths do not scale linearly.
            for (int attempt = 0; attempt < 4 && size > minimum; attempt++) {
                paint.setTextSize(size);
                float measured = Layout.getDesiredWidth(text, paint);
                if (measured <= available) {
                    break;
                }
                size = Math.max(minimum, size * available / measured - 0.5f);
            }
        }
        if (Math.abs(panel.getTextSize() - size) > 0.1f) {
            panel.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        }
        if (!TextUtils.equals(panel.getText(), text) || !Boolean.valueOf(dark).equals(panel.getTag())) {
            panel.setText(text);
            panel.setTag(dark);
        }
    }

    static int color(PerformanceMetric metric, boolean dark) {
        switch (metric) {
            case FPS: return dark ? 0xffff9c91 : 0xff9e372b;
            case CPU:
            case CPU_TEMPERATURE: return dark ? 0xff93caff : 0xff1e5594;
            case GPU:
            case GPU_TEMPERATURE: return dark ? 0xff91e1af : 0xff256744;
            case MEMORY: return dark ? 0xffe5a9ff : 0xff783e91;
            case BATTERY:
            case BATTERY_TEMPERATURE:
            case BATTERY_TIME: return dark ? 0xffffc29b : 0xff8c4c21;
            case POWER: return dark ? 0xffffde83 : 0xff765600;
            default: return dark ? 0xffa7e1df : 0xff2a6069;
        }
    }

    private static void append(SpannableStringBuilder text, TextView panel, PerformanceMetric metric,
            String value, boolean inline, boolean dark) {
        if (text.length() != 0) {
            text.append(inline ? " │ " : "\n");
        }
        int start = text.length();
        int label = metric == PerformanceMetric.BATTERY ? R.string.performance_batt
                : metric == PerformanceMetric.REFRESH_RATE ? R.string.performance_display
                : metric == PerformanceMetric.FAN ? R.string.performance_fan
                : metric.title;
        text.append(panel.getContext().getString(label));
        text.setSpan(new ForegroundColorSpan(color(metric, dark)), start, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(" ").append(value);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

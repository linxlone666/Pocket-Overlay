package app.pocketoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.widget.TextView;

import java.util.List;
import java.util.Objects;

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
        boolean fullList = style == OverlaySettings.PERFORMANCE_FULL_LIST;
        int ramFormat = OverlaySettings.ramFormat(settings);
        int fanFormat = OverlaySettings.fanFormat(settings);
        List<PerformanceGroup> groups = PerformanceGroup.order(settings);
        int labelWidth = inline ? 0 : labelWidth(context, groups, metrics, fullList, sample);
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (PerformanceGroup group : groups) {
            if (group == PerformanceGroup.CHARGE) {
                SpannableStringBuilder values = new SpannableStringBuilder();
                boolean hasPowerOrRuntime = false;
                for (PerformanceMetric metric : BATTERY_METRICS) {
                    if (metric.selected(metrics)) {
                        boolean isPowerOrRuntime = metric == PerformanceMetric.POWER
                                || metric == PerformanceMetric.BATTERY_TIME;
                        if (values.length() > 0) {
                            if (!inline && isPowerOrRuntime && !hasPowerOrRuntime) {
                                values.append('\n');
                                appendSpaces(values, labelWidth + 1);
                            } else {
                                values.append(inline ? " " : "  ");
                            }
                        }
                        String value = readings.value(metric, battery, sample);
                        if (inline) {
                            values.append(value);
                        } else if (metric == PerformanceMetric.BATTERY) {
                            appendUsage(values, context, value);
                        } else if (values.length() == 0 || (isPowerOrRuntime && !hasPowerOrRuntime)) {
                            appendNumber(values, value);
                        } else {
                            values.append(value);
                        }
                        hasPowerOrRuntime |= isPowerOrRuntime;
                    }
                }
                if (values.length() > 0) {
                    appendLabel(text, label(context, PerformanceMetric.BATTERY), PerformanceMetric.BATTERY,
                            inline, dark, labelWidth);
                    text.append(values);
                }
                continue;
            }
            PerformanceMetric primary = group.primary;
            PerformanceMetric secondary = group.secondary;
            boolean showPrimary = primary.selected(metrics);
            boolean showSecondary = secondary != null && secondary.selected(metrics);
            if (showPrimary) {
                appendLabel(text, label(context, primary), primary, inline, dark, labelWidth);
                String value = readings.value(primary, battery, sample);
                if (primary == PerformanceMetric.CPU || primary == PerformanceMetric.GPU
                        || (primary == PerformanceMetric.MEMORY
                                && ramFormat == OverlaySettings.RAM_PERCENTAGE)) {
                    appendUsage(text, context, value);
                } else if (!inline && primary == PerformanceMetric.FAN
                        && fanFormat != OverlaySettings.FAN_RPM_ONLY) {
                    int separator = value.indexOf(" / ");
                    if (separator >= 0) {
                        appendUsage(text, context, value.substring(0, separator));
                        text.append(value.substring(separator));
                    } else {
                        appendUsage(text, context, value);
                    }
                } else if (inline) {
                    text.append(value);
                } else {
                    appendNumber(text, value);
                }
                if (fullList && primary == PerformanceMetric.GPU) {
                    text.append("  ");
                    appendClock(text, context, sample.getInt(PerformanceStats.GPU_MHZ, -1));
                }
            }
            if (showSecondary) {
                String value = readings.value(secondary, battery, sample);
                if (showPrimary) {
                    text.append(inline ? " " : "  ").append(value);
                } else {
                    appendLabel(text, label(context, secondary), secondary, inline, dark, labelWidth);
                    if (inline) {
                        text.append(value);
                    } else {
                        appendNumber(text, value);
                    }
                }
            }
            if (fullList && showPrimary && primary == PerformanceMetric.CPU) {
                appendCpuCores(text, context, sample, dark, labelWidth);
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
        int presentation = Objects.hash(dark, ramFormat, fanFormat, style);
        if (!TextUtils.equals(panel.getText(), text) || !Integer.valueOf(presentation).equals(panel.getTag())) {
            panel.setText(text);
            panel.setTag(presentation);
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

    private static String label(Context context, PerformanceMetric metric) {
        int label = metric == PerformanceMetric.BATTERY ? R.string.performance_batt
                : metric == PerformanceMetric.REFRESH_RATE ? R.string.performance_display
                : metric == PerformanceMetric.FAN ? R.string.performance_fan
                : metric.title;
        return context.getString(label);
    }

    private static int labelWidth(Context context, List<PerformanceGroup> groups, int metrics,
            boolean fullList, Bundle sample) {
        int width = 0;
        for (PerformanceGroup group : groups) {
            if (group == PerformanceGroup.CHARGE) {
                for (PerformanceMetric metric : BATTERY_METRICS) {
                    if (metric.selected(metrics)) {
                        width = Math.max(width, label(context, PerformanceMetric.BATTERY).length());
                        break;
                    }
                }
            } else if (group.primary.selected(metrics)) {
                width = Math.max(width, label(context, group.primary).length());
            } else if (group.secondary != null && group.secondary.selected(metrics)) {
                width = Math.max(width, label(context, group.secondary).length());
            }
        }
        if (fullList && PerformanceMetric.CPU.selected(metrics)) {
            int[] ids = sample.getIntArray(PerformanceStats.CPU_CORE_IDS);
            if (ids != null) {
                for (int id : ids) {
                    width = Math.max(width, context.getString(R.string.performance_cpu_core, id).length());
                }
            }
        }
        return width;
    }

    private static void appendLabel(SpannableStringBuilder text, String label, PerformanceMetric metric,
            boolean inline, boolean dark, int labelWidth) {
        if (text.length() != 0) {
            text.append(inline ? " │ " : "\n");
        }
        int start = text.length();
        text.append(label);
        text.setSpan(new ForegroundColorSpan(color(metric, dark)), start, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        appendSpaces(text, 1 + Math.max(0, labelWidth - label.length()));
    }

    private static void appendSpaces(SpannableStringBuilder text, int count) {
        for (int index = 0; index < count; index++) {
            text.append(' ');
        }
    }

    private static void appendUsage(SpannableStringBuilder text, Context context, String value) {
        appendRightAligned(text, value, context.getString(R.string.performance_percent, 100));
    }

    private static void appendNumber(SpannableStringBuilder text, String value) {
        if ("—".equals(value)) {
            appendRightAligned(text, value, "000");
            return;
        }
        char first = value.charAt(0);
        int digitsStart = first == '+' || first == '-' || first == '~' ? 1 : 0;
        int digitsEnd = digitsStart;
        while (digitsEnd < value.length() && Character.isDigit(value.charAt(digitsEnd))) {
            digitsEnd++;
        }
        if (digitsEnd == digitsStart) {
            text.append(value);
            return;
        }
        appendRightAligned(text, value.substring(0, digitsEnd), "000");
        text.append(value, digitsEnd, value.length());
    }

    private static void appendRightAligned(SpannableStringBuilder text, String value, String template) {
        int start = text.length();
        text.append(value);
        text.setSpan(new RightAlignedWidth(template), start, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void appendClock(SpannableStringBuilder text, Context context, int mhz) {
        text.append(mhz < 0 ? "—" : context.getString(R.string.performance_mhz, mhz));
    }

    private static final class RightAlignedWidth extends ReplacementSpan {
        private final String maximum;

        RightAlignedWidth(String maximum) {
            this.maximum = maximum;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            return (int) Math.ceil(Math.max(paint.measureText(maximum), paint.measureText(text, start, end)));
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                int bottom, Paint paint) {
            float gap = getSize(paint, text, start, end, null) - paint.measureText(text, start, end);
            canvas.drawText(text, start, end, x + gap, y, paint);
        }
    }

    private static void appendCpuCores(SpannableStringBuilder text, Context context, Bundle sample,
            boolean dark, int labelWidth) {
        int[] ids = sample.getIntArray(PerformanceStats.CPU_CORE_IDS);
        int[] loads = sample.getIntArray(PerformanceStats.CPU_CORE_LOADS);
        int[] clocks = sample.getIntArray(PerformanceStats.CPU_CORE_MHZ);
        if (ids == null || loads == null || clocks == null) {
            return;
        }
        for (int index = 0; index < ids.length; index++) {
            appendLabel(text, context.getString(R.string.performance_cpu_core, ids[index]),
                    PerformanceMetric.CPU, false, dark, labelWidth);
            String load = loads[index] < 0 ? "—" : context.getString(R.string.performance_percent, loads[index]);
            appendUsage(text, context, load);
            text.append("  ");
            appendClock(text, context, clocks[index]);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

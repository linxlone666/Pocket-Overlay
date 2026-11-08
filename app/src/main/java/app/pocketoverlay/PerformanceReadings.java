package app.pocketoverlay;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;

final class PerformanceReadings {
    private final Context context;
    private final SharedPreferences settings;
    private final ActivityManager activity;
    private final DisplayManager displays;
    private final BatteryManager batteryManager;
    private static final int MIN_ESTIMATE_MICROAMPS = 50_000;
    private static final long CURRENT_SMOOTHING_MS = 30_000;
    private double averageCurrent;
    private long lastCurrentSample;
    private int lastBatteryStatus;
    private final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();

    PerformanceReadings(Context context) {
        this.context = context;
        settings = OverlaySettings.open(context);
        activity = context.getSystemService(ActivityManager.class);
        displays = context.getSystemService(DisplayManager.class);
        batteryManager = context.getSystemService(BatteryManager.class);
    }

    String value(PerformanceMetric metric, Intent battery, Bundle sample) {
        if (metric == PerformanceMetric.MEMORY) {
            activity.getMemoryInfo(memory);
            if (memory.totalMem <= 0) {
                return "—";
            }
            long used = Math.max(0, memory.totalMem - memory.availMem);
            double gib = 1024.0 * 1024 * 1024;
            switch (OverlaySettings.ramFormat(settings)) {
                case OverlaySettings.RAM_USED_ONLY:
                    return context.getString(R.string.performance_ram_used_gib, used / gib);
                case OverlaySettings.RAM_USED_TOTAL:
                    return context.getString(R.string.performance_ram_gib, used / gib, memory.totalMem / gib);
                default:
                    return context.getString(R.string.performance_percent, Math.round(100f * used / memory.totalMem));
            }
        }
        if (metric == PerformanceMetric.BATTERY_TIME) {
            return batteryTime(battery);
        }
        if (metric == PerformanceMetric.FAN) {
            return fanValue(sample);
        }
        float value = reading(metric, battery, sample);
        if (!Float.isFinite(value)) {
            return "—";
        }
        switch (metric) {
            case POWER:
                return context.getString(R.string.performance_watts, value);
            case CPU_TEMPERATURE:
            case GPU_TEMPERATURE:
            case BATTERY_TEMPERATURE:
                return context.getString(R.string.performance_degrees, value);
            case REFRESH_RATE:
                return context.getString(R.string.performance_hertz, Math.round(value));
            case FPS:
                return Integer.toString(Math.round(value));
            default:
                return context.getString(R.string.performance_percent, Math.round(value));
        }
    }

    private String fanValue(Bundle sample) {
        float duty = sample.getFloat(Integer.toString(PerformanceMetric.FAN.bit), Float.NaN);
        float rpm = sample.getFloat(PerformanceStats.FAN_RPM, Float.NaN);
        String percentage = Float.isFinite(duty)
                ? context.getString(R.string.performance_percent, Math.round(duty)) : "—";
        String speed = Float.isFinite(rpm)
                ? context.getString(R.string.performance_rpm, Math.round(rpm)) : "—";
        switch (OverlaySettings.fanFormat(settings)) {
            case OverlaySettings.FAN_RPM_ONLY:
                return speed;
            case OverlaySettings.FAN_PERCENTAGE_AND_RPM:
                return Float.isFinite(duty) || Float.isFinite(rpm)
                        ? context.getString(R.string.performance_fan_combined, percentage, speed) : "—";
            default:
                return percentage;
        }
    }

    private float reading(PerformanceMetric metric, Intent battery, Bundle sample) {
        switch (metric) {
            case REFRESH_RATE:
                Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
                return display == null ? Float.NaN : display.getMode().getRefreshRate();
            case BATTERY:
                int level = extra(battery, BatteryManager.EXTRA_LEVEL);
                int scale = extra(battery, BatteryManager.EXTRA_SCALE);
                return level >= 0 && scale > 0 ? 100f * level / scale : Float.NaN;
            case BATTERY_TEMPERATURE:
                int temperature = extra(battery, BatteryManager.EXTRA_TEMPERATURE);
                return temperature == Integer.MIN_VALUE ? Float.NaN : temperature / 10f;
            case POWER:
                int voltage = extra(battery, BatteryManager.EXTRA_VOLTAGE);
                int current = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                return voltage > 0 && current != Integer.MIN_VALUE ? voltage * (current / 1_000_000_000f) : Float.NaN;
            default:
                return sample.getFloat(Integer.toString(metric.bit), Float.NaN);
        }
    }

    private String batteryTime(Intent battery) {
        int status = extra(battery, BatteryManager.EXTRA_STATUS);
        if (status == BatteryManager.BATTERY_STATUS_FULL) {
            lastCurrentSample = 0;
            return context.getString(R.string.performance_battery_full);
        }


        int current = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        int charge = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        if (current == Integer.MIN_VALUE || charge <= 0) {
            lastCurrentSample = 0;
            return "—";
        }
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastCurrentSample;
        if (lastCurrentSample == 0 || elapsed >= CURRENT_SMOOTHING_MS || status != lastBatteryStatus) {
            averageCurrent = current;
        } else {
            double weight = 1 - Math.exp(-elapsed / (double) CURRENT_SMOOTHING_MS);
            averageCurrent += weight * (current - averageCurrent);
        }
        lastCurrentSample = now;
        lastBatteryStatus = status;
        double rate = Math.abs(averageCurrent);
        if (rate < MIN_ESTIMATE_MICROAMPS) {
            return "—";
        }

        boolean charging = averageCurrent > 0;
        double capacity = charge;
        if (charging) {
            int level = extra(battery, BatteryManager.EXTRA_LEVEL);
            int scale = extra(battery, BatteryManager.EXTRA_SCALE);
            if (level <= 0 || scale <= 0 || level > scale) {
                return "—";
            }
            if (level == scale) {
                return context.getString(R.string.performance_battery_full);
            }
            capacity = charge * ((scale - level) / (double) level);
        }
        return formatBatteryTime(Math.max(1, Math.round(60 * capacity / rate)), charging);
    }

    private String formatBatteryTime(long minutes, boolean charging) {
        return context.getString(charging ? R.string.performance_charge_time : R.string.performance_runtime,
                minutes / 60, minutes % 60);
    }

    private static int extra(Intent battery, String name) {
        return battery == null ? Integer.MIN_VALUE : battery.getIntExtra(name, Integer.MIN_VALUE);
    }
}

package app.pocketoverlay;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import static app.pocketoverlay.PerformanceMetric.*;

enum PerformanceGroup {
    FRAME(FPS, null),
    PROCESSOR(CPU, CPU_TEMPERATURE),
    GRAPHICS(GPU, GPU_TEMPERATURE),
    CHARGE(BATTERY, BATTERY_TEMPERATURE),
    RAM(MEMORY, null),
    DISPLAY(REFRESH_RATE, null),
    COOLING(FAN, null);

    final PerformanceMetric primary;
    final PerformanceMetric secondary;

    PerformanceGroup(PerformanceMetric primary, PerformanceMetric secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    static List<PerformanceGroup> order(SharedPreferences settings) {
        List<PerformanceGroup> groups = new ArrayList<>();
        for (String name : settings.getString(OverlaySettings.PERFORMANCE_ORDER, "").split(",")) {
            for (PerformanceGroup group : values()) {
                if (group.name().equals(name) && !groups.contains(group)) {
                    groups.add(group);
                }
            }
        }
        for (PerformanceGroup group : values()) {
            if (!groups.contains(group)) {
                groups.add(group);
            }
        }
        return groups;
    }

    static void save(SharedPreferences settings, List<PerformanceGroup> groups) {
        List<String> names = new ArrayList<>();
        for (PerformanceGroup group : groups) {
            names.add(group.name());
        }
        settings.edit().putString(OverlaySettings.PERFORMANCE_ORDER, String.join(",", names)).apply();
    }
}

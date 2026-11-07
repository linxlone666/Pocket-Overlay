package app.pocketoverlay;

import static app.pocketoverlay.PerformanceMetric.BATTERY;
import static app.pocketoverlay.PerformanceMetric.CPU;
import static app.pocketoverlay.PerformanceMetric.FPS;
import static app.pocketoverlay.PerformanceMetric.GPU;

enum PerformancePreset {
    COMPACT(R.string.preset_compact, R.string.preset_compact_detail, FPS.bit | BATTERY.bit),
    GAMING(R.string.preset_gaming, R.string.preset_gaming_detail,
            FPS.bit | CPU.bit | GPU.bit | BATTERY.bit),
    DETAILED(R.string.preset_detailed, R.string.preset_detailed_detail, PerformanceMetric.DEFAULT | PerformanceMetric.BATTERY_TIME.bit | PerformanceMetric.FAN.bit);

    final int title;
    final int description;
    final int metrics;

    PerformancePreset(int title, int description, int metrics) {
        this.title = title;
        this.description = description;
        this.metrics = metrics;
    }
}

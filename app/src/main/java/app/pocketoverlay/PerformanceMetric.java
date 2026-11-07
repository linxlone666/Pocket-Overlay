package app.pocketoverlay;

enum PerformanceMetric {
    FPS(1, R.string.metric_fps),
    CPU(2, R.string.metric_cpu),
    CPU_TEMPERATURE(8, R.string.metric_cpu_temperature),
    GPU(4, R.string.metric_gpu),
    GPU_TEMPERATURE(16, R.string.metric_gpu_temperature),
    BATTERY(32, R.string.metric_battery),
    BATTERY_TEMPERATURE(64, R.string.metric_battery_temperature),
    POWER(128, R.string.metric_power),
    MEMORY(256, R.string.metric_memory),
    REFRESH_RATE(512, R.string.metric_refresh),
    BATTERY_TIME(1024, R.string.metric_battery_time),
    FAN(PerformanceStats.FAN_PERCENT, R.string.metric_fan);

    static final int SAMPLED = PerformanceStats.FPS | PerformanceStats.CPU | PerformanceStats.GPU
            | PerformanceStats.CPU_TEMP | PerformanceStats.GPU_TEMP | PerformanceStats.FAN_PERCENT;
    static final int DEFAULT = 255;
    final int bit;
    final int title;

    PerformanceMetric(int bit, int title) {
        this.bit = bit;
        this.title = title;
    }

    boolean selected(int metrics) {
        return (metrics & bit) != 0;
    }
}

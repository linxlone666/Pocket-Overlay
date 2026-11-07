package app.pocketoverlay;

import android.os.Bundle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads counters without changing device settings. */
final class PerformanceStats {
    static final int FPS = 1;
    static final int CPU = 2;
    static final int GPU = 4;
    static final int CPU_TEMP = 8;
    static final int GPU_TEMP = 16;
    static final int FAN_PERCENT = 2048;
    private static final File FAN_DUTY = new File("/sys/class/gpio5_pwm2/duty");
    private static final File FAN_PERIOD = new File("/sys/class/gpio5_pwm2/period");
    private static final File FAN_STATE = new File("/sys/class/gpio5_pwm2/state");
    private final List<File> cpuSensors = new ArrayList<>();
    private final List<File> gpuSensors = new ArrayList<>();
    private boolean sensorsFound;
    private long cpuTotal;
    private long cpuIdle;
    private long cpuTime;
    private final RetroidFrameRate frameRate = new RetroidFrameRate();

    Bundle sample(int metrics) {
        Bundle result = new Bundle();
        if ((metrics & CPU) != 0) {
            putReading(result, CPU, cpuUsage());
        }
        if ((metrics & GPU) != 0) {
            putReading(result, GPU, gpuUsage());
        }
        if ((metrics & (CPU_TEMP | GPU_TEMP)) != 0 && !sensorsFound) {
            findTemperatureSensors();
        }
        if ((metrics & CPU_TEMP) != 0) {
            putReading(result, CPU_TEMP, temperature(cpuSensors));
        }
        if ((metrics & GPU_TEMP) != 0) {
            putReading(result, GPU_TEMP, temperature(gpuSensors));
        }
        if ((metrics & FPS) != 0) {
            putReading(result, FPS, frameRate.read());
        }
        if ((metrics & FAN_PERCENT) != 0) {
            putReading(result, FAN_PERCENT, fanPercent());
        }
        return result;
    }

    private static float fanPercent() {
        try {
            if ("0".equals(read(FAN_STATE))) {
                return 0;
            }
            long duty = Long.parseLong(read(FAN_DUTY));
            long period = Long.parseLong(read(FAN_PERIOD));
            return duty >= 0 && duty <= period ? percentage(duty, period) : Float.NaN;
        } catch (IOException | NumberFormatException ignored) {
            return Float.NaN;
        }
    }

    private static void putReading(Bundle result, int metric, float reading) {
        if (Float.isFinite(reading)) {
            result.putFloat(Integer.toString(metric), reading);
        }
    }

    private float cpuUsage() {
        try {
            String[] fields = read(new File("/proc/stat")).split("\\s+");
            if (fields.length < 9 || !"cpu".equals(fields[0])) {
                return Float.NaN;
            }
            long total = 0;
            for (int index = 1; index <= 8; index++) {
                total += Long.parseLong(fields[index]);
            }
            long idle = Long.parseLong(fields[4]) + Long.parseLong(fields[5]);
            long now = System.nanoTime();
            long elapsed = total - cpuTotal;
            float value = cpuTime > 0 && now - cpuTime < 5_000_000_000L && elapsed > 0
                    ? percentage(elapsed - (idle - cpuIdle), elapsed) : Float.NaN;
            cpuTime = now;
            cpuTotal = total;
            cpuIdle = idle;
            return value;
        } catch (IOException | NumberFormatException ignored) {
            return Float.NaN;
        }
    }

    private static float gpuUsage() {
        try {
            String[] fields = read(new File("/sys/class/kgsl/kgsl-3d0/gpubusy")).split("\\s+");
            if (fields.length >= 2) {
                long busy = Long.parseLong(fields[0]);
                long total = Long.parseLong(fields[1]);
                return busy == 0 && total == 0 ? 0 : percentage(busy, total);
            }
        } catch (IOException | NumberFormatException ignored) {
            // Not every Android device exposes a KGSL counter.
        }
        return Float.NaN;
    }

    private static float percentage(long busy, long total) {
        return total > 0 ? Math.max(0, Math.min(100, 100f * busy / total)) : Float.NaN;
    }

    private void findTemperatureSensors() {
        sensorsFound = true;
        File[] zones = new File("/sys/class/thermal").listFiles(
                (dir, name) -> name.startsWith("thermal_zone"));
        if (zones == null) {
            return;
        }
        for (File zone : zones) {
            try {
                String name = read(new File(zone, "type")).toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith("cpu")) {
                    cpuSensors.add(new File(zone, "temp"));
                } else if (name.startsWith("gpu")) {
                    gpuSensors.add(new File(zone, "temp"));
                }
            } catch (IOException ignored) {
                // An individual sensor can be inaccessible.
            }
        }
    }

    private static float temperature(List<File> sensors) {
        float hottest = Float.NEGATIVE_INFINITY;
        for (File sensor : sensors) {
            try {
                float celsius = Integer.parseInt(read(sensor)) / 1000f;
                if (celsius >= 0 && celsius <= 150) {
                    hottest = Math.max(hottest, celsius);
                }
            } catch (IOException | NumberFormatException ignored) {
                // Skip unavailable or invalid sensors.
            }
        }
        return hottest;
    }

    private static String read(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        }
    }
}

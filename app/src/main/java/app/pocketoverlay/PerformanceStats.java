package app.pocketoverlay;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reads counters without changing device settings. */
final class PerformanceStats {
    static final int FPS = 1;
    static final int CPU = 2;
    static final int GPU = 4;
    static final int CPU_TEMP = 8;
    static final int GPU_TEMP = 16;
    static final int FAN_PERCENT = 2048;
    static final String FAN_RPM = "fan_rpm";
    static final String CPU_CORE_IDS = "cpu_core_ids";
    static final String CPU_CORE_LOADS = "cpu_core_loads";
    static final String CPU_CORE_MHZ = "cpu_core_mhz";
    static final String GPU_MHZ = "gpu_mhz";
    private static final File PROC_STAT = new File("/proc/stat");
    private static final File GPU_CLOCK = new File("/sys/class/kgsl/kgsl-3d0/gpuclk");
    private static final File FAN_DUTY = new File("/sys/class/gpio5_pwm2/duty");
    private static final File FAN_PERIOD = new File("/sys/class/gpio5_pwm2/period");
    private static final File FAN_SPEED = new File("/sys/class/gpio5_pwm2/speed");
    private static final File FAN_STATE = new File("/sys/class/gpio5_pwm2/state");
    private static final long FAN_RPM_INTERVAL_MS = 3_000;
    private static final long FAN_RPM_GRACE_MS = 6_000;
    private final List<File> cpuSensors = new ArrayList<>();
    private final List<File> gpuSensors = new ArrayList<>();
    private boolean sensorsFound;
    private CpuTicks cpuTicks;
    private final SparseArray<CpuTicks> coreTicks = new SparseArray<>();
    private Map<Integer, File> coreClocks;
    private final RetroidFrameRate frameRate = new RetroidFrameRate();
    private int lastFanRpm;
    private long lastFanRpmTime;
    private long nextFanRpmRead;

    Bundle sample(int metrics, boolean fullList, boolean readFanRpm) {
        Bundle result = new Bundle();
        if ((metrics & CPU) != 0) {
            sampleCpu(result, fullList);
        }
        if ((metrics & GPU) != 0) {
            putReading(result, GPU, gpuUsage());
            if (fullList) {
                result.putInt(GPU_MHZ, clockMhz(GPU_CLOCK, 1_000_000));
            }
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
            sampleFan(result, readFanRpm);
        }
        return result;
    }

    private void sampleFan(Bundle result, boolean readRpm) {
        try {
            if ("0".equals(read(FAN_STATE))) {
                putReading(result, FAN_PERCENT, 0);
                result.putFloat(FAN_RPM, 0);
                lastFanRpm = 0;
                nextFanRpmRead = 0;
                return;
            }
        } catch (IOException ignored) {
            // The speed counter may still be readable.
        }

        boolean zeroDuty = false;
        try {
            long duty = Long.parseLong(read(FAN_DUTY));
            long period = Long.parseLong(read(FAN_PERIOD));
            if (duty >= 0 && duty <= period) {
                putReading(result, FAN_PERCENT, percentage(duty, period));
                zeroDuty = duty == 0;
            }
        } catch (IOException | NumberFormatException ignored) {
            // RPM can still be available when the PWM counter is unreadable.
        }

        if (zeroDuty) {
            result.putFloat(FAN_RPM, 0);
            lastFanRpm = 0;
            nextFanRpmRead = 0;
            return;
        }
        if (!readRpm) {
            nextFanRpmRead = 0;
            return;
        }

        // The Retroid tachometer costs about 100 ms of kernel CPU time per read.
        long now = SystemClock.elapsedRealtime();
        if (now >= nextFanRpmRead) {
            nextFanRpmRead = now + FAN_RPM_INTERVAL_MS;
            try {
                int rpm = Integer.parseInt(read(FAN_SPEED));
                if (rpm > 0) {
                    lastFanRpm = rpm;
                    lastFanRpmTime = now;
                }
            } catch (IOException | NumberFormatException ignored) {
                // Some devices expose duty cycle only.
            }
        }
        if (lastFanRpm > 0 && now - lastFanRpmTime < FAN_RPM_GRACE_MS) {
            result.putFloat(FAN_RPM, lastFanRpm);
        }
    }

    private static void putReading(Bundle result, int metric, float reading) {
        if (Float.isFinite(reading)) {
            result.putFloat(Integer.toString(metric), reading);
        }
    }

    private void sampleCpu(Bundle result, boolean fullList) {
        SparseArray<CpuTicks> currentCores = new SparseArray<>();
        long now = System.nanoTime();
        try (BufferedReader reader = new BufferedReader(new FileReader(PROC_STAT))) {
            String first = reader.readLine();
            if (first != null && first.startsWith("cpu ")) {
                CpuTicks current = CpuTicks.parse(first, now);
                if (current != null) {
                    putReading(result, CPU, current.usageSince(cpuTicks));
                }
                cpuTicks = current;
            }
            if (fullList) {
                String line;
                while ((line = reader.readLine()) != null && line.startsWith("cpu")) {
                    int space = line.indexOf(' ');
                    if (space > 3) {
                        int id = Integer.parseInt(line.substring(3, space));
                        CpuTicks current = CpuTicks.parse(line, now);
                        if (current != null) {
                            currentCores.put(id, current);
                        }
                    }
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // Clock data can still be available when Android restricts /proc/stat.
        }

        if (fullList) {
            sampleCoreDetails(result, currentCores);
        } else {
            coreTicks.clear();
        }
    }

    private void sampleCoreDetails(Bundle result, SparseArray<CpuTicks> currentCores) {
        if (coreClocks == null) {
            coreClocks = new TreeMap<>();
            File[] cores = new File("/sys/devices/system/cpu").listFiles((dir, name) -> name.matches("cpu[0-9]+"));
            if (cores != null) {
                for (File core : cores) {
                    int id = Integer.parseInt(core.getName().substring(3));
                    coreClocks.put(id, new File(core, "cpufreq/scaling_cur_freq"));
                }
            }
        }
        // Keep cores visible even if the firmware only exposes one kind of counter.
        for (int index = 0; index < currentCores.size(); index++) {
            coreClocks.putIfAbsent(currentCores.keyAt(index), null);
        }
        int[] ids = new int[coreClocks.size()];
        int[] loads = new int[ids.length];
        int[] clocks = new int[ids.length];
        int index = 0;
        for (Map.Entry<Integer, File> core : coreClocks.entrySet()) {
            int id = core.getKey();
            CpuTicks current = currentCores.get(id);
            float usage = current == null ? Float.NaN : current.usageSince(coreTicks.get(id));
            ids[index] = id;
            loads[index] = Float.isFinite(usage) ? Math.round(usage) : -1;
            clocks[index] = core.getValue() == null ? -1 : clockMhz(core.getValue(), 1000);
            index++;
        }
        coreTicks.clear();
        for (int i = 0; i < currentCores.size(); i++) {
            coreTicks.put(currentCores.keyAt(i), currentCores.valueAt(i));
        }
        result.putIntArray(CPU_CORE_IDS, ids);
        result.putIntArray(CPU_CORE_LOADS, loads);
        result.putIntArray(CPU_CORE_MHZ, clocks);
    }

    private static int clockMhz(File file, int unitsPerMhz) {
        try {
            long frequency = Long.parseLong(read(file));
            return frequency > 0 ? Math.round(frequency / (float) unitsPerMhz) : -1;
        } catch (IOException | NumberFormatException ignored) {
            return -1;
        }
    }

    private static final class CpuTicks {
        final long total;
        final long idle;
        final long time;

        CpuTicks(long total, long idle, long time) {
            this.total = total;
            this.idle = idle;
            this.time = time;
        }

        static CpuTicks parse(String line, long now) {
            String[] fields = line.split("\\s+");
            if (fields.length < 9) {
                return null;
            }
            long total = 0;
            for (int index = 1; index <= 8; index++) {
                total += Long.parseLong(fields[index]);
            }
            long idle = Long.parseLong(fields[4]) + Long.parseLong(fields[5]);
            return new CpuTicks(total, idle, now);
        }

        float usageSince(CpuTicks previous) {
            if (previous == null || time - previous.time >= 5_000_000_000L) {
                return Float.NaN;
            }
            long elapsed = total - previous.total;
            return percentage(elapsed - (idle - previous.idle), elapsed);
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

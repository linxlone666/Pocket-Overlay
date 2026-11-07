package app.pocketoverlay;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.nio.charset.StandardCharsets;
import java.util.NavigableSet;
import java.util.TreeSet;

/** Reads foreground presentation timestamps through the stock firmware service. */
final class RetroidFrameRate {
    private static final String FOREGROUND =
            "dumpsys -t 1 activity activities 2>/dev/null | awk "
            + "'!found && /topResumedActivity=ActivityRecord/ {split($3,a,\"/\"); print a[1]; found=1}'";

    private IBinder service;
    private boolean checkedForService;
    private String packageName;
    private String layer;

    float read() {
        if (!checkedForService || (service != null && !service.isBinderAlive())) {
            service = findService();
            checkedForService = true;
        }
        if (service == null) {
            return Float.NaN;
        }
        String foreground = query(FOREGROUND);
        if (!foreground.matches("[A-Za-z0-9_.]+") || "com.android.systemui".equals(foreground)) {
            return Float.NaN;
        }
        if (!foreground.equals(packageName)) {
            packageName = foreground;
            layer = null;
        }
        if (layer == null) {
            layer = findLayer(foreground);
        }
        if (layer == null) {
            return Float.NaN;
        }

        // The firmware limits reply size. Return a short window of actual presentation timestamps.
        String timestamps = query("dumpsys -t 1 SurfaceFlinger --latency " + quote(layer)
                + " | awk '$2>0 && $2<9223372036854775807{print $2}' | tail -8 | xargs");
        float fps = calculateFps(timestamps);
        if (!Float.isFinite(fps)) {
            layer = null;
        }
        return fps;
    }

    private String findLayer(String foreground) {
        String matching = "dumpsys -t 1 SurfaceFlinger --list | grep -F " + quote(foreground + "/");
        String surface = query(matching + " | grep '^SurfaceView.*BLAST' | tail -1");
        if (!surface.isEmpty()) {
            return surface;
        }
        String window = query(matching + " | grep '^[^ ]*/' | tail -1");
        return window.isEmpty() ? null : window;
    }

    private static float calculateFps(String timestamps) {
        TreeSet<Long> presented = new TreeSet<>();
        for (String timestamp : timestamps.split("\\s+")) {
            try {
                long time = Long.parseLong(timestamp);
                if (time > 0 && time < Long.MAX_VALUE) {
                    presented.add(time);
                }
            } catch (NumberFormatException ignored) {
                // No frame data or a firmware diagnostic.
            }
        }
        if (presented.size() < 2) {
            return Float.NaN;
        }
        long newest = presented.last();
        long age = System.nanoTime() - newest;
        if (age < 0 || age > 1_500_000_000L) {
            return Float.NaN;
        }
        NavigableSet<Long> recent = presented.tailSet(newest - 1_000_000_000L, true);
        long span = newest - recent.first();
        return span > 0 ? (recent.size() - 1) * 1_000_000_000f / span : Float.NaN;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String query(String command) {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeStringArray(new String[]{command, "1"});
            if (service.transact(0, request, reply, 0)) {
                byte[] bytes = reply.createByteArray();
                if (bytes != null) {
                    return new String(bytes, StandardCharsets.UTF_8).trim();
                }
            }
        } catch (RemoteException | RuntimeException ignored) {
            // Other firmware can hide the service or use a different protocol.
        } finally {
            request.recycle();
            reply.recycle();
        }
        return "";
    }

    @SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
    private static IBinder findService() {
        try {
            Class<?> services = Class.forName("android.os.ServiceManager");
            return (IBinder) services.getDeclaredMethod("getService", String.class)
                    .invoke(null, "PServerBinder");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}

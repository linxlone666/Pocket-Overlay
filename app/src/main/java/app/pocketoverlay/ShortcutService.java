package app.pocketoverlay;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

public final class ShortcutService extends AccessibilityService {
    private static final int MAX_BRIGHTNESS = 255;
    private static final int BRIGHTNESS_STEP = 13;
    private static final int OVERLAY_DURATION_MS = 1800;
    private static final float OVERLAY_SCALE = 0.8f;
    private static final int VOLUME = AudioManager.STREAM_MUSIC;

    private final SparseArray<Set<Integer>> intercepted = new SparseArray<>();
    private final Set<Integer> selectHeld = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable dismiss = this::hideOverlay;
    private AudioManager audio;
    private WindowManager windows;
    private BatteryManager battery;
    private OverlayView panel;

    @Override
    protected void onServiceConnected() {
        audio = getSystemService(AudioManager.class);
        windows = getSystemService(WindowManager.class);
        battery = getSystemService(BatteryManager.class);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int device = event.getDeviceId();
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (key == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (down) {
                selectHeld.add(device);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                selectHeld.remove(device);
            }
            return false;
        }

        Set<Integer> handled = intercepted.get(device);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (handled == null || !handled.remove(key)) {
                return false;
            }
            if (handled.isEmpty()) {
                intercepted.remove(device);
            }
            return true;
        }
        if (!down) {
            return false;
        }
        if (event.getRepeatCount() != 0) {
            return handled != null && handled.contains(key);
        }
        if (!selectHeld.contains(device)) {
            return false;
        }

        boolean brightness;
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_R1:
                audio.adjustStreamVolume(VOLUME, AudioManager.ADJUST_RAISE, 0);
                brightness = false;
                break;
            case KeyEvent.KEYCODE_BUTTON_L1:
                audio.adjustStreamVolume(VOLUME, AudioManager.ADJUST_LOWER, 0);
                brightness = false;
                break;
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_L2:
                if (!changeBrightness(key == KeyEvent.KEYCODE_BUTTON_R2
                        ? BRIGHTNESS_STEP : -BRIGHTNESS_STEP)) {
                    return false;
                }
                brightness = true;
                break;
            default:
                return false;
        }
        if (handled == null) {
            handled = new HashSet<>();
            intercepted.put(device, handled);
        }
        handled.add(key);
        showOverlay(brightness);
        return true;
    }

    private boolean changeBrightness(int delta) {
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, R.string.brightness_permission_needed, Toast.LENGTH_SHORT).show();
            return false;
        }
        int brightness = Math.max(1, Math.min(MAX_BRIGHTNESS, getBrightness() + delta));
        return Settings.System.putInt(
                getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, brightness);
    }

    private int getBrightness() {
        return Settings.System.getInt(
                getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
    }

    private void showOverlay(boolean brightness) {
        int minVolume = audio.getStreamMinVolume(VOLUME);
        int volumeSteps = Math.max(1, audio.getStreamMaxVolume(VOLUME) - minVolume);
        int segments = brightness ? 20 : Math.min(20, volumeSteps);
        float level = brightness ? (float) getBrightness() / MAX_BRIGHTNESS
                : audio.isStreamMute(VOLUME) ? 0f
                : (float) (audio.getStreamVolume(VOLUME) - minVolume) / volumeSteps;
        String time = DateFormat.getTimeFormat(this).format(new Date());
        int batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean alreadyVisible = panel != null;
        if (!alreadyVisible) {
            panel = new OverlayView(this);
        }
        panel.update(brightness, level, segments, time, batteryPercent, battery.isCharging());

        float density = getResources().getDisplayMetrics().density;
        int margin = Math.round(16 * density);
        int available = Math.max(1, getResources().getDisplayMetrics().widthPixels - 2 * margin);
        int width = Math.min(available, Math.round(panel.designWidth() * density * OVERLAY_SCALE));
        int height = Math.max(1, Math.round(width * OverlayView.HEIGHT / panel.designWidth()));
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        layout.y = margin;
        if (alreadyVisible) {
            windows.updateViewLayout(panel, layout);
        } else {
            windows.addView(panel, layout);
        }
        handler.removeCallbacks(dismiss);
        handler.postDelayed(dismiss, OVERLAY_DURATION_MS);
    }

    private void hideOverlay() {
        if (panel != null) {
            windows.removeViewImmediate(panel);
            panel = null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(dismiss);
        hideOverlay();
    }

    @Override
    public void onDestroy() {
        onInterrupt();
        selectHeld.clear();
        intercepted.clear();
        super.onDestroy();
    }
}

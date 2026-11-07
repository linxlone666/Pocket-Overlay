package app.pocketoverlay;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.Date;

import app.pocketoverlay.OverlaySettings.Binding;

public final class ShortcutService extends AccessibilityService {
    private static final int MAX_BRIGHTNESS = 255;
    private static final int BRIGHTNESS_STEPS = 20;
    private static final int VOLUME = AudioManager.STREAM_MUSIC;

    private final SparseArray<SparseArray<Runnable>> intercepted = new SparseArray<>();
    private final SparseIntArray heldModifiers = new SparseIntArray();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable dismiss = this::slideOut;
    private AudioManager audio;
    private WindowManager windows;
    private BatteryManager battery;
    private PowerManager power;
    private SharedPreferences settings;
    private FrameLayout overlayWindow;
    private OverlayView panel;
    private boolean slidingOut;

    @Override
    protected void onServiceConnected() {
        audio = getSystemService(AudioManager.class);
        windows = getSystemService(WindowManager.class);
        battery = getSystemService(BatteryManager.class);
        power = getSystemService(PowerManager.class);
        settings = OverlaySettings.open(this);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int device = event.getDeviceId();
        int key = event.getKeyCode();
        SparseArray<Runnable> handled = intercepted.get(device);
        Runnable heldShortcut = handled == null ? null : handled.get(key);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int modifier = Binding.MODIFIER.key(settings);
        if (heldShortcut == null && (key == modifier || heldModifiers.get(device) == key)) {
            if (down && key == modifier && event.getRepeatCount() == 0 && !OverlaySettings.recordingButton) {
                heldModifiers.put(device, key);
            } else if (event.getAction() == KeyEvent.ACTION_UP && heldModifiers.get(device) == key) {
                heldModifiers.delete(device);
                if (heldModifiers.indexOfValue(key) < 0) {
                    for (int index = 0; index < intercepted.size(); index++) {
                        SparseArray<Runnable> shortcuts = intercepted.valueAt(index);
                        for (int button = 0; button < shortcuts.size(); button++) {
                            handler.removeCallbacks(shortcuts.valueAt(button));
                        }
                    }
                }
            }
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (heldShortcut == null) {
                return false;
            }
            handler.removeCallbacks(heldShortcut);
            handled.remove(key);
            if (handled.size() == 0) {
                intercepted.remove(device);
            }
            return true;
        }
        if (!down) {
            return false;
        }
        if (heldShortcut != null) {
            return true;
        }
        if (event.getRepeatCount() != 0 || OverlaySettings.recordingButton
                || heldModifiers.indexOfValue(modifier) < 0) {
            return false;
        }
        Binding action = Binding.forKey(settings, key);
        if (action == null || !applyShortcut(action)) {
            return false;
        }

        if (handled == null) {
            handled = new SparseArray<>();
            intercepted.put(device, handled);
        }
        Runnable repeat = new Runnable() {
            @Override
            public void run() {
                if (settings.getBoolean(OverlaySettings.REPEAT, true)
                        && !OverlaySettings.recordingButton && Binding.MODIFIER.key(settings) == modifier
                        && heldModifiers.indexOfValue(modifier) >= 0 && action.key(settings) == key
                        && power.isInteractive() && InputDevice.getDevice(device) != null
                        && applyShortcut(action)) {
                    handler.postDelayed(this, settings.getInt(
                            OverlaySettings.REPEAT_INTERVAL, OverlaySettings.DEFAULT_INTERVAL));
                }
            }
        };
        handled.put(key, repeat);
        if (settings.getBoolean(OverlaySettings.REPEAT, true)) {
            handler.postDelayed(repeat, settings.getInt(
                    OverlaySettings.REPEAT_DELAY, OverlaySettings.DEFAULT_DELAY));
        }
        return true;
    }

    private boolean applyShortcut(Binding action) {
        boolean brightness;
        switch (action) {
            case VOLUME_UP:
                audio.adjustStreamVolume(VOLUME, AudioManager.ADJUST_RAISE, 0);
                brightness = false;
                break;
            case VOLUME_DOWN:
                audio.adjustStreamVolume(VOLUME, AudioManager.ADJUST_LOWER, 0);
                brightness = false;
                break;
            case BRIGHTNESS_UP:
            case BRIGHTNESS_DOWN:
                if (!changeBrightness(action == Binding.BRIGHTNESS_UP ? 1 : -1)) {
                    return false;
                }
                brightness = true;
                break;
            default:
                return false;
        }
        showOverlay(brightness);
        return true;
    }

    private boolean changeBrightness(int direction) {
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, R.string.brightness_permission_needed, Toast.LENGTH_SHORT).show();
            return false;
        }
        int currentStep = Math.round((float) getBrightness() * BRIGHTNESS_STEPS / MAX_BRIGHTNESS);
        int nextStep = Math.max(0, Math.min(BRIGHTNESS_STEPS, currentStep + direction));
        int brightness = Math.max(1, Math.round((float) nextStep * MAX_BRIGHTNESS / BRIGHTNESS_STEPS));
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
        int segments = brightness ? BRIGHTNESS_STEPS : Math.min(20, volumeSteps);
        float level = brightness ? (float) getBrightness() / MAX_BRIGHTNESS
                : audio.isStreamMute(VOLUME) ? 0f
                : (float) (audio.getStreamVolume(VOLUME) - minVolume) / volumeSteps;
        String time = DateFormat.getTimeFormat(this).format(new Date());
        int batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean alreadyVisible = panel != null;
        if (!alreadyVisible) {
            overlayWindow = new FrameLayout(this);
            panel = new OverlayView(this);
        } else if (slidingOut) {
            panel.animate().cancel();
        }
        panel.applyTheme(settings.getInt(OverlaySettings.THEME, OverlaySettings.DARK));
        panel.update(brightness, level, segments, time, batteryPercent, battery.isCharging());

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float scale = (float) metrics.densityDpi / OverlayView.REFERENCE_DENSITY_DPI;
        int margin = Math.round(OverlayView.TOP_INSET * scale);
        int available = Math.max(1, metrics.widthPixels - 2 * margin);
        int size = settings.getInt(OverlaySettings.SIZE, OverlaySettings.DEFAULT_SIZE);
        int width = Math.max(1, Math.min(available, Math.round(panel.designWidth() * scale * size / 100f)));
        int height = Math.max(1, Math.round(width * OverlayView.HEIGHT / panel.designWidth()));
        FrameLayout.LayoutParams panelLayout = new FrameLayout.LayoutParams(width, height);
        panelLayout.topMargin = margin;
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                width, height + margin,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        boolean animate = settings.getBoolean(OverlaySettings.SLIDE, true);
        if (alreadyVisible) {
            panel.setLayoutParams(panelLayout);
            windows.updateViewLayout(overlayWindow, layout);
        } else {
            if (animate) {
                panel.setTranslationY(-layout.height);
            }
            overlayWindow.addView(panel, panelLayout);
            windows.addView(overlayWindow, layout);
        }
        int slideInTime = 0;
        if (!animate) {
            panel.animate().cancel();
            panel.setTranslationY(0);
        } else if (!alreadyVisible || slidingOut) {
            slideInTime = settings.getInt(OverlaySettings.SLIDE_IN, OverlaySettings.DEFAULT_SLIDE_IN);
            panel.animate().translationY(0).setDuration(slideInTime)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        slidingOut = false;
        handler.removeCallbacks(dismiss);
        handler.postDelayed(dismiss, slideInTime + settings.getInt(
                OverlaySettings.DISPLAY_TIME, OverlaySettings.DEFAULT_DISPLAY_TIME));
    }

    private void slideOut() {
        if (!settings.getBoolean(OverlaySettings.SLIDE, true)) {
            hideOverlay();
            return;
        }
        slidingOut = true;
        panel.animate().translationY(-overlayWindow.getHeight()).setDuration(settings.getInt(
                        OverlaySettings.SLIDE_OUT, OverlaySettings.DEFAULT_SLIDE_OUT))
                .setInterpolator(new AccelerateInterpolator()).withEndAction(this::hideOverlay).start();
    }

    private void hideOverlay() {
        if (panel != null) {
            panel.animate().cancel();
            windows.removeViewImmediate(overlayWindow);
            overlayWindow = null;
            panel = null;
            slidingOut = false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        hideOverlay();
    }

    @Override
    public void onDestroy() {
        onInterrupt();
        heldModifiers.clear();
        intercepted.clear();
        super.onDestroy();
    }
}

package app.pocketoverlay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

final class NavigationShortcuts {
    static final String RETROID_HOME_PROTECTION = "prevent_press_home_accidentally";
    static final String ENABLED = "navigation_shortcuts_enabled";
    static final String HOME_TAP = "navigation_home_tap";
    static final String HOME_DOUBLE = "navigation_home_double";
    static final String HOME_HOLD = "navigation_home_hold";
    static final String BACK_TAP = "navigation_back_tap";
    static final String BACK_DOUBLE = "navigation_back_double";
    static final String BACK_HOLD = "navigation_back_hold";

    enum Action {
        NONE(R.string.navigation_none),
        HOME(R.string.navigation_home),
        BACK(R.string.navigation_back),
        RECENTS(R.string.navigation_recents),
        MENU(R.string.navigation_menu),
        SCREENSHOT(R.string.navigation_screenshot),
        MUTE(R.string.navigation_mute),
        PERFORMANCE(R.string.performance_shortcut),
        CYCLE_LAYOUT(R.string.performance_cycle_shortcut),
        QUICK_SETTINGS(R.string.navigation_quick_settings),
        CLUSTER_TUNE(R.string.navigation_cluster_tune);

        final int label;

        Action(int label) {
            this.label = label;
        }

        static Action read(SharedPreferences settings, String key, Action fallback) {
            int value = settings.getInt(key, fallback.ordinal());
            Action[] actions = values();
            return value >= 0 && value < actions.length ? actions[value] : fallback;
        }
    }

    private final AccessibilityService service;
    private final SharedPreferences settings;
    private final Handler handler;
    private final PowerManager power;
    private final KeyguardManager keyguard;
    private final Gesture home = new Gesture();
    private final Gesture back = new Gesture();
    private final WindowManager windows;
    private final ClusterTuneControls clusterTuneControls;
    private final Runnable openRecentsPanel = this::openRecentsPanel;
    private final Set<Integer> consumedPanelKeys = new HashSet<>();
    private NavigationPanel panel;
    private String droidDeckPackage;
    private boolean recentsPanel;
    private RetroidModePicker modePicker;
    private StickLighting lighting;
    private boolean lightingOpen;
    private boolean applyingMode;
    private SeekBar fanSpeedSlider;
    private int pendingFanSpeed = -1;
    private boolean writingFanSpeed;
    private final Runnable saveFanSpeed = this::saveFanSpeed;
    private boolean sendingGesture;
    private boolean openingRecent;
    private Object clusterTuneRequest;
    private long recentGestureEndedAt;
    private final ArrayDeque<RecentInput> recentInputs = new ArrayDeque<>();
    private final Runnable processRecentInputs = this::processRecentInputs;

    private enum RecentInput { PREVIOUS, NEXT, OPEN, DISMISS }

    NavigationShortcuts(AccessibilityService service, SharedPreferences settings, Handler handler) {
        this.service = service;
        this.settings = settings;
        this.handler = handler;
        power = service.getSystemService(PowerManager.class);
        keyguard = service.getSystemService(KeyguardManager.class);
        windows = service.getSystemService(WindowManager.class);
        if ("Retroid Pocket 6".equals(Build.MODEL)) {
            lighting = StickLighting.acquire(service, settings, handler);
        }
        clusterTuneControls = new ClusterTuneControls(handler, this::screenSize,
                () -> ready() && panel == null, (x, y) -> gesture(x, y, x, y, 45));
    }

    void onWindowChanged(CharSequence packageName, CharSequence className) {
        if (packageName == null || className == null) {
            return;
        }
        String owner = packageName.toString();
        String windowClass = className.toString();
        // DroidDeck's compatibility builds keep the Activity namespace but use another app ID.
        if ("com.droiddeck.launcher".equals(owner) || "com.steamdeck.launcher".equals(owner)
                || windowClass.startsWith("com.droiddeck.launcher.")) {
            droidDeckPackage = owner;
            back.cancelPending();
        } else if (!owner.equals(droidDeckPackage)
                && !"com.android.systemui".equals(owner)
                && (!service.getPackageName().equals(owner)
                        || SetupActivity.class.getName().equals(windowClass))) {
            droidDeckPackage = null;
        }
    }

    boolean passesBackToDroidDeck(KeyEvent event) {
        return droidDeckPackage != null
                && (event.getScanCode() == 158 || event.getKeyCode() == KeyEvent.KEYCODE_BACK);
    }

    boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP && consumedPanelKeys.remove(key)) {
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0
                && consumedPanelKeys.contains(key)) {
            return true;
        }
        InputDevice device = event.getDevice();
        if (device == null || device.getVendorId() != 0x2022 || device.getProductId() != 0x3001
                || event.getScanCode() == 0) {
            return false;
        }
        // Stock RP6 firmware reports the physical Home scan code as MOVE_HOME.
        boolean isHome = event.getScanCode() == 102 || event.getKeyCode() == KeyEvent.KEYCODE_HOME;
        boolean isBack = event.getScanCode() == 158 || event.getKeyCode() == KeyEvent.KEYCODE_BACK;

        Gesture gesture = isHome ? home : back;
        if ((isHome || isBack) && gesture.down) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                gesture.release(event.isCanceled());
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && clusterTuneControls.handleKey(key, isBack)) {
            consumedPanelKeys.add(key);
            return true;
        }
        if (panel != null && handlePanelKey(event, isHome, isBack)) {
            consumedPanelKeys.add(key);
            return true;
        }
        if (passesBackToDroidDeck(event)) {
            return false;
        }
        if ((!isHome && !isBack) || !ready() || panel != null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        if (event.getRepeatCount() != 0) {
            return false;
        }
        Action tap = Action.read(settings, isHome ? HOME_TAP : BACK_TAP, isHome ? Action.HOME : Action.BACK);
        Action doubleTap = Action.read(settings, isHome ? HOME_DOUBLE : BACK_DOUBLE,
                isHome ? Action.RECENTS : Action.NONE);
        Action hold = Action.read(settings, isHome ? HOME_HOLD : BACK_HOLD, isHome ? Action.MENU : Action.NONE);
        if (!isHome && tap == Action.BACK && doubleTap == Action.NONE && hold == Action.NONE) {
            return false;
        }
        gesture.press(tap, doubleTap, hold);
        return true;
    }

    private boolean ready() {
        return settings.getBoolean(ENABLED, false) && power.isInteractive()
                && !keyguard.isKeyguardLocked() && !OverlaySettings.recordingButton;
    }

    void clear() {
        clusterTuneRequest = null;
        clusterTuneControls.stop();
        home.reset();
        back.reset();
        closePanel();
        if (lighting != null) {
            lighting.release();
            lighting = null;
        }
        consumedPanelKeys.clear();
    }

    private final class Gesture {
        private boolean down;
        private boolean held;
        private boolean waitingForSecond;
        private boolean second;
        private Action tap = Action.NONE;
        private Action doubleTap = Action.NONE;
        private Action hold = Action.NONE;
        private final Runnable finishTap = () -> {
            waitingForSecond = false;
            run(tap);
        };
        private final Runnable finishHold = () -> {
            held = true;
            run(hold);
        };

        void press(Action tap, Action doubleTap, Action hold) {
            handler.removeCallbacks(finishTap);
            second = waitingForSecond;
            waitingForSecond = false;
            down = true;
            held = false;
            this.tap = tap;
            this.doubleTap = doubleTap;
            this.hold = hold;
            if (hold != Action.NONE) {
                handler.postDelayed(finishHold, ViewConfiguration.getLongPressTimeout());
            }
        }

        void release(boolean canceled) {
            down = false;
            handler.removeCallbacks(finishHold);
            if (canceled || held || !ready()) {
                return;
            }
            if (second) {
                run(doubleTap);
            } else if (doubleTap == Action.NONE) {
                run(tap);
            } else {
                waitingForSecond = true;
                handler.postDelayed(finishTap, ViewConfiguration.getDoubleTapTimeout());
            }
        }

        void cancelPending() {
            handler.removeCallbacks(finishTap);
            handler.removeCallbacks(finishHold);
            waitingForSecond = false;
            held = down;
        }

        void reset() {
            cancelPending();
            down = false;
        }
    }

    private void run(Action action) {
        if (!ready()) {
            return;
        }
        clusterTuneRequest = null;
        switch (action) {
            case HOME:
                openHome();
                break;
            case BACK:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                break;
            case RECENTS:
                showRecents();
                break;
            case MENU:
                showQuickActions();
                break;
            case SCREENSHOT:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
                break;
            case MUTE:
                service.getSystemService(AudioManager.class).adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                break;
            case PERFORMANCE:
                settings.edit().putBoolean(OverlaySettings.PERFORMANCE_ENABLED,
                        !settings.getBoolean(OverlaySettings.PERFORMANCE_ENABLED, false)).apply();
                break;
            case CYCLE_LAYOUT:
                OverlaySettings.cyclePerformanceLayout(settings);
                break;
            case QUICK_SETTINGS:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                break;
            case CLUSTER_TUNE:
                openClusterTune();
                break;
            case NONE:
                break;
        }
    }

    private void openClusterTune() {
        Intent directPicker = new Intent("app.pocketoverlay.clustertune.OPEN_PROFILE_PICKER")
                .setPackage("app.pocketoverlay.clustertune")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            service.startActivity(directPicker);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Official ClusterTune exposes its picker through the edge handle.
        }

        Object request = new Object();
        clusterTuneRequest = request;
        Point display = screenSize();
        new Thread(() -> {
            Rect handle = ClusterTuneShortcut.findHandle();
            handler.post(() -> {
                if (clusterTuneRequest != request || !ready() || panel != null || !display.equals(screenSize())) {
                    return;
                }
                clusterTuneRequest = null;
                if (handle == null || !handle.intersect(0, 0, display.x, display.y)
                        || handle.left > dp(4) || handle.right > dp(40)) {
                    Toast.makeText(service, R.string.navigation_cluster_tune_missing, Toast.LENGTH_LONG).show();
                    return;
                }
                float y = handle.exactCenterY() / display.y;
                float x = handle.exactCenterX() / display.x;
                float end = Math.min(display.x - 1, handle.centerX() + dp(96)) / (float) display.x;
                if (gesture(x, y, end, y, 180)) {
                    clusterTuneControls.start(display);
                }
            });
        }, "cluster-tune-handle").start();
    }

    private void openHome() {
        Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            service.startActivity(launcher);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }
    }

    private void showQuickActions() {
        NavigationPanel quick = quickPanel(R.string.navigation_menu, R.string.navigation_quick_hint);
        modePicker = null;
        boolean pinned = settings.getBoolean(OverlaySettings.PERFORMANCE_LOCKED, false);
        quick.addActionRow(new int[]{R.string.navigation_key_x, R.string.navigation_key_a, R.string.navigation_key_l1},
                new int[]{R.string.navigation_quick_hud,
                        pinned ? R.string.navigation_quick_unpin : R.string.navigation_quick_pin,
                        R.string.navigation_quick_cycle},
                new Runnable[]{() -> selectQuick(Action.PERFORMANCE), this::toggleHudPin,
                        () -> selectQuick(Action.CYCLE_LAYOUT)});
        quick.addActionRow(new int[]{R.string.navigation_key_start, R.string.navigation_key_l2, R.string.navigation_key_r2},
                new int[]{R.string.navigation_fan_mode, R.string.navigation_performance_mode, R.string.navigation_quick_settings},
                new Runnable[]{() -> showModePicker(RetroidModePicker.FAN),
                        () -> showModePicker(RetroidModePicker.PERFORMANCE), () -> selectQuick(Action.QUICK_SETTINGS)});
        quick.addActionRow(new int[]{R.string.navigation_key_r1, R.string.navigation_key_y, R.string.navigation_key_select},
                new int[]{R.string.navigation_screenshot, R.string.navigation_quick_mute, R.string.navigation_stick_lighting},
                new Runnable[]{() -> selectQuick(Action.SCREENSHOT), () -> selectQuick(Action.MUTE), this::showStickLighting});
        presentQuickPanel(quick);
        quick.requestFocus();
    }

    private NavigationPanel quickPanel(int title, int detail) {
        lightingOpen = false;
        fanSpeedSlider = null;
        if (panel == null) {
            return new NavigationPanel(service, settings, title, detail);
        }
        panel.setContent(title, detail);
        return panel;
    }

    private void presentQuickPanel(NavigationPanel content) {
        if (panel == null) {
            content.setControls(this::closeOnOutsideTouch, null);
            showPanel(content, false);
        } else {
            content.animateContentChange();
        }
    }

    private static final int[] MODE_KEYS = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1};
    private static final int[] MODE_KEY_LABELS = {R.string.navigation_key_a, R.string.navigation_key_x,
            R.string.navigation_key_y, R.string.navigation_key_l1, R.string.navigation_key_r1};

    private static final int CUSTOM_FAN_MODE = 6;
    private static final String FAN_SPEED_SETTING = "fan_speed";

    private enum RetroidModePicker {
        FAN("fan_mode", R.string.navigation_fan_mode,
                new int[]{0, 1, 4, 5, CUSTOM_FAN_MODE},
                new int[]{R.string.navigation_fan_off, R.string.navigation_fan_quiet,
                        R.string.navigation_fan_smart, R.string.navigation_fan_sport,
                        R.string.navigation_fan_custom}),
        PERFORMANCE("performance_mode", R.string.navigation_performance_mode,
                new int[]{0, 1, 2},
                new int[]{R.string.navigation_performance_standard, R.string.navigation_performance_performance,
                        R.string.navigation_performance_high});

        final String setting;
        final int title;
        final int[] values;
        final int[] labels;

        RetroidModePicker(String setting, int title, int[] values, int[] labels) {
            this.setting = setting;
            this.title = title;
            this.values = values;
            this.labels = labels;
        }
    }

    private void toggleHudPin() {
        boolean pinned = settings.getBoolean(OverlaySettings.PERFORMANCE_LOCKED, false);
        settings.edit().putBoolean(OverlaySettings.PERFORMANCE_LOCKED, !pinned).apply();
        showQuickActions();
    }

    private void showModePicker(RetroidModePicker picker) {
        if (!"Retroid Pocket 6".equals(Build.MODEL)) {
            Toast.makeText(service, R.string.navigation_mode_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        int current = Settings.System.getInt(service.getContentResolver(), picker.setting, -1);
        updateModePicker(picker, current);
    }

    private void updateModePicker(RetroidModePicker picker, int current) {
        boolean customFan = picker == RetroidModePicker.FAN && current == CUSTOM_FAN_MODE;
        NavigationPanel choices = quickPanel(picker.title, customFan
                ? R.string.navigation_fan_custom_hint : R.string.navigation_mode_hint);
        for (int start = 0; start < picker.values.length; start += 3) {
            int count = Math.min(3, picker.values.length - start);
            int[] keys = new int[count];
            int[] labels = new int[count];
            Runnable[] actions = new Runnable[count];
            int selected = -1;
            for (int i = 0; i < count; i++) {
                int index = start + i;
                keys[i] = MODE_KEY_LABELS[index];
                labels[i] = picker.labels[index];
                actions[i] = () -> selectMode(picker, index);
                if (picker.values[index] == current) {
                    selected = i;
                }
            }
            choices.addChoiceRow(keys, labels, actions, selected);
        }
        if (customFan) {
            int speed = pendingFanSpeed >= 0 ? pendingFanSpeed : readFanSpeed();
            fanSpeedSlider = choices.addPercentSlider(R.string.navigation_fan_speed,
                    R.string.navigation_fan_speed_value, speed, this::queueFanSpeed);
        }
        presentQuickPanel(choices);
        modePicker = picker;
        if (fanSpeedSlider != null) {
            fanSpeedSlider.requestFocus();
        } else {
            choices.focusCurrentChoice();
        }
    }

    private void selectMode(RetroidModePicker picker, int index) {
        if (applyingMode) {
            return;
        }
        if (!Settings.System.canWrite(service)) {
            Toast.makeText(service, R.string.navigation_mode_permission, Toast.LENGTH_SHORT).show();
            return;
        }
        int value = picker.values[index];
        NavigationPanel source = panel;
        applyingMode = true;
        new Thread(() -> {
            writeRetroidSetting(picker.setting, value);
            // Retroid can adjust the requested mode after its settings observers run.
            handler.postDelayed(() -> {
                applyingMode = false;
                if (panel == source && modePicker == picker) {
                    int current = Settings.System.getInt(service.getContentResolver(), picker.setting, -1);
                    updateModePicker(picker, current);
                    if (current != value) {
                        Toast.makeText(service, R.string.navigation_mode_write_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            }, 250);
        }, "retroid-mode").start();
    }

    private int readFanSpeed() {
        int saved = Settings.System.getInt(service.getContentResolver(), FAN_SPEED_SETTING, 100);
        return Math.max(0, Math.min(100, saved));
    }

    private void queueFanSpeed(int speed) {
        pendingFanSpeed = speed;
        handler.removeCallbacks(saveFanSpeed);
        handler.postDelayed(saveFanSpeed, 180);
    }

    private void saveFanSpeed() {
        if (writingFanSpeed || pendingFanSpeed < 0) {
            return;
        }
        int speed = pendingFanSpeed;
        pendingFanSpeed = -1;
        if (!Settings.System.canWrite(service)) {
            if (fanSpeedSlider != null) {
                fanSpeedSlider.setProgress(readFanSpeed());
            }
            Toast.makeText(service, R.string.navigation_mode_permission, Toast.LENGTH_SHORT).show();
            return;
        }
        writingFanSpeed = true;
        new Thread(() -> {
            writeRetroidSetting(FAN_SPEED_SETTING, speed);
            handler.post(() -> {
                writingFanSpeed = false;
                if (pendingFanSpeed >= 0) {
                    handler.removeCallbacks(saveFanSpeed);
                    handler.post(saveFanSpeed);
                } else if (fanSpeedSlider != null) {
                    int saved = readFanSpeed();
                    fanSpeedSlider.setProgress(saved);
                    if (saved != speed) {
                        Toast.makeText(service, R.string.navigation_fan_speed_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }, "retroid-fan-speed").start();
    }

    private void writeRetroidSetting(String setting, int value) {
        try {
            if (Settings.System.putInt(service.getContentResolver(), setting, value)) {
                return;
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Retroid's settings are private to the firmware on some builds.
        }
        // Setting names are fixed firmware keys and values are integers.
        new RetroidFirmware().query("settings put system " + setting + " " + value);
    }

    private void showStickLighting() {
        if (!"Retroid Pocket 6".equals(Build.MODEL)) {
            Toast.makeText(service, R.string.navigation_mode_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        NavigationPanel lights = quickPanel(R.string.navigation_stick_lighting, R.string.navigation_lighting_hint);
        modePicker = null;
        if (lighting == null) lighting = StickLighting.acquire(service, settings, handler);
        lighting.refreshSettings();
        LightingColorWheel wheel = lighting.addControls(lights,
                service.getResources().getConfiguration().screenWidthDp >= 560);
        lightingOpen = true;
        presentQuickPanel(lights);
        wheel.requestFocus();
    }

    private void selectQuick(Action action) {
        if (action == Action.PERFORMANCE || action == Action.CYCLE_LAYOUT) {
            run(action);
            return;
        }
        closePanel();
        handler.postDelayed(() -> run(action), action == Action.SCREENSHOT ? 250 : 70);
    }

    private void showRecents() {
        closePanel();
        if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)) {
            handler.postDelayed(openRecentsPanel, 280);
        }
    }

    private void openRecentsPanel() {
        if (!ready() || panel != null) {
            return;
        }
        NavigationPanel recent = new NavigationPanel(service, settings, R.string.navigation_recents, 0);
        recent.setPadding(dp(12), dp(6), dp(12), dp(6));
        recent.addActionRow(new int[]{R.string.navigation_key_left, R.string.navigation_key_right, R.string.navigation_key_a},
                new int[]{R.string.navigation_previous, R.string.navigation_next, R.string.navigation_open_app},
                new Runnable[]{() -> browseRecents(-1), () -> browseRecents(1), this::openCenteredRecent});
        recent.addActionRow(new int[]{R.string.navigation_key_y, R.string.navigation_key_b},
                new int[]{R.string.navigation_dismiss_app, R.string.navigation_back_to_launcher},
                new Runnable[]{this::dismissCenteredRecent, this::exitRecentsToHome});
        recent.setControls(this::closeOnOutsideTouch, this::browseRecents);
        showPanel(recent, true);
    }

    private void showPanel(NavigationPanel next, boolean recents) {
        int margin = dp(8);
        int width = Math.min(screenSize().x - margin * 2, dp(recents ? 420 : 520));
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(width,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        layout.y = margin;
        if (UiMotion.enabled(service)) layout.windowAnimations = R.style.PocketPanelMotion;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            layout.setFitInsetsTypes(WindowInsets.Type.displayCutout());
        }
        windows.addView(next, layout);
        panel = next;
        recentsPanel = recents;
        next.requestFocus();
    }

    private void closeOnOutsideTouch() {
        if (!sendingGesture) {
            closePanel();
        }
    }

    private void closePanel() {
        handler.removeCallbacks(openRecentsPanel);
        handler.removeCallbacks(processRecentInputs);
        recentInputs.clear();
        recentGestureEndedAt = 0;
        openingRecent = false;
        if (panel != null) {
            windows.removeViewImmediate(panel);
            panel = null;
        }
        recentsPanel = false;
        modePicker = null;
        lightingOpen = false;
        fanSpeedSlider = null;
    }

    private boolean handlePanelKey(KeyEvent event, boolean isHome, boolean isBack) {
        int key = event.getKeyCode();
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        if (isBack || key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getRepeatCount() == 0) {
                if (recentsPanel) {
                    exitRecentsToHome();
                } else if (modePicker != null || lightingOpen) {
                    showQuickActions();
                } else {
                    closePanel();
                }
            }
            return true;
        }
        if (isHome) {
            if (recentsPanel && event.getRepeatCount() == 0) {
                exitRecentsToHome();
            }
            return true;
        }
        if (recentsPanel) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (event.getRepeatCount() == 0) {
                        browseRecents(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (event.getRepeatCount() == 0) {
                        openCenteredRecent();
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_Y:
                    if (event.getRepeatCount() == 0) {
                        dismissCenteredRecent();
                    }
                    return true;
                default:
                    return false;
            }
        }
        if (lightingOpen) {
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.getRepeatCount() == 0) {
                    panel.confirmChoice();
                }
                return true;
            }
            return false;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_SELECT && modePicker == null) {
            if (event.getRepeatCount() == 0) {
                showStickLighting();
            }
            return true;
        }
        if (modePicker != null) {
            for (int i = 0; i < modePicker.values.length; i++) {
                if (key == MODE_KEYS[i]) {
                    if (event.getRepeatCount() == 0) {
                        selectMode(modePicker, i);
                    }
                    return true;
                }
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.getRepeatCount() == 0) {
                    panel.confirmChoice();
                }
                return true;
            }
            return false;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getRepeatCount() == 0) {
                toggleHudPin();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_START || key == KeyEvent.KEYCODE_BUTTON_L2) {
            if (event.getRepeatCount() == 0) {
                showModePicker(key == KeyEvent.KEYCODE_BUTTON_START
                        ? RetroidModePicker.FAN : RetroidModePicker.PERFORMANCE);
            }
            return true;
        }
        Action action;
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_R1: action = Action.SCREENSHOT; break;
            case KeyEvent.KEYCODE_BUTTON_Y: action = Action.MUTE; break;
            case KeyEvent.KEYCODE_BUTTON_X: action = Action.PERFORMANCE; break;
            case KeyEvent.KEYCODE_BUTTON_L1: action = Action.CYCLE_LAYOUT; break;
            case KeyEvent.KEYCODE_BUTTON_R2: action = Action.QUICK_SETTINGS; break;
            default: return false;
        }
        if (event.getRepeatCount() == 0) {
            selectQuick(action);
        }
        return true;
    }

    private void exitRecentsToHome() {
        closePanel();
        if (ready() && !service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            openHome();
        }
    }

    private void openCenteredRecent() {
        queueRecentInput(RecentInput.OPEN);
    }

    private void dismissCenteredRecent() {
        queueRecentInput(RecentInput.DISMISS);
    }

    private void browseRecents(int direction) {
        queueRecentInput(direction < 0 ? RecentInput.PREVIOUS : RecentInput.NEXT);
    }

    private void queueRecentInput(RecentInput input) {
        if (recentsPanel && !openingRecent) {
            recentInputs.addLast(input);
            processRecentInputs();
        }
    }

    private void processRecentInputs() {
        handler.removeCallbacks(processRecentInputs);
        if (!recentsPanel || !ready() || openingRecent || sendingGesture || recentInputs.isEmpty()) {
            return;
        }
        RecentInput input = recentInputs.peekFirst();
        boolean browse = input == RecentInput.PREVIOUS || input == RecentInput.NEXT;
        // Quickstep can ignore a tap while its cards are still snapping into place.
        long earliest = recentGestureEndedAt + (browse ? 420 : 760);
        if (SystemClock.uptimeMillis() < earliest) {
            handler.postAtTime(processRecentInputs, earliest);
            return;
        }

        recentInputs.removeFirst();
        boolean sent;
        if (browse) {
            float offset = input == RecentInput.PREVIOUS ? -.14f : .14f;
            sent = gesture(.5f + offset, .48f, .5f - offset, .48f, 160);
        } else if (input == RecentInput.DISMISS) {
            sent = gesture(.5f, .48f, .5f, .1f, 200);
        } else {
            sent = gesture(.5f, .48f, .502f, .48f, 85);
        }
        if (!sent) {
            recentInputs.clear();
        } else if (input == RecentInput.OPEN) {
            openingRecent = true;
            recentInputs.clear();
        }
    }

    private void finishRecentGesture(NavigationPanel source, boolean completed) {
        sendingGesture = false;
        if (panel != source || !recentsPanel) {
            processRecentInputs();
        } else if (!completed) {
            openingRecent = false;
            recentInputs.clear();
        } else if (openingRecent) {
            // Keep focus through Quickstep's launch animation.
            handler.postDelayed(() -> {
                if (panel == source) {
                    closePanel();
                }
            }, 320);
        } else {
            recentGestureEndedAt = SystemClock.uptimeMillis();
            processRecentInputs();
        }
    }

    private boolean gesture(float x1, float y1, float x2, float y2, int duration) {
        if (!ready() || sendingGesture) {
            return false;
        }
        Point size = screenSize();
        Path path = new Path();
        path.moveTo(x1 * size.x, y1 * size.y);
        path.lineTo(x2 * size.x, y2 * size.y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        NavigationPanel source = panel;
        sendingGesture = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription description) {
                finishRecentGesture(source, true);
            }

            @Override
            public void onCancelled(GestureDescription description) {
                finishRecentGesture(source, false);
            }
        }, handler);
        return sendingGesture;
    }

    @SuppressWarnings("deprecation")
    private Point screenSize() {
        Point size = new Point();
        windows.getDefaultDisplay().getRealSize(size);
        return size;
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}

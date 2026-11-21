package app.pocketoverlay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dalvik.system.PathClassLoader;

/** Applies global button outputs through the stock Retroid mapping SDK. */
final class ControllerRemap {
    static final String ENABLED = "swap_face_buttons";
    private static final String NO_CONTROLLER = "no_create_gamepad_button_layout";
    private static final String FLIPPED = "flip_button_layout";
    private static final String INTERFACE = "com.ro.mapping.sdk.IServerApi";
    private static ControllerRemap shared;
    static final int DEFAULT = -1;
    private static final String OUTPUT_PREFIX = "controller_output_";

    enum Source {
        A("A", KeyEvent.KEYCODE_BUTTON_A), B("B", KeyEvent.KEYCODE_BUTTON_B), X("X", KeyEvent.KEYCODE_BUTTON_X), Y("Y", KeyEvent.KEYCODE_BUTTON_Y),
        L1("L1", KeyEvent.KEYCODE_BUTTON_L1), R1("R1", KeyEvent.KEYCODE_BUTTON_R1), L2("L2", KeyEvent.KEYCODE_BUTTON_L2), R2("R2", KeyEvent.KEYCODE_BUTTON_R2),
        L3("L3", KeyEvent.KEYCODE_BUTTON_THUMBL), R3("R3", KeyEvent.KEYCODE_BUTTON_THUMBR), SELECT("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT), START("START", KeyEvent.KEYCODE_BUTTON_START),
        UP("UP", KeyEvent.KEYCODE_DPAD_UP), DOWN("DOWN", KeyEvent.KEYCODE_DPAD_DOWN), LEFT("LEFT", KeyEvent.KEYCODE_DPAD_LEFT), RIGHT("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT),
        HOME("HOME", KeyEvent.KEYCODE_HOME), BACK("BACK", KeyEvent.KEYCODE_BACK), M1("M1", KeyEvent.KEYCODE_BUTTON_C), M2("M2", KeyEvent.KEYCODE_BUTTON_Z);

        final String input;
        final int key;

        Source(String input, int key) {
            this.input = input;
            this.key = key;
        }

        String input(boolean flipped) {
            if (!flipped) return input;
            switch (this) {
                case A: return "B";
                case B: return "A";
                case X: return "Y";
                case Y: return "X";
                default: return input;
            }
        }
    }

    static final int[] OUTPUTS = {
        DEFAULT,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z
    };

    enum Mode {
        RETRO(R.string.remap_mode_retro),
        XBOX(R.string.remap_mode_xbox),
        OFF(R.string.remap_mode_off);

        final int label;
        Mode(int label) {
            this.label = label;
        }
    }

    private final Context context;
    private final InputManager inputManager;
    private final InputManager.InputDeviceListener inputListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { notifyListeners(); }
        @Override public void onInputDeviceRemoved(int id) { notifyListeners(); }
        @Override public void onInputDeviceChanged(int id) { notifyListeners(); }
    };
    private final SharedPreferences settings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners = new HashSet<>();
    private final Runnable refresh = this::refresh;
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            if (hasMappings()) {
                handler.removeCallbacks(refresh);
                handler.postDelayed(refresh, 300);
            }
            notifyListeners();
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = binder;
            if (hasMappings()) refresh();
            notifyListeners();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            notifyListeners();
        }
    };
    private IBinder service;
    private ClassLoader firmware;
    private boolean bound;
    private boolean failed;
    private int users;

    static ControllerRemap acquire(Context context, SharedPreferences settings) {
        if (!"Retroid Pocket 6".equals(Build.MODEL)) return null;
        if (shared == null) shared = new ControllerRemap(context.getApplicationContext(), settings);
        shared.users++;
        return shared;
    }

    private ControllerRemap(Context context, SharedPreferences settings) {
        this.context = context;
        this.settings = settings;
        inputManager = context.getSystemService(InputManager.class);
        inputManager.registerInputDeviceListener(inputListener, handler);
        try {
            String apk = context.getPackageManager().getApplicationInfo("com.rp.mapping", 0).sourceDir;
            firmware = new PathClassLoader(apk, context.getClassLoader());
            Intent intent = new Intent("com.ro.mapping.action.MAPPING_SERVICE")
                    .setClassName("com.rp.mapping", "com.ro.mapping.service.ApiService");
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            failed = !bound;
            for (String key : new String[]{FLIPPED, NO_CONTROLLER,
                    "remap_custom_to_m1_value", "remap_custom_to_m2_value", "remap_custom_to_back_value"}) {
                context.getContentResolver().registerContentObserver(Settings.System.getUriFor(key), false, observer);
            }
        } catch (Exception | LinkageError error) {
            failed = true;
            Log.w("PocketRemap", "Stock remapping service unavailable", error);
        }
    }

    static boolean isBuiltInController(InputDevice device) {
        return device != null && device.getVendorId() == 0x2022
                && device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                && (device.getProductId() == 0x3001 || device.getProductId() == 0x3002);
    }

    Mode activeMode() {
        for (int id : inputManager.getInputDeviceIds()) {
            InputDevice device = inputManager.getInputDevice(id);
            if (isBuiltInController(device)) {
                return device.getProductId() == 0x3002 ? Mode.XBOX : Mode.RETRO;
            }
        }
        return Settings.System.getInt(context.getContentResolver(), NO_CONTROLLER, 0) != 0
                ? Mode.OFF : null;
    }

    boolean swapEnabled() { return settings.getBoolean(ENABLED, false); }
    boolean available() { return service != null && service.isBinderAlive() && !failed; }
    boolean failed() { return failed; }

    void addListener(Runnable listener) { listeners.add(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }

    boolean setSwapEnabled(boolean enabled) {
        if (!available()) return false;
        try {
            apply(enabled, null, DEFAULT);
            settings.edit().putBoolean(ENABLED, enabled).apply();
            notifyListeners();
            return true;
        } catch (Exception | LinkageError error) {
            failed = true;
            Log.w("PocketRemap", "Unable to apply face-button swap", error);
            notifyListeners();
            return false;
        }
    }

    private void refresh() {
        if (available()) {
            try {
                apply(swapEnabled(), null, DEFAULT);
                notifyListeners();
            } catch (Exception | LinkageError error) {
                failed = true;
                Log.w("PocketRemap", "Unable to restore button outputs", error);
                notifyListeners();
            }
        }
    }

    private boolean hasMappings() {
        if (swapEnabled()) return true;
        for (Source source : Source.values()) {
            if (output(source) != DEFAULT) return true;
        }
        return false;
    }

    int output(Source source) {
        int value = settings.getInt(OUTPUT_PREFIX + source.name(), DEFAULT);
        for (int supported : OUTPUTS) {
            if (supported == value) return value;
        }
        return DEFAULT;
    }

    int effectiveOutput(Source source) {
        int custom = output(source);
        return custom == DEFAULT ? layoutOutput(source, swapEnabled()) : custom;
    }

    int layoutOutput(Source source, boolean swap) {
        boolean flipped = Settings.System.getInt(context.getContentResolver(), FLIPPED, 0) != 0;
        switch (source) {
            case A: return flipped ^ swap ? KeyEvent.KEYCODE_BUTTON_B : KeyEvent.KEYCODE_BUTTON_A;
            case B: return flipped ^ swap ? KeyEvent.KEYCODE_BUTTON_A : KeyEvent.KEYCODE_BUTTON_B;
            case X: return flipped ^ swap ? KeyEvent.KEYCODE_BUTTON_Y : KeyEvent.KEYCODE_BUTTON_X;
            case Y: return flipped ^ swap ? KeyEvent.KEYCODE_BUTTON_X : KeyEvent.KEYCODE_BUTTON_Y;
            case M1: return Settings.System.getInt(context.getContentResolver(), "remap_custom_to_m1_value", KeyEvent.KEYCODE_BUTTON_C);
            case M2: return Settings.System.getInt(context.getContentResolver(), "remap_custom_to_m2_value", KeyEvent.KEYCODE_BUTTON_Z);
            case BACK: return Settings.System.getInt(context.getContentResolver(), "remap_custom_to_back_value", KeyEvent.KEYCODE_BACK);
            default: return source.key;
        }
    }

    boolean setOutput(Source source, int output) {
        if (!available()) return false;
        boolean valid = false;
        for (int supported : OUTPUTS) valid |= supported == output;
        if (!valid) return false;
        try {
            apply(swapEnabled(), source, output);
            SharedPreferences.Editor editor = settings.edit();
            if (output == DEFAULT) editor.remove(OUTPUT_PREFIX + source.name());
            else editor.putInt(OUTPUT_PREFIX + source.name(), output);
            editor.apply();
            notifyListeners();
            return true;
        } catch (Exception | LinkageError error) {
            failed = true;
            Log.w("PocketRemap", "Unable to remap button", error);
            notifyListeners();
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void apply(boolean swap, Source changed, int newOutput) throws Exception {
        boolean flipped = Settings.System.getInt(context.getContentResolver(), FLIPPED, 0) != 0;
        Class<?> configClass = firmware.loadClass("com.ro.mapping.sdk.config."
                + (flipped ? "FlippedGamepadConfig" : "StandardGamepadConfig"));
        Object config = configClass.getConstructor(int.class, int.class, int.class).newInstance(
                Settings.System.getInt(context.getContentResolver(), "remap_custom_to_m1_value", KeyEvent.KEYCODE_BUTTON_C),
                Settings.System.getInt(context.getContentResolver(), "remap_custom_to_m2_value", KeyEvent.KEYCODE_BUTTON_Z),
                Settings.System.getInt(context.getContentResolver(), "remap_custom_to_back_value", KeyEvent.KEYCODE_BACK));
        List<Object> keys = (List<Object>) configClass.getField("keyConfigs").get(config);
        Class<?> keyClass = firmware.loadClass("com.ro.mapping.base.config.KeyConfig");
        for (Source source : Source.values()) {
            int output = source == changed ? newOutput : output(source);
            boolean face = source == Source.A || source == Source.B || source == Source.X || source == Source.Y;
            if (output == DEFAULT && !(face && swap)) continue;
            if (output == DEFAULT) output = layoutOutput(source, swap);
            String input = source.input(flipped);
            // Replace all entries for this input, including the stock shoulder/rear mappings.
            for (int index = keys.size() - 1; index >= 0; index--) {
                if (input.equals(keyClass.getField("name").get(keys.get(index)))) keys.remove(index);
            }
            Object key = keyClass.getConstructor(String.class, int.class).newInstance(input, 2001);
            keyClass.getField("targetCode").setInt(key, output);
            keyClass.getField("hideFromUi").setBoolean(key, true);
            keys.add(key);
        }
        Parcel request = Parcel.obtain();
        try {
            request.writeInterfaceToken(INTERFACE);
            request.writeInt(1);
            ((Parcelable) config).writeToParcel(request, 0);
            // IServerApi.setFallbackGamepadConfig leaves the controller mode in place.
            if (!service.transact(6, request, null, IBinder.FLAG_ONEWAY)) {
                throw new IllegalStateException("Unsupported stock remapping API");
            }
        } finally {
            request.recycle();
        }
    }

    private void notifyListeners() {
        for (Runnable listener : new HashSet<>(listeners)) listener.run();
    }

    void release() {
        if (--users != 0) return;
        handler.removeCallbacks(refresh);
        inputManager.unregisterInputDeviceListener(inputListener);
        context.getContentResolver().unregisterContentObserver(observer);
        if (bound) context.unbindService(connection);
        listeners.clear();
        shared = null;
    }
}

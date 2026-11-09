package app.pocketoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.KeyEvent;

final class OverlaySettings {
    static final String SIZE = "size_percent";
    private static final String LEGACY_MODIFIER = "modifier_key";
    static final String OVERLAY_BATTERY_PERCENT = "volume_battery_percent";
    static final String OVERLAY_BATTERY_FILL = "overlay_battery_fill";
    static final String OVERLAY_CHARGING_INDICATOR = "overlay_charging_indicator";
    static final String SLIDE = "slide_animation";
    static final String SLIDE_IN = "slide_in_ms";
    static final String SLIDE_OUT = "slide_out_ms";
    static final String DISPLAY_TIME = "display_time_ms";
    static final String REPEAT = "hold_to_repeat";
    static final String REPEAT_DELAY = "repeat_delay_ms";
    static final String REPEAT_INTERVAL = "repeat_interval_ms";
    static final String THEME = "theme";
    static final String UI_MOTION = "ui_motion";
    static final String PERFORMANCE_ENABLED = "performance_enabled";
    static final String PERFORMANCE_POSITION = "performance_position";
    static final String PERFORMANCE_FAN_FORMAT = "performance_fan_format";
    private static final String LEGACY_FAN_RPM = "performance_fan_rpm";
    static final int FAN_PERCENTAGE = 0;
    static final int FAN_RPM_ONLY = 1;
    static final int FAN_PERCENTAGE_AND_RPM = 2;
    static final String PERFORMANCE_RAM_FORMAT = "performance_ram_format";
    private static final String LEGACY_RAM_AMOUNT = "performance_ram_amount";
    static final int RAM_PERCENTAGE = 0;
    static final int RAM_USED_TOTAL = 1;
    static final int RAM_USED_ONLY = 2;
    static final String PERFORMANCE_ORDER = "performance_order";
    static final String PERFORMANCE_OPACITY = "performance_opacity";
    static final String PERFORMANCE_SCALE = "performance_scale";
    static final int PERFORMANCE_DEFAULT_OPACITY = 85;
    static final int PERFORMANCE_MIN_SCALE = 65;
    static final int PERFORMANCE_MAX_SCALE = 160;
    static final String PERFORMANCE_LAYOUT = "performance_layout";
    static final String PERFORMANCE_X = "performance_x";
    static final String PERFORMANCE_Y = "performance_y";
    static final String PERFORMANCE_LOCKED = "performance_locked";
    static final int PERFORMANCE_INLINE = 0;
    static final int PERFORMANCE_LIST = 1;
    static final int PERFORMANCE_FULL_LIST = 2;
    static final String PERFORMANCE_METRICS = "performance_metrics";
    static final int PERFORMANCE_DEFAULT_POSITION = 3;
    static final int DARK = 0;
    static final int LIGHT = 1;
    static final int MATERIAL_YOU = 2;

    static final int DEFAULT_SIZE = 100;
    static final int DEFAULT_MODIFIER = KeyEvent.KEYCODE_BUTTON_SELECT;
    static final int DEFAULT_DELAY = 1000;
    static final int DEFAULT_INTERVAL = 150;
    static final int DEFAULT_SLIDE_IN = 220;
    static final int DEFAULT_SLIDE_OUT = 180;
    static final int DEFAULT_DISPLAY_TIME = 1800;

    enum Binding {
        VOLUME_UP("volume_up_key", KeyEvent.KEYCODE_BUTTON_R1, R.string.volume_up),
        VOLUME_DOWN("volume_down_key", KeyEvent.KEYCODE_BUTTON_L1, R.string.volume_down),
        BRIGHTNESS_UP("brightness_up_key", KeyEvent.KEYCODE_BUTTON_R2, R.string.brightness_up),
        BRIGHTNESS_DOWN("brightness_down_key", KeyEvent.KEYCODE_BUTTON_L2, R.string.brightness_down),
        PERFORMANCE("performance_toggle_key", KeyEvent.KEYCODE_UNKNOWN, R.string.performance_shortcut),
        PERFORMANCE_CYCLE("performance_cycle_key", KeyEvent.KEYCODE_UNKNOWN, R.string.performance_cycle_shortcut);

        private static final Binding[] ALL = values();
        final String preference;
        final int defaultKey;
        final int title;

        Binding(String preference, int defaultKey, int title) {
            this.preference = preference;
            this.defaultKey = defaultKey;
            this.title = title;
        }

        int key(SharedPreferences settings) {
            return settings.getInt(preference, defaultKey);
        }

        int firstKey(SharedPreferences settings) {
            if (key(settings) == KeyEvent.KEYCODE_UNKNOWN) {
                return KeyEvent.KEYCODE_UNKNOWN;
            }
            return settings.getInt(preference + "_first",
                    settings.getInt(LEGACY_MODIFIER, DEFAULT_MODIFIER));
        }

        void save(SharedPreferences settings, int first, int key) {
            settings.edit().putInt(preference + "_first", first).putInt(preference, key).apply();
        }

        boolean isPerformanceShortcut() {
            return this == PERFORMANCE || this == PERFORMANCE_CYCLE;
        }

        static Binding forKeys(SharedPreferences settings, int first, int key) {
            if (key == KeyEvent.KEYCODE_UNKNOWN) {
                return null;
            }
            for (Binding binding : ALL) {
                if (binding.key(settings) == key && binding.firstKey(settings) == first) {
                    return binding;
                }
            }
            return null;
        }

        static boolean startsCombo(SharedPreferences settings, int key) {
            if (key == KeyEvent.KEYCODE_UNKNOWN) {
                return false;
            }
            for (Binding binding : ALL) {
                if (binding.firstKey(settings) == key) {
                    return true;
                }
            }
            return false;
        }
    }

    // The activity and accessibility service share the same main thread.
    static boolean recordingButton;

    private OverlaySettings() {}

    static SharedPreferences open(Context context) {
        return context.getSharedPreferences("overlay_settings", Context.MODE_PRIVATE);
    }

    static void cyclePerformanceLayout(SharedPreferences settings) {
        int layout = settings.getInt(PERFORMANCE_LAYOUT, PERFORMANCE_LIST);
        int next = (layout + 1) % (PERFORMANCE_FULL_LIST + 1);
        settings.edit().putInt(PERFORMANCE_LAYOUT, next).apply();
    }

    static int fanFormat(SharedPreferences settings) {
        int previous = settings.getBoolean(LEGACY_FAN_RPM, false) ? FAN_RPM_ONLY : FAN_PERCENTAGE;
        return settings.getInt(PERFORMANCE_FAN_FORMAT, previous);
    }

    static int ramFormat(SharedPreferences settings) {
        int previous = settings.getBoolean(LEGACY_RAM_AMOUNT, false) ? RAM_USED_TOTAL : RAM_PERCENTAGE;
        return settings.getInt(PERFORMANCE_RAM_FORMAT, previous);
    }

    static boolean usesDarkColors(Context context, int theme) {
        int nightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return theme == DARK || (theme == MATERIAL_YOU && nightMode == Configuration.UI_MODE_NIGHT_YES);
    }
}

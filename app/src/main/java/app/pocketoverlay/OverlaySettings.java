package app.pocketoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.KeyEvent;

final class OverlaySettings {
    static final String SIZE = "size_percent";
    static final String MODIFIER = "modifier_key";
    static final String SLIDE = "slide_animation";
    static final String SLIDE_IN = "slide_in_ms";
    static final String SLIDE_OUT = "slide_out_ms";
    static final String DISPLAY_TIME = "display_time_ms";
    static final String REPEAT = "hold_to_repeat";
    static final String REPEAT_DELAY = "repeat_delay_ms";
    static final String REPEAT_INTERVAL = "repeat_interval_ms";
    static final String THEME = "theme";
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
        MODIFIER(OverlaySettings.MODIFIER, DEFAULT_MODIFIER, R.string.shortcut_button),
        VOLUME_UP("volume_up_key", KeyEvent.KEYCODE_BUTTON_R1, R.string.volume_up),
        VOLUME_DOWN("volume_down_key", KeyEvent.KEYCODE_BUTTON_L1, R.string.volume_down),
        BRIGHTNESS_UP("brightness_up_key", KeyEvent.KEYCODE_BUTTON_R2, R.string.brightness_up),
        BRIGHTNESS_DOWN("brightness_down_key", KeyEvent.KEYCODE_BUTTON_L2, R.string.brightness_down);

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

        static Binding forKey(SharedPreferences settings, int key) {
            for (Binding binding : ALL) {
                if (binding.key(settings) == key) {
                    return binding;
                }
            }
            return null;
        }
    }

    // The activity and accessibility service share the same main thread.
    static boolean recordingButton;

    private OverlaySettings() {}

    static SharedPreferences open(Context context) {
        return context.getSharedPreferences("overlay_settings", Context.MODE_PRIVATE);
    }

    static boolean usesDarkColors(Context context, int theme) {
        int nightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return theme == DARK || (theme == MATERIAL_YOU && nightMode == Configuration.UI_MODE_NIGHT_YES);
    }
}

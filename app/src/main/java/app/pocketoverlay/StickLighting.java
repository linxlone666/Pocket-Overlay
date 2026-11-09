package app.pocketoverlay;

import android.content.BroadcastReceiver;
import android.content.res.ColorStateList;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class StickLighting {
    private static final String ENABLED = "joystick_light_enabled";
    private static final String COLOR = "joystick_led_light_picker_color";
    private static final String BRIGHTNESS = "led_light_brightness_percent";
    private static final String EFFECT = "stick_lighting_effect";
    private static final String EFFECT_SPEED = "stick_lighting_effect_speed";
    private static final int MIN_EFFECT_SPEED = 50;
    private static final int MAX_EFFECT_SPEED = 200;
    private static final String ENABLED_SIDES = "stick_lighting_enabled_sides";
    private static final String LINK_COLORS = "stick_lighting_link_colors";
    private static StickLighting shared;
    private int users;
    private static final String LEFT = "/sys/class/sn3112l/led/";
    private static final String RIGHT = "/sys/class/sn3112r/led/";
    private static final long FRAME_MS = 100;
    private static final long SAVE_DELAY_MS = 300;

    private static final int[] PRESETS = {
            0xff2be0d8, 0xff388bff, 0xff9a55ff, 0xffff008c, 0xff57df63, 0xffffffff
    };
    private static final int[] PRESET_LABELS = {
            R.string.navigation_light_cyan, R.string.navigation_light_blue, R.string.navigation_light_violet,
            R.string.navigation_light_pink, R.string.navigation_light_green, R.string.navigation_light_white
    };
    private static final int[] EFFECT_LABELS = {
            R.string.navigation_light_solid, R.string.navigation_light_breathing,
            R.string.navigation_light_rainbow, R.string.navigation_light_pulse
    };

    private final Context context;
    private final ContentResolver resolver;
    private final SharedPreferences preferences;
    private final Handler handler;
    private final PowerManager power;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RetroidFirmware firmware = new RetroidFirmware();
    private final Runnable render = this::render;
    private final Set<Runnable> controls = new HashSet<>();
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(render);
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                if (effect != 0 || dirty) requestFrame();
            } else if (dirty) {
                handler.post(render);
            }
        }
    };

    private boolean linked;
    private boolean left;
    private boolean right;
    private int leftColor;
    private int rightColor;
    private int brightness;
    private int effect;
    private long effectStarted;
    private double elapsedBeforeSpeedChange;
    private int effectSpeed;
    private boolean dirty;
    private long saveAt;
    private boolean inFlight;
    private boolean closed;
    private boolean failed;
    private boolean frameRequested;
    private long lastFrameAt;
    // Only accessed by the worker.
    private boolean useFirmware;
    private String lastFrame;
    private String lastEnabled;

    static StickLighting acquire(Context context, SharedPreferences preferences, Handler handler) {
        if (shared == null) shared = new StickLighting(context.getApplicationContext(), preferences, handler);
        shared.users++;
        return shared;
    }

    void release() {
        if (--users == 0) {
            shared = null;
            close();
        }
    }

    private StickLighting(Context context, SharedPreferences preferences, Handler handler) {
        this.context = context;
        this.resolver = context.getContentResolver();
        this.preferences = preferences;
        this.handler = handler;
        power = context.getSystemService(PowerManager.class);
        readSettings();
        linked = preferences.getBoolean(LINK_COLORS, leftColor == rightColor);
        effect = Math.max(0, Math.min(EFFECT_LABELS.length - 1, preferences.getInt(EFFECT, 0)));
        effectStarted = SystemClock.uptimeMillis();
        effectSpeed = Math.max(MIN_EFFECT_SPEED, Math.min(MAX_EFFECT_SPEED, preferences.getInt(EFFECT_SPEED, 100)));
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(screen, filter);
        }
        if (effect != 0 && power.isInteractive()) {
            requestFrame();
        }
    }


    private void readSettings() {
        String enabled = Settings.System.getString(resolver, ENABLED);
        String[] sides = enabled == null ? new String[0] : enabled.split(",");
        left = sides.length > 0 && "1".equals(sides[0]);
        right = sides.length > 1 && "1".equals(sides[1]);
        String color = Settings.System.getString(resolver, COLOR);
        String[] colors = color == null ? new String[0] : color.split(",");
        leftColor = colors.length > 0 ? parseColor(colors[0]) : PRESETS[0];
        rightColor = colors.length > 1 ? parseColor(colors[1]) : leftColor;
        brightness = Math.max(0, Math.min(100,
                Math.round(Settings.System.getFloat(resolver, BRIGHTNESS, 0.5f) * 100)));
    }

    private static int parseColor(String value) {
        try {
            return Color.parseColor(value) | 0xff000000;
        } catch (IllegalArgumentException ignored) {
            return PRESETS[0];
        }
    }

    void refreshSettings() {
        if (!dirty && !inFlight) readSettings();
        refreshControls();
    }

    private Switch createPowerSwitch(Context ui) {
        Switch toggle = new Switch(ui);
        toggle.setContentDescription(ui.getString(R.string.navigation_stick_lighting));
        toggle.setFocusable(true);
        Runnable refresh = () -> toggle.setChecked(left || right);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked == (left || right)) return;
            if (!canChange()) {
                refresh.run();
                return;
            }
            if (checked) {
                int sides = preferences.getInt(ENABLED_SIDES, 3);
                left = (sides & 1) != 0;
                right = (sides & 2) != 0;
            } else {
                preferences.edit().putInt(ENABLED_SIDES, (left ? 1 : 0) | (right ? 2 : 0)).apply();
                left = false;
                right = false;
            }
            changed();
        });
        bindControls(toggle, refresh);
        return toggle;
    }

    LightingColorWheel addControls(NavigationPanel panel, boolean wide) {
        panel.setHeaderControl(createPowerSwitch(panel.getContext()));
        Context ui = panel.getContext();
        LinearLayout layout = new LinearLayout(ui);
        layout.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        layout.setPadding(0, dp(ui, 8), 0, 0);
        panel.addView(layout, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout palette = new LinearLayout(ui);
        palette.setOrientation(LinearLayout.VERTICAL);
        palette.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.addView(palette, wide ? new LinearLayout.LayoutParams(0, -2, 0.9f)
                : new LinearLayout.LayoutParams(-1, -2));
        NavigationPanel options = panel.controlColumn();
        if (wide) options.setPadding(dp(ui, 12), 0, 0, 0);
        layout.addView(options, wide ? new LinearLayout.LayoutParams(0, -2, 1.1f)
                : new LinearLayout.LayoutParams(-1, -2));

        boolean[] rightSide = {false};
        boolean[] updating = {false};
        LightingColorWheel wheel = new LightingColorWheel(ui, value -> setColor(rightSide[0], value));
        palette.addView(wheel, new LinearLayout.LayoutParams(-1, dp(ui, wide ? 186 : 152)));
        TextView preview = new TextView(ui);
        preview.setGravity(Gravity.CENTER);
        preview.setTextSize(13);
        LinearLayout.LayoutParams previewLayout = new LinearLayout.LayoutParams(dp(ui, 100), dp(ui, 26));
        previewLayout.bottomMargin = dp(ui, 6);
        palette.addView(preview, previewLayout);
        View[] swatches = addSwatches(palette, panel.foregroundColor(), rightSide);
        NavigationPanel speedControls = panel.controlColumn();
        palette.addView(speedControls, new LinearLayout.LayoutParams(-1, -2));
        SeekBar speed = speedControls.addSlider(R.string.navigation_lighting_speed,
                R.string.navigation_lighting_speed_value, MIN_EFFECT_SPEED, MAX_EFFECT_SPEED, 10, effectSpeed,
                percent -> {
                    if (!canChange()) {
                        refreshControls();
                        return;
                    }
                    long now = SystemClock.uptimeMillis();
                    elapsedBeforeSpeedChange = effectElapsed(now);
                    effectStarted = now;
                    effectSpeed = percent;
                    preferences.edit().putInt(EFFECT_SPEED, percent).apply();
                    refreshControls();
                    requestFrame();
                });
        speed.getLayoutParams().height = dp(ui, 34);

        Switch[] lights = options.addSwitchRow(
                new int[]{R.string.navigation_lighting_left, R.string.navigation_lighting_right},
                new boolean[]{left, right}, checked -> {
                    if (!updating[0]) {
                        if (canChange()) {
                            left = checked;
                            changed();
                        } else refreshControls();
                    }
                }, checked -> {
                    if (!updating[0]) {
                        if (canChange()) {
                            right = checked;
                            changed();
                        } else refreshControls();
                    }
                });
        Switch link = new Switch(ui);
        link.setText(R.string.navigation_lighting_link);
        link.setTextColor(panel.foregroundColor());
        link.setMinHeight(dp(ui, 36));
        options.addView(link, new LinearLayout.LayoutParams(-1, -2));
        link.setOnCheckedChangeListener((button, checked) -> {
            if (updating[0]) return;
            if (!canChange()) {
                refreshControls();
                return;
            }
            int color = selectedColor(rightSide[0]);
            linked = checked;
            preferences.edit().putBoolean(LINK_COLORS, linked).apply();
            if (linked) {
                leftColor = color;
                rightColor = color;
                changed();
            } else {
                refreshControls();
            }
        });

        LinearLayout targets = new LinearLayout(ui);
        targets.setOrientation(LinearLayout.VERTICAL);
        options.addView(targets);
        NavigationPanel targetButtons = panel.controlColumn();
        targets.addView(targetButtons, new LinearLayout.LayoutParams(-1, -2));
        View[] sides = addChoices(targetButtons,
                new int[]{R.string.navigation_lighting_left_color, R.string.navigation_lighting_right_color},
                new Runnable[]{() -> {
                    rightSide[0] = false;
                    refreshControls();
                }, () -> {
                    rightSide[0] = true;
                    refreshControls();
                }});

        SeekBar brightnessSlider = options.addPercentSlider(R.string.navigation_lighting_brightness,
                R.string.navigation_lighting_brightness_value, brightness, percent -> {
                    if (canChange()) {
                        brightness = percent;
                        changed();
                    } else refreshControls();
                });
        SeekBar saturation = options.addPercentSlider(R.string.navigation_lighting_saturation,
                R.string.navigation_lighting_saturation_value, 0, percent -> setHsv(rightSide[0], 1, percent));
        SeekBar shade = options.addPercentSlider(R.string.navigation_lighting_shade,
                R.string.navigation_lighting_shade_value, 100, percent -> setHsv(rightSide[0], 2, percent));

        Runnable[] effects = new Runnable[EFFECT_LABELS.length];
        for (int i = 0; i < effects.length; i++) {
            int index = i;
            effects[i] = () -> {
                if (!canChange()) return;
                effect = index;
                preferences.edit().putInt(EFFECT, index).apply();
                effectStarted = SystemClock.uptimeMillis();
                elapsedBeforeSpeedChange = 0;
                refreshControls();
                requestFrame();
            };
        }
        brightnessSlider.getLayoutParams().height = dp(ui, 34);
        saturation.getLayoutParams().height = dp(ui, 34);
        shade.getLayoutParams().height = dp(ui, 34);
        View[] effectTiles = addChoices(panel, EFFECT_LABELS, effects);
        Runnable refresh = () -> {
            updating[0] = true;
            lights[0].setChecked(left);
            lights[1].setChecked(right);
            link.setChecked(linked);
            targets.setVisibility(linked ? View.GONE : View.VISIBLE);
            select(sides, rightSide[0] ? 1 : 0);
            select(effectTiles, effect);
            speedControls.setVisibility(effect == 0 ? View.GONE : View.VISIBLE);
            speed.setProgress(effectSpeed);
            brightnessSlider.setProgress(brightness);
            int color = selectedColor(rightSide[0]);
            wheel.setColor(color);
            setPreview(preview, color);
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            saturation.setProgress(Math.round(hsv[1] * 100));
            shade.setProgress(Math.round(hsv[2] * 100));
            for (int i = 0; i < PRESETS.length; i++) swatches[i].setSelected(PRESETS[i] == color);
            updating[0] = false;
        };
        bindControls(layout, refresh);
        return wheel;
    }

    private void bindControls(View control, Runnable refresh) {
        refresh.run();
        control.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                controls.add(refresh);
                refresh.run();
            }

            @Override public void onViewDetachedFromWindow(View view) {
                controls.remove(refresh);
            }
        });
        if (control.isAttachedToWindow()) controls.add(refresh);
    }

    private View[] addSwatches(LinearLayout palette, int foreground, boolean[] rightSide) {
        Context ui = palette.getContext();
        LinearLayout row = new LinearLayout(ui);
        row.setGravity(Gravity.CENTER);
        palette.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View[] swatches = new View[PRESETS.length];
        for (int i = 0; i < PRESETS.length; i++) {
            int color = PRESETS[i];
            FrameLayout cell = new FrameLayout(ui);
            row.addView(cell, new LinearLayout.LayoutParams(0, dp(ui, 40), 1));
            View swatch = new View(ui);
            swatch.setFocusable(true);
            swatch.setContentDescription(ui.getString(PRESET_LABELS[i]));
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(color);
            circle.setStroke(dp(ui, 3), new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused},
                            new int[]{android.R.attr.state_selected}, new int[0]},
                    new int[]{foreground, foreground, Color.TRANSPARENT}));
            swatch.setBackground(circle);
            swatch.setOnClickListener(view -> setColor(rightSide[0], color));
            cell.addView(swatch, new FrameLayout.LayoutParams(dp(ui, 28), dp(ui, 28), Gravity.CENTER));
            swatches[i] = swatch;
        }
        return swatches;
    }

    private int selectedColor(boolean rightSide) {
        return !linked && rightSide ? rightColor : leftColor;
    }

    private void setHsv(boolean rightSide, int channel, int percent) {
        float[] hsv = new float[3];
        Color.colorToHSV(selectedColor(rightSide), hsv);
        hsv[channel] = percent / 100f;
        setColor(rightSide, Color.HSVToColor(hsv));
    }

    private void setColor(boolean rightSide, int color) {
        if (!canChange()) {
            refreshControls();
            return;
        }
        if (linked || !rightSide) leftColor = color;
        if (linked || rightSide) rightColor = color;
        changed();
    }

    private void refreshControls() {
        for (Runnable refresh : controls) refresh.run();
    }

    private static void setPreview(TextView preview, int color) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(dp(preview.getContext(), 13));
        preview.setBackground(fill);
        preview.setTextColor(Color.luminance(color) > 0.18 ? Color.BLACK : Color.WHITE);
        preview.setText(String.format(Locale.ROOT, "#%06X", color & 0xffffff));
    }

    private static View[] addChoices(NavigationPanel panel, int[] labels, Runnable[] actions) {
        View[] tiles = panel.addChoiceRow(new int[labels.length], labels, actions, -1);
        for (View tile : tiles) tile.setFocusableInTouchMode(false);
        return tiles;
    }

    private static void select(View[] choices, int index) {
        for (int i = 0; i < choices.length; i++) choices[i].setSelected(i == index);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private boolean canChange() {
        if (Settings.System.canWrite(context)) return true;
        Toast.makeText(context, R.string.navigation_mode_permission, Toast.LENGTH_SHORT).show();
        return false;
    }

    private void changed() {
        refreshControls();
        dirty = true;
        saveAt = SystemClock.uptimeMillis() + SAVE_DELAY_MS;
        requestFrame();
    }

    private void requestFrame() {
        failed = false;
        frameRequested = true;
        handler.removeCallbacks(render);
        handler.postAtTime(render, Math.max(SystemClock.uptimeMillis(), lastFrameAt + FRAME_MS));
    }

    private void render() {
        if (closed || inFlight || failed) return;
        boolean interactive = power.isInteractive();
        if (!interactive && !dirty) return;
        if (!Settings.System.canWrite(context)) return;
        long now = SystemClock.uptimeMillis();
        frameRequested = false;
        lastFrameAt = now;
        boolean save = dirty && (!interactive || now >= saveAt);
        if (!dirty && effect != 0) readSettings();
        if (save) dirty = false;
        boolean animate = interactive && effect != 0 && (left || right) && brightness > 0;
        Frame frame = frame(now);
        inFlight = true;
        worker.execute(() -> {
            boolean saved = !save || saveSettings(frame);
            boolean shown = !interactive || writeFrame(frame);
            handler.post(() -> {
                inFlight = false;
                if (closed) return;
                if (!shown || !saved) {
                    failed = true;
                    Toast.makeText(context, R.string.navigation_lighting_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (frameRequested || dirty || animate) {
                    handler.removeCallbacks(render);
                    handler.postDelayed(render, FRAME_MS);
                }
            });
        });
    }

    private double effectElapsed(long now) {
        return elapsedBeforeSpeedChange + (now - effectStarted) * (effectSpeed / 100_000.0);
    }

    private Frame frame(long now) {
        double elapsed = effectElapsed(now);
        float level = brightness / 100f;
        int animatedLeft = leftColor;
        int animatedRight = rightColor;
        switch (effect) {
            case 1:
                level *= (float) (0.12 + 0.88 * (1 - Math.cos(elapsed * Math.PI / 2)) / 2);
                break;
            case 2:
                float hue = (float) (elapsed * 60 % 360);
                animatedLeft = Color.HSVToColor(new float[]{hue, 1, 1});
                animatedRight = Color.HSVToColor(new float[]{(hue + 60) % 360, 1, 1});
                break;
            case 3:
                level *= (float) Math.max(0.08, 1 - (elapsed % 1.6) * 2.5);
                break;
            default:
                break;
        }
        return new Frame(left, right, leftColor, rightColor, brightness, animatedLeft, animatedRight, level);
    }

    private void close() {
        closed = true;
        handler.removeCallbacks(render);
        context.unregisterReceiver(screen);
        Frame steady = new Frame(left, right, leftColor, rightColor, brightness,
                leftColor, rightColor, brightness / 100f);
        boolean save = dirty && Settings.System.canWrite(context);
        boolean restore = effect != 0 && power.isInteractive() && Settings.System.canWrite(context);
        if (save || restore) {
            worker.execute(() -> {
                if (save) saveSettings(steady);
                if (restore) writeFrame(steady);
            });
        }
        worker.shutdown();
    }

    private boolean saveSettings(Frame frame) {
        return writeSetting(COLOR, hex(frame.baseLeft) + "," + hex(frame.baseRight))
                & writeSetting(BRIGHTNESS, Float.toString(frame.brightness / 100f))
                & writeSetting(ENABLED, (frame.left ? "1" : "0") + "," + (frame.right ? "1" : "0"));
    }

    private boolean writeSetting(String key, String value) {
        if (value.equalsIgnoreCase(Settings.System.getString(resolver, key))) return true;
        try {
            if (Settings.System.putString(resolver, key, value)) return true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Retroid can reserve these settings for SystemUI.
        }
        firmware.query("settings put system " + key + " '" + value + "'");
        return value.equalsIgnoreCase(Settings.System.getString(resolver, key));
    }

    private boolean writeFrame(Frame frame) {
        String leftRgb = rgb(frame.animatedLeft, frame.level);
        String rightRgb = rgb(frame.animatedRight, frame.level);
        String leftEnabled = frame.left ? "1" : "0";
        String rightEnabled = frame.right ? "1" : "0";
        String enabled = leftEnabled + rightEnabled;
        boolean updateEnabled = !enabled.equals(lastEnabled);
        String signature = leftRgb + "," + rightRgb + "," + enabled;
        if (signature.equals(lastFrame)) return true;
        if (!useFirmware) {
            try {
                writeNode(LEFT + "brightness", leftRgb);
                writeNode(RIGHT + "brightness", rightRgb);
                if (updateEnabled) {
                    writeNode(LEFT + "enable", leftEnabled);
                    writeNode(RIGHT + "enable", rightEnabled);
                }
            } catch (IOException | SecurityException ignored) {
                useFirmware = true;
            }
        }
        if (useFirmware) {
            String result = firmware.query("printf %s " + leftRgb + " > " + LEFT + "brightness"
                    + " && printf %s " + rightRgb + " > " + RIGHT + "brightness"
                    + (updateEnabled ? " && printf %s " + leftEnabled + " > " + LEFT + "enable"
                            + " && printf %s " + rightEnabled + " > " + RIGHT + "enable" : "")
                    + " && printf ok");
            if (!"ok".equals(result)) return false;
        }
        lastFrame = signature;
        lastEnabled = enabled;
        return true;
    }

    private static void writeNode(String path, String value) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(path)) {
            stream.write(value.getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static String rgb(int color, float level) {
        return "1-" + (int) (Color.red(color) * level) + ":" + (int) (Color.green(color) * level)
                + ":" + (int) (Color.blue(color) * level);
    }

    private static String hex(int color) {
        return String.format(Locale.ROOT, "#%08x", color);
    }

    private static final class Frame {
        final boolean left;
        final boolean right;
        final int baseLeft;
        final int baseRight;
        final int brightness;
        final int animatedLeft;
        final int animatedRight;
        final float level;

        Frame(boolean left, boolean right, int baseLeft, int baseRight, int brightness,
                int animatedLeft, int animatedRight, float level) {
            this.left = left;
            this.right = right;
            this.baseLeft = baseLeft;
            this.baseRight = baseRight;
            this.brightness = brightness;
            this.animatedLeft = animatedLeft;
            this.animatedRight = animatedRight;
            this.level = level;
        }
    }
}

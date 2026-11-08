package app.pocketoverlay;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

import app.pocketoverlay.OverlaySettings.Binding;

public final class SetupActivity extends Activity {
    static boolean visible;
    private static final String SELECTED_PAGE = "selected_page";
    private enum Page { OVERLAY, PERFORMANCE, SETTINGS, NAVIGATION }
    private int background, sidebarColor, surface, accent, textColor, muted, selection;
    private SharedPreferences settings;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView overlayTab;
    private TextView performanceTab;
    private TextView navigationTab;
    private Button homeProtection;
    private TextView settingsTab;
    private Page page;
    private boolean darkTheme;
    private AlertDialog recordingDialog;
    private final EnumMap<Binding, Button> bindingButtons = new EnumMap<>(Binding.class);
    private PermissionCard shortcutPermission;
    private PermissionCard brightnessPermission;
    private Button permissionsPrompt;
    private Switch performanceEnabled;
    private Button[] performanceLayouts;
    private Button performancePin;
    private PerformanceStatus performanceStatus;
    private final SharedPreferences.OnSharedPreferenceChangeListener performanceState = (prefs, key) -> {
        if (performanceStatus != null && key != null && key.startsWith("performance_")) {
            performanceStatus.render();
        }
        if (OverlaySettings.PERFORMANCE_LAYOUT.equals(key) && performanceLayouts != null) {
            int layout = prefs.getInt(key, OverlaySettings.PERFORMANCE_LIST);
            for (int i = 0; i < performanceLayouts.length; i++) {
                performanceLayouts[i].setSelected(i == layout);
            }
        }
        if (OverlaySettings.PERFORMANCE_LOCKED.equals(key) && performancePin != null) {
            performancePin.setSelected(prefs.getBoolean(key, false));
        }
        if (OverlaySettings.PERFORMANCE_ENABLED.equals(key) && performanceEnabled != null) {
            performanceEnabled.setChecked(prefs.getBoolean(key, false));
        }
    };

    private static final class PermissionCard {
        final TextView status;
        final Button action;

        PermissionCard(TextView status, Button action) {
            this.status = status;
            this.action = action;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        settings = OverlaySettings.open(this);
        applyTheme();
        super.onCreate(state);
        LinearLayout shell = new LinearLayout(this);
        shell.setBackgroundColor(background);
        shell.setBaselineAligned(false);
        boolean compact = getResources().getConfiguration().screenWidthDp < 520;
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        shell.addView(createSidebar(), new LinearLayout.LayoutParams(dp(compact ? 102 : 164), -1));
        shell.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(compact ? 14 : 24);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        showPage(state == null ? Page.OVERLAY : Page.values()[state.getInt(SELECTED_PAGE)]);
        setContentView(shell);
        settings.registerOnSharedPreferenceChangeListener(performanceState);
    }

    private void applyTheme() {
        int theme = settings.getInt(OverlaySettings.THEME, OverlaySettings.DARK);
        boolean materialYou = theme == OverlaySettings.MATERIAL_YOU;
        boolean dark = OverlaySettings.usesDarkColors(this, theme);
        darkTheme = dark;
        if (materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setTheme(dark ? R.style.PocketOverlayDynamicDarkTheme : R.style.PocketOverlayDynamicLightTheme);
            background = getColor(dark ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_10);
            sidebarColor = getColor(dark ? android.R.color.system_neutral2_800 : android.R.color.system_neutral2_100);
            surface = getColor(dark ? android.R.color.system_neutral1_800 : android.R.color.system_neutral1_50);
            accent = getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_700);
            textColor = getColor(dark ? android.R.color.system_neutral1_50 : android.R.color.system_neutral1_900);
            muted = getColor(dark ? android.R.color.system_neutral2_200 : android.R.color.system_neutral2_700);
            selection = getColor(dark ? android.R.color.system_accent2_700 : android.R.color.system_accent2_200);
        } else {
            setTheme(dark ? R.style.PocketOverlayTheme : R.style.PocketOverlayLightTheme);
            background = dark ? 0xff17151d : 0xfffbf7ff;
            sidebarColor = dark ? 0xff211e29 : 0xffeee7f4;
            surface = dark ? 0xff292532 : 0xfff2ebf7;
            accent = dark ? 0xffd7c6fc : 0xff5b4179;
            textColor = dark ? 0xfff4eefb : 0xff211929;
            muted = dark ? 0xffc0b5ca : 0xff5c5066;
            selection = dark ? 0xff3c304e : 0xffe8d9f9;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(SELECTED_PAGE, page.ordinal());
    }

    private void showPage(Page selected) {
        if (performanceStatus != null) {
            performanceStatus.close();
            performanceStatus = null;
        }
        page = selected;
        content.removeAllViews();
        bindingButtons.clear();
        performanceEnabled = null;
        performancePin = null;
        homeProtection = null;
        performanceLayouts = null;
        shortcutPermission = null;
        brightnessPermission = null;
        permissionsPrompt = null;
        overlayTab.setSelected(page == Page.OVERLAY);
        performanceTab.setSelected(page == Page.PERFORMANCE);
        navigationTab.setSelected(page == Page.NAVIGATION);
        settingsTab.setSelected(page == Page.SETTINGS);
        switch (page) {
            case OVERLAY: createOverlayPage(); break;
            case PERFORMANCE: createPerformancePage(); break;
            case SETTINGS: createSettingsPage(); break;
            case NAVIGATION: createNavigationPage(); break;
        }
        updatePermissions();
        scroll.scrollTo(0, 0);
    }

    private void createOverlayPage() {
        addText(R.string.overlay_page, 28, textColor, true, 0);
        addText(R.string.setup_intro, 14, muted, false, 4);
        permissionsPrompt = addControl();
        permissionsPrompt.setText(R.string.permissions_prompt);
        permissionsPrompt.setOnClickListener(view -> showPage(Page.SETTINGS));
        addText(R.string.preview_section, 16, accent, true, 20);
        LinearLayout previews = new LinearLayout(this);
        previews.setOrientation(LinearLayout.VERTICAL);
        previews.setPadding(dp(8), dp(8), dp(8), dp(8));
        previews.setBackground(rounded(sidebarColor, 14));
        addRow(previews, 8);
        Runnable volumePreview = addPreview(previews, false, 15, 0.53f);
        Runnable brightnessPreview = addPreview(previews, true, 20, 0.65f);

        addText(R.string.appearance_section, 16, accent, true, 20);
        Button batteryIcon = createControl();
        batteryIcon.setText(R.string.overlay_battery_icon);
        batteryIcon.setOnClickListener(view -> showBatteryIconOptions(() -> {
            volumePreview.run();
            brightnessPreview.run();
        }));
        addRow(batteryIcon, 6);
        Switch slide = addToggle(R.string.slide_animation, OverlaySettings.SLIDE);
        TileGrid appearance = new TileGrid();
        addChoice(appearance, R.string.overlay_size, OverlaySettings.SIZE, OverlaySettings.DEFAULT_SIZE,
                new int[]{75, 90, 100, 110, 125},
                getResources().getStringArray(R.array.overlay_sizes), () -> {
                    volumePreview.run();
                    brightnessPreview.run();
                });
        Button enter = addDuration(appearance, R.string.slide_in_time, OverlaySettings.SLIDE_IN,
                OverlaySettings.DEFAULT_SLIDE_IN, 0, 2000);
        Button exit = addDuration(appearance, R.string.slide_out_time, OverlaySettings.SLIDE_OUT,
                OverlaySettings.DEFAULT_SLIDE_OUT, 0, 2000);
        addDuration(appearance, R.string.display_time, OverlaySettings.DISPLAY_TIME,
                OverlaySettings.DEFAULT_DISPLAY_TIME, 250, 10000);
        enter.setEnabled(slide.isChecked());
        exit.setEnabled(slide.isChecked());
        slide.setOnCheckedChangeListener((button, checked) -> {
            settings.edit().putBoolean(OverlaySettings.SLIDE, checked).apply();
            enter.setEnabled(checked);
            exit.setEnabled(checked);
        });

        addText(R.string.shortcuts_section, 16, accent, true, 20);
        addText(R.string.binding_hint, 14, muted, false, 4);
        TileGrid bindings = new TileGrid();
        bindTile(addTile(bindings), Binding.VOLUME_UP);
        bindTile(addTile(bindings), Binding.VOLUME_DOWN);
        bindTile(addTile(bindings), Binding.BRIGHTNESS_UP);
        bindTile(addTile(bindings), Binding.BRIGHTNESS_DOWN);
        refreshBindings();
        Switch repeat = addToggle(R.string.hold_to_repeat, OverlaySettings.REPEAT);
        TileGrid timing = new TileGrid();
        Button delay = addChoice(timing, R.string.repeat_delay, OverlaySettings.REPEAT_DELAY,
                OverlaySettings.DEFAULT_DELAY, new int[]{500, 1000, 1500, 2000},
                getResources().getStringArray(R.array.repeat_delays), null);
        Button speed = addChoice(timing, R.string.repeat_speed, OverlaySettings.REPEAT_INTERVAL,
                OverlaySettings.DEFAULT_INTERVAL, new int[]{250, 150, 100},
                getResources().getStringArray(R.array.repeat_speeds), null);
        delay.setEnabled(repeat.isChecked());
        speed.setEnabled(repeat.isChecked());
        repeat.setOnCheckedChangeListener((button, checked) -> {
            settings.edit().putBoolean(OverlaySettings.REPEAT, checked).apply();
            delay.setEnabled(checked);
            speed.setEnabled(checked);
        });
    }

    private void createPerformancePage() {
        addText(R.string.performance_page, 28, textColor, true, 0);
        addText(R.string.performance_intro, 14, muted, false, 4);
        permissionsPrompt = addControl();
        permissionsPrompt.setText(R.string.permissions_prompt);
        permissionsPrompt.setOnClickListener(view -> showPage(Page.SETTINGS));
        performanceEnabled = addToggle(R.string.performance_enable, OverlaySettings.PERFORMANCE_ENABLED, false);

        LinearLayout choices = new LinearLayout(this);
        addRow(choices, 12);
        performancePin = performanceButton(R.string.performance_pin);
        performancePin.setSelected(settings.getBoolean(OverlaySettings.PERFORMANCE_LOCKED, false));
        performancePin.setOnClickListener(view -> settings.edit()
                .putBoolean(OverlaySettings.PERFORMANCE_LOCKED, !view.isSelected()).apply());
        addEqual(choices, performancePin);
        int[] labels = {R.string.performance_inline, R.string.performance_list, R.string.performance_full_list};
        performanceLayouts = new Button[labels.length];
        int current = settings.getInt(OverlaySettings.PERFORMANCE_LAYOUT, OverlaySettings.PERFORMANCE_LIST);
        for (int i = 0; i < labels.length; i++) {
            final int style = i;
            Button button = performanceButton(labels[i]);
            button.setSelected(current == style);
            button.setOnClickListener(view ->
                    settings.edit().putInt(OverlaySettings.PERFORMANCE_LAYOUT, style).apply());
            performanceLayouts[i] = button;
            addEqual(choices, button);
        }

        addText(R.string.performance_preview, 13, muted, true, 12);
        HorizontalScrollView previewFrame = new HorizontalScrollView(this);
        TextView preview = new TextView(this);
        PerformanceHud.setup(preview);
        preview.setPadding(dp(10), dp(6), dp(10), dp(6));
        previewFrame.addView(preview, new FrameLayout.LayoutParams(-2, -2));
        addRow(previewFrame, 5);
        previewFrame.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL) {
                previewFrame.post(() -> {
                    if (performanceStatus != null && previewFrame.isAttachedToWindow()) {
                        performanceStatus.render();
                    }
                });
            }
        });

        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout dashboard = new LinearLayout(this);
        dashboard.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        dashboard.setBaselineAligned(false);
        addRow(dashboard, 12);
        LinearLayout controls = performancePanel();
        LinearLayout status = performancePanel();
        if (wide) {
            addEqual(dashboard, controls);
            addEqual(dashboard, status);
        } else {
            dashboard.addView(controls);
            LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(-1, -2);
            statusLayout.topMargin = dp(8);
            dashboard.addView(status, statusLayout);
        }

        controls.addView(label(R.string.performance_metrics_section, 15, textColor, true));
        LinearLayout appearance = new LinearLayout(this);
        addEqual(appearance, performanceOption(R.string.performance_background, () -> showPerformanceSlider(true)));
        addEqual(appearance, performanceOption(R.string.performance_scale, () -> showPerformanceSlider(false)));
        LinearLayout.LayoutParams appearanceLayout = new LinearLayout.LayoutParams(-1, -2);
        appearanceLayout.topMargin = dp(6);
        controls.addView(appearance, appearanceLayout);
        LinearLayout arrangement = new LinearLayout(this);
        addEqual(arrangement, performanceOption(R.string.performance_order, this::showPerformanceOrder));
        addEqual(arrangement, performanceOption(R.string.performance_position, this::showPerformancePosition));
        LinearLayout.LayoutParams arrangementLayout = new LinearLayout.LayoutParams(-1, -2);
        arrangementLayout.topMargin = dp(6);
        controls.addView(arrangement, arrangementLayout);

        EnumMap<PerformanceMetric, CheckBox> toggles = new EnumMap<>(PerformanceMetric.class);
        EnumMap<PerformancePreset, View> presets = new EnumMap<>(PerformancePreset.class);
        int selected = settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT);
        LinearLayout row = null;
        int index = 0;
        PerformanceMetric[] metricOptions = {
                PerformanceMetric.FPS, PerformanceMetric.REFRESH_RATE,
                PerformanceMetric.CPU, PerformanceMetric.CPU_TEMPERATURE,
                PerformanceMetric.GPU, PerformanceMetric.GPU_TEMPERATURE,
                PerformanceMetric.MEMORY, PerformanceMetric.FAN,
                PerformanceMetric.BATTERY, PerformanceMetric.BATTERY_TEMPERATURE,
                PerformanceMetric.POWER, PerformanceMetric.BATTERY_TIME
        };
        for (PerformanceMetric metric : metricOptions) {
            if (index++ % 2 == 0) {
                row = new LinearLayout(this);
                controls.addView(row);
            }
            CheckBox toggle = new CheckBox(this);
            toggle.setText(metric.title);
            toggle.setTextColor(textColor);
            toggle.setTextSize(12);
            toggle.setMinHeight(dp(34));
            toggle.setMinimumHeight(dp(34));
            toggle.setChecked(metric.selected(selected));
            LinearLayout option = new LinearLayout(this);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.addView(toggle, new LinearLayout.LayoutParams(0, -2, 1));
            if (metric == PerformanceMetric.MEMORY) {
                addPerformanceGear(option, R.string.performance_ram_format, this::showRamFormat);
            } else if (metric == PerformanceMetric.FAN) {
                addPerformanceGear(option, R.string.performance_fan_format, this::showFanFormat);
            }
            addEqual(row, option);
            toggles.put(metric, toggle);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                int mask = settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT);
                int updated = checked ? mask | metric.bit : mask & ~metric.bit;
                if (updated != mask) {
                    settings.edit().putInt(OverlaySettings.PERFORMANCE_METRICS, updated).apply();
                    selectPerformancePreset(presets, updated);
                }
            });
        }
        createStatusCards(status, preview);

        addText(R.string.performance_presets, 16, accent, true, 16);
        LinearLayout presetRow = new LinearLayout(this);
        addRow(presetRow, 6);
        for (PerformancePreset preset : PerformancePreset.values()) {
            Button button = performanceButton(preset.title);
            addEqual(presetRow, button);
            presets.put(preset, button);
            button.setOnClickListener(view -> {
                settings.edit().putInt(OverlaySettings.PERFORMANCE_METRICS, preset.metrics).apply();
                toggles.forEach((metric, toggle) -> toggle.setChecked(metric.selected(preset.metrics)));
                selectPerformancePreset(presets, preset.metrics);
            });
        }
        selectPerformancePreset(presets, selected);

        addText(R.string.performance_drag_hint, 13, muted, false, 10);
        addText(R.string.shortcuts_section, 16, accent, true, 16);
        TileGrid shortcuts = new TileGrid();
        bindTile(addTile(shortcuts), Binding.PERFORMANCE);
        bindTile(addTile(shortcuts), Binding.PERFORMANCE_CYCLE);
        refreshBindings();
        addText(R.string.performance_shortcut_hint, 13, muted, false, 4);
    }

    private Button performanceButton(int title) {
        Button button = createControl();
        button.setText(title);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), dp(6), dp(6), dp(6));
        button.setBackground(controlBackground(true));
        return button;
    }

    private LinearLayout performancePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.setBackground(rounded(sidebarColor, 14));
        return panel;
    }

    private void addEqual(LinearLayout row, View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() != 0) {
            params.setMarginStart(dp(6));
        }
        row.addView(child, params);
    }

    private Button performanceOption(int title, Runnable onClick) {
        Button button = performanceButton(title);
        button.setOnClickListener(view -> onClick.run());
        return button;
    }

    private void addPerformanceGear(LinearLayout row, int title, Runnable onClick) {
        ImageButton gear = new ImageButton(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setColorFilter(accent);
        gear.setContentDescription(getText(title));
        gear.setBackground(controlBackground(true));
        gear.setOnClickListener(view -> onClick.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(40), dp(40));
        params.setMarginStart(dp(4));
        params.topMargin = dp(3);
        params.bottomMargin = dp(3);
        row.addView(gear, params);
    }

    private void showRamFormat() {
        int[] formats = {OverlaySettings.RAM_PERCENTAGE, OverlaySettings.RAM_USED_ONLY,
                OverlaySettings.RAM_USED_TOTAL};
        showPerformanceChoice(R.string.performance_ram_format, R.array.performance_ram_formats,
                choiceIndex(OverlaySettings.ramFormat(settings), formats),
                choice -> settings.edit().putInt(OverlaySettings.PERFORMANCE_RAM_FORMAT, formats[choice]).apply());
    }

    private void showFanFormat() {
        showPerformanceChoice(R.string.performance_fan_format, R.array.performance_fan_formats,
                OverlaySettings.fanFormat(settings),
                choice -> settings.edit().putInt(OverlaySettings.PERFORMANCE_FAN_FORMAT, choice).apply());
    }

    private void showPerformancePosition() {
        boolean dragged = settings.contains(OverlaySettings.PERFORMANCE_X) || settings.contains(OverlaySettings.PERFORMANCE_Y);
        int selected = dragged ? -1 : settings.getInt(OverlaySettings.PERFORMANCE_POSITION,
                OverlaySettings.PERFORMANCE_DEFAULT_POSITION);
        showPerformanceChoice(R.string.performance_position, R.array.performance_positions, selected,
                choice -> settings.edit().putInt(OverlaySettings.PERFORMANCE_POSITION, choice)
                        .remove(OverlaySettings.PERFORMANCE_X).remove(OverlaySettings.PERFORMANCE_Y).apply());
    }

    private void showPerformanceChoice(int title, int options, int selected, IntConsumer onChoice) {
        showPerformanceDialog(new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, selected, (dialog, choice) -> {
                    onChoice.accept(choice);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private AlertDialog showPerformanceDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(rounded(surface, 18));
        dialog.getWindow().getDecorView().setClipToOutline(true);
        dialog.show();
        return dialog;
    }

    private void createStatusCards(LinearLayout panel, TextView preview) {
        panel.addView(label(R.string.performance_live_status, 15, textColor, true));
        EnumMap<PerformanceMetric, TextView> values = new EnumMap<>(PerformanceMetric.class);
        PerformanceMetric[] displayed = {
                PerformanceMetric.FPS, PerformanceMetric.REFRESH_RATE,
                PerformanceMetric.CPU, PerformanceMetric.GPU,
                PerformanceMetric.CPU_TEMPERATURE, PerformanceMetric.GPU_TEMPERATURE,
                PerformanceMetric.MEMORY, PerformanceMetric.FAN,
                PerformanceMetric.BATTERY, PerformanceMetric.BATTERY_TEMPERATURE,
                PerformanceMetric.POWER, PerformanceMetric.BATTERY_TIME
        };
        LinearLayout row = null;
        for (int index = 0; index < displayed.length; index++) {
            if (index % 2 == 0) {
                row = new LinearLayout(this);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = dp(6);
                panel.addView(row, params);
            }
            PerformanceMetric metric = displayed[index];
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(9), dp(5), dp(9), dp(5));
            tile.setBackground(rounded(background, 8));
            tile.addView(label(metric.title, 12, PerformanceHud.color(metric, darkTheme), true));
            TextView value = new TextView(this);
            value.setText("—");
            value.setTextColor(textColor);
            value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            value.setTextSize(14);
            tile.addView(value);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
            if (index % 2 != 0) {
                params.setMarginStart(dp(6));
            }
            row.addView(tile, params);
            values.put(metric, value);
        }
        performanceStatus = new PerformanceStatus(this, values, preview);
        performanceStatus.setVisible(visible);
    }

    private void showPerformanceSlider(boolean opacity) {
        String key = opacity ? OverlaySettings.PERFORMANCE_OPACITY : OverlaySettings.PERFORMANCE_SCALE;
        int defaultValue = opacity ? OverlaySettings.PERFORMANCE_DEFAULT_OPACITY : 100;
        int min = opacity ? 0 : OverlaySettings.PERFORMANCE_MIN_SCALE;
        int max = opacity ? 100 : OverlaySettings.PERFORMANCE_MAX_SCALE;
        LinearLayout options = performancePanel();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = label(R.string.performance_preview, 13, muted, true);
        heading.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = new TextView(this);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setTextSize(15);
        value.setTextColor(accent);
        value.setPadding(dp(10), dp(5), dp(10), dp(5));
        value.setBackground(rounded(selection, 12));
        heading.addView(value);
        options.addView(heading);

        FrameLayout sample = new FrameLayout(this);
        sample.setPadding(dp(8), dp(8), dp(8), dp(8));
        sample.setBackground(rounded(background, 10));
        LinearLayout.LayoutParams sampleLayout = new LinearLayout.LayoutParams(-1, -2);
        sampleLayout.topMargin = dp(10);
        options.addView(sample, sampleLayout);
        TextView preview = new TextView(this);
        PerformanceHud.setup(preview);
        preview.setPadding(dp(10), dp(6), dp(10), dp(6));
        sample.addView(preview, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(getString(opacity ? R.string.performance_opacity : R.string.performance_scale));
        slider.setProgressTintList(ColorStateList.valueOf(accent));
        slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setMax(max - min);
        slider.setProgress(settings.getInt(key, defaultValue) - min);
        LinearLayout.LayoutParams sliderLayout = new LinearLayout.LayoutParams(-1, dp(46));
        sliderLayout.topMargin = dp(8);
        options.addView(slider, sliderLayout);
        LinearLayout limits = new LinearLayout(this);
        TextView low = percentLabel(min);
        limits.addView(low, new LinearLayout.LayoutParams(0, -2, 1));
        limits.addView(percentLabel(max));
        options.addView(limits);

        PerformanceStatus status = performanceStatus;
        Runnable update = () -> {
            int amount = min + slider.getProgress();
            value.setText(getString(R.string.performance_percent, amount));
            int width = sample.getWidth() - sample.getPaddingLeft() - sample.getPaddingRight();
            status.draw(preview, PerformanceMetric.CPU.bit | PerformanceMetric.BATTERY.bit,
                    OverlaySettings.PERFORMANCE_INLINE,
                    opacity ? settings.getInt(OverlaySettings.PERFORMANCE_SCALE, 100) : amount,
                    opacity ? amount : settings.getInt(OverlaySettings.PERFORMANCE_OPACITY,
                            OverlaySettings.PERFORMANCE_DEFAULT_OPACITY), width);
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { update.run(); }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        sample.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL) {
                sample.post(update);
            }
        });
        update.run();
        AlertDialog dialog = showPerformanceDialog(new AlertDialog.Builder(this)
                .setTitle(opacity ? R.string.performance_opacity : R.string.performance_scale)
                .setView(insetDialogContent(options))
                .setNeutralButton(R.string.performance_default, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, (window, which) ->
                        settings.edit().putInt(key, min + slider.getProgress()).apply()));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> slider.setProgress(defaultValue - min));
    }

    private TextView percentLabel(int percent) {
        TextView text = new TextView(this);
        text.setTextSize(12);
        text.setTextColor(muted);
        text.setText(getString(R.string.performance_percent, percent));
        return text;
    }

    private FrameLayout insetDialogContent(View child) {
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(18), dp(8), dp(18), dp(4));
        frame.addView(child, new FrameLayout.LayoutParams(-1, -2));
        return frame;
    }

    private void showPerformanceOrder() {
        List<PerformanceGroup> groups = PerformanceGroup.order(settings);
        ScrollView scroller = new ScrollView(this);
        LinearLayout options = performancePanel();
        TextView hint = label(R.string.performance_order_hint, 13, muted, false);
        options.addView(hint);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        options.addView(rows);
        scroller.addView(options);
        renderOrder(rows, groups);
        showPerformanceDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.performance_order)
                .setView(insetDialogContent(scroller))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.performance_reset_order, (dialog, which) ->
                        settings.edit().remove(OverlaySettings.PERFORMANCE_ORDER).apply())
                .setPositiveButton(R.string.save, (dialog, which) -> PerformanceGroup.save(settings, groups)));
    }

    private void renderOrder(LinearLayout rows, List<PerformanceGroup> groups) {
        rows.removeAllViews();
        for (int i = 0; i < groups.size(); i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            PerformanceGroup group = groups.get(i);
            row.setBackground(rounded(background, 8));
            row.setPadding(dp(8), dp(2), dp(4), dp(2));
            TextView number = new TextView(this);
            number.setText(Integer.toString(i + 1));
            number.setTextSize(12);
            number.setGravity(Gravity.CENTER);
            number.setTextColor(accent);
            number.setBackground(rounded(selection, 12));
            row.addView(number, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView title = label(group.primary.title, 14, PerformanceHud.color(group.primary, darkTheme), true);
            LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, -2, 1);
            titleLayout.setMarginStart(dp(10));
            row.addView(title, titleLayout);
            for (int direction : new int[]{-1, 1}) {
                Button arrow = performanceButton(direction < 0 ? R.string.performance_move_up : R.string.performance_move_down);
                arrow.setText(direction < 0 ? "↑" : "↓");
                arrow.setContentDescription(getString(direction < 0 ? R.string.performance_move_up
                        : R.string.performance_move_down, title.getText()));
                int destination = i + direction;
                arrow.setEnabled(destination >= 0 && destination < groups.size());
                arrow.setOnClickListener(view -> {
                    Collections.swap(groups, index, destination);
                    renderOrder(rows, groups);
                    LinearLayout moved = (LinearLayout) rows.getChildAt(destination);
                    View next = moved.getChildAt(direction < 0 ? 2 : 3);
                    (next.isEnabled() ? next : moved.getChildAt(direction < 0 ? 3 : 2)).requestFocus();
                });
                arrow.setMinHeight(dp(44));
                arrow.setMinimumWidth(0);
                LinearLayout.LayoutParams arrowLayout = new LinearLayout.LayoutParams(dp(46), dp(44));
                arrowLayout.setMarginStart(dp(5));
                row.addView(arrow, arrowLayout);
            }
            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(-1, -2);
            rowLayout.topMargin = dp(5);
            rows.addView(row, rowLayout);
        }
    }

    private void selectPerformancePreset(EnumMap<PerformancePreset, View> cards, int metrics) {
        cards.forEach((preset, card) -> card.setSelected(preset.metrics == metrics));
    }

    private void createNavigationPage() {
        addText(R.string.navigation_page, 28, textColor, true, 0);
        addText(R.string.navigation_intro, 14, muted, false, 4);
        permissionsPrompt = addControl();
        permissionsPrompt.setText(R.string.permissions_prompt);
        permissionsPrompt.setOnClickListener(view -> showPage(Page.SETTINGS));
        addToggle(R.string.navigation_enable, NavigationShortcuts.ENABLED, false);

        LinearLayout home = addNavigationCard(R.string.navigation_home);
        addNavigationChoice(home, R.string.navigation_tap, NavigationShortcuts.HOME_TAP, NavigationShortcuts.Action.HOME);
        addNavigationChoice(home, R.string.navigation_double, NavigationShortcuts.HOME_DOUBLE, NavigationShortcuts.Action.RECENTS);
        addNavigationChoice(home, R.string.navigation_hold, NavigationShortcuts.HOME_HOLD, NavigationShortcuts.Action.MENU);

        LinearLayout back = addNavigationCard(R.string.navigation_back);
        addNavigationChoice(back, R.string.navigation_tap, NavigationShortcuts.BACK_TAP, NavigationShortcuts.Action.BACK);
        addNavigationChoice(back, R.string.navigation_double, NavigationShortcuts.BACK_DOUBLE, NavigationShortcuts.Action.NONE);
        addNavigationChoice(back, R.string.navigation_hold, NavigationShortcuts.BACK_HOLD, NavigationShortcuts.Action.NONE);
        addText(R.string.navigation_tap_hint, 13, muted, false, 8);

        if (Settings.System.getInt(getContentResolver(), NavigationShortcuts.RETROID_HOME_PROTECTION, -1) != -1) {
            homeProtection = addControl();
            updateHomeProtection();
            homeProtection.setOnClickListener(view -> openRetroidSettings());
            addText(R.string.navigation_home_protection_hint, 13, muted, false, 6);
        }
    }

    private void openRetroidSettings() {
        Intent intent = new Intent().setClassName("com.rp.settings", "com.ro.settings.activity.MainSettingsActivity");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private LinearLayout addNavigationCard(int title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(12));
        card.setBackground(rounded(sidebarColor, 14));
        card.addView(label(title, 16, accent, true), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout choices = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        choices.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        choices.setBaselineAligned(false);
        LinearLayout.LayoutParams choicesLayout = new LinearLayout.LayoutParams(-1, -2);
        choicesLayout.topMargin = dp(8);
        card.addView(choices, choicesLayout);
        addRow(card, 10);
        return choices;
    }

    private void updateHomeProtection() {
        if (homeProtection != null) {
            int protection = Settings.System.getInt(getContentResolver(), NavigationShortcuts.RETROID_HOME_PROTECTION, -1);
            int status = protection < 0 ? R.string.navigation_home_protection_unavailable
                    : protection == 0 ? R.string.navigation_home_protection_off : R.string.navigation_home_protection_on;
            setChoiceLabel(homeProtection, R.string.navigation_home_protection, getString(status));
        }
    }

    private void addNavigationChoice(LinearLayout choices, int title, String key, NavigationShortcuts.Action fallback) {
        NavigationShortcuts.Action[] actions = NavigationShortcuts.Action.values();
        int[] values = new int[actions.length];
        String[] labels = new String[actions.length];
        for (int i = 0; i < actions.length; i++) {
            values[i] = i;
            labels[i] = getString(actions[i].label);
        }
        Button button = createControl();
        button.setTextSize(14);
        button.setMinHeight(dp(58));
        button.setBackground(controlBackground(true));
        boolean horizontal = choices.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams layout = horizontal
                ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, -2);
        if (choices.getChildCount() > 0) {
            if (horizontal) {
                layout.setMarginStart(dp(8));
            } else {
                layout.topMargin = dp(6);
            }
        }
        choices.addView(button, layout);
        configureChoice(button, title, key, fallback.ordinal(), values, labels, null);
    }

    private void createSettingsPage() {
        addText(R.string.settings_page, 28, textColor, true, 0);
        addText(R.string.settings_intro, 14, muted, false, 4);
        addText(R.string.permissions_section, 16, accent, true, 20);
        shortcutPermission = addPermission(R.string.shortcuts_section, R.string.accessibility_detail,
                R.string.accessibility_open, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        brightnessPermission = addPermission(R.string.brightness_permission, R.string.brightness_detail,
                R.string.brightness_open, new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
        addText(R.string.appearance_section, 16, accent, true, 20);
        addChoice(new TileGrid(), R.string.app_theme, OverlaySettings.THEME, OverlaySettings.DARK,
                new int[]{OverlaySettings.DARK, OverlaySettings.LIGHT, OverlaySettings.MATERIAL_YOU},
                getResources().getStringArray(R.array.app_themes), this::recreate);
        addText(R.string.theme_hint, 14, muted, false, 8);
    }

    private PermissionCard addPermission(int title, int detail, int actionText, Intent intent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(rounded(surface, 12));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(title, 15, textColor, true);
        LinearLayout.LayoutParams nameLayout = new LinearLayout.LayoutParams(0, -2, 1);
        nameLayout.setMarginEnd(dp(8));
        heading.addView(name, nameLayout);
        TextView status = label(R.string.permission_needed, 12, textColor, true);
        status.setPadding(dp(10), dp(4), dp(10), dp(4));
        heading.addView(status, new LinearLayout.LayoutParams(-2, -2));
        card.addView(heading);

        TextView description = label(detail, 13, muted, false);
        description.setPadding(0, dp(4), 0, 0);
        card.addView(description);
        Button action = new Button(this);
        action.setText(actionText);
        action.setAllCaps(false);
        action.setOnClickListener(view -> startActivity(intent));
        card.addView(action, new LinearLayout.LayoutParams(-2, -2));
        addRow(card, 8);
        return new PermissionCard(status, action);
    }

    private LinearLayout createSidebar() {
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(10), dp(22), dp(10), dp(12));
        sidebar.setBackgroundColor(sidebarColor);

        TextView brand = label(R.string.app_name, 16, textColor, true);
        brand.setPadding(dp(8), 0, dp(8), dp(16));
        sidebar.addView(brand);

        overlayTab = addTab(sidebar, R.string.overlay_page, Page.OVERLAY);
        performanceTab = addTab(sidebar, R.string.performance_page, Page.PERFORMANCE);
        navigationTab = addTab(sidebar, R.string.navigation_page, Page.NAVIGATION);
        sidebar.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1));
        settingsTab = addTab(sidebar, R.string.settings_page, Page.SETTINGS);
        return sidebar;
    }

    private TextView addTab(LinearLayout sidebar, int title, Page destination) {
        TextView tab = label(title, 14, accent, true);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setPadding(dp(10), dp(11), dp(10), dp(11));
        tab.setBackground(controlBackground());
        tab.setOnClickListener(view -> {
            if (page == destination) {
                scroll.smoothScrollTo(0, 0);
            } else {
                showPage(destination);
            }
        });
        sidebar.addView(tab, new LinearLayout.LayoutParams(-1, -2));
        return tab;
    }

    private void showBatteryIconOptions(Runnable refreshPreview) {
        String[] keys = {OverlaySettings.OVERLAY_BATTERY_PERCENT, OverlaySettings.OVERLAY_BATTERY_FILL,
                OverlaySettings.OVERLAY_CHARGING_INDICATOR};
        CharSequence[] labels = {getText(R.string.overlay_battery_percentage),
                getText(R.string.overlay_battery_fill), getText(R.string.overlay_charging_indicator)};
        boolean[] checked = {settings.getBoolean(keys[0], false), settings.getBoolean(keys[1], true),
                settings.getBoolean(keys[2], true)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.overlay_battery_icon)
                .setMultiChoiceItems(labels, checked, (dialog, index, enabled) -> {
                    settings.edit().putBoolean(keys[index], enabled).apply();
                    refreshPreview.run();
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private Runnable addPreview(LinearLayout previews, boolean brightness, int steps, float level) {
        FrameLayout frame = new FrameLayout(this);
        previews.addView(frame, new LinearLayout.LayoutParams(-1, dp(60)));
        OverlayView preview = new OverlayView(this);
        preview.applyTheme(settings.getInt(OverlaySettings.THEME, OverlaySettings.DARK));

        frame.addView(preview, new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
        Runnable resize = () -> {
            float density = (float) getResources().getDisplayMetrics().densityDpi
                    / OverlayView.REFERENCE_DENSITY_DPI;
            int size = settings.getInt(OverlaySettings.SIZE, OverlaySettings.DEFAULT_SIZE);
            preview.update(brightness, level, steps, getString(R.string.preview_time), 68, brightness, settings);
            int width = Math.min(frame.getWidth(), Math.round(preview.designWidth() * density * size / 100f));
            int height = Math.round(width * OverlayView.HEIGHT / preview.designWidth());
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) preview.getLayoutParams();
            if (params.width != width || params.height != height) {
                params.width = width;
                params.height = height;
                preview.setLayoutParams(params);
            }
        };
        frame.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> resize.run());
        return resize;
    }

    private Switch addToggle(int title, String key) {
        return addToggle(title, key, true);
    }

    private Switch addToggle(int title, String key, boolean defaultValue) {
        Switch toggle = createToggle(title, settings.getBoolean(key, defaultValue));
        addRow(toggle, 6);
        toggle.setOnCheckedChangeListener((button, checked) ->
                settings.edit().putBoolean(key, checked).apply());
        return toggle;
    }

    private Switch createToggle(int title, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextColor(textColor);
        toggle.setTextSize(15);
        toggle.setPadding(dp(14), dp(9), dp(14), dp(9));
        toggle.setMinHeight(dp(48));
        toggle.setBackground(controlBackground());
        toggle.setChecked(checked);
        return toggle;
    }

    private Button addChoice(TileGrid grid, int title, String key, int defaultValue, int[] values,
            String[] labels, Runnable onChange) {
        Button button = addTile(grid);
        configureChoice(button, title, key, defaultValue, values, labels, onChange);
        return button;
    }

    private void configureChoice(Button button, int title, String key, int defaultValue, int[] values,
            String[] labels, Runnable onChange) {
        int selected = choiceIndex(settings.getInt(key, defaultValue), values);
        setChoiceLabel(button, title, labels[selected]);
        button.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, choiceIndex(settings.getInt(key, defaultValue), values),
                        (dialog, index) -> {
                            settings.edit().putInt(key, values[index]).apply();
                            setChoiceLabel(button, title, labels[index]);
                            dialog.dismiss();
                            if (onChange != null) {
                                onChange.run();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private final class TileGrid {
        private final boolean wide = getResources().getConfiguration().screenWidthDp >= 620;
        private LinearLayout row;
        private boolean hasFirst;

        void add(View tile) {
            if (!wide) {
                addRow(tile, 6);
                return;
            }
            if (!hasFirst) {
                row = new LinearLayout(SetupActivity.this);
                row.setBaselineAligned(false);
                addRow(row, 8);
                row.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(new Space(SetupActivity.this), new LinearLayout.LayoutParams(0, 1, 1));
            } else {
                row.removeViewAt(1);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                params.setMarginStart(dp(8));
                row.addView(tile, params);
            }
            hasFirst = !hasFirst;
        }
    }

    private Button addTile(TileGrid grid) {
        Button button = createControl();
        button.setMinHeight(dp(64));
        button.setTextSize(14);
        grid.add(button);
        return button;
    }

    private void bindTile(Button button, Binding binding) {
        bindingButtons.put(binding, button);
        button.setOnClickListener(view -> recordButton(binding));
    }

    private Button createControl() {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(14), dp(9), dp(14), dp(9));
        button.setMinHeight(dp(48));
        button.setBackground(controlBackground());
        return button;
    }

    private Button addControl() {
        Button button = createControl();
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2);
        layout.topMargin = dp(6);
        content.addView(button, layout);
        return button;
    }

    private void setChoiceLabel(Button button, int title, String value) {
        String heading = getString(title);
        SpannableString text = new SpannableString(heading + "\n" + value);
        text.setSpan(new ForegroundColorSpan(muted), heading.length() + 1, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(text);
    }

    private Button addDuration(TileGrid grid, int title, String key, int defaultValue, int min, int max) {
        Button button = addTile(grid);
        setChoiceLabel(button, title, getString(R.string.milliseconds, settings.getInt(key, defaultValue)));
        button.setOnClickListener(view -> {
            EditText field = new EditText(this);
            field.setInputType(InputType.TYPE_CLASS_NUMBER);
            field.setSingleLine(true);
            field.setText(Integer.toString(settings.getInt(key, defaultValue)));
            field.setSelectAllOnFocus(true);
            FrameLayout input = new FrameLayout(this);
            input.setPadding(dp(24), dp(8), dp(24), 0);
            input.addView(field, new FrameLayout.LayoutParams(-1, -2));
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(getString(R.string.duration_range, min, max))
                    .setView(input)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save, null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(save -> {
                try {
                    int time = Integer.parseInt(field.getText().toString());
                    if (time >= min && time <= max) {
                        settings.edit().putInt(key, time).apply();
                        setChoiceLabel(button, title, getString(R.string.milliseconds, time));
                        dialog.dismiss();
                        return;
                    }
                } catch (NumberFormatException ignored) {
                    // The same range message covers empty or invalid input.
                }
                field.setError(getString(R.string.duration_range, min, max));
            });
        });
        return button;
    }

    private void recordButton(Binding binding) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(binding.title)
                .setMessage(R.string.record_button)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(binding.isPerformanceShortcut() ? R.string.performance_clear_shortcut
                        : R.string.restore_button, null)
                .create();
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            private final Set<Integer> pressed = new HashSet<>();
            private int first = KeyEvent.KEYCODE_UNKNOWN;
            private int second = KeyEvent.KEYCODE_UNKNOWN;
            private boolean tooMany;
            private boolean canceled;

            @Override
            public boolean onKey(DialogInterface window, int key, KeyEvent event) {
                if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE
                        || key == KeyEvent.KEYCODE_HOME || key == KeyEvent.KEYCODE_POWER
                        || key == KeyEvent.KEYCODE_UNKNOWN) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                        && pressed.add(key)) {
                    if (first == KeyEvent.KEYCODE_UNKNOWN) {
                        first = key;
                    } else if (second == KeyEvent.KEYCODE_UNKNOWN) {
                        second = key;
                    } else {
                        tooMany = true;
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP && pressed.remove(key)) {
                    canceled |= event.isCanceled();
                    if (pressed.isEmpty()) {
                        if (tooMany) {
                            dialog.setMessage(getString(R.string.shortcut_too_many_buttons));
                        } else if (!canceled) {
                            boolean combo = second != KeyEvent.KEYCODE_UNKNOWN;
                            if (assign(dialog, binding, combo ? first : KeyEvent.KEYCODE_UNKNOWN,
                                    combo ? second : first)) {
                                dialog.dismiss();
                            }
                        }
                        first = KeyEvent.KEYCODE_UNKNOWN;
                        second = KeyEvent.KEYCODE_UNKNOWN;
                        tooMany = false;
                        canceled = false;
                    }
                }
                return true;
            }
        });
        dialog.setOnDismissListener(ignored -> {
            OverlaySettings.recordingButton = false;
            recordingDialog = null;
        });
        recordingDialog = dialog;
        OverlaySettings.recordingButton = true;
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            int first = binding.defaultKey == KeyEvent.KEYCODE_UNKNOWN
                    ? KeyEvent.KEYCODE_UNKNOWN : OverlaySettings.DEFAULT_MODIFIER;
            if (assign(dialog, binding, first, binding.defaultKey)) {
                dialog.dismiss();
            }
        });
    }

    private boolean assign(AlertDialog dialog, Binding binding, int first, int key) {
        Binding previous = Binding.forKeys(settings, first, key);
        if (previous != null && previous != binding) {
            dialog.setMessage(getString(R.string.shortcut_in_use, bindingLabel(first, key), getString(previous.title)));
            return false;
        }
        binding.save(settings, first, key);
        refreshBindings();
        return true;
    }

    private void refreshBindings() {
        bindingButtons.forEach((binding, button) -> setChoiceLabel(button, binding.title,
                bindingLabel(binding.firstKey(settings), binding.key(settings))));
    }

    private String bindingLabel(int first, int key) {
        return first == KeyEvent.KEYCODE_UNKNOWN ? keyLabel(key)
                : getString(R.string.shortcut_combo, keyLabel(first), keyLabel(key));
    }

    private String keyLabel(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_UNKNOWN: return getString(R.string.shortcut_unassigned);
            case KeyEvent.KEYCODE_BUTTON_SELECT: return getString(R.string.button_select);
            case KeyEvent.KEYCODE_BUTTON_START: return getString(R.string.button_start);
            default:
                return KeyEvent.keyCodeToString(key).replaceFirst("^KEYCODE_(BUTTON_)?", "")
                        .replace('_', ' ');
        }
    }

    private static int choiceIndex(int value, int[] choices) {
        for (int index = 0; index < choices.length; index++) {
            if (choices[index] == value) {
                return index;
            }
        }
        return 0;
    }

    private TextView label(int text, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) {
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return label;
    }

    private void addText(int text, int size, int color, boolean bold, int top) {
        addRow(label(text, size, color, bold), top);
    }

    private void addRow(View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        content.addView(view, params);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private StateListDrawable controlBackground() {
        return controlBackground(false);
    }

    private StateListDrawable controlBackground(boolean outlineSelection) {
        StateListDrawable background = new StateListDrawable();
        GradientDrawable focused = rounded(selection, 10);
        focused.setStroke(dp(2), accent);
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        background.addState(new int[]{android.R.attr.state_pressed}, rounded(selection, 10));
        GradientDrawable selected = rounded(selection, 10);
        if (outlineSelection) {
            selected.setStroke(dp(1), accent);
        }
        background.addState(new int[]{android.R.attr.state_selected}, selected);
        GradientDrawable idle = rounded(surface, 10);
        if (outlineSelection) {
            idle.setStroke(dp(1), selection);
        }
        background.addState(new int[0], idle);
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (performanceStatus != null) {
            performanceStatus.setVisible(true);
        }
        updatePermissions();
        updateHomeProtection();
    }

    @Override
    protected void onPause() {
        visible = false;
        if (performanceStatus != null) {
            performanceStatus.setVisible(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        settings.unregisterOnSharedPreferenceChangeListener(performanceState);
        if (performanceStatus != null) {
            performanceStatus.close();
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        if (recordingDialog != null) {
            recordingDialog.dismiss();
        }
        super.onStop();
    }

    private void updatePermissions() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        ComponentName service = new ComponentName(this, ShortcutService.class);
        boolean enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK).stream().anyMatch(info ->
                service.equals(ComponentName.unflattenFromString(info.getId())));
        boolean brightnessAllowed = Settings.System.canWrite(this);
        if (page == Page.SETTINGS) {
            showPermissionStatus(shortcutPermission, enabled);
            showPermissionStatus(brightnessPermission, brightnessAllowed);
        } else {
            boolean needsPermission = !enabled || (page == Page.OVERLAY && !brightnessAllowed);
            permissionsPrompt.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
        }
    }

    private void showPermissionStatus(PermissionCard card, boolean allowed) {
        int foreground = allowed
                ? (darkTheme ? 0xffa5e7b6 : 0xff165a2e)
                : (darkTheme ? 0xffffd58e : 0xff714600);
        int fill = allowed
                ? (darkTheme ? 0xff193b28 : 0xffd5f4dd)
                : (darkTheme ? 0xff493217 : 0xffffead0);
        card.status.setText(allowed ? R.string.permission_allowed : R.string.permission_needed);
        card.status.setTextColor(foreground);
        card.status.setBackground(rounded(fill, 16));
        card.action.setVisibility(allowed ? View.GONE : View.VISIBLE);
    }
}

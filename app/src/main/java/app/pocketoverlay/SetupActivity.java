package app.pocketoverlay;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.EnumMap;

import app.pocketoverlay.OverlaySettings.Binding;

public final class SetupActivity extends Activity {
    private static final String SETTINGS_TAB = "settings_tab";
    private int background, sidebarColor, surface, accent, textColor, muted, selection;
    private SharedPreferences settings;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView overlayTab;
    private TextView settingsTab;
    private boolean showingSettings;
    private boolean darkTheme;
    private AlertDialog recordingDialog;
    private final EnumMap<Binding, Button> bindingButtons = new EnumMap<>(Binding.class);
    private PermissionCard shortcutPermission;
    private PermissionCard brightnessPermission;
    private Button permissionsPrompt;

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
        showPage(state != null && state.getBoolean(SETTINGS_TAB));
        setContentView(shell);
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
        state.putBoolean(SETTINGS_TAB, showingSettings);
    }

    private void showPage(boolean settingsPage) {
        showingSettings = settingsPage;
        content.removeAllViews();
        bindingButtons.clear();
        shortcutPermission = null;
        brightnessPermission = null;
        permissionsPrompt = null;
        overlayTab.setSelected(!settingsPage);
        settingsTab.setSelected(settingsPage);
        if (settingsPage) {
            createSettingsPage();
        } else {
            createOverlayPage();
        }
        updatePermissions();
        scroll.scrollTo(0, 0);
    }

    private void createOverlayPage() {
        addText(R.string.overlay_page, 28, textColor, true, 0);
        addText(R.string.setup_intro, 14, muted, false, 4);
        permissionsPrompt = addControl();
        permissionsPrompt.setText(R.string.permissions_prompt);
        permissionsPrompt.setOnClickListener(view -> showPage(true));
        addText(R.string.preview_section, 16, accent, true, 20);
        LinearLayout previews = new LinearLayout(this);
        previews.setOrientation(LinearLayout.VERTICAL);
        previews.setPadding(dp(8), dp(8), dp(8), dp(8));
        previews.setBackground(rounded(sidebarColor, 14));
        addRow(previews, 8);
        Runnable volumePreview = addPreview(previews, false, 15, 0.53f);
        Runnable brightnessPreview = addPreview(previews, true, 20, 0.65f);

        addText(R.string.appearance_section, 16, accent, true, 20);
        addChoice(R.string.overlay_size, OverlaySettings.SIZE, OverlaySettings.DEFAULT_SIZE,
                new int[]{75, 90, 100, 110, 125},
                getResources().getStringArray(R.array.overlay_sizes), () -> {
                    volumePreview.run();
                    brightnessPreview.run();
                });
        Switch slide = addToggle(R.string.slide_animation, OverlaySettings.SLIDE);
        Button enter = addDuration(R.string.slide_in_time, OverlaySettings.SLIDE_IN,
                OverlaySettings.DEFAULT_SLIDE_IN, 0, 2000);
        Button exit = addDuration(R.string.slide_out_time, OverlaySettings.SLIDE_OUT,
                OverlaySettings.DEFAULT_SLIDE_OUT, 0, 2000);
        addDuration(R.string.display_time, OverlaySettings.DISPLAY_TIME,
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
        for (Binding binding : Binding.values()) {
            Button button = addControl();
            bindingButtons.put(binding, button);
            button.setOnClickListener(view -> recordButton(binding));
        }
        refreshBindings();
        Switch repeat = addToggle(R.string.hold_to_repeat, OverlaySettings.REPEAT);
        Button delay = addChoice(R.string.repeat_delay, OverlaySettings.REPEAT_DELAY,
                OverlaySettings.DEFAULT_DELAY, new int[]{500, 1000, 1500, 2000},
                getResources().getStringArray(R.array.repeat_delays), null);
        Button speed = addChoice(R.string.repeat_speed, OverlaySettings.REPEAT_INTERVAL,
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
        addChoice(R.string.app_theme, OverlaySettings.THEME, OverlaySettings.DARK,
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

        overlayTab = addTab(sidebar, R.string.overlay_page, false);
        sidebar.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1));
        settingsTab = addTab(sidebar, R.string.settings_page, true);
        return sidebar;
    }

    private TextView addTab(LinearLayout sidebar, int title, boolean settingsPage) {
        TextView tab = label(title, 14, accent, true);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setPadding(dp(10), dp(11), dp(10), dp(11));
        tab.setBackground(controlBackground());
        tab.setOnClickListener(view -> {
            if (showingSettings == settingsPage) {
                scroll.smoothScrollTo(0, 0);
            } else {
                showPage(settingsPage);
            }
        });
        sidebar.addView(tab, new LinearLayout.LayoutParams(-1, -2));
        return tab;
    }

    private Runnable addPreview(LinearLayout previews, boolean brightness, int steps, float level) {
        FrameLayout frame = new FrameLayout(this);
        previews.addView(frame, new LinearLayout.LayoutParams(-1, dp(60)));
        OverlayView preview = new OverlayView(this);
        preview.applyTheme(settings.getInt(OverlaySettings.THEME, OverlaySettings.DARK));
        preview.update(brightness, level, steps, getString(R.string.preview_time), 68, false);
        frame.addView(preview, new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
        Runnable resize = () -> {
            float density = (float) getResources().getDisplayMetrics().densityDpi
                    / OverlayView.REFERENCE_DENSITY_DPI;
            int size = settings.getInt(OverlaySettings.SIZE, OverlaySettings.DEFAULT_SIZE);
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
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextColor(textColor);
        toggle.setTextSize(15);
        toggle.setPadding(dp(14), dp(9), dp(14), dp(9));
        toggle.setMinHeight(dp(48));
        toggle.setBackground(controlBackground());
        toggle.setChecked(settings.getBoolean(key, true));
        toggle.setOnCheckedChangeListener((button, checked) ->
                settings.edit().putBoolean(key, checked).apply());
        addRow(toggle, 6);
        return toggle;
    }

    private Button addChoice(int title, String key, int defaultValue, int[] values,
            String[] labels, Runnable onChange) {
        Button button = addControl();
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
        return button;
    }

    private Button addControl() {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(14), dp(9), dp(14), dp(9));
        button.setMinHeight(dp(48));
        button.setBackground(controlBackground());
        addRow(button, 6);
        return button;
    }

    private void setChoiceLabel(Button button, int title, String value) {
        button.setText(getString(R.string.choice_label, getString(title), value));
    }

    private Button addDuration(int title, String key, int defaultValue, int min, int max) {
        Button button = addControl();
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
                .setNeutralButton(R.string.restore_button, (ignored, which) -> assign(binding, binding.defaultKey))
                .create();
        dialog.setOnKeyListener((window, key, event) -> {
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE
                    || key == KeyEvent.KEYCODE_HOME || key == KeyEvent.KEYCODE_POWER
                    || key == KeyEvent.KEYCODE_UNKNOWN) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                assign(binding, key);
                dialog.dismiss();
            }
            return true;
        });
        dialog.setOnDismissListener(ignored -> {
            OverlaySettings.recordingButton = false;
            recordingDialog = null;
        });
        recordingDialog = dialog;
        OverlaySettings.recordingButton = true;
        dialog.show();
    }

    private void assign(Binding binding, int key) {
        Binding previous = Binding.forKey(settings, key);
        SharedPreferences.Editor editor = settings.edit().putInt(binding.preference, key);
        if (previous != null && previous != binding) {
            editor.putInt(previous.preference, binding.key(settings));
            Toast.makeText(this, getString(R.string.swapped_buttons, getString(previous.title)),
                    Toast.LENGTH_SHORT).show();
        }
        editor.apply();
        refreshBindings();
    }

    private void refreshBindings() {
        bindingButtons.forEach((binding, button) ->
                setChoiceLabel(button, binding.title, keyLabel(binding.key(settings))));
    }

    private String keyLabel(int key) {
        switch (key) {
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
        StateListDrawable background = new StateListDrawable();
        GradientDrawable focused = rounded(selection, 10);
        focused.setStroke(dp(2), accent);
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        background.addState(new int[]{android.R.attr.state_pressed}, rounded(selection, 10));
        background.addState(new int[]{android.R.attr.state_selected}, rounded(selection, 10));
        background.addState(new int[0], rounded(surface, 10));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissions();
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
        if (showingSettings) {
            showPermissionStatus(shortcutPermission, enabled);
            showPermissionStatus(brightnessPermission, brightnessAllowed);
        } else {
            permissionsPrompt.setVisibility(enabled && brightnessAllowed ? View.GONE : View.VISIBLE);
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

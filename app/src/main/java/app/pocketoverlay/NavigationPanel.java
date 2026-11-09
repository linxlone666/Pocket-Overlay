package app.pocketoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class NavigationPanel extends LinearLayout {
    private final int foreground;
    private Runnable outside;
    private IntConsumer horizontal;
    private int hat;
    private LinearLayout header;
    private int title;
    private boolean titleChanged;
    private View firstChoice;
    private View currentChoice;

    NavigationPanel(Context context, SharedPreferences settings, int title, int detail) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(14), dp(9), dp(14), dp(10));
        int theme = settings.getInt(OverlaySettings.THEME, OverlaySettings.DARK);
        boolean dark = OverlaySettings.usesDarkColors(context, theme);
        foreground = OverlayView.statusColor(context, theme, dark);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(0xff000000 | OverlayView.panelColor(context, theme, dark));
        fill.setCornerRadius(dp(14));
        setBackground(fill);
        setClipToOutline(true);
        setContent(title, detail);
    }

    private NavigationPanel(Context context, int foreground) {
        super(context);
        this.foreground = foreground;
        setOrientation(VERTICAL);
    }

    NavigationPanel controlColumn() {
        return new NavigationPanel(getContext(), foreground);
    }

    int foregroundColor() {
        return foreground;
    }

    void setContent(int title, int detail) {
        titleChanged = this.title != 0 && this.title != title;
        this.title = title;
        removeAllViews();
        firstChoice = null;
        currentChoice = null;
        header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        TextView heading = label(15);
        heading.setText(title);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(heading, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        if (detail != 0) {
            TextView hint = label(12);
            hint.setText(detail);
            hint.setAlpha(0.8f);
            addView(hint);
        }
    }

    void animateContentChange() {
        if (!titleChanged) return;
        titleChanged = false;
        for (int i = 0; i < getChildCount(); i++) {
            UiMotion.reveal(getChildAt(i), 0.4f, 5, 170);
        }
    }

    void setHeaderControl(View control) {
        header.addView(control, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)));
    }

    void addActionRow(int[] keys, int[] labels, Runnable[] actions) {
        addActionRow(keys, labels, actions, -1, false);
    }

    View[] addChoiceRow(int[] keys, int[] labels, Runnable[] actions, int selectedIndex) {
        return addActionRow(keys, labels, actions, selectedIndex, true);
    }

    Switch[] addSwitchRow(int[] labels, boolean[] values, Consumer<Boolean> left, Consumer<Boolean> right) {
        LinearLayout row = new LinearLayout(getContext());
        row.setPadding(0, dp(5), 0, 0);
        Switch[] switches = new Switch[labels.length];
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < labels.length; i++) {
            Switch toggle = new Switch(getContext());
            toggle.setText(labels[i]);
            toggle.setTextColor(foreground);
            toggle.setChecked(values[i]);
            toggle.setMinHeight(dp(40));
            toggle.setPadding(dp(6), 0, dp(6), 0);
            toggle.setFocusable(true);
            Consumer<Boolean> onChange = i == 0 ? left : right;
            toggle.setOnCheckedChangeListener((button, checked) -> onChange.accept(checked));
            row.addView(toggle, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            switches[i] = toggle;
        }
        return switches;
    }

    SeekBar addPercentSlider(int label, int format, int percent, IntConsumer onChange) {
        return addSlider(label, format, 0, 100, 5, percent, onChange);
    }

    SeekBar addSlider(int label, int format, int min, int max, int step, int initial, IntConsumer onChange) {
        TextView value = label(13);
        value.setText(getContext().getString(format, initial));
        LayoutParams valueLayout = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        valueLayout.topMargin = dp(8);
        addView(value, valueLayout);

        SeekBar slider = new SeekBar(getContext());
        slider.setContentDescription(getContext().getString(label));
        slider.setMax(max);
        slider.setMin(min);
        slider.setKeyProgressIncrement(step);
        slider.setProgress(initial);
        slider.setFocusableInTouchMode(true);
        ColorStateList tint = ColorStateList.valueOf(foreground);
        slider.setThumbTintList(tint);
        slider.setProgressTintList(tint);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(dp(7));
        focus.setStroke(dp(1), new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                new int[]{foreground, 0}));
        slider.setBackground(focus);
        slider.setPadding(dp(12), 0, dp(12), 0);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(getContext().getString(format, progress));
                if (fromUser) {
                    onChange.accept(progress);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        addView(slider, new LayoutParams(LayoutParams.MATCH_PARENT, dp(42)));
        return slider;
    }

    void focusCurrentChoice() {
        (currentChoice == null ? firstChoice : currentChoice).requestFocus();
    }

    void confirmChoice() {
        View focused = findFocus();
        if (focused != null && focused != this) {
            focused.performClick();
        }
    }

    private View[] addActionRow(int[] keys, int[] labels, Runnable[] actions, int selectedIndex, boolean choiceRow) {
        LinearLayout row = new LinearLayout(getContext());
        row.setBaselineAligned(false);
        LayoutParams rowLayout = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowLayout.topMargin = dp(5);
        addView(row, rowLayout);
        View[] tiles = new View[labels.length];
        for (int i = 0; i < labels.length; i++) {
            LinearLayout tile = new LinearLayout(getContext());
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setMinimumHeight(dp(38));
            tile.setPadding(dp(7), dp(4), dp(6), dp(4));

            GradientDrawable fill = new GradientDrawable();
            boolean selected = i == selectedIndex;
            int rgb = foreground & 0x00ffffff;
            fill.setColor(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_selected}, new int[0]},
                    new int[]{0x36000000 | rgb, 0x10000000 | rgb}));
            tile.setSelected(selected);
            fill.setCornerRadius(dp(7));
            tile.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x30000000 | (foreground & 0x00ffffff)), fill, null));

            if (keys[i] != 0) {
                TextView key = label(12);
                key.setText(keys[i]);
                key.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                tile.addView(key, new LayoutParams(dp(32), LayoutParams.WRAP_CONTENT));
            }
            if (choiceRow) {
                tile.setFocusableInTouchMode(true);
                fill.setStroke(dp(2), new ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                        new int[]{foreground, 0}));
                if (firstChoice == null) {
                    firstChoice = tile;
                }
                if (selected) {
                    currentChoice = tile;
                }
            }

            TextView action = label(12);
            action.setText(labels[i]);
            if (selected) {
                action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            }
            tile.addView(action, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            Runnable runAction = actions[i];
            tile.setOnClickListener(view -> runAction.run());

            LayoutParams cell = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) {
                cell.setMarginStart(dp(5));
            }
            row.addView(tile, cell);
            tiles[i] = tile;
        }
        return tiles;
    }

    void setControls(Runnable outside, IntConsumer horizontal) {
        this.outside = outside;
        this.horizontal = horizontal;
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE && outside != null) {
            outside.run();
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (horizontal != null && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float axis = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            if (axis == 0) {
                axis = event.getAxisValue(MotionEvent.AXIS_X);
            }
            int direction = axis > 0.65f ? 1 : axis < -0.65f ? -1 : 0;
            if (direction != hat && direction != 0) {
                horizontal.accept(direction);
            }
            hat = direction;
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private TextView label(int sp) {
        TextView view = new TextView(getContext());
        view.setTextSize(sp);
        view.setTextColor(foreground);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

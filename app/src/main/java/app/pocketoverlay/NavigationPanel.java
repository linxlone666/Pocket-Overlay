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
import android.widget.TextView;

import java.util.function.IntConsumer;

final class NavigationPanel extends LinearLayout {
    private final int foreground;
    private Runnable outside;
    private IntConsumer horizontal;
    private int hat;
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
        setContent(title, detail);
    }

    void setContent(int title, int detail) {
        removeAllViews();
        firstChoice = null;
        currentChoice = null;
        TextView heading = label(15);
        heading.setText(title);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(heading);
        if (detail != 0) {
            TextView hint = label(12);
            hint.setText(detail);
            hint.setAlpha(0.8f);
            addView(hint);
        }
    }

    void addActionRow(int[] keys, int[] labels, Runnable[] actions) {
        addActionRow(keys, labels, actions, -1, false);
    }

    void addChoiceRow(int[] keys, int[] labels, Runnable[] actions, int selectedIndex) {
        addActionRow(keys, labels, actions, selectedIndex, true);
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

    private void addActionRow(int[] keys, int[] labels, Runnable[] actions, int selectedIndex, boolean choiceRow) {
        LinearLayout row = new LinearLayout(getContext());
        row.setBaselineAligned(false);
        LayoutParams rowLayout = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowLayout.topMargin = dp(5);
        addView(row, rowLayout);
        for (int i = 0; i < labels.length; i++) {
            LinearLayout tile = new LinearLayout(getContext());
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setMinimumHeight(dp(38));
            tile.setPadding(dp(7), dp(4), dp(6), dp(4));

            GradientDrawable fill = new GradientDrawable();
            boolean selected = i == selectedIndex;
            fill.setColor((selected ? 0x36000000 : 0x10000000) | (foreground & 0x00ffffff));
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
        }
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

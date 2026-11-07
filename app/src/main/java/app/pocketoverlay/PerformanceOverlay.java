package app.pocketoverlay;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.Layout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PerformanceOverlay implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int UPDATE_INTERVAL_MS = 1000;

    private final Context context;
    private final SharedPreferences settings;
    private final WindowManager windows;
    private final PowerManager power;
    private final KeyguardManager keyguard;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refresh;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final PerformanceReadings readings;
    private final ScaleGestureDetector pinch;
    private float gestureScale;
    private boolean pinched;
    private final PerformanceStats localStats = new PerformanceStats();
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                battery = intent;
            } else {
                refresh();
            }
        }
    };
    private Intent battery;
    private TextView panel;
    private WindowManager.LayoutParams placement;
    private boolean dragging;
    private float dragX;
    private float dragY;
    private Bundle sampledReadings = Bundle.EMPTY;
    private boolean sampling;

    PerformanceOverlay(Context context, SharedPreferences settings, WindowManager windows) {
        this.context = context;
        this.settings = settings;
        this.windows = windows;
        readings = new PerformanceReadings(context);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                gestureScale = hudScale();
                pinched = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                gestureScale = Math.max(OverlaySettings.PERFORMANCE_MIN_SCALE,
                        Math.min(OverlaySettings.PERFORMANCE_MAX_SCALE, gestureScale * detector.getScaleFactor()));
                render(selectedMetrics());
                updateWidth(movementBounds());
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
        pinch.setStylusScaleEnabled(false);
        power = context.getSystemService(PowerManager.class);
        keyguard = context.getSystemService(KeyguardManager.class);
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        battery = Build.VERSION.SDK_INT >= 33
                ? context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                : context.registerReceiver(receiver, filter);
        settings.registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (OverlaySettings.PERFORMANCE_ENABLED.equals(key)
                || OverlaySettings.PERFORMANCE_POSITION.equals(key)
                || OverlaySettings.PERFORMANCE_RAM_FORMAT.equals(key)
                || OverlaySettings.PERFORMANCE_ORDER.equals(key)
                || OverlaySettings.PERFORMANCE_OPACITY.equals(key)
                || OverlaySettings.PERFORMANCE_SCALE.equals(key)
                || OverlaySettings.PERFORMANCE_LAYOUT.equals(key)
                || OverlaySettings.PERFORMANCE_LOCKED.equals(key)
                || OverlaySettings.PERFORMANCE_METRICS.equals(key) || OverlaySettings.THEME.equals(key)) {
            if (panel != null && OverlaySettings.PERFORMANCE_LOCKED.equals(key)) {
                setTouchability(placement);
                windows.updateViewLayout(panel, placement);
            }
            refresh();
        }
    }

    private void refresh() {
        handler.removeCallbacks(refresh);
        int metrics = selectedMetrics();
        if (metrics == 0 || !settings.getBoolean(OverlaySettings.PERFORMANCE_ENABLED, false)
                || !power.isInteractive() || keyguard.isKeyguardLocked()) {
            hide();
            return;
        }
        if (SetupActivity.visible) {
            hide();
            handler.postDelayed(refresh, UPDATE_INTERVAL_MS);
            return;
        }
        sampleStats(metrics & PerformanceMetric.SAMPLED);
        if (panel == null) {
            panel = new TextView(context);
            panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            PerformanceHud.setup(panel);
            panel.setPadding(dp(12), dp(7), dp(12), dp(7));
            panel.setOnTouchListener(this::drag);
            panel.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                if (r - l != oldR - oldL || b - t != oldB - oldT) {
                    handler.post(this::updatePlacement);
                }
            });
            placement = windowLayout();
            windows.addView(panel, placement);
        }
        if (!dragging) {
            render(metrics);
            updatePlacement();
        }
        handler.postDelayed(refresh, UPDATE_INTERVAL_MS);
    }

    private int selectedMetrics() {
        return settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT);
    }

    private void sampleStats(int metrics) {
        if (sampling || metrics == 0) {
            return;
        }
        sampling = true;
        worker.execute(() -> {
            Bundle latest = localStats.sample(metrics);
            handler.post(() -> {
                sampling = false;
                if (panel != null) {
                    sampledReadings = latest;
                    if (!dragging) {
                        render(selectedMetrics());
                        updatePlacement();
                    }
                }
            });
        });
    }

    private void render(int metrics) {
        PerformanceHud.render(panel, settings, readings, battery, sampledReadings, metrics,
                settings.getInt(OverlaySettings.PERFORMANCE_LAYOUT, OverlaySettings.PERFORMANCE_LIST),
                pinched ? gestureScale : hudScale(),
                settings.getInt(OverlaySettings.PERFORMANCE_OPACITY, OverlaySettings.PERFORMANCE_DEFAULT_OPACITY),
                movementBounds().width());
    }

    private WindowManager.LayoutParams windowLayout() {
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            layout.setFitInsetsTypes(0);
        }
        setTouchability(layout);
        return layout;
    }

    private void setTouchability(WindowManager.LayoutParams layout) {
        if (settings.getBoolean(OverlaySettings.PERFORMANCE_LOCKED, false)) {
            layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
    }

    @SuppressWarnings("deprecation")
    private Rect movementBounds() {
        Rect area;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = windows.getCurrentWindowMetrics();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.displayCutout());
            area = new Rect(insets.left, insets.top, metrics.getBounds().width() - insets.right,
                    metrics.getBounds().height() - insets.bottom);
        } else {
            Point size = new Point();
            windows.getDefaultDisplay().getRealSize(size);
            area = new Rect(0, 0, size.x, size.y);
        }
        return area;
    }

    private void updatePlacement() {
        if (panel == null || dragging) {
            return;
        }
        Rect area = movementBounds();
        updateWidth(area);
        int corner = settings.getInt(OverlaySettings.PERFORMANCE_POSITION,
                OverlaySettings.PERFORMANCE_DEFAULT_POSITION);
        float x = settings.getFloat(OverlaySettings.PERFORMANCE_X, corner % 2);
        float y = settings.getFloat(OverlaySettings.PERFORMANCE_Y, corner / 2);
        move(area, area.left + Math.round(x * availableWidth(area)),
                area.top + Math.round(y * availableHeight(area)));
    }

    private void updateWidth(Rect area) {
        int maxWidth = Math.max(1, area.width());
        if (panel.getMaxWidth() != maxWidth) {
            panel.setMaxWidth(maxWidth);
        }
        // Android caps the default wrap-content width of floating windows.
        int width = WindowManager.LayoutParams.WRAP_CONTENT;
        if (panel.getMaxLines() == 1) {
            int textWidth = (int) Math.ceil(Layout.getDesiredWidth(panel.getText(), panel.getPaint()));
            width = Math.min(maxWidth, textWidth + panel.getPaddingLeft() + panel.getPaddingRight() + 2);
        }
        if (placement.width != width) {
            placement.width = width;
            windows.updateViewLayout(panel, placement);
        }
    }

    private int availableWidth(Rect area) {
        return Math.max(0, area.width() - panel.getWidth());
    }

    private int availableHeight(Rect area) {
        return Math.max(0, area.height() - panel.getHeight());
    }

    private void move(Rect area, int x, int y) {
        int nextX = Math.max(area.left, Math.min(x, area.left + availableWidth(area)));
        int nextY = Math.max(area.top, Math.min(y, area.top + availableHeight(area)));
        if (placement.x != nextX || placement.y != nextY) {
            placement.x = nextX;
            placement.y = nextY;
            windows.updateViewLayout(panel, placement);
        }
    }

    private int hudScale() {
        return settings.getInt(OverlaySettings.PERFORMANCE_SCALE, 100);
    }

    private boolean drag(View view, MotionEvent event) {
        pinch.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                pinched = false;
                dragX = event.getRawX() - placement.x;
                dragY = event.getRawY() - placement.y;
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (dragging && !pinched && event.getPointerCount() == 1) {
                    Rect area = movementBounds();
                    move(area, Math.round(event.getRawX() - dragX), Math.round(event.getRawY() - dragY));
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        savePosition(area);
                        dragging = false;
                    }
                }
                if (pinched && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    dragging = false;
                    pinched = false;
                    settings.edit().putInt(OverlaySettings.PERFORMANCE_SCALE, Math.round(gestureScale)).apply();
                    refresh();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                pinched = false;
                render(selectedMetrics());
                dragging = false;
                updatePlacement();
                break;
            default:
                break;
        }
        return true;
    }

    private void savePosition(Rect area) {
        int width = availableWidth(area);
        int height = availableHeight(area);
        SharedPreferences.Editor editor = settings.edit();
        if (width > 0) {
            editor.putFloat(OverlaySettings.PERFORMANCE_X, (placement.x - area.left) / (float) width);
        }
        if (height > 0) {
            editor.putFloat(OverlaySettings.PERFORMANCE_Y, (placement.y - area.top) / (float) height);
        }
        editor.apply();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void hide() {
        if (panel != null) {
            windows.removeViewImmediate(panel);
            panel = null;
            pinched = false;
            dragging = false;
        }
        sampledReadings = Bundle.EMPTY;
    }

    void close() {
        settings.unregisterOnSharedPreferenceChangeListener(this);
        context.unregisterReceiver(receiver);
        handler.removeCallbacks(refresh);
        worker.shutdownNow();
        hide();
    }
}

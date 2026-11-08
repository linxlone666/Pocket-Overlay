package app.pocketoverlay;

import android.graphics.Point;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Closes the stock, non-focusable ClusterTune picker without moving controller focus. */
final class ClusterTuneControls {
    private final Handler handler;
    private final Supplier<Point> screenSize;
    private final BooleanSupplier ready;
    private final BiPredicate<Float, Float> tap;
    private final Runnable poll = this::poll;

    private Point display;
    private Object session;
    private long openDeadline;
    private boolean checking;
    private boolean visible;
    private boolean closing;

    ClusterTuneControls(Handler handler, Supplier<Point> screenSize, BooleanSupplier ready,
            BiPredicate<Float, Float> tap) {
        this.handler = handler;
        this.screenSize = screenSize;
        this.ready = ready;
        this.tap = tap;
    }

    void start(Point size) {
        stop();
        display = size;
        session = new Object();
        openDeadline = SystemClock.uptimeMillis() + 1800;
        handler.postDelayed(poll, 260);
    }

    void stop() {
        session = null;
        checking = visible = closing = false;
        handler.removeCallbacks(poll);
    }

    private void poll() {
        if (checking || session == null) {
            return;
        }
        if (!ready.getAsBoolean() || !display.equals(screenSize.get())) {
            stop();
            return;
        }
        Object current = session;
        checking = true;
        new Thread(() -> {
            boolean open = ClusterTuneShortcut.isPickerVisible(new RetroidFirmware());
            handler.post(() -> update(current, open));
        }, "cluster-tune-visibility").start();
    }

    private void update(Object current, boolean open) {
        if (current != session) {
            return;
        }
        checking = false;
        if (!ready.getAsBoolean() || !display.equals(screenSize.get())) {
            stop();
        } else if (!open) {
            if (!visible && SystemClock.uptimeMillis() < openDeadline) {
                handler.postDelayed(poll, 120);
            } else {
                stop();
            }
        } else {
            visible = true;
            if (closing) {
                // The compact picker leaves a dismissible scrim at the left edge.
                tap.test(.01f, .5f);
                closing = false;
            }
            handler.postDelayed(poll, 320);
        }
    }

    boolean handleKey(int key, boolean physicalBack) {
        if (!visible || !ready.getAsBoolean()
                || (!physicalBack && key != KeyEvent.KEYCODE_BUTTON_B && key != KeyEvent.KEYCODE_ESCAPE)) {
            return false;
        }
        closing = true;
        handler.removeCallbacks(poll);
        poll();
        return true;
    }
}

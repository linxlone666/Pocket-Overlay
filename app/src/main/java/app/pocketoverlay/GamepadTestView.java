package app.pocketoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.util.SparseBooleanArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.IntFunction;

/** Live controller output as seen by Android, after firmware remapping. */
final class GamepadTestView extends View {
    private static GamepadTestView active;
    private final Bitmap console;
    private final int accent;
    private final int textColor;
    private final IntFunction<String> keyLabel;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path directionHighlight = new Path();
    private final SparseBooleanArray pressed = new SparseBooleanArray();
    private float lx, ly, rx, ry, l2, r2, hatX, hatY;
    private static final int PRESS_COLOR = 0xfff9d455;
    private int lastKey = KeyEvent.KEYCODE_UNKNOWN;

    GamepadTestView(Context context, Bitmap console, int accent, int textColor,
                    IntFunction<String> keyLabel) {
        super(context);
        this.console = console;
        this.accent = accent;
        this.textColor = textColor;
        this.keyLabel = keyLabel;
        directionHighlight.moveTo(-12, -11);
        directionHighlight.quadTo(-12, -14, -8, -14);
        directionHighlight.lineTo(8, -14);
        directionHighlight.quadTo(12, -14, 12, -11);
        directionHighlight.lineTo(12, 5);
        directionHighlight.lineTo(0, 17);
        directionHighlight.lineTo(-12, 5);
        directionHighlight.close();
        setFocusableInTouchMode(true);
        setContentDescription(context.getText(R.string.remap_gamepad_hint));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        active = this;
    }

    @Override protected void onDetachedFromWindow() {
        if (active == this) active = null;
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) reset();
    }

    static boolean captureKey(KeyEvent event) {
        return active != null && active.hasWindowFocus() && active.recordKey(event);
    }

    boolean recordKey(KeyEvent event) {
        if (!ControllerRemap.isBuiltInController(event.getDevice())) return false;
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                pressed.put(key, true);
                lastKey = key;
                invalidate();
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            pressed.delete(key);
            invalidate();
            return true;
        }
        return false;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (!ControllerRemap.isBuiltInController(event.getDevice())
                || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event);
        }
        lx = event.getAxisValue(MotionEvent.AXIS_X);
        ly = event.getAxisValue(MotionEvent.AXIS_Y);
        rx = axis(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX);
        ry = axis(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY);
        l2 = axis(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE);
        r2 = axis(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS);
        hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        invalidate();
        return true;
    }

    private static float axis(MotionEvent event, int preferred, int fallback) {
        InputDevice device = event.getDevice();
        int axis = device.getMotionRange(preferred, event.getSource()) != null ? preferred : fallback;
        return event.getAxisValue(axis);
    }

    private void reset() {
        pressed.clear();
        lx = ly = rx = ry = l2 = r2 = hatX = hatY = 0;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 760f, getHeight() / 444f);
        canvas.save();
        canvas.translate((getWidth() - 736 * scale) / 2, (getHeight() - 444 * scale) / 2);
        canvas.scale(scale, scale);

        int artworkTop = 58;
        if (console != null) canvas.drawBitmap(console, null, new RectF(0, artworkTop, 736, artworkTop + 312), paint);
        badge(canvas, 49, 29, "L1", down(KeyEvent.KEYCODE_BUTTON_L1));
        badge(canvas, 139, 29, "L2 " + trigger(l2, KeyEvent.KEYCODE_BUTTON_L2) + "%", l2 > .03f || down(KeyEvent.KEYCODE_BUTTON_L2));
        badge(canvas, 597, 29, "R2 " + trigger(r2, KeyEvent.KEYCODE_BUTTON_R2) + "%", r2 > .03f || down(KeyEvent.KEYCODE_BUTTON_R2));
        badge(canvas, 687, 29, "R1", down(KeyEvent.KEYCODE_BUTTON_R1));

        mark(canvas, 680, 160, KeyEvent.KEYCODE_BUTTON_A, false);
        mark(canvas, 652, 190, KeyEvent.KEYCODE_BUTTON_B, false);
        mark(canvas, 651, 131, KeyEvent.KEYCODE_BUTTON_X, false);
        mark(canvas, 622, 160, KeyEvent.KEYCODE_BUTTON_Y, false);
        mark(canvas, 91, 227, KeyEvent.KEYCODE_DPAD_UP, hatY < -.5f);
        mark(canvas, 91, 275, KeyEvent.KEYCODE_DPAD_DOWN, hatY > .5f);
        mark(canvas, 67, 251, KeyEvent.KEYCODE_DPAD_LEFT, hatX < -.5f);
        mark(canvas, 115, 251, KeyEvent.KEYCODE_DPAD_RIGHT, hatX > .5f);
        mark(canvas, 122, 107, KeyEvent.KEYCODE_BUTTON_SELECT, false);
        mark(canvas, 616, 109, KeyEvent.KEYCODE_BUTTON_START, false);
        mark(canvas, 118, 310, KeyEvent.KEYCODE_HOME, down(KeyEvent.KEYCODE_MOVE_HOME));
        mark(canvas, 619, 312, KeyEvent.KEYCODE_BACK, false);
        stick(canvas, RemapDiagram.LEFT_STICK_X, artworkTop + RemapDiagram.LEFT_STICK_Y,
                lx, ly, KeyEvent.KEYCODE_BUTTON_THUMBL);
        stick(canvas, RemapDiagram.RIGHT_STICK_X, artworkTop + RemapDiagram.RIGHT_STICK_Y,
                rx, ry, KeyEvent.KEYCODE_BUTTON_THUMBR);

        // Stock M1/M2 send C/Z by default. The center labels represent outputs.
        badge(canvas, 250, 209, "C", down(KeyEvent.KEYCODE_BUTTON_C));
        badge(canvas, 365, 209, "Guide", down(KeyEvent.KEYCODE_BUTTON_MODE));
        badge(canvas, 480, 209, "Z", down(KeyEvent.KEYCODE_BUTTON_Z));
        text(canvas, getContext().getString(R.string.remap_test_last,
                lastKey == KeyEvent.KEYCODE_UNKNOWN ? "—" : keyLabel.apply(lastKey)), 368, 395, 23);

        String stickValues = getContext().getString(R.string.remap_test_sticks,
                Math.round(lx * 100), Math.round(ly * 100), Math.round(rx * 100), Math.round(ry * 100));
        text(canvas, stickValues, 368, 429, 19);
        canvas.restore();
    }

    private boolean down(int key) { return pressed.get(key); }

    private int trigger(float value, int key) {
        return Math.round((value > 0 ? value : down(key) ? 1 : 0) * 100);
    }

    private void mark(Canvas canvas, float x, float y, int key, boolean analogPressed) {
        if (!down(key) && !analogPressed) return;
        paint.setColor(0xaaf9d455);
        if (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT) {
            float angle = key == KeyEvent.KEYCODE_DPAD_DOWN ? 180
                    : key == KeyEvent.KEYCODE_DPAD_LEFT ? -90
                    : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 90 : 0;
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(angle);
            canvas.drawPath(directionHighlight, paint);
            canvas.restore();
        } else {
            boolean face = key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_BUTTON_B
                    || key == KeyEvent.KEYCODE_BUTTON_X || key == KeyEvent.KEYCODE_BUTTON_Y;
            canvas.drawCircle(x, y, face ? 14 : 8, paint);
        }
    }

    private void stick(Canvas canvas, int x, int y, float dx, float dy, int key) {
        // Limit radial travel so the cap stays inside the ring even on diagonals.
        float travel = 13f / Math.max(1f, (float) Math.hypot(dx, dy));
        float capX = x + travel * dx;
        float capY = y + travel * dy;
        paint.setColor(0xff26212b);
        canvas.drawCircle(x, y, 30, paint);
        paint.setColor(accent);
        canvas.drawCircle(capX, capY, 22, paint);
        if (console != null) {
            Rect crop = key == KeyEvent.KEYCODE_BUTTON_THUMBL
                    ? new Rect(59, 72, 109, 122) : new Rect(625, 170, 677, 222);
            paint.setColor(0xffffffff);
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(capX, capY, 19, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.drawBitmap(console, crop, new RectF(capX - 19, capY - 19, capX + 19, capY + 19), paint);
            canvas.restore();
        }
        if (down(key)) {
            paint.setColor(0xaaf9d455);
            canvas.drawCircle(capX, capY, 19, paint);
        }
    }

    private void text(Canvas canvas, String label, float x, float baseline, float size) {
        paint.setColor(textColor);
        paint.setTextSize(size);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, x, baseline, paint);
    }

    private void badge(Canvas canvas, int x, int y, String label, boolean held) {
        paint.setColor(held ? PRESS_COLOR : 0xff504957);
        canvas.drawRoundRect(x - 38, y - 16, x + 38, y + 16, 9, 9, paint);
        paint.setColor(held ? 0xff201825 : 0xfffcf7ff);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(16);
        canvas.drawText(label, x, y + 5, paint);
    }
}

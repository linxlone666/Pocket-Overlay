package app.pocketoverlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;

/** Controller artwork for a single button, a remap, or a shortcut combo. */
final class RemapButtonArt extends Drawable {
    private final Bitmap console;
    private final int source;
    private final boolean rear;
    private final int output;
    private final String sourceLabel;
    private final String outputLabel;
    private final int accent;
    private final boolean combo;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    RemapButtonArt(Bitmap console, int source, boolean rear, String sourceLabel,
                   int output, String outputLabel, int accent) {
        this(console, source, rear, sourceLabel, output, outputLabel, accent, false);
    }

    RemapButtonArt(Bitmap console, int source, boolean rear, String sourceLabel,
                   int output, String outputLabel, int accent, boolean combo) {
        this.console = console;
        this.source = source;
        this.rear = rear;
        this.sourceLabel = sourceLabel;
        this.output = output;
        this.outputLabel = outputLabel;
        this.accent = accent;
        this.combo = combo;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        boolean paired = output != KeyEvent.KEYCODE_UNKNOWN;
        canvas.scale(bounds.width() / (paired ? 80f : 28f), bounds.height() / 28f);
        if (!paired) {
            drawButton(canvas, 14, source, rear, sourceLabel);
        } else {
            drawButton(canvas, 14, source, rear, sourceLabel);
            paint.setColor(accent);
            paint.setStrokeWidth(1.5f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(34, 14, 46, 14, paint);
            if (combo) {
                canvas.drawLine(40, 9, 40, 19, paint);
            } else {
                canvas.drawLine(42, 10, 46, 14, paint);
                canvas.drawLine(42, 18, 46, 14, paint);
            }
            drawButton(canvas, 66, output, false, outputLabel);
        }
        canvas.restore();
    }

    private void drawButton(Canvas canvas, int x, int key, boolean rear, String label) {
        Rect crop = rear ? null : cropFor(key);
        if (crop != null && console != null) {
            paint.setColor(0xffffffff);
            canvas.save();
            Path edge = new Path();
            edge.addCircle(x, 14, 13, Path.Direction.CW);
            canvas.clipPath(edge);
            canvas.drawBitmap(console, crop, new RectF(x - 13, 1, x + 13, 27), paint);
            canvas.restore();
            return;
        }

        boolean direction = key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT;
        paint.setColor(0xff8b8798);
        canvas.drawRoundRect(x - 14, 4, x + 14, 25, direction ? 5 : 8, 7, paint);
        paint.setColor(0xff514d5c);
        canvas.drawRoundRect(x - 13, 5, x + 13, 23, direction ? 4 : 7, 6, paint);
        paint.setColor(0xfffaf7ff);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(11);
        if (direction) {
            canvas.save();
            float angle = key == KeyEvent.KEYCODE_DPAD_DOWN ? 180
                    : key == KeyEvent.KEYCODE_DPAD_LEFT ? -90
                    : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 90 : 0;
            canvas.rotate(angle, x, 14);
            paint.setStrokeWidth(1.6f);
            canvas.drawLine(x - 4, 16, x, 12, paint);
            canvas.drawLine(x, 12, x + 4, 16, paint);
            canvas.restore();
        } else {
            paint.setTextSize(label.length() > 3 ? 7 : 11);
            canvas.drawText(label, x, 14 - (paint.ascent() + paint.descent()) / 2, paint);
        }
    }

    // Coordinates refer to the shared 736 × 312 RP6 front artwork.
    private static Rect cropFor(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A: return new Rect(665, 87, 695, 117);
            case KeyEvent.KEYCODE_BUTTON_B: return new Rect(637, 117, 667, 147);
            case KeyEvent.KEYCODE_BUTTON_X: return new Rect(636, 58, 666, 88);
            case KeyEvent.KEYCODE_BUTTON_Y: return new Rect(607, 87, 637, 117);
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return new Rect(59, 72, 109, 122);
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return new Rect(625, 170, 677, 222);
            default: return null;
        }
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

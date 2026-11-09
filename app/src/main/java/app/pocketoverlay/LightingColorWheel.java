package app.pocketoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.IntConsumer;

/** Hue around the wheel, saturation from the center to the edge. */
final class LightingColorWheel extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = {0, 0, 1};
    private final IntConsumer onColor;
    private Shader hue;
    private Shader saturation;
    private float radius;

    LightingColorWheel(Context context, IntConsumer onColor) {
        super(context);
        this.onColor = onColor;
        setFocusableInTouchMode(true);
        setContentDescription(context.getString(R.string.navigation_lighting_wheel));
    }

    void setColor(int color) {
        Color.colorToHSV(color, hsv);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        radius = Math.max(1, Math.min(width, height) / 2f - dp(10));
        hue = new SweepGradient(width / 2f, height / 2f, new int[]{
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        }, null);
        saturation = new RadialGradient(width / 2f, height / 2f, radius,
                Color.WHITE, 0x00ffffff, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = getWidth() / 2f;
        float y = getHeight() / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(hue);
        canvas.drawCircle(x, y, radius, paint);
        paint.setShader(saturation);
        canvas.drawCircle(x, y, radius, paint);
        paint.setShader(null);
        if (isFocused()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, radius + dp(4), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        double angle = Math.toRadians(hsv[0]);
        float markerX = x + (float) Math.cos(angle) * hsv[1] * radius;
        float markerY = y + (float) Math.sin(angle) * hsv[1] * radius;
        paint.setColor(Color.BLACK);
        canvas.drawCircle(markerX, markerY, dp(9), paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(markerX, markerY, dp(7), paint);
        paint.setColor(Color.HSVToColor(hsv));
        canvas.drawCircle(markerX, markerY, dp(5), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                break;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
        float x = event.getX() - getWidth() / 2f;
        float y = event.getY() - getHeight() / 2f;
        hsv[0] = ((float) Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
        hsv[1] = Math.min(1f, (float) Math.hypot(x, y) / radius);
        onColor.accept(Color.HSVToColor(hsv));
        invalidate();
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            getParent().requestDisallowInterceptTouchEvent(false);
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            hsv[0] = (hsv[0] + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? 355 : 5)) % 360;
            if (hsv[1] == 0f) hsv[1] = 1f;
            onColor.accept(Color.HSVToColor(hsv));
            invalidate();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}

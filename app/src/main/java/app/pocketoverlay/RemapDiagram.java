package app.pocketoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

import app.pocketoverlay.OverlaySettings.Binding;

final class RemapDiagram extends LinearLayout {
    private final LinearLayout shoulders;
    private final LinearLayout body;
    private final ImageView console;
    private final LinearLayout left;
    private final LinearLayout right;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path trigger = new Path();
    private final Path bumper = new Path();
    private final Paint plastic = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences settings;
    private final Map<Button, IntSupplier> boxes = new LinkedHashMap<>();

    RemapDiagram(Context context, SharedPreferences settings, int color) {
        super(context);
        this.settings = settings;
        setOrientation(VERTICAL);
        shoulders = new LinearLayout(context);
        addView(shoulders, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(new Space(context), new LayoutParams(LayoutParams.MATCH_PARENT, dp(66)));
        trigger.moveTo(0, 32);
        trigger.cubicTo(3, 13, 8, 1, 26, 0);
        trigger.quadTo(33, 0, 33, 6);
        trigger.lineTo(33, 28);
        trigger.close();

        bumper.moveTo(0, 16);
        bumper.quadTo(3, 8, 10, 6);
        bumper.quadTo(27, 0, 39, 0);
        bumper.quadTo(46, 0, 53, 6);
        bumper.lineTo(48, 17);
        bumper.quadTo(23, 10, 0, 16);
        bumper.close();

        body = new LinearLayout(context);
        body.setGravity(Gravity.CENTER_VERTICAL);
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        ink.setColor(color);
        ink.setStrokeWidth(dp(1.5f));
        left = addColumn();
        console = new ImageView(context);
        console.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LayoutParams picture = new LayoutParams(0, dp(156), 1);
        picture.setMargins(dp(12), dp(12), dp(12), 0);
        body.addView(console, picture);
        right = addColumn();
    }

    private LinearLayout addColumn() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        body.addView(column, new LayoutParams(dp(130), LayoutParams.WRAP_CONTENT));
        return column;
    }

    void setConsoleImage(int drawable) {
        console.setImageResource(drawable);
        invalidate();
    }

    void addBinding(Binding binding, Button button, boolean leftSide) {
        addCallout(button, leftSide, () -> binding.key(settings));
    }

    void addSystemButton(Button button, boolean leftSide, int key) {
        addCallout(button, leftSide, () -> key);
    }

    void addShoulder(Button button, int key) {
        LayoutParams layout = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
        if (shoulders.getChildCount() > 0) layout.setMarginStart(dp(8));
        shoulders.addView(button, layout);
        boxes.put(button, () -> key);
    }

    private void addCallout(Button button, boolean leftSide, IntSupplier key) {
        LinearLayout column = leftSide ? left : right;
        LayoutParams layout = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        if (column.getChildCount() > 0) layout.topMargin = dp(8);
        column.addView(button, layout);
        boxes.put(button, key);
    }

    @Override
    protected void onMeasure(int width, int height) {
        if (MeasureSpec.getMode(width) == MeasureSpec.UNSPECIFIED) {
            width = MeasureSpec.makeMeasureSpec(dp(565), MeasureSpec.EXACTLY);
        }
        super.onMeasure(width, height);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        float scale = Math.min(console.getWidth() / 736f, console.getHeight() / 312f);
        float imageX = console.getLeft() + (console.getWidth() - 736 * scale) / 2f;
        float imageY = body.getTop() + console.getTop() + (console.getHeight() - 312 * scale) / 2f;
        float imageWidth = 736 * scale;
        float leftTrigger = imageX + imageWidth * 0.16f;
        float rightTrigger = imageX + imageWidth * 0.84f;
        float leftBumper = imageX + imageWidth * 0.095f;
        float rightBumper = imageX + imageWidth * 0.905f;
        float triggerTop = shoulders.getBottom() + dp(12);
        float bumperTop = triggerTop + dp(34);

        drawShoulder(canvas, trigger, leftTrigger, triggerTop, 33, false);
        drawShoulder(canvas, trigger, rightTrigger, triggerTop, 33, true);
        drawShoulder(canvas, bumper, leftBumper, bumperTop, 53, false);
        drawShoulder(canvas, bumper, rightBumper, bumperTop, 53, true);

        for (Map.Entry<Button, IntSupplier> entry : boxes.entrySet()) {
            int key = entry.getValue().getAsInt();
            float[] anchor = anchorFor(key);
            if (anchor == null) continue;
            Button box = entry.getKey();
            LinearLayout column = (LinearLayout) box.getParent();
            float x = imageX + (anchor[0] - 62) * scale;
            float y = imageY + (anchor[1] - 129) * scale;
            line.reset();
            if (column == shoulders) {
                switch (key) {
                    case KeyEvent.KEYCODE_BUTTON_L1: x = leftBumper; y = bumperTop + dp(8); break;
                    case KeyEvent.KEYCODE_BUTTON_L2: x = leftTrigger; y = triggerTop + dp(17); break;
                    case KeyEvent.KEYCODE_BUTTON_R1: x = rightBumper; y = bumperTop + dp(8); break;
                    case KeyEvent.KEYCODE_BUTTON_R2: x = rightTrigger; y = triggerTop + dp(17); break;
                    default: break;
                }
                float center = box.getLeft() + box.getWidth() / 2f;
                line.moveTo(center, shoulders.getBottom());
                line.lineTo(center, shoulders.getBottom() + dp(5));
            } else {
                boolean leftSide = column == left;
                float edge = leftSide ? column.getRight() : column.getLeft();
                float boxY = body.getTop() + column.getTop() + box.getTop() + box.getHeight() / 2f;
                line.moveTo(edge, boxY);
                line.lineTo(edge + dp(leftSide ? 8 : -8), boxY);
            }
            line.lineTo(x, y);
            ink.setStyle(Paint.Style.STROKE);
            canvas.drawPath(line, ink);
            ink.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, dp(3), ink);
        }
    }

    private void drawShoulder(Canvas canvas, Path shape, float center, float top,
                              int width, boolean mirror) {
        float density = getResources().getDisplayMetrics().density;
        canvas.save();
        canvas.translate(center + dp(width) * (mirror ? 0.5f : -0.5f), top);
        canvas.scale(mirror ? -density : density, density);
        plastic.setStyle(Paint.Style.FILL);
        plastic.setColor(shape == trigger ? 0xffeae8e7 : 0xffd4d1d4);
        canvas.drawPath(shape, plastic);
        plastic.setStyle(Paint.Style.STROKE);
        plastic.setStrokeWidth(1);
        plastic.setColor(0xff87838d);
        canvas.drawPath(shape, plastic);
        canvas.restore();
    }

    private static float[] anchorFor(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_L1: return new float[]{111, 173};
            case KeyEvent.KEYCODE_BUTTON_L2: return new float[]{163, 147};
            case KeyEvent.KEYCODE_BUTTON_R1: return new float[]{747, 176};
            case KeyEvent.KEYCODE_BUTTON_R2: return new float[]{692, 150};
            case KeyEvent.KEYCODE_BUTTON_A: return new float[]{742, 231};
            case KeyEvent.KEYCODE_BUTTON_B: return new float[]{714, 261};
            case KeyEvent.KEYCODE_BUTTON_X: return new float[]{713, 202};
            case KeyEvent.KEYCODE_BUTTON_Y: return new float[]{684, 231};
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return new float[]{146, 226};
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return new float[]{712, 324};
            case KeyEvent.KEYCODE_DPAD_UP: return new float[]{153, 298};
            case KeyEvent.KEYCODE_DPAD_DOWN: return new float[]{153, 345};
            case KeyEvent.KEYCODE_DPAD_LEFT: return new float[]{129, 322};
            case KeyEvent.KEYCODE_DPAD_RIGHT: return new float[]{177, 322};
            case KeyEvent.KEYCODE_HOME: return new float[]{180, 381};
            case KeyEvent.KEYCODE_BACK: return new float[]{681, 383};
            default: return null;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

package app.pocketoverlay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

final class UiMotion {
    static final Interpolator ENTER = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final Interpolator EXIT = new PathInterpolator(0.4f, 0f, 1f, 1f);

    private UiMotion() {}

    static boolean enabled(Context context) {
        return ValueAnimator.areAnimatorsEnabled()
                && OverlaySettings.open(context).getBoolean(OverlaySettings.UI_MOTION, true);
    }

    static void reveal(View view, float startAlpha, int travelDp, long duration) {
        view.animate().cancel();
        if (!enabled(view.getContext())) {
            view.setAlpha(1f);
            view.setTranslationY(0f);
            return;
        }
        view.setAlpha(startAlpha);
        view.setTranslationY(travelDp * view.getResources().getDisplayMetrics().density);
        view.animate().alpha(1f).translationY(0f).setDuration(duration).setInterpolator(ENTER).start();
    }
}

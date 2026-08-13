package app.morphe.extension.shared.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.view.View;

/**
 * Utility methods for animating view visibility transitions (fade in/out with VISIBLE/GONE).
 */
@SuppressWarnings("unused")
public final class ViewAnimations {
    private ViewAnimations() {}

    /**
     * Fades the view in (alpha 0→1) and sets it to {@link View#VISIBLE}.
     * No-op if the view is already fully visible.
     */
    public static void fadeIn(View view, long duration) {
        view.animate().cancel();
        if (view.getVisibility() == View.VISIBLE && view.getAlpha() == 1f) return;
        if (view.getVisibility() != View.VISIBLE) {
            view.setAlpha(0f);
            view.setVisibility(View.VISIBLE);
        }
        view.animate().alpha(1f).setDuration(duration).setListener(null).start();
    }

    /**
     * Fades the view out (alpha 1→0) then sets it to {@link View#GONE}.
     * Resets alpha to 1 after hiding so the next {@link #fadeIn} starts clean.
     */
    public static void fadeOut(View view, long duration) {
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(duration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.setVisibility(View.GONE);
                        view.setAlpha(1f);
                        view.animate().setListener(null);
                    }
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        view.animate().setListener(null);
                    }
                })
                .start();
    }

    /** How far a view shrinks while held. */
    private static final float PRESSED_SCALE = 0.95f;

    private static final long PRESS_DURATION_MILLISECONDS = 50;

    /**
     * Makes a view press in while held, the way the app animates its own buttons.
     *
     * <p>Uses a state list animator rather than a touch listener, so the effect follows
     * the pressed state and never swallows clicks.
     */
    public static void applyPressEffect(View view) {
        StateListAnimator animator = new StateListAnimator();
        animator.addState(new int[]{android.R.attr.state_pressed}, scaleAnimator(PRESSED_SCALE));
        animator.addState(new int[0], scaleAnimator(1f));
        view.setStateListAnimator(animator);
    }

    /**
     * Animates both scale axes together, since a state list animator drives one
     * animator per state.
     */
    private static Animator scaleAnimator(float scale) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(null, View.SCALE_X, scale),
                ObjectAnimator.ofFloat(null, View.SCALE_Y, scale));
        set.setDuration(PRESS_DURATION_MILLISECONDS);
        return set;
    }
}

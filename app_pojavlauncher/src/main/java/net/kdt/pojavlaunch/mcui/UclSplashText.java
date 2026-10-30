package net.kdt.pojavlaunch.mcui;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * The tilting splash line beside the logo, with the breath it always should have
 * had.
 *
 * <p>The reference artwork answered three questions at once -- colour, tilt and
 * shadow -- and the answers are kept here so the text replaces the bitmap
 * without redrawing the design: vanilla yellow {@code #FFFF55}, a 5 degree
 * counter-clockwise tilt (measured off the original 1140x228 bitmap, whose ink
 * centroids fall 5.01 degrees to the right), and a shadow in the bitmap's own
 * shadow colour {@code #795A00} offset down-right with no blur, because a
 * blurred shadow would read as modern and the rest of the launcher does not.
 *
 * <p>The breath is a plain 0.8 -> 1.2 scale loop. It is deliberately crude:
 * vanilla's splash pulses without easing through exotic keyframes, and a
 * two-key loop is the honest version of "one big, one small".
 *
 * <p>The animation belongs to the view, not to the activity: it starts when the
 * view is attached and stops when it is detached, so switching pages or leaving
 * the launcher cannot leave a ticking animator behind.
 */
public class UclSplashText extends TextView {
    /** One direction of the breath. A full in-and-out cycle is twice this. */
    private static final long BREATH_MS = 900L;
    private static final float BREATH_SMALL = 0.8f;
    private static final float BREATH_LARGE = 1.2f;
    /** Measured off the reference bitmap: its ink centroids drop 5.01 degrees. */
    private static final float TILT_DEGREES = -5f;
    private static final int INK_COLOR = 0xFFFF55;
    private static final int SHADOW_COLOR = 0xFF795A00;

    private ObjectAnimator mBreath;

    public UclSplashText(Context context) {
        this(context, null);
    }

    public UclSplashText(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setGravity(Gravity.CENTER);
        /* the ink, not the line box, is what the eye centres on a tilted label */
        setIncludeFontPadding(false);
        setTextColor(INK_COLOR);
        setTypeface(UclTypeface.bold(context));
        setRotation(TILT_DEGREES);
        float density = getResources().getDisplayMetrics().density;
        float shadow = Math.max(2f, 1.5f * density);
        /* radius 1, not 0: a zero radius is treated as "no shadow" by some
         * render paths, and 1 px of blur is invisible on a 34 px line -- what
         * the eye sees is still the bitmap's hard offset copy */
        setShadowLayer(1f, shadow, shadow, SHADOW_COLOR);
        /* room for the shadow, otherwise the tilted block edge clips it */
        int pad = Math.round(shadow) + 2;
        setPadding(pad, pad, pad, pad);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startBreathing();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopBreathing();
        super.onDetachedFromWindow();
    }

    private void startBreathing() {
        if (mBreath != null) return;
        mBreath = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(View.SCALE_X, BREATH_SMALL, BREATH_LARGE),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, BREATH_SMALL, BREATH_LARGE));
        mBreath.setDuration(BREATH_MS);
        mBreath.setInterpolator(new AccelerateDecelerateInterpolator());
        mBreath.setRepeatCount(ValueAnimator.INFINITE);
        mBreath.setRepeatMode(ValueAnimator.REVERSE);
        mBreath.start();
    }

    private void stopBreathing() {
        if (mBreath == null) return;
        mBreath.cancel();
        mBreath = null;
        /* the loop always ends mid-breath, so the label must not stay shrunk */
        setScaleX(1f);
        setScaleY(1f);
    }
}

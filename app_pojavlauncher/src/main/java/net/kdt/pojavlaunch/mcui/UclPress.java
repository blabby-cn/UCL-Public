package net.kdt.pojavlaunch.mcui;

import android.graphics.Canvas;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * How far a key's label travels when the key is held down.
 *
 * <p>The key texture already has the travel built into it: the pressed slab of
 * button_light.png keeps the same twenty rows as the normal one but leaves its
 * first two transparent, so the plate's top edge drops two rows and its bottom
 * bevel is cut away. The label did not follow, which is what made a held key
 * read as "the plate got shorter" instead of "the key went down".
 *
 * <p>Two rows of the texture is two density independent pixels: the slabs sit
 * in res/drawable, so every pixel of them is one mdpi pixel and the framework
 * scales the whole tile by the device density before stretching it.
 *
 * <p>The offset is not stored and there is no animator: it is read from the
 * live drawable state inside {@code onDraw}, which is the same pass that draws
 * the plate. Storing it from a state callback and animating it back were what
 * made the label trail the plate -- the callback can fire a frame apart and the
 * spring took 170ms to finish while the plate had already snapped. Reading the
 * state at draw time makes the two impossible to separate: one pass, one state,
 * one frame.
 */
public final class UclPress {
    /** The two transparent rows at the top of the pressed slab. */
    private static final float TRAVEL_DP = 2f;

    private final View mView;
    private final float mTravel;
    /* the green MineButton plate changes colour when pressed but never moves,
     * so only the keys wearing the light plate switch this on */
    private boolean mTravels = true;

    public UclPress(View view) {
        mView = view;
        mTravel = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                TRAVEL_DP, view.getResources().getDisplayMetrics());
    }

    /** Whether the view is in the state the plate draws as pressed. */
    public static boolean pressed(View view) {
        for (int state : view.getDrawableState()) {
            if (state == android.R.attr.state_pressed) return true;
        }
        return false;
    }

    /** Turns the travel on or off for keys whose plate does not move. */
    public void setTravels(boolean travels) {
        if (mTravels == travels) return;
        mTravels = travels;
        mView.invalidate();
    }

    /** How far the label is drawn below its resting place this frame. */
    public float offset() {
        return mTravels && pressed(mView) ? mTravel : 0f;
    }

    /**
     * A key built in code -- the dialog keys -- rather than from a layout.
     * Layout keys extend UclButton or MineButton and wire this themselves.
     */
    public static class Text extends TextView {
        private final UclPress mPress = new UclPress(this);

        public Text(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mPress == null) {
                super.onDraw(canvas);
                return;
            }
            float offset = mPress.offset();
            if (offset == 0f) {
                super.onDraw(canvas);
                return;
            }
            int save = canvas.save();
            canvas.translate(0f, offset);
            super.onDraw(canvas);
            canvas.restoreToCount(save);
        }
    }
}

package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A row whose contents go down when the row is held.
 *
 * <p>Every pressable thing in this shell travels two pixels when it is pressed -
 * the plate has the travel built into its pressed slab, and {@link UclPress}
 * moves the label with it. A row built out of several views (a name over its
 * current value, a title beside a key) cannot use either of those on its own:
 * they shift one view, and here the view is a container.
 *
 * <p>So the shift is applied where the children are drawn. The background is
 * painted before {@link #dispatchDraw}, which means the plate stays put while
 * everything on top of it goes down - which is what a pressed key looks like.
 */
public class UclPressRow extends LinearLayout {

    private final UclPress mPress = new UclPress(this);

    public UclPressRow(@NonNull Context context) {
        super(context);
    }

    public UclPressRow(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        float shift = mPress == null ? 0f : mPress.offset();
        if (shift == 0f) {
            super.dispatchDraw(canvas);
            return;
        }
        int save = canvas.save();
        canvas.translate(0f, shift);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }
}

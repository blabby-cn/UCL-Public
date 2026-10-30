package net.kdt.pojavlaunch.mcui;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

/**
 * Turns off bitmap filtering for the launcher chrome.
 *
 * Every UI bitmap in UCL (9-patch button plates, panel frames, 64px icons,
 * cropped skin heads) is drawn in its own pixel grid, so the default bilinear
 * sampler only softens the edges. The launcher wants the same hard pixels the
 * title bar texture already has, hence nearest neighbour everywhere.
 *
 * The settings / download plates in res/drawable-xxhdpi are 1:1 crops of the
 * reference screenshot (one reference pixel = one texture pixel), so on a 3x
 * screen they land on exactly the measured pixels; on any other density the
 * framework scales them, and nearest neighbour is what keeps a 1px frame a
 * 1px frame instead of a two pixel grey smear.
 */
public final class UclDrawables {
    private UclDrawables() {
    }

    /**
     * Turns filtering on for one drawable.
     *
     * <p>Everything in {@link #nearestNeighbour} exists because the launcher's
     * art is pixel art. The language gems are not: they are polygons, and a
     * polygon drawn on the nearest-neighbour grid is a staircase. So a view
     * that carries smooth art is marked with {@link #markSmooth} and this pass
     * leaves its subtree alone - and switches the bitmaps in it back on, since
     * a marked view may be reached after an earlier pass.
     */
    public static void smooth(Drawable drawable) {
        if (drawable == null) return;
        if (drawable instanceof NinePatchDrawable) {
            NinePatchDrawable ninePatch = (NinePatchDrawable) drawable;
            ninePatch.getPaint().setFilterBitmap(true);
            ninePatch.getPaint().setAntiAlias(true);
        } else if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmap = (BitmapDrawable) drawable;
            bitmap.getPaint().setFilterBitmap(true);
            bitmap.getPaint().setAntiAlias(true);
        } else if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                smooth(layers.getDrawable(i));
            }
        } else if (drawable instanceof InsetDrawable) {
            smooth(((InsetDrawable) drawable).getDrawable());
        } else if (drawable instanceof DrawableContainer) {
            DrawableContainer.DrawableContainerState state =
                    (DrawableContainer.DrawableContainerState) drawable.getConstantState();
            if (state != null) {
                for (Drawable child : state.getChildren()) smooth(child);
            }
        }
    }

    /**
     * Marks a view as carrying smooth art and applies it now.
     *
     * <p>The tag is what survives the later tree pass: the pass runs on the
     * whole screen after a page is built, and without the mark it would switch
     * these bitmaps back to nearest neighbour.
     */
    public static void markSmooth(View view) {
        if (view == null) return;
        view.setTag(R.id.ucl_smooth, Boolean.TRUE);
        smoothTree(view);
    }

    /** Switches every bitmap in a subtree to filtered drawing. */
    private static void smoothTree(View view) {
        if (view == null) return;
        smooth(view.getBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) smooth(view.getForeground());
        if (view instanceof ImageView) smooth(((ImageView) view).getDrawable());
        if (view instanceof CompoundButton) smooth(((CompoundButton) view).getButtonDrawable());
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            for (Drawable part : text.getCompoundDrawables()) smooth(part);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                for (Drawable part : text.getCompoundDrawablesRelative()) smooth(part);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                smoothTree(group.getChildAt(i));
            }
        }
    }

    /** Applies nearest neighbour to every bitmap layer of one drawable. */
    public static void nearestNeighbour(Drawable drawable) {
        if (drawable == null) return;
        if (drawable instanceof NinePatchDrawable) {
            NinePatchDrawable ninePatch = (NinePatchDrawable) drawable;
            ninePatch.getPaint().setFilterBitmap(false);
            ninePatch.getPaint().setAntiAlias(false);
        } else if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmap = (BitmapDrawable) drawable;
            bitmap.getPaint().setFilterBitmap(false);
            bitmap.getPaint().setAntiAlias(false);
        } else if (drawable instanceof LayerDrawable) {
            /* also covers RippleDrawable: its content and mask are layers */
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                nearestNeighbour(layers.getDrawable(i));
            }
        } else if (drawable instanceof InsetDrawable) {
            nearestNeighbour(((InsetDrawable) drawable).getDrawable());
        } else if (drawable instanceof DrawableContainer) {
            /* state lists and level lists: the active child changes with the
             * button state, so every child has to be prepared up front */
            DrawableContainer.DrawableContainerState state =
                    (DrawableContainer.DrawableContainerState) drawable.getConstantState();
            if (state != null) {
                for (Drawable child : state.getChildren()) {
                    nearestNeighbour(child);
                }
            }
        }
    }

    /** Applies nearest neighbour to a whole view hierarchy (backgrounds and images). */
    public static void nearestNeighbourTree(View view) {
        if (view == null) return;
        /* a marked subtree is drawn smooth on purpose: see markSmooth() */
        if (view.getTag(R.id.ucl_smooth) != null) {
            smoothTree(view);
            return;
        }
        nearestNeighbour(view.getBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nearestNeighbour(view.getForeground());
        }
        if (view instanceof ImageView) {
            nearestNeighbour(((ImageView) view).getDrawable());
        }
        if (view instanceof CompoundButton) {
            /* switch / checkbox plates are drawn by the button drawable, not
             * by the background */
            nearestNeighbour(((CompoundButton) view).getButtonDrawable());
        }
        if (view instanceof TextView) {
            /* a label can carry an icon or a plate as a compound drawable */
            TextView text = (TextView) view;
            for (Drawable part : text.getCompoundDrawables()) nearestNeighbour(part);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                for (Drawable part : text.getCompoundDrawablesRelative()) {
                    nearestNeighbour(part);
                }
            }
        }
        if (view instanceof SeekBar) {
            /* the groove and its knob live in the progress drawable and the
             * thumb, neither of which is a background */
            SeekBar bar = (SeekBar) view;
            nearestNeighbour(bar.getProgressDrawable());
            nearestNeighbour(bar.getThumb());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                nearestNeighbour(bar.getTickMark());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                nearestNeighbourTree(group.getChildAt(i));
            }
        }
    }
}

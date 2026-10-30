package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The slider's groove: a sunken trough, with the chosen part filled green.
 *
 * <p>Two decisions worth keeping:
 *
 * <p>Written by hand rather than assembled from a layer-list, because a
 * layer-list does not do what the platform theme does with it. The level - the
 * only thing that says how far along the slider is - is set on the root
 * drawable, and a {@code <clip>} nested inside a {@code layer-list} never sees
 * it: the result on a device is a full-width trough and no green at all.
 *
 * <p>Both textures are <em>one</em> art pixel wide and are tiled, not
 * stretched. Tiling at the integer scale is the only way every art pixel stays
 * a square: the bar is uniform along its length, so a single column repeated is
 * the exact shape, and each copy is the same 3x3 (or 4x4) block the artwork was
 * drawn on. Stretching a wider texture across the row - what this used to do -
 * turns each art pixel into a 50x3 sliver, which is what made it look wrong.
 *
 * <p>The fill's right edge snaps to a whole art pixel too: a slider whose green
 * ends halfway through a pixel is off the grid even when the rest is on it.
 */
public class UclSliderTrack extends Drawable {

    private static final int LEVEL_MAX = 10000;

    private final Paint mPaint = new Paint();
    private final BitmapShader mGroove;
    private final BitmapShader mFill;
    private final int mScale;
    private final int mThickness;

    public UclSliderTrack(Context context, int grooveRes, int fillRes) {
        Resources res = context.getResources();
        mScale = UclPixelDrawable.scaleFor(res.getDisplayMetrics().density);
        Bitmap groove = pixel(BitmapFactory.decodeResource(res, grooveRes), mScale);
        Bitmap fill = pixel(BitmapFactory.decodeResource(res, fillRes), mScale);

        mGroove = groove == null ? null
                : new BitmapShader(groove, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
        mFill = fill == null ? null
                : new BitmapShader(fill, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
        mThickness = groove == null ? 0 : groove.getHeight();

        mPaint.setFilterBitmap(false);
        mPaint.setAntiAlias(false);
    }

    /** Scales a column up by whole pixels, filter off: no resampling happens. */
    private static Bitmap pixel(Bitmap source, int scale) {
        if (source == null) return null;
        if (scale == 1) return source;
        return Bitmap.createScaledBitmap(source,
                source.getWidth() * scale, source.getHeight() * scale, false);
    }

    /** The level a seek bar sets for this progress; used to prime ours. */
    public static int levelOf(int progress, int max) {
        return max <= 0 ? 0 : Math.round(progress * (float) LEVEL_MAX / max);
    }

    @Override
    public int getIntrinsicHeight() {
        return mThickness;
    }

    @Override
    public int getIntrinsicWidth() {
        /* no natural width: the seek bar decides how long the groove is */
        return -1;
    }

    @Override
    protected boolean onLevelChange(int level) {
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mGroove == null) return;
        Rect bounds = getBounds();
        int columns = bounds.width() / mScale;
        if (columns <= 0 || mThickness <= 0) return;

        /* whole tiles only: a half column at the right end would be one more
         * pixel off the grid */
        int length = columns * mScale;
        int top = bounds.top + (bounds.height() - mThickness) / 2;

        canvas.save();
        canvas.translate(bounds.left, top);

        mPaint.setShader(mGroove);
        canvas.drawRect(0, 0, length, mThickness, mPaint);

        int filled = Math.round(columns * (getLevel() / (float) LEVEL_MAX));
        if (filled > 0 && mFill != null) {
            mPaint.setShader(mFill);
            canvas.drawRect(0, 0, filled * mScale, mThickness, mPaint);
        }

        mPaint.setShader(null);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

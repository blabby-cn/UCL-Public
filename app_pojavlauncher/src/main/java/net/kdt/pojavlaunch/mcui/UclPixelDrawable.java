package net.kdt.pojavlaunch.mcui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

/**
 * One of our pixel-art bitmaps, drawn at a whole-number scale.
 *
 * <p>Every piece of this UI that came out of the artwork - the switch, the
 * slider grip, the slider groove - has to be scaled by an integer. A density
 * of 2.75 puts one and a bit art pixels on screen, and half a pixel is what
 * turns pixel art into mush. So the factor is the density rounded, clamped to
 * 2..4: the same rule everywhere, so the pieces keep their relative sizes.
 *
 * <p>The intrinsic size is the bitmap times that factor, which is what a
 * SeekBar asks for when it decides how tall it should be.
 */
public class UclPixelDrawable extends Drawable {

    private final Bitmap mBitmap;
    private final Paint mPaint = new Paint();
    private final Rect mSrc = new Rect();
    private final Rect mDst = new Rect();
    private final int mScale;

    public UclPixelDrawable(Bitmap bitmap, float density) {
        mBitmap = bitmap;
        mScale = scaleFor(density);
        mPaint.setFilterBitmap(false);
        mPaint.setAntiAlias(false);
        if (mBitmap != null) mSrc.set(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
    }

    public UclPixelDrawable(android.content.Context context, int drawableRes) {
        this(BitmapFactory.decodeResource(context.getResources(), drawableRes),
                context.getResources().getDisplayMetrics().density);
    }

    /** The one scaling rule: whole numbers only. */
    public static int scaleFor(float density) {
        return Math.max(2, Math.min(4, Math.round(density)));
    }

    public int scale() {
        return mScale;
    }

    public Bitmap bitmap() {
        return mBitmap;
    }

    @Override
    public int getIntrinsicWidth() {
        return mBitmap == null ? -1 : mBitmap.getWidth() * mScale;
    }

    @Override
    public int getIntrinsicHeight() {
        return mBitmap == null ? -1 : mBitmap.getHeight() * mScale;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mBitmap == null) return;
        Rect bounds = getBounds();
        int w = getIntrinsicWidth();
        int h = getIntrinsicHeight();
        /* centred and never stretched: a caller that hands us a bigger box is
         * asking for placement, not for a resize */
        int left = bounds.left + (bounds.width() - w) / 2;
        int top = bounds.top + (bounds.height() - h) / 2;
        mDst.set(left, top, left + w, top + h);
        canvas.drawBitmap(mBitmap, mSrc, mDst, mPaint);
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

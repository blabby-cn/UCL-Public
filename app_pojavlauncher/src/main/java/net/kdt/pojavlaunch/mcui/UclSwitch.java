package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

/**
 * The settings switch, drawn at the artwork's own resolution.
 *
 * <p>The two bitmaps are hand-drawn pixel art and their grid is the real grid:
 * one pixel of the file is one pixel of the design. Showing them therefore means
 * scaling by a whole number. Scaling by the screen density would not do - a 2.25x
 * display puts one and a quarter art pixels on screen, so every seam lands
 * mid-pixel and the plate goes soft, which is the fake-pixel look the artwork was
 * drawn to avoid. The factor is the density rounded, clamped to 2..4, so the
 * switch stays the same physical size on any phone and never interpolates.
 *
 * <p>Nothing about the size is hardcoded: it is read from the bitmaps. The art is
 * the user's and can be redrawn at another resolution, and a constant here would
 * silently stretch it. Reading it means a redraw at twice the size just shows at
 * half the scale and keeps its pixels intact.
 *
 * <p>There is no animation on purpose: the on and off states are different
 * bitmaps with different geometry (the knob travels, the plate changes colour),
 * and cross-fading two pixel grids would only show both grids at once.
 */
public class UclSwitch extends View {
    private final Bitmap mBitmapOn;
    private final Bitmap mBitmapOff;
    private final Paint mPaint = new Paint();
    private final Rect mSrc = new Rect();
    private final Rect mDst = new Rect();
    private final int mScale;
    private final int mArtW;
    private final int mArtH;

    private boolean mChecked;
    private OnCheckedChangeListener mListener;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(UclSwitch view, boolean checked);
    }

    public UclSwitch(Context context) {
        this(context, null);
    }

    public UclSwitch(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mBitmapOn = BitmapFactory.decodeResource(getResources(), R.drawable.ucl_switch_on);
        mBitmapOff = BitmapFactory.decodeResource(getResources(), R.drawable.ucl_switch_off);
        mArtW = Math.max(widthOf(mBitmapOn), widthOf(mBitmapOff));
        mArtH = Math.max(heightOf(mBitmapOn), heightOf(mBitmapOff));
        float density = getResources().getDisplayMetrics().density;
        mScale = Math.max(2, Math.min(4, Math.round(density)));
        mPaint.setFilterBitmap(false);
        mPaint.setAntiAlias(false);
        setClickable(true);
        setFocusable(true);
    }

    private static int widthOf(Bitmap bitmap) {
        return bitmap == null ? 0 : bitmap.getWidth();
    }

    private static int heightOf(Bitmap bitmap) {
        return bitmap == null ? 0 : bitmap.getHeight();
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        mListener = listener;
    }

    public boolean isChecked() {
        return mChecked;
    }

    /** Sets the state without telling the listener: that is for loading a value. */
    public void setChecked(boolean checked) {
        if (mChecked == checked) return;
        mChecked = checked;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(mArtW * mScale, mArtH * mScale);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap bitmap = mChecked ? mBitmapOn : mBitmapOff;
        if (bitmap == null || mArtW == 0 || mArtH == 0) return;
        mSrc.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        /* dst comes from the bitmap, not from the view: if the two states were
         * ever drawn at different sizes, neither of them gets stretched */
        int w = bitmap.getWidth() * mScale;
        int h = bitmap.getHeight() * mScale;
        int left = (getWidth() - w) / 2;
        int top = (getHeight() - h) / 2;
        mDst.set(left, top, left + w, top + h);
        canvas.drawBitmap(bitmap, mSrc, mDst, mPaint);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        mChecked = !mChecked;
        invalidate();
        if (mListener != null) mListener.onCheckedChanged(this, mChecked);
        return true;
    }
}

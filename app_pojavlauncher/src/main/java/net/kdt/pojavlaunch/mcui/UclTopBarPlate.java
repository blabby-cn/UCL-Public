package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

/**
 * The bar's face, frame and tab seam, painted by hand in whole texels.
 *
 * <p>Everything the bar is made of comes out of one 4x21 slice: columns 0 and 1
 * are the seam (shadow, then catch light), columns 2 and 3 are the face. The
 * bar's height is always a whole number of texels, so a texel is a whole
 * number of pixels and every destination pixel copies one source texel with no
 * arithmetic in between. There is no nine patch here, nothing is stretched,
 * nothing is resampled, and no second view contributes a pixel to the bar.
 *
 * <p>That is the difference from what came before. A nine patch is allowed to
 * interpolate, a density bucket is allowed to resample the artwork before the
 * view ever sees it, and a child view computes its own size in dp. Three
 * scalers meant three rounding errors that could not be reconciled on any
 * density that did not divide evenly; this class has one, and it is exact.
 */
public final class UclTopBarPlate extends View {

    /** The slice is four texels wide and twenty one tall. */
    private static final int TEX_W = 4;
    private static final int TEX_H = 21;
    /** Columns 2 and 3 are the face; 0 and 1 are the seam. */
    private static final int FACE_COL = 2;
    private static final int SEAM_COLS = 2;

    private final int[] mTex = new int[TEX_W * TEX_H];
    private boolean mLoaded;

    private Bitmap mCache;
    private int mCacheW = -1;
    private int mCacheH = -1;
    private int mCacheSeam = -1;

    /** Width of the trailing tab, in whole texels. Zero means "no tab". */
    private int mTabTexels;
    /** Whether the seam is drawn at all. A page without a tab draws none. */
    private boolean mTabVisible = true;

    public UclTopBarPlate(Context context) {
        this(context, null);
    }

    public UclTopBarPlate(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        loadArt();
    }

    private void loadArt() {
        if (mLoaded) return;
        Bitmap art = BitmapFactory.decodeResource(getResources(), R.drawable.ucl_topbar_tab);
        if (art == null) return;
        int w = Math.min(TEX_W, art.getWidth());
        int h = Math.min(TEX_H, art.getHeight());
        int[] all = new int[art.getWidth() * art.getHeight()];
        art.getPixels(all, 0, art.getWidth(), 0, 0, art.getWidth(), art.getHeight());
        for (int row = 0; row < TEX_H; row++) {
            for (int col = 0; col < TEX_W; col++) {
                mTex[row * TEX_W + col] =
                        row < h && col < w ? all[row * art.getWidth() + col] : 0;
            }
        }
        art.recycle();
        mLoaded = true;
    }

    /**
     * Places the seam. {@code texels} is how many texels wide the trailing tab
     * is, and a texel here is exactly as wide as one is tall, so the tab keeps
     * the bar's own scale instead of a dp figure that drifts away from it.
     */
    public void setTabTexels(int texels) {
        texels = Math.max(0, texels);
        if (mTabTexels == texels) return;
        mTabTexels = texels;
        mCache = null;
        invalidate();
    }

    /**
     * Shows or hides the seam without forgetting where it belongs.
     *
     * <p>The tab is a segment of the bar on the pages that have one, and no
     * part of the bar at all on the pages that do not. Keeping the width and
     * gating it here means a page switch costs one flag instead of a re-measure,
     * and a page without a tab leaves no seam behind it.
     */
    public void setTabVisible(boolean visible) {
        if (mTabVisible == visible) return;
        mTabVisible = visible;
        mCache = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || !mLoaded) return;

        int seamX = mTabVisible && mTabTexels > 0
                ? Math.max(0, w - mTabTexels * (h / TEX_H)) : w;
        if (mCache == null || mCache.isRecycled()
                || mCacheW != w || mCacheH != h || mCacheSeam != seamX) {
            mCache = build(w, h, seamX);
            mCacheW = w;
            mCacheH = h;
            mCacheSeam = seamX;
        }
        if (mCache != null) canvas.drawBitmap(mCache, 0f, 0f, null);
    }

    /**
     * One row of source texels, repeated down the bar; one column of face,
     * repeated across it; the seam copied at its own texel width.
     *
     * <p>{@code h} is always {@code texel * 21}, so {@code y * 21 / h} lands on
     * a whole texel boundary every time and no row is ever shared between two
     * texels. Same across: the seam is {@code texel} pixels per column, so its
     * two columns are equal by construction rather than by rounding.
     */
    @Nullable
    private Bitmap build(int w, int h, int seamX) {
        int texel = Math.max(1, h / TEX_H);
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int rowBase = (y * TEX_H / h) * TEX_W;
            int face = mTex[rowBase + FACE_COL];
            int seamA = mTex[rowBase];
            int seamB = mTex[rowBase + 1];
            int base = y * w;
            for (int x = 0; x < w; x++) {
                if (x < seamX) {
                    px[base + x] = face;
                } else if (x < seamX + texel) {
                    px[base + x] = seamA;
                } else if (x < seamX + 2 * texel) {
                    px[base + x] = seamB;
                } else {
                    px[base + x] = face;
                }
            }
        }
        try {
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}

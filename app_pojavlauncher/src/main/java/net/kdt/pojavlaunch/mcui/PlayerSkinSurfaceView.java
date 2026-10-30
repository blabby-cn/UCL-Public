package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

/**
 * Transparent GL surface that renders the player skin model and forwards
 * horizontal drags to the renderer so the user can rotate the character.
 */
public class PlayerSkinSurfaceView extends GLSurfaceView {
    private final PlayerSkinRenderer mRenderer;
    private float mLastX;
    private float mLastY;
    private boolean mDragging;

    public PlayerSkinSurfaceView(Context context) {
        this(context, null);
    }

    public PlayerSkinSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        /* the surface must be translucent so the panorama shows through the
         * character; without TRANSLUCENT the EGL surface drops its alpha
         * channel and renders as a black rectangle. The surface stays behind
         * the window (no setZOrderOnTop): stacking two on-top GL surfaces
         * with the panorama underneath makes the character flicker. View
         * order in the layout puts this surface above the panorama one. */
        getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        setEGLContextClientVersion(2);
        mRenderer = new PlayerSkinRenderer();
        setRenderer(mRenderer);
    }

    public PlayerSkinRenderer getRenderer() {
        return mRenderer;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastX = event.getX();
                mLastY = event.getY();
                mDragging = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mDragging) {
                    float deltaX = event.getX() - mLastX;
                    mLastX = event.getX();
                    mLastY = event.getY();
                    int width = getWidth();
                    if (width > 0) {
                        mRenderer.addRotation(deltaX / width, 0f);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}

package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

/**
 * GL surface that renders the blurred panorama cube and forwards drags
 * to the renderer so the user can look around.
 */
public class PanoramaSurfaceView extends GLSurfaceView {
    private final PanoramaRenderer mRenderer;
    private float mLastX;
    private float mLastY;
    private boolean mDragging;

    public PanoramaSurfaceView(Context context) {
        this(context, null);
    }

    public PanoramaSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        mRenderer = new PanoramaRenderer(context);
        setRenderer(mRenderer);
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
                    float deltaY = event.getY() - mLastY;
                    mLastX = event.getX();
                    mLastY = event.getY();
                    int width = getWidth();
                    int height = getHeight();
                    if (width > 0 && height > 0) {
                        mRenderer.addRotation(deltaX / width, deltaY / height);
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

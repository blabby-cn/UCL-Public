package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Renders the blurred panorama cube used as the UCL home screen background.
 * Six faces: 0 front (-Z, initial view), 1 right (+X), 2 back (+Z), 3 left (-X),
 * 4 top (+Y, sky), 5 bottom (-Y).
 * Slow auto rotation around Y plus drag-driven look-around with clamped pitch.
 *
 * Blur pipeline: the cube is first assembled into a half-resolution offscreen
 * framebuffer (scene pass), then a separable gaussian blur (horizontal pass +
 * vertical pass) is applied to the WHOLE assembled image before it reaches the
 * screen. Because the blur runs on the stitched image rather than on each face
 * bitmap separately, pixels on both sides of every face boundary are filtered
 * together and no seam can appear along the cube edges.
 */
public class PanoramaRenderer implements GLSurfaceView.Renderer {
    private static final String[] PANORAMA_ASSETS = {
            "ucl/panorama/panorama_0.png",
            "ucl/panorama/panorama_1.png",
            "ucl/panorama/panorama_2.png",
            "ucl/panorama/panorama_3.png",
            "ucl/panorama/panorama_4.png",
            "ucl/panorama/panorama_5.png"
    };

    private static final int VERTICES_PER_FACE = 4;
    private static final int FACE_COUNT = 6;
    private static final int VERTEX_STRIDE = 5 * 4;

    /* x, y, z, u, v per vertex, one TRIANGLE_FAN per face */
    private static final float[] CUBE_VERTICES = {
            // 0: front (-Z)
            -1,  1, -1, 0, 0,
             1,  1, -1, 1, 0,
             1, -1, -1, 1, 1,
            -1, -1, -1, 0, 1,
            // 1: right (+X)
             1,  1, -1, 0, 0,
             1,  1,  1, 1, 0,
             1, -1,  1, 1, 1,
             1, -1, -1, 0, 1,
            // 2: back (+Z)
             1,  1,  1, 0, 0,
            -1,  1,  1, 1, 0,
            -1, -1,  1, 1, 1,
             1, -1,  1, 0, 1,
            // 3: left (-X)
            -1,  1,  1, 0, 0,
            -1,  1, -1, 1, 0,
            -1, -1, -1, 1, 1,
            -1, -1,  1, 0, 1,
            // 4: top (+Y)
            -1,  1, -1, 0, 0,
             1,  1, -1, 1, 0,
             1,  1,  1, 1, 1,
            -1,  1,  1, 0, 1,
            // 5: bottom (-Y)
            -1, -1,  1, 0, 0,
             1, -1,  1, 1, 0,
             1, -1, -1, 1, 1,
            -1, -1, -1, 0, 1,
    };

    /* fullscreen triangle strip, position only; texcoord derived in the shader */
    private static final float[] FULLSCREEN_QUAD = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f,
    };

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;" +
            "attribute vec4 aPosition;" +
            "attribute vec2 aTexCoord;" +
            "varying vec2 vTexCoord;" +
            "void main() {" +
            "    gl_Position = uMvp * aPosition;" +
            "    vTexCoord = aTexCoord;" +
            "}";

    /* The whole backdrop is dimmed here rather than with a scrim view on top
     * of it: the character preview is its own SurfaceView, and the window-level
     * scrim never gets drawn over that surface (the punch hole punches through
     * it), which left a 13% brighter rectangle around the model. Baking the
     * dim into the panorama makes the backdrop uniformly dark no matter what
     * is composited on top of it. 0.87 == the #21000000 that used to sit in
     * the layout. */
    private static final float BACKDROP_DIM = 0.87f;

    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
            "uniform sampler2D uTexture;" +
            "uniform float uDim;" +
            "varying vec2 vTexCoord;" +
            "void main() {" +
            "    vec4 color = texture2D(uTexture, vTexCoord);" +
            "    gl_FragColor = vec4(color.rgb * uDim, 1.0);" +
            "}";

    private static final String BLUR_VERTEX_SHADER =
            "attribute vec2 aPosition;" +
            "varying vec2 vTexCoord;" +
            "void main() {" +
            "    vTexCoord = aPosition * 0.5 + 0.5;" +
            "    gl_Position = vec4(aPosition, 0.0, 1.0);" +
            "}";

    /* 9-tap separable gaussian (weights sum to 1 with the mirrored pairs) */
    private static final String BLUR_FRAGMENT_SHADER =
            "precision mediump float;" +
            "uniform sampler2D uTexture;" +
            "uniform vec2 uTexelSize;" +
            "uniform vec2 uDirection;" +
            "uniform float uBlurScale;" +
            "varying vec2 vTexCoord;" +
            "const float W0 = 0.2270270270;" +
            "const float W1 = 0.1945945946;" +
            "const float W2 = 0.1216216216;" +
            "const float W3 = 0.0540540541;" +
            "const float W4 = 0.0162162162;" +
            "void main() {" +
            "    vec2 step = uDirection * uTexelSize * uBlurScale;" +
            "    vec4 sum = texture2D(uTexture, vTexCoord) * W0;" +
            "    sum += texture2D(uTexture, vTexCoord + step) * W1;" +
            "    sum += texture2D(uTexture, vTexCoord - step) * W1;" +
            "    sum += texture2D(uTexture, vTexCoord + step * 2.0) * W2;" +
            "    sum += texture2D(uTexture, vTexCoord - step * 2.0) * W2;" +
            "    sum += texture2D(uTexture, vTexCoord + step * 3.0) * W3;" +
            "    sum += texture2D(uTexture, vTexCoord - step * 3.0) * W3;" +
            "    sum += texture2D(uTexture, vTexCoord + step * 4.0) * W4;" +
            "    sum += texture2D(uTexture, vTexCoord - step * 4.0) * W4;" +
            "    gl_FragColor = sum;" +
            "}";

    private static final float ROTATION_SPEED = 1.0f; /* degrees per second */
    /* The four side faces follow the standard cube-map unfolding convention
     * with the raw bitmaps; the top (4) and bottom (5) faces of this cube map
     * their v axis backwards, so their bitmaps render upside-down. Flip them
     * vertically (up-down mirror) at load time to compensate. Equivalent to
     * remapping v -> 1-v in those two faces' UVs. */
    private static final boolean TOP_BOTTOM_TEXTURE_VERTICAL_FLIP = true;
    private static final float DEFAULT_PITCH = -5.0f;
    private static final float MIN_PITCH = -35.0f;
    private static final float MAX_PITCH = 12.0f;
    private static final float FIELD_OF_VIEW = 90.0f;
    /* Blur runs on a half-resolution offscreen target; the tap spacing is
     * expressed in offscreen texels. 2.0 gives a soft background blur without
     * turning the panorama into flat color. */
    private static final float BLUR_SCALE = 2.0f;

    private final Context mContext;
    private final FloatBuffer mCubeBuffer;
    private final FloatBuffer mQuadBuffer;
    private final int[] mTextures = new int[FACE_COUNT];
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final float[] mMvpMatrix = new float[16];

    private int mSceneProgram;
    private int mMvpHandle;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mTextureHandle;
    private int mDimHandle;

    private int mBlurProgram;
    private int mBlurPositionHandle;
    private int mBlurTextureHandle;
    private int mBlurTexelSizeHandle;
    private int mBlurDirectionHandle;
    private int mBlurScaleHandle;

    private final int[] mFbos = new int[2];
    private final int[] mFboTextures = new int[2];
    private int mFboWidth;
    private int mFboHeight;
    private int mScreenWidth;
    private int mScreenHeight;

    private volatile float mYaw;
    private volatile float mPitch = DEFAULT_PITCH;
    private long mLastFrameTime;

    public PanoramaRenderer(Context context) {
        mContext = context;
        mCubeBuffer = ByteBuffer.allocateDirect(CUBE_VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mCubeBuffer.put(CUBE_VERTICES).position(0);
        mQuadBuffer = ByteBuffer.allocateDirect(FULLSCREEN_QUAD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mQuadBuffer.put(FULLSCREEN_QUAD).position(0);
    }

    /** Rotate the view by drag fractions of the screen size */
    public void addRotation(float yawFraction, float pitchFraction) {
        mYaw += yawFraction * FIELD_OF_VIEW;
        mPitch -= pitchFraction * FIELD_OF_VIEW;
        if (mPitch < MIN_PITCH) mPitch = MIN_PITCH;
        if (mPitch > MAX_PITCH) mPitch = MAX_PITCH;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        mSceneProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        mMvpHandle = GLES20.glGetUniformLocation(mSceneProgram, "uMvp");
        mPositionHandle = GLES20.glGetAttribLocation(mSceneProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mSceneProgram, "aTexCoord");
        mTextureHandle = GLES20.glGetUniformLocation(mSceneProgram, "uTexture");
        mDimHandle = GLES20.glGetUniformLocation(mSceneProgram, "uDim");

        mBlurProgram = buildProgram(BLUR_VERTEX_SHADER, BLUR_FRAGMENT_SHADER);
        mBlurPositionHandle = GLES20.glGetAttribLocation(mBlurProgram, "aPosition");
        mBlurTextureHandle = GLES20.glGetUniformLocation(mBlurProgram, "uTexture");
        mBlurTexelSizeHandle = GLES20.glGetUniformLocation(mBlurProgram, "uTexelSize");
        mBlurDirectionHandle = GLES20.glGetUniformLocation(mBlurProgram, "uDirection");
        mBlurScaleHandle = GLES20.glGetUniformLocation(mBlurProgram, "uBlurScale");

        loadTextures();
        mLastFrameTime = System.currentTimeMillis();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mScreenWidth = width;
        mScreenHeight = height;
        GLES20.glViewport(0, 0, width, height);
        float aspect = (float) width / (float) height;
        Matrix.perspectiveM(mProjectionMatrix, 0, FIELD_OF_VIEW, aspect, 0.1f, 10f);
        createOffscreenTargets();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        updateCamera();

        /* Pass 1: assemble the cube into the first offscreen target */
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFbos[0]);
        GLES20.glViewport(0, 0, mFboWidth, mFboHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawCube();

        /* Pass 2: horizontal blur, target 0 -> target 1 */
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFbos[1]);
        GLES20.glViewport(0, 0, mFboWidth, mFboHeight);
        drawBlurPass(mFboTextures[0], 1f, 0f);

        /* Pass 3: vertical blur, target 1 -> screen */
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, mScreenWidth, mScreenHeight);
        drawBlurPass(mFboTextures[1], 0f, 1f);
    }

    private void updateCamera() {
        long now = System.currentTimeMillis();
        float deltaSeconds = Math.min((now - mLastFrameTime) / 1000f, 0.1f);
        mLastFrameTime = now;
        mYaw += ROTATION_SPEED * deltaSeconds;
        if (mYaw > 360f) mYaw -= 360f;

        double pitchRadians = Math.toRadians(mPitch);
        double yawRadians = Math.toRadians(mYaw);
        float cosPitch = (float) Math.cos(pitchRadians);
        float eyeX = 0f;
        float eyeY = 0f;
        float eyeZ = 0f;
        float centerX = (float) (Math.sin(yawRadians) * cosPitch);
        float centerY = -(float) Math.sin(pitchRadians);
        float centerZ = (float) (-Math.cos(yawRadians) * cosPitch);
        Matrix.setLookAtM(mViewMatrix, 0,
                eyeX, eyeY, eyeZ,
                centerX, centerY, centerZ,
                0f, 1f, 0f);
        Matrix.multiplyMM(mMvpMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);
    }

    private void drawCube() {
        GLES20.glUseProgram(mSceneProgram);
        GLES20.glUniformMatrix4fv(mMvpHandle, 1, false, mMvpMatrix, 0);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);

        mCubeBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, VERTEX_STRIDE, mCubeBuffer);
        mCubeBuffer.position(3);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE, mCubeBuffer);

        GLES20.glUniform1i(mTextureHandle, 0);
        GLES20.glUniform1f(mDimHandle, BACKDROP_DIM);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        for (int face = 0; face < FACE_COUNT; face++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[face]);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, face * VERTICES_PER_FACE, VERTICES_PER_FACE);
        }

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    private void drawBlurPass(int sourceTexture, float directionX, float directionY) {
        GLES20.glUseProgram(mBlurProgram);
        GLES20.glUniform2f(mBlurTexelSizeHandle, 1f / mFboWidth, 1f / mFboHeight);
        GLES20.glUniform2f(mBlurDirectionHandle, directionX, directionY);
        GLES20.glUniform1f(mBlurScaleHandle, BLUR_SCALE);
        GLES20.glUniform1i(mBlurTextureHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture);

        mQuadBuffer.position(0);
        GLES20.glEnableVertexAttribArray(mBlurPositionHandle);
        GLES20.glVertexAttribPointer(mBlurPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mQuadBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(mBlurPositionHandle);
    }

    private void createOffscreenTargets() {
        destroyOffscreenTargets();
        mFboWidth = Math.max(1, mScreenWidth / 2);
        mFboHeight = Math.max(1, mScreenHeight / 2);
        GLES20.glGenFramebuffers(2, mFbos, 0);
        GLES20.glGenTextures(2, mFboTextures, 0);
        for (int i = 0; i < 2; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mFboTextures[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    mFboWidth, mFboHeight, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFbos[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, mFboTextures[i], 0);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void destroyOffscreenTargets() {
        if (mFbos[0] != 0 || mFbos[1] != 0) {
            GLES20.glDeleteFramebuffers(2, mFbos, 0);
            mFbos[0] = 0;
            mFbos[1] = 0;
        }
        if (mFboTextures[0] != 0 || mFboTextures[1] != 0) {
            GLES20.glDeleteTextures(2, mFboTextures, 0);
            mFboTextures[0] = 0;
            mFboTextures[1] = 0;
        }
    }

    private void loadTextures() {
        int[] generated = new int[1];
        for (int face = 0; face < FACE_COUNT; face++) {
            GLES20.glGenTextures(1, generated, 0);
            mTextures[face] = generated[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[face]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            try (InputStream stream = mContext.getAssets().open(PANORAMA_ASSETS[face])) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
                if (bitmap != null) {
                    if ((face == 4 || face == 5) && TOP_BOTTOM_TEXTURE_VERTICAL_FLIP) {
                        android.graphics.Matrix flip = new android.graphics.Matrix();
                        flip.postScale(1f, -1f);
                        Bitmap flipped = Bitmap.createBitmap(bitmap, 0, 0,
                                bitmap.getWidth(), bitmap.getHeight(), flip, true);
                        bitmap.recycle();
                        bitmap = flipped;
                    }
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                    bitmap.recycle();
                }
            } catch (IOException e) {
                // Leave the texture empty; the face renders as the clear color
            }
        }
    }

    private static int buildShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }

    private static int buildProgram(String vertexSource, String fragmentSource) {
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, buildShader(GLES20.GL_VERTEX_SHADER, vertexSource));
        GLES20.glAttachShader(program, buildShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource));
        GLES20.glLinkProgram(program);
        return program;
    }
}

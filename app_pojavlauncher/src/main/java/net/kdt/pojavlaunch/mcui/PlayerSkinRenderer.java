package net.kdt.pojavlaunch.mcui;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Home-screen character preview: a 3D render of the Minecraft player model.
 *
 * <p>Geometry, the box-UV unwrap and the whole GL state set are ported from
 * FoldCraftLauncher's {@code com/mio/skin} package (BoxMesh.kt /
 * PlayerModel.kt / SkinRenderer.kt), which in turn follows skinview3d's
 * PlayerObject, so the result matches what the game itself shows.
 *
 * <p>Conventions that must not be "fixed" without re-reading the sources:
 * <ul>
 *   <li>The player faces +Z and the camera sits on the +Z axis looking at the
 *       origin. Total height is 32 units, feet at y=-16, head top at y=+16.</li>
 *   <li>Box unwrap order per part, with (u, v, w, h, d) in skin pixels:
 *       right (u, v+d), front (u+d, v+d), left (u+w+d, v+d),
 *       back (u+2d+w, v+d), top (u+d, v), bottom (u+w+d, v); each rectangle is
 *       w (or d) wide and h (or d) tall. The second layer keeps the *base*
 *       layer's UV size while its geometry is inflated, exactly like the
 *       original model (hat is 9^3 but still samples the 8^3 layout).</li>
 *   <li>Textures are uploaded through {@link GLUtils#texImage2D} so v=0 is the
 *       first row of the bitmap: <b>no 1-v flip</b>. Flipping it puts the face
 *       on the back of the head, which is the bug this rewrite replaces.</li>
 *   <li>{@code GL_NEAREST} filtering on both min and mag: skins are pixel art
 *       and linear filtering smears them.</li>
 *   <li>Bitmaps arrive premultiplied, so blending is
 *       {@code GL_ONE / GL_ONE_MINUS_SRC_ALPHA}, and texels with alpha below
 *       0.1 are discarded (the old fixed-function {@code glAlphaFunc}).</li>
 *   <li>No per-face shading. The original model has no directional light, and
 *       tinting each face makes the skin colours drift away from the texture.</li>
 * </ul>
 */
public class PlayerSkinRenderer implements GLSurfaceView.Renderer {
    private static final float TEX_WIDTH = 64f;
    private static final float TEX_HEIGHT = 64f;

    /* x, y, z, u, v */
    private static final int FLOATS_PER_VERTEX = 5;
    private static final int VERTEX_STRIDE = FLOATS_PER_VERTEX * 4;
    private static final int VERTICES_PER_BOX = 36;
    private static final int BOXES_PER_PART = 2; /* base layer + second layer */

    private static final float FIELD_OF_VIEW = 50f;
    private static final float CAMERA_ZOOM = 0.9f;
    private static final float NEAR_PLANE = 0.5f;
    private static final float FAR_PLANE = 1500f;

    private static final float AUTO_ROTATION_SPEED = 20.0f; /* degrees per second */
    private static final long AUTO_ROTATION_RESUME_DELAY = 2500; /* ms after a drag */
    private static final float IDLE_SWING_AMPLITUDE = 0.10f; /* radians */
    private static final float IDLE_SWING_SPEED = 1.4f; /* radians per second */

    /** One box: geometry size plus the UV rectangle it samples. */
    private static final class BoxDef {
        final float sizeX, sizeY, sizeZ;
        final float u, v;
        final float uvWidth, uvHeight, uvDepth;

        /** Second layer: geometry inflated, UV still sized by the base layer. */
        BoxDef(float sizeX, float sizeY, float sizeZ,
               float u, float v,
               float uvWidth, float uvHeight, float uvDepth) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.u = u;
            this.v = v;
            this.uvWidth = uvWidth;
            this.uvHeight = uvHeight;
            this.uvDepth = uvDepth;
        }

        BoxDef(float sizeX, float sizeY, float sizeZ, float u, float v) {
            this(sizeX, sizeY, sizeZ, u, v, sizeX, sizeY, sizeZ);
        }
    }

    /**
     * A rotatable part. {@code pos} is the joint the part rotates around,
     * {@code pivot} an extra fixed offset inside the part and {@code offset}
     * the shift of the mesh from that point.
     */
    private static final class Part {
        final float posX, posY, posZ;
        final float pivotX, pivotY, pivotZ;
        final float offsetX, offsetY, offsetZ;
        final boolean arm;
        final BoxDef base;
        final BoxDef overlay;

        int firstVertex;
        int vertexCount;

        Part(float posX, float posY, float posZ,
             float pivotX, float pivotY, float pivotZ,
             float offsetX, float offsetY, float offsetZ,
             boolean arm, BoxDef base, BoxDef overlay) {
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.pivotZ = pivotZ;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.arm = arm;
            this.base = base;
            this.overlay = overlay;
        }
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;" +
            "attribute vec3 aPosition;" +
            "attribute vec2 aTexCoord;" +
            "varying vec2 vTexCoord;" +
            "void main() {" +
            "    gl_Position = uMvp * vec4(aPosition, 1.0);" +
            "    vTexCoord = aTexCoord;" +
            "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
            "uniform sampler2D uSkin;" +
            "varying vec2 vTexCoord;" +
            "void main() {" +
            "    vec4 color = texture2D(uSkin, vTexCoord);" +
            "    if (color.a < 0.1) discard;" +
            "    gl_FragColor = color;" +
            "}";

    private final FloatBuffer mMeshBuffer;
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final float[] mPvMatrix = new float[16];
    private final float[] mWrapperMatrix = new float[16];
    private final float[] mPartMatrix = new float[16];
    private final float[] mModelMatrix = new float[16];
    private final float[] mMvpMatrix = new float[16];

    private int mProgram;
    private int mMvpHandle;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mSkinHandle;

    private int mSkinTexture;
    private boolean mMeshReady;
    private Bitmap mCurrentSkin;
    private Part[] mParts;

    private volatile Bitmap mPendingSkin;
    private volatile boolean mPendingSlim;

    private volatile float mYaw;
    private long mLastDragTime;
    private long mLastFrameTime;
    private float mSwingPhase;

    public PlayerSkinRenderer() {
        int floats = 6 * BOXES_PER_PART * VERTICES_PER_BOX * FLOATS_PER_VERTEX;
        mMeshBuffer = ByteBuffer.allocateDirect(floats * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    /** Rotate the model by drag fractions of the surface size (yaw only). */
    public void addRotation(float fractionX, float fractionY) {
        mYaw = (mYaw + fractionX * 360f) % 360f;
        mLastDragTime = System.currentTimeMillis();
    }

    /**
     * Queue a skin bitmap. Normalisation (legacy 64x32 layout, slim detection)
     * happens here on the caller's thread; upload and mesh rebuild happen on
     * the GL thread.
     */
    public void setSkin(Bitmap skin) {
        if (skin == null) return;
        if (skin.getWidth() < 64 || skin.getHeight() < 32) return;
        Bitmap normalized = normalizeSkin(skin);
        if (normalized == null) return;
        mPendingSlim = detectSlim(normalized);
        mPendingSkin = normalized;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        /* premultiplied alpha: the bitmap already carries alpha in its rgb */
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        mProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        mMvpHandle = GLES20.glGetUniformLocation(mProgram, "uMvp");
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mSkinHandle = GLES20.glGetUniformLocation(mProgram, "uSkin");

        int[] generated = new int[1];
        GLES20.glGenTextures(1, generated, 0);
        mSkinTexture = generated[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mSkinTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        /* the EGL context may come back after a pause: the mesh lives in CPU
         * memory but the texture does not, so push the skin again */
        if (mCurrentSkin != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mSkinTexture);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mCurrentSkin, 0);
        }

        mLastFrameTime = System.currentTimeMillis();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        float aspect = height > 0 ? (float) width / (float) height : 1f;
        Matrix.perspectiveM(mProjectionMatrix, 0, FIELD_OF_VIEW, aspect, NEAR_PLANE, FAR_PLANE);
        float distance = cameraDistance();
        Matrix.setLookAtM(mViewMatrix, 0,
                0f, 0f, distance,
                0f, 0f, 0f,
                0f, 1f, 0f);
        Matrix.multiplyMM(mPvMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.currentTimeMillis();
        float deltaSeconds = Math.min((now - mLastFrameTime) / 1000f, 0.1f);
        mLastFrameTime = now;
        mSwingPhase += IDLE_SWING_SPEED * deltaSeconds;
        if (mSwingPhase > 2f * (float) Math.PI) mSwingPhase -= 2f * (float) Math.PI;

        if (now - mLastDragTime > AUTO_ROTATION_RESUME_DELAY) {
            mYaw = (mYaw + AUTO_ROTATION_SPEED * deltaSeconds) % 360f;
        }

        Bitmap pending = mPendingSkin;
        if (pending != null) {
            mPendingSkin = null;
            uploadSkin(pending, mPendingSlim);
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (!mMeshReady) return;

        GLES20.glUseProgram(mProgram);

        /* wrapper: whole-model yaw, the model itself is centred on the origin */
        Matrix.setIdentityM(mWrapperMatrix, 0);
        Matrix.rotateM(mWrapperMatrix, 0, mYaw, 0f, 1f, 0f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mSkinTexture);
        GLES20.glUniform1i(mSkinHandle, 0);

        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);

        float swing = (float) Math.sin(mSwingPhase) * IDLE_SWING_AMPLITUDE;

        for (Part part : mParts) {
            /* joint -> rotation -> pivot -> mesh offset, same order as FCL */
            Matrix.setIdentityM(mPartMatrix, 0);
            Matrix.translateM(mPartMatrix, 0, part.posX, part.posY, part.posZ);
            if (part.arm) {
                Matrix.rotateM(mPartMatrix, 0, (float) Math.toDegrees(swing), 1f, 0f, 0f);
            }
            Matrix.translateM(mPartMatrix, 0,
                    part.pivotX + part.offsetX,
                    part.pivotY + part.offsetY,
                    part.pivotZ + part.offsetZ);
            Matrix.multiplyMM(mModelMatrix, 0, mWrapperMatrix, 0, mPartMatrix, 0);
            Matrix.multiplyMM(mMvpMatrix, 0, mPvMatrix, 0, mModelMatrix, 0);
            GLES20.glUniformMatrix4fv(mMvpHandle, 1, false, mMvpMatrix, 0);

            int base = part.firstVertex * FLOATS_PER_VERTEX;
            mMeshBuffer.position(base);
            GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT,
                    false, VERTEX_STRIDE, mMeshBuffer);
            mMeshBuffer.position(base + 3);
            GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT,
                    false, VERTEX_STRIDE, mMeshBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, part.vertexCount);
        }

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    private static float cameraDistance() {
        double halfTan = Math.tan(Math.toRadians(FIELD_OF_VIEW / 2.0));
        float distance = (float) (4.5 + 16.5 / halfTan / CAMERA_ZOOM);
        return Math.max(10f, Math.min(256f, distance));
    }

    private void uploadSkin(Bitmap skin, boolean slim) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mSkinTexture);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, skin, 0);
        mCurrentSkin = skin;
        buildMesh(slim);
    }

    // ---- mesh ----

    private void buildMesh(boolean slim) {
        mParts = createParts(slim);
        mMeshBuffer.clear();
        for (Part part : mParts) {
            part.firstVertex = mMeshBuffer.position() / FLOATS_PER_VERTEX;
            emitBox(part.base);
            emitBox(part.overlay);
            part.vertexCount = mMeshBuffer.position() / FLOATS_PER_VERTEX - part.firstVertex;
        }
        mMeshBuffer.position(0);
        mMeshReady = true;
    }

    /**
     * Parts of the classic (4px) / slim (3px) model. Positions are in skin
     * units with the model centred vertically: feet -16, head top +16.
     */
    private static Part[] createParts(boolean slim) {
        float armWidth = slim ? 3f : 4f;
        float armOverlayWidth = slim ? 3.5f : 4.5f;
        float armPivotX = slim ? 0.5f : 1f;

        return new Part[]{
                /* head: joint at the neck, mesh shifted up by half its height */
                new Part(0f, 8f, 0f, 0f, 0f, 0f, 0f, 4f, 0f, false,
                        new BoxDef(8, 8, 8, 0, 0),
                        new BoxDef(9, 9, 9, 32, 0, 8, 8, 8)),
                new Part(0f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false,
                        new BoxDef(8, 12, 4, 16, 16),
                        new BoxDef(8.5f, 12.5f, 4.5f, 16, 32, 8, 12, 4)),
                /* arms: joint at the shoulder */
                new Part(-5f, 6f, 0f, -armPivotX, -4f, 0f, 0f, 0f, 0f, true,
                        new BoxDef(armWidth, 12, 4, 40, 16),
                        new BoxDef(armOverlayWidth, 12.5f, 4.5f, 40, 32,
                                armWidth, 12, 4)),
                new Part(5f, 6f, 0f, armPivotX, -4f, 0f, 0f, 0f, 0f, true,
                        new BoxDef(armWidth, 12, 4, 32, 48),
                        new BoxDef(armOverlayWidth, 12.5f, 4.5f, 48, 48,
                                armWidth, 12, 4)),
                /* legs: joint at the hip */
                new Part(-1.9f, -4f, -0.1f, 0f, -6f, 0f, 0f, 0f, 0f, false,
                        new BoxDef(4, 12, 4, 0, 16),
                        new BoxDef(4.5f, 12.5f, 4.5f, 0, 32, 4, 12, 4)),
                new Part(1.9f, -4f, -0.1f, 0f, -6f, 0f, 0f, 0f, 0f, false,
                        new BoxDef(4, 12, 4, 16, 48),
                        new BoxDef(4.5f, 12.5f, 4.5f, 0, 48, 4, 12, 4)),
        };
    }

    /**
     * Emit one box as 36 vertices (6 faces x 2 triangles). Face order is
     * front(+Z), back(-Z), right(-X), left(+X), top(+Y), bottom(-Y); corner
     * order is top-left, top-right, bottom-left, bottom-right. The UV
     * rectangles are the ones described in the class comment.
     */
    private void emitBox(BoxDef box) {
        float hx = box.sizeX / 2f;
        float hy = box.sizeY / 2f;
        float hz = box.sizeZ / 2f;
        float u = box.u;
        float v = box.v;
        float w = box.uvWidth;
        float h = box.uvHeight;
        float d = box.uvDepth;

        float[][] faces = {
                /* front +Z */
                {-hx, hy, hz, hx, hy, hz, -hx, -hy, hz, hx, -hy, hz,
                        u + d, v + d, u + w + d, v + d + h},
                /* back -Z */
                {hx, hy, -hz, -hx, hy, -hz, hx, -hy, -hz, -hx, -hy, -hz,
                        u + w + d * 2, v + d, u + w * 2 + d * 2, v + d + h},
                /* right -X */
                {-hx, hy, -hz, -hx, hy, hz, -hx, -hy, -hz, -hx, -hy, hz,
                        u, v + d, u + d, v + d + h},
                /* left +X */
                {hx, hy, hz, hx, hy, -hz, hx, -hy, hz, hx, -hy, -hz,
                        u + w + d, v + d, u + w + d * 2, v + d + h},
                /* top +Y */
                {-hx, hy, -hz, hx, hy, -hz, -hx, hy, hz, hx, hy, hz,
                        u + d, v, u + w + d, v + d},
                /* bottom -Y */
                {-hx, -hy, -hz, hx, -hy, -hz, -hx, -hy, hz, hx, -hy, hz,
                        u + w + d, v, u + w * 2 + d, v + d},
        };

        for (float[] face : faces) {
            float u1 = face[12] / TEX_WIDTH;
            float v1 = face[13] / TEX_HEIGHT;
            float u2 = face[14] / TEX_WIDTH;
            float v2 = face[15] / TEX_HEIGHT;
            for (int i : TRIANGLE_CORNERS) {
                mMeshBuffer.put(face[i * 3]);
                mMeshBuffer.put(face[i * 3 + 1]);
                mMeshBuffer.put(face[i * 3 + 2]);
                mMeshBuffer.put((i == 0 || i == 2) ? u1 : u2);
                mMeshBuffer.put(i < 2 ? v1 : v2);
            }
        }
    }

    /** Triangle fans over the four corners: (tl, bl, br) then (tl, br, tr). */
    private static final int[] TRIANGLE_CORNERS = {0, 2, 3, 0, 3, 1};

    // ---- skin normalisation ----

    /**
     * Brings any accepted skin to a square, 64-multiple layout. Legacy 64x32
     * skins get their left limbs mirrored into place, exactly like the
     * original NormalizedSkin: the left arm/leg simply do not exist in the old
     * format, so they are copied from the right side with a horizontal flip.
     */
    private static Bitmap normalizeSkin(Bitmap skin) {
        int width = skin.getWidth();
        int height = skin.getHeight();
        if (width % 64 != 0) {
            return Bitmap.createScaledBitmap(skin, 64, 64, false);
        }
        if (width == height) {
            /* the UV table is authored on the 64 grid, so an HD 128x128 skin
             * has to come down to it; nearest neighbour keeps the pixel art
             * intact. A plain 64x64 is already the target layout. */
            return width == 64 ? skin : Bitmap.createScaledBitmap(skin, 64, 64, false);
        }
        if (width != height * 2) {
            return Bitmap.createScaledBitmap(skin, 64, 64, false);
        }

        int scale = width / 64;
        Bitmap out = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888);
        copyRegion(skin, out, 0, 0, 0, 0, width, height, scale, false);
        /* top leg, bottom leg, outer/front/inner/back leg */
        copyRegion(skin, out, 4, 16, 20, 48, 4, 4, scale, true);
        copyRegion(skin, out, 8, 16, 24, 48, 4, 4, scale, true);
        copyRegion(skin, out, 0, 20, 24, 52, 4, 12, scale, true);
        copyRegion(skin, out, 4, 20, 20, 52, 4, 12, scale, true);
        copyRegion(skin, out, 8, 20, 16, 52, 4, 12, scale, true);
        copyRegion(skin, out, 12, 20, 28, 52, 4, 12, scale, true);
        /* top arm, bottom arm, outer/front/inner/back arm */
        copyRegion(skin, out, 44, 16, 36, 48, 4, 4, scale, true);
        copyRegion(skin, out, 48, 16, 40, 48, 4, 4, scale, true);
        copyRegion(skin, out, 40, 20, 40, 52, 4, 12, scale, true);
        copyRegion(skin, out, 44, 20, 36, 52, 4, 12, scale, true);
        copyRegion(skin, out, 48, 20, 32, 52, 4, 12, scale, true);
        copyRegion(skin, out, 52, 20, 44, 52, 4, 12, scale, true);
        return out;
    }

    private static void copyRegion(Bitmap src, Bitmap dst,
                                   int sx, int sy, int dx, int dy,
                                   int w, int h, int scale, boolean flipHorizontal) {
        int[] row = new int[w * scale];
        for (int y = 0; y < h * scale; y++) {
            src.getPixels(row, 0, w * scale, sx * scale, sy * scale + y, w * scale, 1);
            if (flipHorizontal) {
                for (int i = 0, j = row.length - 1; i < j; i++, j--) {
                    int tmp = row[i];
                    row[i] = row[j];
                    row[j] = tmp;
                }
            }
            dst.setPixels(row, 0, w * scale, dx * scale, dy * scale + y, w * scale, 1);
        }
    }

    /**
     * Slim arms are 3px wide, so the outer two columns of their second-layer
     * texture are transparent (or black) instead of painted.
     */
    private static boolean detectSlim(Bitmap skin) {
        int scale = skin.getWidth() / 64;
        boolean transparent = hasTransparency(skin, 50, 16, 2, 4, scale)
                || hasTransparency(skin, 54, 20, 2, 12, scale)
                || hasTransparency(skin, 42, 48, 2, 4, scale)
                || hasTransparency(skin, 46, 52, 2, 12, scale);
        if (transparent) return true;
        return isAreaBlack(skin, 50, 16, 2, 4, scale)
                && isAreaBlack(skin, 54, 20, 2, 12, scale)
                && isAreaBlack(skin, 42, 48, 2, 4, scale)
                && isAreaBlack(skin, 46, 52, 2, 12, scale);
    }

    private static boolean hasTransparency(Bitmap skin, int x0, int y0, int w, int h, int scale) {
        for (int y = 0; y < h * scale; y++) {
            for (int x = 0; x < w * scale; x++) {
                if ((skin.getPixel(x0 * scale + x, y0 * scale + y) >>> 24) != 0xFF) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAreaBlack(Bitmap skin, int x0, int y0, int w, int h, int scale) {
        for (int y = 0; y < h * scale; y++) {
            for (int x = 0; x < w * scale; x++) {
                if (skin.getPixel(x0 * scale + x, y0 * scale + y) != 0xFF000000) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- GL helpers ----

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

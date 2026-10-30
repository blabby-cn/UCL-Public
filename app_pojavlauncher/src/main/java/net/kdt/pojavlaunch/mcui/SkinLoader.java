package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Loads the full skin for the UCL home screen 3D preview.
 *
 * <p>The lookup itself lives in {@link AvatarLoader#fetchFullSkin}, so the home
 * model and the account avatars agree on where a skin comes from: the account's
 * own auth server, a skin station, Mojang and the public mirror are tried in
 * that order and no account type is special cased. A Microsoft account and an
 * account from a third party Yggdrasil server are treated identically -- if a
 * skin can be found it is worn, and only when every source comes back empty
 * does the bundled vanilla Steve appear.
 */
public final class SkinLoader {
    private static final String TAG = "SkinLoader";
    private static final String DEFAULT_SKIN_ASSET = "ucl/steve.png";

    /** A failed lookup is not retried for this long, in milliseconds. */
    private static final long FAIL_TTL = 6 * 60 * 60 * 1000L;

    public interface Callback {
        void onSkinLoaded(Bitmap skin);
    }

    private SkinLoader() {
    }

    public static void load(@NonNull Context context, @Nullable MinecraftAccount account,
                            @NonNull Callback callback) {
        if (account == null || TextUtils.isEmpty(account.username)) {
            callback.onSkinLoaded(decodeDefault(context));
            return;
        }
        File cached = cacheFile(account);
        Bitmap ready = decode(cached);
        if (ready != null) {
            callback.onSkinLoaded(ready);
            return;
        }
        if (recentlyFailed(account)) {
            callback.onSkinLoaded(decodeDefault(context));
            return;
        }
        new Thread(() -> {
            Bitmap skin = null;
            try {
                skin = AvatarLoader.fetchFullSkin(account);
            } catch (Exception e) {
                Log.w(TAG, "skin lookup failed for " + account.username, e);
            }
            if (skin != null) store(account, cached, skin);
            else markFailed(account);
            final Bitmap result = skin != null ? skin : decodeDefault(context);
            Tools.runOnUiThread(() -> callback.onSkinLoaded(result));
        }, "ucl-skin").start();
    }

    /** Drops the cached texture so the next {@link #load} fetches it again. */
    public static void invalidate(@Nullable MinecraftAccount account) {
        if (account == null) return;
        cacheFile(account).delete();
        failMarker(account).delete();
    }

    private static File cacheFile(MinecraftAccount account) {
        /* the avatar key is unique per account (uuid, or the storage file for
         * offline entries), so two accounts sharing a name never trade skins */
        return new File(Tools.DIR_CACHE, "skin_" + AvatarLoader.cacheKey(account) + ".png");
    }

    private static File failMarker(MinecraftAccount account) {
        return new File(Tools.DIR_CACHE, "skin_" + AvatarLoader.cacheKey(account) + ".fail");
    }

    private static boolean recentlyFailed(MinecraftAccount account) {
        File marker = failMarker(account);
        if (!marker.exists()) return false;
        if (System.currentTimeMillis() - marker.lastModified() < FAIL_TTL) return true;
        marker.delete();
        return false;
    }

    private static void markFailed(MinecraftAccount account) {
        File marker = failMarker(account);
        try {
            if (!marker.exists()) marker.createNewFile();
            marker.setLastModified(System.currentTimeMillis());
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private static Bitmap decode(File file) {
        if (!file.exists() || file.length() <= 0) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    private static void store(MinecraftAccount account, File file, Bitmap skin) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            skin.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            Log.w(TAG, "could not cache skin in " + file.getName(), e);
        }
        failMarker(account).delete();
    }

    @Nullable
    private static Bitmap decodeDefault(@NonNull Context context) {
        try (InputStream stream = context.getAssets().open(DEFAULT_SKIN_ASSET)) {
            return BitmapFactory.decodeStream(stream);
        } catch (Exception e) {
            Log.e(TAG, "default skin asset missing", e);
            return null;
        }
    }
}

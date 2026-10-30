package net.kdt.pojavlaunch.mcui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a full Minecraft skin into a face-sized avatar.
 *
 * Microsoft accounts and accounts coming from an external authlib server have
 * no avatar of their own -- only a skin. The head is the top left 8x8 block of
 * that skin, and "double layer" skins (the hat/overlay layer) keep that second
 * layer at a fixed offset, so the avatar has to be composited from both blocks.
 *
 * The parser adapts to the texture instead of assuming 64x64:
 *
 *   64x64    -> 8x8    avatar (1 pixel = 1 skin pixel)
 *   128x128  -> 16x16  avatar (2x HD skin keeps all of its detail)
 *   64x32    -> 8x8    legacy skin, same origin, overlay at x=40
 *   32x64    -> 8x8    narrow legacy skin, no overlay column available
 *   <other>  -> the unit size is guessed from the height
 *
 * Everything is copied pixel by pixel, so the result is never resampled by a
 * bilinear filter and the avatar keeps its hard pixel edges.
 *
 * Resolving *where* the skin lives is the other half of the job. Nothing here
 * knows any site by name: the account remembers the auth server it came from,
 * and the candidates are the plain conventions every Yggdrasil deployment
 * speaks.
 */
public final class AvatarLoader {
    private static final String TAG = "AvatarLoader";
    private static final String MOJANG_SESSIONS =
            "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final String MC_HEADS_SKIN = "https://mc-heads.net/skin/";
    private static final String AGENT = "UniversalClientLauncher";

    private static final int TIMEOUT = 8000;
    private static final int MAX_JSON = 65536;

    /* 8x8 (64px skin) / 16x16 (128px skin) are the sizes asked for; anything
     * bigger only wastes memory for an avatar that is never shown that large */
    private static final int MAX_FACE_UNIT = 2;

    /** Base head block, in skin-pixel units (the 64 wide reference grid). */
    private static final int HEAD_X = 8, HEAD_Y = 8;
    /** Overlay (hat) block, same size, same grid. */
    private static final int HAT_X = 40, HAT_Y = 8;
    private static final int UNIT_SIZE = 8;

    /** A failed lookup is not retried for this long when rendering a list. */
    private static final long FAIL_TTL = 6 * 60 * 60 * 1000L;

    private static final Map<String, Bitmap> MEMORY = new HashMap<>();
    private static final Set<String> IN_FLIGHT = new HashSet<>();

    public interface Callback {
        void onFace(String username, Bitmap face);
    }

    private AvatarLoader() {
    }

    /* ================================================================== */
    /* cache                                                              */
    /* ================================================================== */

    /**
     * Cache key of an account. The UUID is unique across servers, so two
     * accounts that share a name (a LittleSkin "Blabby" and a Mojang "Blabby")
     * no longer overwrite each other's avatar.
     */
    public static String cacheKey(@Nullable MinecraftAccount account) {
        if (account == null) return "";
        String uuid = noHyphen(account.profileId);
        if (!isEmptyUuid(uuid)) return "face_" + uuid.toLowerCase(Locale.US);
        String origin = TextUtils.isEmpty(account.authBaseUrl) ? "local" : account.authBaseUrl;
        /* Offline accounts all share the nil uuid, so the username used to be
         * the only discriminator -- which handed every account called Mike the
         * same head. The storage file is unique, so key on that instead. */
        String scope = TextUtils.isEmpty(account.accountFile)
                ? account.username : account.accountFile;
        return "face_" + Integer.toHexString(origin.hashCode()) + "_" + scope;
    }

    private static File faceFile(String key) {
        return new File(Tools.DIR_CACHE, key + ".png");
    }

    /** Blabby avatars are written here by BlabbyClient, under the plain name. */
    private static File legacyFile(String username) {
        return new File(Tools.DIR_CACHE, username + ".png");
    }

    private static File failMarker(String key) {
        return new File(Tools.DIR_CACHE, key + ".fail");
    }

    @Nullable
    public static Bitmap cached(@Nullable MinecraftAccount account) {
        if (account == null || TextUtils.isEmpty(account.username)) return null;
        String key = cacheKey(account);
        synchronized (MEMORY) {
            Bitmap hit = MEMORY.get(key);
            if (hit != null && !hit.isRecycled()) return hit;
        }
        Bitmap decoded = decode(faceFile(key));
        if (decoded == null) {
            /* older versions cached under the bare user name, and the Blabby
             * website avatar still lives there */
            decoded = decode(legacyFile(account.username));
        }
        if (decoded != null) {
            synchronized (MEMORY) {
                MEMORY.put(key, decoded);
            }
        }
        return decoded;
    }

    /** Legacy lookup by user name only; kept for callers without an account. */
    @Nullable
    public static Bitmap cached(@Nullable String username) {
        if (TextUtils.isEmpty(username)) return null;
        synchronized (MEMORY) {
            Bitmap hit = MEMORY.get(username);
            if (hit != null && !hit.isRecycled()) return hit;
        }
        return decode(legacyFile(username));
    }

    @Nullable
    private static Bitmap decode(File file) {
        if (!file.exists() || file.length() <= 0) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    static void store(@NonNull MinecraftAccount account, @NonNull Bitmap face) {
        String key = cacheKey(account);
        if (TextUtils.isEmpty(key)) return;
        synchronized (MEMORY) {
            MEMORY.put(key, face);
        }
        writeFile(faceFile(key), face);
        clearFailed(account);
    }

    /** Public for the Blabby login flow, which stores a downloaded avatar. */
    public static void store(@NonNull String username, @NonNull Bitmap face) {
        if (TextUtils.isEmpty(username)) return;
        synchronized (MEMORY) {
            MEMORY.put(username, face);
        }
        writeFile(legacyFile(username), face);
    }

    private static void writeFile(File file, Bitmap bitmap) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            Log.w(TAG, "could not cache face in " + file.getName(), e);
        }
    }

    public static void forget(@Nullable MinecraftAccount account) {
        if (account == null) return;
        String key = cacheKey(account);
        synchronized (MEMORY) {
            MEMORY.remove(key);
        }
        faceFile(key).delete();
        failMarker(key).delete();
        forget(account.username);
    }

    public static void forget(@Nullable String username) {
        if (TextUtils.isEmpty(username)) return;
        synchronized (MEMORY) {
            MEMORY.remove(username);
        }
        legacyFile(username).delete();
    }

    private static boolean recentlyFailed(MinecraftAccount account) {
        File marker = failMarker(cacheKey(account));
        if (!marker.exists()) return false;
        if (System.currentTimeMillis() - marker.lastModified() < FAIL_TTL) return true;
        marker.delete();
        return false;
    }

    private static void markFailed(MinecraftAccount account) {
        File marker = failMarker(cacheKey(account));
        try {
            if (!marker.exists()) marker.createNewFile();
            marker.setLastModified(System.currentTimeMillis());
        } catch (Exception ignored) {
        }
    }

    private static void clearFailed(MinecraftAccount account) {
        failMarker(cacheKey(account)).delete();
    }

    /* ================================================================== */
    /* fetching                                                           */
    /* ================================================================== */

    /**
     * Fetches the avatar of an account that has no avatar yet, on a worker
     * thread, and reports the result on the UI thread. The callback also fires
     * with null so callers can stop their spinner.
     */
    public static void refresh(@Nullable MinecraftAccount account, @Nullable Callback callback) {
        if (account == null || TextUtils.isEmpty(account.username)) {
            if (callback != null) callback.onFace("", null);
            return;
        }
        if (recentlyFailed(account)) {
            if (callback != null) callback.onFace(account.username, null);
            return;
        }
        final String username = account.username;
        final String key = cacheKey(account);
        synchronized (IN_FLIGHT) {
            if (!IN_FLIGHT.add(key)) {
                /* another caller is already fetching this account; answer right
                 * away so the caller does not wait forever for a callback */
                if (callback != null) callback.onFace(username, null);
                return;
            }
        }
        new Thread(() -> {
            Bitmap face = null;
            try {
                face = downloadFace(account);
            } catch (Exception e) {
                Log.w(TAG, "avatar fetch failed for " + username, e);
            }
            if (face != null) store(account, face);
            else markFailed(account);
            synchronized (IN_FLIGHT) {
                IN_FLIGHT.remove(key);
            }
            final Bitmap result = face;
            Tools.runOnUiThread(() -> {
                if (callback != null) callback.onFace(username, result);
            });
        }, "ucl-avatar").start();
    }

    /**
     * Blocking variant used by the login flows, which already run off-thread.
     * A previous failure is not held against a fresh login: the account was
     * just re-created, so the platform may answer differently now.
     */
    @Nullable
    public static Bitmap fetchAndStore(@Nullable MinecraftAccount account) {
        if (account == null || TextUtils.isEmpty(account.username)) return null;
        Bitmap face;
        try {
            face = downloadFace(account);
        } catch (Exception e) {
            Log.w(TAG, "avatar fetch failed for " + account.username, e);
            return null;
        }
        if (face != null) store(account, face);
        else markFailed(account);
        return face;
    }

    /**
     * The resolver chain. Every step is a plain convention rather than a site
     * name, and the first hit wins. Raw textures come before the ready made
     * avatar endpoints because cropping them ourselves keeps the pixel grid,
     * while those endpoints answer with a pre-scaled thumbnail:
     *
     *   1. the account's own auth server  (Yggdrasil sessionserver textures)
     *   2. the skin station raw texture    (/skin/<name>.png)
     *   3. the skin station texture APIs   (UniSkinAPI / CustomSkinLoader)
     *   4. the skin station avatar API     (Blessing Skin 4.x / 3.x)
     *   5. Mojang, for profiles that are Mojang accounts
     *   6. a public texture mirror
     */
    @Nullable
    private static Bitmap downloadFace(MinecraftAccount account) {
        final String username = account.username;
        final String uuid = noHyphen(account.profileId);
        final boolean hasUuid = !isEmptyUuid(uuid);

        YggdrasilDiscovery.Api api = YggdrasilDiscovery.resolve(
                TextUtils.isEmpty(account.authBaseUrl) ? null : account.authBaseUrl);

        /* 1. the server that issued this account knows its texture, and it is
         *    not necessarily Mojang */
        if (api != null && hasUuid) {
            Bitmap own = faceFromSession(api.sessionsBase(), uuid);
            if (own != null) {
                Log.d(TAG, "face for " + username + " from " + api.apiRoot);
                return own;
            }
        }

        /* 2./3. raw textures from the skin station, cropped here */
        if (api != null) {
            for (String site : api.siteRoots) {
                Bitmap face = faceFromRawTexture(site, username);
                if (face != null) {
                    Log.d(TAG, "face for " + username + " from " + site + "/skin");
                    return face;
                }
            }
            for (String site : api.siteRoots) {
                Bitmap face = faceFromSkinJson(site, username);
                if (face != null) {
                    Log.d(TAG, "face for " + username + " from " + site + " texture api");
                    return face;
                }
            }
            /* 4. a ready made head, which arrives pre-scaled */
            for (String site : api.siteRoots) {
                Bitmap face = faceFromAvatarApi(site, username);
                if (face != null) {
                    Log.d(TAG, "face for " + username + " from " + site + "/avatar");
                    return face;
                }
            }
        }

        /* 5. a Mojang profile, whichever server the account was created on */
        if (hasUuid) {
            Bitmap mojang = faceFromSession(MOJANG_SESSIONS, uuid);
            if (mojang != null) return mojang;
        }

        /* 6. public mirror, still cropped locally rather than pre-scaled */
        if (hasUuid) {
            Bitmap face = extractFace(fetchBitmap(MC_HEADS_SKIN + uuid));
            if (face != null) return face;
        }
        if (!TextUtils.isEmpty(username)) {
            return extractFace(fetchBitmap(MC_HEADS_SKIN + Uri.encode(username)));
        }
        return null;
    }

    /**
     * Blessing Skin publishes the untouched texture at /skin/<name>.png. A
     * LittleSkin answer is a real 64x64 RGBA skin, hat layer included, so it
     * is the best source short of the sessionserver itself.
     */
    /**
     * The whole skin, for the 3D preview. Same resolver chain as
     * {@link #downloadFace}, minus the ready made avatar endpoint: that one
     * answers with a cropped head and cannot clothe a model.
     *
     * <p>Blocking, so call it off the UI thread. Null means the account has no
     * reachable skin at all and the caller should fall back to the bundled
     * vanilla texture.
     */
    @Nullable
    public static Bitmap fetchFullSkin(@Nullable MinecraftAccount account) {
        if (account == null || TextUtils.isEmpty(account.username)) return null;
        try {
            return resolveSkin(account);
        } catch (Exception e) {
            Log.w(TAG, "skin fetch failed for " + account.username, e);
            return null;
        }
    }

    @Nullable
    private static Bitmap resolveSkin(MinecraftAccount account) {
        final String username = account.username;
        final String uuid = noHyphen(account.profileId);
        final boolean hasUuid = !isEmptyUuid(uuid);

        YggdrasilDiscovery.Api api = YggdrasilDiscovery.resolve(
                TextUtils.isEmpty(account.authBaseUrl) ? null : account.authBaseUrl);

        /* the server that issued the account first: it may not be Mojang */
        if (api != null && hasUuid) {
            String url = resolveSkinUrl(api.sessionsBase(), uuid);
            if (url == null) {
                String hyphenated = hyphenate(uuid);
                if (!hyphenated.equals(uuid)) {
                    url = resolveSkinUrl(api.sessionsBase(), hyphenated);
                }
            }
            if (url != null) {
                Bitmap skin = fetchBitmap(url);
                if (skin != null) return skin;
            }
        }

        /* raw textures published by the skin station */
        if (api != null) {
            for (String site : api.siteRoots) {
                Bitmap skin = rawTexture(site, username);
                if (skin != null) return skin;
            }
            for (String site : api.siteRoots) {
                Bitmap skin = textureFromSkinJson(site, username);
                if (skin != null) return skin;
            }
        }

        /* a Mojang profile, whichever server the account was created on */
        if (hasUuid) {
            String url = resolveSkinUrl(MOJANG_SESSIONS, uuid);
            if (url != null) {
                Bitmap skin = fetchBitmap(url);
                if (skin != null) return skin;
            }
        }

        /* public mirror, last stop */
        return fetchBitmap(hasUuid
                ? MC_HEADS_SKIN + uuid
                : MC_HEADS_SKIN + Uri.encode(username));
    }

    @Nullable
    private static Bitmap faceFromRawTexture(String siteRoot, String username) {
        return extractFace(rawTexture(siteRoot, username));
    }

    /**
     * The untouched skin the station publishes at /skin/&lt;name&gt;.png. This is
     * the same probe the avatar uses, but handed back whole: the 3D preview
     * needs every body part, not just the head.
     */
    @Nullable
    private static Bitmap rawTexture(String siteRoot, String username) {
        if (TextUtils.isEmpty(siteRoot) || TextUtils.isEmpty(username)) return null;
        Bitmap skin = fetchBitmap(siteRoot + "/skin/" + Uri.encode(username) + ".png");
        if (skin == null) {
            /* the alex/steve flavoured variants of the same route */
            skin = fetchBitmap(siteRoot + "/skin/" + Uri.encode(username) + "-steve.png");
        }
        return skin;
    }

    /* ================================================================== */
    /* 1. Yggdrasil sessionserver                                         */
    /* ================================================================== */

    @Nullable
    private static Bitmap faceFromSession(String sessionBase, String uuid) {
        if (TextUtils.isEmpty(sessionBase) || TextUtils.isEmpty(uuid)) return null;
        String skinUrl = resolveSkinUrl(sessionBase, uuid);
        if (skinUrl == null) {
            /* Blessing Skin style servers route only the 32 character form and
             * answer nginx 404 for the hyphenated one; a few private servers do
             * the opposite, so the other spelling is worth one more shot */
            String hyphenated = hyphenate(uuid);
            if (!hyphenated.equals(uuid)) {
                skinUrl = resolveSkinUrl(sessionBase, hyphenated);
            }
        }
        if (skinUrl == null) return null;
        return extractFace(fetchBitmap(skinUrl));
    }

    @Nullable
    private static String resolveSkinUrl(String sessionBase, String uuid) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(sessionBase + uuid).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", AGENT);

            int code = conn.getResponseCode();
            /* every Yggdrasil server answers 204 for "no such profile" */
            if (code == 204) return null;
            if (code < 200 || code >= 300) {
                Log.d(TAG, "session " + code + " for " + uuid + " on " + sessionBase);
                return null;
            }
            String content;
            try (InputStream stream = conn.getInputStream()) {
                content = readLimited(stream, MAX_JSON);
            }
            if (TextUtils.isEmpty(content)) return null;

            JSONObject profile = new JSONObject(content);
            JSONArray properties = profile.optJSONArray("properties");
            if (properties == null) return null;
            for (int i = 0; i < properties.length(); i++) {
                JSONObject property = properties.optJSONObject(i);
                if (property == null || !"textures".equals(property.optString("name"))) continue;
                String value = property.optString("value", null);
                if (TextUtils.isEmpty(value)) continue;
                String json = decodeTexturesValue(value);
                if (json == null) continue;
                JSONObject textures = new JSONObject(json).optJSONObject("textures");
                if (textures == null) continue;
                JSONObject skin = textures.optJSONObject("SKIN");
                if (skin == null) continue;
                String url = skin.optString("url", null);
                /* a few servers hand out "/textures/xxx" instead of a full URL */
                if (!TextUtils.isEmpty(url)) return absolutize(url, sessionBase);
            }
            return null;
        } catch (Exception e) {
            Log.d(TAG, "session lookup failed on " + sessionBase + ": " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Resolves a texture URL that may have been handed out relative to the API. */
    @Nullable
    private static String absolutize(String url, String base) {
        if (TextUtils.isEmpty(url)) return null;
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return url;
        try {
            return new URL(new URL(base), url).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The textures property is base64 encoded JSON, but a couple of servers
     * ship it as plain JSON and base64 padding is occasionally missing.
     */    @Nullable
    private static String decodeTexturesValue(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("{")) return trimmed;
        try {
            int pad = (4 - trimmed.length() % 4) % 4;
            StringBuilder padded = new StringBuilder(trimmed);
            for (int i = 0; i < pad; i++) padded.append('=');
            String decoded = new String(Base64.decode(padded.toString(), Base64.DEFAULT),
                    StandardCharsets.UTF_8).trim();
            if (decoded.startsWith("{")) return decoded;
        } catch (Exception ignored) {
        }
        return null;
    }

    /* ================================================================== */
    /* 2. skin station avatar endpoint                                    */
    /* ================================================================== */

    @Nullable
    private static Bitmap faceFromAvatarApi(String siteRoot, String username) {
        if (TextUtils.isEmpty(siteRoot) || TextUtils.isEmpty(username)) return null;
        String name = Uri.encode(username);
        /* Blessing Skin 4.x; "png" keeps the answer lossless instead of webp */
        Bitmap face = squareToFace(fetchBitmap(siteRoot + "/avatar/player/" + name + "?png"));
        if (face != null) return face;
        /* Blessing Skin 3.x */
        return squareToFace(fetchBitmap(siteRoot + "/avatar/player/" + UNIT_SIZE + "/"
                + name + ".png"));
    }

    /**
     * These endpoints return an already cropped head, but usually pre-scaled
     * to something like 100x100. Sampling it back down to 8x8 keeps the pixel
     * edges hard.
     */
    @Nullable
    private static Bitmap squareToFace(@Nullable Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        if (width < UNIT_SIZE || height < UNIT_SIZE) return null;
        if (width == UNIT_SIZE && height == UNIT_SIZE) return source;
        if (width == UNIT_SIZE * 2 && height == UNIT_SIZE * 2) return source;
        int[] out = new int[UNIT_SIZE * UNIT_SIZE];
        for (int y = 0; y < UNIT_SIZE; y++) {
            int sy = Math.min(height - 1, y * height / UNIT_SIZE);
            for (int x = 0; x < UNIT_SIZE; x++) {
                int sx = Math.min(width - 1, x * width / UNIT_SIZE);
                out[y * UNIT_SIZE + x] = source.getPixel(sx, sy);
            }
        }
        Bitmap face = Bitmap.createBitmap(UNIT_SIZE, UNIT_SIZE, Bitmap.Config.ARGB_8888);
        face.setPixels(out, 0, UNIT_SIZE, 0, 0, UNIT_SIZE, UNIT_SIZE);
        return face;
    }

    /* ================================================================== */
    /* 3./4. skin station texture endpoints                               */
    /* ================================================================== */

    @Nullable
    private static Bitmap faceFromSkinJson(String siteRoot, String username) {
        return extractFace(textureFromSkinJson(siteRoot, username));
    }

    @Nullable
    private static Bitmap textureFromSkinJson(String siteRoot, String username) {
        if (TextUtils.isEmpty(siteRoot) || TextUtils.isEmpty(username)) return null;
        String name = Uri.encode(username);
        String[] endpoints = {
                siteRoot + "/usm/" + name + ".json",   /* UniSkinAPI */
                siteRoot + "/" + name + ".json",       /* CustomSkinLoader */
        };
        for (String endpoint : endpoints) {
            String body = fetchText(endpoint);
            if (TextUtils.isEmpty(body)) continue;
            String skinUrl = skinUrlFromJson(body, siteRoot);
            if (skinUrl == null) continue;
            Bitmap skin = fetchBitmap(skinUrl);
            if (skin != null) return skin;
        }
        return null;
    }

    @Nullable
    private static String skinUrlFromJson(String body, String siteRoot) {
        try {
            JSONObject json = new JSONObject(body.trim());
            JSONObject skins = json.optJSONObject("skins");
            String url = null;
            if (skins != null) {
                url = skins.optString("default", null);
                if (TextUtils.isEmpty(url)) url = skins.optString("slim", null);
            }
            if (TextUtils.isEmpty(url)) {
                for (String key : Arrays.asList("skin", "skinUrl", "url")) {
                    String candidate = json.optString(key, null);
                    if (!TextUtils.isEmpty(candidate)) {
                        url = candidate;
                        break;
                    }
                }
            }
            if (TextUtils.isEmpty(url)) return null;
            if (url.startsWith("/")) return siteRoot + url;
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    /* ================================================================== */
    /* http                                                               */
    /* ================================================================== */

    @Nullable
    private static Bitmap fetchBitmap(String urlString) {
        if (TextUtils.isEmpty(urlString)) return null;
        Bitmap bitmap = fetchBitmapOnce(secureUrl(urlString));
        if (bitmap == null && !secureUrl(urlString).equals(urlString)) {
            /* an external auth server may still only speak plain HTTP */
            bitmap = fetchBitmapOnce(urlString);
        }
        return bitmap;
    }

    /**
     * Microsoft hands out skin URLs on plain http, and from targetSdk 28 up
     * Android refuses cleartext traffic (the manifest sets no exception), so
     * every skin download failed with a "Cleartext HTTP traffic not permitted"
     * IOException and the account kept the placeholder avatar. The texture
     * host serves the very same object over https.
     */
    private static String secureUrl(String urlString) {
        if (urlString == null) return "";
        if (urlString.startsWith("http://")) return "https://" + urlString.substring(7);
        return urlString;
    }

    @Nullable
    private static Bitmap fetchBitmapOnce(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", AGENT);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.d(TAG, "download " + code + ": " + urlString);
                return null;
            }
            String contentType = conn.getContentType();
            if (contentType != null && !contentType.toLowerCase(Locale.US).startsWith("image")) {
                Log.d(TAG, "not an image (" + contentType + "): " + urlString);
                return null;
            }
            try (InputStream stream = conn.getInputStream()) {
                return BitmapFactory.decodeStream(stream);
            }
        } catch (Exception e) {
            Log.d(TAG, "download failed: " + urlString + " (" + e + ")");
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Nullable
    private static String fetchText(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(secureUrl(urlString)).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", AGENT);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream stream = conn.getInputStream()) {
                return readLimited(stream, MAX_JSON);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readLimited(InputStream stream, int max) throws Exception {
        return YggdrasilDiscovery.readLimited(stream, max);
    }

    /* ================================================================== */
    /* uuid helpers                                                       */
    /* ================================================================== */

    /** Yggdrasil profile ids are 32 characters, no hyphens. */
    static String noHyphen(@Nullable String profileId) {
        if (TextUtils.isEmpty(profileId)) return "";
        return profileId.replace("-", "").trim().toLowerCase(Locale.US);
    }

    static String hyphenate(String bare) {
        if (bare == null || bare.length() != 32) return bare == null ? "" : bare;
        return bare.substring(0, 8) + "-" + bare.substring(8, 12) + "-"
                + bare.substring(12, 16) + "-" + bare.substring(16, 20) + "-"
                + bare.substring(20);
    }

    static boolean isEmptyUuid(String uuid) {
        return TextUtils.isEmpty(uuid)
                || "00000000000000000000000000000000".equals(uuid)
                || "00000000-0000-0000-0000-000000000000".equals(uuid);
    }

    /* ================================================================== */
    /* skin -> face                                                       */
    /* ================================================================== */

    /**
     * Crops the head of a skin, hat layer included, at 1:1 pixel scale.
     *
     * @return 8x8 for a 64 wide skin, 16x16 for a 128 wide one, or null when
     * the texture is too small to hold a head at all
     */
    @Nullable
    public static Bitmap extractFace(@Nullable Bitmap skin) {
        if (skin == null || skin.isRecycled()) return null;
        int width = skin.getWidth();
        int height = skin.getHeight();
        int unit = detectUnit(width, height);
        if (unit <= 0) return null;
        int sourceSize = UNIT_SIZE * unit;
        int baseX = HEAD_X * unit;
        int baseY = HEAD_Y * unit;
        if (baseX + sourceSize > width || baseY + sourceSize > height) {
            /* the texture is not laid out on the expected grid: fall back to
             * the biggest block the image can still afford */
            unit = Math.min(width / (HEAD_X + UNIT_SIZE), height / (HEAD_Y + UNIT_SIZE));
            if (unit <= 0) return null;
            sourceSize = UNIT_SIZE * unit;
            baseX = HEAD_X * unit;
            baseY = HEAD_Y * unit;
        }
        if (sourceSize <= 0) return null;

        int[] basePixels = new int[sourceSize * sourceSize];
        skin.getPixels(basePixels, 0, sourceSize, baseX, baseY, sourceSize, sourceSize);

        int[] hatPixels = null;
        int hatX = HAT_X * unit;
        int hatY = HAT_Y * unit;
        if (hatX + sourceSize <= width && hatY + sourceSize <= height) {
            hatPixels = new int[sourceSize * sourceSize];
            skin.getPixels(hatPixels, 0, sourceSize, hatX, hatY, sourceSize, sourceSize);
        }

        int renderUnit = Math.min(unit, MAX_FACE_UNIT);
        int outSize = UNIT_SIZE * renderUnit;
        int factor = Math.max(1, unit / renderUnit);
        int[] out = new int[outSize * outSize];
        for (int y = 0; y < outSize; y++) {
            int sy = Math.min(sourceSize - 1, y * factor);
            for (int x = 0; x < outSize; x++) {
                int sx = Math.min(sourceSize - 1, x * factor);
                int color = basePixels[sy * sourceSize + sx];
                if (hatPixels != null) {
                    int hat = hatPixels[sy * sourceSize + sx];
                    if ((hat >>> 24) != 0) color = blend(hat, color);
                }
                out[y * outSize + x] = color;
            }
        }
        Bitmap face = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888);
        face.setPixels(out, 0, outSize, 0, 0, outSize, outSize);
        return face;
    }

    /** Source-over of the hat layer on top of the head. */
    private static int blend(int src, int dst) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        int outA = sa + da * (255 - sa) / 255;
        if (outA == 0) return 0;
        int r = (((src >> 16) & 0xFF) * sa + ((dst >> 16) & 0xFF) * da * (255 - sa) / 255) / outA;
        int g = (((src >> 8) & 0xFF) * sa + ((dst >> 8) & 0xFF) * da * (255 - sa) / 255) / outA;
        int b = ((src & 0xFF) * sa + (dst & 0xFF) * da * (255 - sa) / 255) / outA;
        return (outA << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    /**
     * How many pixels of this texture make up one unit of the 64 wide skin
     * grid. Skins are authored on that grid and then either scaled up whole
     * (128x128, 256x256...) or left narrow (32x64 legacy).
     */
    private static int detectUnit(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        if (width >= 64 && height >= 32) {
            if (width % 64 == 0 && height % 32 == 0) return Math.max(1, width / 64);
            return Math.max(1, height / 64);
        }
        /* narrow legacy textures keep the same left-handed grid */
        return 1;
    }
}

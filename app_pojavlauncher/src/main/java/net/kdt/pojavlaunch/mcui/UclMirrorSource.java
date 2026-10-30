package net.kdt.pojavlaunch.mcui;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns whatever the user typed into a usable mirror.
 *
 * <p>Nothing is preset. PojavLauncher ships with Mojang's repository wired in
 * as the default source and a second vendor baked into a switch statement; both
 * are gone. With no source configured this class hands out nothing, and the
 * downloader refuses to run instead of quietly reaching for a vendor.
 *
 * <p>A mirror is not a different API, it is the same paths behind another host.
 * So a source is just a root, and the three roots the downloader needs
 * (libraries / metadata / assets) are derived from that one string.
 */
public final class UclMirrorSource {
    private static final String TAG = "UclMirrorSource";

    /** Same order as DownloadMirror's DOWNLOAD_CLASS_* constants. */
    public static final int ROOT_LIBRARIES = 0;
    public static final int ROOT_METADATA = 1;
    public static final int ROOT_ASSETS = 2;

    private static final String PREF_KEY = "downloadSource";
    private static final String PREF_MANIFEST = "downloadSourceManifest";
    private static final String PREF_COUNT = "downloadSourceCount";
    private static final String PREF_NAME = "downloadSourceName";

    /** Pasting a manifest link is common; accept it but keep the root. */
    private static final String[] MANIFEST_TAILS = {
            "/mc/game/version_manifest_v2.json",
            "/mc/game/version_manifest.json",
            "/version_manifest_v2.json",
            "/version_manifest.json",
    };

    private UclMirrorSource() {}

    public static final class Mirror {
        public final String root;
        public final String manifestUrl;
        public final int versionCount;
        @Nullable public final String serverName;

        Mirror(String root, String manifestUrl, int versionCount, @Nullable String serverName) {
            this.root = root;
            this.manifestUrl = manifestUrl;
            this.versionCount = versionCount;
            this.serverName = serverName;
        }
    }

    public interface Callback {
        /** @param mirror null when the address does not expose a version list */
        void onResolved(@Nullable Mirror mirror);
    }

    /**
     * Strips the parts of a pasted address that are not the root: a scheme if
     * missing, trailing slashes, and a full manifest path. Never downgrades to
     * plain http, the specification forbids it.
     */
    public static String normalize(@Nullable String input) {
        if (input == null) return "";
        String url = input.trim();
        if (url.isEmpty()) return "";
        if (!url.contains("://")) url = "https://" + url;
        url = trimSlashes(url);

        String lower = url.toLowerCase(Locale.US);
        for (String tail : MANIFEST_TAILS) {
            if (lower.endsWith(tail)) {
                url = trimSlashes(url.substring(0, url.length() - tail.length()));
                break;
            }
        }
        return url;
    }

    private static String trimSlashes(String url) {
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** The version list to sync from, or null when the user has not set one. */
    @Nullable
    public static String manifestUrl() {
        String root = LauncherPreferences.PREF_DOWNLOAD_SOURCE;
        if (TextUtils.isEmpty(root) || !root.contains("://")) return null;
        String cached = LauncherPreferences.DEFAULT_PREF.getString(PREF_MANIFEST, null);
        if (!TextUtils.isEmpty(cached) && cached.startsWith(root)) return cached;
        /* Not probed yet: assume the canonical layout rather than making the
         * caller wait another round trip just to read the version list. */
        return root + "/mc/game/version_manifest_v2.json";
    }

    /** True when this root has already answered with a version list. */
    public static boolean hasProbe() {
        String root = LauncherPreferences.PREF_DOWNLOAD_SOURCE;
        if (TextUtils.isEmpty(root)) return false;
        String cached = LauncherPreferences.DEFAULT_PREF.getString(PREF_MANIFEST, null);
        return !TextUtils.isEmpty(cached) && cached.startsWith(root);
    }

    /** Version count reported by the last successful probe, 0 when unknown. */
    public static int cachedCount() {
        return LauncherPreferences.DEFAULT_PREF.getInt(PREF_COUNT, 0);
    }

    /** Display name the mirror reported, or null when it reported none. */
    @Nullable
    public static String cachedName() {
        return LauncherPreferences.DEFAULT_PREF.getString(PREF_NAME, null);
    }

    /**
     * The three roots DownloadMirror rewrites to, in DOWNLOAD_CLASS_* order:
     * 0 = libraries, 1 = metadata, 2 = assets. Null when nothing is configured,
     * which is what stops every download path.
     */
    @Nullable
    public static String[] roots() {
        String root = LauncherPreferences.PREF_DOWNLOAD_SOURCE;
        if (TextUtils.isEmpty(root)) return null;
        /* Defensive: a bare word is never a source, and rewriting Mojang URLs
         * onto one would produce nonsense that fails halfway through a job. */
        if (!root.contains("://")) return null;
        String[] roots = new String[3];
        if (isOfficial(root)) {
            /* The official host is a legal answer to "which source": its
             * manifest lives under /mc/game, but libraries and assets are on
             * two other hosts, so deriving them from the root would 404. */
            roots[ROOT_LIBRARIES] = "https://libraries.minecraft.net";
            roots[ROOT_METADATA] = "https://piston-meta.mojang.com";
            roots[ROOT_ASSETS] = "https://resources.download.minecraft.net";
            return roots;
        }
        roots[ROOT_LIBRARIES] = root + "/maven";
        roots[ROOT_METADATA] = root;
        roots[ROOT_ASSETS] = root + "/assets";
        return roots;
    }

    private static boolean isOfficial(String root) {
        String lower = root.toLowerCase(Locale.US);
        return lower.contains("piston-meta.mojang.com")
                || lower.contains("launchermeta.mojang.com")
                || lower.contains("piston-data.mojang.com");
    }

    public static void save(Mirror mirror) {
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(PREF_KEY, mirror.root)
                .putString(PREF_MANIFEST, mirror.manifestUrl)
                .putInt(PREF_COUNT, mirror.versionCount)
                .putString(PREF_NAME, mirror.serverName)
                .apply();
        LauncherPreferences.PREF_DOWNLOAD_SOURCE = mirror.root;
    }

    /** Forgets the source, including everything cached about it. */
    public static void clear() {
        LauncherPreferences.DEFAULT_PREF.edit()
                .remove(PREF_KEY)
                .remove(PREF_MANIFEST)
                .remove(PREF_COUNT)
                .remove(PREF_NAME)
                .apply();
        LauncherPreferences.PREF_DOWNLOAD_SOURCE = "";
    }

    /** Probes off the main thread, answers on it. */
    public static void resolve(@Nullable String input, Callback callback) {
        final String root = normalize(input);
        if (root.isEmpty()) {
            Tools.runOnUiThread(() -> callback.onResolved(null));
            return;
        }
        sExecutorService.execute(() -> {
            Mirror mirror = probe(root);
            Tools.runOnUiThread(() -> callback.onResolved(mirror));
        });
    }

    /**
     * Tries the usual manifest locations under the root and keeps the first one
     * that answers with actual versions. What makes a mirror a mirror is the
     * response, not the domain.
     */
    @Nullable
    private static Mirror probe(String root) {
        for (String candidate : manifestCandidates(root)) {
            try {
                String body = DownloadUtils.downloadString(candidate);
                if (!Tools.isValidString(body) || !body.contains("versions")) continue;
                JSONObject json = new JSONObject(body);
                JSONArray versions = json.optJSONArray("versions");
                if (versions == null || versions.length() == 0) continue;

                String name = null;
                JSONObject meta = json.optJSONObject("meta");
                if (meta != null) {
                    String raw = meta.optString("serverName", null);
                    if (!TextUtils.isEmpty(raw)) name = raw;
                }
                Log.i(TAG, "mirror ok: " + candidate + " (" + versions.length() + " versions)");
                return new Mirror(root, candidate, versions.length(), name);
            } catch (Exception e) {
                Log.d(TAG, "no manifest at " + candidate + " (" + e + ")");
            }
        }
        Log.w(TAG, "no version list under " + root);
        return null;
    }

    private static List<String> manifestCandidates(String root) {
        List<String> out = new ArrayList<>(5);
        out.add(root + "/mc/game/version_manifest_v2.json");
        out.add(root + "/mc/game/version_manifest.json");
        out.add(root + "/version_manifest_v2.json");
        out.add(root + "/version_manifest.json");
        /* a bare JSON endpoint is legal too, keep it last so the canonical
         * layout always wins when both exist */
        if (root.toLowerCase(Locale.US).endsWith(".json")) out.add(root);
        return out;
    }
}

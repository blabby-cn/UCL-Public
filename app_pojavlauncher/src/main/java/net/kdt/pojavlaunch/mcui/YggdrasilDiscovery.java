package net.kdt.pojavlaunch.mcui;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns whatever the user typed in the "auth server" field into a real
 * Yggdrasil API root, without knowing anything about the site.
 *
 * People type all of these for the very same server:
 *
 *   littleskin.cn
 *   https://littleskin.cn
 *   https://littleskin.cn/api/yggdrasil
 *   https://littleskin.cn/api/yggdrasil/authserver      (many launchers ask
 *                                                       for the authserver)
 *
 * The first one only works through ALI, the API Location Indication from the
 * authlib-injector spec: any page of a participating skin site answers with an
 * {@code X-Authlib-Injector-API-Location} header pointing at its own API root.
 * LittleSkin does exactly that, and so does every skin site that advertises
 * itself to authlib-injector, which keeps this launcher free of a hard coded
 * site list.
 *
 * When the header is missing the spec says "the URL you asked for is the API
 * root", which is what already configured servers expect.
 */
public final class YggdrasilDiscovery {
    private static final String TAG = "YggdrasilDiscovery";
    private static final String ALI_HEADER = "X-Authlib-Injector-API-Location";
    private static final String AGENT = "UniversalClientLauncher";
    private static final int MAX_ALI_HOPS = 5;
    private static final int TIMEOUT = 10000;
    private static final int MAX_BODY = 16384;

    /* a working server keeps its answer for a while; a broken one is retried
     * soon so that a fixed server is picked up without reinstalling */
    private static final long POSITIVE_TTL = 6 * 60 * 60 * 1000L;
    private static final long NEGATIVE_TTL = 10 * 60 * 1000L;

    /** A resolved authentication server. */
    public static final class Api {
        /** The real API root, e.g. https://littleskin.cn/api/yggdrasil */
        public final String apiRoot;
        /**
         * Where the site itself lives, most likely first. Skin stations keep
         * their avatar endpoint on the site root, not under the API root, so
         * both candidates are tried.
         */
        public final List<String> siteRoots;
        /** meta.serverName when the server advertised one; may be null. */
        public final String serverName;
        /** True when the server actually answered like a Yggdrasil API root. */
        public final boolean verified;

        Api(String apiRoot, List<String> siteRoots, String serverName, boolean verified) {
            this.apiRoot = apiRoot;
            this.siteRoots = siteRoots;
            this.serverName = serverName;
            this.verified = verified;
        }

        /** Base for GET <base><uuid>, the standard profile lookup. */
        public String sessionsBase() {
            return apiRoot + "/sessionserver/session/minecraft/profile/";
        }

        /** Base for POST <base>, the standard authenticate call. */
        public String authserverBase() {
            return apiRoot + "/authserver";
        }
    }

    private static final class Entry {
        final Api api;
        final long stamp;
        final boolean positive;

        Entry(Api api, boolean positive) {
            this.api = api;
            this.stamp = System.currentTimeMillis();
            this.positive = positive;
        }

        boolean expired() {
            long age = System.currentTimeMillis() - stamp;
            return age > (positive ? POSITIVE_TTL : NEGATIVE_TTL);
        }
    }

    private static final class Probe {
        String finalUrl;
        String ali;
        String serverName;
        boolean apiRoot;
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();

    private YggdrasilDiscovery() {
    }

    /* ================================================================== */
    /* public entry points                                                */
    /* ================================================================== */

    /**
     * Resolves user input into an API root. Network access happens here, so
     * call it off the UI thread. Returns an unverified guess when the server
     * cannot be reached, so the caller can still try the address as typed.
     */
    @Nullable
    public static Api resolve(@Nullable String userInput) {
        String url = normalize(userInput);
        if (url == null) return null;

        Api cached = cached(url);
        if (cached != null) return cached;

        String current = url;
        String serverName = null;
        boolean verified = false;
        boolean reached = false;

        for (int hop = 0; hop < MAX_ALI_HOPS; hop++) {
            Probe probe = probe(current);
            if (probe == null) break;
            reached = true;
            if (!TextUtils.isEmpty(probe.serverName)) serverName = probe.serverName;
            if (probe.apiRoot) verified = true;

            String next = probe.ali;
            if (TextUtils.isEmpty(next) || sameUrl(next, current)) break;
            Log.d(TAG, "ALI " + current + " -> " + next);
            current = next;
        }

        Api api = build(current, originOf(url), serverName, verified);
        if (api != null) {
            remember(url, api, reached);
            /* The resolved root is a cache key of its own: the login flow stores
             * it on the account, and the avatar resolver later asks for it
             * directly instead of probing the site a second time. */
            remember(api.apiRoot, api, true);
        }
        return api;
    }

    /**
     * Local only: derives the site roots for an already known API root. Never
     * touches the network, so it is safe to call from anywhere.
     */
    @Nullable
    public static Api forApiRoot(@Nullable String apiRoot) {
        if (TextUtils.isEmpty(apiRoot)) return null;
        String normalized = normalize(apiRoot);
        if (normalized == null) return null;
        Api cached = cached(normalized);
        if (cached != null) return cached;
        return build(normalized, null, null, false);
    }

    /** Drops the memoised answer, e.g. after the user edited the address. */
    public static void forget(@Nullable String userInput) {
        String url = normalize(userInput);
        if (url == null) return;
        synchronized (CACHE) {
            CACHE.remove(url);
        }
    }

    /* ================================================================== */
    /* normalisation                                                      */
    /* ================================================================== */

    /**
     * Adds a scheme when missing and drops the parts that are not part of an
     * API root. authlib-injector forbids downgrading to cleartext, so a bare
     * host always becomes https.
     */
    @Nullable
    public static String normalize(@Nullable String input) {
        if (input == null) return null;
        String url = input.trim();
        if (url.isEmpty()) return null;
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            url = "https://" + url;
        }
        url = stripTrailingSlash(url);
        url = stripNoiseSuffix(url);
        return url.isEmpty() ? null : url;
    }

    private static final String[] NOISE_SUFFIXES = {
            "/authserver/authenticate",
            "/authserver/refresh",
            "/authserver/validate",
            "/authserver/invalidate",
            "/authserver/signout",
            "/sessionserver/session/minecraft/profile",
            "/sessionserver/session/minecraft",
            "/sessionserver/session",
            "/authserver",
            "/sessionserver",
            "/minecraftservices",
    };

    private static String stripNoiseSuffix(String url) {
        String out = url;
        boolean changed = true;
        while (changed) {
            changed = false;
            String lower = out.toLowerCase(Locale.US);
            for (String suffix : NOISE_SUFFIXES) {
                if (lower.endsWith(suffix)) {
                    out = stripTrailingSlash(out.substring(0, out.length() - suffix.length()));
                    changed = true;
                    break;
                }
            }
        }
        return out;
    }

    private static String stripTrailingSlash(String url) {
        String out = url;
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static boolean sameUrl(String a, String b) {
        if (a == null || b == null) return false;
        return stripTrailingSlash(a).equalsIgnoreCase(stripTrailingSlash(b));
    }

    /* ================================================================== */
    /* probing                                                            */
    /* ================================================================== */

    @Nullable
    private static Probe probe(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json, text/html;q=0.8, */*;q=0.5");
            conn.setRequestProperty("User-Agent", AGENT);

            int code = conn.getResponseCode();
            String ali = conn.getHeaderField(ALI_HEADER);

            String body = null;
            try (InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream()) {
                if (stream != null) body = readLimited(stream, MAX_BODY);
            } catch (Exception ignored) {
                /* the header is what matters, a body we cannot read is fine */
            }

            Probe probe = new Probe();
            /* after redirects conn.getURL() is the page that really answered,
             * which is what a relative ALI has to be resolved against */
            probe.finalUrl = stripTrailingSlash(conn.getURL().toString());
            probe.ali = absolutize(ali, probe.finalUrl);
            probe.serverName = serverNameOf(body);
            probe.apiRoot = looksLikeApiRoot(body);
            return probe;
        } catch (Exception e) {
            Log.d(TAG, "probe failed for " + url + ": " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String absolutize(@Nullable String value, String baseUrl) {
        if (TextUtils.isEmpty(value)) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        String lower = trimmed.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return stripTrailingSlash(trimmed);
        }
        try {
            return stripTrailingSlash(new URL(new URL(baseUrl), trimmed).toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean looksLikeApiRoot(@Nullable String body) {
        if (TextUtils.isEmpty(body)) return false;
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) return false;
        try {
            JSONObject json = new JSONObject(trimmed);
            return json.has("meta")
                    || json.has("skinDomains")
                    || json.has("signaturePublickey")
                    || json.has("signaturePublicKey");
        } catch (Exception e) {
            return false;
        }
    }

    private static String serverNameOf(@Nullable String body) {
        if (TextUtils.isEmpty(body)) return null;
        try {
            JSONObject meta = new JSONObject(body.trim()).optJSONObject("meta");
            if (meta == null) return null;
            String name = meta.optString("serverName", null);
            return TextUtils.isEmpty(name) ? null : name;
        } catch (Exception e) {
            return null;
        }
    }

    /* ================================================================== */
    /* site root derivation                                               */
    /* ================================================================== */

    private static Api build(String apiRoot, @Nullable String siteHint, String serverName,
                             boolean verified) {
        String root = stripTrailingSlash(apiRoot);
        if (root.isEmpty()) return null;
        List<String> roots = siteRootsOf(root);
        /* The ALI may point at another host than the one the user typed (ely.by
         * keeps its API on account.ely.by while the site, and its avatar
         * endpoint, stay on ely.by), so the typed origin is a candidate too. */
        if (!TextUtils.isEmpty(siteHint) && !roots.contains(siteHint)) {
            roots.add(0, siteHint);
        }
        return new Api(root, roots, serverName, verified);
    }

    /**
     * Skin stations serve their avatar endpoint from the site root, while the
     * API usually sits under an /api/... prefix. Strip everything from the
     * last /api/ segment, and keep the bare origin as a second candidate for
     * installations that live in a sub directory.
     *
     *   /api/yggdrasil          -> [origin]
     *   /skin/api/yggdrasil     -> [origin/skin, origin]
     *   /api/authlib-injector   -> [origin]        (ely.by)
     *   /yggdrasil              -> [origin]
     */
    private static List<String> siteRootsOf(String apiRoot) {
        List<String> roots = new ArrayList<>(2);
        String origin = originOf(apiRoot);
        if (origin == null) return roots;

        String path = pathOf(apiRoot);
        String stripped = stripApiSegments(path == null ? "" : path);
        if (!TextUtils.isEmpty(stripped)) roots.add(origin + stripped);
        if (!roots.contains(origin)) roots.add(origin);
        return roots;
    }

    /** Everything before the API prefix, or "" when the API sits at the root. */
    private static String stripApiSegments(String path) {
        if (TextUtils.isEmpty(path) || "/".equals(path)) return "";
        String lower = path.toLowerCase(Locale.US);

        int index = lower.lastIndexOf("/api/");
        if (index >= 0) return stripTrailingSlash(path.substring(0, index));
        if (lower.endsWith("/api")) {
            return stripTrailingSlash(path.substring(0, path.length() - 4));
        }

        /* no /api/ marker: drop the last segment only when it is clearly the
         * API folder rather than a real sub directory */
        int slash = path.lastIndexOf('/');
        if (slash > 0) {
            String last = lower.substring(slash + 1);
            if (last.startsWith("yggdrasil") || last.equals("authlib-injector")
                    || last.equals("minecraftservices")) {
                return stripTrailingSlash(path.substring(0, slash));
            }
        }
        return "";
    }

    @Nullable
    private static String originOf(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String authority = uri.getAuthority();
            if (TextUtils.isEmpty(scheme) || TextUtils.isEmpty(authority)) return null;
            return scheme + "://" + authority;
        } catch (Exception e) {
            return null;
        }
    }

    private static String pathOf(String url) {
        try {
            return new URI(url).getPath();
        } catch (Exception e) {
            return null;
        }
    }

    /* ================================================================== */
    /* cache                                                              */
    /* ================================================================== */

    @Nullable
    private static Api cached(String key) {
        synchronized (CACHE) {
            Entry entry = CACHE.get(key);
            if (entry == null) return null;
            if (entry.expired()) {
                CACHE.remove(key);
                return null;
            }
            return entry.positive ? entry.api : null;
        }
    }

    private static void remember(String key, Api api, boolean reached) {
        synchronized (CACHE) {
            CACHE.put(key, new Entry(api, reached));
        }
    }

    /* ================================================================== */
    /* small helpers                                                      */
    /* ================================================================== */

    static String readLimited(InputStream stream, int max) throws IOException {
        byte[] buffer = new byte[4096];
        StringBuilder out = new StringBuilder();
        int total = 0;
        int read;
        while (total < max && (read = stream.read(buffer)) != -1) {
            out.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            total += read;
        }
        return out.toString();
    }

    /** Base for GET <base><uuid>; used by the avatar resolver as well. */
    @NonNull
    public static String sessionsBaseOf(@Nullable String apiRoot) {
        Api api = forApiRoot(apiRoot);
        return api != null ? api.sessionsBase() : "";
    }
}

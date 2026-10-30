package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Client for the Blabby account system (login.blabby.top).
 *
 * The website authenticates with a plain session cookie, so the flow is:
 *   POST /api/login  {login, password}  -> {success, error}
 *   GET  /api/me                        -> {loggedIn, username, nickname, email, avatarUrl, shortUid}
 * The session cookie returned by the first call has to be sent back with the
 * second one, which is why the response headers are parsed here instead of
 * using a one-off request.
 */
final class BlabbyClient {
    private static final String TAG = "BlabbyClient";

    static final String BASE = "https://login.blabby.top";
    private static final int TIMEOUT = 15000;

    /** What the launcher needs from a Blabby account. */
    static final class Profile {
        String username = "";
        String nickname = "";
        String email = "";
        String avatarUrl = "";
    }

    interface Callback {
        void onSuccess(Profile profile);

        void onError(String message);
    }

    private BlabbyClient() {
    }

    /** Runs the whole login on a worker thread and reports back there too. */
    static void login(final Context context, final String login, final String password,
                      final Callback callback) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("login", login);
                body.put("password", password);
                Response response = post("/api/login", body.toString(), null);
                JSONObject result = new JSONObject(response.body);
                if (!result.optBoolean("success")) {
                    callback.onError(friendly(context, result.optString("error", "")));
                    return;
                }
                Response me = get("/api/me", response.cookie);
                JSONObject user = new JSONObject(me.body);
                if (!user.optBoolean("loggedIn")) {
                    callback.onError(context.getString(R.string.ucl_net_session_invalid));
                    return;
                }
                Profile profile = new Profile();
                profile.username = user.optString("username", login);
                profile.nickname = user.optString("nickname", "");
                profile.email = user.optString("email", "");
                profile.avatarUrl = user.optString("avatarUrl", "");
                if (profile.avatarUrl.isEmpty()) profile.avatarUrl = BASE + "/api/avatar/default";
                else if (profile.avatarUrl.startsWith("/")) profile.avatarUrl = BASE + profile.avatarUrl;
                callback.onSuccess(profile);
            } catch (Exception e) {
                Log.w(TAG, "Blabby login failed", e);
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                callback.onError(message);
            }
        }, "blabby-login").start();
    }

    /**
     * Stores the avatar where {@link net.kdt.pojavlaunch.value.MinecraftAccount}
     * looks for a face, so the account centre can show it without its own cache.
     */
    static boolean saveAvatar(String username, String avatarUrl) {
        File target = new File(Tools.DIR_CACHE, username + ".png");
        try {
            byte[] data = read(avatarUrl, null);
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap == null) return false;
            try (OutputStream out = new java.io.FileOutputStream(target)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not cache the Blabby avatar", e);
            return false;
        }
    }

    /**
     * The API answers with bare English strings; say the same thing in the
     * language the user is reading.
     *
     * <p>The mapping is by meaning, not by wording: the site may rephrase its
     * messages, and an unknown one is passed through rather than swallowed.
     */
    private static String friendly(Context context, String error) {
        if (error == null || error.isEmpty()) {
            return context.getString(R.string.ucl_login_failed_generic);
        }
        String lower = error.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("invalid credentials")) {
            return context.getString(R.string.ucl_login_bad_credentials);
        }
        if (lower.contains("not verified")) {
            return context.getString(R.string.ucl_login_not_verified);
        }
        if (lower.contains("deactivated")) {
            return context.getString(R.string.ucl_login_deactivated);
        }
        if (lower.contains("too many")) {
            return context.getString(R.string.ucl_login_too_many);
        }
        return error;
    }

    private static final class Response {
        String body = "";
        String cookie = "";
    }

    private static Response get(String path, String cookie) throws IOException {
        return read(open(path, "GET", cookie));
    }

    private static Response post(String path, String json, String cookie) throws IOException {
        HttpURLConnection connection = open(path, "POST", cookie);
        connection.setRequestProperty("Content-Type", "application/json");
        if (json != null) {
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
        }
        return read(connection);
    }

    private static HttpURLConnection open(String path, String method, String cookie)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setRequestProperty("User-Agent", "UniversalClientLauncher");
        connection.setRequestProperty("Accept", "application/json");
        if (cookie != null && !cookie.isEmpty()) {
            connection.setRequestProperty("Cookie", cookie);
        }
        return connection;
    }

    private static byte[] read(String url, String cookie) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setRequestProperty("User-Agent", "UniversalClientLauncher");
        if (cookie != null && !cookie.isEmpty()) {
            connection.setRequestProperty("Cookie", cookie);
        }
        try (InputStream in = connection.getInputStream()) {
            return readAll(in);
        }
    }

    private static Response read(HttpURLConnection connection) throws IOException {
        Response response = new Response();
        int code = connection.getResponseCode();
        InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        response.body = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        response.cookie = joinCookies(connection);
        if (code >= 400 && response.body.isEmpty()) {
            throw new IOException("HTTP " + code);
        }
        return response;
    }

    private static String joinCookies(HttpURLConnection connection) {
        StringBuilder builder = new StringBuilder();
        for (java.util.Map.Entry<String, java.util.List<String>> entry
                : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
            for (String raw : entry.getValue()) {
                int end = raw.indexOf(';');
                String pair = end > 0 ? raw.substring(0, end) : raw;
                if (builder.length() > 0) builder.append("; ");
                builder.append(pair.trim());
            }
        }
        return builder.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }
}

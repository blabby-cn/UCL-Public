package net.kdt.pojavlaunch.mcui;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Minimal Yggdrasil-compatible (authlib-injector style) client used by the UCL
 * account center for external auth servers and the Blabby account system.
 */
public class YggdrasilClient {
    private static final String TAG = "YggdrasilClient";

    public interface LoginCallback {
        void onSuccess(MinecraftAccount account);
        void onError(String message);
    }

    private final String mBaseUrl;
    private final String mUsername;
    private final String mPassword;

    public YggdrasilClient(@NonNull String baseUrl, @NonNull String username, @NonNull String password) {
        String normalized = YggdrasilDiscovery.normalize(baseUrl);
        mBaseUrl = normalized != null ? normalized : baseUrl.trim();
        mUsername = username;
        mPassword = password;
    }

    /**
     * Resolves the address the user typed into a real API root before talking
     * to it. People type "littleskin.cn" as often as the full
     * "https://littleskin.cn/api/yggdrasil", and only the ALI header of the
     * former tells us where the API actually lives. When discovery cannot
     * reach anything the typed address is used as-is, so a working setup can
     * never be broken by a failing probe.
     */
    private String resolveBaseUrl() {
        try {
            YggdrasilDiscovery.Api api = YggdrasilDiscovery.resolve(mBaseUrl);
            if (api != null && !TextUtils.isEmpty(api.apiRoot)) {
                if (!api.apiRoot.equals(mBaseUrl)) {
                    Log.i(TAG, "auth server " + mBaseUrl + " resolved to " + api.apiRoot);
                }
                return api.apiRoot;
            }
        } catch (Exception e) {
            Log.w(TAG, "auth server discovery failed for " + mBaseUrl, e);
        }
        return mBaseUrl;
    }

    public void login(@NonNull LoginCallback callback) {
        new Thread(() -> {
            try {
                MinecraftAccount account = performLogin();
                Tools.runOnUiThread(() -> callback.onSuccess(account));
            } catch (final Exception e) {
                Log.e(TAG, "Yggdrasil login failed", e);
                String raw = e.getMessage();
                final String message = (raw == null || raw.isEmpty())
                        ? e.getClass().getSimpleName() : raw;
                Tools.runOnUiThread(() -> callback.onError(message));
            }
        }).start();
    }

    private MinecraftAccount performLogin() throws Exception {
        if (mUsername.isEmpty() || mPassword.isEmpty()) {
            throw new IllegalArgumentException("username or password is empty");
        }
        String clientToken = UUID.randomUUID().toString();
        JSONObject payload = new JSONObject();
        JSONObject agent = new JSONObject();
        agent.put("name", "Minecraft");
        agent.put("version", 1);
        payload.put("agent", agent);
        payload.put("username", mUsername);
        payload.put("password", mPassword);
        payload.put("clientToken", clientToken);
        payload.put("requestUser", true);

        String base = resolveBaseUrl();
        JSONObject response = postJson(base + "/authserver/authenticate", payload.toString());

        String accessToken = response.getString("accessToken");
        JSONArray profiles = response.optJSONArray("availableProfiles");
        if (profiles == null || profiles.length() == 0) {
            throw new IllegalStateException("no game profile on this account");
        }
        JSONObject profile = profiles.getJSONObject(0);

        MinecraftAccount account = new MinecraftAccount();
        account.accessToken = accessToken;
        account.clientToken = clientToken;
        account.profileId = profile.getString("id");
        account.username = profile.optString("name", mUsername);
        account.isMicrosoft = false;
        account.msaRefreshToken = "0";
        account.expiresAt = 0;
        /* remember where the account came from: the avatar of an external
         * authlib account can only be resolved on that server */
        account.authBaseUrl = base;
        account.save();
        account.updateSkinFace();
        return account;
    }

    private JSONObject postJson(String urlString, String body) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestMethod("POST");
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.connect();
        try (OutputStream wr = conn.getOutputStream()) {
            wr.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String content;
        if (code >= 200 && code < 300) {
            content = Tools.read(conn.getInputStream());
        } else {
            content = Tools.read(conn.getErrorStream());
            String errorMessage = content;
            try {
                JSONObject errorJson = new JSONObject(content);
                errorMessage = errorJson.optString("errorMessage", errorJson.optString("error", content));
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("HTTP " + code + ": " + errorMessage);
        }
        return new JSONObject(content);
    }
}

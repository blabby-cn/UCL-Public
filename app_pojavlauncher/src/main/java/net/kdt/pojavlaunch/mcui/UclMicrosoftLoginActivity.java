package net.kdt.pojavlaunch.mcui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.BaseActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.value.MinecraftAccount;

/**
 * Standalone Microsoft OAuth login screen for the UCL account center.
 * Opens the desktop OAuth flow in a WebView, captures the auth code from the
 * redirect URL and exchanges it in the background.
 */
public class UclMicrosoftLoginActivity extends BaseActivity {
    private static final String MS_AUTH_URL =
            "https://login.live.com/oauth20_authorize.srf"
                    + "?client_id=00000000402b5328"
                    + "&response_type=code"
                    + "&scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL"
                    + "&redirect_url=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf";
    private static final String REDIRECT_PREFIX = "https://login.live.com/oauth20_desktop.srf";
    /* the mobile OAuth flow lands on this custom scheme instead of the
     * desktop redirect; a plain WebView load fails with
     * ERR_UNKNOWN_URL_SCHEME, so the code has to be captured here */
    private static final String MS_XAL_PREFIX = "ms-xal-";

    private WebView mWebView;
    private ProgressBar mProgressBar;
    private boolean mLoginInProgress;

    @Override
    protected boolean shouldIgnoreNotch() {
        return true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ucl_microsoft_login);
        mWebView = findViewById(R.id.ucl_ms_webview);
        mProgressBar = findViewById(R.id.ucl_ms_progress);

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        mWebView.setWebViewClient(new AuthTrackingClient());
        // 黑屏修复：部分设备/ROM 上硬件加速 WebView 的 GL surface 首帧未合成，
        // 整块显示默认黑。强制软件层渲染，由 View 系统合成位图，消除黑屏。
        // 若因此回归滚动/输入性能，可改回 View.LAYER_TYPE_HARDWARE。
        mWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        mWebView.loadUrl(MS_AUTH_URL);
    }

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) {
            mWebView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        mWebView.destroy();
        super.onDestroy();
    }

    private void handleAuthCode(String authCode) {
        if (mLoginInProgress) return;
        mLoginInProgress = true;
        mProgressBar.setVisibility(View.VISIBLE);
        new MicrosoftBackgroundLogin(false, authCode).performLogin(null,
                new DoneListener() {
                    @Override
                    public void onLoginDone(MinecraftAccount account) {
                        Toast.makeText(UclMicrosoftLoginActivity.this,
                                R.string.ucl_login_success, Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    }
                },
                new ErrorListener() {
                    @Override
                    public void onLoginError(Throwable errorMessage) {
                        mLoginInProgress = false;
                        mProgressBar.setVisibility(View.GONE);
                        Toast.makeText(UclMicrosoftLoginActivity.this,
                                getString(R.string.ucl_login_failed, String.valueOf(errorMessage.getMessage())),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private class AuthTrackingClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url.startsWith(MS_XAL_PREFIX) || url.startsWith(REDIRECT_PREFIX)) {
                Uri uri = Uri.parse(url);
                String code = uri.getQueryParameter("code");
                if (code != null && !code.isEmpty()) {
                    handleAuthCode(code);
                } else {
                    Toast.makeText(UclMicrosoftLoginActivity.this,
                            R.string.ucl_login_cancelled, Toast.LENGTH_SHORT).show();
                    finish();
                }
                return true;
            }
            if (url.contains("res=cancel")) {
                finish();
                return true;
            }
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (url.startsWith(MS_XAL_PREFIX) || url.startsWith(REDIRECT_PREFIX)) {
                Uri uri = Uri.parse(url);
                String code = uri.getQueryParameter("code");
                if (code != null && !code.isEmpty()) {
                    handleAuthCode(code);
                    return;
                }
            }
            mProgressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            mProgressBar.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // 诊断用：若黑屏实为方向③（OAuth 端点被网络拦截），logcat 会暴露具体错误
            Log.e("UclMicrosoftLoginActivity",
                    "WebView error " + error.getErrorCode() + ": " + error.getDescription()
                            + " @ " + request.getUrl());
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            Log.e("UclMicrosoftLoginActivity",
                    "HTTP error " + errorResponse.getStatusCode() + " @ " + request.getUrl());
        }
    }
}

package net.kdt.pojavlaunch.mcui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.value.MinecraftAccount;

/**
 * Microsoft OAuth login embedded in the account center right column.
 *
 * The panel owns a container instead of a single WebView, because the account
 * center has to be able to sign in several Microsoft accounts in a row: after a
 * successful login the whole browsing session is dropped and a brand new
 * WebView is built, so the sign-in page shows up again instead of the dead
 * redirect page (a consumed oauth20_desktop.srf URL renders as a black plate).
 *
 * Until the page has painted there is a loading screen over it: label, spinner
 * and a determinate bar fed by the WebView load progress.
 */
public class UclMicrosoftLoginPanel {
    private static final String TAG = "UclMsLoginPanel";

    private static final String MS_CLIENT_ID = "00000000402b5328";
    private static final String MS_REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";

    /* The authorize URL, byte for byte the one upstream Pojav sends.
     *
     * The spelling of the redirect parameter has to stay "redirect_url" here,
     * because the token exchange in MicrosoftBackgroundLogin posts exactly that
     * name. Sending a proper "redirect_uri" on this page while the exchange
     * still says "redirect_url" leaves Microsoft without a matching redirect on
     * the second half of the flow, and oauth20_token.srf answers
     * "400 Bad Request" -- which is what surfaced as a failed login.
     *
     * Multi-account support therefore does not ride on request parameters: the
     * panel drops the Microsoft session itself (see wipeSession) so the next
     * login really does ask for credentials. */
    private static String authUrl() {
        return "https://login.live.com/oauth20_authorize.srf"
                + "?client_id=" + MS_CLIENT_ID
                + "&response_type=code"
                + "&scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL"
                + "&redirect_url=" + Uri.encode(MS_REDIRECT_URI);
    }
    private static final String REDIRECT_PREFIX = "https://login.live.com/oauth20_desktop.srf";
    /* the mobile flow lands on this custom scheme, which a WebView cannot load
     * (ERR_UNKNOWN_URL_SCHEME) -- the auth code has to be captured here */
    private static final String MS_XAL_PREFIX = "ms-xal-";

    private static final int COLOR_PLATE = 0xFF1B1B1B;
    private static final int COLOR_ACCENT = 0xFF7BCB4F;
    private static final int COLOR_TRACK = 0xFF3A3A3A;
    private static final int COLOR_TEXT = 0xFFECECEC;

    public interface Callback {
        void onLoginDone(MinecraftAccount account);

        void onLoginError(String message);

        void onLoginCancelled();
    }

    private final Activity mActivity;
    private final Callback mCallback;
    private final FrameLayout mContainer;
    private final LinearLayout mLoadingView;
    private final ProgressBar mProgressBar;
    private WebView mWebView;
    private boolean mLoaded;
    private boolean mLoginInProgress;
    /* true once this panel has produced an account: the next start() has to
     * drop the Microsoft session first, otherwise the account picker would be
     * skipped and the same account would be written over and over */
    private boolean mSessionSpent = true;
    private boolean mNeedsCookieWipe = true;

    @SuppressLint("SetJavaScriptEnabled")
    public UclMicrosoftLoginPanel(Activity activity, Callback callback) {
        mActivity = activity;
        mCallback = callback;
        mContainer = new FrameLayout(activity);
        mContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mContainer.setBackgroundColor(COLOR_PLATE);

        mProgressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        mLoadingView = buildLoadingView();

        createWebView();
        mContainer.addView(mLoadingView);
    }

    public View getView() {
        return mContainer;
    }

    /* "loading the sign-in page": spinner, label, and a progress bar that follows
     * the real load progress instead of an endless animation */
    private LinearLayout buildLoadingView() {
        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(COLOR_PLATE);

        ProgressBar spinner = new ProgressBar(mActivity);
        LinearLayout.LayoutParams spinnerParams =
                new LinearLayout.LayoutParams(dp(44), dp(44));
        spinnerParams.gravity = Gravity.CENTER_HORIZONTAL;
        spinner.setLayoutParams(spinnerParams);
        if (spinner.getIndeterminateDrawable() != null) {
            spinner.getIndeterminateDrawable().setTint(COLOR_ACCENT);
        }
        root.addView(spinner);

        TextView label = new TextView(mActivity);
        label.setText(R.string.ucl_ms_loading);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.gravity = Gravity.CENTER_HORIZONTAL;
        labelParams.topMargin = dp(16);
        label.setLayoutParams(labelParams);
        root.addView(label);

        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(240), dp(6));
        barParams.gravity = Gravity.CENTER_HORIZONTAL;
        barParams.topMargin = dp(12);
        mProgressBar.setLayoutParams(barParams);
        mProgressBar.setMax(100);
        mProgressBar.setProgress(0);
        mProgressBar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        mProgressBar.setProgressBackgroundTintList(ColorStateList.valueOf(COLOR_TRACK));
        root.addView(mProgressBar);

        return root;
    }

    private int dp(int value) {
        return (int) (value * mActivity.getResources().getDisplayMetrics().density + 0.5f);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        if (mWebView != null) {
            mContainer.removeView(mWebView);
            mWebView.destroy();
        }
        WebView webView = new WebView(mActivity);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // white plate instead of the platform default: any gap before the page
        // paints reads as "still loading", never as a black hole
        webView.setBackgroundColor(0xFFFFFFFF);
        webView.setVisibility(View.INVISIBLE);
        // never prefill the sign-in form with the account added last
        webView.clearFormData();
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        webView.setWebViewClient(new AuthClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mProgressBar.setProgress(newProgress);
            }
        });
        // same black screen mitigation as the standalone activity: some ROMs
        // fail to composite the accelerated WebView surface on its first frame
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        mContainer.addView(webView);
        if (mLoadingView != null) mContainer.bringChildToFront(mLoadingView);
        mWebView = webView;
    }

    private void showLoading() {
        if (mLoadingView != null) mLoadingView.setVisibility(View.VISIBLE);
        if (mWebView != null) mWebView.setVisibility(View.INVISIBLE);
        mProgressBar.setProgress(0);
    }

    private void showPage() {
        if (mWebView != null) mWebView.setVisibility(View.VISIBLE);
        if (mLoadingView != null) mLoadingView.setVisibility(View.GONE);
    }

    /**
     * Called whenever the Microsoft entry is tapped. Adds an account from
     * scratch: if the previous sign-in already produced an account, the
     * Microsoft session is thrown away first so the user lands on a real
     * sign-in page (or on the account picker) instead of being silently
     * re-authenticated as the account that was added last.
     */
    public void begin() {
        if (mSessionSpent) {
            wipeSession();
        }
        start();
    }

    /** Loads the OAuth page once; later calls keep the live session. */
    public void start() {
        if (mLoaded) return;
        mLoaded = true;
        showLoading();
        if (mNeedsCookieWipe) {
            mNeedsCookieWipe = false;
            clearCookiesThenLoad();
        } else if (mWebView != null) {
            mWebView.loadUrl(authUrl());
        }
    }

    /** Starts over, e.g. after the user cancelled or the token expired. */
    public void restart() {
        mLoginInProgress = false;
        mLoaded = true;
        showLoading();
        if (mWebView != null) mWebView.loadUrl(authUrl());
    }

    /**
     * Drops the Microsoft session: cookies, saved form data and the WebView
     * itself. Cookie removal is asynchronous, so the reload waits for the
     * callback -- flushing right after removeAllCookies() can otherwise write
     * the old cookies back to disk and the next sign-in reuses them.
     */
    private void wipeSession() {
        mLoginInProgress = false;
        mLoaded = false;
        mSessionSpent = false;
        mNeedsCookieWipe = true;
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.removeAllCookies(null);
        try {
            WebViewDatabase database = WebViewDatabase.getInstance(mActivity);
            database.clearFormData();
            database.clearHttpAuthUsernamePassword();
        } catch (Throwable ignored) {
            // form data hygiene is best effort only
        }
        createWebView();
        showLoading();
    }

    private void clearCookiesThenLoad() {
        final CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.removeAllCookies(value -> {
            cookies.flush();
            if (mWebView != null && mLoaded) mWebView.loadUrl(authUrl());
        });
    }

    public void release() {
        if (mWebView != null) {
            mContainer.removeView(mWebView);
            mWebView.destroy();
            mWebView = null;
        }
    }

    private void handleAuthCode(String authCode) {
        if (mLoginInProgress) return;
        mLoginInProgress = true;
        new MicrosoftBackgroundLogin(false, authCode).performLogin(null,
                new DoneListener() {
                    @Override
                    public void onLoginDone(MinecraftAccount account) {
                        mLoginInProgress = false;
                        /* the session now belongs to that account: the next
                         * visit to the Microsoft entry must sign in again */
                        mSessionSpent = true;
                        mLoaded = false;
                        mCallback.onLoginDone(account);
                    }
                },
                new ErrorListener() {
                    @Override
                    public void onLoginError(Throwable errorMessage) {
                        mLoginInProgress = false;
                        /* a failed exchange leaves the WebView on a dead
                         * redirect page: force a clean sign-in next time */
                        mSessionSpent = true;
                        mLoaded = false;
                        String detail = errorMessage.getMessage() == null
                                ? String.valueOf(errorMessage) : errorMessage.getMessage();
                        mCallback.onLoginError(detail);
                    }
                });
    }

    private boolean consumeRedirect(String url) {
        if (url == null) return false;
        if (!url.startsWith(MS_XAL_PREFIX) && !url.startsWith(REDIRECT_PREFIX)) return false;
        Uri uri = Uri.parse(url);
        String code = uri.getQueryParameter("code");
        if (code != null && !code.isEmpty()) {
            handleAuthCode(code);
        } else {
            mLoginInProgress = false;
            /* the session is not usable any more (the user dismissed the
             * consent page, or the flow died): start over next time */
            mSessionSpent = true;
            mLoaded = false;
            mCallback.onLoginCancelled();
        }
        return true;
    }

    private class AuthClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (consumeRedirect(request.getUrl().toString())) return true;
            return super.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (consumeRedirect(url)) return;
            showLoading();
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            mProgressBar.setProgress(100);
            showPage();
            super.onPageFinished(view, url);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            Log.e(TAG, "WebView error " + error.getErrorCode() + ": "
                    + error.getDescription() + " @ " + request.getUrl());
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            Log.e(TAG, "HTTP error " + errorResponse.getStatusCode() + " @ " + request.getUrl());
        }
    }
}

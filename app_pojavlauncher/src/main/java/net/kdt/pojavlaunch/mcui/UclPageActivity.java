package net.kdt.pojavlaunch.mcui;

import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.StatFs;
import android.text.Editable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.BaseActivity;
import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.gamepad.Gamepad;
import net.kdt.pojavlaunch.customcontrols.gamepad.GamepadMapperAdapter;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.apache.commons.io.FileUtils;

import fr.spse.gamepad_remapper.RemapperManager;
import fr.spse.gamepad_remapper.RemapperView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kdt.pojavlaunch.mcui.UclAlert;

/**
 * Single activity hosting all UCL sub pages: account center, version selection,
 * download manager and settings. Replaces the legacy Pojav launcher UI shell.
 */
public class UclPageActivity extends BaseActivity implements View.OnClickListener,
        UclDownloadManager.TaskUpdateListener {

    public static final int PAGE_ACCOUNT = 0;
    public static final int PAGE_VERSION = 1;
    public static final int PAGE_DOWNLOAD = 2;
    public static final int PAGE_SETTINGS = 3;
    /* the home screen is a page too: this activity hosts everything */
    public static final int PAGE_HOME = 4;
    /* a page we do not own (the Blabby account site): still a page of this
     * activity, so it enters and leaves like every other one */
    public static final int PAGE_WEB = 5;
    /** The pad mapper. Reached from the control page, not from the home rail:
     *  it is a step deeper than settings, and back returns there. */
    public static final int PAGE_GAMEPAD = 6;
    public static final String EXTRA_PAGE = "ucl_page";
    /* requested by the launcher activities that used to open the home screen */
    public static final String EXTRA_OPEN_AUTH = "ucl_open_auth";
    public static final String EXTRA_OPEN_SETTINGS = "ucl_open_settings";

    private static final float REFERENCE_WIDTH = 1920f;
    private static final int SLIDE_DURATION = 300;
    /**
     * The drawer is a shorter gesture than a page change: it covers a third of
     * the screen, so the same 300ms that reads as a page transition here reads
     * as a slow panel. 220 is what the reference feels like.
     */
    private static final int DRAWER_DURATION = 220;
    /* the console line the Blabby pages log from their own back arrow */
    private static final String WEB_BACK_SIGNAL = "native_back_to_prev_activity";
    /**
     * Injected into the web page once it has loaded.
     *
     * <p>The pages already set {@code user-select:none} on the body, but the
     * long press on their app bar still reached the platform's selection code,
     * which answers by floating its own overlay right over the page title.
     * Selecting anything on a read-only form is meaningless anyway.
     */
    private static final String NO_SELECT_JS =
            "(function(){"
                    + "if(document.getElementById('ucl-noselect'))return;"
                    + "var s=document.createElement('style');s.id='ucl-noselect';"
                    + "s.textContent='*{-webkit-user-select:none!important;user-select:none!important;"
                    + "-webkit-touch-callout:none!important}';"
                    + "(document.head||document.documentElement).appendChild(s);"
                    + "document.addEventListener('selectstart',function(e){e.preventDefault();},true);"
                    + "document.addEventListener('contextmenu',function(e){e.preventDefault();},true);"
                    + "})();";
    private static final long WEB_TIMEOUT_MS = 20000L;
    /* home concept art is authored on a 1920x1080 canvas */
    private static final float HOME_BUTTON_TEXT_SIZE = 27f;
    private static final float HOME_SUBTITLE_TEXT_SIZE = 26f;
    private static final float HOME_NAME_TEXT_SIZE = 20f;
    /**
     * The splash line sits on the same canvas. 34 px is the size at which the
     * line occupies the band the old 1140x228 bitmap did -- its ink was 101 px
     * tall inside a 228 px frame drawn at 19.79% of the canvas width.
     */
    private static final float HOME_SPLASH_TEXT_SIZE = 34f;
    /**
     * One launch in five shows the subtitle under the logo. A subtitle that is
     * always there is a caption; one that shows up now and then is a signature.
     * Rolled once per process -- see {@link #sSubtitleThisRun}.
     */
    private static final float SUBTITLE_CHANCE = 0.2f;
    /**
     * The roll for this process. Kept static so a rotation, a page change or a
     * second visit to the home screen cannot re-roll it: the promise is "one
     * launch, one decision", not "one redraw, one decision".
     */
    private static Boolean sSubtitleThisRun;

    /**
     * How far CJK ink sits below the centre of its line box, in em.
     *
     * <p>Noto Sans CJK: ascender 1160 / descender 288 centres the box at 436
     * units above the baseline, but the ideographs run -79..840 and centre at
     * 380. A TextView with font padding off centres the box, so the ink lands
     * 0.056 em low -- which on a 27 px label is 1.5 px, and it is why the
     * labels read as sitting on the bottom edge even when nothing is clipped.
     */
    private static final float CJK_INK_OFFSET_EM = 0.056f;
    private static final String AUTH_PREFS = "ucl_auth";

    private static final int AUTH_MODE_OFFLINE = 0;
    private static final int AUTH_MODE_MICROSOFT = 1;
    private static final int AUTH_MODE_YGGDRASIL = 2;
    private static final int AUTH_MODE_BLABBY = 3;

    /* entry order must match the ids bound in setupAccountPage() */
    private static final int[] ENTRY_MODES = {
            AUTH_MODE_BLABBY, AUTH_MODE_MICROSOFT, AUTH_MODE_OFFLINE, AUTH_MODE_YGGDRASIL
    };

    /** Same numbering as {@link UclSettingsPage}: about is the last row. */
    private static final int SETTINGS_VIDEO = UclSettingsPage.CAT_VIDEO;

    /** The page on screen: always the top of {@link #mHistory}. */
    private int mPage = PAGE_HOME;
    /**
     * Where to come back to after a rebuild this class asked for.
     *
     * <p>Switching the language recreates the activity, and a recreate that
     * dropped the user on the home screen would be a strange way to answer
     * "what language?". The pick is kept across the rebuild in a static, which
     * is exactly as long as it is needed: one activity's lifetime.
     */
    private static int sRestorePage = -1;
    private static int sRestoreCategory = -1;
    /**
     * How long the plate stays up while the language is applied.
     *
     * <p>The rebuild is fast: the window comes down and comes back in about a
     * frame, so without the plate the change reads as a stutter and the user
     * cannot tell whether anything happened. Half a second is the shortest
     * time an answer can be read as an answer.
     */
    private static final long LANGUAGE_SWITCH_HOLD_MS = 600L;
    /** The pending language rebuild, if the plate is up. */
    private Runnable mLanguageSwitch;
    private static final long LAUNCH_CHECK_DELAY_MS = 4000L;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mLaunchCheck = this::reportUnfinishedLaunch;
    private float mScale;

    /* single activity shell: panorama backdrop + a host for whole screens */
    private View mShell;
    /**
     * Every page is an object mounted here, and it stays mounted after it is
     * left: the pages are stacked in this one FrameLayout and navigation
     * translates them. Nothing is inflated twice and nothing is thrown away, so
     * "which tab" and "how far down" are not state this class has to remember --
     * they never left.
     */
    private FrameLayout mScreenHost;
    private final android.util.SparseArray<UclScreen> mScreens = new android.util.SparseArray<>();
    /** Page ids from the bottom of the stack (home) to the one on screen. */
    private final List<Integer> mHistory = new ArrayList<>();
    private PanoramaSurfaceView mPanorama;
    private PlayerSkinSurfaceView mSkinView;
    private TextView mPlayerName;
    private String mLoadedSkinKey;

    /* The web page is resident. Its body is ucl_web_host, a full bleed
     * container over the screens (the site brings its own app bar, so it must
     * not be pushed down by ours), and the WebView inside it is built on first
     * use and then kept for the life of the activity: coming back to the page
     * is a translation, never a load. mWebLoaded says the document has painted
     * at least once; the plate covers only that first wait. */
    private FrameLayout mWebHost;
    private WebView mWeb;
    private UclDialog mLoadingPlate;
    private boolean mWebLoaded;
    private boolean mWebPending;
    private Runnable mWebTimeout;
    private final android.os.Handler mUi = new android.os.Handler(android.os.Looper.getMainLooper());

    /* account page state: the left column never changes, the right column swaps
     * between the stored account list and the login panel of the picked method */
    private int mAuthMode = AUTH_MODE_OFFLINE;
    private boolean mRightShowsForms;
    private View[] mModeEntries;
    private LinearLayout mRightList, mRightForms;
    private FrameLayout mRightColumn, mAuthForms;
    private View mFormHeader, mFormDivider;
    private View mOfflineForm, mYggForm, mBlabbyForm;
    private UclMicrosoftLoginPanel mMicrosoftPanel;
    private TextView mFormTitle;
    private TextView mCurrentName, mCurrentType, mCurrentEmail;
    private ImageView mCurrentAvatar, mCurrentOnline;
    /* usernames whose skin is currently being downloaded for an avatar */
    private final java.util.Set<String> mAvatarFetches = new java.util.HashSet<>();
    private View mLogoutRow, mAddAccountButton;
    private ListView mAccountList;
    private AccountListAdapter mAccountAdapter;
    private final List<MinecraftAccount> mAccounts = new ArrayList<>();
    /* how many accounts share each username, and each account's ordinal among
     * them, so duplicate rows can be numbered */
    private final java.util.Map<String, Integer> mAccountNameCounts = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> mAccountOrdinals = new java.util.HashMap<>();
    /* original right column insets, restored when the Microsoft page is left */
    private int mRightPadStart, mRightPadTop, mRightPadEnd, mRightPadBottom;
    /* the auth form host sits in a LinearLayout, so only MarginLayoutParams is
     * safe to hold here -- casting to FrameLayout.LayoutParams crashes the page */
    private ViewGroup.MarginLayoutParams mAuthFormsParams;
    private int mAuthFormsTopMargin;

    /** Result of the embedded Microsoft OAuth page shown in the right column. */
    private final UclMicrosoftLoginPanel.Callback mMicrosoftCallback =
            new UclMicrosoftLoginPanel.Callback() {
                @Override
                public void onLoginDone(MinecraftAccount account) {
                    PojavProfile.setCurrentProfile(UclPageActivity.this, account.accountFile);
                    refreshAccountInfo();
                    showAccountsList();
                    /* the panel already dropped its Microsoft session, so the
                     * next tap on the Microsoft entry adds another account
                     * instead of overwriting this one */
                    Toast.makeText(UclPageActivity.this, R.string.ucl_login_success,
                            Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onLoginError(String message) {
                    Toast.makeText(UclPageActivity.this,
                            getString(R.string.ucl_login_failed, message), Toast.LENGTH_LONG).show();
                }

                @Override
                public void onLoginCancelled() {
                    Toast.makeText(UclPageActivity.this, R.string.ucl_login_cancelled,
                            Toast.LENGTH_SHORT).show();
                    showAccountsList();
                }
            };

    /* version page state */
    private LinearLayout mVersionList;
    private TextView mVersionEmpty;
    private TextView mStorageText;
    private LinearLayout mVersionDetailBody;
    /** the play key doubles as the version readout: 启 动（1.12.2） */
    private TextView mPlayButton;
    private String mSelectedVersion;

    /* download page state */
    private EditText mMirrorInput;
    private TextView mMirrorStatus;
    private TextView mMirrorHost;
    private View mMirrorDot;
    private TextView mTaskCount;
    private TextView mTaskEmpty;
    private ListView mTaskList;
    private TaskAdapter mTaskAdapter;
    private View mTaskBar;
    private TextView mTaskBarText;
    private View mTaskBarDot;
    private TextView mTaskBarToggle;

    /* catalogue order in the left column: the editions first, then the content
     * that hangs off them. Java is the only one with a working path, the rest
     * are slots that already have their row and their panel. */
    private static final int CAT_JAVA = 0;
    private static final int CAT_RUNTIME = 1;
    private static final int CAT_BEDROCK = 2;
    private static final int CAT_MOD = 3;
    private static final int CAT_PACK = 4;
    private static final int CAT_SHADER = 5;

    private static final int[] CATEGORY_LABEL = {
            R.string.ucl_dl_cat_java, R.string.ucl_dl_cat_runtime,
            R.string.ucl_dl_cat_bedrock, R.string.ucl_dl_cat_mod,
            R.string.ucl_dl_cat_pack, R.string.ucl_dl_cat_shader};
    /** True for the categories whose download path is actually connected. */
    private static final boolean[] CATEGORY_WIRED = {
            true, true, false, false, false, false};

    /* One icon per entry, same set of pack icons the account rows use. Adding a
     * category is still one entry per array and nothing else. */
    private static final int[] CATEGORY_ICON = {
            R.drawable.ucl_cat_java, R.drawable.ucl_cat_java,
            R.drawable.ucl_cat_bedrock, R.drawable.ucl_cat_mod,
            R.drawable.ucl_cat_pack, R.drawable.ucl_cat_shader};

    private int mDownloadCategory = CAT_JAVA;
    private LinearLayout mCategoryList;
    /** which runtimes were installed the last time their list was built */
    private String mJavaSignature;

    /* drawer */
    private View mDrawerScrim;
    private FrameLayout mDrawerPanel;
    private boolean mDrawerOpen;
    private TextView mDrawerCount;

    /* settings page state */
    private int mSettingsCategory = SETTINGS_VIDEO;
    private LinearLayout mSettingsCatList;
    private ScrollView mSettingsScroll;
    private TextView mSettingsTitle;
    private LinearLayout mSettingsBody;
    private UclSettingsPage mSettingsPage;

    /* pad mapper */
    private RecyclerView mGamepadList;
    private GamepadMapperAdapter mGamepadAdapter;
    private RemapperManager mGamepadManager;
    private Gamepad mGamepadPad;
    private boolean mGamepadGrabState = false;
    private final Handler mGamepadExitHandler = new Handler(Looper.getMainLooper());
    private final Runnable mGamepadExit = () -> {
        if (mPage == PAGE_GAMEPAD) onBackPressed();
    };

    @Override
    protected boolean shouldIgnoreNotch() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Tools.initStorageConstants(getApplicationContext());
        setContentView(R.layout.activity_ucl_page);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        mScale = dm.widthPixels / REFERENCE_WIDTH;

        mShell = findViewById(R.id.ucl_shell);
        mScreenHost = findViewById(R.id.ucl_screen_host);
        mWebHost = findViewById(R.id.ucl_web_host);
        mPanorama = findViewById(R.id.ucl_panorama);

        /* The corner tab and the dim behind the drawer are wired once: what
         * the drawer holds is decided per page, but how it opens and closes is
         * the same everywhere, so it lives here rather than in a page. */
        mDrawerScrim = findViewById(R.id.ucl_drawer_scrim);
        mDrawerPanel = findViewById(R.id.ucl_drawer_panel);
        if (mDrawerScrim != null) mDrawerScrim.setOnClickListener(v -> closeDrawer(true));
        /* the drawer is 37.45% of the screen, the share the reference gives it;
         * a FrameLayout child cannot ask for a percentage of its parent */
        mShell.post(() -> {
            if (mDrawerPanel == null || mShell.getWidth() == 0) return;
            ViewGroup.LayoutParams lp = mDrawerPanel.getLayoutParams();
            lp.width = Math.round(mShell.getWidth() * 0.3745f);
            mDrawerPanel.setLayoutParams(lp);
        });

        /* The home screen is the bottom of the stack. A page opened straight
         * from a shortcut is simply put on top of it, with no transition. */
        int page = getIntent().getIntExtra(EXTRA_PAGE, PAGE_HOME);
        if (sRestorePage >= 0) {
            page = sRestorePage;
            if (sRestoreCategory >= 0) mSettingsCategory = sRestoreCategory;
            sRestorePage = -1;
            sRestoreCategory = -1;
        }
        openPage(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPanorama != null) mPanorama.onResume();
        if (mSkinView != null) mSkinView.onResume();
        updateHomePlayer();
        if (mPage == PAGE_ACCOUNT && mCurrentName != null) refreshAccountInfo();
        /* A launch that dies inside the JVM brings this activity back within a
         * couple of seconds, and the failure has no message of its own. Checking
         * a moment later is what turns that silence into a report; the delay is
         * what keeps a launch that is still starting from being reported. */
        mHandler.removeCallbacks(mLaunchCheck);
        mHandler.postDelayed(mLaunchCheck, LAUNCH_CHECK_DELAY_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mLaunchCheck);
        if (mPanorama != null) mPanorama.onPause();
        if (mSkinView != null) mSkinView.onPause();
    }

    @Override
    public void onBackPressed() {
        /* the drawer is one step above the page that opened it: the key folds
         * it away before it touches the page stack */
        if (isDrawerOpen()) {
            closeDrawer(true);
            return;
        }
        /* a web page is one step of the same back stack: leave it before
         * leaving the page under it -- whether the key came from the system or
         * from the page's own back arrow (which logs WEB_BACK_SIGNAL). */
        if (mPage == PAGE_WEB) {
            closeWebPage();
            return;
        }
        /* one step back, whatever the step was: the pad mapper returns to the
         * controls page it was opened from, not all the way home */
        if (mHistory.size() > 1) {
            popPage();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (mLanguageSwitch != null) {
            mUi.removeCallbacks(mLanguageSwitch);
            mLanguageSwitch = null;
        }
        releasePageWidgets();
        discardWebPage();
        UclDownloadManager.getInstance().removeListener(this);
        super.onDestroy();
    }

    /* ===================================================================== */
    /* home screen + screen switching (single activity)                      */
    /* ===================================================================== */

    private void setupHome(View home) {
        mSkinView = home.findViewById(R.id.ucl_character);
        mPlayerName = home.findViewById(R.id.ucl_player_name);
        int[] ids = {
                R.id.ucl_btn_play, R.id.ucl_btn_version, R.id.ucl_btn_download,
                R.id.ucl_btn_settings, R.id.ucl_btn_social, R.id.ucl_btn_account
        };
        for (int id : ids) home.findViewById(id).setOnClickListener(this);
        applyHomeTextScale(home);
        updateHomePlayer();
    }

    private void applyHomeTextScale(View home) {
        int[] buttons = {
                R.id.ucl_btn_play, R.id.ucl_btn_version, R.id.ucl_btn_download,
                R.id.ucl_btn_settings, R.id.ucl_btn_social, R.id.ucl_btn_account
        };
        for (int id : buttons) scaleHomeText(home.findViewById(id), HOME_BUTTON_TEXT_SIZE);

        TextView subtitle = home.findViewById(R.id.ucl_subtitle);
        subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                HOME_SUBTITLE_TEXT_SIZE * mScale);
        subtitle.setTypeface(UclTypeface.title(this));
        /* the tracking is part of the look, but the display face already has
         * its own air between letters: the body's 0.38 on top of it would read
         * as a gap instead of a space */
        subtitle.setLetterSpacing(UclTypeface.isDisplay(subtitle.getTypeface())
                ? 0.30f : 0.38f);
        /* one launch in five -- the layout still leaves the room for it, and
         * GONE is the right verb there: the space closes up instead of leaving
         * a hole under the logo */
        if (sSubtitleThisRun == null) sSubtitleThisRun = Math.random() < SUBTITLE_CHANCE;
        subtitle.setVisibility(sSubtitleThisRun ? View.VISIBLE : View.GONE);

        UclSplashText splash = home.findViewById(R.id.ucl_splash);
        splash.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                HOME_SPLASH_TEXT_SIZE * mScale);
        splash.setText(UclSplashes.pick(this));

        mPlayerName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                HOME_NAME_TEXT_SIZE * mScale);

        mPlayButton = home.findViewById(R.id.ucl_btn_play);
        updatePlayLabel();
    }

    private void scaleHomeText(TextView view, float conceptTextSize) {
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, conceptTextSize * mScale);
        view.setBackground(ResourcesCompat.getDrawable(getResources(),
                R.drawable.ucl_button_background, null));
        /* the light plate sinks two pixels when held, so its label goes with
         * it; the green plate these keys ship with does not move at all */
        if (view instanceof com.kdt.mcgui.MineButton) {
            ((com.kdt.mcgui.MineButton) view).setPlateTravel(true);
        }

        /* These six buttons get their height from the layout (a percentage of
         * the screen split by weight), never from their text. Noto's line box
         * is 1.448 em tall -- far taller than the 1.17 em Roboto box the plate
         * sizes were drawn against -- so padding borrowed from the old
         * MineButton plate plus the font's own leading pushed the line past the
         * button floor. The text was then dropped to top alignment and clipped
         * by the bottom edge, which reads as "the label sank".
         *
         * Dropping the font padding and clearing the side padding gives the
         * line back the whole plate, and centre gravity puts it there. Both
         * calls must follow setBackground(), which re-applies the drawable's
         * own padding every time it runs.
         *
         * The plate cannot be moved with translationY -- the view *is* the
         * button, so offsetting it would drag the artwork along. A little extra
         * bottom padding does the job instead: centre gravity splits the
         * difference, so twice the wanted lift in bottom padding raises the
         * line by the wanted amount and leaves the plate untouched. */
        float textPx = conceptTextSize * mScale;
        int inkLift = Math.round(2f * textPx * CJK_INK_OFFSET_EM);
        view.setIncludeFontPadding(false);
        view.setPaddingRelative(0, 0, 0, inkLift);
        view.setGravity(android.view.Gravity.CENTER);
    }

    private void updateHomePlayer() {
        if (mPlayerName == null) return;
        MinecraftAccount account = PojavProfile.getCurrentProfileContent(this, null);
        if (account != null && account.username != null && !account.username.isEmpty()) {
            mPlayerName.setText(account.username);
        } else {
            mPlayerName.setText(R.string.ucl_not_logged_in);
        }
        loadHomeSkin(account);
    }

    /** Load the skin for the current account into the 3D preview */
    private void loadHomeSkin(MinecraftAccount account) {
        if (mSkinView == null) return;
        /* keyed on the account, not the name: two accounts may share a name but
         * only one of them owns the texture we just loaded */
        String key = account == null ? null : AvatarLoader.cacheKey(account);
        if (key != null && key.equals(mLoadedSkinKey)) return;
        mLoadedSkinKey = key;
        SkinLoader.load(this, account, skin -> {
            if (skin != null) mSkinView.getRenderer().setSkin(skin);
        });
    }

    /**
     * Reports a launch that never came back from the JVM.
     *
     * <p>That failure has no exit code to show, because the process is
     * terminated from native code before the launcher regains control. The only
     * account of it is the end of latestlog.txt, which is what the previous
     * attempt left behind.
     */
    private void reportUnfinishedLaunch() {
        String report = UclLaunchWatch.takeUnfinishedReport(this);
        if (report == null) return;
        ScrollView scroller = new ScrollView(this);
        TextView body = new TextView(this);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        body.setTextSize(11);
        body.setTextIsSelectable(true);
        body.setText(report);
        scroller.addView(body);
        UclDialog.build(this)
                .title(getString(R.string.ucl_launch_unfinished))
                .body(scroller, false)
                .rowLayout(true)
                .key(getString(R.string.main_share_logs), UclDialog.STYLE_LIGHT,
                        () -> Tools.shareLog(this))
                .key(getString(R.string.ucl_dlg_cancel), UclDialog.STYLE_LIGHT, null)
                .show();
    }

    private void playGame() {
        MinecraftAccount account = PojavProfile.getCurrentProfileContent(this, null);
        if (account == null || account.username == null || account.username.isEmpty()) {
            Toast.makeText(this, R.string.ucl_need_account, Toast.LENGTH_SHORT).show();
            return;
        }
        /* the play key starts the version the version page picked, not whatever
         * the launcher profile happens to hold: that is the point of the picker */
        String versionId = UclVersionSelection.resolve(this);
        if (versionId == null) {
            Toast.makeText(this, R.string.ucl_version_none, Toast.LENGTH_SHORT).show();
            openPageAnimated(PAGE_VERSION);
            return;
        }
        if (!Tools.isVersionInstalled(versionId)) {
            downloadAndShowProgress(versionId);
            return;
        }
        syncProfileVersion(versionId);
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.INTENT_MINECRAFT_VERSION, versionId));
    }

    /* ===================================================================== */
    /* screens: one page, one object, one slide                              */
    /* ===================================================================== */

    /**
     * A page, as an object.
     *
     * <p>Its root is the whole screen - its own bar plus its own body - so
     * moving a page means moving one view, header included. That is what the
     * old shell could not do: there was one shared bar, so only the body could
     * travel, and the bar stayed nailed to the screen while the content under
     * it slid away.
     *
     * <p>A page that is left is kept, not destroyed: it is translated off
     * screen and left alone. That is the whole state policy. There is no saved
     * state, no "which tab was it" bookkeeping and no restore pass, because the
     * view never stopped existing - its scroll offset, its selected category
     * and its list are still exactly where they were. Pages that own something
     * live (the pad listener, the WebView) are told when they leave instead.
     */
    private static final class UclScreen {
        final int page;
        final View root;
        /** null for the home screen (it has no bar) and for the web page */
        final FrameLayout body;
        boolean built;

        UclScreen(int page, View root, FrameLayout body) {
            this.page = page;
            this.root = root;
            this.body = body;
        }
    }

    /** The page object for an id, created and wired the first time it is used. */
    private UclScreen screenFor(int page) {
        UclScreen screen = mScreens.get(page);
        if (screen == null) {
            screen = createScreen(page);
            mScreens.put(page, screen);
            if (screen.root.getParent() == null) mScreenHost.addView(screen.root);
        }
        if (!screen.built) {
            screen.built = true;
            buildScreenBody(screen);
        }
        return screen;
    }

    /**
     * Builds a page's furniture: the bar for the ones that have one, the home
     * layer for the home screen, nothing for the resident web page.
     */
    private UclScreen createScreen(int page) {
        if (page == PAGE_WEB) {
            /* the site brings its own app bar, so this page has no bar and no
             * body of ours; it is built once and only ever parked */
            UclScreen web = new UclScreen(PAGE_WEB, mWebHost, null);
            web.built = true;
            return web;
        }

        if (page == PAGE_HOME) {
            FrameLayout holder = new FrameLayout(this);
            holder.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View home = LayoutInflater.from(this).inflate(R.layout.activity_ucl_home, holder, false);
            holder.addView(home);
            setupHome(home);
            holder.post(() -> UclDrawables.nearestNeighbourTree(holder));
            UclScreen screen = new UclScreen(PAGE_HOME, holder, null);
            screen.built = true;
            return screen;
        }

        View root = LayoutInflater.from(this).inflate(R.layout.ucl_screen, mScreenHost, false);
        FrameLayout body = root.findViewById(R.id.ucl_screen_body);
        /* geometry, typeface and colours of this bar, from the one definition */
        UclTopBar.install(this, root);
        UclTopBar.setTitle(root, titleForPage(page));
        UclTopBar.setTabVisible(root, page == PAGE_DOWNLOAD);

        root.findViewById(R.id.ucl_topbar_back).setOnClickListener(v -> onBarBack(page));
        View tab = root.findViewById(R.id.ucl_topbar_action);
        if (tab != null) {
            tab.setOnClickListener(v -> {
                if (mDrawerOpen) closeDrawer(true);
                else openDrawer();
            });
        }
        return new UclScreen(page, root, body);
    }

    /** Inflates a page body into its own screen and wires it up. */
    private void buildScreenBody(UclScreen screen) {
        final int page = screen.page;
        View content = LayoutInflater.from(this).inflate(contentLayoutForPage(page), screen.body, true);
        // The account center and the download tool are authored in sp/dp and
        // must not be rescaled; the older pages still use concept pixels and
        // rely on this pass.
        if (page != PAGE_ACCOUNT && page != PAGE_DOWNLOAD && page != PAGE_VERSION
                && page != PAGE_SETTINGS && page != PAGE_GAMEPAD) {
            applyTextScale(content);
        }

        switch (page) {
            case PAGE_ACCOUNT:
                setupAccountPage();
                break;
            case PAGE_VERSION:
                setupVersionPage();
                break;
            case PAGE_DOWNLOAD:
                setupDownloadPage();
                break;
            case PAGE_SETTINGS:
                setupSettingsPage();
                break;
            case PAGE_GAMEPAD:
                setupGamepadPage();
                break;
        }
        /* every plate, icon and frame on the page is drawn on its own pixel
         * grid: keep the hard edges instead of bilinear softening them */
        screen.root.post(() -> UclDrawables.nearestNeighbourTree(screen.root));
        /* pages are inflated one at a time, so the resume-time pass in
         * BaseActivity never sees this tree: adopt it here */
        UclTypeface.apply(screen.root);
    }

    /** The bar's back key belongs to the page under it, so it pops that page. */
    private void onBarBack(int page) {
        if (page == PAGE_WEB) {
            closeWebPage();
            return;
        }
        popPage();
    }

    /** A page that owns something live has to be told when it leaves. */
    private void onScreenLeft(int page) {
        if (page == PAGE_WEB) {
            parkWebPage();
        } else if (page == PAGE_GAMEPAD) {
            dropScreen(page);
        }
    }

    /** Puts a page on screen without a transition. For the launch shortcut. */
    private void openPage(int page) {
        if (mHistory.isEmpty()) mHistory.add(PAGE_HOME);
        if (page != PAGE_HOME && !mHistory.contains(page)) mHistory.add(page);
        mPage = page;
        for (int i = 0; i < mHistory.size(); i++) {
            UclScreen screen = screenFor(mHistory.get(i));
            screen.root.animate().cancel();
            screen.root.setTranslationX(0f);
            screen.root.setVisibility(i == mHistory.size() - 1 ? View.VISIBLE : View.GONE);
        }
        onScreenShown(page);
    }

    /**
     * Forward navigation: the screen being left goes out to the left and the
     * new one arrives from the right, in one movement, bar and all.
     */
    private void openPageAnimated(final int page) {
        if (page == mPage) return;
        int earlier = mHistory.indexOf(page);
        if (earlier >= 0) {
            /* never grow a second copy of a page that is already under this
             * one: walk back to it instead */
            backToIndex(earlier);
            return;
        }
        mHistory.add(page);
        goTo(page, true);
    }

    /** Back one step. At the bottom of the stack the launcher itself leaves. */
    private void popPage() {
        if (mHistory.size() <= 1) {
            finish();
            return;
        }
        backToIndex(mHistory.size() - 2);
    }

    /** Rolls the stack back to a page that is already in it. */
    private void backToIndex(int index) {
        if (index < 0 || index >= mHistory.size() - 1) return;
        int target = mHistory.get(index);
        /* anything between here and there is dropped without its own slide */
        while (mHistory.size() - 1 > index + 1) {
            onScreenLeft(mHistory.remove(mHistory.size() - 1));
        }
        mHistory.remove(mHistory.size() - 1);
        goTo(target, false);
    }

    /** Page -> home. */
    private void backToHome() {
        if (mHistory.isEmpty()) return;
        backToIndex(0);
    }

    /**
     * The one transition. {@code forward} says which way the world moves: the
     * page that is being left always leaves the way the new one came from.
     */
    private void goTo(final int target, final boolean forward) {
        UclScreen cached = mScreens.get(target);
        /* a page that is being built right now has just refreshed itself in
         * setup(); only a page coming back from the cache needs the pass */
        final boolean returning = cached != null && cached.built;
        final UclScreen in = screenFor(target);
        final int leaving = mPage;
        final UclScreen out = mScreens.get(leaving);
        mPage = target;
        /* a drawer belongs to the page that opened it: it must not travel on
         * to the next one, and it must not be left mid-slide either */
        closeDrawer(false);

        final int width = screenWidth();
        in.root.animate().cancel();
        in.root.setVisibility(View.VISIBLE);
        in.root.setTranslationX(forward ? width : -width);
        in.root.animate().translationX(0f)
                .setDuration(SLIDE_DURATION)
                .setInterpolator(new DecelerateInterpolator(2f))
                .start();
        if (returning) onScreenShown(target);

        if (out == null || out.root == in.root) return;
        out.root.animate().cancel();
        out.root.animate().translationX(forward ? -width : width)
                .setDuration(SLIDE_DURATION)
                .setInterpolator(new DecelerateInterpolator(2f))
                .withEndAction(() -> {
                    out.root.setVisibility(View.GONE);
                    out.root.setTranslationX(0f);
                    onScreenLeft(leaving);
                })
                .start();
    }

    /** Refreshes what a kept page cannot know changed while it was away. */
    private void onScreenShown(int page) {
        if (page == PAGE_ACCOUNT && mCurrentName != null) refreshAccountInfo();
        if (page == PAGE_VERSION && mVersionList != null) rebuildVersionList();
    }

    /**
     * Removes a page for good, together with whatever it was running.
     *
     * <p>Only two pages are ever dropped: the pad mapper, because its gamepad
     * polls the display every frame and would keep doing it off screen, and a
     * web page that never loaded. Everything else stays in the cache so that
     * coming back is free and exact.
     */
    private void dropScreen(int page) {
        if (page == PAGE_GAMEPAD) releaseGamepad();
        UclScreen screen = mScreens.get(page);
        if (screen == null || page == PAGE_WEB) return;
        screen.root.animate().cancel();
        screen.root.setVisibility(View.GONE);
        if (screen.root.getParent() != null) {
            ((ViewGroup) screen.root.getParent()).removeView(screen.root);
        }
        mScreens.remove(page);
    }

    private int screenWidth() {
        int width = mShell != null ? mShell.getWidth() : 0;
        return width > 0 ? width : getResources().getDisplayMetrics().widthPixels;
    }

    /** Drops everything a page allocated so re-entering it starts clean. */
    private void releasePageWidgets() {
        if (mMicrosoftPanel != null) {
            mMicrosoftPanel.release();
            mMicrosoftPanel = null;
        }
        mModeEntries = null;
        mCurrentName = null;
        mCurrentAvatar = null;
        mAccountAdapter = null;
        mAccountList = null;
        /* the drawer's contents belong to the page that put them there */
        mDrawerCount = null;
        mCategoryList = null;
        mTaskAdapter = null;
        mTaskList = null;
        mTaskEmpty = null;
        mTaskBar = null;
        if (mDrawerPanel != null) mDrawerPanel.removeAllViews();
        /* the pad listener is bound to a view that is about to be torn down,
         * and the gamepad polls the display every frame until told to stop */
        releaseGamepad();
    }

    /** Sends the resident web page back to its parked state, content intact. */
    private void parkWebPage() {
        if (mWebHost == null) return;
        mWebHost.animate().cancel();
        mWebHost.setTranslationX(0f);
        if (mPage != PAGE_WEB) mWebHost.setVisibility(View.GONE);
    }

    private int titleForPage(int page) {
        switch (page) {
            case PAGE_VERSION:
                return R.string.ucl_page_version;
            case PAGE_DOWNLOAD:
                return R.string.ucl_page_download;
            case PAGE_SETTINGS:
                return R.string.ucl_page_settings;
            case PAGE_GAMEPAD:
                return R.string.ucl_page_gamepad;
            case PAGE_WEB:
                return R.string.ucl_page_web;
            default:
                return R.string.ucl_page_account;
        }
    }

    /** Bodies inflated into a screen. The web page has a container of its own
     *  (ucl_web_host): it is never rebuilt, so it has no layout to inflate. */
    private int contentLayoutForPage(int page) {
        switch (page) {
            case PAGE_VERSION:
                return R.layout.ucl_page_version;
            case PAGE_DOWNLOAD:
                return R.layout.ucl_page_download;
            case PAGE_SETTINGS:
                return R.layout.ucl_page_settings;
            case PAGE_GAMEPAD:
                return R.layout.ucl_page_gamepad;
            default:
                return R.layout.ucl_page_account;
        }
    }

    /* ===================================================================== */
    /* account center                                                        */
    /* ===================================================================== */

    private void setupAccountPage() {
        mModeEntries = new View[]{
                findViewById(R.id.ucl_mode_blabby),
                findViewById(R.id.ucl_mode_microsoft),
                findViewById(R.id.ucl_mode_offline),
                findViewById(R.id.ucl_mode_yggdrasil)
        };
        findViewById(R.id.ucl_mode_blabby).setOnClickListener(v -> openLoginForm(AUTH_MODE_BLABBY));
        findViewById(R.id.ucl_mode_microsoft).setOnClickListener(v -> openLoginForm(AUTH_MODE_MICROSOFT));
        findViewById(R.id.ucl_mode_offline).setOnClickListener(v -> openLoginForm(AUTH_MODE_OFFLINE));
        findViewById(R.id.ucl_mode_yggdrasil).setOnClickListener(v -> openLoginForm(AUTH_MODE_YGGDRASIL));

        mRightList = findViewById(R.id.ucl_right_list);
        mRightForms = findViewById(R.id.ucl_right_forms);
        mRightColumn = findViewById(R.id.ucl_right_column);
        mAuthForms = findViewById(R.id.ucl_auth_forms);
        mFormHeader = findViewById(R.id.ucl_form_header);
        mFormDivider = findViewById(R.id.ucl_form_divider);
        mFormTitle = findViewById(R.id.ucl_form_title);
        mRightPadStart = mRightColumn.getPaddingStart();
        mRightPadTop = mRightColumn.getPaddingTop();
        mRightPadEnd = mRightColumn.getPaddingEnd();
        mRightPadBottom = mRightColumn.getPaddingBottom();
        mAuthFormsParams = (ViewGroup.MarginLayoutParams) mAuthForms.getLayoutParams();
        mAuthFormsTopMargin = mAuthFormsParams.topMargin;

        mCurrentName = findViewById(R.id.ucl_current_name);
        mCurrentType = findViewById(R.id.ucl_current_type);
        mCurrentEmail = findViewById(R.id.ucl_current_email);
        mCurrentAvatar = findViewById(R.id.ucl_current_avatar);
        mCurrentOnline = findViewById(R.id.ucl_current_online);
        mLogoutRow = findViewById(R.id.ucl_btn_logout);
        mAddAccountButton = findViewById(R.id.ucl_btn_add_account);

        // tapping the current account summary reveals every stored account
        findViewById(R.id.ucl_current_card).setOnClickListener(v -> showAccountsList());
        mLogoutRow.setOnClickListener(v -> {
            PojavProfile.setCurrentProfile(UclPageActivity.this, null);
            refreshAccountInfo();
            Toast.makeText(this, R.string.ucl_logged_out, Toast.LENGTH_SHORT).show();
        });
        mAddAccountButton.setOnClickListener(v -> openLoginForm(mAuthMode));
        /* the back arrow is gone from the form header: tapping its title returns
         * to the account list, same as tapping the current account card */
        mFormTitle.setOnClickListener(v -> showAccountsList());

        mAccountList = findViewById(R.id.ucl_account_list);
        mAccountAdapter = new AccountListAdapter();
        mAccountList.setAdapter(mAccountAdapter);
        mAccountList.setEmptyView(findViewById(R.id.ucl_account_empty));
        mAccountList.setOnItemClickListener((parent, view, position, id) ->
                switchToAccount(mAccounts.get(position)));

        mOfflineForm = LayoutInflater.from(this).inflate(R.layout.ucl_auth_offline, mAuthForms, false);
        mYggForm = LayoutInflater.from(this).inflate(R.layout.ucl_auth_yggdrasil, mAuthForms, false);
        mBlabbyForm = LayoutInflater.from(this).inflate(R.layout.ucl_auth_blabby, mAuthForms, false);
        mMicrosoftPanel = new UclMicrosoftLoginPanel(this, mMicrosoftCallback);
        mAuthForms.addView(mOfflineForm);
        mAuthForms.addView(mYggForm);
        mAuthForms.addView(mBlabbyForm);
        /* the Microsoft method is the OAuth page itself, hosted in the column */
        mAuthForms.addView(mMicrosoftPanel.getView());

        mOfflineForm.findViewById(R.id.ucl_offline_login).setOnClickListener(this);
        mYggForm.findViewById(R.id.ucl_ygg_login).setOnClickListener(this);
        mBlabbyForm.findViewById(R.id.ucl_ygg_login).setOnClickListener(this);
        mBlabbyForm.findViewById(R.id.ucl_blabby_register).setOnClickListener(this);
        bindPasswordToggle(mYggForm);
        bindPasswordToggle(mBlabbyForm);

        /* restore remembered credentials -- Blabby has no server field at all */
        android.content.SharedPreferences authPrefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE);
        fillForm(mBlabbyForm, null,
                authPrefs.getString("blabby_user", ""),
                authPrefs.getString("blabby_pass", ""),
                authPrefs.getBoolean("blabby_remember", true));
        fillForm(mYggForm, authPrefs.getString("ygg_url", ""),
                authPrefs.getString("ygg_user", ""),
                authPrefs.getString("ygg_pass", ""),
                authPrefs.getBoolean("ygg_remember", true));

        refreshAuthMode();
        refreshAccountInfo();
        showAccountsList();
    }

    private void fillForm(View form, String url, String user, String pass, boolean remember) {
        EditText urlInput = form.findViewById(R.id.ucl_ygg_url);
        if (urlInput != null && url != null) urlInput.setText(url);
        ((EditText) form.findViewById(R.id.ucl_ygg_user)).setText(user);
        ((EditText) form.findViewById(R.id.ucl_ygg_pass)).setText(pass);
        ((CheckBox) form.findViewById(R.id.ucl_ygg_remember)).setChecked(remember);
    }

    private void bindPasswordToggle(final View form) {
        final EditText pass = form.findViewById(R.id.ucl_ygg_pass);
        final android.widget.ImageButton toggle = form.findViewById(R.id.ucl_ygg_pass_toggle);
        if (pass == null || toggle == null) return;
        final boolean[] shown = {false};
        toggle.setOnClickListener(v -> {
            shown[0] = !shown[0];
            int cursor = pass.getSelectionStart();
            android.graphics.Typeface typeface = pass.getTypeface();
            pass.setInputType(shown[0]
                    ? android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            // setInputType() swaps the typeface for the monospace password one
            pass.setTypeface(typeface);
            pass.setSelection(Math.max(0, Math.min(cursor, pass.getText().length())));
            toggle.setImageResource(shown[0] ? R.drawable.ucl_ic_eye : R.drawable.ucl_ic_eye_off);
        });
    }

    /** Switches the right column to the login panel of the given method. */
    private void openLoginForm(int mode) {
        mAuthMode = mode;
        mRightShowsForms = true;
        refreshAuthMode();
    }

    private void showAccountsList() {
        mRightShowsForms = false;
        refreshAuthMode();
    }

    private void refreshAuthMode() {
        for (int i = 0; i < mModeEntries.length && i < ENTRY_MODES.length; i++) {
            mModeEntries[i].setSelected(ENTRY_MODES[i] == mAuthMode);
        }
        mRightList.setVisibility(mRightShowsForms ? View.GONE : View.VISIBLE);
        mRightForms.setVisibility(mRightShowsForms ? View.VISIBLE : View.GONE);
        if (!mRightShowsForms) {
            applyMicrosoftFullBleed(false);
            return;
        }
        mFormTitle.setText(modeTitle(mAuthMode));
        mOfflineForm.setVisibility(mAuthMode == AUTH_MODE_OFFLINE ? View.VISIBLE : View.GONE);
        mYggForm.setVisibility(mAuthMode == AUTH_MODE_YGGDRASIL ? View.VISIBLE : View.GONE);
        mBlabbyForm.setVisibility(mAuthMode == AUTH_MODE_BLABBY ? View.VISIBLE : View.GONE);
        boolean microsoft = mAuthMode == AUTH_MODE_MICROSOFT;
        mMicrosoftPanel.getView().setVisibility(microsoft ? View.VISIBLE : View.GONE);
        applyMicrosoftFullBleed(microsoft);
        if (microsoft) mMicrosoftPanel.begin();
    }

    /* The Microsoft method is the OAuth page itself: it takes the whole right
     * column, with no page title, no back row and no panel padding around it. */
    private void applyMicrosoftFullBleed(boolean fullBleed) {
        mFormHeader.setVisibility(fullBleed ? View.GONE : View.VISIBLE);
        mFormDivider.setVisibility(fullBleed ? View.GONE : View.VISIBLE);
        if (fullBleed) {
            mRightColumn.setPadding(0, 0, 0, 0);
            mAuthFormsParams.topMargin = 0;
        } else {
            mRightColumn.setPadding(mRightPadStart, mRightPadTop, mRightPadEnd, mRightPadBottom);
            mAuthFormsParams.topMargin = mAuthFormsTopMargin;
        }
        /* mutate the live params in place -- the host is a LinearLayout child,
         * so re-installing a generic MarginLayoutParams would fail its cast */
        mAuthForms.requestLayout();
    }

    private int modeTitle(int mode) {
        switch (mode) {
            case AUTH_MODE_MICROSOFT:
                return R.string.ucl_mode_microsoft;
            case AUTH_MODE_YGGDRASIL:
                return R.string.ucl_mode_yggdrasil;
            case AUTH_MODE_BLABBY:
                return R.string.ucl_mode_blabby;
            default:
                return R.string.ucl_mode_offline;
        }
    }

    private void refreshAccountInfo() {
        refreshAccountList();
        updateCurrentCard(PojavProfile.getCurrentProfileContent(this, null));
    }

    private void updateCurrentCard(MinecraftAccount account) {
        if (account == null || TextUtils.isEmpty(account.username)) {
            mCurrentName.setText(R.string.ucl_not_logged_in);
            mCurrentType.setText(R.string.ucl_account_pick_hint);
            mCurrentEmail.setVisibility(View.GONE);
            mCurrentOnline.setVisibility(View.GONE);
            applyAvatar(mCurrentAvatar, null);
            return;
        }
        mCurrentName.setText(account.username);
        mCurrentType.setText(typeLabel(account));
        String detail = TextUtils.isEmpty(account.profileId) ? "" : account.profileId;
        mCurrentEmail.setText(detail);
        mCurrentEmail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        mCurrentOnline.setVisibility(account.isMicrosoft ? View.VISIBLE : View.GONE);
        applyAvatar(mCurrentAvatar, account);
    }

    private int typeLabel(MinecraftAccount account) {
        if (account.isMicrosoft) return R.string.ucl_account_type_microsoft;
        if (isBlabbyAccount(account)) return R.string.ucl_account_type_blabby;
        if (account.isLocal()) return R.string.ucl_account_type_offline;
        return R.string.ucl_account_type_external;
    }

    /* Blabby accounts are stored like offline ones, so the marker is either the
     * recorded Blabby username or -- for accounts saved before that field
     * existed -- the Blabby avatar sitting in the face cache (offline accounts
     * never get one). */
    private boolean isBlabbyAccount(MinecraftAccount account) {
        if (account == null) return false;
        if (!TextUtils.isEmpty(account.blabbyUser)) return true;
        if (!account.isLocal() || account.isMicrosoft) return false;
        return new File(Tools.DIR_CACHE, account.username + ".png").exists();
    }

    /* Scan Tools.DIR_ACCOUNT_NEW for stored accounts and rebuild the list.
     * Each file is one account and the file name is its identity, so several
     * accounts sharing a username are all listed -- the username is only what
     * gets displayed. */
    private void refreshAccountList() {
        String currentId = PojavProfile.getCurrentProfileName(this);
        mAccounts.clear();
        File dir = new File(Tools.DIR_ACCOUNT_NEW);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                String key = file.getName().substring(0, file.getName().length() - 5);
                MinecraftAccount account = MinecraftAccount.load(key);
                if (account != null && !TextUtils.isEmpty(account.username)) {
                    mAccounts.add(account);
                }
            }
        }
        java.util.Collections.sort(mAccounts, (a, b) -> {
            int byName = a.username.compareToIgnoreCase(b.username);
            return byName != 0 ? byName : String.valueOf(a.accountFile)
                    .compareToIgnoreCase(String.valueOf(b.accountFile));
        });
        mAccountAdapter.setCurrentId(currentId);
        mAccountAdapter.notifyDataSetChanged();
        mAccountList.setEmptyView(findViewById(R.id.ucl_account_empty));

        /* Two rows can read exactly the same when accounts share a username,
         * so number the duplicates in the type line to keep them tellable
         * apart. #1 is the first by storage key, which never changes. */
        mAccountNameCounts.clear();
        mAccountOrdinals.clear();
        for (MinecraftAccount account : mAccounts) {
            String name = normalizeName(account.username);
            Integer count = mAccountNameCounts.get(name);
            mAccountNameCounts.put(name, count == null ? 1 : count + 1);
        }
        java.util.Map<String, Integer> counter = new java.util.HashMap<>();
        for (MinecraftAccount account : mAccounts) {
            String name = normalizeName(account.username);
            Integer seen = counter.get(name);
            int ordinal = seen == null ? 1 : seen + 1;
            counter.put(name, ordinal);
            mAccountOrdinals.put(String.valueOf(account.accountFile), ordinal);
        }
    }

    private static String normalizeName(String username) {
        return username == null ? "" : username.toLowerCase(java.util.Locale.US);
    }

    private void switchToAccount(MinecraftAccount account) {
        if (account == null || TextUtils.isEmpty(account.accountFile)) return;
        PojavProfile.setCurrentProfile(this, account.accountFile);
        refreshAccountInfo();
        updateHomePlayer();
        Toast.makeText(this, getString(R.string.ucl_account_switched, account.username),
                Toast.LENGTH_SHORT).show();
    }

    private void deleteAccount(final MinecraftAccount account) {
        UclAlert.confirm(this,
                getString(R.string.ucl_account_delete_title),
                getString(R.string.ucl_account_delete_confirm, account.username),
                getString(R.string.ucl_dlg_delete),
                UclDialog.STYLE_RED,
                () -> {
                    String currentId = PojavProfile.getCurrentProfileName(this);
                    /* compare storage keys, not usernames: another Mike may
                     * well be the one currently logged in */
                    boolean wasCurrent = account.accountFile != null
                            && account.accountFile.equals(currentId);
                    account.deleteStoredFile();
                    AvatarLoader.forget(account);
                    if (wasCurrent) PojavProfile.setCurrentProfile(UclPageActivity.this, null);
                    refreshAccountInfo();
                    Toast.makeText(this, getString(R.string.ucl_account_deleted, account.username),
                            Toast.LENGTH_SHORT).show();
                });
    }

    private void loginOffline() {
        EditText nameInput = mOfflineForm.findViewById(R.id.ucl_offline_name);
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) name = "Steve";
        MinecraftAccount account = new MinecraftAccount();
        account.accessToken = "0";
        account.clientToken = UUID.randomUUID().toString();
        account.profileId = "00000000-0000-0000-0000-000000000000";
        account.username = name;
        account.isMicrosoft = false;
        String key;
        try {
            key = account.save();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.ucl_login_failed, String.valueOf(e.getMessage())),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        PojavProfile.setCurrentProfile(this, key);
        refreshAccountInfo();
        showAccountsList();
        Toast.makeText(this, R.string.ucl_login_success, Toast.LENGTH_SHORT).show();
    }

    private void loginYggdrasil(View form) {
        EditText urlInput = form.findViewById(R.id.ucl_ygg_url);
        EditText userInput = form.findViewById(R.id.ucl_ygg_user);
        EditText passInput = form.findViewById(R.id.ucl_ygg_pass);
        CheckBox remember = form.findViewById(R.id.ucl_ygg_remember);
        View loginButton = form.findViewById(R.id.ucl_ygg_login);
        final String url = urlInput.getText().toString().trim();
        final String user = userInput.getText().toString().trim();
        final String pass = passInput.getText().toString();
        if (user.isEmpty() || pass.isEmpty() || url.isEmpty()) {
            Toast.makeText(this, R.string.ucl_yggdrasil_incomplete, Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.SharedPreferences.Editor editor =
                getSharedPreferences(AUTH_PREFS, MODE_PRIVATE).edit();
        editor.putBoolean("ygg_remember", remember.isChecked());
        editor.putString("ygg_url", url);
        if (remember.isChecked()) {
            editor.putString("ygg_user", user);
            editor.putString("ygg_pass", pass);
        } else {
            editor.remove("ygg_user");
            editor.remove("ygg_pass");
        }
        editor.apply();
        loginButton.setEnabled(false);
        new YggdrasilClient(url, user, pass).login(new YggdrasilClient.LoginCallback() {
            @Override
            public void onSuccess(MinecraftAccount account) {
                loginButton.setEnabled(true);
                PojavProfile.setCurrentProfile(UclPageActivity.this, account.accountFile);
                refreshAccountInfo();
                showAccountsList();
                Toast.makeText(UclPageActivity.this, R.string.ucl_login_success, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                loginButton.setEnabled(true);
                Toast.makeText(UclPageActivity.this,
                        getString(R.string.ucl_login_failed, message), Toast.LENGTH_LONG).show();
            }
        });
    }

    /* Blabby accounts: the website session is the credential. Minecraft itself
     * has no Blabby auth server, so the launcher stores the account like an
     * offline profile (same username, same offline UUID) and keeps the Blabby
     * avatar in the face cache so the account list can show it. */
    private void loginBlabby(View form) {
        EditText userInput = form.findViewById(R.id.ucl_ygg_user);
        EditText passInput = form.findViewById(R.id.ucl_ygg_pass);
        CheckBox remember = form.findViewById(R.id.ucl_ygg_remember);
        View loginButton = form.findViewById(R.id.ucl_ygg_login);
        final String user = userInput.getText().toString().trim();
        final String pass = passInput.getText().toString();
        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.ucl_yggdrasil_incomplete, Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.SharedPreferences.Editor editor =
                getSharedPreferences(AUTH_PREFS, MODE_PRIVATE).edit();
        editor.putBoolean("blabby_remember", remember.isChecked());
        if (remember.isChecked()) {
            editor.putString("blabby_user", user);
            editor.putString("blabby_pass", pass);
        } else {
            editor.remove("blabby_user");
            editor.remove("blabby_pass");
        }
        editor.apply();
        loginButton.setEnabled(false);
        BlabbyClient.login(this, user, pass, new BlabbyClient.Callback() {
            @Override
            public void onSuccess(BlabbyClient.Profile profile) {
                final String name = blabbyName(profile);
                MinecraftAccount account = new MinecraftAccount();
                account.accessToken = "0";
                account.clientToken = UUID.randomUUID().toString();
                account.profileId = UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + name).getBytes()).toString();
                account.username = name;
                account.blabbyUser = profile.username;
                account.isMicrosoft = false;
                BlabbyClient.saveAvatar(name, profile.avatarUrl);
                final String key;
                try {
                    key = account.save();
                } catch (Exception e) {
                    final String detail = e.getMessage() == null
                            ? e.toString() : e.getMessage();
                    runOnUiThread(() -> {
                        loginButton.setEnabled(true);
                        Toast.makeText(UclPageActivity.this,
                                getString(R.string.ucl_login_failed, detail),
                                Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                runOnUiThread(() -> {
                    loginButton.setEnabled(true);
                    PojavProfile.setCurrentProfile(UclPageActivity.this, key);
                    refreshAccountInfo();
                    showAccountsList();
                    Toast.makeText(UclPageActivity.this, R.string.ucl_login_success,
                            Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(final String message) {
                runOnUiThread(() -> {
                    loginButton.setEnabled(true);
                    Toast.makeText(UclPageActivity.this,
                            getString(R.string.ucl_login_failed, message),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /* Minecraft only accepts 3-16 characters of [A-Za-z0-9_]. */
    private String blabbyName(BlabbyClient.Profile profile) {
        String name = profile.username == null ? "" : profile.username.replaceAll("[^A-Za-z0-9_]", "");
        if (name.isEmpty()) name = profile.nickname == null ? "" : profile.nickname.replaceAll("[^A-Za-z0-9_]", "");
        if (name.length() > 16) name = name.substring(0, 16);
        if (name.length() < 3) {
            name = "Blb" + Math.abs((profile.username == null ? "" : profile.username).hashCode() % 1000);
        }
        return name;
    }

    /**
     * Account avatar. Blabby accounts ship an avatar from the site, everything
     * else (offline, Microsoft, external authlib) starts with nothing, so the
     * skin is pulled from the account's own platform and the head is cropped
     * out of it. Fetches are asynchronous and only run once per username.
     */
    private void applyAvatar(ImageView view, MinecraftAccount account) {
        android.graphics.Bitmap face = account == null
                ? null : AvatarLoader.cached(account);
        if (face != null) {
            /* wrap it ourselves: a plain setImageBitmap() would bilinear
             * soften the 8x8 / 16x16 head when it is blown up */
            android.graphics.drawable.BitmapDrawable drawable =
                    new android.graphics.drawable.BitmapDrawable(getResources(), face);
            drawable.getPaint().setFilterBitmap(false);
            drawable.getPaint().setAntiAlias(false);
            view.setPadding(0, 0, 0, 0);
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setImageDrawable(drawable);
            return;
        }
        int pad = Math.round(4 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        view.setImageResource(R.drawable.ucl_ic_avatar);
        if (account == null || TextUtils.isEmpty(account.username)) return;
        if (account.isLocal() || TextUtils.isEmpty(account.profileId)) return;
        /* one fetch per account, keyed by profile so that two servers using the
         * same player name do not cancel each other out */
        final String key = AvatarLoader.cacheKey(account);
        if (TextUtils.isEmpty(key) || !mAvatarFetches.add(key)) return;
        AvatarLoader.refresh(account, (username, bitmap) -> {
            mAvatarFetches.remove(key);
            if (bitmap != null) refreshAccountInfo();
        });
    }

    /* ===================================================================== */
    /* version selection                                                     */
    /* ===================================================================== */

    private void setupVersionPage() {
        mVersionList = findViewById(R.id.ucl_version_list);
        mVersionEmpty = findViewById(R.id.ucl_version_empty);
        mStorageText = findViewById(R.id.ucl_storage_text);
        mVersionDetailBody = findViewById(R.id.ucl_version_detail_body);
        findViewById(R.id.ucl_btn_install_version).setOnClickListener(this);
        findViewById(R.id.ucl_btn_repair_version).setOnClickListener(this);
        findViewById(R.id.ucl_btn_open_mc_dir).setOnClickListener(this);
        findViewById(R.id.ucl_btn_delete_version).setOnClickListener(this);
        mSelectedVersion = UclVersionSelection.resolve(this);
        rebuildVersionList();
        refreshStorageText();
        refreshVersionDetail();
    }

    /**
     * Builds the version rows by hand.
     *
     * <p>A ListView would bring its own divider and row padding, and this page
     * is supposed to sit on the same grid as the account and download centers.
     * A row is the catalogue row: 48dp tall, a 24dp icon, the version at 14sp
     * and its release date underneath at 11sp.
     */
    private void rebuildVersionList() {
        if (mVersionList == null) return;
        mVersionList.removeAllViews();
        List<String> versions = UclVersionSelection.installed();
        if (mVersionEmpty != null) {
            mVersionEmpty.setVisibility(versions.isEmpty() ? View.VISIBLE : View.GONE);
        }
        for (String version : versions) {
            View row = LayoutInflater.from(this).inflate(R.layout.ucl_cat_row, mVersionList, false);
            ImageView icon = row.findViewById(R.id.ucl_cat_row_icon);
            TextView name = row.findViewById(R.id.ucl_cat_row_name);
            TextView state = row.findViewById(R.id.ucl_cat_row_state);
            icon.setImageResource(R.drawable.ucl_cat_java);
            name.setText(version);
            state.setText(releaseDateOf(version));
            /* the row plate only paints itself for the selected child, which is
             * also what turns the name green */
            row.setSelected(version.equals(mSelectedVersion));
            row.setOnClickListener(v -> selectVersion(version));
            mVersionList.addView(row);
        }
        UclTypeface.apply(mVersionList);
        UclDrawables.nearestNeighbourTree(mVersionList);
    }

    /** The release date of an installed version, or an empty line. */
    private String releaseDateOf(String versionId) {
        try {
            JMinecraftVersionList.Version info = Tools.getVersionInfo(versionId);
            if (info != null && info.releaseTime != null && info.releaseTime.length() >= 10) {
                return info.releaseTime.substring(0, 10);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * A tap on a row is the selection. There is no confirm key: the pick is
     * written here, the corner of the home screen follows, and the play key
     * starts it.
     */
    private void selectVersion(String versionId) {
        mSelectedVersion = versionId;
        UclVersionSelection.set(this, versionId);
        syncProfileVersion(versionId);
        rebuildVersionList();
        refreshVersionDetail();
        updatePlayLabel();
    }

    /** Keeps the launcher profile in step so the rest of the launch path agrees. */
    private void syncProfileVersion(String versionId) {
        try {
            MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
            if (profile != null && !versionId.equals(profile.lastVersionId)) {
                profile.lastVersionId = versionId;
                LauncherProfiles.write();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Writes the picked version onto the play key: 启 动（1.12.2）.
     *
     * <p>The corner of the screen used to answer this question while the key
     * did not, so the eye had to travel from the thing being pressed to the
     * thing being described. The key now names its own target.
     *
     * <p>Version ids are not the same length -- "1.12.2" is six characters,
     * "1.20.1-forge-47.2.0" is nineteen -- and the plate is a fixed 29% of the
     * canvas, so the label is fitted rather than clipped.
     */
    private void updatePlayLabel() {
        if (mPlayButton == null) return;
        String version = UclVersionSelection.resolve(this);
        if (version == null || version.isEmpty()) {
            mPlayButton.setText(R.string.ucl_btn_play);
        } else {
            mPlayButton.setText(getString(R.string.ucl_btn_play_version, version));
        }
        fitPlayLabel();
    }

    private void fitPlayLabel() {
        final TextView key = mPlayButton;
        if (key == null) return;
        final float base = HOME_BUTTON_TEXT_SIZE * mScale;
        key.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base);
        if (key.getWidth() > 0) {
            shrinkPlayLabel(key, base);
            return;
        }
        /* the first pass runs before the plate has a measured width; measuring
         * the label against zero would either skip the fit or shrink it to
         * nothing, so wait for the plate instead of guessing */
        key.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (key.getWidth() <= 0) return;
                key.removeOnLayoutChangeListener(this);
                shrinkPlayLabel(key, base);
            }
        });
    }

    /** Steps the label down until it fits the plate, down to 60% of the base. */
    private void shrinkPlayLabel(TextView key, float base) {
        CharSequence text = key.getText();
        if (text == null || text.length() == 0) return;
        /* a hair of air off each end, so the ink never touches the plate edge */
        float room = key.getWidth() - key.getPaddingLeft() - key.getPaddingRight()
                - 12f * mScale;
        if (room <= 0) return;
        /* measured on a copy of the paint: setting the size on the live view in
         * a loop would schedule one layout pass per step */
        TextPaint probe = new TextPaint(key.getPaint());
        float size = base;
        while (size > base * 0.6f) {
            probe.setTextSize(size);
            if (probe.measureText(text, 0, text.length()) <= room) break;
            size -= base * 0.04f;
        }
        key.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
    }

    private void refreshStorageText() {
        StatFs stat = new StatFs(Tools.DIR_HOME_VERSION);
        long freeBytes = stat.getAvailableBytes();
        long mib = freeBytes / (1024 * 1024);
        if (mib >= 1024) {
            mStorageText.setText(getString(R.string.ucl_storage_free_gb,
                    String.format(java.util.Locale.US, "%.1f", mib / 1024f)));
        } else {
            mStorageText.setText(getString(R.string.ucl_storage_free, mib));
        }
    }

    private void refreshVersionDetail() {
        mVersionDetailBody.removeAllViews();
        if (mSelectedVersion == null) {
            TextView empty = new TextView(this);
            empty.setText(R.string.ucl_version_pick_hint);
            empty.setTextColor(ContextCompat.getColor(this, R.color.ucl_text_muted));
            empty.setTextSize(13);
            UclTypeface.apply(empty);
            mVersionDetailBody.addView(empty);
            return;
        }
        JMinecraftVersionList.Version versionInfo = null;
        try {
            versionInfo = Tools.getVersionInfo(mSelectedVersion);
        } catch (Exception ignored) {
        }
        JMinecraftVersionList.Version listed = AsyncMinecraftDownloader.getListedVersion(mSelectedVersion);

        addDetailRow(getString(R.string.ucl_version_id), mSelectedVersion);
        if (listed != null) {
            addDetailRow(getString(R.string.ucl_version_release), listed.releaseTime);
            addDetailRow(getString(R.string.ucl_version_kind),
                    "snapshot".equals(listed.type) ? getString(R.string.ucl_version_snapshot)
                            : getString(R.string.ucl_version_release_kind));
        }
        if (versionInfo != null) {
            if (versionInfo.inheritsFrom != null && !versionInfo.inheritsFrom.equals(versionInfo.id)) {
                addDetailRow(getString(R.string.ucl_version_inherits), versionInfo.inheritsFrom);
            }
            int javaRequirement = versionInfo.javaVersion != null ? versionInfo.javaVersion.majorVersion : 8;
            addDetailRow(getString(R.string.ucl_version_java), "Java " + javaRequirement);
        }
        /* the json and the client jar are what a version consists of; saying
         * plainly whether both are present is what makes the repair key make
         * sense before it is pressed */
        addDetailRow(getString(R.string.ucl_version_files),
                Tools.isVersionComplete(mSelectedVersion)
                        ? getString(R.string.ucl_version_files_complete)
                        : getString(R.string.ucl_version_files_missing));
    }

    /**
     * One label-and-value line of the detail column.
     *
     * <p>The shared row layout carries a size meant for the settings page, which
     * then rescales it; on a page authored in sp that comes out unreadably
     * small, so the size is set here instead. Thirteen for the label and
     * fourteen bold for the value is the same pair the version rows use.
     */
    private void addDetailRow(String key, String value) {
        View row = LayoutInflater.from(this).inflate(R.layout.ucl_auth_info_row, mVersionDetailBody, false);
        TextView keyView = row.findViewById(R.id.ucl_info_key);
        TextView valueView = row.findViewById(R.id.ucl_info_value);
        keyView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        valueView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        keyView.setText(key);
        valueView.setText(value);
        mVersionDetailBody.addView(row);
    }

    private void installVersion(String versionId) {
        if (Tools.isVersionInstalled(versionId)) {
            showVersionPresent(versionId);
            return;
        }
        UclDownloadManager.getInstance().startDownload(this, versionId);
        Toast.makeText(this, getString(R.string.ucl_version_downloading, versionId), Toast.LENGTH_SHORT).show();
    }

    /**
     * Says why nothing was downloaded, and what to do if the copy on disk is
     * the reason the game will not start.
     *
     * <p>Downloading over an existing folder rewrites every file in it for no
     * gain, which is what the user asked to stop. The window therefore names
     * the two ways forward instead of leaving the user to guess.
     */
    private void showVersionPresent(String versionId) {
        UclLegalDialogs.notice(this, R.string.ucl_version_present_title,
                getString(R.string.ucl_version_present_body, versionId),
                R.string.ucl_dlg_ok, null);
    }

    /**
     * Downloads only what the selected version is missing.
     *
     * <p>The downloader checks every file against its hash before writing it,
     * so running the whole job over an existing folder leaves the files that
     * are already correct alone and fills in the rest. That is what makes
     * repair a normal download rather than a second kind of job.
     */
    private void repairSelectedVersion() {
        if (mSelectedVersion == null) {
            Toast.makeText(this, R.string.ucl_version_pick_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Tools.isVersionComplete(mSelectedVersion)) {
            Toast.makeText(this, getString(R.string.ucl_version_repair_complete, mSelectedVersion),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        UclDownloadManager manager = UclDownloadManager.getInstance();
        if (manager.hasUnfinished(mSelectedVersion)) {
            Toast.makeText(this, getString(R.string.ucl_task_duplicate_body, mSelectedVersion),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (manager.hasActiveTask()) {
            UclLegalDialogs.notice(this, R.string.ucl_dlg_busy_title,
                    getString(R.string.ucl_dlg_busy_body), R.string.ucl_dlg_ok, null);
            return;
        }
        manager.startDownload(this, mSelectedVersion);
        Toast.makeText(this, getString(R.string.ucl_version_repair_started, mSelectedVersion),
                Toast.LENGTH_SHORT).show();
    }

    private void deleteSelectedVersion() {
        if (mSelectedVersion == null) {
            Toast.makeText(this, R.string.ucl_version_pick_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        final String version = mSelectedVersion;
        UclAlert.confirm(this,
                getString(R.string.ucl_version_delete),
                getString(R.string.ucl_version_delete_confirm, version),
                getString(R.string.ucl_dlg_delete),
                UclDialog.STYLE_RED,
                () -> {
                    try {
                        FileUtils.deleteDirectory(new File(Tools.DIR_HOME_VERSION, version));
                        if (version.equals(UclVersionSelection.peek(UclPageActivity.this))) {
                            UclVersionSelection.set(UclPageActivity.this, null);
                        }
                        mSelectedVersion = UclVersionSelection.resolve(UclPageActivity.this);
                        rebuildVersionList();
                        refreshStorageText();
                        refreshVersionDetail();
                        updatePlayLabel();
                        Toast.makeText(UclPageActivity.this, R.string.ucl_version_deleted, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(UclPageActivity.this, R.string.ucl_version_delete_failed, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private class AccountListAdapter extends BaseAdapter {
        /* storage key of the account in use; usernames are not unique */
        private String mCurrentId;

        void setCurrentId(String id) {
            mCurrentId = id;
        }

        @Override
        public int getCount() {
            return mAccounts.size();
        }

        @Override
        public MinecraftAccount getItem(int position) {
            return mAccounts.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(UclPageActivity.this)
                        .inflate(R.layout.ucl_account_row, parent, false);
                UclTypeface.apply(convertView);
            }
            final MinecraftAccount account = getItem(position);
            TextView name = convertView.findViewById(R.id.ucl_account_row_name);
            TextView type = convertView.findViewById(R.id.ucl_account_row_type);
            TextView current = convertView.findViewById(R.id.ucl_account_row_current);
            View delete = convertView.findViewById(R.id.ucl_account_row_delete);
            applyAvatar((ImageView) convertView.findViewById(R.id.ucl_account_row_avatar), account);
            /* tapping anywhere on the row switches to that account */
            convertView.setOnClickListener(v -> switchToAccount(account));
            name.setText(account.username);
            String typeText = getString(typeLabel(account));
            Integer total = mAccountNameCounts.get(normalizeName(account.username));
            Integer ordinal = mAccountOrdinals.get(String.valueOf(account.accountFile));
            if (total != null && total > 1 && ordinal != null) {
                typeText = getString(R.string.ucl_account_row_suffix, typeText, ordinal);
            }
            type.setText(typeText);
            boolean isCurrent = account.accountFile != null
                    && account.accountFile.equals(mCurrentId);
            /* the row background is the measured MC plate: it only shows on the
             * active row, so the plate has to follow the selection state */
            convertView.setSelected(isCurrent);
            current.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            delete.setOnClickListener(v -> deleteAccount(account));
            UclDrawables.nearestNeighbourTree(convertView);
            return convertView;
        }
    }

    /* ===================================================================== */
    /* drawer                                                                */
    /* ===================================================================== */

    /**
     * True while the drawer is out, including while it is still sliding.
     *
     * <p>The flag is set when the slide starts rather than when it ends: a
     * second tap on the corner tab during the animation has to close it, not
     * queue another open.
     */
    private boolean isDrawerOpen() {
        return mDrawerOpen && mDrawerPanel != null;
    }

    /**
     * Puts one page's content into the drawer, replacing whatever was there.
     *
     * <p>The drawer itself — plate, width, scrim — belongs to the shell, so it
     * is inflated once with the activity; only the contents change with the
     * page, and a page without contents simply has nothing to open.
     */
    @Nullable
    private View installDrawer(int contentRes) {
        if (mDrawerPanel == null) return null;
        mDrawerPanel.removeAllViews();
        View content = LayoutInflater.from(this).inflate(contentRes, mDrawerPanel, true);
        UclTypeface.apply(content);
        UclDrawables.nearestNeighbourTree(content);
        return content;
    }

    /** Slides the panel in from the right edge over a dimmed page. */
    private void openDrawer() {
        if (mDrawerPanel == null || mDrawerOpen || mDrawerPanel.getChildCount() == 0) return;
        mDrawerOpen = true;
        /* measured off the reference: the panel is the right 37.45% of the
         * screen, and it starts its slide from exactly that far out */
        int width = mDrawerPanel.getWidth() > 0 ? mDrawerPanel.getWidth()
                : Math.round(screenWidth() * 0.3745f);
        mDrawerPanel.animate().cancel();
        mDrawerPanel.setTranslationX(width);
        mDrawerPanel.setVisibility(View.VISIBLE);
        mDrawerPanel.animate().translationX(0f)
                .setDuration(DRAWER_DURATION)
                .setInterpolator(new DecelerateInterpolator(2f))
                .start();
        if (mDrawerScrim != null) {
            mDrawerScrim.animate().cancel();
            mDrawerScrim.setAlpha(0f);
            mDrawerScrim.setVisibility(View.VISIBLE);
            mDrawerScrim.animate().alpha(1f).setDuration(DRAWER_DURATION).start();
        }
    }

    /**
     * Folds the drawer away.
     *
     * @param animate false when the page itself is leaving, in which case the
     *                drawer has to be gone before the slide starts: otherwise
     *                it is seen travelling off the edge with the page
     */
    private void closeDrawer(boolean animate) {
        if (mDrawerPanel == null) return;
        boolean wasOpen = mDrawerOpen;
        mDrawerOpen = false;
        mDrawerPanel.animate().cancel();
        if (mDrawerScrim != null) mDrawerScrim.animate().cancel();

        if (!wasOpen || !animate || mDrawerPanel.getVisibility() != View.VISIBLE) {
            mDrawerPanel.setVisibility(View.GONE);
            mDrawerPanel.setTranslationX(0f);
            if (mDrawerScrim != null) {
                mDrawerScrim.setVisibility(View.GONE);
                mDrawerScrim.setAlpha(1f);
            }
            return;
        }
        hideKeyboard(mDrawerPanel);
        int width = mDrawerPanel.getWidth() > 0 ? mDrawerPanel.getWidth() : screenWidth();
        mDrawerPanel.animate().translationX(width)
                .setDuration(DRAWER_DURATION)
                .setInterpolator(new DecelerateInterpolator(2f))
                .withEndAction(() -> {
                    mDrawerPanel.setVisibility(View.GONE);
                    mDrawerPanel.setTranslationX(0f);
                })
                .start();
        if (mDrawerScrim != null) {
            mDrawerScrim.animate().alpha(0f)
                    .setDuration(DRAWER_DURATION)
                    .withEndAction(() -> mDrawerScrim.setVisibility(View.GONE))
                    .start();
        }
    }

    private void hideKeyboard(View anchor) {
        if (anchor == null) return;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
    }

    /**
     * Fills the download drawer: the queue, its count and the close key.
     *
     * <p>Nothing else is in here on purpose. The catalogue is the page's left
     * column, the address form is its right column, and the drawer is only
     * ever the list of downloads: running ones first, finished ones under
     * them, in the order the manager keeps them.
     */
    private void bindDownloadDrawer(View drawer) {
        if (drawer == null) return;

        drawer.findViewById(R.id.ucl_drawer_close).setOnClickListener(v -> closeDrawer(true));
        mDrawerCount = drawer.findViewById(R.id.ucl_drawer_count);

        mTaskList = drawer.findViewById(R.id.ucl_drawer_task_list);
        mTaskEmpty = drawer.findViewById(R.id.ucl_drawer_task_empty);
        mTaskAdapter = new TaskAdapter();
        mTaskList.setAdapter(mTaskAdapter);
        mTaskList.setOnItemClickListener((parent, view, position, id) ->
                openTask(mTaskAdapter.getItem(position)));
    }

    /**
     * The catalogue, as the page's left column.
     *
     * <p>Rows come from the category arrays rather than from the layout, so a
     * new category is one entry in each array and nothing else: this list, the
     * page and the panels all follow.
     */
    /**
     * Fills the left column with one row per category.
     *
     * <p>Rows are views added to a column, not a list adapter, because that is
     * what the account center does: the same 48 dp height, the same 24 dp icon,
     * the same 2 dp gap between rows, and the same selected plate. A list view
     * would bring its own dividers and its own idea of row height, and the two
     * pages would drift apart again.
     */
    private void rebuildCatalogue() {
        if (mCategoryList == null) return;
        mCategoryList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < CATEGORY_LABEL.length; i++) {
            final int category = i;
            View row = inflater.inflate(R.layout.ucl_cat_row, mCategoryList, false);

            ImageView icon = row.findViewById(R.id.ucl_cat_row_icon);
            TextView name = row.findViewById(R.id.ucl_cat_row_name);
            TextView state = row.findViewById(R.id.ucl_cat_row_state);

            boolean wired = CATEGORY_WIRED[category];
            boolean picked = category == mDownloadCategory;

            /* the shell's row background only lights up on a selected row */
            row.setSelected(picked);
            if (icon != null) icon.setImageResource(CATEGORY_ICON[category]);
            if (name != null) name.setText(CATEGORY_LABEL[category]);
            if (state != null) {
                state.setText(wired
                        ? R.string.ucl_dl_state_ready : R.string.ucl_dl_state_planned);
                state.setTextColor(ContextCompat.getColor(UclPageActivity.this,
                        wired ? R.color.ucl_text_secondary : R.color.ucl_text_muted));
            }
            row.setOnClickListener(v -> showCategory(category));
            mCategoryList.addView(row);
        }
    }

    /* ===================================================================== */
    /* download manager                                                      */
    /* ===================================================================== */

    private void setupDownloadPage() {
        /* the queue is read back before anything renders it: a job the process
         * did not outlive comes back as interrupted, with its percentage, so
         * the row can offer to carry on instead of starting from zero */
        UclDownloadManager.getInstance().attach(this);
        mMirrorInput = findViewById(R.id.ucl_mirror_input);
        mMirrorStatus = findViewById(R.id.ucl_mirror_status);
        mMirrorHost = findViewById(R.id.ucl_mirror_host);
        mMirrorDot = findViewById(R.id.ucl_mirror_dot);
        mTaskCount = findViewById(R.id.ucl_task_count);
        mTaskBar = findViewById(R.id.ucl_dl_task_bar);
        mTaskBarText = findViewById(R.id.ucl_task_bar_text);
        mTaskBarDot = findViewById(R.id.ucl_task_bar_dot);
        mTaskBarToggle = findViewById(R.id.ucl_task_bar_toggle);

        /* the left column: what this center can install */
        mCategoryList = findViewById(R.id.ucl_dl_cat_list);

        /* the drawer holds the queue and nothing else; the strip under the
         * detail panel is only its summary and its way in */
        bindDownloadDrawer(installDrawer(R.layout.ucl_drawer_download));
        if (mTaskBar != null) mTaskBar.setOnClickListener(v -> openDrawer());

        showCategory(mDownloadCategory);

        String saved = LauncherPreferences.PREF_DOWNLOAD_SOURCE;
        mMirrorInput.setText(saved);
        setMirrorHost(saved);
        if (TextUtils.isEmpty(saved)) {
            setMirrorStatus(R.string.ucl_dl_status_empty, R.color.ucl_text_muted);
        } else if (UclMirrorSource.hasProbe()) {
            setMirrorStatusOk(UclMirrorSource.cachedName(), UclMirrorSource.cachedCount());
        } else {
            setMirrorStatus(R.string.ucl_dlt_status_idle, R.color.ucl_text_muted);
        }

        /* typing invalidates whatever the last probe said */
        mMirrorInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (mMirrorStatus == null) return;
                String typed = s == null ? "" : s.toString().trim();
                /* the card always shows the address the field holds, so it can
                 * never be out of step with what a download would use */
                setMirrorHost(typed);
                if (TextUtils.isEmpty(typed)) {
                    setMirrorStatus(R.string.ucl_dl_status_empty, R.color.ucl_text_muted);
                    return;
                }
                if (!UclMirrorSource.normalize(typed)
                        .equals(LauncherPreferences.PREF_DOWNLOAD_SOURCE)) {
                    setMirrorStatus(R.string.ucl_dlt_status_idle, R.color.ucl_text_muted);
                }
            }
        });

        findViewById(R.id.ucl_btn_start_download).setOnClickListener(this);
        findViewById(R.id.ucl_btn_probe_mirror).setOnClickListener(this);
        /* both live in the left column now, under a groove */
        View terms = findViewById(R.id.ucl_btn_view_disclaimer);
        if (terms != null) terms.setOnClickListener(this);
        View folder = findViewById(R.id.ucl_btn_open_download_dir);
        if (folder != null) folder.setOnClickListener(this);
        View settings = findViewById(R.id.ucl_btn_download_settings);
        if (settings != null) {
            settings.setOnClickListener(v -> UclDownloadSettingsDialog.open(this));
        }
        UclDownloadManager.getInstance().addListener(this);
        updateTaskEmpty();

        /* Opening the page is itself a step in the chain: the terms are shown
         * before anything can be typed, and again before every download. Wait
         * for the slide-in to land, otherwise the window pops over a moving
         * page and looks misplaced. */
        mScreenHost.postDelayed(() -> {
            if (!isFinishing() && mPage == PAGE_DOWNLOAD) {
                UclLegalDialogs.disclaimer(this, this::backToHome, null);
            }
        }, SLIDE_DURATION);
    }

    /**
     * Picks a category and shows its panel.
     *
     * <p>The left column and the page have to agree: whichever row is picked is
     * the one the right column is talking about, and its heading says the
     * name. Only Java has a panel that can start anything; the other four swap
     * in the panel that says what is still missing, which is why none of them
     * opens a window that only repeats itself.
     */
    private void showCategory(int category) {
        if (category < 0 || category >= CATEGORY_LABEL.length) category = CAT_JAVA;
        mDownloadCategory = category;

        boolean wired = CATEGORY_WIRED[category];
        boolean runtime = category == CAT_RUNTIME;
        View panelJava = findViewById(R.id.ucl_dl_panel_java);
        View panelRuntime = findViewById(R.id.ucl_dl_panel_runtime);
        View panelSoon = findViewById(R.id.ucl_dl_panel_soon);
        if (panelJava != null) {
            panelJava.setVisibility(category == CAT_JAVA ? View.VISIBLE : View.GONE);
        }
        if (panelRuntime != null) {
            panelRuntime.setVisibility(runtime ? View.VISIBLE : View.GONE);
        }
        if (panelSoon != null) {
            panelSoon.setVisibility(wired ? View.GONE : View.VISIBLE);
        }
        /* the runtime list carries one key per version whose text depends on
         * whether that version is already unpacked, so it is built on entry
         * rather than shipped in the layout */
        if (runtime) rebuildJavaRows();

        TextView title = findViewById(R.id.ucl_dl_detail_title);
        if (title != null) title.setText(CATEGORY_LABEL[category]);

        if (panelSoon != null && !wired) {
            TextView body = findViewById(R.id.ucl_dl_soon_body);
            if (body != null) {
                body.setText(getString(R.string.ucl_dl_soon_body,
                        getString(CATEGORY_LABEL[category])));
            }
        }
        /* each panel starts at its own top: carrying the last one's scroll
         * offset over lands the user in the middle of a shorter panel */
        View scroll = findViewById(R.id.ucl_dl_detail_scroll);
        if (scroll != null) scroll.scrollTo(0, 0);
        /* the picked row wears the selected plate, and that is decided while
         * the column is built, so it has to be rebuilt after a pick */
        rebuildCatalogue();
    }

    @Override
    public void onTasksUpdated() {
        if (mTaskAdapter != null) mTaskAdapter.notifyDataSetChanged();
        updateTaskEmpty();
        /* a finished runtime changes its key from "download" to "reinstall".
         * Rebuilding on every progress tick would inflate four rows twice a
         * second for nothing, so the list is rebuilt only when the set of
         * installed runtimes actually moved. */
        if (mDownloadCategory == CAT_RUNTIME && !javaSignature().equals(mJavaSignature)) {
            rebuildJavaRows();
        }
    }

    /**
     * One row per Java version this device can run.
     *
     * <p>The list is what the launcher is allowed to fetch, not what is on
     * disk: an installed version stays in it with a different key, because
     * "reinstall" is a real thing to want after a broken unpack.
     */
    private void rebuildJavaRows() {
        LinearLayout list = findViewById(R.id.ucl_java_list);
        if (list == null) return;
        TextView arch = findViewById(R.id.ucl_dl_runtime_arch);
        if (arch != null) {
            arch.setText(getString(R.string.ucl_dl_runtime_arch, UclJavaRuntimes.deviceArch()));
        }
        mJavaSignature = javaSignature();
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (UclJavaRuntimes.Build build : UclJavaRuntimes.builds()) {
            View row = inflater.inflate(R.layout.ucl_java_row, list, false);
            UclTypeface.apply(row);
            TextView name = row.findViewById(R.id.ucl_java_row_name);
            TextView meta = row.findViewById(R.id.ucl_java_row_meta);
            UclButton action = row.findViewById(R.id.ucl_java_row_action);

            boolean installed = UclJavaRuntimes.isInstalled(build.major);
            name.setText(build.displayName());
            meta.setText(getString(R.string.ucl_java_row_meta, build.arch,
                    build.bytes / (1024L * 1024L)));
            action.setText(installed
                    ? R.string.ucl_java_action_reinstall : R.string.ucl_java_action_download);
            action.setOnClickListener(v -> startJavaDownload(build));
            list.addView(row);
        }
    }

    private String javaSignature() {
        StringBuilder out = new StringBuilder(UclJavaRuntimes.deviceArch());
        for (UclJavaRuntimes.Build build : UclJavaRuntimes.builds()) {
            out.append('|').append(build.major)
                    .append(UclJavaRuntimes.isInstalled(build.major) ? '+' : '-');
        }
        return out.toString();
    }

    /**
     * Starts a runtime download, or takes the user back to the job that is
     * already working on it.
     *
     * <p>Two jobs for one runtime unpack into one folder, so the duplicate is
     * refused here with the row that owns it rather than by the manager's
     * silent return.
     */
    private void startJavaDownload(UclJavaRuntimes.Build build) {
        UclDownloadManager manager = UclDownloadManager.getInstance();
        String id = build.displayName();
        if (manager.hasUnfinished(id)) {
            openTask(manager.findUnfinished(id));
            return;
        }
        if (manager.hasActiveTask()) {
            UclLegalDialogs.notice(this, R.string.ucl_dlg_busy_title,
                    getString(R.string.ucl_dlg_busy_body), R.string.ucl_dlg_ok, null);
            return;
        }
        manager.startJavaDownload(this, build.major);
        UclDownloadManager.DownloadTask active = manager.getActiveTask();
        if (active != null && active.javaMajor == build.major) {
            UclDownloadProgressDialog.show(this, active);
        }
        rebuildJavaRows();
    }

    /** A row was tapped in the drawer: one job, one window. */
    private void openTask(@Nullable UclDownloadManager.DownloadTask task) {
        if (task == null) return;
        /* a running row brings the progress window back: closing it with
         * "run in background" must not leave the job unreachable */
        if (task.state == UclDownloadManager.STATE_DOWNLOADING) {
            UclDownloadProgressDialog.show(this, task);
            return;
        }
        /* everything else opens the same window its own key opens, so tapping
         * a row and pressing its key never lead to two different places */
        UclTaskDetailsDialog.open(this, task);
    }

    private void updateTaskEmpty() {
        int count = mTaskAdapter == null ? 0 : mTaskAdapter.getCount();
        boolean empty = count == 0;
        if (mTaskEmpty != null) mTaskEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (mTaskList != null) mTaskList.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (mDrawerCount != null) {
            mDrawerCount.setText(getString(R.string.ucl_drawer_tasks_count, count));
        }
        if (mTaskCount != null) {
            mTaskCount.setText(empty ? "" : getString(R.string.ucl_task_count, count));
        }
        /* nothing to report means no strip at all: the drawer is still there,
         * one tap away on the bar, and it says the queue is empty */
        if (mTaskBar != null) mTaskBar.setVisibility(empty ? View.GONE : View.VISIBLE);
        updateTaskBar(count);
    }

    /** The one line the strip carries: what is moving, or that nothing is. */
    private void updateTaskBar(int count) {
        if (mTaskBarText == null) return;
        UclDownloadManager.DownloadTask running = null;
        for (int i = 0; i < count; i++) {
            UclDownloadManager.DownloadTask task = mTaskAdapter.getItem(i);
            if (task != null && task.state == UclDownloadManager.STATE_DOWNLOADING) {
                running = task;
                break;
            }
        }
        if (running != null) {
            mTaskBarText.setText(getString(R.string.ucl_task_bar_downloading,
                    running.versionId,
                    getString(R.string.ucl_task_downloading, running.progress)));
        } else {
            /* nothing is on the wire, but a killed run may be waiting: saying
             * "nothing running" there would hide a job the user can finish */
            UclDownloadManager.DownloadTask waiting = null;
            for (int i = 0; i < count; i++) {
                UclDownloadManager.DownloadTask task = mTaskAdapter.getItem(i);
                if (task != null && task.state == UclDownloadManager.STATE_INTERRUPTED) {
                    waiting = task;
                    break;
                }
            }
            mTaskBarText.setText(waiting != null
                    ? getString(R.string.ucl_task_bar_interrupted, waiting.versionId,
                    getString(R.string.ucl_task_interrupted, waiting.progress))
                    : getString(R.string.ucl_task_none_running));
        }
        if (mTaskBarDot != null) {
            mTaskBarDot.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this,
                            running != null ? R.color.ucl_accent_light : R.color.ucl_text_muted)));
        }
    }

    /** The dot beside the status line: same colour as the message it sits on. */
    private void tintMirrorDot(int colorRes) {
        if (mMirrorDot == null) return;
        mMirrorDot.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(this, colorRes)));
    }

    private void setMirrorStatus(int textRes, int colorRes) {
        if (mMirrorStatus == null) return;
        mMirrorStatus.setText(textRes);
        mMirrorStatus.setTextColor(ContextCompat.getColor(this, colorRes));
        tintMirrorDot(colorRes);
    }

    private void setMirrorStatusOk(String name, int count) {
        if (mMirrorStatus == null) return;
        /* most mirrors advertise no server name at all, and falling back to the
         * address would just repeat the line above the status; only a real name
         * is worth printing */
        String address = LauncherPreferences.PREF_DOWNLOAD_SOURCE;
        boolean hasName = !TextUtils.isEmpty(name) && !name.equals(address);
        mMirrorStatus.setText(hasName
                ? getString(R.string.ucl_dlt_status_ok, name, count)
                : getString(R.string.ucl_dlt_status_ok_bare, count));
        mMirrorStatus.setTextColor(ContextCompat.getColor(this, R.color.ucl_accent_light));
        tintMirrorDot(R.color.ucl_accent_light);
    }

    private void setMirrorStatusFailed() {
        if (mMirrorStatus == null) return;
        /* the address is on the line above already: the status says what went
         * wrong, the card says where */
        mMirrorStatus.setText(R.string.ucl_dlt_status_fail);
        mMirrorStatus.setTextColor(ContextCompat.getColor(this, R.color.ucl_dlt_bad));
        tintMirrorDot(R.color.ucl_dlt_bad);
    }

    /**
     * The address line of the source card. It mirrors the field, never the
     * other way round, so the card can only ever show what a download would
     * actually use. An empty field reads "not set" rather than an empty line.
     */
    private void setMirrorHost(CharSequence value) {
        if (mMirrorHost == null) return;
        boolean empty = TextUtils.isEmpty(value);
        mMirrorHost.setText(empty ? getString(R.string.ucl_dl_source_none) : value);
        mMirrorHost.setTextColor(ContextCompat.getColor(this,
                empty ? R.color.ucl_text_muted : R.color.ucl_text_primary));
    }

    /** Sends the cursor to the address field: the card and the notice both do. */
    private void focusMirrorInput() {
        if (mMirrorInput == null) return;
        mMirrorInput.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(mMirrorInput,
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    /** A notice rather than a Toast: an empty field has to send the user back. */
    private void askForMirror() {
        UclLegalDialogs.notice(this, R.string.ucl_dlg_need_mirror_title,
                getString(R.string.ucl_dlg_need_mirror_body), R.string.ucl_dlg_goto,
                this::focusMirrorInput);
    }

    /**
     * Runs the whole confirmation chain, then probes the source.
     *
     * <p>Four windows every single time: terms (scroll to the end), address
     * confirmation, risk acknowledgement, final go-ahead. The chain is not
     * remembered between attempts on purpose.
     */
    private void startDownloadFlow() {
        if (UclDownloadManager.getInstance().hasActiveTask()) {
            UclLegalDialogs.notice(this, R.string.ucl_dlg_busy_title,
                    getString(R.string.ucl_dlg_busy_body), R.string.ucl_dlg_ok, null);
            return;
        }
        String raw = mMirrorInput == null ? "" : mMirrorInput.getText().toString().trim();
        if (raw.isEmpty()) {
            askForMirror();
            return;
        }
        final String root = UclMirrorSource.normalize(raw);
        setMirrorStatus(R.string.ucl_dlt_status_checking, R.color.ucl_text_secondary);

        /* declining here only aborts the chain, it does not leave the page */
        UclLegalDialogs.disclaimer(this, () -> { }, () ->
                UclLegalDialogs.confirmSource(this, root, () ->
                        UclLegalDialogs.confirmRisk(this, () -> {
                            mMirrorInput.setText(root);
                            if (root.equals(LauncherPreferences.PREF_DOWNLOAD_SOURCE)
                                    && UclMirrorSource.hasProbe()) {
                                UclLegalDialogs.finalConfirm(this,
                                        root,
                                        UclMirrorSource.cachedCount(),
                                        this::pickVersionAndDownload);
                            } else {
                                probeMirror(true);
                            }
                        })));
    }

    /** Detects the source under the current field value. */
    private void probeMirror(boolean thenContinue) {
        String raw = mMirrorInput == null ? "" : mMirrorInput.getText().toString().trim();
        if (raw.isEmpty()) {
            askForMirror();
            return;
        }
        final String root = UclMirrorSource.normalize(raw);
        setMirrorStatus(R.string.ucl_dlt_status_checking, R.color.ucl_text_secondary);
        UclMirrorSource.resolve(root, mirror -> {
            if (isFinishing() || isDestroyed() || mMirrorStatus == null) return;
            if (mirror == null) {
                setMirrorStatusFailed();
                UclLegalDialogs.notice(this, R.string.ucl_dlg_mirror_failed_title,
                        getString(R.string.ucl_dlg_mirror_failed_body, root),
                        R.string.ucl_dlg_ok, null);
                return;
            }
            UclMirrorSource.save(mirror);
            /* the cached list belongs to whichever source was used before */
            new File(Tools.DIR_DATA, "version_list.json").delete();
            setMirrorStatusOk(mirror.serverName, mirror.versionCount);
            if (mMirrorInput != null) mMirrorInput.setText(mirror.root);
            if (thenContinue) {
                UclLegalDialogs.finalConfirm(this, mirror.root,
                        mirror.versionCount, this::pickVersionAndDownload);
            }
        });
    }

    private void pickVersionAndDownload() {
        UclVersionPickerDialog.open(this, version -> {
            UclDownloadManager manager = UclDownloadManager.getInstance();
            /* two jobs for one version write the same folder, so the second is
             * refused here as well as in the manager: this is the copy the user
             * sees, and it says why */
            if (Tools.isVersionInstalled(version)) {
                showVersionPresent(version);
                return;
            }
            if (manager.hasUnfinished(version)) {
                UclLegalDialogs.notice(this, R.string.ucl_task_duplicate_title,
                        getString(R.string.ucl_task_duplicate_body, version),
                        R.string.ucl_dlg_ok, null);
                return;
            }
            if (manager.hasActiveTask()) {
                UclLegalDialogs.notice(this, R.string.ucl_dlg_busy_title,
                        getString(R.string.ucl_dlg_busy_body), R.string.ucl_dlg_ok, null);
                return;
            }
            downloadAndShowProgress(version);
        });
    }

    /**
     * Starts a version download and puts the progress window on top of it.
     *
     * <p>The window is opened from the task the manager just created rather
     * than unconditionally: a storage or source check can fail the task before
     * a single byte is fetched, and there is nothing to show progress for in
     * that case.
     */
    private void downloadAndShowProgress(String versionId) {
        UclDownloadManager manager = UclDownloadManager.getInstance();
        manager.startDownload(this, versionId);
        UclDownloadManager.DownloadTask active = manager.getActiveTask();
        if (active != null) {
            UclDownloadProgressDialog.show(this, active);
        }
    }

    private class TaskAdapter extends BaseAdapter {
        private final List<UclDownloadManager.DownloadTask> mTasks =
                UclDownloadManager.getInstance().getTasks();

        @Override
        public int getCount() {
            return mTasks.size();
        }

        @Override
        public UclDownloadManager.DownloadTask getItem(int position) {
            return mTasks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = LayoutInflater.from(UclPageActivity.this)
                    .inflate(R.layout.ucl_task_row, parent, false);
            UclTypeface.apply(row);
            /* ucl_task_row is authored in sp now, so it must not go through the
             * concept-pixel pass: that would shrink it below legibility */
            UclDownloadManager.DownloadTask task = getItem(position);
            TextView title = row.findViewById(R.id.ucl_task_title);
            TextView state = row.findViewById(R.id.ucl_task_state);
            TextView meta = row.findViewById(R.id.ucl_task_meta);
            TextView action = row.findViewById(R.id.ucl_task_action);
            ProgressBar bar = row.findViewById(R.id.ucl_task_progress);

            title.setText(task.versionId);
            /* the running job wears the picked plate of this shell, so the row
             * that is actually moving is the one that stands out */
            row.setSelected(task.state == UclDownloadManager.STATE_DOWNLOADING);
            /* meta is reserved for the second line of context; nothing uses it
             * yet, and an empty TextView would still take up its margin */
            meta.setVisibility(View.GONE);

            int stateColor;
            switch (task.state) {
                case UclDownloadManager.STATE_DONE:
                    state.setText(R.string.ucl_task_done);
                    stateColor = R.color.ucl_text_secondary;
                    bar.setProgress(100);
                    /* every finished job answers the same question -- what did
                     * it do -- so every finished job offers the same key */
                    bindDetailsAction(action, task);
                    break;
                case UclDownloadManager.STATE_CANCELLED:
                    state.setText(task.purgedFiles > 0
                            ? getString(R.string.ucl_task_cancelled_purged, task.purgedFiles)
                            : getString(R.string.ucl_task_cancelled));
                    stateColor = R.color.ucl_text_muted;
                    bar.setVisibility(View.GONE);
                    bindDetailsAction(action, task);
                    break;
                case UclDownloadManager.STATE_FAILED:
                    /* the actual reason: "no version list", a 404 URL, a sha1
                     * mismatch -- a bare "Failed" tells nobody anything */
                    state.setText(TextUtils.isEmpty(task.progressText)
                            ? getString(R.string.ucl_task_failed) : task.progressText);
                    stateColor = R.color.ucl_dlt_bad;
                    bar.setVisibility(View.GONE);
                    bindDetailsAction(action, task);
                    break;
                case UclDownloadManager.STATE_INTERRUPTED:
                    /* the process was killed: nothing failed, and what is on
                     * disk is still good, so the one useful action is to carry
                     * on rather than to start over */
                    state.setText(getString(R.string.ucl_task_interrupted, task.progress));
                    stateColor = R.color.ucl_dlt_bad;
                    bar.setVisibility(View.VISIBLE);
                    bar.setProgress(Math.max(0, Math.min(100, task.progress)));
                    bindResumeAction(action, task);
                    break;
                default:
                    /* the row mirrors the progress window: percentage, then
                     * speed, then whatever file is on the wire. One line: the
                     * sidebar is too narrow to spend one on the file name */
                    StringBuilder line = new StringBuilder(
                            getString(R.string.ucl_task_downloading, task.progress));
                    if (task.speedBytesPerSecond > 0d) {
                        line.append("，").append(formatSpeed(task.speedBytesPerSecond));
                    }
                    if (!TextUtils.isEmpty(task.currentFile)) {
                        line.append("，").append(task.currentFile);
                    }
                    state.setText(line.toString());
                    stateColor = R.color.ucl_accent_light;
                    bar.setProgress(Math.max(0, Math.min(100, task.progress)));
                    bindTaskAction(action, R.string.ucl_task_action_cancel,
                            R.color.ucl_dlt_bad, task);
                    break;
            }
            state.setTextColor(ContextCompat.getColor(UclPageActivity.this, stateColor));
            return row;
        }

        /**
         * The row's key for a job that is not running: open its details.
         *
         * <p>There is no delete here on purpose. A queue row is a record of
         * something the user started; the thing worth offering is what happened
         * to it, and the retry/resume key lives in that window rather than
         * spending the row's only slot on a destructive action.
         */
        private void bindDetailsAction(TextView action,
                                       UclDownloadManager.DownloadTask task) {
            action.setVisibility(View.VISIBLE);
            action.setText(R.string.ucl_task_action_details);
            action.setTextColor(ContextCompat.getColor(UclPageActivity.this,
                    R.color.ucl_text_secondary));
            action.setOnClickListener(v -> UclTaskDetailsDialog.open(UclPageActivity.this, task));
        }

        /** One tap to carry on with a job the process interrupted. */
        private void bindResumeAction(TextView action,
                                      UclDownloadManager.DownloadTask task) {
            action.setVisibility(View.VISIBLE);
            action.setText(R.string.ucl_task_action_resume);
            action.setTextColor(ContextCompat.getColor(UclPageActivity.this,
                    R.color.ucl_accent_light));
            action.setOnClickListener(v -> {
                if (UclDownloadManager.getInstance().hasActiveTask()) {
                    Toast.makeText(UclPageActivity.this, R.string.ucl_download_busy,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                UclDownloadManager.getInstance().resumeDownload(UclPageActivity.this, task);
                UclDownloadProgressDialog.show(UclPageActivity.this, task);
            });
        }

        /**
         * Wires the row's own button, so a running job can be stopped without
         * opening a window first.
         */
        private void bindTaskAction(TextView action, int textRes, int colorRes,
                                    UclDownloadManager.DownloadTask task) {
            action.setVisibility(View.VISIBLE);
            action.setText(textRes);
            action.setTextColor(ContextCompat.getColor(UclPageActivity.this, colorRes));
            action.setOnClickListener(v -> {
                if (task.state == UclDownloadManager.STATE_DOWNLOADING) {
                    UclDownloadManager.getInstance().cancelDownload(task);
                } else if (!UclDownloadManager.getInstance().hasActiveTask()) {
                    UclDownloadManager.getInstance()
                            .startDownload(UclPageActivity.this, task.versionId);
                } else {
                    Toast.makeText(UclPageActivity.this, R.string.ucl_download_busy,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1024d * 1024d) {
            return String.format(java.util.Locale.US, "%.2f MB/s",
                    bytesPerSecond / (1024d * 1024d));
        }
        if (bytesPerSecond >= 1024d) {
            return String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSecond / 1024d);
        }
        return String.format(java.util.Locale.US, "%.0f B/s", bytesPerSecond);
    }

    /* ===================================================================== */
    /* pad mapper                                                            */
    /* ===================================================================== */

    /**
     * The mapper, as a step inside the shell rather than a window of its own.
     *
     * <p>It used to be a black dialog: a second window with its own header,
     * arriving from nowhere, with no top bar behind it. A page gets all of that
     * right by not being special - the bar, the plate, the slide and the cutout
     * behaviour are the ones every other page already has.
     */
    void openGamepadMapper() {
        openPageAnimated(PAGE_GAMEPAD);
    }

    private void setupGamepadPage() {
        mGamepadList = findViewById(R.id.ucl_gamepad_list);
        mGamepadAdapter = new GamepadMapperAdapter(this,
                R.layout.item_ucl_controller_mapping, R.layout.item_ucl_dropdown_text);
        mGamepadAdapter.setGrabState(mGamepadGrabState);
        mGamepadList.setLayoutManager(new LinearLayoutManager(this));
        mGamepadList.setAdapter(mGamepadAdapter);
        mGamepadList.setOnKeyListener(this::onGamepadKey);
        mGamepadList.setOnGenericMotionListener(this::onGamepadMotion);
        /* the list is the view that listens for the pad, so it has to hold
         * focus from the first frame: a page that opens with focus on the mode
         * key hears nothing until something is touched */
        mGamepadList.post(() -> {
            if (mGamepadList != null) mGamepadList.requestFocus();
        });

        RemapperView.Builder builder = new RemapperView.Builder(null)
                .remapA(true)
                .remapB(true)
                .remapX(true)
                .remapY(true)
                .remapLeftJoystick(true)
                .remapRightJoystick(true)
                .remapStart(true)
                .remapSelect(true)
                .remapLeftShoulder(true)
                .remapRightShoulder(true)
                .remapLeftTrigger(true)
                .remapRightTrigger(true)
                .remapDpad(true);
        mGamepadManager = new RemapperManager(this, builder);

        UclButton mode = findViewById(R.id.ucl_gamepad_mode);
        mode.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        mode.setText(grabStateName(mGamepadGrabState));
        mode.setOnClickListener(v -> pickGrabState(mode));
    }

    /**
     * Stops listening to the pad.
     *
     * <p>The gamepad object polls the display every frame through the
     * choreographer; dropping our reference would leave that loop running
     * against a view that is gone, so it is told to remove itself.
     */
    private void releaseGamepad() {
        mGamepadExitHandler.removeCallbacks(mGamepadExit);
        if (mGamepadPad != null) {
            mGamepadPad.removeSelf();
            mGamepadPad = null;
        }
        mGamepadList = null;
        mGamepadAdapter = null;
        mGamepadManager = null;
    }

    /** The two modes the mapper writes to: the menu map or the in-game map. */
    private CharSequence grabStateName(boolean inGame) {
        return getString(inGame ? R.string.customctrl_visibility_ingame
                : R.string.customctrl_visibility_in_menus);
    }

    private void pickGrabState(UclButton mode) {
        if (mGamepadAdapter == null) return;
        String[] names = {
                getString(R.string.customctrl_visibility_in_menus),
                getString(R.string.customctrl_visibility_ingame)
        };
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final UclDialog[] holder = new UclDialog[1];
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            boolean picked = (index == 1) == mGamepadGrabState;
            TextView item = new TextView(this);
            item.setText(names[i]);
            item.setTextColor(ContextCompat.getColor(this,
                    picked ? R.color.ucl_accent_light : R.color.ucl_text_primary));
            item.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(14), dp(12), dp(14), dp(12));
            item.setBackgroundResource(picked
                    ? R.drawable.ucl_nav_row_selected : R.drawable.ucl_nav_row_bg);
            item.setOnClickListener(v -> {
                mGamepadGrabState = index == 1;
                mGamepadAdapter.setGrabState(mGamepadGrabState);
                mode.setText(names[index]);
                if (holder[0] != null) holder[0].dismiss();
            });
            list.addView(item);
        }
        holder[0] = UclDialog.build(this)
                .title(getString(R.string.controller_remapper_operating_mode))
                .body(list, true)
                .closeMark(true)
                .show();
    }

    private boolean onGamepadKey(View view, int keyCode, KeyEvent event) {
        if (!Gamepad.isGamepadEvent(event)) return false;
        ensureGamepad(event.getDevice());
        if (mGamepadPad == null || mGamepadManager == null) return false;
        mGamepadManager.handleKeyEventInput(this, event, mGamepadPad);
        return true;
    }

    private boolean onGamepadMotion(View view, MotionEvent event) {
        if (!Gamepad.isGamepadEvent(event)) return false;
        ensureGamepad(event.getDevice());
        if (mGamepadPad == null || mGamepadManager == null) return false;
        mGamepadManager.handleMotionEventInput(this, event, mGamepadPad);
        return true;
    }

    private void ensureGamepad(InputDevice device) {
        if (mGamepadPad != null || device == null) return;
        mGamepadPad = new Gamepad(mScreenHost, device, mGamepadAdapter, false) {
            @Override
            public void handleGamepadInput(int keyCode, float value) {
                /* hold SELECT: a pad has no back key, and this is the gesture
                 * the mapper has always used to leave */
                if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
                    if (value > 0.5f) mGamepadExitHandler.postDelayed(mGamepadExit, 400);
                    else mGamepadExitHandler.removeCallbacks(mGamepadExit);
                }
                super.handleGamepadInput(keyCode, value);
            }
        };
    }

    /* ===================================================================== */
    /* settings                                                              */
    /* ===================================================================== */

    /**
     * Rebuilds this activity in the language just picked.
     *
     * <p>{@code recreate()} is the hot switch: the new instance attaches through
     * {@code LocaleUtils.setLocale}, so every string, every layout and every
     * dialog comes back in the new language. The static below is what makes it
     * land back on the language page instead of the home screen.
     */
    void reloadForLanguage() {
        if (mLanguageSwitch != null) return;    /* one change at a time */
        sRestorePage = PAGE_SETTINGS;
        sRestoreCategory = UclSettingsPage.CAT_LANGUAGE;
        /* The plate is the one the account centre shows while its page loads:
         * a language change is the same kind of wait, and inventing a second
         * way of saying "please wait" would teach the user nothing. */
        mLoadingPlate = UclDialog.loading(this, getString(R.string.ucl_language_switching));
        mLanguageSwitch = new Runnable() {
            @Override
            public void run() {
                mLanguageSwitch = null;
                /* the plate lives in this window: it has to be dismissed before
                 * the window is torn down, or it outlives the activity */
                dismissLoadingPlate();
                recreate();
            }
        };
        mUi.postDelayed(mLanguageSwitch, LANGUAGE_SWITCH_HOLD_MS);
    }

    private void setupSettingsPage() {
        mSettingsCatList = findViewById(R.id.ucl_settings_cat_list);
        mSettingsScroll = findViewById(R.id.ucl_settings_scroll);
        mSettingsTitle = findViewById(R.id.ucl_settings_title);
        mSettingsBody = findViewById(R.id.ucl_settings_body);
        mSettingsPage = new UclSettingsPage(this, mSettingsBody, this::refreshSettingsCategories);
        buildSettingsCategories();
        showSettingsCategory(mSettingsCategory);
    }

    /**
     * The six categories. A row is the name over the value that page is
     * currently set to, the same two line shape the version list uses, so the
     * column states the whole launcher's state instead of listing six nouns.
     */
    private void buildSettingsCategories() {
        mSettingsCatList.removeAllViews();
        int[] titles = settingsCategoryTitles();
        for (int i = 0; i < titles.length; i++) {
            final int category = i;
            /* a travelling row: pressing a category takes the name and its
             * value down with the plate, the way every key in this shell does */
            LinearLayout row = new UclPressRow(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), 0, dp(10), 0);
            row.setMinimumHeight(dp(48));
            row.setBackgroundResource(R.drawable.ucl_nav_row_bg);
            row.setClickable(true);
            row.setFocusable(true);

            TextView name = new TextView(this);
            name.setText(titles[i]);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            /* a tab, not body text: it wears the display face in English */
            name.setTypeface(UclTypeface.title(this));

            TextView meta = new TextView(this);
            meta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);

            row.addView(name);
            row.addView(meta);
            row.setOnClickListener(v -> showSettingsCategory(category));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(2);
            mSettingsCatList.addView(row, lp);
        }
        refreshSettingsCategories();
    }

    private int[] settingsCategoryTitles() {
        return new int[]{
                R.string.ucl_settings_video,
                R.string.ucl_settings_control,
                R.string.ucl_settings_java,
                R.string.ucl_settings_misc,
                R.string.ucl_settings_experimental,
                R.string.ucl_settings_about,
                R.string.ucl_settings_language
        };
    }

    /** Repaints the column: the selected plate, and every row's current value. */
    private void refreshSettingsCategories() {
        if (mSettingsCatList == null) return;
        for (int i = 0; i < mSettingsCatList.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) mSettingsCatList.getChildAt(i);
            boolean selected = i == mSettingsCategory;
            row.setSelected(selected);
            TextView name = (TextView) row.getChildAt(0);
            TextView meta = (TextView) row.getChildAt(1);
            name.setTextColor(ContextCompat.getColor(this,
                    selected ? R.color.ucl_accent_light : R.color.ucl_text_primary));
            meta.setTextColor(ContextCompat.getColor(this,
                    selected ? R.color.ucl_text_secondary : R.color.ucl_text_muted));
            meta.setText(settingsSummary(i));
        }
    }

    /** What the row under a category name says. Derived, never stored. */
    private CharSequence settingsSummary(int category) {
        switch (category) {
            case UclSettingsPage.CAT_VIDEO:
                return LauncherPreferences.PREF_RENDERER + " \u00b7 "
                        + Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f) + "%";
            case UclSettingsPage.CAT_CONTROL:
                /* a 0.28 column truncates: the value is what the page is set
                 * to, so the label goes and the state stays */
                return getString(R.string.ucl_settings_meta_gestures) + " "
                        + getString(LauncherPreferences.PREF_DISABLE_GESTURES
                        ? R.string.ucl_settings_meta_off : R.string.ucl_settings_meta_on)
                        + " \u00b7 " + Math.round(LauncherPreferences.PREF_BUTTONSIZE) + "%";
            case UclSettingsPage.CAT_JAVA: {
                String runtime = LauncherPreferences.PREF_DEFAULT_RUNTIME;
                String ram = LauncherPreferences.PREF_RAM_ALLOCATION + " MB";
                return runtime == null || runtime.isEmpty() ? ram : runtime + " \u00b7 " + ram;
            }
            case UclSettingsPage.CAT_MISC:
                return getString(R.string.arc_capes_title) + " "
                        + getString(LauncherPreferences.PREF_ARC_CAPES
                        ? R.string.ucl_settings_meta_on : R.string.ucl_settings_meta_off);
            case UclSettingsPage.CAT_LANGUAGE:
                /* the one summary that cannot be translated: a language is
                 * named in itself */
                return net.kdt.pojavlaunch.utils.LocaleUtils.currentName();
            case UclSettingsPage.CAT_EXPERIMENTAL: {
                int on = 0;
                if (LauncherPreferences.PREF_DUMP_SHADERS) on++;
                if (LauncherPreferences.PREF_BIG_CORE_AFFINITY) on++;
                if (LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER) on++;
                return getString(R.string.ucl_settings_meta_enabled, on);
            }
            default:
                return BuildConfig.VERSION_NAME;
        }
    }

    private void showSettingsCategory(int category) {
        mSettingsCategory = category;
        int[] titles = settingsCategoryTitles();
        mSettingsTitle.setText(titles[category]);
        refreshSettingsCategories();
        mSettingsPage.show(category);
        mSettingsScroll.post(() -> mSettingsScroll.scrollTo(0, 0));
    }

    private void addDetailRow(LinearLayout parent, String key, String value) {
        View row = LayoutInflater.from(this).inflate(R.layout.ucl_auth_info_row, parent, false);
        applyTextScale(row);
        TextView keyView = row.findViewById(R.id.ucl_info_key);
        TextView valueView = row.findViewById(R.id.ucl_info_value);
        keyView.setText(key);
        valueView.setText(value);
        parent.addView(row);
    }

    /* ===================================================================== */
    /* shared helpers                                                        */
    /* ===================================================================== */

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void openGameDirectory() {
        File dir = new File(Tools.DIR_GAME_HOME);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.fromFile(dir), "resource/folder")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(this, dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    /** Scale every plain TextView by the 1920 concept width, skipping UclButton (self-scaling) */
    private void applyTextScale(View root) {
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTextScale(group.getChildAt(i));
            }
        } else if (root instanceof TextView && !(root instanceof UclButton)) {
            TextView textView = (TextView) root;
            textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    textView.getTextSize() * mScale);
        }
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        /* home screen menu: opens a page with the slide transition */
        if (id == R.id.ucl_btn_play) {
            playGame();
            return;
        } else if (id == R.id.ucl_btn_version) {
            openPageAnimated(PAGE_VERSION);
            return;
        } else if (id == R.id.ucl_btn_download) {
            openPageAnimated(PAGE_DOWNLOAD);
            return;
        } else if (id == R.id.ucl_btn_settings) {
            openPageAnimated(PAGE_SETTINGS);
            return;
        } else if (id == R.id.ucl_btn_account) {
            openPageAnimated(PAGE_ACCOUNT);
            return;
        } else if (id == R.id.ucl_btn_social) {
            Toast.makeText(this, R.string.ucl_coming_soon, Toast.LENGTH_SHORT).show();
            return;
        }
        if (mPage == PAGE_ACCOUNT) {
            if (id == R.id.ucl_offline_login) {
                loginOffline();
            } else if (id == R.id.ucl_ygg_login) {
                if (mAuthMode == AUTH_MODE_BLABBY) loginBlabby(mBlabbyForm);
                else loginYggdrasil(mYggForm);
            } else if (id == R.id.ucl_blabby_register) {
                openBlabbyRegister();
            }
        } else if (mPage == PAGE_VERSION) {
            if (id == R.id.ucl_btn_install_version) {
                UclVersionPickerDialog.open(this, UclPageActivity.this::installVersion);
            } else if (id == R.id.ucl_btn_repair_version) {
                repairSelectedVersion();
            } else if (id == R.id.ucl_btn_open_mc_dir) {
                openGameDirectory();
            } else if (id == R.id.ucl_btn_delete_version) {
                deleteSelectedVersion();
            }
        } else if (mPage == PAGE_DOWNLOAD) {
            if (id == R.id.ucl_btn_start_download) {
                startDownloadFlow();
            } else if (id == R.id.ucl_btn_probe_mirror) {
                probeMirror(false);
            } else if (id == R.id.ucl_btn_view_disclaimer) {
                UclLegalDialogs.disclaimer(this, null, null);
            } else if (id == R.id.ucl_btn_open_download_dir) {
                openGameDirectory();
            }
        }
    }

    private void openBlabbyRegister() {
        openWebPage(getString(R.string.ucl_blabby_register_url),
                getString(R.string.ucl_loading_register));
    }

    /* ===================================================================== */
    /* the resident web page                                                 */
    /* ===================================================================== */

    /**
     * Opens a web page as a page of this activity.
     *
     * <p>Not a browser and not a second activity: it is a body like any other
     * page, so it enters from the right while the page it was opened from
     * leaves to the left -- the transition the home screen uses -- and back is
     * the exact reverse.
     *
     * <p>The WebView is built once and kept for the life of the activity. A
     * second visit is a translation: no request, no parse, no layout, no
     * raster, and no plate, because the plate exists to cover the first wait
     * only. The document, its scripts, its scroll position and its painted
     * layers survive because nothing ever destroys or reloads them.
     */
    private void openWebPage(String url, CharSequence loadingTitle) {
        if (isFinishing() || isDestroyed() || mPage == PAGE_WEB) return;
        if (mWeb == null) buildWebPage();

        if (mWebLoaded) {
            /* already here, already painted: just bring it in */
            enterWebPage();
            return;
        }
        if (mWebPending) return;    /* the same load will reveal it */
        mWebPending = true;
        mLoadingPlate = UclDialog.loading(this, loadingTitle);
        beginWebLoad(url);
    }

    /**
     * Creates the one WebView this activity will own.
     *
     * <p>Everything the platform likes to add on long press is off. The site
     * brings its own tooltip (a 350 ms pointerdown, drawn in the page), so the
     * only thing a long press could still reach is the platform's text
     * selection, its handles, and the magnifier Android floats over the press
     * point -- which lands on the app bar title and shows a blown up smear
     * where the title was. Returning null from startActionMode cuts that chain
     * at the source.
     */
    private void buildWebPage() {
        final WebView web = new WebView(this) {
            @Override
            public ActionMode startActionMode(ActionMode.Callback callback) {
                return null;
            }

            @Override
            @SuppressWarnings("NewApi")
            public ActionMode startActionMode(ActionMode.Callback callback, int type) {
                return null;
            }
        };
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.ucl_page_bg));
        web.setLongClickable(false);
        web.setOnLongClickListener(v -> true);
        web.setHapticFeedbackEnabled(false);
        /* never prefill these fields from anything typed on an earlier visit */
        web.clearFormData();
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        /* No LAYER_TYPE_SOFTWARE and no overview mode: the page ships a proper
         * viewport, and a software layer makes the platform redraw its own
         * overlays (magnifier, handles) into garbage on top of the content. */

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String address) {
                if (view != mWeb) return;
                /* the site sets user-select:none on body only; make it the
                 * whole document so no element can start a selection the
                 * platform then has to draw handles for */
                view.evaluateJavascript(NO_SELECT_JS, null);
                onWebPageLoaded();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (view != mWeb || request == null || !request.isForMainFrame()) return;
                failWebPage();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                /* the pages call console.log("native_back_to_prev_activity")
                 * from their app bar back arrow: that is the whole bridge */
                String text = message == null || message.message() == null
                        ? null : message.message().trim();
                if (WEB_BACK_SIGNAL.equals(text)) {
                    closeWebPage();
                    return true;
                }
                return super.onConsoleMessage(message);
            }
        });

        mWeb = web;
        mWebHost.addView(web);
    }

    /**
     * First load only. The page is laid out off the right edge, with a real
     * surface, so it paints while the plate covers it: onPageFinished alone
     * does not mean a frame exists, and sliding a blank WebView in would show
     * exactly that.
     */
    private void beginWebLoad(String url) {
        mWebHost.setVisibility(View.VISIBLE);
        mWebHost.setTranslationX(screenWidth());
        mWeb.loadUrl(url);
        cancelWebTimeout();
        mWebTimeout = this::failWebPage;
        mUi.postDelayed(mWebTimeout, WEB_TIMEOUT_MS);
    }

    /** The document has painted at least once; from here on entry is free. */
    private void onWebPageLoaded() {
        mWebLoaded = true;
        mWebPending = false;
        cancelWebTimeout();
        dismissLoadingPlate();
        if (isFinishing() || isDestroyed() || mPage == PAGE_WEB) return;
        enterWebPage();
    }

    /**
     * Brings the resident page in over the one on screen.
     *
     * <p>It is a page of the same stack as everything else, minus a bar: the
     * site brings its own. Nothing about the page underneath is lost, because
     * it is not rebuilt on the way back -- it is still the same view, still
     * scrolled where it was, still showing the login form it was opened from.
     */
    private void enterWebPage() {
        if (mPage == PAGE_WEB) return;
        mHistory.remove(Integer.valueOf(PAGE_WEB));
        mHistory.add(PAGE_WEB);
        goTo(PAGE_WEB, true);
    }

    /** The page never arrived: drop it quietly and let the next tap retry. */
    private void failWebPage() {
        if (mWebLoaded) return;
        cancelWebTimeout();
        dismissLoadingPlate();
        mWebPending = false;
        discardWebPage();
        if (isFinishing() || isDestroyed()) return;
        UclLegalDialogs.notice(this, R.string.ucl_web_failed_title,
                getString(R.string.ucl_web_failed_body), R.string.ucl_dlg_ok, null);
    }

    /** Back arrow of the page, or the system back key: one step back. */
    private void closeWebPage() {
        if (mPage != PAGE_WEB) return;
        cancelWebTimeout();
        dismissLoadingPlate();
        /* the web page is not destroyed here: it slides out and is parked, so
         * the next entry is a translation instead of a fetch */
        popPage();
    }

    /**
     * Destroys the resident page. For the end of the activity, or for a load
     * that never arrived -- everything else parks it and keeps the document.
     */
    private void discardWebPage() {
        cancelWebTimeout();
        dismissLoadingPlate();
        mWebLoaded = false;
        mWebPending = false;
        if (mWebHost != null) {
            mWebHost.animate().cancel();
            mWebHost.setVisibility(View.GONE);
            mWebHost.setTranslationX(0f);
        }
        if (mWeb != null) {
            WebView web = mWeb;
            mWeb = null;
            if (mWebHost != null) mWebHost.removeView(web);
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.destroy();
        }
    }

    private void dismissLoadingPlate() {
        UclDialog plate = mLoadingPlate;
        mLoadingPlate = null;
        if (plate == null) return;
        if (plate.isShowing() && !isFinishing()) plate.dismiss();
    }

    private void cancelWebTimeout() {
        if (mWebTimeout != null) {
            mUi.removeCallbacks(mWebTimeout);
            mWebTimeout = null;
        }
    }
}

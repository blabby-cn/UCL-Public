package net.kdt.pojavlaunch.mcui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.LocaleUtils;

import java.util.ArrayList;
import java.util.List;

import fr.spse.gamepad_remapper.Remapper;

/**
 * The body of one settings category.
 *
 * <p>Rows are data, so they are built here rather than written out in XML: a
 * page is a section title, a few rows, and a screen full of identical
 * attributes. What the rows do share with the rest of the shell is the shape:
 * a 48 dp row (56 with a second line), a 3 dp groove between rows, a 13 sp
 * section title over its own bright rule, and every control right aligned to
 * the plate's inner padding.
 *
 * <p>Slider bounds are read from the same {@code @integer} resources the old
 * preference screens pointed at, so the two cannot drift apart: a step of 8 MB
 * for memory is not a number this file gets to choose.
 *
 * <p>Values are written straight to the preference store and mirrored onto the
 * {@link LauncherPreferences} field of the same name. The launcher reads those
 * fields, not the store, at launch time, so a setting that only reached the
 * store would survive a restart and do nothing until one.
 */
public final class UclSettingsPage {

    public static final int CAT_VIDEO = 0;
    public static final int CAT_CONTROL = 1;
    public static final int CAT_JAVA = 2;
    public static final int CAT_MISC = 3;
    public static final int CAT_EXPERIMENTAL = 4;
    public static final int CAT_ABOUT = 5;
    /* last on purpose: the language is the one setting nobody looks for, and it
     * sits below the page that says what this build is */
    public static final int CAT_LANGUAGE = 6;
    public static final int CAT_COUNT = 7;

    /** The two upstream projects the About page credits, and links to. */
    private static final String URL_POJAV =
            "https://github.com/PojavLauncherTeam/PojavLauncher";
    private static final String URL_FCL =
            "https://github.com/FCL-Team/FoldCraftLauncher";

    /** Called after a value changes, so the sidebar can restate its summary. */
    public interface Listener {
        void onSettingChanged();
    }

    private final Activity mHost;
    private final LinearLayout mBody;
    private final Listener mListener;
    private int mCategory = CAT_VIDEO;

    public UclSettingsPage(Activity host, LinearLayout body, Listener listener) {
        mHost = host;
        mBody = body;
        mListener = listener;
    }

    /* ===================================================================== */
    /* pages                                                                 */
    /* ===================================================================== */

    public void show(int category) {
        mCategory = category;
        mBody.removeAllViews();
        switch (category) {
            case CAT_CONTROL:
                buildControl();
                break;
            case CAT_JAVA:
                buildJava();
                break;
            case CAT_MISC:
                buildMisc();
                break;
            case CAT_EXPERIMENTAL:
                buildExperimental();
                break;
            case CAT_ABOUT:
                buildAbout();
                break;
            case CAT_LANGUAGE:
                buildLanguage();
                break;
            default:
                buildVideo();
                break;
        }
        UclTypeface.apply(mBody);
    }

    /** Re-runs the page in place: used when a switch reveals or hides rows. */
    private void rebuild() {
        mBody.post(() -> show(mCategory));
    }

    private void buildVideo() {
        section(R.string.ucl_settings_section_display);

        Tools.RenderersList renderers = Tools.getCompatibleRenderers(mHost);
        choiceRow(R.string.mcl_setting_category_renderer, 0,
                renderers.rendererDisplayNames,
                renderers.rendererIds.toArray(new String[0]),
                LauncherPreferences.PREF_RENDERER, "renderer");
        groove();
        switchRow(R.string.mcl_setting_title_ignore_notch,
                R.string.mcl_setting_subtitle_ignore_notch, "ignoreNotch",
                LauncherPreferences.PREF_IGNORE_NOTCH, false);
        groove();
        sliderRow(R.string.mcl_setting_title_resolution_scaler,
                R.string.mcl_setting_subtitle_resolution_scaler, "resolutionRatio",
                intRes(R.integer.resolution_seekbar_min), 100,
                intRes(R.integer.resolution_seekbar_increment), " %",
                Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f));

        section(R.string.ucl_settings_section_performance);
        switchRow(R.string.preference_sustained_performance_title,
                R.string.preference_sustained_performance_description, "sustainedPerformance",
                LauncherPreferences.PREF_SUSTAINED_PERFORMANCE, false);
        groove();
        switchRow(R.string.mcl_setting_title_use_surface_view,
                R.string.mcl_setting_subtitle_use_surface_view, "alternate_surface",
                LauncherPreferences.PREF_USE_ALTERNATE_SURFACE, false);
        groove();
        switchRow(R.string.preference_force_vsync_title,
                R.string.preference_force_vsync_description, "force_vsync",
                LauncherPreferences.PREF_FORCE_VSYNC, false);
        groove();
        switchRow(R.string.preference_vsync_in_zink_title,
                R.string.preference_vsync_in_zink_description, "vsync_in_zink",
                LauncherPreferences.PREF_VSYNC_IN_ZINK, false);
    }

    private void buildControl() {
        actionRow(R.string.preference_edit_controls_title,
                R.string.ucl_settings_edit_controls_sub, R.string.ucl_settings_open,
                () -> mHost.startActivity(new Intent(mHost, CustomControlsActivity.class)));

        section(R.string.preference_category_gestures);
        switchRow(R.string.mcl_disable_gestures, 0, "disableGestures",
                LauncherPreferences.PREF_DISABLE_GESTURES, true);
        groove();
        switchRow(R.string.mcl_disable_swap_hand, 0, "disableDoubleTap",
                LauncherPreferences.PREF_DISABLE_SWAP_HAND, false);
        if (!LauncherPreferences.PREF_DISABLE_GESTURES) {
            groove();
            sliderRow(R.string.mcl_setting_title_longpresstrigger, 0, "timeLongPressTrigger",
                    intRes(R.integer.gesture_delay_seekbar_min),
                    intRes(R.integer.gesture_delay_seekbar_max),
                    intRes(R.integer.gesture_delay_seekbar_increment), " ms",
                    LauncherPreferences.PREF_LONGPRESS_TRIGGER);
        }

        section(R.string.preference_category_buttons);
        sliderRow(R.string.mcl_setting_title_buttonscale, 0, "buttonscale",
                intRes(R.integer.button_scale_seekbar_min),
                intRes(R.integer.button_scale_seekbar_max),
                intRes(R.integer.button_scale_seekbar_increment), " %",
                Math.round(LauncherPreferences.PREF_BUTTONSIZE));
        groove();
        switchRow(R.string.mcl_setting_title_buttonallcaps, 0, "buttonAllCaps",
                LauncherPreferences.PREF_BUTTON_ALL_CAPS, false);

        section(R.string.preference_category_virtual_mouse);
        sliderRow(R.string.mcl_setting_title_mousescale, 0, "mousescale",
                intRes(R.integer.mouse_scale_seekbar_min),
                intRes(R.integer.mouse_scale_seekbar_max),
                intRes(R.integer.mouse_scale_seekbar_increment), " %",
                Math.round(LauncherPreferences.PREF_MOUSESCALE * 100f));
        groove();
        sliderRow(R.string.mcl_setting_title_mousespeed, 0, "mousespeed",
                intRes(R.integer.mouse_speed_seekbar_min),
                intRes(R.integer.mouse_speed_seekbar_max),
                intRes(R.integer.mouse_speed_seekbar_increment), " %",
                Math.round(LauncherPreferences.PREF_MOUSESPEED * 100f));
        groove();
        switchRow(R.string.preference_mouse_start_title, 0, "mouse_start",
                LauncherPreferences.PREF_VIRTUAL_MOUSE_START, false);

        buildGyro();
        buildGamepad();
    }

    /**
     * The gyro block only exists on hardware that has one: a phone without the
     * sensor would show six rows that cannot do anything, and the original
     * screen hid the whole category for the same reason.
     */
    private void buildGyro() {
        if (!Tools.deviceSupportsGyro(mHost)) return;

        section(R.string.preference_category_gyro_controls);
        switchRow(R.string.preference_enable_gyro_title, 0, "enableGyro",
                LauncherPreferences.PREF_ENABLE_GYRO, true);
        if (!LauncherPreferences.PREF_ENABLE_GYRO) return;

        groove();
        sliderRow(R.string.preference_gyro_sensitivity_title,
                R.string.preference_gyro_sensitivity_description, "gyroSensitivity",
                intRes(R.integer.gyro_speed_seekbar_min),
                intRes(R.integer.gyro_speed_seekbar_max),
                intRes(R.integer.gyro_speed_seekbar_increment), " %",
                Math.round(LauncherPreferences.PREF_GYRO_SENSITIVITY * 100f));
        groove();
        sliderRow(R.string.preference_gyro_sample_rate_title,
                R.string.preference_gyro_sample_rate_description, "gyroSampleRate",
                intRes(R.integer.gyro_rate_seekbar_min),
                intRes(R.integer.gyro_rate_seekbar_max), 1, " ms",
                LauncherPreferences.PREF_GYRO_SAMPLE_RATE);
        groove();
        switchRow(R.string.preference_gyro_smoothing_title,
                R.string.preference_gyro_smoothing_description, "gyroSmoothing",
                LauncherPreferences.PREF_GYRO_SMOOTHING, false);
        groove();
        switchRow(R.string.preference_gyro_invert_x_axis,
                R.string.preference_gyro_invert_x_axis_description, "gyroInvertX",
                LauncherPreferences.PREF_GYRO_INVERT_X, false);
        groove();
        switchRow(R.string.preference_gyro_invert_y_axis,
                R.string.preference_gyro_invert_y_axis_description, "gyroInvertY",
                LauncherPreferences.PREF_GYRO_INVERT_Y, false);
    }

    private void buildGamepad() {
        section(R.string.preference_category_controller_settings);
        actionRow(R.string.preference_remap_controller_title,
                R.string.preference_remap_controller_description, 0,
                () -> {
                    if (mHost instanceof UclPageActivity) {
                        ((UclPageActivity) mHost).openGamepadMapper();
                    }
                });
        groove();
        actionRow(R.string.preference_wipe_controller_title,
                R.string.preference_wipe_controller_description, 0, () -> {
                    Remapper.wipePreferences(mHost);
                    Toast.makeText(mHost, R.string.preference_controller_map_wiped,
                            Toast.LENGTH_SHORT).show();
                });
        groove();
        sliderRow(R.string.preference_deadzone_scale_title,
                R.string.preference_deadzone_scale_description, "gamepad_deadzone_scale",
                intRes(R.integer.gamepad_deadzone_seekbar_min),
                intRes(R.integer.gamepad_deadzone_seekbar_max),
                intRes(R.integer.gamepad_deadzone_seekbar_increment), " %",
                Math.round(LauncherPreferences.PREF_DEADZONE_SCALE * 100f));
    }

    private void buildJava() {
        section(R.string.preference_category_java_tweaks);

        /* the runtimes that are actually unpacked, by name; a runtime that is
         * installed but not listed here could not be started, so it is not
         * offered as a choice either */
        List<String> names = new ArrayList<>();
        for (Runtime runtime : installedRuntimes()) names.add(runtime.name);
        choiceRow(R.string.multirt_title, R.string.multirt_subtitle,
                names.toArray(new String[0]), names.toArray(new String[0]),
                LauncherPreferences.PREF_DEFAULT_RUNTIME, "defaultRuntime");
        groove();

        int deviceRam = Tools.getTotalDeviceMemory(mHost);
        int ramMax = (Architecture.is32BitsDevice() || deviceRam < 2048)
                ? Math.min(1024, deviceRam)
                : deviceRam - (deviceRam < 3064 ? 800 : 1024);
        sliderRow(R.string.mcl_memory_allocation, R.string.mcl_memory_allocation_subtitle,
                "allocation", intRes(R.integer.memory_seekbar_min), Math.max(512, ramMax),
                intRes(R.integer.memory_seekbar_increment), " MB",
                LauncherPreferences.PREF_RAM_ALLOCATION);
        groove();
        inputRow(R.string.mcl_setting_title_javaargs, R.string.mcl_setting_subtitle_javaargs,
                "javaArgs", LauncherPreferences.PREF_CUSTOM_JAVA_ARGS, 3);
        groove();
        switchRow(R.string.mcl_setting_java_sandbox, R.string.mcl_setting_java_sandbox_subtitle,
                "java_sandbox", LauncherPreferences.PREF_JAVA_SANDBOX, false);
    }

    private static List<Runtime> installedRuntimes() {
        List<Runtime> out = new ArrayList<>();
        try {
            for (Runtime runtime : MultiRTUtils.getRuntimes()) {
                if (runtime.versionString != null) out.add(runtime);
            }
        } catch (RuntimeException ignored) {
            /* an unreadable runtime folder is reported where it matters: at
             * launch. Here it just means nothing to choose from. */
        }
        return out;
    }

    private void buildMisc() {
        section(R.string.ucl_settings_section_general);
        /* "force English" used to live here. A switch that can only ever say
         * "English" is not a language setting; the language page replaces it,
         * and it can also say Japanese. */
        switchRow(R.string.preference_ask_for_notification_title, 0,
                "notification_permission_request",
                !LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK, false);

        section(R.string.preference_category_miscellaneous);
        switchRow(R.string.mcl_setting_check_libraries,
                R.string.mcl_setting_check_libraries_subtitle, "checkLibraries",
                LauncherPreferences.PREF_CHECK_LIBRARY_SHA, false);
        groove();
        switchRow(R.string.arc_capes_title, 0, "arc_capes",
                LauncherPreferences.PREF_ARC_CAPES, false);
        groove();
        switchRow(R.string.preference_verify_manifest_title, 0, "verifyManifest",
                LauncherPreferences.PREF_VERIFY_MANIFEST, false);
    }

    private void buildExperimental() {
        section(R.string.preference_category_experimental_settings);
        switchRow(R.string.preference_shader_dump_title, 0, "dump_shaders",
                LauncherPreferences.PREF_DUMP_SHADERS, false);
        groove();
        switchRow(R.string.preference_force_big_core_title, 0, "bigCoreAffinity",
                LauncherPreferences.PREF_BIG_CORE_AFFINITY, false);
        groove();
        switchRow(R.string.ucl_settings_zink_system_driver, 0, "zinkPreferSystemDriver",
                LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER, false);
    }

    /**
     * The About page.
     *
     * <p>It used to be three rows of build metadata, which answers a question
     * nobody asked: a user opening this page wants to know what the thing is
     * built on and what it owes to whom. So the page is mostly prose now -
     * where the code comes from, every third-party component with its license,
     * what the artwork is, and the disclaimer - with the version on top
     * because that is the one row a bug report needs.
     *
     * <p>The two project rows open the upstream repositories. They are the
     * only actionable things here, and they are what makes the credit checkable
     * rather than a claim.
     *
     * <p>Every licence named in these strings was read from the upstream
     * licence file or package metadata, including the awkward ones: PojavLauncher
     * itself is the Lesser GPL, the gamepad remapper is LGPL 3.0 while the other
     * input libraries are MIT or Apache, and xHook is MIT with BSD parts. A
     * guessed licence on this page is worse than no page, so do not add one
     * without opening the repository.
     */
    private void buildAbout() {
        section(R.string.ucl_about_section_app);
        infoRow(R.string.ucl_info_version, BuildConfig.VERSION_NAME);
        prose(R.string.ucl_about_intro);

        section(R.string.ucl_about_section_origin);
        prose(R.string.ucl_about_origin_pojav);
        prose(R.string.ucl_about_origin_fcl);
        prose(R.string.ucl_about_origin_art);
        actionRow(R.string.ucl_about_project_pojav, 0, R.string.ucl_settings_open,
                () -> Tools.openURL(mHost, URL_POJAV));
        groove();
        actionRow(R.string.ucl_about_project_fcl, 0, R.string.ucl_settings_open,
                () -> Tools.openURL(mHost, URL_FCL));

        section(R.string.ucl_about_section_components);
        prose(R.string.ucl_about_components_runtime);
        prose(R.string.ucl_about_components_tools);
        prose(R.string.ucl_about_components_ui);
        prose(R.string.ucl_about_components_fonts);

        section(R.string.ucl_about_section_license);
        prose(R.string.ucl_about_license);

        section(R.string.ucl_about_section_disclaimer);
        prose(R.string.ucl_about_disclaimer);
    }

    /**
     * The language page.
     *
     * <p>One row per language, named in that language, with a gem on the left
     * that is green for the one in force and stone for the rest - the marking
     * the reference screen uses, and the reason these two bitmaps are the only
     * smooth art in the launcher.
     *
     * <p>Picking a row writes the tag and rebuilds the activity; that is the
     * whole switch. There is no "apply" key, because a language that needs to
     * be confirmed is a language the user has to read in the old language.
     */
    private void buildLanguage() {
        section(R.string.ucl_settings_section_language, R.string.ucl_settings_language_sub);

        String current = LocaleUtils.currentTag();
        for (int i = 0; i < LocaleUtils.TAGS.length; i++) {
            if (i > 0) groove();
            languageRow(LocaleUtils.TAGS[i], LocaleUtils.TAGS[i].equals(current));
        }
    }

    private void languageRow(final String tag, boolean picked) {
        LinearLayout row = new UclPressRow(mHost);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(12), dp(6), dp(10), dp(6));
        /* Every row keeps the same plate, picked or not.
         *
         * The selected plate draws its own top and bottom rules, and each row
         * is already closed by a groove: together they read as two dividers
         * stacked on one another. The gem is the marker, so the plate has
         * nothing to say. */
        row.setBackgroundResource(R.drawable.ucl_nav_row_bg);
        row.setClickable(true);
        row.setFocusable(true);

        int index = LocaleUtils.indexOf(tag);
        ImageView gem = new ImageView(mHost);
        gem.setImageResource(picked ? R.drawable.ucl_lang_on : R.drawable.ucl_lang_off);
        gem.setScaleType(ImageView.ScaleType.FIT_CENTER);
        /* a polygon, not a pixel grid: this subtree keeps its filtering */
        UclDrawables.markSmooth(gem);

        LinearLayout text = new LinearLayout(mHost);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(mHost);
        name.setText(index >= 0 ? LocaleUtils.NAMES[index] : tag);
        name.setTextColor(color(picked ? R.color.ucl_accent_light : R.color.ucl_text_primary));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        name.setTypeface(UclTypeface.regular(mHost));
        text.addView(name);

        TextView region = new TextView(mHost);
        region.setText(index >= 0 ? LocaleUtils.REGIONS[index] : "");
        region.setTextColor(color(R.color.ucl_text_muted));
        region.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        LinearLayout.LayoutParams regionLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        regionLp.topMargin = dp(2);
        text.addView(region, regionLp);

        LinearLayout.LayoutParams gemLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        gemLp.rightMargin = dp(12);
        row.addView(gem, gemLp);
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> pickLanguage(tag));
        addRow(row);
    }

    private void pickLanguage(String tag) {
        if (tag.equals(LocaleUtils.currentTag())) return;
        LocaleUtils.setTag(tag);
        if (mHost instanceof UclPageActivity) {
            ((UclPageActivity) mHost).reloadForLanguage();
        } else {
            mHost.recreate();
        }
    }

    /* ===================================================================== */
    /* rows                                                                  */
    /* ===================================================================== */

    private void section(int titleRes) {
        section(titleRes, 0);
    }

    /**
     * A section head with the line that explains it.
     *
     * <p>Most sections do not need one, which is why the one-argument form
     * still exists; the language page does, because "language" alone does not
     * say whether it changes Minecraft or the launcher.
     */
    private void section(int titleRes, int subRes) {
        TextView title = new TextView(mHost);
        title.setText(titleRes);
        title.setTextColor(color(R.color.ucl_text_secondary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        /* a heading, not a line of prose: it is one of the places the display
         * face belongs */
        title.setTypeface(UclTypeface.title(mHost));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(2);
        lp.topMargin = mBody.getChildCount() == 0 ? dp(2) : dp(16);
        lp.bottomMargin = subRes == 0 ? dp(5) : dp(2);
        mBody.addView(title, lp);

        /* the explainer sits between the head and its rule, the order the
         * reference screen uses: the rule closes the block, it does not open
         * it */
        if (subRes != 0) {
            TextView sub = new TextView(mHost);
            sub.setText(subRes);
            sub.setTextColor(color(R.color.ucl_text_muted));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            sub.setLineSpacing(dp(1), 1f);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            subLp.leftMargin = dp(2);
            subLp.bottomMargin = dp(5);
            mBody.addView(sub, subLp);
        }

        View rule = new View(mHost);
        rule.setBackgroundResource(R.drawable.ucl_rule_section);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2.67f));
        ruleLp.bottomMargin = dp(4);
        mBody.addView(rule, ruleLp);
    }

    private void groove() {
        View rule = new View(mHost);
        rule.setBackgroundResource(R.drawable.ucl_rule_row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2.67f));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        mBody.addView(rule, lp);
    }

    /** The shared body of every row: a stacked label and its sub line. */
    private LinearLayout newRow(int titleRes, int subRes) {
        LinearLayout row = new UclPressRow(mHost);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(subRes == 0 ? 48 : 56));

        LinearLayout text = new LinearLayout(mHost);
        text.setOrientation(LinearLayout.VERTICAL);
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(mHost);
        title.setText(titleRes);
        title.setTextColor(color(R.color.ucl_text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(UclTypeface.regular(mHost));
        text.addView(title);

        if (subRes != 0) {
            TextView sub = new TextView(mHost);
            sub.setText(subRes);
            sub.setTextColor(color(R.color.ucl_text_muted));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            sub.setLineSpacing(dp(1), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            text.addView(sub, lp);
        }
        return row;
    }

    private void addRow(LinearLayout row) {
        mBody.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /**
     * @param gates true when other rows appear or disappear with this value:
     *              the page is rebuilt so the change is visible immediately
     *              instead of after leaving and coming back
     */
    private void switchRow(int titleRes, int subRes, String key, boolean current, boolean gates) {
        LinearLayout row = newRow(titleRes, subRes);
        UclSwitch view = new UclSwitch(mHost);
        view.setChecked(current);
        view.setOnCheckedChangeListener((v, checked) -> {
            setBool(key, checked);
            changed();
            if (gates) rebuild();
        });
        row.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addRow(row);
    }

    /**
     * A slider row.
     *
     * <p>Bounds arrive as values, resolved by the caller with {@link #intRes}
     * against the same {@code @integer} resources the old preference screens
     * read, so the two cannot drift. They are values and not ids because not
     * every bound is a resource: the memory ceiling is worked out from the
     * device. Mixing the two in one call is how you end up calling
     * {@code getInteger(100)} - which resolves to nothing and throws.
     */
    private void sliderRow(int titleRes, int subRes, String key,
                           int min, int max, int step, String unit, int current) {
        if (max <= min) return;

        LinearLayout row = newRow(titleRes, subRes);
        final int span = Math.max(1, (max - min) / step);

        LinearLayout box = new LinearLayout(mHost);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);

        SeekBar bar = new SeekBar(mHost);
        final int start = clamp((current - min) / step, 0, span);

        /* The drawable goes in before the progress, not after.
         *
         * ProgressBar hands the level - the only thing that says how far along
         * the slider is - to whichever drawable it is holding when the progress
         * changes. Setting progress first sent that level to the stock
         * drawable; ours then sat at its initial 0, so the groove drew with no
         * green at all until the first touch made the bar send a level of its
         * own. Order is the fix; the explicit setLevel below is the belt to
         * that pair of braces.
         */
        UclPixelDrawable grip = new UclPixelDrawable(mHost, R.drawable.ucl_slider_grip);
        UclSliderTrack track = new UclSliderTrack(mHost,
                R.drawable.ucl_slider_groove, R.drawable.ucl_slider_groove_full);
        bar.setProgressDrawable(track);
        bar.setThumb(grip);
        bar.setThumbOffset(0);
        bar.setSplitTrack(false);
        bar.setPadding(0, 0, 0, 0);
        bar.setMax(span);
        bar.setProgress(start);
        track.setLevel(UclSliderTrack.levelOf(start, span));

        TextView value = new TextView(mHost);
        value.setText((min + start * step) + unit);
        value.setTextColor(color(R.color.ucl_text_primary));
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        value.setTypeface(UclTypeface.bold(mHost));
        value.setGravity(Gravity.END);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(dp(62),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        valueLp.leftMargin = dp(8);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText((min + progress * step) + unit);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setInt(key, min + seekBar.getProgress() * step);
                changed();
            }
        });

        /* as tall as the grip, in px: the groove is 9 art pixels and centres
         * itself inside that */
        box.addView(bar, new LinearLayout.LayoutParams(dp(132),
                grip.getIntrinsicHeight()));
        box.addView(value, valueLp);
        row.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addRow(row);
    }

    /**
     * A row whose value is picked from a list. The list opens in the same plate
     * dialog the version picker uses, and the picked value is written by key so
     * the caller does not have to own a setter per preference.
     */
    private void choiceRow(int titleRes, int subRes, CharSequence[] names, String[] values,
                           String current, String key) {
        LinearLayout row = newRow(titleRes, subRes);

        /* a real key, so it behaves like every other key: the plate and the
         * label go down together under the finger instead of only the plate
         * changing colour */
        UclButton face = new UclButton(mHost);
        face.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        face.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        face.setPadding(dp(12), 0, dp(12), 0);
        face.setMinWidth(0);
        face.setMinimumWidth(0);
        face.setMinHeight(0);
        face.setMinimumHeight(0);
        face.setSingleLine(true);
        face.setEllipsize(TextUtils.TruncateAt.END);
        face.setText(nameFor(names, values, current));

        face.setOnClickListener(v -> {
            if (values.length == 0) return;
            /* The highlight comes from the store, read now, not from the value
             * this row was built with. A row is built once and then kept for
             * the life of the page, so the built-in value goes stale the moment
             * the user picks something - which is exactly why the first entry
             * used to stay lit no matter what was chosen. */
            String live = prefs().getString(key, current);
            /* a store that has nothing usable (a runtime that was uninstalled,
             * an id this build no longer ships) falls back to the value the row
             * was built with, so the list always lights exactly one entry */
            boolean known = false;
            for (String value : values) {
                if (value.equals(live)) {
                    known = true;
                    break;
                }
            }
            if (!known) live = current;
            final String highlighted = live;
            final UclDialog[] holder = new UclDialog[1];
            LinearLayout list = new LinearLayout(mHost);
            list.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < values.length; i++) {
                final int index = i;
                final boolean picked = values[i].equals(highlighted);
                TextView item = new TextView(mHost);
                item.setText(names[i]);
                item.setTextColor(color(picked
                        ? R.color.ucl_accent_light : R.color.ucl_text_primary));
                item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(14), dp(12), dp(14), dp(12));
                item.setBackgroundResource(picked
                        ? R.drawable.ucl_nav_row_selected : R.drawable.ucl_nav_row_bg);
                item.setOnClickListener(w -> {
                    face.setText(names[index]);
                    setString(key, values[index]);
                    changed();
                    if (holder[0] != null) holder[0].dismiss();
                });
                list.addView(item);
            }
            holder[0] = UclDialog.build(mHost)
                    .title(mHost.getString(titleRes))
                    .body(list, true)
                    .closeMark(true)
                    .show();
        });

        row.addView(face, new LinearLayout.LayoutParams(dp(168), dp(32)));
        addRow(row);
    }

    /**
     * A titled field: the label row, then the box under it.
     *
     * <p>The label keeps the horizontal row it shares with every other row. It
     * is not re-oriented to vertical: the shared row gives the label a weight
     * to take the space the control leaves, and a weight on a vertical
     * container means height, which would collapse the label to nothing.
     */
    private void inputRow(int titleRes, int subRes, String key, String current, int lines) {
        LinearLayout outer = new LinearLayout(mHost);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(0, dp(6), 0, dp(6));
        outer.addView(newRow(titleRes, subRes), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText field = new EditText(mHost);
        field.setText(current == null ? "" : current);
        field.setTextColor(color(R.color.ucl_text_primary));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        field.setTypeface(UclTypeface.regular(mHost));
        field.setBackgroundResource(R.drawable.ucl_edit_background);
        field.setPadding(dp(10), dp(8), dp(10), dp(8));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setSingleLine(false);
        field.setLines(lines);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                setString(key, field.getText().toString().trim());
                changed();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        outer.addView(field, lp);
        addRow(outer);
    }

    /**
     * A row that opens another screen.
     *
     * @param keyRes label for the key on the right, or 0 for none: a row that
     *               only needs a target (reset the pad) stays a plain row and
     *               does not pretend to have a second state
     */
    private void actionRow(int titleRes, int subRes, int keyRes, Runnable action) {
        LinearLayout row = newRow(titleRes, subRes);
        row.setBackgroundResource(R.drawable.ucl_setting_action);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(dp(12), dp(8), dp(10), dp(8));
        row.setOnClickListener(v -> action.run());

        if (keyRes != 0) {
            UclButton key = new UclButton(mHost);
            key.setText(keyRes);
            key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            key.setGravity(Gravity.CENTER);
            key.setPadding(0, 0, 0, 0);
            key.setMinWidth(0);
            key.setMinimumWidth(0);
            key.setMinHeight(0);
            key.setMinimumHeight(0);
            /* the key is a real key, so it takes the touch: without its own
             * listener the row's listener never sees the tap and the key looks
             * broken - which is exactly how "edit the controls" behaved */
            key.setOnClickListener(v -> action.run());
            key.setFocusable(false);
            row.addView(key, new LinearLayout.LayoutParams(dp(72), dp(32)));
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        mBody.addView(row, lp);
    }

    /**
     * A paragraph of running text.
     *
     * <p>The About page is the one page that has to explain itself, and an
     * explanation is not a row: no plate, no minimum height, no key on the
     * right. It is set one step smaller than a row label and in the secondary
     * colour, because a page of statements at primary brightness reads as a
     * list of settings, which is exactly what this is not.
     */
    private void prose(int textRes) {
        TextView text = new TextView(mHost);
        text.setText(textRes);
        text.setTextColor(color(R.color.ucl_text_secondary));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        /* running text, not a heading: the reading face in every language */
        text.setTypeface(UclTypeface.regular(mHost));
        text.setLineSpacing(dp(2), 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        lp.topMargin = dp(5);
        lp.bottomMargin = dp(3);
        mBody.addView(text, lp);
    }

    private void infoRow(int titleRes, String value) {
        LinearLayout row = newRow(titleRes, 0);
        row.setMinimumHeight(dp(40));
        TextView text = new TextView(mHost);
        text.setText(value);
        text.setTextColor(color(R.color.ucl_text_primary));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        text.setTypeface(UclTypeface.bold(mHost));
        text.setGravity(Gravity.END);
        row.addView(text, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addRow(row);
    }

    /* ===================================================================== */
    /* small helpers                                                         */
    /* ===================================================================== */

    private CharSequence nameFor(CharSequence[] names, String[] values, String current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) return names[i];
        }
        return mHost.getString(R.string.ucl_settings_current_none);
    }

    private void changed() {
        if (mListener != null) mListener.onSettingChanged();
    }

    private int color(int res) {
        return ContextCompat.getColor(mHost, res);
    }

    private int intRes(int res) {
        return mHost.getResources().getInteger(res);
    }

    private int dp(float value) {
        return (int) (value * mHost.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    /* ===================================================================== */
    /* preference writes                                                     */
    /* ===================================================================== */

    private SharedPreferences prefs() {
        return LauncherPreferences.DEFAULT_PREF;
    }

    private void setBool(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
        switch (key) {
            case "ignoreNotch": LauncherPreferences.PREF_IGNORE_NOTCH = value; break;
            case "sustainedPerformance": LauncherPreferences.PREF_SUSTAINED_PERFORMANCE = value; break;
            case "alternate_surface": LauncherPreferences.PREF_USE_ALTERNATE_SURFACE = value; break;
            case "force_vsync": LauncherPreferences.PREF_FORCE_VSYNC = value; break;
            case "vsync_in_zink": LauncherPreferences.PREF_VSYNC_IN_ZINK = value; break;
            case "disableGestures": LauncherPreferences.PREF_DISABLE_GESTURES = value; break;
            case "disableDoubleTap": LauncherPreferences.PREF_DISABLE_SWAP_HAND = value; break;
            case "buttonAllCaps": LauncherPreferences.PREF_BUTTON_ALL_CAPS = value; break;
            case "mouse_start": LauncherPreferences.PREF_VIRTUAL_MOUSE_START = value; break;
            case "enableGyro": LauncherPreferences.PREF_ENABLE_GYRO = value; break;
            case "gyroSmoothing": LauncherPreferences.PREF_GYRO_SMOOTHING = value; break;
            case "gyroInvertX": LauncherPreferences.PREF_GYRO_INVERT_X = value; break;
            case "gyroInvertY": LauncherPreferences.PREF_GYRO_INVERT_Y = value; break;
            case "java_sandbox": LauncherPreferences.PREF_JAVA_SANDBOX = value; break;
            case "checkLibraries": LauncherPreferences.PREF_CHECK_LIBRARY_SHA = value; break;
            case "arc_capes": LauncherPreferences.PREF_ARC_CAPES = value; break;
            case "verifyManifest": LauncherPreferences.PREF_VERIFY_MANIFEST = value; break;
            case "dump_shaders": LauncherPreferences.PREF_DUMP_SHADERS = value; break;
            case "bigCoreAffinity": LauncherPreferences.PREF_BIG_CORE_AFFINITY = value; break;
            case "zinkPreferSystemDriver": LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER = value; break;
            case "notification_permission_request":
                LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK = !value;
                break;
            default: break;
        }
    }

    private void setInt(String key, int value) {
        prefs().edit().putInt(key, value).apply();
        switch (key) {
            case "resolutionRatio": LauncherPreferences.PREF_SCALE_FACTOR = value / 100f; break;
            case "timeLongPressTrigger": LauncherPreferences.PREF_LONGPRESS_TRIGGER = value; break;
            case "buttonscale": LauncherPreferences.PREF_BUTTONSIZE = value; break;
            case "mousescale": LauncherPreferences.PREF_MOUSESCALE = value / 100f; break;
            case "mousespeed": LauncherPreferences.PREF_MOUSESPEED = value / 100f; break;
            case "gyroSensitivity": LauncherPreferences.PREF_GYRO_SENSITIVITY = value / 100f; break;
            case "gyroSampleRate": LauncherPreferences.PREF_GYRO_SAMPLE_RATE = value; break;
            case "gamepad_deadzone_scale":
                LauncherPreferences.PREF_DEADZONE_SCALE = value / 100f;
                break;
            case "allocation": LauncherPreferences.PREF_RAM_ALLOCATION = value; break;
            default: break;
        }
    }

    private void setString(String key, String value) {
        prefs().edit().putString(key, value).apply();
        switch (key) {
            case "renderer": LauncherPreferences.PREF_RENDERER = value; break;
            case "javaArgs": LauncherPreferences.PREF_CUSTOM_JAVA_ARGS = value; break;
            case "defaultRuntime": LauncherPreferences.PREF_DEFAULT_RUNTIME = value; break;
            default: break;
        }
    }
}

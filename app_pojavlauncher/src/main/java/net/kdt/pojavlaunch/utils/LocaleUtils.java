package net.kdt.pojavlaunch.utils;


import static net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF;

import android.content.*;
import android.content.res.*;
import android.os.Build;
import android.os.LocaleList;

import androidx.preference.*;

import java.util.*;

/**
 * The one place that decides which language the launcher runs in.
 *
 * <p>It used to answer a smaller question: a switch called "force English"
 * either did nothing or forced English, and a Chinese phone could not be told
 * to run the launcher in Japanese at all. That switch is gone. What is left is
 * a real choice among the languages the launcher ships, stored as a language
 * tag, with "no tag" meaning "follow the system".
 *
 * <p>Switching is immediate rather than on the next start because every
 * activity is built through {@link #setLocale(Context)}: the picker writes the
 * tag and asks the running activity to rebuild itself, and the rebuilt context
 * resolves its resources in the new language. Nothing is restarted, nothing is
 * downloaded, and a session that was on the language page comes back to it.
 *
 * <p>The list is fixed on purpose. Offering every locale the app has
 * translations for would mean offering languages whose launcher chrome has
 * never been written in that language; these are the ones we intend to keep
 * translated.
 */
public class LocaleUtils extends ContextWrapper {

    /** The languages the picker offers, in the order it lists them. */
    public static final String[] TAGS = {"zh-CN", "zh-TW", "ja", "ko", "en", "fr", "de"};
    /**
     * A language is named in its own language.
     *
     * <p>This is the one place in the launcher that must not be translated:
     * someone who cannot read the current language still has to be able to find
     * theirs in the list.
     */
    public static final String[] NAMES = {
            "简体中文", "繁體中文", "日本語", "한국어", "English", "Français", "Deutsch"
    };
    /** The second line of each row: where the language is spoken. */
    public static final String[] REGIONS = {
            "中国", "中國台灣", "日本", "대한민국", "United States", "France", "Deutschland"
    };

    /** Where the picked tag lives. Shared with everything else in the app. */
    public static final String KEY_LANGUAGE = "ucl_language";

    public LocaleUtils(Context base) {
        super(base);
    }

    /** The tag the user picked, or "" meaning "follow the system". */
    public static String currentTag() {
        if (DEFAULT_PREF == null) return "";
        String tag = DEFAULT_PREF.getString(KEY_LANGUAGE, "");
        return tag == null ? "" : tag;
    }

    /** Writes the pick. The caller is the one that rebuilds the screen. */
    public static void setTag(String tag) {
        if (DEFAULT_PREF == null) return;
        DEFAULT_PREF.edit().putString(KEY_LANGUAGE, tag == null ? "" : tag).apply();
    }

    /** The locale the tag names, or null when the app should follow the device. */
    public static Locale selectedLocale() {
        String tag = currentTag();
        if (tag.isEmpty()) return null;
        Locale locale = Locale.forLanguageTag(tag);
        return locale.getLanguage().isEmpty() ? null : locale;
    }

    /** The native name of a tag, for the settings summary and the picker. */
    public static String displayName(String tag) {
        for (int i = 0; i < TAGS.length; i++) {
            if (TAGS[i].equals(tag)) return NAMES[i];
        }
        return "";
    }

    /**
     * What the launcher is speaking right now.
     *
     * <p>With no tag stored the answer is the device's own language, named in
     * itself - not in the current UI language, which would be circular.
     */
    public static String currentName() {
        String tag = currentTag();
        if (!tag.isEmpty()) {
            String name = displayName(tag);
            if (!name.isEmpty()) return name;
        }
        Locale system = Locale.getDefault();
        return system.getDisplayName(system);
    }

    /**
     * Whether the launcher is being read in English right now.
     *
     * <p>A tag comparison, not a look at the letters on screen: a German page
     * is full of Latin characters and is still German. The display face the
     * top bar and the settings tabs use exists for English only, so this is the
     * one question that decides whether it may appear at all.
     */
    public static boolean isEnglish() {
        String tag = currentTag();
        if (!tag.isEmpty()) {
            return "en".equals(Locale.forLanguageTag(tag).getLanguage());
        }
        return "en".equals(Locale.getDefault().getLanguage());
    }

    /** Index of a tag in {@link #TAGS}, or -1 for "follow the system". */
    public static int indexOf(String tag) {
        for (int i = 0; i < TAGS.length; i++) {
            if (TAGS[i].equals(tag)) return i;
        }
        return -1;
    }

    public static ContextWrapper setLocale(Context context) {
        if (DEFAULT_PREF == null) {
            DEFAULT_PREF = PreferenceManager.getDefaultSharedPreferences(context);
        }

        Locale locale = selectedLocale();
        /* no pick: the device decides, and nothing here overrides it */
        if (locale == null) return new LocaleUtils(context);

        Resources resources = context.getResources();
        Configuration configuration = new Configuration(resources.getConfiguration());

        configuration.setLocale(locale);
        Locale.setDefault(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList localeList = new LocaleList(locale);
            LocaleList.setDefault(localeList);
            configuration.setLocales(localeList);
        }

        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            context = context.createConfigurationContext(configuration);
        }

        return new LocaleUtils(context);
    }
}

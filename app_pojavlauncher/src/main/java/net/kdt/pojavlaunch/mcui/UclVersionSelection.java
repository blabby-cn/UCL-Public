package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.content.SharedPreferences;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The one version this launcher starts.
 *
 * <p>The version page picks it and the home screen shows it; the play key reads
 * it and nothing else. Keeping it here rather than in the launcher profile
 * means the pick survives a profile rewrite, and {@link #resolve(Context)}
 * makes a deleted version fall back instead of leaving the play key pointing at
 * a folder that is no longer there.
 */
public final class UclVersionSelection {
    private static final String PREFS = "ucl_version_selection";
    private static final String KEY_SELECTED = "selected";

    private UclVersionSelection() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Every version folder that carries its json, newest first.
     *
     * <p>The comparison is by number, not by string: plain sorting puts 1.20.1
     * before 1.8.9, which reads as a bug in a list this short.
     */
    public static List<String> installed() {
        List<String> versions = new ArrayList<>();
        File[] dirs = new File(Tools.DIR_HOME_VERSION).listFiles();
        if (dirs != null) {
            for (File dir : dirs) {
                if (dir.isDirectory() && new File(dir, dir.getName() + ".json").isFile()) {
                    versions.add(dir.getName());
                }
            }
        }
        versions.sort((left, right) -> -compareNumbers(left, right));
        return versions;
    }

    /** Compares two version names segment by segment, falling back to text. */
    private static int compareNumbers(String left, String right) {
        String[] a = left.split("[._-]");
        String[] b = right.split("[._-]");
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            String sa = i < a.length ? a[i] : "0";
            String sb = i < b.length ? b[i] : "0";
            Integer na = parseInt(sa);
            Integer nb = parseInt(sb);
            if (na != null && nb != null) {
                if (!na.equals(nb)) return Integer.compare(na, nb);
            } else {
                int text = sa.compareToIgnoreCase(sb);
                if (text != 0) return text;
            }
        }
        return left.compareTo(right);
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The stored pick, without checking that it still exists. */
    public static String peek(Context context) {
        return prefs(context).getString(KEY_SELECTED, null);
    }

    public static void set(Context context, String versionId) {
        if (versionId == null) {
            prefs(context).edit().remove(KEY_SELECTED).apply();
        } else {
            prefs(context).edit().putString(KEY_SELECTED, versionId).apply();
        }
    }

    /**
     * The version to start, or null when nothing is installed.
     *
     * <p>A stored pick that no longer exists is replaced by the newest installed
     * version, so the corner of the home screen never names something that was
     * deleted.
     */
    public static String resolve(Context context) {
        List<String> installed = installed();
        String stored = peek(context);
        if (stored != null && installed.contains(stored)) return stored;
        if (installed.isEmpty()) {
            if (stored != null) set(context, null);
            return null;
        }
        String fallback = installed.get(0);
        set(context, fallback);
        return fallback;
    }
}

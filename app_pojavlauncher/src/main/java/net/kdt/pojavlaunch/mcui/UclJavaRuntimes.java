package net.kdt.pojavlaunch.mcui;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The Java runtimes this launcher is allowed to fetch.
 *
 * <p>These are the Android builds of OpenJDK, the same ones PojavLauncher runs
 * on, not desktop JDKs: same build scripts (Pojav's
 * android-openjdk-build-multiarch), same tar.xz layout, same {@code release}
 * file inside. A desktop build would unpack fine and then fail to start,
 * because the native libraries and the memory layout are not the ones this
 * process has.
 *
 * <p>The launcher ships none of them. That is deliberate: an APK carrying four
 * runtimes is four times the download for a phone that needs one, and the
 * licence notice that comes with redistributing a JDK. Fetching them here keeps
 * the package small and the runtime the user's own copy.
 */
public final class UclJavaRuntimes {

    /**
     * One downloadable runtime: a major version, the architecture it is built
     * for, and where the archive lives.
     */
    public static final class Build {
        public final int major;
        public final String arch;
        public final String url;
        /** displayed before the download starts and as the progress total */
        public final long bytes;

        Build(int major, String arch, String url, long bytes) {
            this.major = major;
            this.arch = arch;
            this.url = url;
            this.bytes = bytes;
        }

        /** "Java 21" -- the name the queue row, the window and the folder use. */
        public String displayName() {
            return UclJavaRuntimes.displayName(major);
        }

        public String fileName() {
            return "jre" + major + "-android-" + arch + ".tar.xz";
        }
    }

    /** Newest first: the version most game builds ask for is the one on top. */
    private static final int[] MAJORS = {21, 8, 17, 25};

    /**
     * Sizes as published with each release, in bytes. Only used to show a size
     * before the transfer starts and to give the bar a total while the server
     * has not answered yet; the real total comes from Content-Length.
     */
    private static final long MB = 1024L * 1024L;

    /* GitHub release assets. One tag per major version; the file name is
     * jre<major>-android-<arch>.tar.xz. Kept as a template so adding a version
     * is one line in MAJORS plus its size. */
    private static final String BASE =
            "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/";

    private UclJavaRuntimes() {
    }

    public static String runtimeName(int major) {
        return "Java-" + major;
    }

    public static String displayName(int major) {
        return "Java " + major;
    }

    /** arm64, arm, x86_64 or x86 -- the suffix every asset carries. */
    public static String deviceArch() {
        return Architecture.archAsString(Tools.DEVICE_ARCHITECTURE);
    }

    /** What this device can install, in display order. */
    public static List<Build> builds() {
        List<Build> builds = new ArrayList<>();
        String arch = deviceArch();
        for (int major : MAJORS) {
            long bytes = sizeOf(major, arch);
            /* a version with no build for this architecture is not offered at
             * all: a key that downloads nothing is worse than a missing row */
            if (bytes <= 0L) continue;
            builds.add(new Build(major, arch, urlOf(major, arch), bytes));
        }
        return builds;
    }

    public static Build buildFor(int major) {
        String arch = deviceArch();
        long bytes = sizeOf(major, arch);
        if (bytes <= 0L) return null;
        return new Build(major, arch, urlOf(major, arch), bytes);
    }

    private static String urlOf(int major, String arch) {
        return BASE + "download_jre" + major + "/jre" + major + "-android-" + arch + ".tar.xz";
    }

    /**
     * Published size per version and architecture; zero where the build does
     * not exist (there is no 32-bit x86 build of 25).
     */
    private static long sizeOf(int major, String arch) {
        switch (major) {
            case 8:
                switch (arch) {
                    case "arm": return 26 * MB;
                    case "arm64": return 27 * MB;
                    case "x86": return 26 * MB;
                    case "x86_64": return 28 * MB;
                    default: return 0L;
                }
            case 17:
                switch (arch) {
                    case "arm": return 24 * MB;
                    case "arm64": return 26 * MB;
                    case "x86": return 26 * MB;
                    case "x86_64": return 27 * MB;
                    default: return 0L;
                }
            case 21:
                switch (arch) {
                    case "arm": return 25 * MB;
                    case "arm64": return 27 * MB;
                    case "x86": return 27 * MB;
                    case "x86_64": return 28 * MB;
                    default: return 0L;
                }
            case 25:
                switch (arch) {
                    case "arm": return 27 * MB;
                    case "arm64": return 36 * MB;
                    case "x86_64": return 37 * MB;
                    default: return 0L;
                }
            default:
                return 0L;
        }
    }

    /**
     * Whether a usable runtime for this major version is already unpacked.
     *
     * <p>Presence of the folder is not enough: an interrupted unpack leaves one
     * behind, and offering "installed" for a runtime that cannot start would
     * turn a download problem into a mysterious launch failure. The release
     * file is what the rest of the launcher reads to learn the Java version.
     */
    public static boolean isInstalled(int major) {
        File home = new File(Tools.MULTIRT_HOME, runtimeName(major));
        return new File(home, "release").isFile();
    }

    /** The folder an installed runtime lives in. */
    public static File home(int major) {
        return new File(Tools.MULTIRT_HOME, runtimeName(major));
    }

    /** "已安装" state for the settings panel, empty when nothing is installed. */
    public static int installedCount() {
        int count = 0;
        for (int major : MAJORS) {
            if (isInstalled(major)) count++;
        }
        return count;
    }

    /**
     * Makes this runtime the default one when there is no usable default.
     *
     * <p>A freshly installed runtime that nothing points at is a download the
     * user cannot spend: the launch screen would still report that no Java is
     * available. Only an empty or broken selection is replaced -- a default the
     * user picked deliberately is theirs to keep.
     */
    public static void adoptAsDefaultIfNeeded(int major) {
        String current = LauncherPreferences.PREF_DEFAULT_RUNTIME;
        boolean usable = current != null && !current.isEmpty()
                && MultiRTUtils.forceReread(current).versionString != null;
        if (usable) return;
        String name = runtimeName(major);
        LauncherPreferences.PREF_DEFAULT_RUNTIME = name;
        LauncherPreferences.DEFAULT_PREF.edit().putString("defaultRuntime", name).apply();
    }
}

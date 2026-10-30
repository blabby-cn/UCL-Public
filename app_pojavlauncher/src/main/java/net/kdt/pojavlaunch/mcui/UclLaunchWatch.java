package net.kdt.pojavlaunch.mcui;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.IOException;

/**
 * Leaves a trace of every trip into the JVM.
 *
 * <p>A launch can die without the Java side ever seeing the result: the process
 * is terminated from native code, so no dialog, no toast and no return value
 * reach the launcher. What the user sees is the launcher going back to its home
 * screen. This class makes that case detectable on the next attempt: the marker
 * is written right before the JVM starts and removed as soon as control returns
 * (or once the game has been running long enough to count as started), so a
 * marker that is still there means the previous trip ended inside the JVM.
 */
public final class UclLaunchWatch {
    private static final String MARKER_NAME = "ucl_launch_in_progress";
    private static final String RESULT_NAME = "ucl_launch_result";
    /** A run that survives this long started fine; anything after it is a normal exit. */
    private static final long STABLE_RUN_MS = 90_000L;
    private static final int LOG_TAIL_CHARS = 4000;

    private UclLaunchWatch() {}

    private static File marker() {
        return new File(Tools.DIR_GAME_HOME, MARKER_NAME);
    }

    private static File result() {
        return new File(Tools.DIR_GAME_HOME, RESULT_NAME);
    }

    /** Called right before control leaves for JLI_Launch. */
    public static void markStarted() {
        final String stamp = Long.toString(System.currentTimeMillis());
        try {
            Tools.write(marker().getAbsolutePath(), stamp);
        } catch (IOException ignored) {
            return;
        }
        Thread settle = new Thread(() -> {
            try {
                Thread.sleep(STABLE_RUN_MS);
            } catch (InterruptedException e) {
                return;
            }
            /* Only clear our own marker: a later attempt may have replaced it. */
            try {
                if (marker().isFile() && stamp.equals(Tools.read(marker().getAbsolutePath()).trim()))
                    marker().delete();
            } catch (IOException ignored) {
            }
        });
        settle.setDaemon(true);
        settle.start();
    }

    /** Called when the JVM hands control back, however it decided to end. */
    public static void markReturned(int exitCode, long elapsedMs) {
        try {
            Tools.write(result().getAbsolutePath(), exitCode + " " + elapsedMs);
        } catch (IOException ignored) {
        }
        marker().delete();
    }

    /**
     * Describes the previous launch if it never came back, then forgets it so
     * the report is shown once.
     *
     * @return the report, or null when the previous launch ended normally
     */
    public static String takeUnfinishedReport(android.content.Context context) {
        File marker = marker();
        if (!marker.isFile()) return null;
        marker.delete();
        String detail = logTail();
        /* The report is read by the person who was trying to play, so it is
         * said in their language; the log itself stays verbatim. */
        if (detail == null) {
            return context.getString(R.string.ucl_launch_unfinished_body);
        }
        return context.getString(R.string.ucl_launch_unfinished_body_log, detail);
    }

    private static String logTail() {
        File log = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        if (!log.isFile()) return null;
        try {
            String content = Tools.read(log.getAbsolutePath());
            if (content.length() <= LOG_TAIL_CHARS) return content;
            return content.substring(content.length() - LOG_TAIL_CHARS);
        } catch (IOException e) {
            return null;
        }
    }
}

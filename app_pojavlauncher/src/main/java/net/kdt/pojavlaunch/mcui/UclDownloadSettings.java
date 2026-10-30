package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The three numbers the download engine obeys.
 *
 * <p>Kept in one place so the settings window, the download manager and the
 * engine all read the same value: a limit that the window shows but the engine
 * ignores is worse than no setting at all.
 *
 * <p>Speed is stored in megabytes per second, zero meaning no limit. Threads
 * are the number of files fetched at the same time, which is what the engine's
 * connection pool is sized by.
 */
public final class UclDownloadSettings {

    private static final String FILE = "ucl_download";
    private static final String KEY_TASKS = "parallel_tasks";
    private static final String KEY_SPEED = "speed_limit_mbps";
    private static final String KEY_THREADS = "threads";

    /** Choices offered by the window, in the order they are shown. */
    public static final int[] TASK_CHOICES = {1, 2, 3, 4};
    public static final int[] SPEED_CHOICES = {0, 5, 10, 20};
    public static final int[] THREAD_CHOICES = {3, 16, 32, 64};

    public static final int DEFAULT_TASKS = 1;
    public static final int DEFAULT_SPEED = 0;
    public static final int DEFAULT_THREADS = 16;

    private UclDownloadSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int tasks(Context context) {
        return prefs(context).getInt(KEY_TASKS, DEFAULT_TASKS);
    }

    public static void setTasks(Context context, int value) {
        prefs(context).edit().putInt(KEY_TASKS, clamp(value, TASK_CHOICES)).apply();
    }

    /** Megabytes per second, zero for no limit. */
    public static int speedMbps(Context context) {
        return prefs(context).getInt(KEY_SPEED, DEFAULT_SPEED);
    }

    public static void setSpeedMbps(Context context, int value) {
        prefs(context).edit().putInt(KEY_SPEED, clamp(value, SPEED_CHOICES)).apply();
    }

    public static int threads(Context context) {
        return prefs(context).getInt(KEY_THREADS, DEFAULT_THREADS);
    }

    public static void setThreads(Context context, int value) {
        prefs(context).edit().putInt(KEY_THREADS, clamp(value, THREAD_CHOICES)).apply();
    }

    /** Bytes per second, zero when the user does not want a cap. */
    public static long speedBytesPerSecond(Context context) {
        int mbps = speedMbps(context);
        return mbps <= 0 ? 0L : mbps * 1024L * 1024L;
    }

    /** Keeps a stored value inside the set the window can show. */
    private static int clamp(int value, int[] allowed) {
        for (int candidate : allowed) {
            if (candidate == value) return value;
        }
        return allowed[allowed.length / 2];
    }
}

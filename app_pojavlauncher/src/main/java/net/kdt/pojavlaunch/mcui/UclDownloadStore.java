package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The download queue, kept across process death.
 *
 * <p>A launcher download is long and the phone is not patient: swiping the app
 * out of the recents list kills the process, and until this existed the queue
 * died with it. The bytes already written stay on disk and the downloader
 * re-checks every file against its sha1, so a second run only fetches what is
 * still missing -- but the user had no job to press, so that knowledge was
 * unusable.
 *
 * <p>One record per line, tab separated, which is enough for six fields and
 * avoids pulling a JSON library into a queue this small. Text that could carry
 * a tab or a newline is flattened first, so a failure message cannot corrupt
 * the record after it.
 */
final class UclDownloadStore {

    private static final String PREFS = "ucl_download_queue";
    private static final String KEY_QUEUE = "tasks";
    /** A queue longer than this is history, not work in progress. */
    private static final int MAX_RECORDS = 20;

    private UclDownloadStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Writes only the jobs that are still outstanding.
     *
     * <p>Finished, cancelled and failed entries are session history: they exist
     * so the user can see what happened while the app is open, and the queue
     * row offers no way to remove them. Persisting those would mean a queue
     * that fills up with rows nobody can clear, and the one thing a restart
     * actually needs is the work that is still unfinished.
     */
    static void save(Context context, List<UclDownloadManager.DownloadTask> tasks) {
        if (context == null || tasks == null) return;
        StringBuilder out = new StringBuilder();
        int written = 0;
        for (UclDownloadManager.DownloadTask task : tasks) {
            if (task.state != UclDownloadManager.STATE_DOWNLOADING
                    && task.state != UclDownloadManager.STATE_INTERRUPTED) {
                continue;
            }
            if (written >= MAX_RECORDS) break;
            written++;
            out.append(flatten(task.versionId)).append('\t')
                    .append(task.state).append('\t')
                    .append(task.progress).append('\t')
                    .append(task.startedAt).append('\t')
                    .append(task.purgedFiles).append('\t')
                    .append(flatten(task.progressText)).append('\t')
                    /* two fields appended for runtime jobs; a queue written by
                     * an older build simply has six and still reads back */
                    .append(task.javaRuntime ? 1 : 0).append('\t')
                    .append(task.javaMajor).append('\n');
        }
        prefs(context).edit().putString(KEY_QUEUE, out.toString()).apply();
    }

    static List<UclDownloadManager.DownloadTask> load(Context context) {
        List<UclDownloadManager.DownloadTask> tasks = new ArrayList<>();
        if (context == null) return tasks;
        String raw = prefs(context).getString(KEY_QUEUE, "");
        if (raw == null || raw.isEmpty()) return tasks;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 6 || parts[0].isEmpty()) continue;
            int javaMajor = parts.length >= 8 ? parseInt(parts[7], 0) : 0;
            UclDownloadManager.DownloadTask task =
                    new UclDownloadManager.DownloadTask(parts[0], javaMajor);
            task.state = parseInt(parts[1], UclDownloadManager.STATE_FAILED);
            task.progress = parseInt(parts[2], 0);
            long startedAt = parseLong(parts[3], 0L);
            if (startedAt > 0L) task.startedAt = startedAt;
            task.purgedFiles = parseInt(parts[4], 0);
            task.progressText = parts[5].isEmpty() ? null : parts[5];
            tasks.add(task);
        }
        return tasks;
    }

    /** Drops the stored queue. Called when the user clears finished entries. */
    static void clear(Context context) {
        if (context == null) return;
        prefs(context).edit().remove(KEY_QUEUE).apply();
    }

    private static String flatten(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

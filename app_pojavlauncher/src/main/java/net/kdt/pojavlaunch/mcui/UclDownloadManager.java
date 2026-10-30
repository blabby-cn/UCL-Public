package net.kdt.pojavlaunch.mcui;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks Minecraft version downloads started from the UCL download page.
 * Backend work is delegated to {@link MinecraftDownloader}; this class only
 * keeps a UI-friendly task list with states and progress.
 */
public class UclDownloadManager {
    public static final int STATE_DOWNLOADING = 0;
    public static final int STATE_DONE = 1;
    public static final int STATE_FAILED = 2;
    public static final int STATE_CANCELLED = 3;
    /**
     * The process died while this job was running.
     *
     * <p>Kept apart from "failed" on purpose: nothing went wrong with the
     * network, the app was simply killed, and the files already written are
     * still good. A row in this state offers "resume", not "retry".
     */
    public static final int STATE_INTERRUPTED = 4;

    public interface TaskUpdateListener {
        void onTasksUpdated();
    }

    public static class DownloadTask {
        public final String versionId;
        /** true for a Java runtime job: same queue, different payload */
        public final boolean javaRuntime;
        /** Java major version for a runtime job, zero otherwise */
        public final int javaMajor;
        /** when this run began; the cleanup uses it as its cutoff */
        public volatile long startedAt = System.currentTimeMillis();
        public volatile int state = STATE_DOWNLOADING;
        public volatile int progress;
        public volatile String progressText;
        /** stage of the job, e.g. "downloading game files" */
        public volatile String stageText;
        /** file currently on the wire, relative to the game folder */
        public volatile String currentFile;
        /** how many files are being fetched at the same time */
        public volatile int activeFileCount;
        public volatile double speedBytesPerSecond;
        /** set once the user asks to stop; the failure that follows is expected */
        public volatile boolean cancelRequested;
        /** how many half-written files the cleanup removed */
        public volatile int purgedFiles;
        /** cleanup is idempotent: cancel and the worker both land here */
        volatile boolean purgeStarted;

        DownloadTask(String versionId) {
            this(versionId, 0);
        }

        DownloadTask(String versionId, int javaMajor) {
            this.versionId = versionId;
            this.javaMajor = javaMajor;
            this.javaRuntime = javaMajor > 0;
        }
    }

    private static volatile UclDownloadManager sInstance;

    private final List<DownloadTask> mTasks = new ArrayList<>();
    private final List<TaskUpdateListener> mListeners = new ArrayList<>();
    private Context mAppContext;
    private volatile MinecraftDownloader mDownloader;
    /** the runtime job currently on the wire, so cancel can close its socket */
    private volatile UclJavaInstaller mJavaInstaller;
    /** whether the saved queue has been read back yet */
    private boolean mRestored;
    private volatile long mLastSave;
    private final ProgressListener mProgressListener = new ProgressListener() {
        @Override
        public void onProgressStarted() {
        }

        @Override
        public void onProgressUpdated(int progress, int resid, Object... va) {
            DownloadTask active = getActiveTask();
            if (active == null || mAppContext == null) return;
            active.progress = progress;
            active.progressText = mAppContext.getString(resid, va);
            notifyListeners();
        }

        @Override
        public void onProgressEnded() {
        }
    };

    public static UclDownloadManager getInstance() {
        if (sInstance == null) {
            synchronized (UclDownloadManager.class) {
                if (sInstance == null) sInstance = new UclDownloadManager();
            }
        }
        return sInstance;
    }

    private UclDownloadManager() {
    }

    public List<DownloadTask> getTasks() {
        return mTasks;
    }

    /**
     * Binds the manager to a context and reads the saved queue back, once.
     *
     * <p>A job the process did not outlive comes back as
     * {@link #STATE_INTERRUPTED}: nothing failed, the app was killed, and the
     * bytes already written are still on disk. Reporting it as failed would be
     * wrong and would throw away the one thing worth keeping, which is the
     * knowledge that this version is half here.
     */
    public void attach(@NonNull Context context) {
        if (mAppContext == null) mAppContext = context.getApplicationContext();
        if (mRestored || mAppContext == null) return;
        mRestored = true;
        mTasks.addAll(UclDownloadStore.load(mAppContext));
        for (DownloadTask task : mTasks) {
            if (task.state != STATE_DOWNLOADING) continue;
            task.state = STATE_INTERRUPTED;
            task.speedBytesPerSecond = 0d;
            task.currentFile = null;
            task.progressText = mAppContext.getString(
                    R.string.ucl_task_interrupted, task.progress);
        }
        if (!mTasks.isEmpty()) notifyListeners();
        persist(true);
    }

    /**
     * The job for this version that is either running or waiting to resume.
     *
     * <p>Two jobs for one version write the same folder, so the second one is
     * never allowed to start: whichever file finished last would be overwritten
     * by a half-written copy from the other.
     */
    @Nullable
    public DownloadTask findUnfinished(@NonNull String versionId) {
        for (DownloadTask task : mTasks) {
            if (!task.versionId.equals(versionId)) continue;
            if (task.state == STATE_DOWNLOADING || task.state == STATE_INTERRUPTED) return task;
        }
        return null;
    }

    public boolean hasUnfinished(@NonNull String versionId) {
        return findUnfinished(versionId) != null;
    }

    @Nullable
    public DownloadTask getActiveTask() {
        for (DownloadTask task : mTasks) {
            if (task.state == STATE_DOWNLOADING) return task;
        }
        return null;
    }

    public void addListener(@NonNull TaskUpdateListener listener) {
        if (!mListeners.contains(listener)) mListeners.add(listener);
    }

    public void removeListener(@NonNull TaskUpdateListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Whether the queue is full.
     *
     * <p>This used to mean "any job is running", which capped the launcher at
     * one download no matter what the settings said. The cap now comes from the
     * settings window, so the same call answers "may another job start".
     */
    public boolean hasActiveTask() {
        int limit = mAppContext == null ? 1 : UclDownloadSettings.tasks(mAppContext);
        int running = 0;
        for (DownloadTask task : mTasks) {
            if (task.state == STATE_DOWNLOADING) running++;
        }
        return running >= limit;
    }

    public boolean isDownloaded(String versionId) {
        for (DownloadTask task : mTasks) {
            if (task.versionId.equals(versionId) && task.state == STATE_DONE) return true;
        }
        return false;
    }

    /**
     * Proves the game folder really is writable before a job starts.
     *
     * <p>On API 29+ this is the app's own external files directory, so no
     * runtime permission is involved -- but the volume can still be unmounted,
     * full, or the directory missing, and then every download fails with a
     * generic message that points at the network. A one-file write probe turns
     * that into a clear, actionable error.
     */
    private boolean ensureStorageWritable(Activity activity, DownloadTask task) {
        String reason = null;
        java.io.File root = new java.io.File(Tools.DIR_GAME_HOME);
        if (!Tools.checkStorageRoot(activity)) {
            reason = activity.getString(R.string.ucl_storage_unavailable);
        } else if (!root.exists() && !root.mkdirs()) {
            reason = activity.getString(R.string.ucl_storage_unavailable);
        } else {
            java.io.File probe = new java.io.File(root, ".ucl_write_probe");
            try {
                if (probe.exists() || probe.createNewFile()) {
                    if (!probe.delete()) reason = activity.getString(R.string.ucl_storage_unwritable);
                } else {
                    reason = activity.getString(R.string.ucl_storage_unwritable);
                }
            } catch (Exception e) {
                reason = activity.getString(R.string.ucl_storage_unwritable);
            }
        }
        if (reason == null) return true;
        task.state = STATE_FAILED;
        task.progressText = reason;
        notifyListeners();
        Tools.showErrorRemote(reason, new java.io.IOException(reason));
        return false;
    }

    /**
     * Start a version download. When it finishes, the ContextAwareDoneListener
     * behavior is applied: launch the game and kill this process.
     */
    public void startDownload(@NonNull Activity activity, @NonNull String normalizedVersionId) {
        attach(activity);
        /* the page refuses this before it gets here, with an explanation. This
         * is the floor: two jobs on one version write one folder, and no caller
         * is allowed to arrange that by accident. */
        if (hasUnfinished(normalizedVersionId)) return;
        DownloadTask task = new DownloadTask(normalizedVersionId);
        mTasks.add(0, task);
        run(activity, task);
    }

    /**
     * Start a Java runtime download.
     *
     * <p>It shares the queue with game versions on purpose: one row per job,
     * one place where "how many may run" is enforced, and one window that
     * knows how to stop a transfer. Two jobs for the same runtime unpack into
     * the same folder, so the second one is refused exactly like a duplicate
     * version.
     */
    public void startJavaDownload(@NonNull Activity activity, int major) {
        attach(activity);
        UclJavaRuntimes.Build build = UclJavaRuntimes.buildFor(major);
        /* nothing to fetch for an architecture this version was never built
         * for; the page does not offer the row, and this is the floor */
        if (build == null) return;
        String id = UclJavaRuntimes.displayName(major);
        if (hasUnfinished(id)) return;
        DownloadTask task = new DownloadTask(id, major);
        mTasks.add(0, task);
        runJava(activity, task);
    }

    /**
     * Picks a job back up where the previous run left it.
     *
     * <p>The bytes are still on disk and every file is checked against its sha1
     * before it is fetched, so restarting the same version only pulls what is
     * still missing. What this restores is the handle: the row, the percentage
     * and the cancel key.
     *
     * <p>{@code startedAt} is reset, which is deliberate. It is the cutoff the
     * cleanup uses to decide what this run wrote, so leaving it at the original
     * value would make a cancel delete files that were fully verified before the
     * interruption.
     */
    public void resumeDownload(@NonNull Activity activity, @NonNull DownloadTask task) {
        attach(activity);
        if (task.state == STATE_DOWNLOADING) return;
        task.cancelRequested = false;
        task.purgeStarted = false;
        task.purgedFiles = 0;
        task.progressText = null;
        task.speedBytesPerSecond = 0d;
        task.currentFile = null;
        task.startedAt = System.currentTimeMillis();
        task.state = STATE_DOWNLOADING;
        if (task.javaRuntime) runJava(activity, task);
        else run(activity, task);
    }

    /** The worker itself, shared by a fresh start and a resume. */
    private void run(@NonNull Activity activity, @NonNull final DownloadTask task) {
        mAppContext = activity.getApplicationContext();
        notifyListeners();
        persist(true);

        final String normalizedVersionId = task.versionId;

        /* Every byte ends up under the game folder, so check it before the
         * network work starts: a storage problem dressed up as a download
         * failure is indistinguishable from a broken mirror. */
        if (!ensureStorageWritable(activity, task)) return;

        /* No configured source means there is nowhere to fetch from. Fail the
         * job with that specific reason instead of letting a generic network
         * error surface halfway through the task list. */
        if (!DownloadMirror.hasSource()) {
            task.state = STATE_FAILED;
            task.progressText = activity.getString(R.string.ucl_mirror_required);
            notifyListeners();
            persist(true);
            Tools.showErrorRemote(activity.getString(R.string.ucl_mirror_required),
                    new DownloadMirror.NoSourceException());
            return;
        }
        ProgressKeeper.addListener(ProgressLayout.DOWNLOAD_MINECRAFT, mProgressListener);

        /* the connection pool is sized by the settings window; a limit that
         * only the window knows about would be a lie */
        MinecraftDownloader.setConcurrentFiles(UclDownloadSettings.threads(activity));
        net.kdt.pojavlaunch.utils.UclThrottle.setLimit(
                UclDownloadSettings.speedBytesPerSecond(activity));
        final MinecraftDownloader downloader = new MinecraftDownloader();
        mDownloader = downloader;
        /* The window refreshes itself from this: current file, speed, stage.
         * Thirty reports a second would be a full adapter rebind each time, so
         * the observer throttles itself and only pushes when the percentage
         * moves or half a second has gone by. */
        downloader.setProgressObserver(new MinecraftDownloader.ProgressObserver() {
            private long mLastNotify;
            private int mLastPercent = -1;

            @Override
            public void onDownloadProgress(@Nullable String stage, int percent,
                                           double bytesPerSecond, @Nullable String currentFile,
                                           int activeFileCount) {
                /* this downloader belongs to exactly one task. Reading "the"
                 * active one instead was invisible while only a single job
                 * could run, and wrong as soon as the settings allow more: two
                 * jobs would write their numbers into whichever row came
                 * first. */
                if (task.state != STATE_DOWNLOADING) return;
                task.progress = percent;
                task.speedBytesPerSecond = bytesPerSecond;
                task.currentFile = currentFile;
                task.activeFileCount = activeFileCount;
                if (stage != null) task.stageText = stage;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - mLastNotify < 400L && percent == mLastPercent) return;
                mLastNotify = now;
                mLastPercent = percent;
                notifyListeners();
            }
        });

        downloader.start(
                activity,
                AsyncMinecraftDownloader.getListedVersion(normalizedVersionId),
                normalizedVersionId,
                new AsyncMinecraftDownloader.DoneListener() {
                    @Override
                    public void onDownloadDone() {
                        ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_MINECRAFT, mProgressListener);
                        /* only drop the reference if it is still ours: a new
                         * job may have replaced it while this one was dying */
                        if (mDownloader == downloader) mDownloader = null;
                        if (task.cancelRequested) {
                            /* the abort landed just as the last file finished */
                            markCancelled(task);
                            purgePartial(task);
                            return;
                        }
                        task.state = STATE_DONE;
                        task.progress = 100;
                        task.currentFile = null;
                        task.speedBytesPerSecond = 0;
                        task.progressText = null;
                        notifyListeners();
                        persist(true);
                        new ContextAwareDoneListener(activity, normalizedVersionId).onDownloadDone();
                    }

                    @Override
                    public void onDownloadFailed(Throwable throwable) {
                        ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_MINECRAFT, mProgressListener);
                        /* only drop the reference if it is still ours: a new
                         * job may have replaced it while this one was dying */
                        if (mDownloader == downloader) mDownloader = null;
                        if (task.cancelRequested) {
                            /* cancelling by definition throws; that is not a
                             * failure to report, it is the requested outcome */
                            markCancelled(task);
                            purgePartial(task);
                            return;
                        }
                        task.state = STATE_FAILED;
                        /* Keep the reason on the task row instead of throwing
                         * the user into a separate error screen: the row can be
                         * tapped for the full text, and the download page stays
                         * where it is. */
                        task.progressText = describe(throwable);
                        task.speedBytesPerSecond = 0;
                        notifyListeners();
                        persist(true);
                    }
                });
    }

    /**
     * The worker for a runtime job.
     *
     * <p>It runs on its own thread because {@link UclJavaInstaller} blocks:
     * first it downloads an archive, then it unpacks one with tens of
     * thousands of entries. Both steps report through the same task fields the
     * game downloader writes, so the queue row, the window and the details
     * page need no knowledge of which kind of job they are showing.
     */
    private void runJava(@NonNull Activity activity, @NonNull final DownloadTask task) {
        mAppContext = activity.getApplicationContext();
        notifyListeners();
        persist(true);

        final UclJavaRuntimes.Build build = UclJavaRuntimes.buildFor(task.javaMajor);
        if (build == null) {
            task.state = STATE_FAILED;
            task.progressText = activity.getString(R.string.ucl_java_unsupported);
            notifyListeners();
            persist(true);
            return;
        }

        /* the same limit the settings window set for game downloads: a byte is
         * a byte, and a cap that some transfers ignore is not a cap */
        net.kdt.pojavlaunch.utils.UclThrottle.setLimit(
                UclDownloadSettings.speedBytesPerSecond(activity));

        final UclJavaInstaller installer = new UclJavaInstaller(build);
        mJavaInstaller = installer;

        Thread worker = new Thread(() -> {
            Exception failure = null;
            try {
                installer.install((stage, percent, done, total, bytesPerSecond) -> {
                    if (task.state != STATE_DOWNLOADING) return;
                    task.progress = percent;
                    task.speedBytesPerSecond = bytesPerSecond;
                    task.currentFile = build.fileName();
                    task.stageText = stageText(activity, stage);
                    /* one archive, one connection: saying so is more honest
                     * than borrowing the parallel-file wording */
                    task.activeFileCount = 1;
                    notifyListeners();
                });
            } catch (Exception e) {
                failure = e;
            }
            finishJava(task, installer, failure);
        }, "ucl-java-download");
        worker.start();
    }

    /** Turns whatever the installer returned into a state the row understands. */
    private void finishJava(@NonNull DownloadTask task, @NonNull UclJavaInstaller installer,
                            @Nullable Exception failure) {
        if (mJavaInstaller == installer) mJavaInstaller = null;
        task.stageText = null;
        if (failure == null) {
            task.state = STATE_DONE;
            task.progress = 100;
            task.currentFile = null;
            task.speedBytesPerSecond = 0d;
            task.progressText = null;
            /* nothing else points at a runtime that just arrived: without this
             * the download finishes and the launch screen still says there is
             * no Java */
            UclJavaRuntimes.adoptAsDefaultIfNeeded(task.javaMajor);
            notifyListeners();
            persist(true);
            return;
        }
        /* a cancellation interrupts a read, and that IOException is the
         * requested outcome, not a failure worth reporting */
        if (task.cancelRequested || installer.isCancelled()) {
            markCancelled(task);
            return;
        }
        task.state = STATE_FAILED;
        task.progressText = describe(failure);
        task.speedBytesPerSecond = 0d;
        notifyListeners();
        persist(true);
    }

    private static String stageText(Activity activity, UclJavaInstaller.Stage stage) {
        switch (stage) {
            case CONNECTING:
                return activity.getString(R.string.ucl_java_stage_connecting);
            case UNPACKING:
                return activity.getString(R.string.ucl_java_stage_unpacking);
            default:
                return activity.getString(R.string.ucl_java_stage_downloading);
        }
    }

    private void markCancelled(DownloadTask task) {
        task.state = STATE_CANCELLED;
        task.speedBytesPerSecond = 0;
        task.currentFile = null;
        task.progressText = null;
        notifyListeners();
        persist(true);
    }

    /**
     * Stops the job now and throws away what this run half-wrote.
     *
     * <p>The abort is immediate: the downloader interrupts its workers, so the
     * socket in flight dies instead of the cancel waiting for the current file.
     * The cleanup then runs in the background -- it walks the library and asset
     * trees to find what this run touched, and that must not block the UI.
     *
     * <p>"What this run touched" is decided by modification time, not by
     * deleting whole folders: a version that was already complete on disk
     * keeps its files, because those carry an old timestamp. Only bytes written
     * since the task started are removed.
     */
    public void cancelDownload(@NonNull DownloadTask task) {
        if (task.state != STATE_DOWNLOADING) return;
        task.cancelRequested = true;
        if (task.javaRuntime) {
            UclJavaInstaller installer = mJavaInstaller;
            if (installer != null) installer.cancel();
            markCancelled(task);
            /* the partial archive is exactly what a resume needs, and this job
             * wrote nothing else. The cleanup below walks the game folders by
             * modification time, so running it here could delete files another
             * job is writing at this very moment. */
            return;
        }
        MinecraftDownloader downloader = mDownloader;
        if (downloader != null) downloader.cancel();
        /* flip the state immediately so the button stops being live even if the
         * worker takes a moment to notice */
        markCancelled(task);
        purgePartial(task);
    }

    private void purgePartial(@NonNull final DownloadTask task) {
        synchronized (task) {
            if (task.purgeStarted) return;
            task.purgeStarted = true;
        }
        final long startedAt = task.startedAt - 2000L;
        final String versionId = task.versionId;
        Thread purge = new Thread(() -> {
            int removed = 0;
            try {
                removed += deleteRecent(new File(Tools.DIR_HOME_VERSION, versionId), startedAt);
                removed += deleteRecent(new File(Tools.DIR_HOME_LIBRARY), startedAt);
                removed += deleteRecent(new File(Tools.ASSETS_PATH), startedAt);
            } catch (Exception ignored) {
            }
            task.purgedFiles = removed;
            notifyListeners();
        }, "ucl-download-purge");
        purge.setPriority(Thread.MIN_PRIORITY);
        purge.start();
    }

    /** Deletes every file below {@code root} whose mtime is at or after the cutoff. */
    private static int deleteRecent(File root, long cutoff) {
        if (root == null) return 0;
        File[] children = root.listFiles();
        if (children == null) return 0;
        int removed = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                removed += deleteRecent(child, cutoff);
                /* a directory created by this run has just been emptied */
                File[] left = child.listFiles();
                if (left != null && left.length == 0) child.delete();
            } else if (child.lastModified() >= cutoff) {
                if (child.delete()) removed++;
            }
        }
        return removed;
    }

    /** Flattens the cause chain into one line worth showing in a task row. */
    private static String describe(Throwable throwable) {
        if (throwable == null) return "";
        StringBuilder out = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 5) {
            String message = current.getMessage();
            if (message != null && !message.isEmpty() && out.indexOf(message) < 0) {
                if (out.length() > 0) out.append(" / ");
                out.append(message);
            }
            current = current.getCause();
            depth++;
        }
        return out.length() == 0 ? throwable.getClass().getSimpleName() : out.toString();
    }

    /**
     * Writes the queue out. Throttled for progress, forced for state changes:
     * a percentage every four hundred milliseconds would rewrite the file
     * dozens of times a minute for nothing, while a job that starts or dies
     * must be on disk before the next frame.
     */
    private void persist(boolean force) {
        if (mAppContext == null || !mRestored) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - mLastSave < 2000L) return;
        mLastSave = now;
        UclDownloadStore.save(mAppContext, mTasks);
    }

    private void notifyListeners() {
        persist(false);
        Tools.runOnUiThread(() -> {
            for (TaskUpdateListener listener : new ArrayList<>(mListeners)) {
                listener.onTasksUpdated();
            }
        });
    }
}

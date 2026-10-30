package net.kdt.pojavlaunch.mcui;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.utils.UclThrottle;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fetches one Java runtime and unpacks it into the runtime folder.
 *
 * <p>Two steps, one row in the queue: the archive is downloaded to a partial
 * file, then handed to the same unpacker the launcher uses for its own
 * runtimes. Doing the unpack here instead of at install time is what lets an
 * interrupted download carry on: the partial file stays, and the next attempt
 * asks the server for the rest of it with a Range header.
 *
 * <p>The download obeys the same speed setting as game downloads, through the
 * same shared byte budget -- one archive is a single connection, but a limit
 * that only applies to some transfers is not a limit.
 */
public final class UclJavaInstaller {

    /** What the job is doing right now, for the progress window. */
    public enum Stage {
        CONNECTING,
        DOWNLOADING,
        UNPACKING
    }

    public interface Listener {
        void onProgress(Stage stage, int percent, long done, long total,
                        double bytesPerSecond);
    }

    private static final int BUFFER = 16 * 1024;

    private final UclJavaRuntimes.Build mBuild;
    private volatile boolean mCancelled;
    private volatile HttpURLConnection mConnection;

    public UclJavaInstaller(UclJavaRuntimes.Build build) {
        mBuild = build;
    }

    /** Asks the job to stop. The socket is closed so a stalled transfer ends now. */
    public void cancel() {
        mCancelled = true;
        HttpURLConnection connection = mConnection;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isCancelled() {
        return mCancelled;
    }

    /**
     * Runs the whole job on the calling thread.
     *
     * <p>Returns normally only when the runtime is unpacked and readable by the
     * rest of the launcher. A cancellation is reported as an
     * {@link IOException}, because that is what interrupts a stream read.
     */
    public void install(Listener listener) throws Exception {
        /* kept outside the runtime folder on purpose: every child of that
         * folder is listed as an installed runtime, and a half-written archive
         * showing up there as a broken Java would be worse than the download
         * failing outright */
        File cache = new File(Tools.DIR_DATA, "java_cache");
        if (!cache.exists() && !cache.mkdirs()) {
            throw new IOException("Cannot create " + cache);
        }
        File partial = new File(cache, mBuild.fileName() + ".part");

        long total = download(partial, listener);
        if (mCancelled) throw new IOException("cancelled");
        if (total > 0L && partial.length() < total) {
            throw new IOException("Incomplete download: " + partial.length() + "/" + total);
        }

        listener.onProgress(Stage.UNPACKING, 100, partial.length(), partial.length(), 0d);
        MultiRTUtils.installFromArchive(new FileInputStream(partial),
                UclJavaRuntimes.runtimeName(mBuild.major));
        /* only after a successful unpack: a failed one keeps the archive so a
         * retry does not pay for the same bytes twice */
        //noinspection ResultOfMethodCallIgnored
        partial.delete();
    }

    /**
     * Copies the archive to disk, resuming whatever a previous attempt left.
     *
     * @return the total size of the archive, or zero when the server did not
     *         say -- in which case the caller accepts what it got
     */
    private long download(File partial, Listener listener) throws IOException {
        long offset = partial.isFile() ? partial.length() : 0L;
        listener.onProgress(Stage.CONNECTING, 0, offset, mBuild.bytes, 0d);

        HttpURLConnection connection = (HttpURLConnection) new URL(mBuild.url).openConnection();
        mConnection = connection;
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent",
                "UCL/" + net.kdt.pojavlaunch.BuildConfig.VERSION_NAME);
        if (offset > 0L) connection.setRequestProperty("Range", "bytes=" + offset + "-");

        int code = connection.getResponseCode();
        if (code == 416) {
            /* the file on disk is already the whole thing */
            return partial.length();
        }
        if (code != HttpURLConnection.HTTP_OK && code != 206) {
            throw new IOException("HTTP " + code + " for " + mBuild.url);
        }
        /* 200 means the range was ignored, so the body starts at zero and the
         * partial file has to start there too */
        boolean append = code == 206 && offset > 0L;
        if (!append) offset = 0L;

        /* getContentLengthLong() is API 24; this launcher still runs on 21, and
         * these archives are tens of megabytes, so the int variant is exact */
        long length = connection.getContentLength();
        long total = length > 0L ? offset + length : mBuild.bytes;
        if (total <= 0L) total = mBuild.bytes;

        long done = offset;
        long windowStart = System.currentTimeMillis();
        long windowBytes = 0L;
        double speed = 0d;

        try (InputStream in = connection.getInputStream();
             BufferedOutputStream out = new BufferedOutputStream(
                     new FileOutputStream(partial, append), BUFFER)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (mCancelled) throw new IOException("cancelled");
                out.write(buffer, 0, read);
                done += read;
                windowBytes += read;
                UclThrottle.consume(read);

                long now = System.currentTimeMillis();
                if (now - windowStart >= 400L) {
                    speed = windowBytes * 1000d / (now - windowStart);
                    windowStart = now;
                    windowBytes = 0L;
                    int percent = total > 0L ? (int) (done * 100L / total) : 0;
                    listener.onProgress(Stage.DOWNLOADING,
                            Math.min(100, Math.max(0, percent)), done, total, speed);
                }
            }
            out.flush();
        } finally {
            mConnection = null;
            connection.disconnect();
        }

        listener.onProgress(Stage.DOWNLOADING, 100, done, total, speed);
        return total;
    }
}

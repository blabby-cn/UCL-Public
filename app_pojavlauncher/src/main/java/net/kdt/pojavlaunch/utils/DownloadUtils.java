package net.kdt.pojavlaunch.utils;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.concurrent.Callable;

import net.kdt.pojavlaunch.*;
import org.apache.commons.io.*;

@SuppressWarnings("IOStreamConstructor")
public class DownloadUtils {
    public static final String USER_AGENT = Tools.APP_NAME;
    /* Ten seconds is the upstream value and it is too tight for mobile
     * networks: a mirror that answers through a proxy routinely needs longer
     * just for the TLS handshake, and a single timeout used to abort the whole
     * version download. */
    private static final int TIME_OUT = 30000;

    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        InputStream is = null;
        try {
            // System.out.println("Connecting: " + url.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(TIME_OUT);
            conn.setReadTimeout(TIME_OUT);
            conn.setDoInput(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + conn.getResponseCode()
                        + ": " + conn.getResponseMessage());
            }
            is = conn.getInputStream();
            IOUtils.copy(is, os);
        } catch (IOException e) {
            throw new IOException("Unable to download from " + url, e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static String downloadString(String url) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        download(url, bos);
        bos.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void downloadFile(String url, File out) throws IOException {
        FileUtils.ensureParentDirectory(out);
        try (FileOutputStream fileOutputStream = new FileOutputStream(out)) {
            download(url, fileOutputStream);
        }
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        FileUtils.ensureParentDirectory(outputFile);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlInput).openConnection();
        conn.setConnectTimeout(TIME_OUT);
        conn.setReadTimeout(TIME_OUT);
        InputStream readStr = conn.getInputStream();
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            int current;
            int overall = 0;
            int length = conn.getContentLength();

            if (buffer == null) buffer = new byte[65535];

            while ((current = readStr.read(buffer)) != -1) {
                overall += current;
                fos.write(buffer, 0, current);
                UclThrottle.consume(current);
                monitor.updateProgress(overall, length);
            }
            conn.disconnect();
        } catch (IOException e) {
            throw new IOException("Unable to download from " + urlInput, e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* multi-connection ranged download                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Files at or above this size are fetched over several Range connections.
     *
     * <p>Numbers taken from PCL CE, which is the launcher whose speed this is
     * modelled on: its small-file cutoff is 4 MB, its segment size 8 MB, its
     * per-file connection limit 8 and its global connection limit 64. Those
     * limits make sense on a desktop; a phone has one radio, so the same shape
     * is used at a smaller size -- four connections per large file, at most
     * twelve segment sockets in total, on top of the sixteen files the pool
     * already keeps in flight.
     */
    public static final long RANGE_MIN_SIZE = 8L * 1024 * 1024;
    private static final long RANGE_SEGMENT_SIZE = 4L * 1024 * 1024;
    private static final int RANGE_MAX_SEGMENTS = 4;
    private static final int RANGE_MAX_RETRIES = 3;
    private static final java.util.concurrent.Semaphore RANGE_BUDGET =
            new java.util.concurrent.Semaphore(12);

    private static final ThreadLocal<byte[]> sRangeBuffer = new ThreadLocal<>();

    /** Thrown when the source answers a Range request with the whole file. */
    public static class RangeNotSupportedException extends IOException {
        public RangeNotSupportedException(String message) {
            super(message);
        }
    }

    private static byte[] rangeBuffer() {
        byte[] buffer = sRangeBuffer.get();
        if (buffer == null) {
            buffer = new byte[32768];
            sRangeBuffer.set(buffer);
        }
        return buffer;
    }

    /**
     * Fetches one large file over several HTTP Range connections, all writing
     * into their own region of the same file.
     *
     * <p>A single socket on a mobile link stalls at whatever the cell can give
     * one TCP stream; several streams together are what turns 300 KB/s into
     * megabytes. Each segment resumes from where it stopped, so a dropped
     * connection costs the bytes of one segment, not the file.
     *
     * @return false when the source does not honour Range, in which case the
     *         caller has to download the file sequentially
     */
    public static boolean downloadFileRanged(String url, File out, long expectedSize,
                                             Tools.DownloaderFeedback monitor)
            throws IOException, InterruptedException {
        if (expectedSize < RANGE_MIN_SIZE) return false;
        FileUtils.ensureParentDirectory(out);

        int segmentCount = (int) Math.min(RANGE_MAX_SEGMENTS,
                (expectedSize + RANGE_SEGMENT_SIZE - 1) / RANGE_SEGMENT_SIZE);
        long perSegment = (expectedSize + segmentCount - 1) / segmentCount;
        if (perSegment <= 0) return false;

        /* the file is written at four offsets at once, so its full length has
         * to exist before the first write lands past the end */
        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            raf.setLength(expectedSize);
        }

        RangeJob[] jobs = new RangeJob[segmentCount];
        Thread[] workers = new Thread[segmentCount];
        for (int i = 0; i < segmentCount; i++) {
            long start = i * perSegment;
            long end = Math.min(expectedSize - 1, start + perSegment - 1);
            if (start > end) break;
            jobs[i] = new RangeJob(url, out, start, end, expectedSize, monitor);
            workers[i] = new Thread(jobs[i], "ucl-range-" + start);
            workers[i].start();
        }

        try {
            for (Thread worker : workers) {
                if (worker != null) worker.join();
            }
        } catch (InterruptedException e) {
            /* the caller was cancelled: pull the sockets out from under the
             * workers, otherwise they keep writing after the caller has
             * already deleted the file */
            for (RangeJob job : jobs) {
                if (job != null) job.abort();
            }
            for (Thread worker : workers) {
                if (worker == null) continue;
                try {
                    worker.join(1000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            throw e;
        }

        long written = 0;
        IOException failure = null;
        boolean rangeRejected = false;
        for (RangeJob job : jobs) {
            if (job == null) continue;
            written += job.mWritten.get();
            if (failure == null && job.mFailure != null) failure = job.mFailure;
            if (job.mRangeRejected) rangeRejected = true;
        }
        if (rangeRejected) throw new RangeNotSupportedException(url);
        if (failure != null) throw failure;
        if (written != expectedSize) {
            throw new IOException("Ranged download incomplete: wrote " + written
                    + " of " + expectedSize + " bytes (" + url + ")");
        }
        return true;
    }

    private static final class RangeJob implements Runnable {
        private final String mUrl;
        private final File mFile;
        private final long mStart;
        private final long mEnd;
        private final long mTotal;
        private final Tools.DownloaderFeedback mMonitor;
        private volatile IOException mFailure;
        private volatile boolean mRangeRejected;
        private final java.util.concurrent.atomic.AtomicLong mWritten =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.List<HttpURLConnection> mConnections =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>(1));
        private volatile boolean mAborted;

        RangeJob(String url, File file, long start, long end, long total,
                 Tools.DownloaderFeedback monitor) {
            mUrl = url;
            mFile = file;
            mStart = start;
            mEnd = end;
            mTotal = total;
            mMonitor = monitor;
        }

        void abort() {
            mAborted = true;
            synchronized (mConnections) {
                for (HttpURLConnection connection : mConnections) {
                    try {
                        connection.disconnect();
                    } catch (Exception ignored) {
                    }
                }
                mConnections.clear();
            }
        }

        @Override
        public void run() {
            long offset = mStart;
            int attempt = 0;
            while (offset <= mEnd && !mAborted) {
                attempt++;
                try {
                    fetch(offset);
                    return;
                } catch (RangeNotSupportedException e) {
                    mRangeRejected = true;
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    mFailure = new IOException("Ranged download interrupted", e);
                    return;
                } catch (IOException e) {
                    if (mAborted) return;
                    offset = mStart + mWritten.get();
                    if (attempt >= RANGE_MAX_RETRIES) {
                        mFailure = e;
                        return;
                    }
                    try {
                        Thread.sleep(300L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        mFailure = new IOException("Ranged download interrupted", ie);
                        return;
                    }
                }
            }
        }

        private void fetch(long offset) throws IOException, InterruptedException {
            /* blocking, not timed: permits are always released in the finally
             * below, so waiting only serialises segments when several large
             * files are in flight -- whereas a timeout would fail them */
            RANGE_BUDGET.acquire();
            HttpURLConnection connection = null;
            try {
                if (mAborted) throw new IOException("aborted");
                connection = (HttpURLConnection) new URL(mUrl).openConnection();
                connection.setRequestProperty("User-Agent", USER_AGENT);
                /* without this a proxy may hand back a gzip stream whose
                 * offsets no longer line up with the file on disk */
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Range", "bytes=" + offset + "-" + mEnd);
                connection.setConnectTimeout(TIME_OUT);
                connection.setReadTimeout(TIME_OUT);
                mConnections.add(connection);

                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    /* the whole file came back: the source ignores Range */
                    throw new RangeNotSupportedException(mUrl);
                }
                if (code != 206) {
                    throw new IOException("HTTP " + code + " for range " + offset
                            + "-" + mEnd + " of " + mUrl);
                }

                InputStream input = connection.getInputStream();
                try (RandomAccessFile raf = new RandomAccessFile(mFile, "rw")) {
                    raf.seek(offset);
                    byte[] buffer = rangeBuffer();
                    int read;
                    while (offset <= mEnd) {
                        int want = (int) Math.min(buffer.length, mEnd - offset + 1);
                        read = input.read(buffer, 0, want);
                        if (read == -1) break;
                        raf.write(buffer, 0, read);
                        UclThrottle.consume(read);
                        offset += read;
                        long totalWritten = mWritten.addAndGet(read);
                        if (mMonitor != null) {
                            /* four threads report into one counter; the
                             * monitor keeps a single last-value, so the calls
                             * have to be serialised to stay monotonic */
                            synchronized (mMonitor) {
                                mMonitor.updateProgress(
                                        (int) Math.min(Integer.MAX_VALUE, totalWritten),
                                        (int) Math.min(Integer.MAX_VALUE, mTotal));
                            }
                        }
                        if (mAborted) throw new IOException("aborted");
                    }
                } finally {
                    try {
                        input.close();
                    } catch (Exception ignored) {
                    }
                }
                if (offset <= mEnd) {
                    throw new IOException("Connection closed at " + offset
                            + ", expected up to " + mEnd);
                }
            } finally {
                if (connection != null) {
                    mConnections.remove(connection);
                    /* closes the socket right away instead of waiting for the
                     * keep-alive pool to be collected */
                    try {
                        connection.disconnect();
                    } catch (Exception ignored) {
                    }
                }
                RANGE_BUDGET.release();
            }
        }
    }

    public static <T> T downloadStringCached(String url, String cacheName, ParseCallback<T> parseCallback) throws IOException, ParseException{
        File cacheDestination = new File(Tools.DIR_CACHE, "string_cache/"+cacheName);
        if(cacheDestination.isFile() &&
                cacheDestination.canRead() &&
                System.currentTimeMillis() < (cacheDestination.lastModified() + 86400000)) {
            try {
                String cachedString = Tools.read(new FileInputStream(cacheDestination));
                return parseCallback.process(cachedString);
            }catch(IOException e) {
                Log.i("DownloadUtils", "Failed to read the cached file", e);
            }catch (ParseException e) {
                Log.i("DownloadUtils", "Failed to parse the cached file", e);
            }
        }
        String urlContent = DownloadUtils.downloadString(url);
        // if we download the file and fail parsing it, we will yeet outta there
        // and not cache the unparseable sting. We will return this after trying to save the downloaded
        // string into cache
        T parseResult = parseCallback.process(urlContent);

        boolean tryWriteCache;
        if(cacheDestination.exists()) {
            tryWriteCache = cacheDestination.canWrite();
        } else {
            tryWriteCache = FileUtils.ensureParentDirectorySilently(cacheDestination);
        }

        if(tryWriteCache) try {
            Tools.write(cacheDestination.getAbsolutePath(), urlContent);
        }catch(IOException e) {
            Log.i("DownloadUtils", "Failed to cache the string", e);
        }
        return parseResult;
    }

    private static <T> T downloadFile(Callable<T> downloadFunction) throws IOException{
        try {
            return downloadFunction.call();
        } catch (IOException e){
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.exists() && Tools.compareSHA1(file, sha1);
    }
    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction) throws IOException {
        // Skip if needed
        if(sha1 == null) {
            // If the file exists and we don't know it's SHA1, don't try to redownload it.
            if(outputFile.exists()) return null;
            else return downloadFile(downloadFunction);
        }

        int attempts = 0;
        boolean fileOkay = verifyFile(outputFile, sha1);
        T result = null;
        while (attempts < 5 && !fileOkay){
            attempts++;
            downloadFile(downloadFunction);
            fileOkay = verifyFile(outputFile, sha1);
        }
        if(!fileOkay) throw new SHA1VerificationException("SHA1 verifcation failed after 5 download attempts");
        return result;
    }

    /**
     * Get the content length for a given URL.
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     * @throws IOException if an I/O error occurs.
     */
    public static long getContentLength(String url) throws IOException {
        HttpURLConnection urlConnection = (HttpURLConnection) new URL(url).openConnection();
        urlConnection.setRequestMethod("HEAD");
        urlConnection.setDoInput(false);
        urlConnection.setDoOutput(false);
        urlConnection.connect();
        int responseCode = urlConnection.getResponseCode();
        if(responseCode >= 200 && responseCode <= 299) return urlConnection.getContentLength();
        return -1;
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }
    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }

    public static class SHA1VerificationException extends IOException {
        public SHA1VerificationException(String message) {
            super(message);
        }
    }
}


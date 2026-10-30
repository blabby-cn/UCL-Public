package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JAssetInfo;
import net.kdt.pojavlaunch.JAssets;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.mirrors.MirrorTamperedException;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftClientInfo;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MinecraftDownloader {
    private static final double ONE_MEGABYTE = (1024d * 1024d);
    /* attempts per candidate URL before this file is declared unavailable */
    private static final int MAX_ATTEMPTS_PER_URL = 3;
    /* whole-list passes; later passes only fetch what is still missing */
    private static final int MAX_DOWNLOAD_ROUNDS = 3;
    /* Files fetched at the same time.
     *
     * One version is mostly a storm of small files -- several thousand assets
     * and libraries -- and a single socket on a phone link crawls at a few
     * hundred KB/s no matter how good the mirror is. PCL CE keeps 64
     * connections in the air; three was the reason this launcher sat at
     * 300 KB/s. Sixteen is the phone-sized version of the same idea: enough to
     * fill the radio, not so many that a weak CDN starts dropping sockets. */
    private static volatile int sConcurrentFiles = 16;

    /**
     * How many files may be on the wire at once.
     *
     * <p>Sixteen is the default for the reason above; the settings window can
     * ask for fewer, or for more when a mirror is known to cope. The pool is
     * created per download, so a change here applies to the next job rather
     * than to one already running.
     */
    public static void setConcurrentFiles(int files) {
        sConcurrentFiles = Math.max(1, Math.min(64, files));
    }
    public static final String MINECRAFT_RES = "https://resources.download.minecraft.net/";
    private static final String MAVEN_CENTRAL_REPO1 = "https://repo1.maven.org/maven2/";
    private AtomicReference<Exception> mDownloaderThreadException;
    private ArrayList<DownloaderTask> mScheduledDownloadTasks;
    private ArrayList<File> mDeclaredNatives;
    private AtomicLong mProcessedFileCounter;
    private AtomicLong mProcessedSizeCounter; // Total bytes of processed files (passed SHA1 or downloaded)
    private AtomicLong mInternetUsageCounter; // How many bytes downloaded over Internet
    private long mTotalFileCount;
    private long mTotalSize;
    private File mSourceJarFile; // The source client JAR picked during the inheritance process
    private File mTargetJarFile; // The destination client JAR to which the source will be copied to.
    private boolean mUseFileCounter; // Whether a file counter or a size counter should be used for progress

    private static final ThreadLocal<byte[]> sThreadLocalDownloadBuffer = new ThreadLocal<>();

    private boolean isLocalProfile = false;

    /* ------------------------------------------------------------------ */
    /* live progress + cancellation                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Numbers for a progress window: which stage, how far along, how fast,
     * and which file is on the wire right now.
     */
    public interface ProgressObserver {
        void onDownloadProgress(@Nullable String stage, int percent, double bytesPerSecond,
                                @Nullable String currentFile, int activeFileCount);
    }

    private volatile ProgressObserver mObserver;
    private volatile boolean mCancelled;
    private volatile ThreadPoolExecutor mActivePool;
    private volatile int mStageRes;
    private android.content.Context mAppContext;
    /* files currently on the wire; concurrent, because three workers write it */
    private final Set<String> mActiveFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void setProgressObserver(@Nullable ProgressObserver observer) {
        mObserver = observer;
    }

    public boolean isCancelled() {
        return mCancelled;
    }

    /**
     * Aborts the run and interrupts the workers.
     *
     * <p>A flag alone would only stop the next file: the point of cancelling is
     * that the socket in flight dies now. shutdownNow() interrupts the worker
     * threads, the blocking read throws, and every layer below rethrows as
     * InterruptedException, which is how the caller tells "cancelled" apart
     * from "broken".
     */
    public void cancel() {
        mCancelled = true;
        ThreadPoolExecutor pool = mActivePool;
        if (pool != null) pool.shutdownNow();
    }

    private void throwIfCancelled() throws InterruptedException {
        if (mCancelled) throw new InterruptedException("download cancelled");
    }

    /** Reports current numbers to the observer, if anybody is listening. */
    private void reportObserver(int percent, double bytesPerSecond) {
        ProgressObserver observer = mObserver;
        if (observer == null) return;
        android.content.Context ctx = mAppContext;
        String stage = (ctx != null && mStageRes != 0) ? ctx.getString(mStageRes) : null;
        observer.onDownloadProgress(stage, percent, bytesPerSecond,
                firstActiveFile(), mActiveFiles.size());
    }

    @Nullable
    private String firstActiveFile() {
        if (mActiveFiles.isEmpty()) return null;
        String shortest = null;
        for (String path : mActiveFiles) {
            if (shortest == null || path.length() < shortest.length()) shortest = path;
        }
        return shortest;
    }

    /** Path shown in the progress window: relative to the game folder. */
    private static String displayPath(File file) {
        try {
            String home = new File(Tools.DIR_GAME_NEW).getAbsolutePath();
            String absolute = file.getAbsolutePath();
            if (absolute.startsWith(home)) {
                String relative = absolute.substring(home.length());
                if (relative.startsWith(File.separator)) relative = relative.substring(1);
                return relative;
            }
        } catch (Exception ignored) {
        }
        return file.getName();
    }

    /**
     * Start the game version download process on the global executor service.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param version The JMinecraftVersionList.Version from the version list, if available
     * @param realVersion The version ID (necessary)
     * @param listener The download status listener
     */
    public void start(@Nullable Activity activity, @Nullable JMinecraftVersionList.Version version,
                      @NonNull String realVersion, // this was there for a reason
                      @NonNull AsyncMinecraftDownloader.DoneListener listener) {
        if(activity != null){
            isLocalProfile = Tools.isLocalProfile(activity);
            Tools.switchDemo(Tools.isDemoProfile(activity));
            mAppContext = activity.getApplicationContext();
        } else {
            isLocalProfile = true;
            Tools.switchDemo(true);
        }
        mCancelled = false;

        sExecutorService.execute(() -> {
            try {
                downloadGame(activity, version, realVersion);
                listener.onDownloadDone();
            }catch (Exception e) {
                listener.onDownloadFailed(e);
            }
            ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT);
        });
    }

    /**
     * Download the game version.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @throws Exception when an exception occurs in the function body or in any of the downloading threads.
     */
    private void downloadGame(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws Exception {
        // Put up a dummy progress line, for the activity to start the service and do all the other necessary
        // work to keep the launcher alive. We will replace this line when we will start downloading stuff.
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0, R.string.newdl_starting);
        SpeedCalculator speedCalculator = new SpeedCalculator();
        mStageRes = R.string.ucl_stage_metadata;
        mActiveFiles.clear();
        reportObserver(0, 0);

        mTargetJarFile = createGameJarPath(versionName);
        mScheduledDownloadTasks = new ArrayList<>();
        mDeclaredNatives = new ArrayList<>();
        mProcessedFileCounter = new AtomicLong(0);
        mProcessedSizeCounter = new AtomicLong(0);
        mInternetUsageCounter = new AtomicLong(0);
        mDownloaderThreadException = new AtomicReference<>(null);
        mUseFileCounter = false;

        if(!downloadAndProcessMetadata(activity, verInfo, versionName)) {
            throw new RuntimeException(activity.getString(R.string.exception_failed_to_unpack_jre17));
        }
        throwIfCancelled();
        mStageRes = R.string.ucl_stage_game_files;

        /* One dropped connection used to condemn the whole version, even with
         * hundreds of files already on disk. Rounds are cheap to repeat: every
         * file is checked against its sha1 before downloading, so a second
         * round only fetches what is still missing -- which is exactly a
         * resume, without needing any state on disk. */
        Exception lastRoundFailure = null;
        for (int round = 1; round <= MAX_DOWNLOAD_ROUNDS; round++) {
            try {
                runDownloadRound(speedCalculator);
                lastRoundFailure = null;
                break;
            } catch (InterruptedException e) {
                /* cancelled: kill the workers and stop */
                throw e;
            } catch (Exception e) {
                lastRoundFailure = e;
                Log.w("MinecraftDownloader", "download round " + round + " failed: "
                        + e.getMessage());
                if (round < MAX_DOWNLOAD_ROUNDS) {
                    try {
                        Thread.sleep(800L * round);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        }
        if (lastRoundFailure != null) throw lastRoundFailure;
        throwIfCancelled();

        mStageRes = R.string.ucl_stage_jar;
        ensureJarFileCopy();
        mStageRes = R.string.ucl_stage_natives;
        extractNatives(versionName);
        mStageRes = 0;
    }

    /** Runs every scheduled file once, reporting progress until the pool ends. */
    private void runDownloadRound(SpeedCalculator speedCalculator) throws Exception {
        mDownloaderThreadException.set(null);
        mProcessedFileCounter.set(0);
        mProcessedSizeCounter.set(0);
        mInternetUsageCounter.set(0);
        /* per-task byte counters must restart too, otherwise the deltas of the
         * new round are measured against the old one and go negative */
        for (DownloaderTask task : mScheduledDownloadTasks) task.mLastCurr = 0;

        ArrayBlockingQueue<Runnable> taskQueue =
                new ArrayBlockingQueue<>(Math.max(1, mScheduledDownloadTasks.size()), false);
        ThreadPoolExecutor downloaderPool =
                new ThreadPoolExecutor(sConcurrentFiles, sConcurrentFiles,
                        500, TimeUnit.MILLISECONDS, taskQueue);
        mActivePool = downloaderPool;
        for (DownloaderTask scheduledTask : mScheduledDownloadTasks) {
            downloaderPool.execute(scheduledTask);
        }
        downloaderPool.shutdown();

        try {
            while (mDownloaderThreadException.get() == null &&
                    !mCancelled &&
                    !downloaderPool.awaitTermination(33, TimeUnit.MILLISECONDS)) {
                double speedBytes = speedCalculator.feed(mInternetUsageCounter.get());
                int percent;
                if (mUseFileCounter) percent = reportProgressFileCounter(speedBytes / ONE_MEGABYTE);
                else percent = reportProgressSizeCounter(speedBytes / ONE_MEGABYTE);
                reportObserver(percent, speedBytes);
            }
            throwIfCancelled();
            Exception thrownException = mDownloaderThreadException.get();
            if (thrownException != null) {
                downloaderPool.shutdownNow();
                throw thrownException;
            }
        } catch (InterruptedException e) {
            downloaderPool.shutdownNow();
            throw e;
        } finally {
            mActivePool = null;
            mActiveFiles.clear();
        }
    }

    private int reportProgressFileCounter(double speed) {
        long dlFileCounter = mProcessedFileCounter.get();
        int progress = mTotalFileCount == 0 ? 0 : (int)((dlFileCounter * 100L) / mTotalFileCount);
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                R.string.newdl_downloading_game_files, dlFileCounter,
                mTotalFileCount, speed);
        return progress;
    }

    private int reportProgressSizeCounter(double speed) {
        long dlFileSize = mProcessedSizeCounter.get();
        double dlSizeMegabytes = (double) dlFileSize / ONE_MEGABYTE;
        double dlTotalMegabytes = (double) mTotalSize / ONE_MEGABYTE;
        int progress = mTotalSize == 0 ? 0 : (int)((dlFileSize * 100L) / mTotalSize);
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                R.string.newdl_downloading_game_files_size, dlSizeMegabytes, dlTotalMegabytes, speed);
        return progress;
    }

    private File createGameJsonPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".json");
    }

    private File createGameJarPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".jar");
    }

    /**
     * Ensure that there is a copy of the client JAR file in the version folder, if a copy is
     * needed.
     * @throws IOException if the copy fails
     */
    private void ensureJarFileCopy() throws IOException {
        if(mSourceJarFile == null) return;
        if(mSourceJarFile.equals(mTargetJarFile)) return;
        if(mTargetJarFile.exists()) return;
        FileUtils.ensureParentDirectory(mTargetJarFile);
        Log.i("NewMCDownloader", "Copying " + mSourceJarFile.getName() + " to "+mTargetJarFile.getAbsolutePath());
        org.apache.commons.io.FileUtils.copyFile(mSourceJarFile, mTargetJarFile, false);
    }

    private void extractNatives(String versionName) throws IOException {
        if(mDeclaredNatives.isEmpty()) return;
        int totalCount = mDeclaredNatives.size();

        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                R.string.newdl_extracting_native_libraries, 0, totalCount);

        File targetDirectory = new File(Tools.DIR_CACHE, "natives/"+versionName);
        FileUtils.ensureDirectory(targetDirectory);
        NativesExtractor nativesExtractor = new NativesExtractor(targetDirectory);
        int extractedCount = 0;
        for(File source : mDeclaredNatives) {
            nativesExtractor.extractFromAar(source);
            extractedCount++;
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, extractedCount * 100 / totalCount,
                    R.string.newdl_extracting_native_libraries, extractedCount, totalCount);
        }
    }

    private File downloadGameJson(JMinecraftVersionList.Version verInfo) throws IOException, MirrorTamperedException {
        return downloadGameJson(verInfo, false);
    }

    /**
     * @param force drop whatever is on disk and fetch again, used to recover
     *              from a version json that a previous attempt left truncated
     */
    private File downloadGameJson(JMinecraftVersionList.Version verInfo, boolean force)
            throws IOException, MirrorTamperedException {
        File targetFile = createGameJsonPath(verInfo.id);
        if (force && targetFile.exists()) targetFile.delete();
        if(!force && verInfo.sha1 == null && targetFile.canRead() && targetFile.isFile()) {
            /* a readable file is not necessarily a usable one: the manifest
             * frequently carries no sha1, so check the content before trusting
             * the cache, otherwise one half-written file breaks every retry */
            if (tryParseVersionJson(targetFile) != null) return targetFile;
            Log.w("MinecraftDownloader", "discarding truncated " + targetFile.getName()
                    + " (" + targetFile.length() + " bytes)");
            targetFile.delete();
        }
        FileUtils.ensureParentDirectory(targetFile);
        /* A mirror is supposed to rewrite the version json so that every url in
         * it points back at the mirror; that is the whole reason it exists. Its
         * bytes therefore cannot match the sha1 in the manifest, and enforcing
         * that hash made every download from every mirror fail on the first
         * metadata file. Only a manifest fetched from the official host can be
         * verified. */
        String expectedSha1 = (LauncherPreferences.PREF_VERIFY_MANIFEST
                && !DownloadMirror.hasSource()) ? verInfo.sha1 : null;
        try {
            DownloadUtils.ensureSha1(targetFile, expectedSha1, () -> {
                ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                        R.string.newdl_downloading_metadata, targetFile.getName());
                DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, verInfo.url, targetFile);
                return null;
            });
        }catch (DownloadUtils.SHA1VerificationException e) {
            if(DownloadMirror.hasSource()) throw new MirrorTamperedException();
            else throw e;
        }
        return targetFile;
    }

    /**
     * Parses a version json, returning null instead of throwing when the file
     * is missing, empty, truncated or otherwise not json.
     */
    @Nullable
    private static JMinecraftVersionList.Version tryParseVersionJson(File file) {
        if (file == null || !file.isFile() || !file.canRead()) return null;
        try {
            String content = Tools.read(file);
            if (!Tools.isValidString(content)) return null;
            return Tools.GLOBAL_GSON.fromJson(content, JMinecraftVersionList.Version.class);
        } catch (Exception e) {
            Log.w("MinecraftDownloader", "unusable version json " + file.getName()
                    + " (" + file.length() + " bytes): " + e);
            return null;
        }
    }

    private JAssets downloadAssetsIndex(JMinecraftVersionList.Version verInfo) throws IOException{
        JMinecraftVersionList.AssetIndex assetIndex = verInfo.assetIndex;
        if(assetIndex == null || verInfo.assets == null) return null;
        File targetFile = new File(Tools.ASSETS_PATH, "indexes"+ File.separator + verInfo.assets + ".json");
        FileUtils.ensureParentDirectory(targetFile);
        DownloadUtils.ensureSha1(targetFile, assetIndex.sha1, ()-> {
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                    R.string.newdl_downloading_metadata, targetFile.getName());
            DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, assetIndex.url, targetFile);
            return null;
        });
        return Tools.GLOBAL_GSON.fromJson(Tools.read(targetFile), JAssets.class);
    }
    
    private MinecraftClientInfo getClientInfo(JMinecraftVersionList.Version verInfo) {
        Map<String, MinecraftClientInfo> downloads = verInfo.downloads;
        if(downloads == null) return null;
        return downloads.get("client");
    }

    /**
     * Download (if necessary) and process a version's metadata, scheduling all downloads that this
     * version needs.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @return false if JRE17 installation failed, true otherwise
     * @throws IOException if the download of any of the metadata files fails
     */
    private boolean downloadAndProcessMetadata(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws IOException, MirrorTamperedException {
        File versionJsonFile;
        if(verInfo != null) versionJsonFile = downloadGameJson(verInfo);
        else versionJsonFile = createGameJsonPath(versionName);

        JMinecraftVersionList.Version parsed = tryParseVersionJson(versionJsonFile);
        if (parsed == null && verInfo != null) {
            /* A previous attempt that died mid-write leaves a truncated json
             * behind. downloadGameJson() will happily reuse any file it finds
             * when the manifest carries no sha1, so that corpse poisoned every
             * later run with "Unterminated string at column 8193". Throw the
             * file away and fetch it again, once. */
            Log.w("MinecraftDownloader", "version json unusable, refetching " + versionName);
            versionJsonFile = downloadGameJson(verInfo, true);
            parsed = tryParseVersionJson(versionJsonFile);
        }
        if (parsed == null) {
            long size = versionJsonFile.length();
            throw new IOException("Version JSON for " + versionName
                    + " is unreadable (" + size + " bytes); the mirror returned a truncated or invalid file");
        }
        verInfo = parsed;

        /* Runtime selection is a launch-time concern and must not gate a
         * download. Calling NewJREUtil here threw two different ways before a
         * single game file was fetched: it needs a launcher profile that this
         * build never writes, and it needs a JRE packed in the APK, which this
         * build does not carry. Either one aborted every download with
         * "failed to unpack JRE17". The version json is still parsed below, so
         * the files themselves land fine; a missing runtime is reported when
         * the game is actually started. */
        if (activity != null && verInfo.javaVersion != null) {
            Log.i("MinecraftDownloader", "download proceeds, runtime check deferred to launch (needs java "
                    + verInfo.javaVersion.majorVersion + ")");
        }

        JAssets assets = downloadAssetsIndex(verInfo);
        if(assets != null) scheduleAssetDownloads(assets);


        MinecraftClientInfo minecraftClientInfo = getClientInfo(verInfo);
        if(minecraftClientInfo != null) scheduleGameJarDownload(minecraftClientInfo, versionName);

        if(verInfo.libraries != null) scheduleLibraryDownloads(verInfo.libraries);

        if(verInfo.logging != null) scheduleLoggingAssetDownloadIfNeeded(verInfo.logging);

        if(Tools.isValidString(verInfo.inheritsFrom)) {
            JMinecraftVersionList.Version inheritedVersion = AsyncMinecraftDownloader.getListedVersion(verInfo.inheritsFrom);
            // Infinite inheritance !?! :noway:
            return downloadAndProcessMetadata(activity, inheritedVersion, verInfo.inheritsFrom);
        }
        return true;
    }

    private void growDownloadList(int addedElementCount) {
        mScheduledDownloadTasks.ensureCapacity(mScheduledDownloadTasks.size() + addedElementCount);
    }

    private void scheduleDownload(File targetFile, int downloadClass, String url, String sha1,
                                  long size, boolean skipIfFailed) throws IOException {
        scheduleDownload(targetFile, downloadClass, new String[]{url}, sha1, size, skipIfFailed);
    }

    private void scheduleDownload(File targetFile, int downloadClass, String[] urls, String sha1,
                                  long size, boolean skipIfFailed) throws IOException {
        FileUtils.ensureParentDirectory(targetFile);
        mTotalFileCount++;
        // Only attempt to check size if we still use the size counter and didn't switch to file counter.
        if(size <= 0 && !mUseFileCounter) {
            size = DownloadMirror.getContentLengthMirrored(downloadClass, urls);
        }
        if(size < 0) {
            // If we were unable to get the content length ourselves, we automatically fall back
            // to tracking the progress using the file counter.
            size = 0;
            mUseFileCounter = true;
            Log.i("MinecraftDownloader", "Failed to determine size of "+targetFile.getName()+", switching to file counter");
        }else {
            mTotalSize += size;
        }
        mScheduledDownloadTasks.add(
                new DownloaderTask(targetFile, downloadClass, urls, sha1, size, skipIfFailed)
        );
    }

    /**
     * Schedule the download of an AAR library containing the required natives, for later extraction
     * and adding to the library path.
     * @param baseRepository the source Maven repository to download from.
     * @param dependentLibrary the DependentLibrary to get the path from
     * @throws IOException in case if download scheduling fails.
     */
    private void scheduleNativeLibraryDownload(String baseRepository, DependentLibrary dependentLibrary) throws IOException {
        String path = FileUtils.removeExtension(Tools.artifactToPath(dependentLibrary)) + ".aar";
        String downloadUrl = baseRepository + path;
        File targetPath = new File(Tools.DIR_HOME_LIBRARY, path);
        mDeclaredNatives.add(targetPath);
        scheduleDownload(targetPath, DownloadMirror.DOWNLOAD_CLASS_LIBRARIES, downloadUrl, null, 0, true);
    }

    private void scheduleLibraryDownloads(DependentLibrary[] dependentLibraries) throws IOException {
        Tools.preProcessLibraries(dependentLibraries);
        growDownloadList(dependentLibraries.length);
        for(DependentLibrary dependentLibrary : dependentLibraries) {
            // Don't download lwjgl, we have our own bundled in.
            if(dependentLibrary.name.startsWith("org.lwjgl")) continue;
            // Special handling for JNA Android natives
            if(dependentLibrary.name.startsWith("net.java.dev.jna:jna:")) {
                scheduleNativeLibraryDownload(MAVEN_CENTRAL_REPO1, dependentLibrary);
            }
            String libArtifactPath = Tools.artifactToPath(dependentLibrary);
            String sha1 = null, url = null;
            long size = 0;
            boolean skipIfFailed = false;
            if(dependentLibrary.downloads != null) {
                if(dependentLibrary.downloads.artifact != null) {
                    MinecraftLibraryArtifact artifact = dependentLibrary.downloads.artifact;
                    sha1 = artifact.sha1;
                    url = artifact.url;
                    size = artifact.size;
                } else {
                    // If the library has a downloads section but doesn't have an artifact in
                    // it, it is likely natives-only, which means it can be skipped.
                    Log.i("NewMCDownloader", "Skipped library " + dependentLibrary.name + " due to lack of artifact");
                    continue;
                }
            }
            if(url == null) {
                url = (dependentLibrary.url == null
                        ? "https://libraries.minecraft.net/"
                        : dependentLibrary.url.replace("http://","https://")) + libArtifactPath;
                skipIfFailed = true;
            }
            if(!LauncherPreferences.PREF_CHECK_LIBRARY_SHA) sha1 = null;
            scheduleDownload(new File(Tools.DIR_HOME_LIBRARY, libArtifactPath),
                    DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                    url, sha1, size, skipIfFailed
            );
        }
    }
    
    private void scheduleAssetDownloads(JAssets assets) throws IOException {
        Map<String, JAssetInfo> assetObjects = assets.objects;
        if(assetObjects == null) return;
        Set<String> assetNames = assetObjects.keySet();
        growDownloadList(assetNames.size());
        for(String asset : assetNames) {
            JAssetInfo assetInfo = assetObjects.get(asset);
            if(assetInfo == null) continue;
            File targetFile;
            String hashedPath = assetInfo.hash.substring(0, 2) + File.separator + assetInfo.hash;
            String basePath = assets.mapToResources ? Tools.OBSOLETE_RESOURCES_PATH : Tools.ASSETS_PATH;
            if(assets.virtual || assets.mapToResources) {
                targetFile = new File(basePath, asset);
            } else {
                targetFile = new File(basePath, "objects" + File.separator + hashedPath);
            }
            String sha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ? assetInfo.hash : null;
            scheduleDownload(targetFile,
                    DownloadMirror.DOWNLOAD_CLASS_ASSETS,
                    MINECRAFT_RES + hashedPath,
                    sha1,
                    assetInfo.size,
                    false);
        }
    }

    private void scheduleLoggingAssetDownloadIfNeeded(JMinecraftVersionList.LoggingConfig loggingConfig) throws IOException {
        if(loggingConfig.client == null || loggingConfig.client.file == null) return;
        JMinecraftVersionList.FileProperties loggingFileProperties = loggingConfig.client.file;
        File internalLoggingConfig = new File(Tools.DIR_DATA + File.separator + "security",
                loggingFileProperties.id.replace("client", "log4j-rce-patch"));
        if(internalLoggingConfig.exists()) return;
        File destination = new File(Tools.DIR_GAME_NEW, loggingFileProperties.id);
        scheduleDownload(destination,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                loggingFileProperties.url,
                loggingFileProperties.sha1,
                loggingFileProperties.size,
                false);
    }

    private void scheduleGameJarDownload(MinecraftClientInfo minecraftClientInfo, String versionName) throws IOException {
        File clientJar = createGameJarPath(versionName);
        String clientSha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ?
                minecraftClientInfo.sha1 : null;
        growDownloadList(1);
        /* the client jar is served at the original /v1/objects path by a
         * mirror that copies Mojang, and at /version/<id>/client by the common
         * China mirrors: try both instead of betting on one */
        scheduleDownload(clientJar,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                DownloadMirror.clientJarCandidates(versionName, minecraftClientInfo.url),
                clientSha1,
                minecraftClientInfo.size,
                false
        );
        // Store the path of the JAR to copy it into our new version folder later.
        mSourceJarFile = clientJar;
    }

    private static byte[] getLocalBuffer() {
        byte[] tlb = sThreadLocalDownloadBuffer.get();
        if(tlb != null) return tlb;
        tlb = new byte[32768];
        sThreadLocalDownloadBuffer.set(tlb);
        return tlb;
    }

    private final class DownloaderTask implements Runnable, Tools.DownloaderFeedback {
        private final File mTargetPath;
        private final String mTargetUrl;
        /* every URL worth trying for this file; one entry for most files */
        private final String[] mTargetUrls;
        private String mTargetSha1;
        private final int mDownloadClass;
        private final boolean mSkipIfFailed;
        private int mLastCurr;
        private final long mDownloadSize;

        DownloaderTask(File targetPath, int downloadClass, String targetUrl, String targetSha1,
                       long downloadSize, boolean skipIfFailed) {
            this(targetPath, downloadClass, new String[]{targetUrl}, targetSha1,
                    downloadSize, skipIfFailed);
        }

        DownloaderTask(File targetPath, int downloadClass, String[] targetUrls, String targetSha1,
                       long downloadSize, boolean skipIfFailed) {
            this.mTargetPath = targetPath;
            this.mTargetUrls = targetUrls;
            this.mTargetUrl = targetUrls[0];
            this.mTargetSha1 = targetSha1;
            this.mDownloadClass = downloadClass;
            this.mDownloadSize = downloadSize;
            this.mSkipIfFailed = skipIfFailed;
        }

        private String downloadSha1() throws IOException {
            String downloadedHash = DownloadMirror.downloadStringMirrored(
                    mDownloadClass, mTargetUrl + ".sha1"
            );
            if(!Tools.isValidString(downloadedHash)) return null;
            // Ensure that we don't have leading/trailing whitespaces before checking hash length
            downloadedHash = downloadedHash.trim();
            // SHA1 is made up of 20 bytes, which means 40 hexadecimal digits, which means 40 chars
            if(downloadedHash.length() != 40) return null;
            return downloadedHash;
        }

        /*
         * Maven repositories usually have the hash of a library near it, like:
         * .../libraryName-1.0.jar
         * .../libraryName.1.0.jar.sha1
         * Since Minecraft libraries are stored in maven repositories, try to use
         * this when downloading libraries without hashes in the json.
         */
        private void tryGetLibrarySha1() {
            String resultHash = null;
            try {
                resultHash = downloadSha1();
                // The hash is a 40-byte download.
                mInternetUsageCounter.getAndAdd(40);
            }catch (IOException e) {
                Log.i("MinecraftDownloader", "Failed to download hash", e);
            }
            if(resultHash != null) {
                Log.i("MinecraftDownloader", "Got hash: "+resultHash+ " for "+FileUtils.getFileName(mTargetUrl));
                mTargetSha1 = resultHash;
            }
        }

        @Override
        public void run() {
            try {
                runCatching();
            }catch (Exception e) {
                mDownloaderThreadException.set(e);
            }
        }

        private void runCatching() throws Exception {
            throwIfCancelled();
            if(mDownloadClass == DownloadMirror.DOWNLOAD_CLASS_LIBRARIES && !Tools.isValidString(mTargetSha1)) {
                // If we're downloading a library, try to get sha1 since it might be available as a file
                tryGetLibrarySha1();
            }
            if(Tools.isValidString(mTargetSha1)) {
                verifyFileSha1();
            }else {
                mTargetSha1 = null; // Nullify SHA1 as DownloadUtils.ensureSha1 only checks for null,
                                    // not for string validity
                if(mTargetPath.exists()) finishWithoutDownloading();
                else downloadFile();
            }
        }
        
        private void verifyFileSha1() throws Exception {
            if(mTargetPath.isFile() && mTargetPath.canRead() && Tools.compareSHA1(mTargetPath, mTargetSha1)) {
                finishWithoutDownloading();
            } else {
                // Rely on the download function to throw an IOE in case if the file is not
                // writable/not a file/etc...
                downloadFile();
            }
        }
        
        private void downloadFile() throws Exception {
            /* Upstream refused to download anything for a local (offline)
             * profile and threw on the first file, which made every download
             * fail at one or two percent for anyone not signed in with
             * Microsoft. Downloading game files has nothing to do with which
             * account is selected, so that gate is gone. */
            String label = displayPath(mTargetPath);
            mActiveFiles.add(label);
            try {
                try {
                    DownloadUtils.ensureSha1(mTargetPath, mTargetSha1, () -> {
                        downloadFromCandidates();
                        return null;
                    });
                }catch (Exception e) {
                    /* skipIfFailed swallows a missing library, but never a
                     * cancellation: the caller has to see the abort */
                    if (mCancelled) throw e;
                    if(!mSkipIfFailed) throw e;
                }
            } finally {
                mActiveFiles.remove(label);
            }
            mProcessedFileCounter.incrementAndGet();
        }

        /**
         * Tries every URL this task knows about, in order, with retries.
         *
         * <p>A mirror is not required to keep Mojang's layout: the client jar
         * lives at {@code /version/<id>/client} on some of them and at the
         * original {@code /v1/objects/...} path on others. Asking the task for
         * a list instead of one URL is what lets either kind work.
         *
         * <p>Transient failures are normal on mobile links -- "Software caused
         * connection abort" is a dropped keep-alive, not a missing file -- and
         * one of those used to kill the entire version. Each candidate now gets
         * three attempts before it is considered dead.
         */
        private void downloadFromCandidates() throws Exception {
            Exception last = null;
            for (String url : mTargetUrls) {
                for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_URL; attempt++) {
                    try {
                        /* Anything big -- the client jar above all -- is pulled
                         * over several connections. One stream is what made a
                         * 25 MB jar take minutes; four take a quarter of that.
                         * Sources that ignore Range answer 200 instead of 206
                         * and we simply fall back to the plain download. */
                        if (mDownloadSize >= DownloadUtils.RANGE_MIN_SIZE) {
                            String resolved = DownloadMirror.resolveUrl(mDownloadClass, url);
                            try {
                                if (DownloadUtils.downloadFileRanged(resolved, mTargetPath,
                                        mDownloadSize, this)) {
                                    return;
                                }
                            } catch (DownloadUtils.RangeNotSupportedException ignored) {
                                Log.i("MinecraftDownloader", "no Range support at " + resolved);
                            }
                        }
                        DownloadMirror.downloadFileMirrored(mDownloadClass, url, mTargetPath,
                                getLocalBuffer(), this);
                        return;
                    } catch (Exception e) {
                        last = e;
                        if (mCancelled) throw new InterruptedException("download cancelled");
                        Log.w("MinecraftDownloader", "attempt " + attempt + "/"
                                + MAX_ATTEMPTS_PER_URL + " failed for " + url
                                + " (" + e.getMessage() + ")");
                        if (e instanceof InterruptedException) throw e;
                        if (attempt < MAX_ATTEMPTS_PER_URL) {
                            try {
                                Thread.sleep(400L * attempt);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw ie;
                            }
                            /* the next attempt starts the byte counter over */
                            mLastCurr = 0;
                        }
                    }
                }
            }
            if (last != null) throw last;
            throw new IOException("No URL for " + mTargetPath.getName());
        }

        private void finishWithoutDownloading() {
            mProcessedFileCounter.incrementAndGet();
            mProcessedSizeCounter.addAndGet(mDownloadSize);
        }

        @Override
        public void updateProgress(int curr, int max) {
            int delta = curr - mLastCurr;
            mProcessedSizeCounter.addAndGet(delta);
            mInternetUsageCounter.addAndGet(delta);
            mLastCurr = curr;
        }
    }
}

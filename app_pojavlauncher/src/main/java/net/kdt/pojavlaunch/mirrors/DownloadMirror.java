package net.kdt.pojavlaunch.mirrors;

import android.util.Log;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.mcui.UclMirrorSource;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

/**
 * Rewrites official download URLs onto the mirror the user supplied.
 *
 * <p>This used to ship two sources: "default" (Mojang) and a hardcoded
 * BMCLAPI entry. Both are gone. There is no built-in source any more, and
 * there is no "fall back to the official server" path either -- falling back
 * would mean the application still fetches game files on its own, which is
 * exactly what it must never do. With no source configured every method here
 * throws, and the caller reports that a source is required.
 */
public class DownloadMirror {
    public static final int DOWNLOAD_CLASS_LIBRARIES = 0;
    public static final int DOWNLOAD_CLASS_METADATA = 1;
    public static final int DOWNLOAD_CLASS_ASSETS = 2;

    private static final String URL_PROTOCOL_TAIL = "://";
    private static final String HOST_LIBRARIES = "libraries.minecraft.net";
    private static final String HOST_RESOURCES = "resources.download.minecraft.net";
    private static final String HOST_PISTON = "piston-meta.mojang.com";
    private static final String HOST_LAUNCHERMETA = "launchermeta.mojang.com";

    /** Thrown when no source is configured; the UI turns this into a prompt. */
    public static class NoSourceException extends IOException {
        public NoSourceException() {
            super("No download source configured");
        }
    }

    public static void downloadFileMirrored(int downloadClass, String urlInput, File outputFile,
                                            @Nullable byte[] buffer, Tools.DownloaderFeedback monitor) throws IOException {
        if (downloadClass != DOWNLOAD_CLASS_METADATA) {
            DownloadUtils.downloadFileMonitored(getMirrorMapping(downloadClass, urlInput),
                    outputFile, buffer, monitor);
            return;
        }
        IOException last = null;
        for (String candidate : metadataCandidates(urlInput)) {
            try {
                /* no monitor means the caller wants a plain copy: passing null
                 * down to downloadFileMonitored() is a NullPointerException on
                 * the first byte, not a download */
                if (monitor == null) DownloadUtils.downloadFile(candidate, outputFile);
                else DownloadUtils.downloadFileMonitored(candidate, outputFile, buffer, monitor);
                return;
            } catch (IOException e) {
                last = e;
                Log.w("DownloadMirror", "metadata miss " + candidate + " (" + e + ")");
            }
        }
        throw last == null ? new IOException("No metadata URL for " + urlInput) : last;
    }

    public static void downloadFileMirrored(int downloadClass, String urlInput, File outputFile) throws IOException {
        if (downloadClass != DOWNLOAD_CLASS_METADATA) {
            DownloadUtils.downloadFile(getMirrorMapping(downloadClass, urlInput), outputFile);
            return;
        }
        IOException last = null;
        for (String candidate : metadataCandidates(urlInput)) {
            try {
                DownloadUtils.downloadFile(candidate, outputFile);
                return;
            } catch (IOException e) {
                last = e;
                Log.w("DownloadMirror", "metadata miss " + candidate + " (" + e + ")");
            }
        }
        throw last == null ? new IOException("No metadata URL for " + urlInput) : last;
    }

    public static long getContentLengthMirrored(int downloadClass, String urlInput) throws IOException {        if (downloadClass != DOWNLOAD_CLASS_METADATA) {
            return DownloadUtils.getContentLength(getMirrorMapping(downloadClass, urlInput));
        }
        IOException last = null;
        for (String candidate : metadataCandidates(urlInput)) {
            try {
                long length = DownloadUtils.getContentLength(candidate);
                /* a miss often answers 200-with-no-length or 404; only a real
                 * length means this candidate is the right file */
                if (length > 0) return length;
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        return -1;
    }

    /** Size probe across several candidates; -1 when none reports a length. */
    public static long getContentLengthMirrored(int downloadClass, String[] urls) {
        if (urls != null) {
            for (String url : urls) {
                try {
                    long length = getContentLengthMirrored(downloadClass, url);
                    if (length > 0) return length;
                } catch (IOException ignored) {
                }
            }
        }
        return -1;
    }

    /**
     * Where the client jar may live, in the order worth trying.
     *
     * <p>Mirrors that copy Mojang serve it at the original {@code /v1/objects}
     * path, the common China mirrors serve it at {@code /version/<id>/client}.
     * The official URL is kept last so an unmirrored setup still works.
     */
    public static String[] clientJarCandidates(String versionId, String originalUrl) {
        String[] roots = UclMirrorSource.roots();
        if (roots == null || versionId == null || versionId.isEmpty() || originalUrl == null) {
            return new String[]{originalUrl};
        }
        java.util.List<String> out = new java.util.ArrayList<>(3);
        out.add(roots[UclMirrorSource.ROOT_METADATA] + "/version/" + versionId + "/client");
        String mirrored = originalUrl;
        try {
            mirrored = getMirrorMapping(DOWNLOAD_CLASS_LIBRARIES, originalUrl);
        } catch (Exception ignored) {
        }
        if (!out.contains(mirrored)) out.add(mirrored);
        if (!out.contains(originalUrl)) out.add(originalUrl);
        return out.toArray(new String[0]);
    }

    public static String downloadStringMirrored(int downloadClass, String urlInput) throws IOException {
        if (downloadClass != DOWNLOAD_CLASS_METADATA) {
            String plain = DownloadUtils.downloadString(getMirrorMapping(downloadClass, urlInput));
            if (!Tools.isValidString(plain)) {
                throw new IOException("Empty response from mirror for " + urlInput);
            }
            return plain;
        }
        IOException last = null;
        for (String candidate : metadataCandidates(urlInput)) {
            try {
                String result = DownloadUtils.downloadString(candidate);
                if (Tools.isValidString(result)) return result;
                last = new IOException("Empty response from " + candidate);
            } catch (IOException e) {
                last = e;
                Log.w("DownloadMirror", "metadata miss " + candidate + " (" + e + ")");
            }
        }
        throw last == null ? new IOException("No metadata URL for " + urlInput) : last;
    }

    /**
     * Every place a metadata file may live on a mirror, best guess first.
     *
     * <p>Mirrors are not obliged to keep Mojang's layout. The common one keeps
     * the paths and only swaps the host; the other serves a version's json at
     * {@code /version/<id>/json} and asset indexes under {@code /assets/indexes}.
     * Trying them in order is what makes "any mirror" actually work instead of
     * assuming the first layout and failing with a 404.
     */
    private static java.util.List<String> metadataCandidates(String mojangUrl) throws IOException {
        String[] roots = UclMirrorSource.roots();
        if (roots == null) throw new NoSourceException();
        if (mojangUrl == null) throw new MalformedURLException("Null URL");

        java.util.List<String> out = new java.util.ArrayList<>(4);
        int urlTail = getBaseUrlTail(mojangUrl);
        String baseUrl = mojangUrl.substring(0, urlTail);
        String path = mojangUrl.substring(urlTail);

        boolean mojangHost = baseUrl.equals("https://" + HOST_PISTON)
                || baseUrl.equals("http://" + HOST_PISTON)
                || baseUrl.equals("https://" + HOST_LAUNCHERMETA)
                || baseUrl.equals("http://" + HOST_LAUNCHERMETA);
        if (!mojangHost) {
            /* already pointing at whatever the mirror put in its own manifest */
            out.add(mojangUrl);
            return out;
        }

        String root = roots[UclMirrorSource.ROOT_METADATA];
        out.add(root + path);
        if (path.endsWith(".json")) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            String bare = name.substring(0, name.length() - 5);
            if (!bare.isEmpty()) {
                /* version json, second layout */
                out.add(root + "/version/" + bare + "/json");
                /* asset index, second layout */
                out.add(root + "/assets/indexes/" + name);
                /* asset index served straight from the assets root */
                out.add(roots[UclMirrorSource.ROOT_ASSETS] + "/indexes/" + name);
            }
        }
        return out;
    }

    /** True when the user has configured a source at all. */
    public static boolean hasSource() {
        return UclMirrorSource.roots() != null;
    }

    /**
     * The URL this class would actually fetch, exposed for callers that open
     * their own connections.
     *
     * <p>The ranged downloader issues a request per segment, so it cannot go
     * through {@link #downloadFileMirrored}. Handing it the resolved URL keeps
     * the mirror in charge: without this it would happily ask Mojang directly
     * for a file the manifest pointed at a Mojang host, which is exactly the
     * "no built-in source" rule this build must not break.
     */
    public static String resolveUrl(int downloadClass, String urlInput) throws IOException {
        if (downloadClass == DOWNLOAD_CLASS_METADATA) {
            java.util.List<String> candidates = metadataCandidates(urlInput);
            return candidates.get(0);
        }
        return getMirrorMapping(downloadClass, urlInput);
    }

    /**
     * Rewrites the URL when it points at a Mojang host we know the mirror
     * serves. URLs that already point somewhere else (a mirror's own metadata,
     * a third-party library host) are passed through untouched.
     */
    private static String getMirrorMapping(int downloadClass, String mojangUrl)
            throws MalformedURLException, IOException {
        String[] roots = UclMirrorSource.roots();
        if (roots == null) throw new NoSourceException();
        if (mojangUrl == null) throw new MalformedURLException("Null URL");

        int urlTail = getBaseUrlTail(mojangUrl);
        String baseUrl = mojangUrl.substring(0, urlTail);
        String path = mojangUrl.substring(urlTail);

        switch (downloadClass) {
            case DOWNLOAD_CLASS_LIBRARIES:
                if (baseUrl.endsWith(HOST_LIBRARIES)) {
                    baseUrl = roots[UclMirrorSource.ROOT_LIBRARIES];
                }
                break;
            case DOWNLOAD_CLASS_METADATA:
                if (baseUrl.equals("https://" + HOST_PISTON) || baseUrl.equals("http://" + HOST_PISTON)
                        || baseUrl.equals("https://" + HOST_LAUNCHERMETA)
                        || baseUrl.equals("http://" + HOST_LAUNCHERMETA)) {
                    baseUrl = roots[UclMirrorSource.ROOT_METADATA];
                }
                break;
            case DOWNLOAD_CLASS_ASSETS:
                if (baseUrl.equals("https://" + HOST_RESOURCES) || baseUrl.equals("http://" + HOST_RESOURCES)) {
                    baseUrl = roots[UclMirrorSource.ROOT_ASSETS];
                }
                break;
        }
        Log.d("DownloadMirror", "-> " + baseUrl + path);
        return baseUrl + path;
    }

    private static int getBaseUrlTail(String wholeUrl) throws MalformedURLException {
        int protocolNameEnd = wholeUrl.indexOf(URL_PROTOCOL_TAIL);
        if (protocolNameEnd == -1)
            throw new MalformedURLException("No protocol, or non path-based URL");
        protocolNameEnd += URL_PROTOCOL_TAIL.length();
        int hostnameEnd = wholeUrl.indexOf('/', protocolNameEnd);
        if (protocolNameEnd >= wholeUrl.length() || hostnameEnd == protocolNameEnd)
            throw new MalformedURLException("No hostname");
        if (hostnameEnd == -1) hostnameEnd = wholeUrl.length();
        return hostnameEnd;
    }
}

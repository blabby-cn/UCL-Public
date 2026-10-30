package net.kdt.pojavlaunch.multirt;

import static net.kdt.pojavlaunch.Tools.NATIVE_LIB_DIR;
import static org.apache.commons.io.FileUtils.listFiles;

import android.system.Os;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.MathUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

public class MultiRTUtils {

    private static final HashMap<String,Runtime> sCache = new HashMap<>();

    private static final File RUNTIME_FOLDER = new File(Tools.MULTIRT_HOME);
    private static final String JAVA_VERSION_STR = "JAVA_VERSION=\"";
    private static final String OS_ARCH_STR = "OS_ARCH=\"";

    public static List<Runtime> getRuntimes() {
        if(!RUNTIME_FOLDER.exists() && !RUNTIME_FOLDER.mkdirs()) {
            throw new RuntimeException("Failed to create runtime directory");
        }

        ArrayList<Runtime> runtimes = new ArrayList<>();
        File[] files = RUNTIME_FOLDER.listFiles();
        if(files != null) for(File f : files) {
            runtimes.add(read(f.getName()));
        }
        else throw new RuntimeException("The runtime directory does not exist");

        return runtimes;
    }

    public static String getExactJreName(int majorVersion) {
        List<Runtime> runtimes = getRuntimes();
        for(Runtime r : runtimes)
            if(r.javaVersion == majorVersion)return r.name;

        return null;
    }

    public static String getNearestJreName(int majorVersion) {
        List<Runtime> runtimes = getRuntimes();
        MathUtils.RankedValue<Runtime> nearestRankedRuntime = MathUtils.findNearestPositive(majorVersion, runtimes, (runtime)->runtime.javaVersion);
        if(nearestRankedRuntime == null) return null;
        Runtime nearestRuntime = nearestRankedRuntime.value;
        if(nearestRuntime == null) return null;
        return nearestRuntime.name;
    }

    public static void installRuntimeNamed(String nativeLibDir, InputStream runtimeInputStream, String name) throws IOException {
        File dest = new File(RUNTIME_FOLDER,"/"+name);
        if(dest.exists()) FileUtils.deleteDirectory(dest);
        uncompressTarXZ(runtimeInputStream,dest);
        runtimeInputStream.close();
        unpack200(nativeLibDir,RUNTIME_FOLDER + "/" + name);
        ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);
        read(name);
    }

    public static void postPrepare(String name) throws IOException {
        File dest = new File(RUNTIME_FOLDER,"/" + name);
        if(!dest.exists()) return;
        Runtime runtime = read(name);
        String libFolder = "lib";
        if(new File(dest,libFolder + "/" + runtime.arch).exists()) libFolder = libFolder + "/" + runtime.arch;
        File ftIn = new File(dest, libFolder + "/libfreetype.so.6");
        File ftOut = new File(dest, libFolder + "/libfreetype.so");
        if (ftIn.exists() && (!ftOut.exists() || ftIn.length() != ftOut.length())) {
            if(!ftIn.renameTo(ftOut)) throw new IOException("Failed to rename freetype");
        }

        // Refresh libraries
        copyDummyNativeLib("libawt_xawt.so", dest, libFolder);
    }

    public static void installRuntimeNamedBinpack(InputStream universalFileInputStream, InputStream platformBinsInputStream, String name, String binpackVersion) throws IOException {
        File dest = new File(RUNTIME_FOLDER,"/"+name);
        if(dest.exists()) FileUtils.deleteDirectory(dest);
        installRuntimeNamedNoRemove(universalFileInputStream,dest);
        installRuntimeNamedNoRemove(platformBinsInputStream,dest);

        unpack200(NATIVE_LIB_DIR,RUNTIME_FOLDER + "/" + name);

        File binpack_verfile = new File(RUNTIME_FOLDER,"/"+name+"/pojav_version");
        FileOutputStream fos = new FileOutputStream(binpack_verfile);
        fos.write(binpackVersion.getBytes());
        fos.close();

        ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);

        forceReread(name);
    }


    /**
     * Installs a runtime from one complete tar.xz archive.
     *
     * <p>This is the shape the download centre fetches: one file per version
     * and architecture, unlike the two-file binpack the launcher used to carry
     * around. The archive is expected to contain the runtime at its root, but
     * builds released by other projects often wrap everything in a folder named
     * after the archive -- {@link #collapseSingleRoot} flattens that, because a
     * runtime whose release file sits one level too deep reads as broken
     * everywhere else in the launcher.
     */
    public static void installFromArchive(InputStream archive, String name) throws IOException {
        File dest = new File(RUNTIME_FOLDER, name);
        if (dest.exists()) FileUtils.deleteDirectory(dest);
        installRuntimeNamedNoRemove(archive, dest);
        collapseSingleRoot(dest);
        renameFreetype(dest);
        /* Java 8 ships packed jars; newer versions have nothing to unpack and
         * the walk simply finds no .pack file */
        unpack200(NATIVE_LIB_DIR, dest.getAbsolutePath());
        /* Same follow-up the launcher applies to an imported or bundled
         * runtime. The builds this launcher downloads do not carry
         * libawt_xawt.so, and libawt.so dlopen()s it by name at runtime; the
         * stub in the apk is what answers that call. Skipping this step is how
         * a downloaded runtime ends up "installed but unusable". */
        postPrepare(name);
        ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);
        Runtime installed = forceReread(name);
        /* An archive that unpacked into something without a readable release
         * file is not a runtime. Leaving it there would make the folder show up
         * as an installed Java that fails at launch with no explanation, so the
         * half-made tree is removed and the caller gets a retryable error. The
         * downloaded archive is kept by the caller, so retrying is cheap. */
        if (installed.versionString == null) {
            FileUtils.deleteDirectory(dest);
            sCache.remove(name);
            throw new IOException("Unpacked runtime has no readable release file");
        }
    }

    /**
     * Moves the contents of a single wrapping folder up one level.
     *
     * <p>Only when the destination has no release file of its own and exactly
     * one child directory does: anything else is a runtime we do not recognise
     * and guessing at its layout would be worse than leaving it.
     */
    private static void collapseSingleRoot(File dest) throws IOException {
        if (new File(dest, "release").isFile()) return;
        File[] children = dest.listFiles();
        if (children == null || children.length != 1 || !children[0].isDirectory()) return;
        File inner = children[0];
        if (!new File(inner, "release").isFile()) return;
        File[] parts = inner.listFiles();
        if (parts == null) return;
        for (File part : parts) {
            if (!part.renameTo(new File(dest, part.getName()))) {
                throw new IOException("Failed to lift " + part.getName() + " out of " + inner.getName());
            }
        }
        if (!inner.delete()) Log.w("MultiRT", "Empty runtime wrapper left behind: " + inner);
    }

    /**
     * Gives the runtime the freetype name it actually looks for.
     *
     * <p>The build ships {@code libfreetype.so.6}; the JVM dlopen()s
     * {@code libfreetype.so}. Every Pojav runtime does this rename after
     * unpacking, and skipping it costs a crash the first time a font is needed.
     */
    private static void renameFreetype(File dest) throws IOException {
        File lib = new File(dest, "lib");
        File[] candidates = {lib, new File(lib, "aarch64"), new File(lib, "arm"),
                new File(lib, "x86_64"), new File(lib, "i386")};
        for (File dir : candidates) {
            File source = new File(dir, "libfreetype.so.6");
            File target = new File(dir, "libfreetype.so");
            if (!source.isFile()) continue;
            if (target.isFile() && target.length() == source.length()) continue;
            if (!source.renameTo(target)) throw new IOException("Failed to rename freetype");
        }
    }

    public static String readInternalRuntimeVersion(String name) {
        File versionFile = new File(RUNTIME_FOLDER,"/" + name + "/pojav_version");
        try {
            if (versionFile.exists()) {
                return Tools.read(versionFile.getAbsolutePath());
            }else{
                return null;
            }
        }catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void removeRuntimeNamed(String name) throws IOException {
        File dest = new File(RUNTIME_FOLDER,"/"+name);
        if(dest.exists()) {
            FileUtils.deleteDirectory(dest);
            sCache.remove(name);
        }
    }

    public static File getRuntimeHome(String name) {
        File dest = new File(RUNTIME_FOLDER, name);
        Log.i("MiltiRTUitls", "Dest exists? "+dest.exists());
        if((!dest.exists()) || MultiRTUtils.forceReread(name).versionString == null) throw new RuntimeException("Selected runtime is broken!");
        return dest;
    }

    public static Runtime forceReread(String name) {
        sCache.remove(name);
        return read(name);
    }

    public static Runtime read(String name) {
        Runtime returnRuntime = sCache.get(name);
        if(returnRuntime != null) return returnRuntime;
        File release = new File(RUNTIME_FOLDER,name+"/release");
        if(!release.exists()) {
            return new Runtime(name);
        }
        try {
            String content = Tools.read(release.getAbsolutePath());
            String javaVersion = Tools.extractUntilCharacter(content, JAVA_VERSION_STR, '"');
            String osArch = Tools.extractUntilCharacter(content, OS_ARCH_STR, '"');
            if(javaVersion != null && osArch != null) {
                String[] javaVersionSplit = javaVersion.split("\\.");
                int javaVersionInt;
                if (javaVersionSplit[0].equals("1")) {
                    javaVersionInt = Integer.parseInt(javaVersionSplit[1]);
                } else {
                    javaVersionInt = Integer.parseInt(javaVersionSplit[0]);
                }
                returnRuntime = new Runtime(name, javaVersion, osArch, javaVersionInt);
            }else{
                returnRuntime =  new Runtime(name);
            }
        }catch(IOException e) {
            returnRuntime =  new Runtime(name);
        }
        sCache.put(name, returnRuntime);
        return returnRuntime;
    }

    /**
     * Unpacks all .pack files into .jar Serves only for java 8, as java 9 brought project jigsaw
     * @param nativeLibraryDir The native lib path, required to execute the unpack200 binary
     * @param runtimePath The path to the runtime to walk into
     */
    private static void unpack200(String nativeLibraryDir, String runtimePath) {

        File basePath = new File(runtimePath);
        Collection<File> files = listFiles(basePath, new String[]{"pack"}, true);

        File workdir = new File(nativeLibraryDir);

        ProcessBuilder processBuilder = new ProcessBuilder().directory(workdir);
        for(File jarFile : files){
            try{
                Process process = processBuilder.command("./libunpack200.so", "-r", jarFile.getAbsolutePath(), jarFile.getAbsolutePath().replace(".pack", "")).start();
                process.waitFor();
            }catch (InterruptedException | IOException e) {
                Log.e("MULTIRT", "Failed to unpack the runtime !");
            }
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static void copyDummyNativeLib(String name, File dest, String libFolder) throws IOException {
        File source = new File(NATIVE_LIB_DIR, name);
        /* A build without the stub must not fail the whole install: the runtime
         * is still usable for anything that does not touch that code path. */
        if (!source.isFile()) {
            Log.w("MultiRT", "No " + name + " in the native library directory, skipping");
            return;
        }
        File fileLib = new File(dest, "/"+libFolder + "/" + name);
        FileInputStream is = new FileInputStream(source);
        FileOutputStream os = new FileOutputStream(fileLib);
        IOUtils.copy(is, os);
        is.close();
        os.close();
    }

    private static void installRuntimeNamedNoRemove(InputStream runtimeInputStream, File dest) throws IOException {
        uncompressTarXZ(runtimeInputStream,dest);
        runtimeInputStream.close();
    }

    private static void uncompressTarXZ(final InputStream tarFileInputStream, final File dest) throws IOException {
        net.kdt.pojavlaunch.utils.FileUtils.ensureDirectory(dest);

        byte[] buffer = new byte[8192];
        TarArchiveInputStream tarIn = new TarArchiveInputStream(
                new XZCompressorInputStream(tarFileInputStream)
        );
        TarArchiveEntry tarEntry = tarIn.getNextTarEntry();
        // tarIn is a TarArchiveInputStream
        while (tarEntry != null) {

            final String tarEntryName = tarEntry.getName();
            // publishProgress(null, "Unpacking " + tarEntry.getName());
            ProgressLayout.setProgress(ProgressLayout.UNPACK_RUNTIME, 100, R.string.global_unpacking, tarEntryName);

            File destPath = new File(dest, tarEntry.getName());
            net.kdt.pojavlaunch.utils.FileUtils.ensureParentDirectory(destPath);
            if (tarEntry.isSymbolicLink()) {
                try {
                    // android.system.Os
                    // Libcore one support all Android versions
                    Os.symlink(tarEntry.getName(), tarEntry.getLinkName());
                } catch (Throwable e) {
                    Log.e("MultiRT", e.toString());
                }

            } else if (tarEntry.isDirectory()) {
                net.kdt.pojavlaunch.utils.FileUtils.ensureDirectory(destPath);
            } else if (!destPath.exists() || destPath.length() != tarEntry.getSize()) {
                FileOutputStream os = new FileOutputStream(destPath);
                IOUtils.copyLarge(tarIn, os, buffer);
                os.close();
            }
            tarEntry = tarIn.getNextTarEntry();
        }
        tarIn.close();
    }
}

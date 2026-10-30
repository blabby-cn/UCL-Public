package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The splash line, read from {@code assets/ucl/splashes.json}.
 *
 * <p>This used to be a 1140x228 PNG that said one fixed thing forever. A splash
 * that never changes is furniture, not a splash: the point of the line is that
 * the next launch surprises you. So the words ship as data -- one JSON array of
 * strings, editable without touching code -- and one entry is drawn at random
 * every time the launcher starts.
 *
 * <p>The file is read once per process, not once per draw: it is a compiled
 * asset, and the pick is what changes, not the contents. A missing or broken
 * file degrades to a single fallback line instead of an empty spot beside the
 * logo.
 */
public final class UclSplashes {
    private static final String TAG = "UclSplashes";
    private static final String ASSET = "ucl/splashes.json";
    private static final String FALLBACK = "Hello world!";
    private static final int MAX_ASSET_BYTES = 256 * 1024;

    private static final Random RANDOM = new Random();
    private static List<String> sLines;

    private UclSplashes() {}

    /** One line, at random, for this launch. Never null, never empty. */
    public static String pick(Context context) {
        List<String> lines = lines(context);
        return lines.get(RANDOM.nextInt(lines.size()));
    }

    private static List<String> lines(Context context) {
        if (sLines == null) sLines = read(context);
        return sLines;
    }

    private static List<String> read(Context context) {
        List<String> out = new ArrayList<>();
        InputStream stream = null;
        try {
            stream = context.getApplicationContext().getAssets().open(ASSET);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                /* a runaway file must not eat the heap at startup */
                if (buffer.size() > MAX_ASSET_BYTES) throw new IllegalStateException("too big");
            }
            JSONArray array = new JSONArray(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                String line = array.optString(i, "").trim();
                if (!line.isEmpty()) out.add(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot read " + ASSET + ", using the fallback line", e);
            out.clear();
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (out.isEmpty()) out.add(FALLBACK);
        return Collections.unmodifiableList(out);
    }
}

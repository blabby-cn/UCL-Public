package net.kdt.pojavlaunch.mcui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.utils.LocaleUtils;

/**
 * The one place that decides what text looks like across the launcher.
 *
 * <p>Every screen used to fall back to whatever the ROM's system font is, so a
 * MIUI phone and an AOSP phone rendered the same window in two different faces.
 * Noto Sans SC ships inside the APK now, and this class is what puts it on
 * every {@link TextView} -- including the ones built in code, which a theme
 * attribute cannot reach.
 *
 * <p>The rule for adopting a view is deliberately narrow: only the untouched
 * defaults are replaced. A view that already carries a specific {@link Typeface}
 * instance picked one on purpose (a button that wants its own weight, the log
 * console that wants monospace), and overwriting it would be worse than leaving
 * it alone.
 */
public final class UclTypeface {

    private static Typeface sRegular;
    private static Typeface sBold;
    private static Typeface sDisplay;

    private UclTypeface() {}

    public static Typeface regular(Context context) {
        if (sRegular == null) {
            sRegular = load(context, R.font.noto_sans_sc);
        }
        return sRegular;
    }

    /**
     * The display face: Minecraft Ten in English, Noto everywhere else.
     *
     * <p>It is not a second body font. Only the page titles and the settings
     * tabs wear it, and only while the launcher speaks English - the face has
     * no Chinese, no kana and no hangul, so any other language would either
     * fall back to the system font mid-word or lose its text entirely. A page
     * of German is Latin letters too, and still is not English.
     */
    public static Typeface title(Context context) {
        return display(context, regular(context));
    }

    /**
     * The face for keys - every button, plate or dialog footer.
     *
     * <p>Same display face as the titles, with the body's bold as the fallback:
     * a key is a command, and a command is not set in the reading weight.
     */
    public static Typeface command(Context context) {
        return display(context, bold(context));
    }

    /**
     * The display face when English is on screen, the fallback otherwise.
     *
     * <p>The check lives here rather than at each call site so that no screen
     * can forget it: a button added tomorrow is either drawn through this class
     * and therefore correct, or it is not a button of ours.
     */
    public static Typeface display(Context context, Typeface fallback) {
        if (!LocaleUtils.isEnglish()) return fallback;
        if (sDisplay == null) {
            sDisplay = load(context, R.font.minecraft_ten);
        }
        return sDisplay;
    }

    /** Whether this is the display face, i.e. the one English titles wear. */
    public static boolean isDisplay(Typeface typeface) {
        return typeface != null && typeface == sDisplay;
    }

    /**
     * How far the cap ink of a line sits below the centre of its line box.
     *
     * <p>The bar centres a line box, not the ink, and every face puts the two
     * in a different place: the display face's metrics are its own (a 0.99/0.25
     * ascent/descent against Noto's 1.16/0.29), so the fraction that lifts Noto
     * into the optical centre lifts this one too far by about a tenth of an em.
     * Measured from the paint instead of guessed, because the two numbers only
     * agree on one font.
     */
    public static float capRise(android.widget.TextView view) {
        android.graphics.Paint paint = view.getPaint();
        android.graphics.Paint.FontMetrics metrics = paint.getFontMetrics();
        if (metrics == null) return 0f;
        android.graphics.Rect cap = new android.graphics.Rect();
        paint.getTextBounds("H", 0, 1, cap);
        float boxHeight = metrics.descent - metrics.ascent;
        float baseline = -metrics.ascent;
        float inkCentre = baseline + (cap.top + cap.bottom) / 2f;
        return inkCentre - boxHeight / 2f;
    }

    public static Typeface bold(Context context) {
        if (sBold == null) {
            sBold = load(context, R.font.noto_sans_sc_bold);
        }
        return sBold;
    }

    private static Typeface load(Context context, int fontRes) {
        Typeface typeface = ResourcesCompat.getFont(context.getApplicationContext(), fontRes);
        /* a broken font resource must not take the UI down with it */
        return typeface != null ? typeface : Typeface.DEFAULT;
    }

    /** Walks a whole tree. Safe to call on a view that is still being built. */
    public static void apply(View root) {
        adopt(root, 0);
    }

    public static void apply(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (activity.getWindow() == null) return;
        apply(activity.getWindow().getDecorView());
    }

    public static void apply(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        apply(dialog.getWindow().getDecorView());
    }

    private static void adopt(View view, int depth) {
        /* view trees are shallow; the guard is only here so a pathological
         * layout cannot turn a resume into a stack overflow */
        if (view == null || depth > 64) return;

        if (view instanceof TextView) {
            adopt((TextView) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                adopt(group.getChildAt(i), depth + 1);
            }
        }
    }

    private static void adopt(TextView view) {
        Typeface current = view.getTypeface();
        Context context = view.getContext();
        Typeface replacement;
        if (current == Typeface.DEFAULT_BOLD) {
            replacement = bold(context);
        } else if (current == null || current == Typeface.DEFAULT) {
            replacement = regular(context);
        } else {
            /* already deliberate: a custom typeface, or monospace for the log */
            return;
        }
        /* NORMAL on purpose -- the weight lives in the font file, so asking for
         * BOLD here would double it with a synthetic stroke */
        view.setTypeface(replacement, Typeface.NORMAL);
    }
}

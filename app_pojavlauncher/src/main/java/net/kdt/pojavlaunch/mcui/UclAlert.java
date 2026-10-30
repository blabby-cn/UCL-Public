package net.kdt.pojavlaunch.mcui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

import net.kdt.pojavlaunch.R;

/**
 * The plain two shapes of window, for the code that only wants to say something
 * or ask a yes/no question.
 *
 * <p>Every call site used to reach for {@code AlertDialog.Builder}. That builder
 * brings a title strip and a button row of its own, so those windows looked like
 * a different application: different plate colour, different key shape, system
 * keys instead of the game's. Nothing in the shell does that any more; these two
 * methods are what the call sites use instead of the builder.
 *
 * <p>Both tolerate a context that is not an activity -- a service, or the
 * application itself -- by doing nothing. A window needs a window to sit in, and
 * the paths that pass such a context have already failed to reach the point
 * where the user could act on an answer.
 */
public final class UclAlert {

    private UclAlert() {}

    /** The activity inside a context, or null when there is none. */
    public static Activity activityOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == null || base == current) break;
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    /**
     * The builder, for the windows that need keys or a body of their own.
     * Null when the context cannot host a window.
     */
    public static UclDialog.Builder builder(Context context) {
        Activity activity = activityOf(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        return UclDialog.build(activity);
    }

    /** A window with one key, which is the only way out of it. */
    public static UclDialog notice(Context context, CharSequence title,
                                   CharSequence body, Runnable onOk) {
        Activity activity = activityOf(context);
        if (activity == null || activity.isFinishing()) return null;
        return UclDialog.build(activity)
                .title(title)
                .message(body)
                .cancelable(false)
                .closeMark(false)
                .key(activity.getString(R.string.ucl_dlg_ok), UclDialog.STYLE_LIGHT, onOk)
                .show();
    }

    /**
     * A question with two answers: the way out on the left, the answer that does
     * something on the right.
     *
     * <p>{@code style} colours the acting key -- light for an ordinary action,
     * red for one that cannot be undone. The key that closes without doing
     * anything is never red.
     */
    public static UclDialog confirm(Context context, CharSequence title, CharSequence body,
                                    CharSequence positive, int style, Runnable onOk) {
        Activity activity = activityOf(context);
        if (activity == null || activity.isFinishing()) return null;
        return UclDialog.build(activity)
                .title(title)
                .message(body)
                .rowLayout(true)
                .key(activity.getString(R.string.ucl_dlg_cancel), UclDialog.STYLE_LIGHT, null)
                .key(positive, style, onOk)
                .show();
    }

    /** The same question, with the confirm key in the ordinary colour. */
    public static UclDialog confirm(Context context, CharSequence title, CharSequence body,
                                    CharSequence positive, Runnable onOk) {
        return confirm(context, title, body, positive, UclDialog.STYLE_LIGHT, onOk);
    }
}

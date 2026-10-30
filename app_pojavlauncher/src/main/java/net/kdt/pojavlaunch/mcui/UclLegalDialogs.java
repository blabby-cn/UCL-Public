package net.kdt.pojavlaunch.mcui;

import android.app.Activity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

/**
 * The confirmation chain that stands between the download button and any file
 * being fetched.
 *
 * <p>It is a chain on purpose. Eleven rounds of "one Toast is enough" ended with
 * an app that starts fetching on a single tap, so every request now has to pass
 * four separate windows: the disclaimer (which only unlocks after the text has
 * actually been scrolled to the end), the source confirmation, the legal risk
 * acknowledgement, and the final go-ahead. None of them remember a previous
 * answer, so the whole chain shows up again on the next attempt.
 *
 * <p>Every one of them is drawn by {@link UclDialog}, which reproduces the
 * Minecraft plate measured off the reference screenshots. Nothing here touches
 * AppCompat's AlertDialog any more: its title strip and button row are the two
 * things that made these windows look unlike the rest of the shell.
 *
 * <p>Key colours follow the reference convention: green is the affirmative key,
 * light grey is the way out, red is reserved for an action that carries a
 * warning with it. Cancelling a step is never red -- it is the safe answer.
 */
public final class UclLegalDialogs {

    private UclLegalDialogs() {}

    public interface Callback {
        void onDone();
    }

    /* ------------------------------------------------------------------ */
    /* 1. disclaimer, gated on scrolling to the end                        */
    /* ------------------------------------------------------------------ */

    /**
     * The full disclaimer. The accept key stays disabled until the text has
     * been scrolled to the bottom; a body short enough not to scroll unlocks
     * immediately, so large screens are not stuck.
     *
     * <p>No close mark: this window is a decision, not a notice. There is no
     * way to leave it except answering it -- agreeing, or declining. The cross
     * that every other plate carries would be a third answer, and it would be
     * the one that skips the question while looking like a dismissal.
     *
     * @param onDecline what declining does; null means leave the activity
     */
    public static void disclaimer(Activity activity, final Callback onDecline,
                                  final Callback onAgree) {
        if (activity == null || activity.isFinishing()) return;

        final UclDialog dialog = UclDialog.build(activity)
                .title(activity.getString(R.string.ucl_disclaimer_title))
                .message(activity.getString(R.string.ucl_disclaimer_body))
                .scrollable(true)
                .cancelable(false)
                .closeMark(false)
                .key(activity.getString(R.string.ucl_disclaimer_accept), UclDialog.STYLE_GREEN, () -> {
                    /* no callback means "just close the window" -- that is the
                     * read-again entry from the download page, which must not
                     * do anything else */
                    if (onAgree != null) onAgree.onDone();
                })
                .key(activity.getString(R.string.ucl_disclaimer_decline), UclDialog.STYLE_LIGHT, () -> {
                    if (onDecline != null) onDecline.onDone();
                    else activity.finish();
                })
                .show();

        final TextView accept = dialog.key(0);
        accept.setEnabled(false);
        accept.setAlpha(0.4f);

        final ScrollView scroll = dialog.scrollBody();
        if (scroll == null) {
            accept.setEnabled(true);
            accept.setAlpha(1f);
            return;
        }

        final boolean[] unlocked = {false};
        final Runnable unlock = () -> {
            if (unlocked[0]) return;
            unlocked[0] = true;
            accept.setEnabled(true);
            accept.setAlpha(1f);
        };

        scroll.getViewTreeObserver().addOnScrollChangedListener(
                new ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        View child = scroll.getChildAt(0);
                        if (child == null) return;
                        int slack = UclDialog.px(activity, 24);
                        if (child.getBottom() - (scroll.getHeight() + scroll.getScrollY()) <= slack) {
                            unlock.run();
                            scroll.getViewTreeObserver().removeOnScrollChangedListener(this);
                        }
                    }
                });
        /* nothing to scroll means nothing to gate on */
        scroll.post(() -> {
            View child = scroll.getChildAt(0);
            if (child == null) return;
            if (child.getHeight() <= scroll.getHeight()) unlock.run();
        });
    }

    /* ------------------------------------------------------------------ */
    /* 2. the source itself was typed by the user                          */
    /* ------------------------------------------------------------------ */

    public static void confirmSource(Activity activity, String root, final Callback onNext) {
        if (activity == null || activity.isFinishing()) return;
        confirm(activity,
                activity.getString(R.string.ucl_dlg_source_title),
                activity.getString(R.string.ucl_dlg_source_body,
                        root == null ? "" : root),
                activity.getString(R.string.ucl_dlg_continue),
                UclDialog.STYLE_GREEN,
                onNext);
    }

    /* ------------------------------------------------------------------ */
    /* 3. legal risk, own responsibility                                   */
    /* ------------------------------------------------------------------ */

    public static void confirmRisk(Activity activity, final Callback onNext) {
        if (activity == null || activity.isFinishing()) return;
        /* the one red key in this chain: the window is a risk acknowledgement,
         * so the affirmative answer is the one that needs finding, not the
         * recommended one */
        confirm(activity,
                activity.getString(R.string.ucl_dlg_risk_title),
                activity.getString(R.string.ucl_dlg_risk_body),
                activity.getString(R.string.ucl_dlg_risk_ok),
                UclDialog.STYLE_RED,
                onNext);
    }

    /* ------------------------------------------------------------------ */
    /* 4. last stop before the picker                                      */
    /* ------------------------------------------------------------------ */

    /**
     * The last stop before the picker.
     *
     * <p>The "source" line shows the address itself, not the name the mirror
     * advertises in its manifest. Most mirrors advertise no name at all, and a
     * blank one used to fall through to a dash -- a dash is not a source, and
     * the one thing the user has to be able to check here is which address the
     * next 920 files are coming from. The address is always known, so it is
     * always what gets shown.
     */
    public static void finalConfirm(Activity activity, String address, int count,
                                    final Callback onNext) {
        if (activity == null || activity.isFinishing()) return;
        confirm(activity,
                activity.getString(R.string.ucl_dlg_final_title),
                activity.getString(R.string.ucl_dlg_final_body,
                        address == null ? "" : address, count),
                activity.getString(R.string.ucl_dlg_final_ok),
                UclDialog.STYLE_GREEN,
                onNext);
    }

    /** Cancel on the left, the way the reference pairs its two keys. */
    private static void confirm(Activity activity, CharSequence title, CharSequence body,
                                CharSequence positive, int positiveStyle, final Callback onNext) {
        UclDialog.build(activity)
                .title(title)
                .message(body)
                .rowLayout(true)
                .key(activity.getString(R.string.ucl_dlg_cancel), UclDialog.STYLE_LIGHT, null)
                .key(positive, positiveStyle, () -> {
                    if (onNext != null) onNext.onDone();
                })
                .show();
    }

    /* ------------------------------------------------------------------ */
    /* plain notices                                                       */
    /* ------------------------------------------------------------------ */

    /** A one-key notice. Used instead of a Toast wherever the user must act. */
    public static void notice(Activity activity, int titleRes, CharSequence body,
                              int positiveRes, final Callback onOk) {
        if (activity == null || activity.isFinishing()) return;
        UclDialog.build(activity)
                .title(activity.getString(titleRes))
                .message(body)
                .key(activity.getString(positiveRes), UclDialog.STYLE_LIGHT, () -> {
                    if (onOk != null) onOk.onDone();
                })
                .show();
    }
}

package net.kdt.pojavlaunch.mirrors;

import android.app.Activity;
import android.content.Context;
import android.text.Html;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.ShowErrorActivity;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;
import net.kdt.pojavlaunch.mcui.UclAlert;
import net.kdt.pojavlaunch.mcui.UclDialog;
import net.kdt.pojavlaunch.mcui.UclMirrorSource;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

public class MirrorTamperedException extends Exception implements ContextExecutorTask {
    // Do not change. Android really hates when this value changes for some reason.
    private static final long serialVersionUID = -7482301619612640658L;
    @Override
    public void executeWithActivity(Activity activity) {
        UclDialog.Builder builder = UclAlert.builder(activity);
        if (builder == null) return;
        builder.title(activity.getString(R.string.dl_tampered_manifest_title))
                .message(Html.fromHtml(activity.getString(R.string.dl_tampered_manifest)))
                .scrollable(true)
                .key(activity.getString(R.string.ucl_dlg_cancel), UclDialog.STYLE_LIGHT, null)
                .key(activity.getString(R.string.dl_turn_off_manifest_checks),
                        UclDialog.STYLE_LIGHT, () -> {
                            LauncherPreferences.DEFAULT_PREF.edit()
                                    .putBoolean("verifyManifest", false).apply();
                            LauncherPreferences.PREF_VERIFY_MANIFEST = false;
                        })
                .key(activity.getString(R.string.dl_switch_to_official_site),
                        UclDialog.STYLE_GREEN, () -> {
                            /* There is no official source to switch to any more.
                             * Dropping the configured mirror is the safe
                             * equivalent: nothing will download again until the
                             * user supplies a source they trust. */
                            UclMirrorSource.clear();
                        });
        UclDialog dialog = builder.show();
        ShowErrorActivity.installRemoteDialogHandling(activity, dialog);
    }

    @Override
    public void executeWithApplication(Context context) {}
}

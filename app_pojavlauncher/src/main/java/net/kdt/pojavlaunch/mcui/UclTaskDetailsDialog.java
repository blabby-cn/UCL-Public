package net.kdt.pojavlaunch.mcui;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What a finished, interrupted or failed download actually did.
 *
 * <p>The row in the queue carries one line, and that line is deliberately one
 * line: the queue is narrow. Everything else -- the reason it failed, how many
 * files the cleanup removed, when it started, what it is missing -- belongs
 * here, one tap away, instead of being an action the row cannot afford.
 *
 * <p>The window is the only place a job can be resumed or retried from, so the
 * key row carries that action rather than the queue row that opened it.
 */
public final class UclTaskDetailsDialog {

    private UclTaskDetailsDialog() {
    }

    public static void open(Activity activity, UclDownloadManager.DownloadTask task) {
        if (activity == null || activity.isFinishing() || task == null) return;

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        row(activity, body, task.javaRuntime
                        ? R.string.ucl_task_detail_runtime : R.string.ucl_task_detail_version,
                task.versionId, R.color.ucl_text_primary, true);
        row(activity, body, R.string.ucl_task_detail_state, stateText(activity, task),
                stateColor(task), false);

        if (task.progress > 0 && task.state != UclDownloadManager.STATE_DONE) {
            row(activity, body, R.string.ucl_task_detail_progress,
                    activity.getString(R.string.ucl_dlp_percent, task.progress),
                    R.color.ucl_text_primary, false);
        }
        if (task.state == UclDownloadManager.STATE_DOWNLOADING) {
            if (task.speedBytesPerSecond > 0d) {
                row(activity, body, R.string.ucl_task_detail_speed,
                        formatSpeed(task.speedBytesPerSecond), R.color.ucl_text_primary, false);
            }
            if (!TextUtils.isEmpty(task.currentFile)) {
                row(activity, body, R.string.ucl_task_detail_file, task.currentFile,
                        R.color.ucl_text_secondary, false);
            }
        }
        if (!TextUtils.isEmpty(task.progressText)
                && task.state == UclDownloadManager.STATE_FAILED) {
            row(activity, body, R.string.ucl_task_detail_reason, task.progressText,
                    R.color.ucl_dlt_bad, false);
        }
        if (task.purgedFiles > 0) {
            row(activity, body, R.string.ucl_task_detail_purged,
                    activity.getString(R.string.ucl_task_detail_files, task.purgedFiles),
                    R.color.ucl_text_secondary, false);
        }
        if (task.startedAt > 0L) {
            String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(task.startedAt));
            row(activity, body, R.string.ucl_task_detail_started, when,
                    R.color.ucl_text_secondary, false);
        }
        if (task.state == UclDownloadManager.STATE_INTERRUPTED) {
            TextView note = new TextView(activity);
            note.setText(R.string.ucl_task_detail_resume_note);
            note.setTextColor(ContextCompat.getColor(activity, R.color.ucl_text_muted));
            note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
            note.setLineSpacing(0f, 1.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(8 * activity.getResources().getDisplayMetrics().density);
            note.setLayoutParams(lp);
            body.addView(note);
        }
        UclTypeface.apply(body);

        UclDialog.Builder builder = UclDialog.build(activity)
                .title(activity.getString(R.string.ucl_task_details_title))
                .body(body, true)
                .rowLayout(true);

        final boolean resumable = task.state == UclDownloadManager.STATE_INTERRUPTED;
        final boolean retryable = task.state == UclDownloadManager.STATE_FAILED
                || task.state == UclDownloadManager.STATE_CANCELLED;
        if (resumable || retryable) {
            builder.key(activity.getString(resumable
                            ? R.string.ucl_task_action_resume
                            : R.string.ucl_task_action_retry),
                    UclDialog.STYLE_GREEN, () -> {
                        if (resumable) {
                            UclDownloadManager.getInstance().resumeDownload(activity, task);
                            UclDownloadProgressDialog.show(activity, task);
                        } else {
                            /* a retry of a runtime job has no version list to
                             * look up; it starts the same archive again, and
                             * the partial file it left makes that cheap */
                            if (task.javaRuntime) {
                                UclDownloadManager.getInstance()
                                        .startJavaDownload(activity, task.javaMajor);
                            } else {
                                UclDownloadManager.getInstance()
                                        .startDownload(activity, task.versionId);
                            }
                            UclDownloadManager.DownloadTask fresh =
                                    UclDownloadManager.getInstance().getActiveTask();
                            if (fresh != null) UclDownloadProgressDialog.show(activity, fresh);
                        }
                    });
        }
        builder.key(activity.getString(R.string.ucl_dlg_ok), UclDialog.STYLE_LIGHT, null).show();
    }

    /** The same wording the queue row uses, so the two never disagree. */
    public static String stateText(Activity activity, UclDownloadManager.DownloadTask task) {
        switch (task.state) {
            case UclDownloadManager.STATE_DONE:
                return activity.getString(R.string.ucl_task_done);
            case UclDownloadManager.STATE_CANCELLED:
                return activity.getString(R.string.ucl_task_cancelled);
            case UclDownloadManager.STATE_FAILED:
                return activity.getString(R.string.ucl_task_failed);
            case UclDownloadManager.STATE_INTERRUPTED:
                return activity.getString(R.string.ucl_task_interrupted, task.progress);
            default:
                return activity.getString(R.string.ucl_task_downloading, task.progress);
        }
    }

    private static int stateColor(UclDownloadManager.DownloadTask task) {
        switch (task.state) {
            case UclDownloadManager.STATE_DONE:
                return R.color.ucl_text_secondary;
            case UclDownloadManager.STATE_FAILED:
                return R.color.ucl_dlt_bad;
            case UclDownloadManager.STATE_INTERRUPTED:
                return R.color.ucl_dlt_bad;
            case UclDownloadManager.STATE_CANCELLED:
                return R.color.ucl_text_muted;
            default:
                return R.color.ucl_accent_light;
        }
    }

    private static void row(Activity activity, LinearLayout body, int labelRes,
                            CharSequence value, int colorRes, boolean bold) {
        float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lineParams.topMargin = Math.round(6 * density);
        line.setLayoutParams(lineParams);

        TextView label = new TextView(activity);
        label.setText(labelRes);
        label.setTextColor(ContextCompat.getColor(activity, R.color.ucl_text_muted));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                Math.round(76 * density), LinearLayout.LayoutParams.WRAP_CONTENT);
        label.setLayoutParams(labelParams);
        line.addView(label);

        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextColor(ContextCompat.getColor(activity, colorRes));
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        if (bold) text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setLineSpacing(0f, 1.2f);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(textParams);
        line.addView(text);

        body.addView(line);
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1024d * 1024d) {
            return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024d * 1024d));
        }
        if (bytesPerSecond >= 1024d) {
            return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024d);
        }
        return String.format(Locale.ROOT, "%.0f B/s", bytesPerSecond);
    }
}

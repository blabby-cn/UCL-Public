package net.kdt.pojavlaunch.mcui;

import android.app.Activity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

import java.util.Locale;

/**
 * The window that shows what a running download is actually doing: the stage,
 * the file currently on the wire, the percentage, the speed, and a cancel
 * button that stops the job immediately.
 *
 * <p>It follows one task and lives only as long as that task is running. "Run
 * in background" closes the window without touching the job -- the row in the
 * task list keeps counting, and tapping that row brings the window back.
 */
public final class UclDownloadProgressDialog implements UclDownloadManager.TaskUpdateListener {

    private final Activity mActivity;
    private final UclDownloadManager.DownloadTask mTask;
    private UclDialog mDialog;
    private ProgressBar mBar;
    private TextView mPercent;
    private TextView mStage;
    private TextView mFile;
    private TextView mSpeed;
    private TextView mParallel;
    private boolean mClosed;

    private UclDownloadProgressDialog(Activity activity, UclDownloadManager.DownloadTask task) {
        mActivity = activity;
        mTask = task;
    }

    /** Opens the window for a running task. Does nothing for a finished one. */
    public static void show(Activity activity, UclDownloadManager.DownloadTask task) {
        if (activity == null || activity.isFinishing() || task == null) return;
        if (task.state != UclDownloadManager.STATE_DOWNLOADING) return;
        new UclDownloadProgressDialog(activity, task).open();
    }

    private void open() {
        View content = LayoutInflater.from(mActivity)
                .inflate(R.layout.ucl_download_body, null);
        mBar = content.findViewById(R.id.ucl_dlp_bar);
        mPercent = content.findViewById(R.id.ucl_dlp_percent);
        mStage = content.findViewById(R.id.ucl_dlp_stage);
        mFile = content.findViewById(R.id.ucl_dlp_file);
        mSpeed = content.findViewById(R.id.ucl_dlp_speed);
        mParallel = content.findViewById(R.id.ucl_dlp_parallel);

        /* no byte total is tracked by the downloader, and inventing one would
         * be worse than leaving the line out */
        content.findViewById(R.id.ucl_dlp_amount).setVisibility(View.GONE);

        /* the plate draws its own title strip and its own key row, so this
         * window only supplies the live numbers; adding a framework title on
         * top would stack a second strip above the measured one */
        mDialog = UclDialog.build(mActivity)
                .title(mActivity.getString(R.string.ucl_dlp_title, mTask.versionId))
                .body(content, false)
                .closeMark(false)
                /* Back and outside taps must not silently hide a running job:
                 * the two keys are the only ways out, so neither can be missed */
                .cancelable(false)
                .rowLayout(true)
                .key(mActivity.getString(R.string.ucl_dlp_cancel), UclDialog.STYLE_RED, () -> {
                    UclDownloadManager.getInstance().cancelDownload(mTask);
                })
                .key(mActivity.getString(R.string.ucl_dlp_background), UclDialog.STYLE_LIGHT, null)
                .show();

        mDialog.setOnDismissListener(dialog -> {
            mClosed = true;
            UclDownloadManager.getInstance().removeListener(this);
        });

        UclDownloadManager.getInstance().addListener(this);
        refresh();
    }

    private void dismiss() {
        if (mClosed) return;
        mClosed = true;
        if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
        UclDownloadManager.getInstance().removeListener(this);
    }

    @Override
    public void onTasksUpdated() {
        if (mClosed) return;
        /* done, failed or cancelled: the row carries the outcome, so the window
         * gets out of the way instead of sitting on a frozen percentage */
        if (mTask.state != UclDownloadManager.STATE_DOWNLOADING) {
            dismiss();
            return;
        }
        refresh();
    }

    private void refresh() {
        if (mBar == null) return;
        int percent = Math.max(0, Math.min(100, mTask.progress));
        mBar.setProgress(percent);
        mPercent.setText(mActivity.getString(R.string.ucl_dlp_percent, percent));

        CharSequence stage = mTask.stageText;
        if (TextUtils.isEmpty(stage)) stage = mTask.progressText;
        if (TextUtils.isEmpty(stage)) stage = mActivity.getString(R.string.ucl_dlp_connecting);
        mStage.setText(stage);

        String file = mTask.currentFile;
        if (TextUtils.isEmpty(file)) {
            mFile.setVisibility(View.GONE);
        } else {
            mFile.setVisibility(View.VISIBLE);
            mFile.setText(mTask.activeFileCount > 1
                    ? mActivity.getString(R.string.ucl_dlp_files_more, file, mTask.activeFileCount)
                    : file);
        }

        double speed = mTask.speedBytesPerSecond;
        mSpeed.setText(speed <= 0d
                ? mActivity.getString(R.string.ucl_dlp_speed_zero)
                : mActivity.getString(R.string.ucl_dlp_speed, formatSpeed(speed)));

        /* how many files are in flight: the honest answer to "why is this
         * slow", and the one number that changes the moment a mirror starts
         * throttling a single socket */
        if (mParallel != null) {
            int files = Math.max(0, mTask.activeFileCount);
            mParallel.setText(files <= 1
                    ? mActivity.getString(R.string.ucl_dlp_parallel_none)
                    : mActivity.getString(R.string.ucl_dlp_parallel_files, files));
        }
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1024d * 1024d) {
            return String.format(Locale.US, "%.2f MB/s", bytesPerSecond / (1024d * 1024d));
        }
        if (bytesPerSecond >= 1024d) {
            return String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024d);
        }
        return String.format(Locale.US, "%.0f B/s", bytesPerSecond);
    }
}

package net.kdt.pojavlaunch.mcui;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

/**
 * The download settings window.
 *
 * <p>Three groups, one choice each, everything visible at once: the values are
 * few enough that a nested list would only add taps. A pick is written through
 * immediately, so closing the window is not a commit step and there is no way
 * to leave it thinking a value was saved when it was not.
 *
 * <p>Thirty-two and sixty-four threads ask first. They are the two settings that
 * can make a download slower instead of faster, and the reason is not obvious
 * from the number alone: each thread holds a connection open, and a mirror that
 * throttles per connection will answer a larger burst with resets.
 */
public final class UclDownloadSettingsDialog {

    private interface OnPick {
        void onPick(int value);
    }

    private UclDownloadSettingsDialog() {
    }

    public static void open(Activity activity) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        fill(activity, column);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        UclDialog.build(activity)
                .title(activity.getString(R.string.ucl_dl_settings_row))
                .body(scroll, true)
                .scrollHeightFraction(0.5f)
                .key(activity.getString(R.string.ucl_settings_close),
                        UclDialog.STYLE_LIGHT, null)
                .show();
    }

    /** Rebuilds the three groups in place; also what a pick calls to repaint. */
    private static void fill(Activity activity, LinearLayout column) {
        column.removeAllViews();
        Runnable refresh = () -> fill(activity, column);

        group(activity, column, R.string.ucl_settings_tasks,
                UclDownloadSettings.TASK_CHOICES,
                UclDownloadSettings.tasks(activity), false, value -> {
                    UclDownloadSettings.setTasks(activity, value);
                    refresh.run();
                });

        group(activity, column, R.string.ucl_settings_speed,
                UclDownloadSettings.SPEED_CHOICES,
                UclDownloadSettings.speedMbps(activity), true, value -> {
                    UclDownloadSettings.setSpeedMbps(activity, value);
                    refresh.run();
                });

        group(activity, column, R.string.ucl_settings_threads,
                UclDownloadSettings.THREAD_CHOICES,
                UclDownloadSettings.threads(activity), false, value -> {
                    if (value >= 32) {
                        confirmThreads(activity, value, () -> {
                            UclDownloadSettings.setThreads(activity, value);
                            refresh.run();
                        });
                    } else {
                        UclDownloadSettings.setThreads(activity, value);
                        refresh.run();
                    }
                });

        TextView note = new TextView(activity);
        note.setText(R.string.ucl_settings_note);
        note.setTextColor(ContextCompat.getColor(activity, R.color.ucl_text_muted));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(activity, 14);
        lp.bottomMargin = dp(activity, 4);
        note.setLayoutParams(lp);
        column.addView(note);
    }

    private static void group(Activity activity, LinearLayout column, int titleRes,
                              int[] values, int current, boolean speed, OnPick onPick) {
        TextView title = new TextView(activity);
        title.setText(titleRes);
        title.setTextColor(ContextCompat.getColor(activity, R.color.ucl_text_secondary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(activity, column.getChildCount() == 0 ? 4 : 14);
        tlp.bottomMargin = dp(activity, 6);
        tlp.leftMargin = dp(activity, 2);
        title.setLayoutParams(tlp);
        column.addView(title);

        for (int i = 0; i < values.length; i++) {
            final int value = values[i];
            boolean picked = value == current;

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setFocusable(true);
            row.setSelected(picked);
            row.setBackgroundResource(R.drawable.ucl_nav_row_bg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 44));
            rlp.bottomMargin = dp(activity, 2);
            row.setLayoutParams(rlp);
            row.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);

            TextView label = new TextView(activity);
            label.setText(speed && value == 0
                    ? activity.getString(R.string.ucl_settings_unlimited)
                    : speed
                    ? activity.getString(R.string.ucl_settings_mb, value)
                    : activity.getString(R.string.ucl_settings_count, value));
            label.setTextColor(ContextCompat.getColor(activity,
                    picked ? R.color.ucl_accent_light : R.color.ucl_row_idle_text));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(llp);
            row.addView(label);

            ImageView tick = new ImageView(activity);
            tick.setImageResource(R.drawable.ucl_ic_check);
            tick.setVisibility(picked ? View.VISIBLE : View.INVISIBLE);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    dp(activity, 16), dp(activity, 16));
            tick.setLayoutParams(ilp);
            row.addView(tick);

            row.setOnClickListener(v -> onPick.onPick(value));
            column.addView(row);
        }
    }

    /** Asks before a thread count that can backfire. */
    private static void confirmThreads(Activity activity, int threads, Runnable onContinue) {
        UclDialog.build(activity)
                .title(activity.getString(R.string.ucl_settings_thread_warn_title))
                .message(activity.getString(R.string.ucl_settings_thread_warn_body, threads))
                .key(activity.getString(R.string.ucl_settings_close),
                        UclDialog.STYLE_LIGHT, null)
                .key(activity.getString(R.string.ucl_settings_continue),
                        UclDialog.STYLE_GREEN, onContinue)
                .rowLayout(true)
                .show();
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

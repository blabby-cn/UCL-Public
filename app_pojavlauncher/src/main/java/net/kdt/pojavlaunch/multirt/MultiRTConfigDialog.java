package net.kdt.pojavlaunch.multirt;

import android.annotation.SuppressLint;
import android.content.Context;
import net.kdt.pojavlaunch.mcui.UclAlert;
import net.kdt.pojavlaunch.mcui.UclDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

public class MultiRTConfigDialog {
    private Context mContext;
    private ActivityResultLauncher<Object> mInstallLauncher;
    private RecyclerView mDialogView;
    private RTRecyclerViewAdapter mAdapter;

    /** Show the dialog, refreshes the adapter data before showing it */
    public void show(){
        if (mContext == null) return;
        refresh();
        UclDialog.Builder builder = UclAlert.builder(mContext);
        if (builder == null) return;
        /* the list outlives one window: asking for it twice must not hand the
         * view to a second parent */
        if (mDialogView.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) mDialogView.getParent()).removeView(mDialogView);
        }
        mDialogView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (mContext.getResources().getDisplayMetrics().heightPixels * 0.5f)));

        final UclDialog[] holder = new UclDialog[1];
        holder[0] = builder.title(mContext.getString(R.string.multirt_config_title))
                .body(mDialogView, false)
                .key(mContext.getString(R.string.multirt_delete_runtime), UclDialog.STYLE_LIGHT,
                        () -> {
                            boolean isEditing = !mAdapter.getIsEditing();
                            mAdapter.setIsEditing(isEditing);
                            android.widget.TextView key = holder[0].key(0);
                            if (key != null) {
                                key.setText(isEditing ? R.string.multirt_config_setdefault
                                        : R.string.multirt_delete_runtime);
                            }
                        }, false)
                .key(mContext.getString(R.string.multirt_config_add), UclDialog.STYLE_GREEN,
                        () -> mInstallLauncher.launch(null))
                .show();
    }

    @SuppressLint("NotifyDataSetChanged") //only used to completely refresh the list, it is necessary
    public void refresh() {
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
    }

    /** Build the dialog behavior and style */
    public void prepare(Context activity, ActivityResultLauncher<Object> installJvmLauncher) {
        mContext = activity;
        mInstallLauncher = installJvmLauncher;
        mDialogView = new RecyclerView(activity);
        mDialogView.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false));
        mAdapter = new RTRecyclerViewAdapter();
        mDialogView.setAdapter(mAdapter);
    }
}

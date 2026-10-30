package net.kdt.pojavlaunch.profiles;

import static net.kdt.pojavlaunch.extra.ExtraCore.getValue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ExpandableListView;


import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.mcui.UclAlert;
import net.kdt.pojavlaunch.mcui.UclDialog;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.extra.ExtraConstants;

public class VersionSelectorDialog {
    public static void open(Context context, boolean hideCustomVersions, VersionSelectorListener listener) {
        UclDialog.Builder builder = UclAlert.builder(context);
        if (builder == null) return;

        ExpandableListView expandableListView = (ExpandableListView) LayoutInflater.from(context)
                .inflate(R.layout.dialog_expendable_list_view , null);
        JMinecraftVersionList jMinecraftVersionList = (JMinecraftVersionList) getValue(ExtraConstants.RELEASE_TABLE);
        JMinecraftVersionList.Version[] versionArray;
        if(jMinecraftVersionList == null || jMinecraftVersionList.versions == null) versionArray = new JMinecraftVersionList.Version[0];
        else versionArray = jMinecraftVersionList.versions;
        VersionListAdapter adapter = new VersionListAdapter(versionArray, hideCustomVersions, context);

        expandableListView.setAdapter(adapter);
        expandableListView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (context.getResources().getDisplayMetrics().heightPixels * 0.6f)));

        UclDialog dialog = builder.title(context.getString(R.string.ucl_version_picker_title))
                .body(expandableListView, false)
                .closeMark(true)
                .show();

        expandableListView.setOnChildClickListener((parent, v1, groupPosition, childPosition, id) -> {
            String version = adapter.getChild(groupPosition, childPosition);
            listener.onVersionSelected(version, adapter.isSnapshotSelected(groupPosition));
            dialog.dismiss();
            return true;
        });
    }
}

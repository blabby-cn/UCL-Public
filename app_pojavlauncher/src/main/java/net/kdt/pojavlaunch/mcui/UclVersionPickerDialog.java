package net.kdt.pojavlaunch.mcui;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UCL styled version picker used by the version and download pages when the
 * user wants to install a new Minecraft version.
 *
 * <p>The list is the body of a {@link UclDialog}, so the window carries the
 * same measured plate and key row as every other confirmation in the shell
 * instead of an AppCompat title strip.
 *
 * <p>The list runs to several hundred entries, so the title strip carries the
 * filter field itself: what is typed there narrows the rows below on every
 * keystroke, matching on substrings so "11" finds both 1.11 and 1.8.11. There
 * is no separate search window and no key to press.
 */
public class UclVersionPickerDialog {

    public interface Listener {
        void onVersionPicked(String versionId);
    }

    public static void open(Activity activity, Listener listener) {
        /* No built-in repository: without a user supplied source there is no
         * list to pick from, so say so instead of leaving an empty dialog. */
        if (!net.kdt.pojavlaunch.mirrors.DownloadMirror.hasSource()) {
            Toast.makeText(activity, R.string.ucl_mirror_required, Toast.LENGTH_LONG).show();
            return;
        }
        JMinecraftVersionList versionList =
                (JMinecraftVersionList) ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if (versionList == null || versionList.versions == null) {
            Toast.makeText(activity, R.string.ucl_version_list_loading, Toast.LENGTH_SHORT).show();
            new AsyncVersionList().getVersionList(versions -> {
                if (versions != null && versions.versions != null) {
                    ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions);
                    Tools.runOnUiThread(() -> show(activity, listener));
                }
            }, false);
            return;
        }
        show(activity, listener);
    }

    private static void show(Activity activity, Listener listener) {
        JMinecraftVersionList versionList =
                (JMinecraftVersionList) ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if (versionList == null || versionList.versions == null) {
            Toast.makeText(activity, R.string.ucl_version_list_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        /* two lists: the whole table, and the rows the filter field lets through.
         * The adapter reads the second one, which is what keeps typing cheap --
         * nothing is rebuilt, the backing list is refilled in place. */
        final List<JMinecraftVersionList.Version> all = new ArrayList<>();
        for (JMinecraftVersionList.Version version : versionList.versions) {
            if (version != null && version.id != null) all.add(version);
        }
        final List<JMinecraftVersionList.Version> shown = new ArrayList<>(all);

        /* the tap handler is installed before the window is built so the row
         * and its reaction exist in the same pass; the holder is how it reaches
         * the window it has to close */
        final UclDialog[] dialogHolder = new UclDialog[1];
        ListView listView = buildList(activity, shown, version -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            listener.onVersionPicked(version.id);
        });
        final VersionAdapter adapter = (VersionAdapter) listView.getAdapter();

        TextWatcher filter = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (JMinecraftVersionList.Version version : all) {
                    if (query.isEmpty() || version.id.toLowerCase(Locale.ROOT).contains(query)) {
                        shown.add(version);
                    }
                }
                adapter.notifyDataSetChanged();
            }
        };

        final UclDialog dialog = UclDialog.build(activity)
                .title(activity.getString(R.string.ucl_version_picker_title))
                .body(listView, false)
                .titleField(activity.getString(R.string.ucl_version_search_hint), filter)
                .key(activity.getString(R.string.ucl_dlg_cancel), UclDialog.STYLE_LIGHT, null)
                .show();
        dialogHolder[0] = dialog;
    }

    private static ListView buildList(Activity activity, List<JMinecraftVersionList.Version> versions,
                                      VersionAdapter.OnVersionTap onTap) {
        ListView listView = new ListView(activity);
        /* the whole app is dark, and the window behind this list is the shared
         * dialog plate: a white sheet here was the brightest object on screen */
        listView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        /* the rows carry no touch handling of their own, so the row highlight
         * comes from the list selector: it is drawn under the child, which is
         * transparent until it is the selected one */
        listView.setSelector(R.drawable.ucl_nav_row_selected);
        listView.setDrawSelectorOnTop(false);
        listView.setItemsCanFocus(false);
        listView.setAdapter(new VersionAdapter(activity, versions));
        /* wrap_content would let the list grow past the plate, so it is capped
         * against the screen the same way the disclaimer body is */
        int screen = activity.getResources().getDisplayMetrics().heightPixels;
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(UclDialog.px(activity, 180 * 3), (int) (screen * 0.42f))));
        listView.setOnItemClickListener((parent, view, position, id) ->
                onTap.onTap(versions.get(position)));
        return listView;
    }

    private static class VersionAdapter extends BaseAdapter {
        interface OnVersionTap {
            void onTap(JMinecraftVersionList.Version version);
        }

        private final Context mContext;
        private final List<JMinecraftVersionList.Version> mVersions;

        VersionAdapter(Context context, List<JMinecraftVersionList.Version> versions) {
            mContext = context;
            mVersions = versions;
        }

        @Override
        public int getCount() {
            return mVersions.size();
        }

        @Override
        public JMinecraftVersionList.Version getItem(int position) {
            return mVersions.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = View.inflate(mContext, R.layout.ucl_version_row, null);
                UclTypeface.apply(row);
            }
            JMinecraftVersionList.Version version = getItem(position);
            TextView id = row.findViewById(R.id.ucl_version_row_id);
            TextView date = row.findViewById(R.id.ucl_version_row_date);
            id.setText(version.id);
            String release = version.releaseTime;
            if (release != null && release.length() >= 10) release = release.substring(0, 10);
            date.setText(release);
            return row;
        }
    }
}

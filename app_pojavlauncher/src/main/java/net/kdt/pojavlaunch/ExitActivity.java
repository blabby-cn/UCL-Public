package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Tools.shareLog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import net.kdt.pojavlaunch.mcui.UclDialog;

@Keep
public class ExitActivity extends AppCompatActivity {

    @SuppressLint("StringFormatInvalid") //invalid on some translations but valid on most, cant fix that atm
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int code = -1;
        boolean isSignal = false;
        Bundle extras = getIntent().getExtras();
        if(extras != null) {
            code = extras.getInt("code",-1);
            isSignal = extras.getBoolean("isSignal", false);
        }

        int message = isSignal ? R.string.mcn_signal_title : R.string.mcn_exit_title;

        UclDialog dialog = UclDialog.build(this)
                .title(getString(R.string.ucl_launch_exit_title))
                .message(getString(message, code))
                .rowLayout(true)
                .closeMark(false)
                .cancelable(false)
                .key(getString(R.string.main_share_logs), UclDialog.STYLE_LIGHT, () -> shareLog(this))
                .key(getString(R.string.ucl_dlg_ok), UclDialog.STYLE_LIGHT, null)
                .show();
        /* this activity exists only to show the window: closing it closes the
         * activity too, whatever closed it */
        dialog.setOnDismissListener(d -> ExitActivity.this.finish());
    }

    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        Intent i = new Intent(ctx,ExitActivity.class);
        i.putExtra("code",code);
        i.putExtra("isSignal", isSignal);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

}

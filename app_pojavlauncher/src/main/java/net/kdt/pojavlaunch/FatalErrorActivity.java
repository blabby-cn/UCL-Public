package net.kdt.pojavlaunch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import net.kdt.pojavlaunch.mcui.UclDialog;

public class FatalErrorActivity extends AppCompatActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		Bundle extras = getIntent().getExtras();
		if(extras == null) {
			finish();
			return;
		}
		boolean storageAllow = extras.getBoolean("storageAllow", false);
		Throwable throwable = (Throwable) extras.getSerializable("throwable");
		final String stackTrace = throwable != null ? Tools.printToString(throwable) : "<null>";
		String strSavePath = extras.getString("savePath");
		String errHeader = storageAllow ?
			"Crash stack trace saved to " + strSavePath + "." :
			"Storage permission is required to save crash stack trace!";
		
		UclDialog.build(this)
			.title(getString(R.string.error_fatal))
			.message(errHeader + "\n\n" + stackTrace)
			.scrollable(true)
			.rowLayout(true)
			.closeMark(false)
			.cancelable(false)
			.key(getString(android.R.string.copy), UclDialog.STYLE_LIGHT, () -> {
				ClipboardManager mgr = (ClipboardManager) FatalErrorActivity.this.getSystemService(CLIPBOARD_SERVICE);
				mgr.setPrimaryClip(ClipData.newPlainText("error", stackTrace));

				finish();
			})
			.key(getString(R.string.global_restart), UclDialog.STYLE_LIGHT,
					() -> startActivity(new Intent(FatalErrorActivity.this, LauncherActivity.class)))
			.key(getString(R.string.ucl_dlg_ok), UclDialog.STYLE_LIGHT, this::finish)
			.show();
	}

	public static void showError(Context ctx, String savePath, boolean storageAllow, Throwable th) {
		Intent fatalErrorIntent = new Intent(ctx, FatalErrorActivity.class);
		fatalErrorIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
		fatalErrorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		fatalErrorIntent.putExtra("throwable", th);
		fatalErrorIntent.putExtra("savePath", savePath);
		fatalErrorIntent.putExtra("storageAllow", storageAllow);
		ctx.startActivity(fatalErrorIntent);
	}
}

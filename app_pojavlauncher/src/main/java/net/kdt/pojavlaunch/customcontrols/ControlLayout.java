package net.kdt.pojavlaunch.customcontrols;

import static android.content.Context.INPUT_METHOD_SERVICE;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import static org.lwjgl.glfw.CallbackBridge.isGrabbing;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonSyntaxException;
import com.kdt.pickafile.FileListView;
import com.kdt.pickafile.FileSelectedListener;

import net.kdt.pojavlaunch.MinecraftGLSurface;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlButton;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlDrawer;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlJoystick;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlSubButton;
import net.kdt.pojavlaunch.customcontrols.handleview.ActionRow;
import net.kdt.pojavlaunch.customcontrols.handleview.ControlHandleView;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlSideDialog;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.kdt.pojavlaunch.mcui.UclAlert;
import net.kdt.pojavlaunch.mcui.UclDialog;
import android.widget.LinearLayout;
import android.view.ViewGroup;

public class ControlLayout extends FrameLayout {
	protected CustomControls mLayout;
	/* Accessible when inside the game by ControlInterface implementations, cached for perf. */
	private MinecraftGLSurface mGameSurface = null;

	/* Cache to buttons for performance purposes */
	private List<ControlInterface> mButtons;
	private boolean mModifiable = false;
	private boolean mIsModified;
	private boolean mControlVisible = false;

	private EditControlSideDialog mControlDialog = null;
	private ControlHandleView mHandleView;
	private ControlButtonMenuListener mMenuListener;
	public ActionRow mActionRow = null;
	public String mLayoutFileName;

	public ControlLayout(Context ctx) {
		super(ctx);
	}

	public ControlLayout(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
	}


	public void loadLayout(String jsonPath) throws IOException, JsonSyntaxException {
		CustomControls layout = LayoutConverter.loadAndConvertIfNecessary(jsonPath);
		if(layout != null) {
			loadLayout(layout);
			updateLoadedFileName(jsonPath);
			return;
		}

		throw new IOException("Unsupported control layout version");
	}

	public void loadLayout(CustomControls controlLayout) {
		boolean sanitizedModified = false;
		if(controlLayout != null) {
			sanitizedModified = LayoutSanitizer.sanitizeLayout(controlLayout);
		}
		if(mActionRow == null){
			mActionRow = new ActionRow(getContext());
			addView(mActionRow);
		}

		removeAllButtons();
		if(mLayout != null) {
			mLayout.mControlDataList = null;
			mLayout = null;
		}

		System.gc();
		mapTable.clear();

		// Cleanup buttons only when input layout is null
		if (controlLayout == null) return;

		mLayout = controlLayout;
		

		// Joystick(s) first, to workaround the touch dispatch
		for(ControlJoystickData joystick : mLayout.mJoystickDataList){
			addJoystickView(joystick);
		}

		//CONTROL BUTTON
		for (ControlData button : controlLayout.mControlDataList) {
			addControlView(button);
		}

		//CONTROL DRAWER
		for(ControlDrawerData drawerData : controlLayout.mDrawerDataList){
			ControlDrawer drawer = addDrawerView(drawerData);
			if(mModifiable) drawer.areButtonsVisible = true;
		}

		mLayout.scaledAt = LauncherPreferences.PREF_BUTTONSIZE;

		setModified(sanitizedModified);
		mButtons = null;
		getButtonChildren(); // Force refresh
	} // loadLayout

	//CONTROL BUTTON
	public void addControlButton(ControlData controlButton) {
		mLayout.mControlDataList.add(controlButton);
		addControlView(controlButton);
	}

	private void addControlView(ControlData controlButton) {
		final ControlButton view = new ControlButton(this, controlButton);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);

		setModified(true);
	}

	// CONTROL DRAWER
	public void addDrawer(ControlDrawerData drawerData){
		mLayout.mDrawerDataList.add(drawerData);
		addDrawerView();
	}

	private void addDrawerView(){
		addDrawerView(null);
	}

	private ControlDrawer addDrawerView(ControlDrawerData drawerData){

		final ControlDrawer view = new ControlDrawer(this,drawerData == null ? mLayout.mDrawerDataList.get(mLayout.mDrawerDataList.size()-1) : drawerData);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);
		//CONTROL SUB BUTTON
		for (ControlData subButton : view.getDrawerData().buttonProperties) {
			addSubView(view, subButton);
		}

		setModified(true);
		return view;
	}

	//CONTROL SUB-BUTTON
	public void addSubButton(ControlDrawer drawer, ControlData controlButton){
		//Yep there isn't much here
		drawer.getDrawerData().buttonProperties.add(controlButton);
		addSubView(drawer, drawer.getDrawerData().buttonProperties.get(drawer.getDrawerData().buttonProperties.size()-1 ));
	}

	private void addSubView(ControlDrawer drawer, ControlData controlButton){
		final ControlSubButton view = new ControlSubButton(this, controlButton, drawer);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}else{
			view.setVisible(true);
		}

		addView(view);
		drawer.addButton(view);


		setModified(true);
	}

	// JOYSTICK BUTTON
	public void addJoystickButton(ControlJoystickData data){
		mLayout.mJoystickDataList.add(data);
		addJoystickView(data);
	}

	private void addJoystickView(ControlJoystickData data){
		ControlJoystick view = new ControlJoystick(this, data);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);

	}


	private void removeAllButtons() {
		for(ControlInterface button : getButtonChildren()){
			removeView(button.getControlView());
		}

		System.gc();
		//i wanna be sure that all the removed Views will be removed after a reload
		//because if frames will slowly go down after many control changes it will be warm and bad
	}

	public void saveLayout(String path) throws Exception {
		mLayout.save(path);
		setModified(false);
	}

	public void toggleControlVisible(){
		mControlVisible = !mControlVisible;
		setControlVisible(mControlVisible);
	}

	public float getLayoutScale(){
		return mLayout.scaledAt;
	}

	public CustomControls getLayout(){
		return mLayout;
	}

	public void setControlVisible(boolean isVisible) {
		if (mModifiable) return; // Not using on custom controls activity

		mControlVisible = isVisible;
		for(ControlInterface button : getButtonChildren()){
			button.setVisible(((button.getProperties().displayInGame && isGrabbing()) || (button.getProperties().displayInMenu && !isGrabbing())) && isVisible);
		}
	}

	public void setModifiable(boolean isModifiable) {
		if(!isModifiable && mModifiable){
			removeEditWindow();
		}
		mModifiable = isModifiable;
		if(isModifiable){
			// In edit mode, all controls have to be shown
			for(ControlInterface button : getButtonChildren()){
				button.setVisible(true);
			}
		}
	}

	public boolean getModifiable(){
		return mModifiable;
	}

	public void setModified(boolean isModified) {
		mIsModified = isModified;
	}

	public List<ControlInterface> getButtonChildren(){
		if(mModifiable || mButtons == null){
			mButtons = new ArrayList<>();
			for(int i=0; i<getChildCount(); ++i){
				View v = getChildAt(i);
				if(v instanceof ControlInterface)
					mButtons.add(((ControlInterface) v));
			}
		}

		return mButtons;
	}

	public void refreshControlButtonPositions(){
		for(ControlInterface button : getButtonChildren()){
			button.setDynamicX(button.getProperties().dynamicX);
			button.setDynamicY(button.getProperties().dynamicY);
		}
	}

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        if(child instanceof ControlInterface && mControlDialog != null){
			mControlDialog.disappearColor();
            mControlDialog.disappear(false);
        }
    }

    /**
	 * Load the layout if needed, and pass down the burden of filling values
	 * to the button at hand.
	 */
	public void editControlButton(ControlInterface button){
		if(mControlDialog == null){
			// When the panel is null, it needs to inflate first.
			// So inflate it, then process it on the next frame
			mControlDialog = new EditControlSideDialog(getContext(), this);
			post(() -> editControlButton(button));
			return;
		}

		mControlDialog.internalChanges = true;
		mControlDialog.setCurrentlyEditedButton(button);

		mControlDialog.appear(button.getControlView().getX() + button.getControlView().getWidth()/2f < currentDisplayMetrics.widthPixels/2f);
		button.loadEditValues(mControlDialog);

		mControlDialog.internalChanges = false;

		mControlDialog.disappearColor();

		if(mHandleView == null){
			mHandleView = new ControlHandleView(getContext());
			addView(mHandleView);
		}
		mHandleView.setControlButton(button);

		//mHandleView.show();
	}

	/** Swap the panel if the button position requires it */
	public void adaptPanelPosition(){
		if(mControlDialog != null) mControlDialog.adaptPanelPosition();
	}


	final HashMap<View, ControlInterface> mapTable = new HashMap<>();

	//While this is called onTouch, this should only be called from a ControlButton.
	public void onTouch(View v, MotionEvent ev) {
		ControlInterface lastControlButton = mapTable.get(v);

		// Map location to screen coordinates
		ev.offsetLocation(v.getX(), v.getY());


		//Check if the action is cancelling, reset the lastControl button associated to the view
		if (ev.getActionMasked() == MotionEvent.ACTION_UP
				|| ev.getActionMasked() == MotionEvent.ACTION_CANCEL
				|| ev.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
			if (lastControlButton != null) lastControlButton.sendKeyPresses(false);
			mapTable.put(v, null);
			return;
		}

		if (ev.getActionMasked() != MotionEvent.ACTION_MOVE) return;


		//Optimization pass to avoid looking at all children again
		if (lastControlButton != null) {
			System.out.println("last control button check" + ev.getX() + "-" + ev.getY() + "-" + lastControlButton.getControlView().getX() + "-" + lastControlButton.getControlView().getY());
			if (ev.getX() > lastControlButton.getControlView().getX()
					&& ev.getX() < lastControlButton.getControlView().getX() + lastControlButton.getControlView().getWidth()
					&& ev.getY() > lastControlButton.getControlView().getY()
					&& ev.getY() < lastControlButton.getControlView().getY() + lastControlButton.getControlView().getHeight()) {
				return;
			}
		}

		//Release last keys
		if (lastControlButton != null) lastControlButton.sendKeyPresses(false);
		mapTable.remove(v);

		// Update the state of all swipeable buttons
		for (ControlInterface button : getButtonChildren()) {
			if (!button.getProperties().isSwipeable) continue;

			if (ev.getX() > button.getControlView().getX()
					&& ev.getX() < button.getControlView().getX() + button.getControlView().getWidth()
					&& ev.getY() > button.getControlView().getY()
					&& ev.getY() < button.getControlView().getY() + button.getControlView().getHeight()) {

				//Press the new key
				if (!button.equals(lastControlButton)) {
					button.sendKeyPresses(true);
					mapTable.put(v, button);
					return;
				}

			}
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (mModifiable && event.getActionMasked() != MotionEvent.ACTION_UP || mControlDialog == null)
			return true;

		InputMethodManager imm = (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);

		// When the input window cannot be hidden, it returns false
		if(!imm.hideSoftInputFromWindow(getWindowToken(), 0)){
			if(mControlDialog.disappearLayer()){
				mActionRow.setFollowedButton(null);
				mHandleView.hide();
			}
		}
		return true;
	}

	public void removeEditWindow() {
		InputMethodManager imm = (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);

		// When the input window cannot be hidden, it returns false
		imm.hideSoftInputFromWindow(getWindowToken(), 0);
		if(mControlDialog != null) {
			mControlDialog.disappearColor();
			mControlDialog.disappear(true);
		}

		if(mActionRow != null) mActionRow.setFollowedButton(null);
		if(mHandleView != null) mHandleView.hide();
	}

	public void save(String path){
		try {
			mLayout.save(path);
		} catch (IOException e) {Log.e("ControlLayout", "Failed to save the layout at:" + path);}
	}


	public boolean hasMenuButton() {
		for(ControlInterface controlInterface : getButtonChildren()){
			for (int keycode : controlInterface.getProperties().keycodes) {
				if (keycode == ControlData.SPECIALBTN_MENU) return true;
			}
		}
		return false;
	}

	public void setMenuListener(ControlButtonMenuListener menuListener) {
		this.mMenuListener = menuListener;
	}

	public void notifyAppMenu() {
		if(mMenuListener != null) mMenuListener.onClickedMenu();
	}

	/** Cached getter for perf purposes */
	public MinecraftGLSurface getGameSurface(){
		if(mGameSurface == null){
			mGameSurface = findViewById(R.id.main_game_render_view);
		}
		return mGameSurface;
	}

	public void askToExit(EditorExitable editorExitable) {
		if(mIsModified) {
			openSaveDialog(editorExitable);
		}else{
			openExitDialog(editorExitable);
		}
	}

	public void updateLoadedFileName(String path) {
		path = path.replace(Tools.CTRLMAP_PATH, ".");
		path = path.substring(0, path.length() - 5);
		mLayoutFileName = path;
	}

	public String saveToDirectory(String name) throws Exception{
		String jsonPath = Tools.CTRLMAP_PATH + "/" + name + ".json";
		saveLayout(jsonPath);
		return jsonPath;
	}

	private void saveLayoutAndMaybeExit(EditText editText, EditorExitable listener,
									   UclDialog dialog) {
		Context context = getContext();
		if (editText.getText().toString().isEmpty()) {
			editText.setError(context.getString(R.string.global_error_field_empty));
			return;
		}
		try {
			String jsonPath = saveToDirectory(editText.getText().toString());
			Toast.makeText(context, context.getString(R.string.global_save) + ": " + jsonPath, Toast.LENGTH_SHORT).show();
			dialog.dismiss();
			if (listener != null) listener.exitEditor();
		} catch (Throwable th) {
			Tools.showError(context, th, listener != null);
		}
	}

	public void openSaveDialog(EditorExitable editorExitable) {
		final Context context = getContext();
		final EditText edit = new EditText(context);
		edit.setSingleLine();
		edit.setText(mLayoutFileName);

		UclDialog.Builder builder = UclAlert.builder(context);
		if (builder == null) return;
		/* the keys do not close the window themselves: an empty name has to keep
		 * the field on screen so the message next to it can be read */
		final UclDialog[] holder = new UclDialog[1];
		builder.title(context.getString(R.string.global_save))
				.body(edit, false)
				.key(context.getString(R.string.ucl_dlg_cancel), UclDialog.STYLE_LIGHT, null)
				.key(context.getString(R.string.ucl_dlg_save), UclDialog.STYLE_LIGHT,
						() -> saveLayoutAndMaybeExit(edit, null, holder[0]), false);
		if (editorExitable != null) {
			builder.key(context.getString(R.string.global_save_and_exit), UclDialog.STYLE_GREEN,
					() -> saveLayoutAndMaybeExit(edit, editorExitable, holder[0]), false);
		}
		holder[0] = builder.show();
	}

	public void openLoadDialog() {
		Context context = getContext();
		UclDialog.Builder builder = UclAlert.builder(context);
		if (builder == null) return;

		FileListView flv = new FileListView(context);
		flv.setSuffixes("json");
		if(Build.VERSION.SDK_INT < 29)flv.listFileAt(new File(Tools.CTRLMAP_PATH));
		else flv.lockPathAt(new File(Tools.CTRLMAP_PATH));

		final UclDialog[] holder = new UclDialog[1];
		flv.setFileSelectedListener(new FileSelectedListener() {
			@Override
			public void onFileSelected(File file, String path) {
				try {
					loadLayout(path);
				} catch (IOException e) {
					Tools.showError(context, e);
				}
				if (holder[0] != null) holder[0].dismiss();
			}
		});
		flv.setLayoutParams(new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, listHeight(context)));

		holder[0] = builder.title(context.getString(R.string.global_load))
				.body(flv, false)
				.key(context.getString(R.string.ucl_dlg_cancel), UclDialog.STYLE_LIGHT, null)
				.show();
		/* the title is the directory being shown, so it follows the walk */
		flv.setDialogTitleListener(title -> {
			if (holder[0] != null) holder[0].setTitle(title);
		});
	}

	public void openSetDefaultDialog() {
		Context context = getContext();
		UclDialog.Builder builder = UclAlert.builder(context);
		if (builder == null) return;

		FileListView flv = new FileListView(context);
		flv.setSuffixes("json");
		flv.lockPathAt(new File(Tools.CTRLMAP_PATH));

		final UclDialog[] holder = new UclDialog[1];
		flv.setFileSelectedListener(new FileSelectedListener() {
			@Override
			public void onFileSelected(File file, String path) {
				try {
					LauncherPreferences.DEFAULT_PREF.edit().putString("defaultCtrl", path).apply();
					LauncherPreferences.PREF_DEFAULTCTRL_PATH = path;loadLayout(path);
				} catch (IOException|JsonSyntaxException e) {
					Tools.showError(context, e);
				}
				if (holder[0] != null) holder[0].dismiss();
			}
		});
		flv.setLayoutParams(new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, listHeight(context)));

		holder[0] = builder.title(context.getString(R.string.customctrl_selectdefault))
				.body(flv, false)
				.key(context.getString(R.string.ucl_dlg_cancel), UclDialog.STYLE_LIGHT, null)
				.show();
		flv.setDialogTitleListener(title -> {
			if (holder[0] != null) holder[0].setTitle(title);
		});
	}

	public void openExitDialog(EditorExitable exitListener) {
		Context context = getContext();
		UclAlert.confirm(context,
				context.getString(R.string.customctrl_editor_exit_title),
				context.getString(R.string.customctrl_editor_exit_msg),
				context.getString(R.string.global_yes),
				() -> exitListener.exitEditor());
	}

	/** Half the screen: enough rows to walk a directory, short of a full page. */
	private static int listHeight(Context context) {
		return (int) (context.getResources().getDisplayMetrics().heightPixels * 0.5f);
	}

	public boolean areControlVisible(){
		return mControlVisible;
	}
}

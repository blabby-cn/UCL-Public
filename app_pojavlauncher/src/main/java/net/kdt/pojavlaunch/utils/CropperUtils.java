package net.kdt.pojavlaunch.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.app.Dialog;
import net.kdt.pojavlaunch.mcui.UclAlert;
import net.kdt.pojavlaunch.mcui.UclDialog;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.imgcropper.BitmapCropBehaviour;
import net.kdt.pojavlaunch.imgcropper.CropperBehaviour;
import net.kdt.pojavlaunch.imgcropper.CropperView;
import net.kdt.pojavlaunch.imgcropper.RegionDecoderCropBehaviour;

import java.io.IOException;
import java.io.InputStream;

public class CropperUtils {
    public static ActivityResultLauncher<?> registerCropper(Fragment fragment, final CropperListener cropperListener) {
        return fragment.registerForActivityResult(new ActivityResultContracts.OpenDocument(), (result)->{
            Context context = fragment.getContext();
            if(context == null) return;
            if (result == null) {
                Toast.makeText(context, R.string.cropper_select_cancelled, Toast.LENGTH_SHORT).show();
                return;
            }
            openCropperDialog(context, result, cropperListener);
        });
    }

    private static void openCropperDialog(Context context, Uri selectedUri,
                                          final CropperListener cropperListener) {
        ContentResolver contentResolver = context.getContentResolver();
        UclDialog.Builder builder = UclAlert.builder(context);
        if (builder == null) return;
        View cropRoot = android.view.LayoutInflater.from(context)
                .inflate(R.layout.dialog_cropper, null);
        final UclDialog[] holder = new UclDialog[1];
        UclDialog dialog = builder.title(context.getString(R.string.cropper_title))
                .body(cropRoot, false)
                .rowLayout(true)
                .key(context.getString(R.string.ucl_dlg_cancel), UclDialog.STYLE_LIGHT, null)
                .key(context.getString(R.string.ucl_dlg_ok), UclDialog.STYLE_LIGHT, () -> {
                    if (holder[0] != null) holder[0].dismiss();
                    cropperListener.onCropped(
                            ((CropperView) cropRoot.findViewById(R.id.crop_dialog_view))
                                    .crop((int) Tools.dpToPx(70)));
                }, false)
                .show();
        holder[0] = dialog;
        CropperView cropImageView = dialog.findViewById(R.id.crop_dialog_view);
        View finishProgressBar = dialog.findViewById(R.id.crop_dialog_progressbar);
        assert cropImageView != null;
        assert finishProgressBar != null;
        bindViews(dialog, cropImageView);
        PojavApplication.sExecutorService.execute(()->{
            CropperBehaviour cropperBehaviour = null;
            try {
                 cropperBehaviour = createBehaviour(cropImageView, contentResolver, selectedUri);
            }catch (Exception e) {
                cropperListener.onFailed(e);
            }
            CropperBehaviour finalBehaviour = cropperBehaviour;
            Tools.runOnUiThread(()->finishSetup(dialog, finishProgressBar, cropImageView, finalBehaviour));
        });
    }

    // Fixes the chin that the dialog has on my huawei fon
    private static void fixDialogHeight(Dialog dialog) {
        Window dialogWindow = dialog.getWindow();
        if(dialogWindow != null)
            dialogWindow.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT, // width
                    WindowManager.LayoutParams.WRAP_CONTENT  // height
            );
    }

    private static void finishSetup(Dialog dialog, View progressBar,
                                    CropperView cropImageView, CropperBehaviour cropperBehaviour) {
        if(cropperBehaviour == null) {
            dialog.dismiss();
            return;
        }
        progressBar.setVisibility(View.GONE);
        cropImageView.setCropperBehaviour(cropperBehaviour);
        cropperBehaviour.applyImage();
        cropImageView.post(()->{
            fixDialogHeight(dialog);
            cropImageView.requestLayout();
        });
    }


    private static CropperBehaviour createBehaviour(CropperView cropImageView,
                                      ContentResolver contentResolver,
                                      Uri selectedUri) throws Exception {
        try (InputStream inputStream = contentResolver.openInputStream(selectedUri)) {
            if(inputStream == null) return null;
            try {
                BitmapRegionDecoder regionDecoder = BitmapRegionDecoder.newInstance(inputStream, false);
                RegionDecoderCropBehaviour cropBehaviour = new RegionDecoderCropBehaviour(cropImageView);
                cropBehaviour.setRegionDecoder(regionDecoder);
                return cropBehaviour;
            }catch (IOException e) {
                // Catch IOE here to detect the case when BitmapRegionDecoder does not support this image format.
                // If it does not, we will just have to load the bitmap in full resolution using BitmapFactory.
                Log.w("CropperUtils", "Failed to load image into BitmapRegionDecoder", e);
            }
        }
        // We can safely re-open the stream here as ACTION_OPEN_DOCUMENT grants us long-term access
        // to the file that we have picked.
        try (InputStream inputStream = contentResolver.openInputStream(selectedUri)) {
            if(inputStream == null) return null;
            Bitmap originalBitmap = BitmapFactory.decodeStream(inputStream);
            BitmapCropBehaviour cropBehaviour = new BitmapCropBehaviour(cropImageView);
            cropBehaviour.setBitmap(originalBitmap);
            return cropBehaviour;
        }
    }

    private static void bindViews(Dialog alertDialog, CropperView imageCropperView) {
        ToggleButton horizontalLock = alertDialog.findViewById(R.id.crop_dialog_hlock);
        ToggleButton verticalLock = alertDialog.findViewById(R.id.crop_dialog_vlock);
        View reset = alertDialog.findViewById(R.id.crop_dialog_reset);
        assert horizontalLock != null;
        assert verticalLock != null;
        assert reset != null;
        horizontalLock.setOnClickListener(v->
                imageCropperView.horizontalLock = horizontalLock.isChecked()
        );
        verticalLock.setOnClickListener(v->
                imageCropperView.verticalLock = verticalLock.isChecked()
        );
        reset.setOnClickListener(v->
            imageCropperView.resetTransforms()
        );
    }

    @SuppressWarnings("unchecked")
    public static void startCropper(ActivityResultLauncher<?> resultLauncher) {
        ActivityResultLauncher<String[]> realResultLauncher =
                (ActivityResultLauncher<String[]>) resultLauncher;
        realResultLauncher.launch(new String[]{"image/*"});
    }
    public interface CropperListener {
        void onCropped(Bitmap contentBitmap);
        void onFailed(Exception exception);
    }
}

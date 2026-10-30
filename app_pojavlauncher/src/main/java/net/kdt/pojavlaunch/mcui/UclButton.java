package net.kdt.pojavlaunch.mcui;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.content.res.TypedArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

/**
 * UCL styled button: user supplied light button texture, bold font,
 * text size expressed in concept pixels (1920x1080 canvas) and scaled at runtime.
 */
public class UclButton extends AppCompatButton {
    private static final float REFERENCE_WIDTH = 1920f;

    private final UclPress mPress = new UclPress(this);

    public UclButton(@NonNull Context context) {
        this(context, null);
    }

    public UclButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        float conceptTextSize = 22f;
        int style = 0;
        if (attrs != null) {
            TypedArray array = getContext().obtainStyledAttributes(attrs, R.styleable.UclButton);
            conceptTextSize = array.getFloat(R.styleable.UclButton_uclTextSize, conceptTextSize);
            style = array.getInt(R.styleable.UclButton_uclStyle, 0);
            array.recycle();
        }
        float scale = getResources().getDisplayMetrics().widthPixels / REFERENCE_WIDTH;
        setTypeface(UclTypeface.command(getContext()));
        setBackground(ResourcesCompat.getDrawable(getResources(), plateFor(style), null));
        /* the plate is a 9-patch drawn on the pixel grid: no bilinear filter */
        UclDrawables.nearestNeighbour(getBackground());
        if (style == 0) {
            setTextColor(ContextCompat.getColorStateList(getContext(), R.color.ucl_button_text));
        } else {
            /* the recoloured plates are dark, so the ink is the light one the
             * dialog keys already use on them */
            setTextColor(0xFFFCFCFA);
        }
        setTextSize(TypedValue.COMPLEX_UNIT_PX, conceptTextSize * scale);
        setAllCaps(false);
        setIncludeFontPadding(false);
    }

    /**
     * The plate for a {@code uclStyle}: the designer's light key, or one of its
     * two recolours. They share the artwork, so only the colour source differs.
     */
    private static int plateFor(int style) {
        switch (style) {
            case 1: return R.drawable.ucl_btn_red;
            case 2: return R.drawable.ucl_btn_green;
            default: return R.drawable.ucl_button_background;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        /* the offset is read from the live state here, in the same pass that
         * draws the plate: the label can never be a frame behind it */
        float shift = mPress == null ? 0f : mPress.offset();
        if (shift == 0f) {
            super.onDraw(canvas);
            return;
        }
        int save = canvas.save();
        canvas.translate(0f, shift);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
    }
}

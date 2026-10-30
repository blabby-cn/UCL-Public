package com.kdt.mcgui;

import android.content.*;
import android.graphics.*;
import android.util.*;

import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

public class MineButton extends androidx.appcompat.widget.AppCompatButton {
	private final net.kdt.pojavlaunch.mcui.UclPress mPress = new net.kdt.pojavlaunch.mcui.UclPress(this);

	public MineButton(Context ctx) {
		this(ctx, null);
	}

	public MineButton(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
		init();
	}

	public void init() {
		setTypeface(net.kdt.pojavlaunch.mcui.UclTypeface.command(getContext()));
		setBackground(ResourcesCompat.getDrawable(getResources(), R.drawable.mine_button_background, null));
		net.kdt.pojavlaunch.mcui.UclDrawables.nearestNeighbour(getBackground());
		setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen._13ssp));
		/* the green plate swaps colour when pressed but does not move, so the
		 * label only travels once the key has been switched to the light one */
		mPress.setTravels(false);
	}

	/** Turns the label travel on for keys wearing the light plate. */
	public void setPlateTravel(boolean enabled) {
		mPress.setTravels(enabled);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		/* read from the live state, in the pass that draws the plate itself */
		float shift = mPress.offset();
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

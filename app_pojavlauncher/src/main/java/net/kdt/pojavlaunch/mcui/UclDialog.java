package net.kdt.pojavlaunch.mcui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The Minecraft modal, rebuilt from the reference screenshots.
 *
 * <p>Every number below is a measurement, not a guess: the plate is 902
 * reference pixels wide, the title strip 84 tall, the body face 23 steps
 * darker than the strip, the keys 92 tall with 7 between them. The reference
 * runs at GUI scale 3, so a reference pixel is one third of a dp -- dividing by
 * three is what makes the result land on the same proportions at any density.
 *
 * <p>Why not a themed AlertDialog: AppCompat owns the title strip, the divider
 * and the button row, and none of the three can be given the banded bevel this
 * design needs. That is also why the buttons here are plain TextViews with a
 * nine-patch plate rather than framework buttons -- the framework one draws its
 * own background and its own insets on top of whatever plate you set.
 *
 * <p>The red and green keys are the designer's light button recoloured, see
 * scripts/make_button_tints.py; only the grey under each bevel was remapped, so
 * the bevel the artwork already carries decides where highlight and shade land.
 */
public final class UclDialog extends Dialog {

    /* ------------------------------------------------------------------ */
    /* measurements, in reference pixels (the screenshot's own grid)        */
    /* ------------------------------------------------------------------ */

    private static final int REF_WIDTH = 902;
    private static final int REF_EDGE_TOP = 3;
    private static final int REF_EDGE_LEFT = 4;
    private static final int REF_EDGE_RIGHT = 3;
    private static final int REF_TITLE_H = 84;
    private static final int REF_RULE = 4;
    private static final int REF_BODY_TOP = 34;
    private static final int REF_BODY_BOTTOM = 32;
    private static final int REF_FOOT_TOP = 26;
    private static final int REF_FOOT_BOTTOM = 27;
    private static final int REF_BTN_H = 92;
    private static final int REF_BTN_GAP = 7;
    private static final int REF_BTN_PAD = 34;
    private static final int REF_BTN_ROW_GAP = 48;
    /** Cap for a scrolling body, in reference pixels (240dp). */
    private static final int REF_SCROLL_MAX = 720;
    /** The loading plate's body: measured off the in-game "loading" modal. */
    private static final int REF_LOADING_BODY = 194;
    /** The spinner itself, about the size of the 16px game cursor ring. */
    private static final int REF_SPINNER = 40;
    private static final int REF_CLOSE = 27;
    private static final int REF_CLOSE_RIGHT = 32;
    private static final float REF_TEXT = 28f;

    /**
     * The one number every measurement on the plate is multiplied by.
     *
     * <p>All four windows -- the confirmation chain, the version picker, the
     * download progress and the loading plate -- are laid out from the REF_*
     * constants below, so moving this moves the whole set together instead of
     * leaving one of them at the old size.
     */
    private static final float BASE_SCALE = 1.25f;

    private static float sScale = BASE_SCALE;

    /**
     * How much of a reference measurement the vertical air gets.
     *
     * <p>The plate grew to {@link #BASE_SCALE} of its reference size, and the
     * type grows with it (see {@link #ts}), so the air has to be pulled in
     * against the reference: 0.72 means the gap above and below a line is 72%
     * of what the reference plate carries. On screen that is 0.9 of the air the
     * plate used to have -- tighter than before, not just tighter-looking.
     */
    private static final float VSPACE = 0.72f / BASE_SCALE;

    public static void setScale(float scale) {
        if (scale > 0f) sScale = scale;
    }

    public static float scale() {
        return sScale;
    }

    /* ------------------------------------------------------------------ */
    /* palette, sampled off the same screenshots                            */
    /* ------------------------------------------------------------------ */

    private static final int EDGE_TOP = 0xFF6D6F6E;
    private static final int EDGE_LEFT = 0xFF6C6E6D;
    private static final int EDGE_RIGHT = 0xFF5C5D5F;
    private static final int TITLE_FACE = 0xFF48494B;
    private static final int TITLE_RULE = 0xFF5E5F61;
    private static final int BODY_FACE = 0xFF313234;
    private static final int BODY_RULE = 0xFF595A5C;
    private static final int FOOT_FACE = 0xFF47484A;
    private static final int FOOT_EDGE = 0xFF323335;
    private static final int DROP = 0xFF1D1E20;
    private static final int INK = 0xFFFAFBFD;
    private static final int HINT_INK = 0xFF9A9C9E;
    private static final int INK_ON_LIGHT = 0xFF121216;

    /* ------------------------------------------------------------------ */
    /* key styles                                                           */
    /* ------------------------------------------------------------------ */

    public static final int STYLE_LIGHT = 0;
    public static final int STYLE_RED = 1;
    public static final int STYLE_GREEN = 2;

    private static final int[] KEY_PLATE = {
            R.drawable.ucl_button_background,
            R.drawable.ucl_btn_red,
            R.drawable.ucl_btn_green,
    };
    private static final int[] KEY_INK = {INK_ON_LIGHT, 0xFFFCFCFA, 0xFFF8FAF6};

    private final List<TextView> mKeys = new ArrayList<>();
    private ScrollView mScroll;
    private TextView mTitleView;

    private UclDialog(Activity activity) {
        super(activity, R.style.UclPlate);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    /** The key at {@code index}, in the order it was added. Null if absent. */
    public TextView key(int index) {
        return index >= 0 && index < mKeys.size() ? mKeys.get(index) : null;
    }

    /** The scrolling body, when the dialog was built with one. */
    public ScrollView scrollBody() {
        return mScroll;
    }

    /**
     * Replaces the title of a window that is already on screen.
     *
     * <p>The file picker is the reason this exists: its title is the directory
     * it is currently showing, so the title changes as the user walks the tree.
     */
    public void setTitle(CharSequence title) {
        if (mTitleView != null) mTitleView.setText(title);
    }

    /* ------------------------------------------------------------------ */
    /* builder                                                              */
    /* ------------------------------------------------------------------ */

    public static Builder build(Activity activity) {
        return new Builder(activity);
    }

    /**
     * The game's "loading" plate: title strip, one spinning ring, no keys and
     * no close mark. It is deliberately not cancelable -- it stands in front of
     * a page that is still being fetched, and the caller closes it.
     */
    public static UclDialog loading(Activity activity, CharSequence title) {
        if (activity == null) return null;
        return build(activity)
                .title(title)
                .spinner()
                .closeMark(false)
                .cancelable(false)
                .show();
    }

    public static final class Builder {
        private final Activity mActivity;
        private CharSequence mTitle;
        private CharSequence mMessage;
        private View mCustomBody;
        private boolean mScrollable;
        private float mScrollHeightFraction = 0.38f;
        private final List<Key> mKeys = new ArrayList<>();
        private boolean mRowLayout;
        private boolean mCancelable = true;
        private boolean mCancelOnTouchOutside = true;
        private boolean mCloseMark = true;
        private CharSequence mTitleFieldHint;
        private TextWatcher mTitleFieldWatcher;
        private boolean mLoading;

        Builder(Activity activity) {
            mActivity = activity;
        }

        public Builder title(CharSequence title) {
            mTitle = title;
            return this;
        }

        public Builder message(CharSequence message) {
            mMessage = message;
            return this;
        }

        /** Replaces the message with any view; {@code scrollable} puts it in a
         *  box capped against the screen height. */
        public Builder body(View body, boolean scrollable) {
            mCustomBody = body;
            mScrollable = scrollable;
            return this;
        }

        public Builder scrollHeightFraction(float fraction) {
            mScrollHeightFraction = fraction;
            return this;
        }

        /** Puts the message in a box that scrolls, capped against the screen
         *  height. Used by the disclaimer, whose body is longer than any plate. */
        public Builder scrollable(boolean scrollable) {
            mScrollable = scrollable;
            return this;
        }

        /** Buttons side by side instead of stacked. The reference uses this for
         *  the pair that gets equal weight, and stacking for everything else. */
        public Builder rowLayout(boolean row) {
            mRowLayout = row;
            return this;
        }

        /** Swaps the text body for a single centred spinner. */
        public Builder spinner() {
            mLoading = true;
            return this;
        }

        public Builder key(CharSequence label, int style, Runnable action) {
            return key(label, style, action, true);
        }

        /**
         * A key that runs its action and leaves the window open.
         *
         * <p>The runtime list needs this: one of its keys toggles the list
         * between "delete" and "set as default", and closing the window on that
         * tap would make the mode it just switched to invisible.
         */
        public Builder key(CharSequence label, int style, Runnable action, boolean dismissAfter) {
            mKeys.add(new Key(label, style, action, dismissAfter));
            return this;
        }

        public Builder closeMark(boolean show) {
            mCloseMark = show;
            return this;
        }

        /**
         * Puts an input field in the title strip, between the title and the
         * close mark, and hands it a watcher.
         *
         * <p>A list window that can be filtered needs the field where the user
         * is already looking, not behind a second window: typing in the strip
         * filters the rows underneath it as the letters arrive.
         */
        public Builder titleField(CharSequence hint, TextWatcher watcher) {
            mTitleFieldHint = hint;
            mTitleFieldWatcher = watcher;
            return this;
        }

        public Builder cancelable(boolean cancelable) {
            mCancelable = cancelable;
            mCancelOnTouchOutside = cancelable;
            return this;
        }

        public Builder cancelOnTouchOutside(boolean outside) {
            mCancelOnTouchOutside = outside;
            return this;
        }

        public UclDialog show() {
            UclDialog dialog = new UclDialog(mActivity);
            dialog.setCancelable(mCancelable);
            dialog.setCanceledOnTouchOutside(mCancelOnTouchOutside);
            dialog.setContentView(buildShell(dialog));
            dialog.show();
            dialog.applyWindowMetrics();
            UclDrawables.nearestNeighbourTree(dialog.getWindow().getDecorView());
            return dialog;
        }

        /* -------------------------------------------------------------- */

        private View buildShell(UclDialog dialog) {
            Context c = mActivity;

            Plate card = new Plate(c);
            card.setBackgroundColor(BODY_FACE);
            card.bevel(px(c, REF_EDGE_TOP), px(c, REF_EDGE_LEFT),
                    px(c, REF_EDGE_RIGHT), px(c, REF_RULE),
                    EDGE_TOP, EDGE_LEFT, EDGE_RIGHT, FOOT_EDGE);

            LinearLayout column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            columnParams.leftMargin = px(c, REF_EDGE_LEFT);
            columnParams.rightMargin = px(c, REF_EDGE_RIGHT);
            columnParams.topMargin = px(c, REF_EDGE_TOP);
            columnParams.bottomMargin = px(c, REF_RULE);
            card.addView(column, columnParams);

            column.addView(buildTitle(dialog, c));
            column.addView(rule(c, TITLE_RULE));
            column.addView(buildBody(dialog, c));
            if (!mKeys.isEmpty()) {
                column.addView(rule(c, BODY_RULE));
                column.addView(buildFooter(dialog, c));
            }

            /* the four 4px shade rows under the plate: without them the window
             * looks pasted on rather than sitting on the backdrop */
            FrameLayout shell = new FrameLayout(c);
            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = px(c, REF_RULE);
            shell.addView(card, cardParams);

            View drop = new View(c);
            drop.setBackgroundColor(DROP);
            FrameLayout.LayoutParams dropParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(c, REF_RULE), Gravity.BOTTOM);
            dropParams.leftMargin = px(c, REF_EDGE_LEFT);
            dropParams.rightMargin = px(c, REF_EDGE_RIGHT);
            shell.addView(drop, dropParams);

            return shell;
        }

        private View buildTitle(UclDialog dialog, Context c) {
            int height = px(c, REF_TITLE_H);
            if (mTitleFieldHint != null) return buildTitleWithField(dialog, c, height);

            FrameLayout strip = new FrameLayout(c);
            strip.setBackgroundColor(TITLE_FACE);

            TextView title = new TextView(c);
            title.setText(mTitle == null ? "" : mTitle);
            title.setTextColor(INK);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, ts(REF_TEXT));
            title.setTypeface(UclTypeface.title(c));
            title.setGravity(Gravity.CENTER);
            title.setIncludeFontPadding(false);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            /* the close mark eats into the right edge, so the text is padded on
             * both sides by the same amount and stays optically centred */
            int side = px(c, REF_CLOSE_RIGHT + REF_CLOSE + 8);
            title.setPadding(side, 0, side, 0);
            strip.addView(title, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.CENTER));
            dialog.mTitleView = title;

            if (mCloseMark) {
                CloseMark close = new CloseMark(c);
                FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                        px(c, REF_CLOSE), px(c, REF_CLOSE), Gravity.END | Gravity.CENTER_VERTICAL);
                closeParams.rightMargin = px(c, REF_CLOSE_RIGHT);
                strip.addView(close, closeParams);
                close.setOnClickListener(v -> dialog.dismiss());
            }
            return strip;
        }

        /**
         * The same strip with an input field in it: title on the left, field
         * filling the middle, close mark on the right.
         *
         * <p>The title loses its centring because the field needs the room, and
         * it is capped so a long title truncates instead of squeezing the field
         * to nothing. The field keeps the body face and the one-pixel edge the
         * rest of the shell uses for a surface that takes text.
         */
        private View buildTitleWithField(UclDialog dialog, Context c, int height) {
            LinearLayout strip = new LinearLayout(c);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setGravity(Gravity.CENTER_VERTICAL);
            strip.setBackgroundColor(TITLE_FACE);

            TextView title = new TextView(c);
            title.setText(mTitle == null ? "" : mTitle);
            title.setTextColor(INK);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, ts(REF_TEXT));
            title.setTypeface(UclTypeface.title(c));
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setIncludeFontPadding(false);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setMaxWidth(px(c, REF_WIDTH * 0.42f));
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, height);
            titleParams.leftMargin = px(c, 32);
            titleParams.rightMargin = px(c, 14);
            strip.addView(title, titleParams);
            dialog.mTitleView = title;

            EditText field = new EditText(c);
            field.setSingleLine(true);
            field.setHint(mTitleFieldHint);
            field.setHintTextColor(HINT_INK);
            field.setTextColor(INK);
            field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, ts(REF_TEXT));
            field.setIncludeFontPadding(false);
            field.setTypeface(UclTypeface.regular(c));
            field.setPadding(px(c, 16), 0, px(c, 16), 0);
            GradientDrawable face = new GradientDrawable();
            face.setColor(BODY_FACE);
            face.setCornerRadius(px(c, 3));
            face.setStroke(Math.max(1, px(c, 2)), DROP);
            field.setBackground(face);
            if (mTitleFieldWatcher != null) field.addTextChangedListener(mTitleFieldWatcher);
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, px(c, 56), 1f);
            fieldParams.rightMargin = px(c, 10);
            strip.addView(field, fieldParams);

            if (mCloseMark) {
                CloseMark close = new CloseMark(c);
                LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                        px(c, REF_CLOSE), px(c, REF_CLOSE));
                closeParams.rightMargin = px(c, REF_CLOSE_RIGHT);
                strip.addView(close, closeParams);
                close.setOnClickListener(v -> dialog.dismiss());
            }
            return strip;
        }

        private View buildBody(UclDialog dialog, Context c) {
            LinearLayout holder = new LinearLayout(c);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.setBackgroundColor(BODY_FACE);

            if (mLoading) {
                /* the body of the loading plate is just air with a ring in the
                 * middle; it keeps the same width and faces as every other
                 * plate, and its height comes off the same reference grid */
                FrameLayout box = new FrameLayout(c);
                ProgressBar ring = new ProgressBar(c);
                ring.setIndeterminate(true);
                android.graphics.drawable.Drawable spinner = ring.getIndeterminateDrawable();
                if (spinner != null) {
                    spinner = spinner.mutate();
                    spinner.setTint(INK);
                    ring.setIndeterminateDrawable(spinner);
                }
                box.addView(ring, new FrameLayout.LayoutParams(
                        px(c, REF_SPINNER), px(c, REF_SPINNER), Gravity.CENTER));
                holder.addView(box, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, px(c, REF_LOADING_BODY)));
                return holder;
            }

            holder.setPadding(px(c, REF_BTN_PAD), vy(c, REF_BODY_TOP),
                    px(c, REF_BTN_PAD), vy(c, REF_BODY_BOTTOM));

            if (mCustomBody != null) {
                styleFields(mCustomBody, c);
                if (mScrollable) {
                    ScrollView scroll = new ScrollView(c);
                    scroll.setVerticalScrollBarEnabled(true);
                    scroll.addView(mCustomBody);
                    int screen = c.getResources().getDisplayMetrics().heightPixels;
                    scroll.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Math.min(px(c, REF_SCROLL_MAX),
                                    (int) (screen * mScrollHeightFraction))));
                    holder.addView(scroll);
                    dialog.mScroll = scroll;
                } else {
                    holder.addView(mCustomBody);
                }
                return holder;
            }

            TextView body = new TextView(c);
            body.setText(mMessage == null ? "" : mMessage);
            body.setTextColor(INK);
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, ts(REF_TEXT));
            body.setTypeface(UclTypeface.regular(c));
            body.setIncludeFontPadding(false);
            /* centred: the reference plate carries a symmetric block of text,
             * left alignment made it read like a form */
            body.setGravity(Gravity.CENTER);
            /* no extra leading. The face already carries 1.45em of line box and
             * the glyphs fill ~0.86em of it; stacking another 10 reference
             * pixels on top pushed a multi-line body to 1.8em, which is what
             * made the long plates (disclaimer, download) look airy. */
            body.setLineSpacing(0f, 1f);

            if (mScrollable) {
                ScrollView scroll = new ScrollView(c);
                scroll.setVerticalScrollBarEnabled(true);
                scroll.addView(body);
                int screen = c.getResources().getDisplayMetrics().heightPixels;
                scroll.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.min(px(c, REF_SCROLL_MAX), (int) (screen * mScrollHeightFraction))));
                holder.addView(scroll);
                dialog.mScroll = scroll;
            } else {
                holder.addView(body);
            }
            return holder;
        }

        private View buildFooter(UclDialog dialog, Context c) {
            LinearLayout footer = new LinearLayout(c);
            footer.setOrientation(mRowLayout ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            footer.setBackgroundColor(FOOT_FACE);
            footer.setPadding(px(c, REF_BTN_PAD), vy(c, REF_FOOT_TOP),
                    px(c, REF_BTN_PAD), vy(c, REF_FOOT_BOTTOM));

            int gap = mRowLayout ? px(c, REF_BTN_ROW_GAP) : vy(c, REF_BTN_GAP);
            for (int i = 0; i < mKeys.size(); i++) {
                Key spec = mKeys.get(i);
                TextView key = new UclPress.Text(c);
                key.setText(spec.label);
                key.setTextColor(KEY_INK[spec.style]);
                key.setTextSize(TypedValue.COMPLEX_UNIT_DIP, ts(REF_TEXT));
                key.setTypeface(UclTypeface.command(c));
                key.setIncludeFontPadding(false);
                key.setGravity(Gravity.CENTER);
                key.setBackgroundResource(KEY_PLATE[spec.style]);
                key.setClickable(true);
                key.setFocusable(true);
                if (spec.action != null) {
                    key.setOnClickListener(v -> {
                        if (spec.dismissAfter) dialog.dismiss();
                        spec.action.run();
                    });
                } else {
                    /* a key with no action is a way out, not a dead button */
                    key.setOnClickListener(v -> dialog.dismiss());
                }

                LinearLayout.LayoutParams params;
                if (mRowLayout) {
                    params = new LinearLayout.LayoutParams(0, px(c, REF_BTN_H), 1f);
                } else {
                    params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, px(c, REF_BTN_H));
                }
                if (i > 0) {
                    if (mRowLayout) params.leftMargin = gap;
                    else params.topMargin = gap;
                }
                footer.addView(key, params);
                dialog.mKeys.add(key);
            }
            return footer;
        }

        /**
         * Gives any field inside a caller's body the look the shell puts on a
         * surface that takes text.
         *
         * <p>A bare {@link EditText} arrives with the platform's own underline
         * and light background, which is the one thing on the plate that would
         * still look like the system's window rather than the game's.
         */
        private void styleFields(View root, Context c) {
            if (root instanceof EditText) {
                EditText field = (EditText) root;
                field.setTextColor(INK);
                field.setHintTextColor(HINT_INK);
                field.setTypeface(UclTypeface.regular(c));
                GradientDrawable face = new GradientDrawable();
                face.setColor(TITLE_FACE);
                face.setCornerRadius(px(c, 3));
                face.setStroke(Math.max(1, px(c, 2)), DROP);
                field.setBackground(face);
                field.setPadding(px(c, 16), px(c, 8), px(c, 16), px(c, 8));
            } else if (root instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) root;
                for (int i = 0; i < group.getChildCount(); i++) {
                    styleFields(group.getChildAt(i), c);
                }
            }
        }

        private View rule(Context c, int colour) {
            View line = new View(c);
            line.setBackgroundColor(colour);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(c, REF_RULE)));
            return line;
        }
    }

    /* ------------------------------------------------------------------ */

    /**
     * The plate: the body face plus the four bevel rows around it.
     *
     * <p>The rows are painted rather than built out of child Views. A plain
     * View with MATCH_PARENT height measures itself against the whole AT_MOST
     * constraint, so inside a wrap_content frame it takes the full screen
     * height; the frame then wraps to that, and the column stays at the top
     * with an empty screen-tall slab underneath. Painting keeps the height
     * decided by the column alone.
     */
    private static final class Plate extends FrameLayout {
        private final Paint mPaint = new Paint();
        private int mTop;
        private int mLeft;
        private int mRight;
        private int mBottom;
        private int mTopColour;
        private int mLeftColour;
        private int mRightColour;
        private int mBottomColour;

        Plate(Context context) {
            super(context);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setAntiAlias(false);
            setWillNotDraw(false);
        }

        void bevel(int top, int left, int right, int bottom,
                   int topColour, int leftColour, int rightColour, int bottomColour) {
            mTop = top;
            mLeft = left;
            mRight = right;
            mBottom = bottom;
            mTopColour = topColour;
            mLeftColour = leftColour;
            mRightColour = rightColour;
            mBottomColour = bottomColour;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final int w = getWidth();
            final int h = getHeight();
            mPaint.setColor(mTopColour);
            canvas.drawRect(0, 0, w, mTop, mPaint);
            mPaint.setColor(mBottomColour);
            canvas.drawRect(0, h - mBottom, w, h, mPaint);
            mPaint.setColor(mLeftColour);
            canvas.drawRect(0, 0, mLeft, h, mPaint);
            mPaint.setColor(mRightColour);
            canvas.drawRect(w - mRight, 0, w, h, mPaint);
        }
    }

    /* ------------------------------------------------------------------ */

    private void applyWindowMetrics() {
        Window window = getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(0));
        window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.CENTER;
        params.dimAmount = 0.45f;
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setAttributes(params);

        int screen = getContext().getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(px(getContext(), REF_WIDTH), (int) (screen * 0.86f));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    /* ------------------------------------------------------------------ */

    /**
     * The 27px cross, drawn as 4px stair steps because the source icon is a
     * tiny sprite blown up -- a smooth diagonal is what makes a redrawn close
     * button read as foreign next to the rest of the chrome.
     */
    private static final class CloseMark extends View {
        private final Paint mPaint = new Paint();
        private final float mSize;
        private final float mUnit;

        CloseMark(Context context) {
            super(context);
            mSize = px(context, REF_CLOSE);
            mUnit = mSize / REF_CLOSE;
            mPaint.setColor(INK);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setAntiAlias(false);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(Math.round(mSize), Math.round(mSize));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            for (int t = 0; t < REF_CLOSE; t += 4) {
                int h = Math.min(4, REF_CLOSE - t);
                canvas.drawRect(t * mUnit, t * mUnit, (t + 4) * mUnit, (t + h) * mUnit, mPaint);
                canvas.drawRect((REF_CLOSE - 4 - t) * mUnit, t * mUnit,
                        (REF_CLOSE - t) * mUnit, (t + h) * mUnit, mPaint);
            }
        }
    }

    /* ------------------------------------------------------------------ */

    private static final class Key {
        final CharSequence label;
        final int style;
        final Runnable action;
        final boolean dismissAfter;

        Key(CharSequence label, int style, Runnable action, boolean dismissAfter) {
            this.label = label;
            this.style = style;
            this.action = action;
            this.dismissAfter = dismissAfter;
        }
    }

    /** Reference pixel to screen pixels: the plate runs at GUI scale 3. */
    static int px(Context context, float reference) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                reference / 3f * sScale, context.getResources().getDisplayMetrics()));
    }

    /**
     * Reference type size, in dp, at the plate's current growth.
     *
     * <p>The type has to ride the same {@link #sScale} the plate does. When the
     * plate grew and the letters did not, the dialog read as a bigger empty box
     * -- the padding around the line stayed the same size while the line looked
     * smaller and smaller inside it. One factor for both keeps the proportions
     * the reference screenshot has.
     */
    private static float ts(float reference) {
        return reference / 3f * sScale;
    }

    /** Reference pixel of vertical air, on top of the plate's own growth. */
    private static int vy(Context context, float reference) {
        return px(context, reference * VSPACE);
    }
}

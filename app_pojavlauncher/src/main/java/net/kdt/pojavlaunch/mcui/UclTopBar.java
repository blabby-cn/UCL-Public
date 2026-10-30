package net.kdt.pojavlaunch.mcui;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

/**
 * The one and only definition of the title bar.
 *
 * <p>Every screen that has a bar calls {@link #install(Activity)} from
 * onCreate, so the bar cannot drift between pages: height, title font, title
 * size, colour and back-key size all come from here, and nowhere else. Two
 * page-specific copies of those numbers is how the settings page ended up with
 * a back arrow taller than its own title.
 *
 * <p>The typeface is bundled ({@code res/font/noto_sans_sc.ttf}, Noto Sans SC,
 * a subset of the CJK face that ships with Minecraft). Relying on the system
 * font was the other half of the problem: vendor ROMs replace it wholesale, so
 * the title rendered differently on every phone, and the size of the text no
 * longer matched the size of the artwork around it.
 */
public final class UclTopBar {

    /** Concept pixels: page artwork is authored against a 1920 px wide screen. */
    public static final float TITLE_SIZE = 43.2f;
    /**
     * Bar height expressed in title-font units.
     *
     * <p>The artwork ({@code ucl_topbar.9.png}) is a 2x21 strip of texels, so
     * its own proportions say nothing about how tall the bar should be: it
     * stretches, by design, the way Minecraft's own bar texture does. Height
     * is therefore tied to the title font, and the 9-patch keeps the bottom
     * four texels (highlight, frame, <b>one-pixel shadow</b>) at their real
     * thickness no matter how tall the bar gets.
     *
     * <p>That decoupling is the whole fix. When the shadow lived inside a
     * bitmap that got stretched to the bar height, every extra dp of bar
     * turned into extra millimetres of grey band, and the bar could never look
     * like Minecraft's no matter what it was scaled to.
     */
    private static final float BAR_HEIGHT_TO_TITLE = 1.95f;
    /** Never thinner than this: the back key needs a real tap target. */
    private static final float BAR_MIN_HEIGHT_DP = 40f;
    /**
     * How many texels tall the bar artwork is, and the number its height must
     * be a multiple of.
     *
     * <p>This is the rule that makes the bar exact. The slice is 21 texels
     * tall; if the bar is 110 px, one texel is 5.238 px, so every boundary in
     * the artwork — the catch light, the two shadow rows, the frame, the tab
     * seam — lands between pixels and has to be either interpolated or rounded
     * differently from its neighbour. No amount of marker tuning fixes a
     * division that does not come out. Snapping the height to a whole multiple
     * of 21 removes the division: a texel becomes an integer number of pixels
     * on every screen, and every line stays one line.
     *
     * <p>This is also why the reference build looks right on the same phone: its
     * bar is 21 texels at an integer GUI scale, so 21 x 2 or 21 x 3. Bitmap
     * tiling on an integer grid needs no tolerance for rounding, because there
     * is none.
     */
    private static final int BAR_TEXELS = 21;
    /**
     * Tab width, as a fraction of the bar height.
     *
     * <p>The design width was 116 dp against a 40 dp bar, so 2.9. Expressing it
     * against the bar rather than against the screen keeps it in the same
     * integer grid as everything else: {@code round(2.9 * 21)} = 61 texels.
     */
    private static final float TAB_WIDTH_TO_BAR = 2.9f;
    /** Icon size, type size, and the two insets, all as fractions of the bar. */
    private static final float TAB_ICON_TO_BAR = 15f / 40f;
    private static final float TAB_TEXT_TO_BAR = 13f / 40f;
    private static final float TAB_PAD_START_TO_BAR = 20f / 40f;
    private static final float TAB_PAD_END_TO_BAR = 16f / 40f;
    private static final float TAB_GAP_TO_BAR = 7f / 40f;
    /**
     * Back arrow height, relative to the title font size.
     *
     * <p>The arrow was a fixed 21dp while the title is a scaled font: on a
     * 2.75x screen that is 58px of solid glyph next to 53px of text whose ink
     * is only about 45px tall, so the back key read as the loudest element on
     * the screen. Tying it to the font size keeps the two proportionate at any
     * screen size instead of at one.
     */
    private static final float BACK_ICON_TO_TITLE = 0.82f;
    /** ucl_back.png is 32 x 56. */
    private static final float BACK_ICON_ASPECT = 32f / 56f;
    /** Smallest tap target worth shipping, per the accessibility guidelines. */
    private static final float BACK_MIN_TOUCH_DP = 44f;
    /**
     * How far the title is lifted inside the bar, in title-font units.
     *
     * <p>The bar centres a line box, not the ink. Noto Sans CJK is drawn so the
     * ideograph sits slightly below that box: ascender 1160 / descender 288 puts
     * the box centre 436 units above the baseline while the glyphs run
     * -79..840, centring them at 380. The 56-unit shortfall is 0.056 em of
     * visible sag; anything beyond that is a deliberate upward bias, which is
     * what the extra 0.034 em here buys.
     *
     * <p>This only works with font padding off: the font's bounding box reaches
     * 1808 above and 1048 below the baseline, so the padded line box would be
     * 2.86 em tall and get clipped by the bar instead of centred in it.
     */
    private static final float TITLE_RISE_TO_FONT = 0.09f;

    private UclTopBar() {
    }

    /**
     * Applies geometry, typeface and colours to one screen's bar.
     *
     * <p>The bar is no longer a single view shared by every page: it belongs to
     * the screen it titles, so that the whole screen can slide as one object.
     * That makes every lookup here relative to {@code root} - the screen, not
     * the activity - because an activity-wide lookup would find the first bar
     * in the tree and size somebody else's.
     */
    public static void install(Activity activity, View root) {
        if (activity == null || root == null) return;
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        float scale = dm.widthPixels / 1920f;

        float titlePx = TITLE_SIZE * scale;

        /* Height follows the type, with a floor for the back key's tap target,
         * and is then snapped to a whole number of artwork texels. The snap is
         * the point: it makes one texel an exact number of pixels, so the face,
         * the foot and the tab seam are all laid out on the same integer grid
         * and none of them can drift from the others. */
        int designHeight = Math.round(titlePx * BAR_HEIGHT_TO_TITLE);
        int floorHeight = Math.round(BAR_MIN_HEIGHT_DP * dm.density);
        int wanted = Math.max(designHeight, floorHeight);
        int texel = Math.max(1, Math.round(wanted / (float) BAR_TEXELS));
        int barHeight = texel * BAR_TEXELS;

        View bar = root.findViewById(R.id.ucl_topbar);
        if (bar != null && bar.getLayoutParams() != null) {
            bar.getLayoutParams().height = barHeight;
        }

        /* the tab is a whole number of texels wide, so the seam inside the
         * plate lands on a texel boundary and its two columns come out equal */
        int tabTexels = Math.max(2, Math.round(TAB_WIDTH_TO_BAR * BAR_TEXELS));
        UclTopBarPlate plate = root.findViewById(R.id.ucl_topbar_plate);
        if (plate != null) plate.setTabTexels(tabTexels);

        TextView title = root.findViewById(R.id.ucl_topbar_title);
        if (title != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, titlePx);
            Typeface typeface = titleTypeface(activity);
            if (typeface != null) title.setTypeface(typeface);
            title.setTextColor(ContextCompat.getColor(activity, R.color.ucl_topbar_ink));
            title.setIncludeFontPadding(false);
            /* Drawing-only offset: the layout centred the line box, the ink
             * still needs lifting to reach the optical centre. The fraction
             * below was measured for Noto; the display face has its own
             * metrics, so it gets its own number. */
            float rise = UclTypeface.isDisplay(typeface)
                    ? UclTypeface.capRise(title)
                    : titlePx * TITLE_RISE_TO_FONT;
            title.setTranslationY(-rise);
            sizeBackArrow(activity, root, titlePx, barHeight);
        }

        /* The corner tab is a segment of the bar, so its width comes from the
         * bar's texel grid, not from a dp figure of its own: 61 texels. Its
         * height is the bar's height, handed in rather than measured, so the
         * two cannot disagree. Everything inside it is sized from the same
         * number, which is why the label sits where the artwork puts it. */
        View action = root.findViewById(R.id.ucl_topbar_action);
        if (action != null) {
            UclAnchor.apply(action, UclAnchor.RIGHT_CENTER,
                    tabTexels * texel, barHeight);
            sizeTabContent(activity, root, barHeight);
        }
    }

    /**
     * Shows or hides the bar's trailing segment.
     *
     * <p>The tab belongs to the bar, but not every bar has one: only a page
     * with something to fold into a drawer carries it. Both halves of it are
     * switched together — the segment itself and the seam the bar paints at its
     * leading edge — so a page without the tab has a plain bar rather than a
     * bar with a line where the tab used to be.
     */
    public static void setTabVisible(View root, boolean visible) {
        if (root == null) return;
        View action = root.findViewById(R.id.ucl_topbar_action);
        if (action != null) action.setVisibility(visible ? View.VISIBLE : View.GONE);
        UclTopBarPlate plate = root.findViewById(R.id.ucl_topbar_plate);
        if (plate != null) plate.setTabVisible(visible);
    }

    /**
     * Sizes what sits on the tab from the bar's height alone.
     *
     * <p>These used to be dp and sp: 15 dp icon, 13 sp label, 20 dp inset. On a
     * bar whose height is a texel grid those figures are unrelated to it, so
     * the icon drifted off the seam by a different amount on every density.
     * Everything is now the same fraction of the bar, in whole pixels.
     */
    private static void sizeTabContent(Activity activity, View root, int barHeight) {
        float textPx = TAB_TEXT_TO_BAR * barHeight;
        View row = root.findViewById(R.id.ucl_topbar_action_row);
        if (row != null) {
            row.setPadding(Math.round(TAB_PAD_START_TO_BAR * barHeight), 0,
                    Math.round(TAB_PAD_END_TO_BAR * barHeight), 0);
            /* Same lift as the title, in the same unit: the bar centres a line
             * box, and in this typeface the ink sits below that box by a fixed
             * fraction of an em. The title is lifted by that fraction, so the
             * tab has to be lifted by its own fraction or the two read at
             * different heights even though both are centred. */
            row.setTranslationY(-textPx * TITLE_RISE_TO_FONT);
        }

        View icon = root.findViewById(R.id.ucl_topbar_action_icon);
        if (icon != null && icon.getLayoutParams() != null) {
            int size = Math.max(1, Math.round(TAB_ICON_TO_BAR * barHeight));
            ViewGroup.LayoutParams lp = icon.getLayoutParams();
            lp.width = size;
            lp.height = size;
            icon.setLayoutParams(lp);
        }

        TextView label = root.findViewById(R.id.ucl_topbar_action_label);
        if (label != null) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
            label.setTypeface(UclTypeface.title(activity));
            ViewGroup.LayoutParams lp = label.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMarginStart(
                        Math.round(TAB_GAP_TO_BAR * barHeight));
                label.setLayoutParams(lp);
            }
        }
    }

    /** The title font. Bundled, never the system default. */
    @Nullable
    public static Typeface titleTypeface(Activity activity) {
        return UclTypeface.title(activity);
    }

    /** Changes the title text, keeping every other property from install(). */
    public static void setTitle(View root, CharSequence text) {
        if (root == null) return;
        TextView title = root.findViewById(R.id.ucl_topbar_title);
        if (title != null) title.setText(text);
    }

    public static void setTitle(View root, int textRes) {
        if (root == null) return;
        TextView title = root.findViewById(R.id.ucl_topbar_title);
        if (title != null) title.setText(textRes);
    }

    /**
     * Sizes the arrow against the title and centres it inside the bar, which
     * doubles as the tap target.
     *
     * <p>The bar is only ~7.8% of the screen height, so a 44dp square would
     * spill out of it on a phone; using the bar height for the box keeps the
     * target generous without overlapping the page below. The drawable keeps
     * its own aspect ratio through fitCenter, so the padding is what decides
     * the visible size, not the view box.
     */
    private static void sizeBackArrow(Activity activity, View root, float titlePx, int barHeight) {
        View back = root.findViewById(R.id.ucl_topbar_back);
        if (back == null || back.getLayoutParams() == null) return;

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        float iconHeight = titlePx * BACK_ICON_TO_TITLE;
        float iconWidth = iconHeight * BACK_ICON_ASPECT;
        float minTouch = BACK_MIN_TOUCH_DP * dm.density;

        float boxHeight = barHeight > 0 ? barHeight : Math.max(iconHeight, minTouch);
        float boxWidth = Math.max(minTouch, iconWidth + 2 * 8 * dm.density);
        int paddingX = Math.max(0, Math.round((boxWidth - iconWidth) / 2f));
        int paddingY = Math.max(0, Math.round((boxHeight - iconHeight) / 2f));

        ViewGroup.LayoutParams lp = back.getLayoutParams();
        lp.width = Math.round(boxWidth);
        lp.height = Math.round(boxHeight);
        /* keep the arrow itself at the same inset from the screen edge as the
         * design, and let the padding grow inward instead */
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            int inset = Math.round(20 * dm.density);
            ((ViewGroup.MarginLayoutParams) lp).setMarginStart(Math.max(0, inset - paddingX));
        }
        back.setLayoutParams(lp);

        back.setPadding(paddingX, paddingY, paddingX, paddingY);
    }
}

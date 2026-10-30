package net.kdt.pojavlaunch.mcui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

/**
 * Where a view sits inside the bar, said once and said in one place.
 *
 * <p>The top bar used to place its children with a handful of
 * {@code layout_alignParent*} attributes spread through the XML, while the bar
 * height itself was decided at runtime and written back through
 * {@code getLayoutParams().height} from a different file. Nothing owned the
 * answer to "where is this thing", so a child could pick up a top rule from the
 * layout, a bottom rule from a stale layout pass, and a height from a third
 * place, and still look plausible in the editor. That is exactly how the corner
 * tab ended up with a foot of its own: it was aligned top and bottom in the XML,
 * but its stretch marks pinned its foot elsewhere, and no single line was wrong
 * enough to spot.
 *
 * <p>An anchor is two slot values packed into one int, one for the horizontal
 * axis and one for the vertical:
 *
 * <ul>
 *   <li>{@link #START} pin the leading edge; the view keeps its own size</li>
 *   <li>{@link #CENTER} centre on that axis; the view keeps its own size</li>
 *   <li>{@link #END} pin the trailing edge; the view keeps its own size</li>
 *   <li>{@link #FILL} pin both edges; the view takes the whole axis</li>
 * </ul>
 *
 * <p>{@link #RIGHT_CENTER} therefore means "trailing edge on the container's
 * trailing edge, centred vertically", and {@link #RIGHT_FILL} means "from the
 * container's top edge to its bottom edge, against its trailing edge". A
 * segment of the bar wants the second one when the bar's height is computed at
 * runtime, because only then does the segment's foot land on the bar's foot at
 * every screen size instead of at the size the layout happened to assume.
 *
 * <p>The rules emitted are the ordinary {@link RelativeLayout} rules. The point
 * of the class is not new layout behaviour, it is that the position of a child
 * becomes a single value that the same code which knows the container's height
 * can also set.
 */
public final class UclAnchor {

    /** Pin the leading edge. */
    public static final int START = 0;
    /** Centre on the axis. */
    public static final int CENTER = 1;
    /** Pin the trailing edge. */
    public static final int END = 2;
    /** Pin both edges: the view takes the whole axis. */
    public static final int FILL = 3;

    private static final int H_SHIFT = 0;
    private static final int V_SHIFT = 2;
    private static final int SLOT_MASK = 0x3;

    public static final int LEFT_TOP = slot(START, START);
    public static final int LEFT_CENTER = slot(START, CENTER);
    public static final int LEFT_BOTTOM = slot(START, END);
    public static final int LEFT_FILL = slot(START, FILL);

    public static final int CENTER_TOP = slot(CENTER, START);
    public static final int CENTER_MIDDLE = slot(CENTER, CENTER);
    public static final int CENTER_BOTTOM = slot(CENTER, END);
    public static final int CENTER_FILL = slot(CENTER, FILL);

    public static final int RIGHT_TOP = slot(END, START);
    public static final int RIGHT_CENTER = slot(END, CENTER);
    public static final int RIGHT_BOTTOM = slot(END, END);
    public static final int RIGHT_FILL = slot(END, FILL);

    /** Leaves a dimension as the view already had it. */
    public static final int SIZE_UNSET = -1;

    private UclAnchor() {
    }

    private static int slot(int horizontal, int vertical) {
        return (horizontal << H_SHIFT) | (vertical << V_SHIFT);
    }

    /** Places the view. Its current size is left alone. */
    public static void apply(View view, int anchor) {
        apply(view, anchor, SIZE_UNSET, SIZE_UNSET);
    }

    /** Places the view and gives it a height. Negative leaves the height alone. */
    public static void apply(View view, int anchor, int height) {
        apply(view, anchor, SIZE_UNSET, height);
    }

    /**
     * Places the view, optionally resizing it.
     *
     * <p>A fresh {@link RelativeLayout.LayoutParams} is built rather than
     * editing the one in place: {@code addRule} only ever adds, so a view that
     * moves from one anchor to another would keep the rules from where it used
     * to be, and a top rule left over from a previous anchor is precisely the
     * kind of ghost that makes a child look pinned to something it is not.
     * Margins are carried over because they are the caller's business, not the
     * anchor's.
     *
     * @throws IllegalArgumentException if the view does not live in a
     *         {@link RelativeLayout}. Failing here is deliberate: a silently
     *         misplaced child is what this class exists to stop.
     */
    public static void apply(View view, int anchor, int width, int height) {
        if (view == null) return;
        ViewGroup.LayoutParams current = view.getLayoutParams();
        if (!(current instanceof RelativeLayout.LayoutParams)) {
            throw new IllegalArgumentException(
                    "UclAnchor needs a RelativeLayout parent, not " + current);
        }

        int w = width == SIZE_UNSET ? current.width : width;
        int h = height == SIZE_UNSET ? current.height : height;

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(w, h);
        RelativeLayout.LayoutParams old = (RelativeLayout.LayoutParams) current;
        lp.setMargins(old.leftMargin, old.topMargin, old.rightMargin, old.bottomMargin);

        int horizontal = (anchor >> H_SHIFT) & SLOT_MASK;
        int vertical = (anchor >> V_SHIFT) & SLOT_MASK;

        switch (horizontal) {
            case START:
                lp.addRule(RelativeLayout.ALIGN_PARENT_START);
                break;
            case CENTER:
                lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
                break;
            case END:
                lp.addRule(RelativeLayout.ALIGN_PARENT_END);
                break;
            case FILL:
                lp.addRule(RelativeLayout.ALIGN_PARENT_START);
                lp.addRule(RelativeLayout.ALIGN_PARENT_END);
                break;
            default:
                break;
        }

        switch (vertical) {
            case START:
                lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                break;
            case CENTER:
                lp.addRule(RelativeLayout.CENTER_VERTICAL);
                break;
            case END:
                lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                break;
            case FILL:
                lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                break;
            default:
                break;
        }

        view.setLayoutParams(lp);
    }
}

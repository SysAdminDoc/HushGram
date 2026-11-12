/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.TwoStatePreference;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.hushgram.extension.shared.settings.preference.ImmediateAction;

/** Shared monochrome surfaces for HushGram settings and settings-owned dialogs. */
public final class ScreenColors {
    static final int SURFACE = 0xFF0E0E0E;
    static final int SURFACE_CONTAINER = 0xFF1B1B1B;
    static final int SURFACE_CONTAINER_HIGH = 0xFF262626;
    static final int ON_SURFACE = 0xFFF4F4F4;
    static final int ON_SURFACE_VARIANT = 0xFFABABAB;
    static final int OUTLINE = 0xFF757575;
    static final int OUTLINE_VARIANT = 0xFF3A3A3A;
    static final int PRIMARY = 0xFFFFFFFF;
    static final int ON_PRIMARY = 0xFF101010;
    static final int RIPPLE = 0x26FFFFFF;
    static final int CORNER_XL = 28;
    static final int CORNER_L = 24;
    static final int CORNER_JOINT = 4;
    static final int CORNER_FULL = 200;
    static final int ROW_GAP_DP = 3;
    /** The page, behind the title bar and every row. */
    public final int background;
    /** A dialog's surface. */
    public final int dialog;
    /** Row and dialog titles, the title bar and its back arrow. */
    public final int title;
    /** Row summaries and a dialog's message. */
    public final int summary;
    /**
     * A dialog field's placeholder. Quieter than {@link #summary}, so an example in an empty field
     * doesn't read as a value already saved, and still 4.5:1 on every surface.
     */
    public final int hint;
    /** Section titles. */
    public final int heading;
    /** A switch that's on, and the buttons of a dialog. */
    public final int accent;
    /** A switch that's off. */
    public final int switchOff;
    /** Grouped preference rows and their edge. */
    public final int card;
    public final int outline;
    /** Tonal surface used by secondary controls. */
    public final int iconSurface;
    /** Text on the filled primary dialog action. */
    public final int onAccent;
    /** Calm validation feedback on the charcoal dialog surface. */
    public final int error;

    public static final ScreenColors DEFAULT = new ScreenColors();

    /** Returns the single monochrome palette used by the settings UI. */
    public static ScreenColors current() {
        return DEFAULT;
    }

    ScreenColors() {
        background = SURFACE;
        dialog = SURFACE_CONTAINER;
        title = ON_SURFACE;
        summary = ON_SURFACE_VARIANT;
        hint = ON_SURFACE_VARIANT;
        heading = ON_SURFACE;
        accent = PRIMARY;
        switchOff = OUTLINE;
        card = SURFACE_CONTAINER;
        outline = OUTLINE_VARIANT;
        iconSurface = SURFACE_CONTAINER_HIGH;
        onAccent = ON_PRIMARY;
        error = PRIMARY;
    }

    /**
     * [color], at Material's 38% on a disabled row. A plain colour replaced the theme's own
     * disabled state, so a row that ignored taps, such as Import while an export runs, looked
     * the same as one that would take them.
     */
    static ColorStateList dimmedWhenDisabled(int color) {
        int dimmed = (color & 0x00FFFFFF) | 0x61000000;
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{dimmed, color});
    }

    /**
     * [color] for a disabled row's summary, at 75%. The summary is where a greyed-out row says
     * what to turn on first, and at 38% it read at about 2:1 on the card. The title and switch
     * keep the 38% that shows the row is off.
     */
    static ColorStateList readableWhenDisabled(int color) {
        int dimmed = (color & 0x00FFFFFF) | 0xC0000000;
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{dimmed, color});
    }

    /** Native list navigation uses selected/activated as well as focused. Keep each visible. */
    ColorStateList interactionOutline(int normal) {
        return interactionOutline(normal, heading);
    }

    private ColorStateList interactionOutline(int normal, int focused) {
        return new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled}, {android.R.attr.state_focused},
                {android.R.attr.state_hovered}, {android.R.attr.state_selected},
                {android.R.attr.state_activated}, {android.R.attr.state_checked}, {}},
                new int[]{(normal & 0x00FFFFFF) | (Math.round(Color.alpha(normal) * 0.38f) << 24),
                        focused, focused, focused, focused, focused, normal});
    }

    static ColorStateList interactionRipple() {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{Color.TRANSPARENT, RIPPLE});
    }

    ColorStateList choiceFill(int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{(accent & 0x00FFFFFF) | 0x22000000, normal});
    }

    /** Secondary actions use the primary text color without a filled-button surface. */
    int secondaryActionText() {
        return heading;
    }

    /** Framework theme used to construct rows before the settings palette paints them. */
    static int themeFor() {
        return android.R.style.Theme_Material_NoActionBar;
    }

    /** A row's title and summary, its switch if it has one, and its chevron if a tap opens something. */
    void paintRow(View row, Preference preference) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(dimmedWhenDisabled(this.title));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    "hushgram_status".equals(preference.getKey()) ? 14 : 16);
            title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            if (title.getParent() instanceof View) {
                View text = (View) title.getParent();
                text.setPadding(0, 0, 0, 0);
                text.setMinimumHeight(0);
            }
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextColor(readableWhenDisabled(this.summary));
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            summary.setLineSpacing(0, 1.15f);
        }
        paintChevron(row, preference);
        ImageView icon = row.findViewById(android.R.id.icon);
        // Decoration: the row's title already says what it is, so a screen reader skips the icon.
        if (icon != null) icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (icon != null && preference.getIcon() != null) {
            ViewGroup.LayoutParams imageSize = icon.getLayoutParams();
            imageSize.width = dp(row, 24);
            imageSize.height = dp(row, 24);
            icon.setLayoutParams(imageSize);
            icon.setPadding(0, 0, 0, 0);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setImageTintList(dimmedWhenDisabled(heading));
            icon.setBackground(null);
            if (icon.getParent() instanceof ViewGroup) {
                ViewGroup frame = (ViewGroup) icon.getParent();
                frame.setPaddingRelative(0, 0, dp(row, 14), 0);
                ViewGroup.LayoutParams size = frame.getLayoutParams();
                size.width = dp(row, 38);
                frame.setLayoutParams(size);
            }
        }
        paintSurface(row, preference, opensCard(preference), closesCard(preference));
    }

    /**
     * Whether [preference] is the top row of the card it sits in. A section's rows share one card.
     * On the page itself, the rows that open each category's page share one too, and Report a
     * problem and Support HushGram join the About row they follow; every other row there is a card
     * of its own.
     */
    static boolean opensCard(Preference preference) {
        PreferenceGroup parent = preference.getParent();
        if (parent == null) return true;
        int at = indexIn(parent, preference);
        if (at <= 0) return true;
        return !(parent instanceof PreferenceCategory) && !sharesCard(parent.getPreference(at - 1), preference);
    }

    /** Whether [preference] is the bottom row of its card. */
    static boolean closesCard(Preference preference) {
        PreferenceGroup parent = preference.getParent();
        if (parent == null) return true;
        int at = indexIn(parent, preference);
        if (at < 0 || at == parent.getPreferenceCount() - 1) return true;
        return !(parent instanceof PreferenceCategory) && !sharesCard(preference, parent.getPreference(at + 1));
    }

    /**
     * Two neighbours on the page itself that draw as one card: both open a page, or one of the two
     * last rows (Report a problem, Support HushGram) follows a page's row or the other one.
     */
    private static boolean sharesCard(Preference above, Preference below) {
        return (opensPage(above) && (opensPage(below) || closesPage(below))) || (closesPage(above) && closesPage(below));
    }

    /** Report a problem and Support HushGram, which end the page. */
    private static boolean closesPage(Preference preference) {
        String key = preference.getKey();
        return HushgramPreferenceFragment.REPORT_ROW_KEY.equals(key) || HushgramPreferenceFragment.SUPPORT_ROW_KEY.equals(key);
    }

    private static boolean opensPage(Preference preference) {
        String key = preference.getKey();
        return key != null && key.startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY);
    }

    /** Where [preference] stands among the rows [group] shows, which it keeps in their order. */
    private static int indexIn(PreferenceGroup group, Preference preference) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == preference) return i;
        return -1;
    }

    /**
     * Group boundaries follow the visible rows, including a filtered search result. The card's
     * height is the row's: its text wraps at any font size and the row grows to fit.
     */
    void paintSurface(View row, Preference preference, boolean first, boolean last) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(card);
        float top = dp(row, first ? CORNER_L : CORNER_JOINT);
        float bottom = dp(row, last ? CORNER_L : CORNER_JOINT);
        surface.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        // The page-colored gap between separate row surfaces replaces divider lines. Split odd
        // pixel values between top and bottom so adjacent rows keep the full configured gap.
        int gap = dp(row, ROW_GAP_DP);
        Drawable inset = new InsetDrawable(surface, 0, gap / 2, 0, gap - gap / 2);
        row.setBackground(preference.isSelectable()
                ? new RippleDrawable(ColorStateList.valueOf(RIPPLE), inset, null).mutate() : inset);
        row.setPaddingRelative(dp(row, 20), dp(row, 16), dp(row, 20), dp(row, 16));
        row.setMinimumHeight(dp(row, "hushgram_status".equals(preference.getKey()) ? 56 : 64));
    }

    /** A section title in the overview or search results. */
    void paintHeading(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(summary);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setLetterSpacing(0.01f);
            title.setAllCaps(false);
        }
        row.setPaddingRelative(dp(row, 20), dp(row, 12), dp(row, 20), dp(row, 8));
    }

    /** A category page headline, using Oxbow's large-title hierarchy without a description. */
    void paintPageHeading(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(ON_SURFACE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setLetterSpacing(-0.02f);
            title.setAllCaps(false);
        }
        row.setPaddingRelative(dp(row, 20), dp(row, 16), dp(row, 20), dp(row, 8));
    }

    /** Marks the chevron a row was given, so a recycled row's can be found and taken off. */
    static final String CHEVRON = "hushgram_chevron";

    /**
     * Whether a tap on [preference] opens something: a dialog, a file picker, the browser. A
     * switch says what a tap does with its switch, and a row that acts the moment it's tapped
     * goes without a chevron, as the shared ImmediateAction asks, so the chevron keeps one meaning.
     */
    static boolean opensSomething(Preference preference) {
        return preference.isSelectable() && !(preference instanceof TwoStatePreference)
                && !(preference instanceof ImmediateAction && ((ImmediateAction) preference).actsOnTap());
    }

    /**
     * The chevron at the end of every row a tap opens something from. Only its title's colour set
     * such a row apart before, which is how "Licenses" and "Version" looked alike (WCAG 1.4.1). It
     * sits in the row's widget frame, where a switch row keeps its switch. Rows are recycled, and
     * one Row class draws plain lines too, so a row that shouldn't have one has it taken off.
     */
    private void paintChevron(View row, Preference preference) {
        View found = row.findViewById(android.R.id.widget_frame);
        if (!(found instanceof ViewGroup)) return;
        ViewGroup frame = (ViewGroup) found;
        View chevron = frame.findViewWithTag(CHEVRON);
        if (!opensSomething(preference)) {
            if (chevron != null) {
                frame.removeView(chevron);
                // Preference hides an empty frame only when it builds the row, which a recycled row
                // skips, and an empty frame still takes its padding's width from the text.
                if (frame.getChildCount() == 0) frame.setVisibility(View.GONE);
            }
            return;
        }
        ImageView image;
        if (chevron instanceof ImageView) {
            image = (ImageView) chevron;
        } else {
            image = new ImageView(row.getContext());
            image.setTag(CHEVRON);
            // The row already tells a screen reader it's a button; the shape adds nothing to hear.
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(image, new LinearLayout.LayoutParams(dp(row, 16), dp(row, 24)));
        }
        Drawable drawn = image.getDrawable();
        int direction = preference instanceof HushgramPreferenceFragment.Heading
                ? ((HushgramPreferenceFragment.Heading) preference).expanded ? -1 : 1 : 0;
        if (!(drawn instanceof Chevron) || ((Chevron) drawn).color != summary
                || ((Chevron) drawn).direction != direction) {
            image.setImageDrawable(new Chevron(summary, dp(row, 2), direction));
        }
        image.setEnabled(preference.isEnabled());
        frame.setVisibility(View.VISIBLE);
    }

    /**
     * A chevron drawn in code, since Instagram's APK gives the patch no resource for one. It points the way
     * the row reads, so it mirrors in a right-to-left layout, and it dims with its row.
     */
    static final class Chevron extends Drawable {
        /** The colour it's drawn in on a row that can be tapped now. */
        final int color;
        /** Zero follows reading direction; positive opens down, negative collapses up. */
        final int direction;
        private final ColorStateList colors;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Chevron(int color, float stroke) {
            this(color, stroke, 0);
        }

        Chevron(int color, float stroke, int direction) {
            this.color = color;
            this.direction = direction;
            colors = dimmedWhenDisabled(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        /**
         * One arm's end, the tip and the other arm's end, as x and y pairs. The tip is at the end
         * the row reads towards: the right, or the left in a right-to-left layout. Material's own
         * chevron is 4.6 dp wide and 9.2 dp tall in a 24 dp icon, and this keeps that shape.
         */
        float[] points() {
            Rect bounds = getBounds();
            float half = bounds.height() * 0.19f;
            float reach = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -half / 2 : half / 2;
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            if (direction != 0) {
                float tip = half / 2 * direction;
                return new float[]{x - half, y - tip, x, y + tip, x + half, y - tip};
            }
            return new float[]{x - reach, y - half, x + reach, y, x - reach, y + half};
        }

        /** The colour it draws in now, which follows its row's state. */
        int currentColor() {
            return paint.getColor();
        }

        @Override
        public void draw(Canvas canvas) {
            float[] at = points();
            path.rewind();
            path.moveTo(at[0], at[1]);
            path.lineTo(at[2], at[3]);
            path.lineTo(at[4], at[5]);
            canvas.drawPath(path, paint);
        }

        @Override
        public boolean isAutoMirrored() {
            return true;
        }

        @Override
        public boolean onLayoutDirectionChanged(int layoutDirection) {
            invalidateSelf();
            return true;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            int now = colors.getColorForState(state, color);
            if (now == paint.getColor()) return false;
            paint.setColor(now);
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Recovery is a choice, not another pair of settings rows. */
    private void paintRecoveryAction(View row, boolean primary) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(dimmedWhenDisabled(primary ? onAccent : secondaryActionText()));
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setGravity(android.view.Gravity.CENTER);
            // Centred by gravity, which a start alignment from showAllText would override.
            title.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
            ViewGroup.LayoutParams size = title.getLayoutParams();
            size.width = ViewGroup.LayoutParams.MATCH_PARENT;
            title.setLayoutParams(size);
        }
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(primary ? dimmedWhenDisabled(accent) : ColorStateList.valueOf(iconSurface));
        surface.setCornerRadius(dp(row, CORNER_FULL));
        if (!primary) surface.setStroke(dp(row, 1), outline);
        row.setBackground(new RippleDrawable(interactionRipple(),
                new InsetDrawable(surface, dp(row, 18), dp(row, 4), dp(row, 18), dp(row, 4)), null).mutate());
        row.setMinimumHeight(dp(row, 56));
        row.setPadding(dp(row, 32), dp(row, 8), dp(row, 32), dp(row, 8));
    }

    static void recoveryMessage(View row) {
        ScreenColors palette = DEFAULT;
        HushgramPreferenceFragment.showAllText(row);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        // Centred across the row: text wrapped to its own width has nothing to centre in.
        if (title != null) {
            fillWidth(title);
            title.setGravity(android.view.Gravity.CENTER);
            // Centred by gravity, which a start alignment from showAllText would override.
            title.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
            title.setTextSize(22);
            title.setTextColor(palette.title);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            Drawable icon = SettingsIcons.icon(row.getContext(), SettingsIcons.ABOUT, palette.heading);
            icon.setBounds(0, 0, dp(row, 56), dp(row, 56));
            title.setCompoundDrawablesRelative(null, icon, null, null);
            title.setCompoundDrawablePadding(dp(row, 24));
        }
        if (summary != null) {
            fillWidth(summary);
            summary.setGravity(android.view.Gravity.CENTER);
            summary.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
            summary.setTextColor(palette.summary);
            summary.setTextSize(16);
            summary.setPadding(0, dp(row, 12), 0, 0);
        }
        row.setPadding(dp(row, 32), dp(row, 100), dp(row, 32), dp(row, 56));
    }

    private static void fillWidth(View text) {
        ViewGroup.LayoutParams size = text.getLayoutParams();
        if (size == null) return;
        size.width = ViewGroup.LayoutParams.MATCH_PARENT;
        text.setLayoutParams(size);
    }

    /** A dialog on show: its surface, title, message, real list choices, buttons and text field. */
    void paint(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(this.dialog);
            panel.setCornerRadius(dp(window.getDecorView(), CORNER_XL));
            panel.setStroke(dp(window.getDecorView(), 1), OUTLINE_VARIANT);
            window.setBackgroundDrawable(panel);
            int width = Math.min(dialog.getContext().getResources().getDisplayMetrics().widthPixels
                    - dp(window.getDecorView(), 32), dp(window.getDecorView(), 560));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        @SuppressWarnings("DiscouragedApi")
        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title != null) {
            title.setTextColor(this.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
        }
        // AlertDialog's own message, and the one a preference's dialog layout brings, which is the
        // one on show there: AlertDialog's own sits GONE above it, and findViewById finds that first.
        if (window != null) paintMessages(window.getDecorView());
        android.widget.ListView choices = dialog.getListView();
        if (choices != null && choices.getAdapter() != null && !(choices.getAdapter() instanceof DialogChoices)) {
            DialogChoices.swap(choices, new DialogChoices(choices.getAdapter(), this));
            choices.setDivider(null);
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            paintAction(button, which == AlertDialog.BUTTON_POSITIVE);
            // Keep the framework's 48dp touch area clear of the dialog's edges.
            ((RippleDrawable) button.getBackground()).setLayerInsetRelative(
                    0, dp(button, 4), dp(button, 6), dp(button, 4), dp(button, 6));
        }
        View field = dialog.findViewById(android.R.id.edit);
        if (field instanceof EditText) paintField((EditText) field);
        if (window != null) startTextAtTheStart(window.getDecorView());
    }

    /**
     * Text set to start at its start edge starts at the layout's: on the right in a right-to-left
     * layout, under the title, where HushGram's English otherwise sits flush left. Centred text and
     * buttons keep their gravity, a field keeps the direction of what's typed, and a list's rows
     * are its adapter's.
     */
    public static void startTextAtTheStart(View view) {
        if (view instanceof android.widget.AdapterView) return;
        if (view instanceof TextView && !(view instanceof EditText)) {
            TextView text = (TextView) view;
            if ((text.getGravity() & android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == android.view.Gravity.START
                    && text.getTextAlignment() == View.TEXT_ALIGNMENT_GRAVITY) {
                text.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) startTextAtTheStart(group.getChildAt(index));
        }
    }

    /**
     * A dialog's text field. The framework theme draws its underline, cursor and selection in
     * the framework's teal, so they take the accent the buttons have.
     */
    void paintField(EditText field) {
        field.setHintTextColor(dimmedWhenDisabled(hint));
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(dialog);
        outline.setCornerRadius(dp(field, 16));
        outline.setStroke(dp(field, 2), interactionOutline(switchOff));
        field.setBackgroundTintList(null);
        field.setBackground(outline);
        field.setPaddingRelative(dp(field, 12), dp(field, 10), dp(field, 12), dp(field, 10));
        field.setTextColor(dimmedWhenDisabled(title));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setMinHeight(dp(field, 48));
        field.setHighlightColor(half(accent));
        // Android 9 has no setters for the cursor and handles, so they keep the framework's colour.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        Drawable cursor = field.getTextCursorDrawable();
        if (cursor != null) {
            cursor = cursor.mutate();
            cursor.setTint(accent);
            field.setTextCursorDrawable(cursor);
        }
        Drawable handle = field.getTextSelectHandle();
        if (handle != null) {
            handle = handle.mutate();
            handle.setTint(accent);
            field.setTextSelectHandle(handle);
        }
        Drawable left = field.getTextSelectHandleLeft();
        if (left != null) {
            left = left.mutate();
            left.setTint(accent);
            field.setTextSelectHandleLeft(left);
        }
        Drawable right = field.getTextSelectHandleRight();
        if (right != null) {
            right = right.mutate();
            right.setTint(accent);
            field.setTextSelectHandleRight(right);
        }
    }

    private void paintMessages(View view) {
        if (view instanceof TextView && view.getId() == android.R.id.message) {
            TextView message = (TextView) view;
            message.setTextColor(dimmedWhenDisabled(summary));
            message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            message.setLineSpacing(dp(view, 2), 1);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) paintMessages(group.getChildAt(index));
        }
    }

    /** Paints a row with the settings palette. */
    static void row(View row, Preference preference) {
        ScreenColors used = current();
        used.paintRow(row, preference);
        NewRowDots.paint(row, preference, used.accent);
    }

    /** Paints a section title with the settings palette. */
    static void heading(View row) {
        current().paintHeading(row);
    }

    /** Styles the page-level category headline without changing its persisted Preference object. */
    static void pageHeading(View row) {
        current().paintPageHeading(row);
    }

    /** Paints both recovery actions even if the ordinary settings page failed to initialize. */
    static void recoveryAction(View row, boolean primary) {
        DEFAULT.paintRecoveryAction(row, primary);
    }

    /** Paints a dialog with the settings palette. */
    public static void dialog(@Nullable AlertDialog dialog) {
        current().paint(dialog);
    }

    /** Styles a runtime dialog's own input without changing its validation or input type. */
    public static void styleField(EditText field) {
        current().paintField(field);
    }

    /** Styles an existing action without replacing its content, listeners or accessibility delegate. */
    public static void styleAction(TextView action, boolean primary) {
        current().paintAction(action, primary);
    }

    private void paintAction(TextView action, boolean primary) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(dimmedWhenDisabled(primary ? accent : iconSurface));
        surface.setCornerRadius(dp(action, CORNER_FULL));
        if (!primary) surface.setStroke(dp(action, 1), interactionOutline(outline));
        action.setBackgroundTintList(null);
        // View's tint pass mutates the background after setting its state. Mutate first so
        // that pass cannot replace enabled children with fresh, empty-state copies.
        action.setBackground(new RippleDrawable(
                interactionRipple(), surface, null).mutate());
        action.setTextColor(dimmedWhenDisabled(primary ? onAccent : title));
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        action.setAllCaps(false);
        action.setLetterSpacing(0f);
        action.setStateListAnimator(null);
        action.setSingleLine(false);
        action.setMaxLines(Integer.MAX_VALUE);
        action.setEllipsize(null);
        action.setHorizontallyScrolling(false);
        action.setGravity(android.view.Gravity.CENTER);
        action.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        action.setMinHeight(dp(action, 52));
        action.setMinimumHeight(dp(action, 52));
        action.setMinWidth(dp(action, 64));
        action.setMinimumWidth(dp(action, 64));
        action.setPaddingRelative(dp(action, 20), 0, dp(action, 20), 0);
    }

    /** Compact actions retain the shared interaction states without becoming filled buttons. */
    public static void styleIconAction(View action) {
        ScreenColors colors = current();
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.TRANSPARENT);
        surface.setCornerRadius(dp(action, CORNER_FULL));
        surface.setStroke(dp(action, 1), colors.interactionOutline(OUTLINE_VARIANT));
        action.setBackgroundTintList(null);
        action.setBackground(new RippleDrawable(interactionRipple(), surface, null).mutate());
    }

    private static int dp(View view, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics()));
    }

    /** A switch's track: the thumb's colour at half strength, as Material's own switch draws it. */
    public static int half(int color) {
        return (color & 0x00FFFFFF) | 0x80000000;
    }
}

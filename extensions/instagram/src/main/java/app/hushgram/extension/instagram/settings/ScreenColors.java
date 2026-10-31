/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import android.app.AlertDialog;
import android.content.Context;
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
import android.preference.PreferenceScreen;
import android.preference.TwoStatePreference;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.hushgram.extension.shared.settings.preference.ImmediateAction;

/** Shared black and charcoal surfaces, in Instagram's own blues, for HushGram's settings and runtime controls. */
public final class ScreenColors {
    public final boolean light;
    /** The page, behind the title bar and every row. */
    public final int background;
    /** A dialog's surface. */
    public final int dialog;
    /** Row and dialog titles, the title bar and its back arrow. */
    public final int title;
    /** Row summaries and a dialog's message. */
    public final int summary;
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

    /** The colours the screen on show was built with, or null for the black page. */
    @Nullable
    static volatile ScreenColors shown;
    public static final ScreenColors DEFAULT = new ScreenColors(SettingsStatus.pureBlack());

    /**
     * [pureBlack]: the build has Pure black dark mode, so the cards and dialogs go black like
     * Instagram's own sheets do, and their outlines keep them apart (#374).
     */
    ScreenColors(boolean pureBlack) {
        light = false;
        background = Color.BLACK;
        dialog = pureBlack ? Color.BLACK : 0xFF18161D;
        title = 0xFFF8F5F8;
        summary = 0xFFAAA5AF;
        // Instagram's own blues (AccentColor.role): its link on a dark screen, 7:1 on the charcoal,
        // and its Prism indigo button fill, which white text reads on at 5:1.
        heading = 0xFF85A1FF;
        accent = 0xFF4A5DF9;
        // At least 3:1 against either charcoal surface, with a white thumb above 5:1.
        switchOff = 0xFF696370;
        card = pureBlack ? Color.BLACK : 0xFF151419;
        outline = 0xFF2D2832;
        iconSurface = 0xFF211D27;
        onAccent = Color.WHITE;
        error = 0xFFFFB4AB;
    }

    /** A switch's white thumb remains distinct from either track. */
    int offThumb() {
        return light ? title : onAccent;
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

    static ColorStateList interactionRipple(int color) {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{Color.TRANSPARENT, (color & 0x00FFFFFF) | 0x33000000});
    }

    ColorStateList choiceFill(int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{(accent & 0x00FFFFFF) | 0x22000000, normal});
    }

    /** The lighter blue passes text contrast on charcoal, unlike the filled action's accent. */
    int secondaryActionText() {
        return heading;
    }

    /** The colours for a screen shown in this context. HushGram has one page, black, so always null. */
    @Nullable
    static ScreenColors forScreen(Context context) {
        return null;
    }

    /** The framework theme rows and dialogs are built with, so what this class doesn't paint still reads. */
    int theme() {
        return light ? android.R.style.Theme_Material_Light_NoActionBar : android.R.style.Theme_Material_NoActionBar;
    }

    /** {@link #theme()} of {@link #forScreen}, or the dark Material theme the black page has always had. */
    static int themeFor(Context context) {
        ScreenColors colors = forScreen(context);
        return colors == null ? android.R.style.Theme_Material_NoActionBar : colors.theme();
    }

    /** A row's title and summary, its switch if it has one, and its chevron if a tap opens something. */
    void paintRow(View row, Preference preference) {
        boolean overview = preference.getParent() instanceof PreferenceScreen;
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(dimmedWhenDisabled(this.title));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    "hushgram_status".equals(preference.getKey()) ? 14 : overview ? 15 : 16);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            if (title.getParent() instanceof View) {
                View text = (View) title.getParent();
                text.setPadding(0, 0, 0, 0);
                text.setMinimumHeight(0);
            }
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextColor(dimmedWhenDisabled(this.summary));
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, overview ? 12 : 13);
            summary.setLineSpacing(0, 1);
        }
        View widget = row.findViewById(android.R.id.switch_widget);
        if (widget instanceof Switch) {
            int[][] states = {{-android.R.attr.state_enabled, android.R.attr.state_checked},
                    {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}};
            Switch toggle = (Switch) widget;
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.RECTANGLE);
            thumb.setCornerRadius(dp(row, 4));
            thumb.setSize(dp(row, 20), dp(row, 20));
            thumb.setColor(Color.WHITE);
            GradientDrawable track = new GradientDrawable();
            track.setCornerRadius(dp(row, 6));
            track.setSize(dp(row, 40), dp(row, 24));
            track.setColor(Color.WHITE);
            toggle.setThumbDrawable(thumb);
            toggle.setTrackDrawable(track);
            toggle.setSwitchMinWidth(dp(row, 40));
            toggle.setSplitTrack(false);
            toggle.setThumbTintList(new ColorStateList(states, new int[]{half(onAccent), half(offThumb()), onAccent, offThumb()}));
            toggle.setTrackTintList(new ColorStateList(states, new int[]{half(accent), half(switchOff), accent, switchOff}));
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
        boolean overview = preference.getParent() instanceof PreferenceScreen;
        float radius = overview ? 0 : dp(row, 10);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(card);
        surface.setStroke(dp(row, 1), preference.isSelectable() ? interactionOutline(outline)
                : ColorStateList.valueOf(outline));
        surface.setCornerRadii(new float[]{
                first ? radius : 0, first ? radius : 0,
                first ? radius : 0, first ? radius : 0,
                last ? radius : 0, last ? radius : 0,
                last ? radius : 0, last ? radius : 0});
        int side = overview ? 0 : dp(row, 18);
        Drawable inset = new InsetDrawable(surface, side, overview ? -dp(row, 1) : first ? dp(row, 4) : -dp(row, 1),
                side, !overview && last ? dp(row, 4) : 0);
        row.setBackground(preference.isSelectable()
                ? new RippleDrawable(interactionRipple(heading), inset, null).mutate() : inset);
        // Explicit padding after the inset background prevents its padding from moving the text.
        int horizontal = overview ? 20 : 30;
        int vertical = overview ? 6 : 10;
        row.setPaddingRelative(dp(row, horizontal), dp(row, vertical + (!overview && first ? 4 : 0)),
                dp(row, horizontal), dp(row, vertical + (!overview && last ? 4 : 0)));
        row.setMinimumHeight(dp(row, "hushgram_view_toggle".equals(preference.getKey()) ? 48
                : "hushgram_status".equals(preference.getKey()) ? 56 : overview ? 52 : 64));
    }

    /** A section title. */
    void paintHeading(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(heading);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setLetterSpacing(0.055f);
            title.setAllCaps(true);
        }
        row.setPaddingRelative(dp(row, 22), dp(row, 20), dp(row, 22), dp(row, 8));
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
        surface.setColor(primary ? dimmedWhenDisabled(accent) : ColorStateList.valueOf(Color.TRANSPARENT));
        surface.setCornerRadius(dp(row, 12));
        surface.setStroke(dp(row, 2), interactionOutline(primary ? accent : outline,
                primary ? onAccent : heading));
        row.setBackground(new RippleDrawable(interactionRipple(primary ? onAccent : heading),
                new InsetDrawable(surface, dp(row, 18), dp(row, 4), dp(row, 18), dp(row, 4)), null).mutate());
        row.setMinimumHeight(dp(row, 56));
        row.setPadding(dp(row, 32), dp(row, 8), dp(row, 32), dp(row, 8));
    }

    static void recoveryMessage(View row) {
        ScreenColors palette = forScreen(row.getContext());
        if (palette == null) palette = DEFAULT;
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
            ShimmerTitle.wear(title);
            title.setTypeface(Typeface.create("sans-serif-medium", 0));
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
            panel.setCornerRadius(dp(window.getDecorView(), 12));
            panel.setStroke(dp(window.getDecorView(), 1), outline);
            window.setBackgroundDrawable(panel);
            int width = Math.min(dialog.getContext().getResources().getDisplayMetrics().widthPixels
                    - dp(window.getDecorView(), 32), dp(window.getDecorView(), 560));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        @SuppressWarnings("DiscouragedApi")
        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title != null) {
            // In the gradient, as every HushGram title is.
            ShimmerTitle.wear(title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
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
    }

    /**
     * A dialog's text field. The framework theme draws its underline, cursor and selection in
     * the framework's teal, so they take the accent the buttons have.
     */
    void paintField(EditText field) {
        field.setHintTextColor(dimmedWhenDisabled(summary));
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(dialog);
        outline.setCornerRadius(dp(field, 12));
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

    /** Paints a row with the colours on show, if there are any. */
    static void row(View row, Preference preference) {
        ScreenColors colors = shown;
        ScreenColors used = colors == null ? DEFAULT : colors;
        used.paintRow(row, preference);
        NewRowDots.paint(row, preference, used.accent);
    }

    /** Paints a section title with the colours on show, if there are any. */
    static void heading(View row) {
        ScreenColors colors = shown;
        (colors == null ? DEFAULT : colors).paintHeading(row);
    }

    /** Paints both recovery actions even if the ordinary settings page failed to initialize. */
    static void recoveryAction(View row, boolean primary) {
        ScreenColors colors = forScreen(row.getContext());
        (colors == null ? DEFAULT : colors).paintRecoveryAction(row, primary);
    }

    /** Paints a dialog with the colours on show, if there are any. */
    public static void dialog(@Nullable AlertDialog dialog) {
        ScreenColors colors = shown;
        (colors == null ? DEFAULT : colors).paint(dialog);
    }

    /** Styles a runtime dialog's own input without changing its validation or input type. */
    public static void styleField(EditText field) {
        ScreenColors colors = shown;
        (colors == null ? DEFAULT : colors).paintField(field);
    }

    /** Styles an existing action without replacing its content, listeners or accessibility delegate. */
    public static void styleAction(TextView action, boolean primary) {
        ScreenColors colors = shown;
        (colors == null ? DEFAULT : colors).paintAction(action, primary);
    }

    private void paintAction(TextView action, boolean primary) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(dimmedWhenDisabled(primary ? accent : card));
        surface.setCornerRadius(dp(action, 12));
        surface.setStroke(dp(action, 2), interactionOutline(primary ? accent : outline,
                primary ? onAccent : heading));
        action.setBackgroundTintList(null);
        // View's tint pass mutates the background after setting its state. Mutate first so
        // that pass cannot replace enabled children with fresh, empty-state copies.
        action.setBackground(new RippleDrawable(
                interactionRipple(primary ? onAccent : heading), surface, null).mutate());
        action.setTextColor(dimmedWhenDisabled(primary ? onAccent : secondaryActionText()));
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        action.setAllCaps(false);
        action.setSingleLine(false);
        action.setMaxLines(Integer.MAX_VALUE);
        action.setEllipsize(null);
        action.setHorizontallyScrolling(false);
        action.setGravity(android.view.Gravity.CENTER);
        action.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        action.setMinHeight(dp(action, 48));
        action.setMinimumHeight(dp(action, 48));
        action.setMinWidth(dp(action, 64));
        action.setMinimumWidth(dp(action, 64));
        action.setPaddingRelative(dp(action, 16), dp(action, 12), dp(action, 16), dp(action, 12));
    }

    /** Compact actions retain the shared interaction states without becoming filled buttons. */
    public static void styleIconAction(View action) {
        ScreenColors colors = shown == null ? DEFAULT : shown;
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.TRANSPARENT);
        surface.setCornerRadius(dp(action, 8));
        surface.setStroke(dp(action, 2), colors.interactionOutline(Color.TRANSPARENT));
        action.setBackgroundTintList(null);
        action.setBackground(new RippleDrawable(interactionRipple(colors.heading), surface, null).mutate());
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

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the second switch of the "View stories anonymously" patch: a Mark as seen button in
 * the story viewer's header.
 *
 * <p>Every story on screen goes through Instagram's story item binder, and the patch calls
 * {@link #bind} first thing in its toolbar bind with the account signed in, the story and its view
 * holder (451 has no header binder; its media bind and item bind call the toolbar bind instead).
 * The button goes in the row of buttons at the top end of the header, before the three-dot menu,
 * and shows where the story stands ({@link StoryMarks}): an eye crossed out while it's held back, a
 * solid eye once it's marked, dimmed once it's been sent. A tap marks the story, or takes the mark
 * back before it goes. A story a batch already held back is sent straight away, through
 * Instagram's own send.
 *
 * <p>The button shows only while views are anonymous and its own switch is on. With either off,
 * HushGram paused or the settings not read yet, it's hidden, and the patch's send hook holds
 * every batch back or lets every batch through as it would without it. Holders are recycled, so a
 * button already in a header is shown, hidden or pointed at the new story on each bind. A hidden
 * button forgets its story, so it can't mark one later.
 *
 * <p>A mark belongs to the account signed in when it was made ({@link StoryMarks}), so a story
 * marked on one account is never sent for another.
 */
public final class StorySeenButton {
    /** Instagram's row of buttons at the top end of the story header, and its three-dot menu in it. */
    static final String CONTAINER = "reel_header_extras_container";
    static final String MENU = "header_menu_button";
    static final String MENU_STUB = "header_menu_button_stub";

    static final String HOOK = "story seen button";

    private static final ResourceIdCache IDS = new ResourceIdCache();

    /** Whether a button was ever added, so a switch that was never on costs nothing per story. */
    private static volatile boolean added;

    private StorySeenButton() {
    }

    /** Where {@link #bind} gets what it needs from Instagram's objects. Tests hand in their own. */
    interface Reader {
        /** Instagram's id for [item], a story in the viewer, or null. */
        @Nullable
        String storyId(Object item);

        /** The root view of the story's view holder [holder], or null. */
        @Nullable
        View itemView(Object holder);

        /** Instagram's user ID for [session], the account signed in, or null. */
        @Nullable
        String account(Object session);
    }

    static final Reader PATCHED = new Reader() {
        @Override
        public String storyId(Object item) {
            return StorySeenButton.storyId(item);
        }

        @Override
        public View itemView(Object holder) {
            return StorySeenButton.itemView(holder);
        }

        @Override
        public String account(Object session) {
            return StorySeen.sessionAccount(session);
        }
    };

    /** What a tap does with a marked story a batch held back: sends it for [session]'s account. */
    interface Sender {
        void send(Object session);
    }

    /** The story a button is for, the account's user ID, and the account to send it for. */
    static final class Bound {
        final String story;
        final String account;
        final WeakReference<Object> session;

        Bound(String story, String account, @Nullable Object session) {
            this.story = story;
            this.account = account;
            this.session = new WeakReference<>(session);
        }
    }

    /**
     * Injected first thing in the story item binder's toolbar bind. Shows the button for [item] in
     * [holder]'s header while the switches are on, and hides it otherwise. Never throws.
     */
    public static void bind(Object session, Object item, Object holder) {
        bind(session, item, holder, PATCHED, StorySeenButton::switchedOn, StorySeen.MARKS, StorySeen::sendMarked);
    }

    static void bind(@Nullable Object session, @Nullable Object item, @Nullable Object holder, Reader reader,
                     BooleanSupplier on, StoryMarks marks, Sender sender) {
        try {
            HookStatus.invoked(FamilyNames.STORY_SEEN);
            boolean showing = on.getAsBoolean();
            if (!showing && !added) return;
            View root = holder == null ? null : reader.itemView(holder);
            if (root == null) return;
            LinearLayout row = row(root, showing);
            if (row == null) return;
            ImageView button = find(row);
            String story = showing && item != null ? StoryMarks.storyOf(reader.storyId(item)) : null;
            String account = story != null && session != null ? reader.account(session) : null;
            if (story == null || account == null) {
                if (button != null) hide(button);
                return;
            }
            if (button == null) {
                button = create(row.getContext(), on, marks, sender);
                insert(row, button);
                added = true;
            }
            button.setTag(new Bound(story, account, session));
            show(button, marks.state(account, story));
            button.setVisibility(View.VISIBLE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /** A tap on [button]: marks its story or takes the mark back, and shows where it stands. Never throws. */
    static void tapped(ImageView button, BooleanSupplier on, StoryMarks marks, Sender sender) {
        try {
            if (!(button.getTag() instanceof Bound)) return;
            if (!on.getAsBoolean()) {
                hide(button);
                return;
            }
            Bound bound = (Bound) button.getTag();
            if (marks.toggle(bound.account, bound.story) == StoryMarks.State.MARKED && marks.held(bound.account, bound.story)) {
                Object session = bound.session.get();
                if (session != null) sender.send(session);
            }
            StoryMarks.State now = marks.state(bound.account, bound.story);
            show(button, now);
            button.announceForAccessibility(describe(now));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /** Whether the button shows: views held back and its own switch on. Both read off while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.VIEW_STORIES_ANONYMOUSLY.get() && Settings.MARK_STORIES_SEEN.get();
    }

    /** What the button says it does, for TalkBack. */
    static String describe(StoryMarks.State state) {
        switch (state) {
            case MARKED:
                return L10n.t("Marked as seen. Tap again to undo.");
            case SENT:
                return L10n.t("Marked as seen and sent");
            default:
                return L10n.t("Mark as seen");
        }
    }

    /** Forgets the ids looked up and that a button was added. */
    static void resetForTests() {
        IDS.clear();
        added = false;
    }

    static void putIdForTests(Context context, String name, int id) {
        IDS.putForTests(context.getPackageName(), name, id);
    }

    /** The header's row of buttons under [root], reporting a build without it while the button is wanted. */
    @Nullable
    private static LinearLayout row(View root, boolean wanted) {
        int id = id(root, CONTAINER);
        View found = id == 0 ? null : root.findViewById(id);
        if (!(found instanceof LinearLayout)) {
            if (wanted) HookStatus.missingViewId(FamilyNames.STORY_SEEN, CONTAINER);
            return null;
        }
        HookStatus.recoveredViewId(FamilyNames.STORY_SEEN, CONTAINER);
        return (LinearLayout) found;
    }

    private static int id(View view, String name) {
        return IDS.resolve(view.getResources(), view.getContext().getPackageName(), name, false);
    }

    @Nullable
    private static ImageView find(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof Eye) return (ImageView) child;
        }
        return null;
    }

    /** Puts [button] before the three-dot menu, or its stub, or at the end when the row has neither. */
    private static void insert(LinearLayout row, ImageView button) {
        int at = row.getChildCount();
        int menu = id(row, MENU);
        int stub = id(row, MENU_STUB);
        for (int i = 0; i < row.getChildCount(); i++) {
            int child = row.getChildAt(i).getId();
            if (child != View.NO_ID && (child == menu || child == stub)) {
                at = i;
                break;
            }
        }
        float density = row.getResources().getDisplayMetrics().density;
        // A 48dp touch target that takes only 40dp of the row's height: the 4dp above and below are
        // negative margins, so the header doesn't grow (#151) and a row 48dp tall gets the whole box.
        int target = Math.round(48 * density);
        int overhang = -Math.round(4 * density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(target, target);
        params.topMargin = overhang;
        params.bottomMargin = overhang;
        params.gravity = Gravity.CENTER;
        row.addView(button, at, params);
    }

    private static ImageView create(Context context, BooleanSupplier on, StoryMarks marks, Sender sender) {
        ImageView button = new ImageView(context);
        button.setImageDrawable(new Eye(context.getResources().getDisplayMetrics().density));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setOnClickListener(view -> tapped((ImageView) view, on, marks, sender));
        return button;
    }

    /** Hides [button] and forgets its story, so nothing can mark it until a bind shows it again. */
    private static void hide(ImageView button) {
        button.setVisibility(View.GONE);
        button.setTag(null);
    }

    private static void show(ImageView button, StoryMarks.State state) {
        Drawable drawable = button.getDrawable();
        if (drawable instanceof Eye) ((Eye) drawable).setState(state);
        button.setContentDescription(describe(state));
        button.setEnabled(state != StoryMarks.State.SENT);
        button.setAlpha(state == StoryMarks.State.SENT ? 0.6f : 1f);
    }

    /**
     * The button's eye, drawn rather than taken from Instagram's resources: crossed out while the
     * story is held back, solid once it's marked or sent. White with a soft shadow, like the
     * header's other buttons over a story.
     */
    static final class Eye extends Drawable {
        private final float unit;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pupil = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path lids = new Path();
        private StoryMarks.State state = StoryMarks.State.UNMARKED;

        Eye(float density) {
            unit = density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2 * density);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(Color.WHITE);
            line.setShadowLayer(2 * density, 0, 0, 0x66000000);
            solid.setStyle(Paint.Style.FILL);
            solid.setColor(Color.WHITE);
            solid.setShadowLayer(2 * density, 0, 0, 0x66000000);
            pupil.setStyle(Paint.Style.FILL);
            pupil.setColor(0xFF262626);
        }

        StoryMarks.State state() {
            return state;
        }

        void setState(StoryMarks.State state) {
            if (this.state == state) return;
            this.state = state;
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            float half = 10 * unit;
            lids.reset();
            lids.moveTo(x - half, y);
            lids.quadTo(x, y - 12 * unit, x + half, y);
            lids.quadTo(x, y + 12 * unit, x - half, y);
            lids.close();
            if (state == StoryMarks.State.UNMARKED) {
                canvas.drawPath(lids, line);
                canvas.drawCircle(x, y, 3 * unit, line);
                canvas.drawLine(x - 8 * unit, y - 8 * unit, x + 8 * unit, y + 8 * unit, line);
            } else {
                canvas.drawPath(lids, solid);
                canvas.drawCircle(x, y, 3.5f * unit, pupil);
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.round(24 * unit);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round(24 * unit);
        }

        @Override
        public void setAlpha(int alpha) {
            line.setAlpha(alpha);
            solid.setAlpha(alpha);
            pupil.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            line.setColorFilter(filter);
            solid.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: Instagram's id for [item], a story in the viewer. */
    @Nullable
    public static String storyId(Object item) {
        return null;
    }

    /** Filled in by the patch: the root view of the story's view holder [holder]. */
    @Nullable
    public static View itemView(Object holder) {
        return null;
    }
}

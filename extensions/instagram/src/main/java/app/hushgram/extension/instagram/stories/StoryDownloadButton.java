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
import java.util.function.Consumer;

import app.hushgram.extension.instagram.download.StoryDownload;
import app.hushgram.extension.instagram.download.ViewingHistory;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Download any story" patch's second switch (#400): a Download button in the story
 * viewer's header, so saving a story is one tap instead of the three-dot menu and a row.
 *
 * <p>The patch calls {@link #bind} first thing in the story item binder's toolbar bind with the
 * story and its view holder, as the Mark as seen button's hook does. The button goes in the row of
 * buttons at the top end of the header, before the three-dot menu. A tap runs the save the menu's
 * Download row runs ({@link StoryDownload#saveStory}) on the story's Media, read when the header
 * was bound.
 *
 * <p>Holders are recycled from story to story, so a button already in a header is shown, hidden or
 * pointed at the new story on each bind. With the switch off, HushGram paused or the settings not
 * read yet, the button is hidden and forgets its story, so it can't save one later. A switch that
 * was never on costs one check per bind.
 */
public final class StoryDownloadButton {
    /** Instagram's row of buttons at the top end of the story header, and its three-dot menu in it. */
    static final String CONTAINER = "reel_header_extras_container";
    static final String MENU = "header_menu_button";
    static final String MENU_STUB = "header_menu_button_stub";

    static final String HOOK = "story download button";

    private static final ResourceIdCache IDS = new ResourceIdCache();

    /** Whether a button was ever added, so a switch that was never on costs nothing per story. */
    private static volatile boolean added;

    private StoryDownloadButton() {
    }

    /** Where {@link #bind} gets what it needs from Instagram's objects. Tests hand in their own. */
    interface Reader {
        /** The Media of [item], a story in the viewer, or null. */
        @Nullable
        Object media(Object item);

        /** The root view of the story's view holder [holder], or null. */
        @Nullable
        View itemView(Object holder);
    }

    static final Reader PATCHED = new Reader() {
        @Override
        public Object media(Object item) {
            return StoryDownloadButton.media(item);
        }

        @Override
        public View itemView(Object holder) {
            return StoryDownloadButton.itemView(holder);
        }
    };

    /** The story a button saves, held weakly so a story Instagram dropped can go. */
    static final class Bound {
        final WeakReference<Object> media;

        Bound(Object media) {
            this.media = new WeakReference<>(media);
        }
    }

    /**
     * Injected first thing in the story item binder's toolbar bind. Shows the button for [item] in
     * [holder]'s header while the switch is on, and hides it otherwise, then lets the Copy text button
     * (#303) do the same. Never throws.
     */
    public static void bind(Object item, Object holder) {
        bind(item, holder, PATCHED, StoryDownloadButton::switchedOn, StoryDownload::saveStory);
        StoryTextButton.bind(item, holder, PATCHED, StoryTextButton::switchedOn);
    }

    static void bind(@Nullable Object item, @Nullable Object holder, Reader reader, BooleanSupplier on,
                     Consumer<Object> saver) {
        try {
            HookStatus.invoked(FamilyNames.STORY_DOWNLOAD);
            if (item != null && ViewingHistory.on()) ViewingHistory.story(reader.media(item));
            boolean showing = on.getAsBoolean();
            if (!showing && !added) return;
            View root = holder == null ? null : reader.itemView(holder);
            if (root == null) return;
            LinearLayout row = row(root, showing);
            if (row == null) return;
            ImageView button = find(row);
            Object media = showing && item != null ? reader.media(item) : null;
            if (media == null) {
                if (button != null) hide(button);
                return;
            }
            if (button == null) {
                button = create(row.getContext(), on, saver);
                insert(row, button);
                added = true;
            }
            button.setTag(new Bound(media));
            button.setVisibility(View.VISIBLE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, HOOK, failure);
        }
    }

    /** A tap on [button]: saves its story, unless the switch went off since. Never throws. */
    static void tapped(ImageView button, BooleanSupplier on, Consumer<Object> saver) {
        try {
            if (!(button.getTag() instanceof Bound)) return;
            if (!on.getAsBoolean()) {
                hide(button);
                return;
            }
            Object media = ((Bound) button.getTag()).media.get();
            if (media == null) {
                hide(button);
                return;
            }
            HookStatus.counted(FamilyNames.STORY_DOWNLOAD, "story button tapped");
            saver.accept(media);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, HOOK, failure);
        }
    }

    /** Whether the button shows: its own switch. Reads off while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.DOWNLOAD_BUTTON_ON_STORIES.get();
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
    static LinearLayout row(View root, boolean wanted) {
        int id = id(root, CONTAINER);
        View found = id == 0 ? null : root.findViewById(id);
        if (!(found instanceof LinearLayout)) {
            if (wanted) HookStatus.missingViewId(FamilyNames.STORY_DOWNLOAD, CONTAINER);
            return null;
        }
        HookStatus.recoveredViewId(FamilyNames.STORY_DOWNLOAD, CONTAINER);
        return (LinearLayout) found;
    }

    private static int id(View view, String name) {
        return IDS.resolve(view.getResources(), view.getContext().getPackageName(), name, false);
    }

    @Nullable
    static ImageView find(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof Arrow) return (ImageView) child;
        }
        return null;
    }

    /** Puts [button] before the three-dot menu, or its stub, or at the end when the row has neither. */
    static void insert(LinearLayout row, ImageView button) {
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
        // A 48dp touch target that takes only 40dp of the row's height, as the Mark as seen button's.
        int target = Math.round(48 * density);
        int overhang = -Math.round(4 * density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(target, target);
        params.topMargin = overhang;
        params.bottomMargin = overhang;
        params.gravity = Gravity.CENTER;
        row.addView(button, at, params);
    }

    private static ImageView create(Context context, BooleanSupplier on, Consumer<Object> saver) {
        ImageView button = new ImageView(context);
        button.setImageDrawable(new Arrow(context.getResources().getDisplayMetrics().density));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        button.setContentDescription(L10n.t("Download"));
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setOnClickListener(view -> tapped((ImageView) view, on, saver));
        return button;
    }

    /** Hides [button] and forgets its story, so nothing can save it until a bind shows it again. */
    private static void hide(ImageView button) {
        button.setVisibility(View.GONE);
        button.setTag(null);
    }

    /** A download arrow over a tray, drawn in code: white with a soft shadow, like the header's other buttons. */
    static final class Arrow extends Drawable {
        private final float unit;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        Arrow(float density) {
            unit = density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2 * density);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(Color.WHITE);
            line.setShadowLayer(2 * density, 0, 0, 0x66000000);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            canvas.drawLine(x, y - 9 * unit, x, y + 3 * unit, line);
            canvas.drawLine(x - 5.5f * unit, y - 2.5f * unit, x, y + 3 * unit, line);
            canvas.drawLine(x + 5.5f * unit, y - 2.5f * unit, x, y + 3 * unit, line);
            canvas.drawLine(x - 8 * unit, y + 8 * unit, x + 8 * unit, y + 8 * unit, line);
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
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            line.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the Media of [item], a story in the viewer. */
    @Nullable
    public static Object media(Object item) {
        return null;
    }

    /** Filled in by the patch: the root view of the story's view holder [holder]. */
    @Nullable
    public static View itemView(Object holder) {
        return null;
    }
}

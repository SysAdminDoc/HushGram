/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import app.hushgram.extension.instagram.download.ReelTextButton;
import app.hushgram.extension.instagram.download.TextGrab;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A Lens button in the story viewer's header, for "Copy text button on stories" (#303).
 *
 * <p>{@link StoryDownloadButton#bind} hands each story header here after its own button, so this one
 * needs no hook of its own. The button goes in the same row, before the three-dot menu, and a tap
 * sends what the story shows to Google Lens ({@link TextGrab}), where its words can be selected and
 * copied.
 *
 * <p>Holders are recycled from story to story, so a button already in a header is shown or hidden on
 * each bind. A switch that was never on costs one check per bind, and nothing throws into Instagram.
 */
public final class StoryTextButton {
    static final String HOOK = "story text button";
    static final String COUNT_NO_GLYPH = "story text button skipped, no Lens glyph";

    /** Whether a button was ever added, so a switch that was never on costs nothing per story. */
    private static volatile boolean added;

    /** Instagram's Lens glyph, white. Replaced by tests, which have no Instagram resources. */
    static Function<Context, Drawable> icon = context -> ReelTextButton.drawable(context, null);

    /** Sends what a story's page shows on. Replaced by tests. */
    static BiConsumer<View, Activity> grabber = (page, activity) -> TextGrab.grab(page, activity, FamilyNames.STORY_DOWNLOAD);

    private StoryTextButton() {
    }

    /** The header button this class adds, told apart from the row's own buttons by its class. */
    static final class Lens extends ImageView {
        Lens(Context context) {
            super(context);
        }
    }

    /** Shows the button in [holder]'s header while the switch is on, and hides it otherwise. Never throws. */
    static void bind(@Nullable Object item, @Nullable Object holder, StoryDownloadButton.Reader reader, BooleanSupplier on) {
        try {
            boolean showing = on.getAsBoolean();
            if (!showing && !added) return;
            View root = holder == null ? null : reader.itemView(holder);
            if (root == null) return;
            LinearLayout row = StoryDownloadButton.row(root, showing);
            if (row == null) return;
            Lens button = find(row);
            if (!showing || item == null) {
                if (button != null) button.setVisibility(View.GONE);
                return;
            }
            if (button == null) {
                button = create(row.getContext(), on);
                if (button == null) return;
                StoryDownloadButton.insert(row, button);
                added = true;
            }
            button.setVisibility(View.VISIBLE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, HOOK, failure);
        }
    }

    /** A tap on [button]: the story it sits on goes to Lens, unless the switch went off since. Never throws. */
    static void tapped(View button, BooleanSupplier on) {
        try {
            if (!on.getAsBoolean()) {
                button.setVisibility(View.GONE);
                return;
            }
            HookStatus.counted(FamilyNames.STORY_DOWNLOAD, "story text button tapped");
            Context context = button.getContext();
            Activity activity = null;
            while (context instanceof ContextWrapper) {
                if (context instanceof Activity) {
                    activity = (Activity) context;
                    break;
                }
                context = ((ContextWrapper) context).getBaseContext();
            }
            grabber.accept(TextGrab.pageOf(button), activity != null ? activity : Utils.getActivity());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, HOOK, failure);
        }
    }

    /** Whether the button shows: its own switch. Reads off while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.TEXT_BUTTON_ON_STORIES.get();
    }

    /** Forgets that a button was added. */
    static void resetForTests() {
        added = false;
    }

    @Nullable
    static Lens find(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof Lens) return (Lens) row.getChildAt(i);
        }
        return null;
    }

    @Nullable
    private static Lens create(Context context, BooleanSupplier on) {
        Drawable glyph = icon.apply(context);
        if (glyph == null) {
            HookStatus.counted(FamilyNames.STORY_DOWNLOAD, COUNT_NO_GLYPH);
            return null;
        }
        Lens button = new Lens(context);
        button.setImageDrawable(glyph);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        button.setContentDescription(L10n.t(context, "Copy text"));
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setOnClickListener(view -> tapped(view, on));
        return button;
    }
}

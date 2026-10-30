/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

import app.hushgram.extension.instagram.download.FeedDownloadButton;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * A Messages button on Home's header, for the Tab bar switch Messages button on Home's header (#156).
 *
 * <p>On Instagram 451 many accounts get Messages as a tab on the bar and lose the paper plane at the
 * top of Home. The header's draw hook ({@code GhostHeaderButton.drew}) posts {@link #place} behind
 * each draw, and when the header has no Messages button of its own in a row, this one goes at the end
 * of the header's end row of buttons, where Instagram's own sits. When Instagram's own is there, none
 * is added, and one left from an earlier draw is taken out.
 *
 * <p>The icon is Instagram's own Messages icon, {@link #ICON}, the one its header button and its
 * Messages tab wear, looked up by name in Instagram's resources and given Instagram's icon color for
 * the header's theme, like the Search and Ghost mode buttons beside it.
 *
 * <p>A tap opens Messages the way Instagram's own header button does where Messages is a tab: it
 * switches the tab host to the Messages tab (Instagram's button asks its activity for the tab by name,
 * which ends at the same switch). The patch writes that switch for the Search button, and this button
 * borrows it ({@link SearchHeaderButton#select}). With Messages off the bar, where the tabs don't
 * swipe the switch opens it by its fragment, as Instagram does on a wide screen. Where they swipe there
 * is no page for it, so the tap says to turn on Show the Messages tab and is counted.
 *
 * <p>With the switch off or HushGram paused, no button is added and one left from before is taken out
 * at the next draw. A tap while paused does nothing. Nothing in here throws.
 *
 * <p>The opposite switch, Hide Messages on Home's header (#210), is for accounts that still have
 * Instagram's own button there and turned on Show the Messages tab, which leaves Messages in two
 * places. Each draw hides Instagram's button and adds none, and it comes back at the next draw with
 * the switch off or HushGram paused.
 */
public final class MessagesHeaderButton {
    private MessagesHeaderButton() { }

    /** The name the diagnostic report counts this under, the Reels tab line the header's other hooks use. */
    static final String ROUTE = FamilyNames.REELS_TAB;
    static final String PLACED = "Home header Messages button placed";
    static final String NO_ROW = "Home header Messages button not placed, no row to put it in";
    static final String NO_ICON = "Home header Messages button not placed, no Messages icon";
    static final String OPENED = "Home header Messages button opened Messages";
    static final String OPENED_OFF_BAR = "Home header Messages button opened Messages off the tab bar";
    static final String SWIPING = "Home header Messages button left Messages shut, it's off the swiping tabs";
    static final String NO_HOST = "Home header Messages button found no tab host";
    static final String HIDDEN_OWN = "Home header Messages button of Instagram's hidden";

    /** The resource name of Instagram's Messages icon: its header button's, and its Messages tab's while not selected. */
    static final String ICON = "instagram_direct_outline_24";

    /** Stands in for the icon lookup in tests, or null to look the icon up in Instagram's resources. */
    @Nullable
    static volatile Function<Context, Drawable> iconForTests;

    /** Sets {@link #iconForTests} from the settings package's tests, or null to look the icon up again. */
    public static void standInIconForTests(@Nullable Function<Context, Drawable> icons) {
        iconForTests = icons;
    }

    /** The button, its own type so the next draw finds it in the row. */
    static final class Inbox extends ImageView {
        Inbox(Context context) {
            super(context);
        }
    }

    /** Puts the button in, leaves the one there, or takes it out, as the switch, Pause and the header say. Never throws. */
    public static void place(View header) {
        try {
            if (header == null) return;
            boolean ready = Utils.settingsReady() && !HushgramPause.isPaused();
            boolean hide = ready && Settings.HIDE_HOME_MESSAGES_BUTTON.get();
            hideOwn(header, hide);
            // Hide the Messages tab keeps a way in on Home's header, unless Instagram's own button is there (#298).
            boolean wanted = ready && !hide && (Settings.MESSAGES_BUTTON_ON_HOME.get() || Settings.HIDE_MESSAGES_TAB.get());
            LinearLayout end = HomeHeader.endRowOf(header);
            if (end == null) {
                if (wanted && !instagramsOwnShows(header)) HookStatus.counted(ROUTE, NO_ROW);
                return;
            }
            ImageView existing = in(end);
            if (!wanted || instagramsOwnShows(header)) {
                if (existing != null) end.removeView(existing);
                return;
            }
            if (existing != null) return;
            Context context = header.getContext();
            Drawable icon = icon(context, end);
            if (icon == null) {
                HookStatus.counted(ROUTE, NO_ICON);
                return;
            }
            Inbox button = create(context, icon, end.getChildCount() > 0 ? end.getChildAt(end.getChildCount() - 1) : null);
            end.addView(button, params());
            HookStatus.counted(ROUTE, PLACED);
            Logger.printDebug(() -> "Reels tab: the Messages button is on Home's header");
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "messages button", failure);
            Logger.printException(() -> "Reels tab: could not put the Messages button on Home's header", failure);
        }
    }

    /** The button HushGram put in [row], or null. The Ghost mode button goes right before it. */
    @Nullable
    public static ImageView in(@Nullable LinearLayout row) {
        if (row == null) return null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof Inbox) return (ImageView) child;
        }
        return null;
    }

    /**
     * Hides Instagram's own Messages button on the header when [hide] is set, for Hide Messages on
     * Home's header (#210), and gives one it hid back its visibility when [hide] isn't. The header
     * keeps the button in its row, so its getter and the buttons placed beside it still find it.
     */
    static void hideOwn(View header, boolean hide) {
        View own = HomeHeader.inboxOf(header);
        if (own == null || own instanceof Inbox) return;
        if (hide) {
            if (own.getVisibility() == View.GONE) return;
            HIDDEN.put(own, own.getVisibility());
            own.setVisibility(View.GONE);
            HookStatus.counted(ROUTE, HIDDEN_OWN);
            return;
        }
        Integer was = HIDDEN.remove(own);
        if (was != null && own.getVisibility() == View.GONE) own.setVisibility(was);
    }

    /** Instagram's Messages buttons {@link #hideOwn} hid, with the visibility each had before. Main thread only. */
    private static final Map<View, Integer> HIDDEN = new WeakHashMap<>();

    /** Whether the header shows Instagram's own Messages button: it has one and it sits in a row. */
    static boolean instagramsOwnShows(View header) {
        View inbox = HomeHeader.inboxOf(header);
        return inbox != null && inbox.getParent() != null;
    }

    /** The tap: opens Messages, or says why it can't. Does nothing while paused. Never throws. */
    static void press(View view) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return;
            Activity activity = SearchHeaderButton.activityOf(view.getContext());
            Object messages = TabStart.tabNamed(ReelsTab.MESSAGES);
            if (activity == null || messages == null) {
                HookStatus.counted(ROUTE, NO_HOST);
                return;
            }
            boolean onTheBar = TabStart.isOnTheBar(ReelsTab.MESSAGES);
            if (!onTheBar && SearchHeaderButton.tabsSwipe(activity)) {
                HookStatus.counted(ROUTE, SWIPING);
                // A toast shows two lines at most on Android 12 and newer, so it says what to do and stops.
                Utils.showToastLong(Settings.SHOW_MESSAGES_TAB.get()
                        ? L10n.t("Restart Instagram to put Messages on your tab bar.")
                        : L10n.t("To open Messages here, turn on Show the Messages tab and restart."));
                return;
            }
            if (SearchHeaderButton.switchTo(activity, messages) == 0) {
                HookStatus.counted(ROUTE, NO_HOST);
                return;
            }
            HookStatus.counted(ROUTE, onTheBar ? OPENED : OPENED_OFF_BAR);
            Logger.printDebug(() -> "Reels tab: the header's Messages button opened Messages" + (onTheBar ? "" : " off the bar"));
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "messages button tap", failure);
            Logger.printException(() -> "Reels tab: the header's Messages button failed", failure);
        }
    }

    /**
     * Instagram's Messages icon, or null when it can't be found. It's looked up in the package the
     * header's own views take their ids from, which on a second copy installed next to Instagram is
     * still Instagram's, then in the app's own package.
     */
    @Nullable
    static Drawable icon(Context context, LinearLayout row) {
        Function<Context, Drawable> stand = iconForTests;
        if (stand != null) return stand.apply(context);
        Resources resources = context.getResources();
        int found = 0;
        for (int i = -1; i < row.getChildCount() && found == 0; i++) {
            int id = (i < 0 ? row : row.getChildAt(i)).getId();
            if (id == View.NO_ID || (id >>> 24) != 0x7f) continue;
            try {
                found = resources.getIdentifier(ICON, "drawable", resources.getResourcePackageName(id));
            } catch (Resources.NotFoundException unnamed) {
                found = 0;
            }
        }
        if (found == 0) found = resources.getIdentifier(ICON, "drawable", context.getPackageName());
        if (found == 0) return null;
        Drawable icon = context.getDrawable(found);
        return icon != null ? icon.mutate() : null;
    }

    private static LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    private static Inbox create(Context context, Drawable icon, @Nullable View neighbor) {
        Inbox button = new Inbox(context);
        button.setImageDrawable(icon);
        button.setColorFilter(FeedDownloadButton.iconColor(context), PorterDuff.Mode.SRC_IN);
        button.setScaleType(ImageView.ScaleType.CENTER);
        HomeHeader.padLike(button, neighbor);
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setContentDescription(L10n.t("Messages"));
        button.setOnClickListener(MessagesHeaderButton::press);
        return button;
    }
}

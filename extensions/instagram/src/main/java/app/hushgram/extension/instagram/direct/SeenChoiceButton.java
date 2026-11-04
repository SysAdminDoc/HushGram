/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.lang.ref.WeakReference;

/**
 * The eye in a chat's header while Choose Seen in each chat is on (#13). It sits in the row of call
 * buttons beside the check mark ({@link MarkReadButton}), which places it, and shows an open eye
 * while the chat on screen sends its seen receipts and a crossed-out one while it holds them
 * ({@link ThreadSeen#holdsHere}). A tap opens a menu with the chat's three choices: use the HushGram
 * setting, hold Seen or send Seen. Picking one keeps it for that chat on that account
 * ({@link ThreadSeen#choose}), says so in a toast and changes the eye. Nothing held is sent by it and
 * nothing sent is taken back.
 *
 * <p>The chat on screen and its account are the ones the chat lock read when it came to the front.
 * With either unknown the eye stays hidden, since a choice would belong to no chat.
 */
public final class SeenChoiceButton {
    static final String TAG = "hushgram_seen_choice";
    /** Instagram's open eye, for a chat that sends Seen. */
    static final String[] SENDS = {"instagram_eye_pano_outline_24", "instagram_eye_outline_24"};
    /** Instagram's crossed-out eye, for a chat that holds Seen. */
    static final String[] HOLDS = {"instagram_eye_off_pano_outline_24", "instagram_eye_off_outline_24"};
    static final String STEP = "seen choice button";

    /** The menu's rows, by item id. */
    private static final SeenChoices.Choice[] ROWS = {SeenChoices.Choice.SWITCH, SeenChoices.Choice.HOLD,
            SeenChoices.Choice.SEND};
    private static final int GROUP = 1;

    private static volatile WeakReference<ImageView> button = new WeakReference<>(null);
    /** The menu open now, for tests. */
    private static volatile WeakReference<PopupMenu> shown = new WeakReference<>(null);

    private static final View.OnClickListener TAP = new View.OnClickListener() {
        @Override public void onClick(View v) {
            try {
                open(v);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
            }
        }
    };

    private SeenChoiceButton() {
    }

    /**
     * Puts the eye in the header's row of call buttons, or finds the one there, and shows it while
     * the chat on screen is known. Null when the row or Instagram's glyph isn't there.
     */
    static ImageView place(View root, View.OnAttachStateChangeListener keep) {
        View found = ChatPager.nearest(root, ChatPager.BUTTON_ROW);
        if (!(found instanceof LinearLayout)) return null;
        LinearLayout row = (LinearLayout) found;
        View had = row.findViewWithTag(TAG);
        ImageView ours;
        if (had instanceof ImageView) {
            ours = (ImageView) had;
        } else {
            Drawable glyph = ChatPager.icon(row.getContext(), SENDS);
            if (glyph == null) return null;
            ours = ChatPager.addHeaderButton(row, glyph, L10n.t("Seen goes out in this chat"), TAG, TAP, keep);
        }
        button = new WeakReference<>(ours);
        refresh();
        return ours;
    }

    /** Shows the eye the chat on screen's way, or hides it when that chat isn't known or the switch is off. */
    static void refresh() {
        try {
            ImageView ours = button.get();
            if (ours == null) return;
            String account = ChatLocks.currentAccount();
            String thread = ChatLocks.current();
            boolean known = ThreadSeen.choosesPerChat() && !isEmpty(account) && !isEmpty(thread);
            ours.setVisibility(known ? View.VISIBLE : View.GONE);
            if (!known) return;
            boolean holds = ThreadSeen.holdsHere(account, thread);
            Drawable glyph = ChatPager.icon(ours.getContext(), holds ? HOLDS : SENDS);
            if (glyph != null) ours.setImageDrawable(glyph);
            ours.setContentDescription(holds ? L10n.t("Seen held in this chat") : L10n.t("Seen goes out in this chat"));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
        }
    }

    /** Takes the eye out of the header, once its chat has left the front. */
    static void remove() {
        ImageView ours = button.get();
        button = new WeakReference<>(null);
        ViewParent parent = ours == null ? null : ours.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(ours);
        PopupMenu open = shown.get();
        shown = new WeakReference<>(null);
        if (open != null) open.dismiss();
    }

    /** Opens the chat's choices under [anchor], the one it has now checked. */
    private static void open(View anchor) {
        final String account = ChatLocks.currentAccount();
        final String thread = ChatLocks.current();
        if (isEmpty(account) || isEmpty(thread)) {
            Utils.showToastShort(L10n.t("Couldn't save the choice"));
            return;
        }
        SeenChoices.Choice now = ThreadSeen.choiceOf(account, thread);
        PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
        Menu menu = popup.getMenu();
        menu.add(GROUP, 0, 0, L10n.t("Use the HushGram setting"));
        menu.add(GROUP, 1, 1, L10n.t("Hold Seen in this chat"));
        menu.add(GROUP, 2, 2, L10n.t("Send Seen in this chat"));
        menu.setGroupCheckable(GROUP, true, true);
        for (int index = 0; index < ROWS.length; index++) {
            if (ROWS[index] == now) menu.findItem(index).setChecked(true);
        }
        popup.setOnMenuItemClickListener(item -> {
            int index = item.getItemId();
            if (index >= 0 && index < ROWS.length) pick(account, thread, ROWS[index]);
            return true;
        });
        popup.setOnDismissListener(closed -> shown = new WeakReference<>(null));
        shown = new WeakReference<>(popup);
        popup.show();
    }

    /** Keeps [choice] for [thread] on [account], says so and shows the eye and the check mark its way. */
    static void pick(String account, String thread, SeenChoices.Choice choice) {
        try {
            boolean kept = ThreadSeen.choose(account, thread, choice);
            Utils.showToastShort(!kept ? L10n.t("Couldn't save the choice")
                    : choice == SeenChoices.Choice.HOLD ? L10n.t("Seen held in this chat")
                    : choice == SeenChoices.Choice.SEND ? L10n.t("Seen goes out in this chat")
                    : L10n.t("This chat uses the setting"));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
        }
        refresh();
        MarkReadButton.refresh();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    /** The menu open now, or null. For tests. */
    static PopupMenu shownForTests() {
        return shown.get();
    }

    /** Forgets the eye and its menu. For tests. */
    static void resetForTests() {
        button = new WeakReference<>(null);
        shown = new WeakReference<>(null);
    }
}

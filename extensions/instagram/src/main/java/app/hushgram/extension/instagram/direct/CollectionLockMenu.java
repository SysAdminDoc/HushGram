/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * A long press on a collection's tile in Saved offers Lock collection, or Remove lock for one that's
 * locked, part of the "Lock your messages" patch (#678).
 *
 * <p>The patch hands {@link #tile} each tile as Instagram makes it, in Saved's row of collections and
 * in its collections list, the same view Instagram's tap goes on. Nothing looks for tiles while
 * screens draw. Instagram gives the tiles no long press of its own, so this one takes nothing away,
 * and a tile that has one already is left alone.
 *
 * <p>Lock collection puts it on the list of the account in front ({@link CollectionLocks}) at once,
 * since locking never needs asking. Remove lock asks the phone's lock or the PIN every time, even with
 * the collection open, so a phone handed over unlocked can't take the lock off. The collection is read
 * from the tile as it's pressed, so a tile Instagram reuses for another collection asks for the right
 * one. Paused, a long press does nothing.
 *
 * <p>A locked collection's tile also hides its cover while the lock is shut, since the cover is one of
 * the posts inside. It comes back once the tile names another collection, or the same one after the
 * lock opens and Saved shows it again.
 */
public final class CollectionLockMenu {
    /** The view in a collection's tile naming it, as Instagram names it. */
    static final String NAME = "saved_collection_name";
    /** The view in a collection's tile showing its cover, as Instagram names it. */
    static final String COVER = "saved_collection_thumbnail";

    /** The covers hidden here, each with the alpha it had before. Touched on the main thread only. */
    private static final Map<View, Float> hidden = new WeakHashMap<>();

    /** The menu's one item, either way. */
    static final int LOCK = 1;
    static final int REMOVE = 2;

    /** The step a failure is reported under. */
    static final String STEP = "collection long press";

    private CollectionLockMenu() {
    }

    /**
     * Called by the patch with each collection tile Instagram makes, as soon as it's inflated:
     * gives it the long press. That's before Instagram's accessibility setup on the See all grid,
     * which keeps the long click flag it finds and puts it back on every accessibility query.
     * Never throws.
     */
    public static void tile(View tile) {
        try {
            if (tile == null) return;
            watchCover(tile);
            // Instagram's own long press, should it ever add one, stays.
            if (tile.isLongClickable()) return;
            tile.setOnLongClickListener(CollectionLockMenu::pressed);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, STEP, t);
        }
    }

    /**
     * Each time Instagram names [tile] after a collection, its cover follows: hidden for one that's
     * locked while the lock is shut, so the tile doesn't show what's inside, and back for any other.
     */
    static void watchCover(View tile) {
        TextView name = nameView(tile);
        if (name == null) return;
        name.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                cover(tile);
            }
        });
    }

    /** Hides [tile]'s cover or puts it back, for the collection the tile names now. Never throws. */
    static void cover(View tile) {
        try {
            View cover = view(tile, COVER);
            TextView name = nameView(tile);
            if (cover == null || name == null) return;
            String named = CollectionLocks.text(name);
            boolean hide = named != null && MessagesLock.collectionListLocked() && CollectionLocks.listed(named);
            Float was = hidden.get(cover);
            if (hide && was == null) {
                hidden.put(cover, cover.getAlpha());
                cover.setAlpha(0f);
            } else if (!hide && was != null) {
                hidden.remove(cover);
                cover.setAlpha(was);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, STEP, t);
        }
    }

    /** The collection [tile] shows, as its page names it, or null when its name can't be read. */
    @Nullable
    static String nameOf(View tile) {
        TextView name = nameView(tile);
        return name == null ? null : CollectionLocks.text(name);
    }

    @Nullable
    private static TextView nameView(View tile) {
        View name = view(tile, NAME);
        return name instanceof TextView ? (TextView) name : null;
    }

    /** The view in [tile] Instagram names [name], or null. */
    @Nullable
    private static View view(View tile, String name) {
        int id = MessagesLock.id(tile.getContext(), name);
        if (id == 0) {
            HookStatus.missingViewId(FamilyNames.MESSAGES_LOCK, name);
            return null;
        }
        return tile.findViewById(id);
    }

    /** The long press on [tile]: its menu, unless HushGram is paused or the collection can't be told. */
    static boolean pressed(View tile) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return false;
            String name = nameOf(tile);
            if (name == null) return false;
            String account = ChatAccounts.active();
            boolean locked = CollectionLocks.listed(account, name);
            PopupMenu menu = new PopupMenu(tile.getContext(), tile);
            fill(menu.getMenu(), locked);
            menu.setOnMenuItemClickListener(item -> pick(tile, item.getItemId(), account, name));
            menu.show();
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, STEP, t);
            return false;
        }
    }

    /** What the long press offers: Remove lock for a collection on the list, Lock collection otherwise. */
    static void fill(Menu menu, boolean locked) {
        if (locked) menu.add(Menu.NONE, REMOVE, Menu.NONE, L10n.t("Remove lock"));
        else menu.add(Menu.NONE, LOCK, Menu.NONE, L10n.t("Lock collection"));
    }

    /** Does what the menu's [id] says for the collection called [name] on [account]'s list. */
    static boolean pick(View tile, int id, @Nullable String account, String name) {
        try {
            if (id == LOCK) {
                say(CollectionLocks.add(account, name), L10n.f("%1$s is locked", L10n.isolate(name)));
                cover(tile);
                return true;
            }
            if (id != REMOVE) return false;
            Activity activity = ChatReelsExit.activityOf(tile.getContext());
            if (activity == null) {
                say(false, null);
                return true;
            }
            MessagesLock.confirmCollectionRemoveThen(activity, () -> {
                say(CollectionLocks.remove(account, name), L10n.f("%1$s is unlocked", L10n.isolate(name)));
                cover(tile);
            });
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, STEP, t);
            return false;
        }
    }

    private static void say(boolean done, @Nullable String message) {
        Utils.showToastShort(done ? message : L10n.t("Couldn't change the lock. Try again."));
    }
}

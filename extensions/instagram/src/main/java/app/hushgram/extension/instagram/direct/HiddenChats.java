/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * The chats "Lock your messages" hides, one at a time.
 *
 * <p>A hidden chat is left out of the list of chats the inbox draws its rows from ({@link #inbox}),
 * out of the chats each folder tab counts its unread ones from ({@link #folder}) and the ones the
 * unread badge reads ({@link #filter}), out of the inbox search's results and message matches ({@link #searchResults},
 * {@link #searchHits}), out of the recent searches it lists before you type ({@link #recents}), and a push for it isn't posted at all ({@link ChatLocks#track} asks {@link #hides}). The list is
 * {@link Settings#HIDDEN_CHATS}, kept in the same lines as the locked chats ({@link ChatList}): the
 * account a chat was hidden on, its thread id and the name it had there. The ids never leave the
 * phone. HushGram settings add the chat you opened last and take a chat off the list, for the
 * account in front ({@link ChatAccounts}) and no other.
 *
 * <p>Each account's inbox, search and badge leave out the chats hidden on that account. A push is
 * dropped for a chat hidden on the account it was sent to. While no account is known (before the
 * first screen, or on one whose session can't be read) every account's hidden chats are left out,
 * and when another account comes to the front each inbox drawn is handed its list again for it.
 *
 * <p>A list with a chat on it is the switch. Paused, the setting answers an empty list, so every
 * hidden chat is back until HushGram is turned on again; the settings screen still shows what was
 * chosen ({@link #saved}).
 *
 * <p>A chat ticked or unticked in HushGram settings shows in the inbox as soon as you're back
 * ({@link #changed}): each inbox that drew its rows is handed Instagram's own list again without the
 * chats hidden now, and draws them again.
 *
 * <p>Double-tap Messages shows the hidden chats in the inbox by themselves, after the phone's lock
 * or the PIN ({@link #reveal}), until the next double tap or until you leave Instagram ({@link
 * #conceal}) (#661), asking the inbox for more pages while one hasn't loaded yet ({@link #pageIn}).
 * Search, the folder counts, the unread badge and pushes leave them out all along.
 *
 * <p>Nothing here throws into Instagram. A list that can't be read hides nothing, and a summary
 * whose id can't be read stays in the inbox.
 */
public final class HiddenChats {
    /** What is counted in the diagnostic report. */
    static final String LEFT_OUT = "hidden chats left out of the inbox";
    static final String LEFT_OUT_FOLDERS = "hidden chats left out of folder unread counts";
    static final String LEFT_OUT_BADGE = "hidden chats left out of the unread badge";
    static final String LEFT_OUT_SEARCH = "hidden chats left out of search";
    static final String LEFT_OUT_RECENTS = "hidden chats left out of recent searches";
    static final String SILENCED = "hidden chat notifications dropped";
    static final String REDRAWN = "inbox redrawn after hidden chats changed";
    static final String PAGED = "inbox pages asked for to show hidden chats";

    /** Steps a failure is reported under. */
    static final String LIST = "hidden chats";
    static final String FILTER = "inbox filter";
    static final String REDRAW = "inbox redraw";
    static final String FOLDER_FILTER = "folder count filter";
    static final String BADGE_FILTER = "unread badge filter";
    static final String SEARCH = "inbox search filter";
    static final String RECENTS = "recent searches filter";
    static final String PAGE = "inbox next page";

    /** Instagram's own names for a chat as the inbox search lists it, and for a chat whose messages matched. */
    private static final String SHARE_TARGET = "com.instagram.model.direct.DirectShareTarget";
    private static final String HIT_THREAD = "com.instagram.model.direct.DirectMessageSearchThread";
    private static final String HIT_MESSAGE = "com.instagram.model.direct.DirectMessageSearchMessage";

    /** Reads the thread id of one of the inbox's thread summaries. Tests put a fake in. */
    interface Reader {
        String threadId(Object summary);
    }

    static volatile Reader reader = HiddenChats::threadId;

    /** Reads the thread id of a chat in the inbox search's results. Tests put a fake in. */
    static volatile Reader searchReader = HiddenChats::targetThreadId;

    /** Hands an inbox holder a list and has it draw its rows from it. Tests put a fake in. */
    interface Redrawer {
        void redraw(Object holder, List<Object> summaries);
    }

    static volatile Redrawer redrawer = HiddenChats::redraw;

    /** The inbox shows the hidden chats, and only them, until {@link #conceal}. */
    private static volatile boolean revealed;

    private HiddenChats() {
    }

    // ---------------------------------------------------------------- shown by a double tap

    /** The inbox shows the hidden chats now, by themselves. */
    public static boolean revealed() {
        return revealed;
    }

    /** The inbox shows the hidden chats by themselves, until {@link #conceal}. */
    static void reveal() {
        synchronized (pagers) {
            asked = 0;
            askedAt = -1;
        }
        revealed = true;
        ChatGroups.left();
        changedSoon();
    }

    /** The inbox goes back to every chat but the hidden ones. False when they weren't shown. */
    static boolean conceal() {
        if (!revealed) return false;
        revealed = false;
        changedSoon();
        return true;
    }

    // ---------------------------------------------------------------- paging the hidden chats in

    /** The most pages one showing of the hidden chats asks an inbox for, a page being about 20 chats. */
    static final int PAGES_ASKED = 30;

    /** Asks an inbox's load-more for its next page. Tests put a fake in. */
    interface Pages {
        void next(Object pager);
    }

    static volatile Pages pages = HiddenChats::nextPage;

    /** The load-mores the inboxes made, held weakly. Guards {@link #asked} and {@link #askedAt} too. */
    private static final ArrayList<WeakReference<Object>> pagers = new ArrayList<>();
    /** Pages asked for since the hidden chats were last shown. */
    private static int asked;
    /** How many chats Instagram's list held at the last ask, or -1 before one. */
    private static int askedAt = -1;

    /**
     * Asked with an inbox's load-more as the inbox makes it: the one its list's scroll asks for the
     * next page of chats near the end. Kept weakly, for {@link #pageIn}.
     */
    public static void pager(Object pager) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (pager == null) return;
            synchronized (pagers) {
                for (Iterator<WeakReference<Object>> it = pagers.iterator(); it.hasNext(); ) {
                    Object kept = it.next().get();
                    if (kept == null || kept == pager) it.remove();
                }
                pagers.add(new WeakReference<>(pager));
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, PAGE, t);
        }
    }

    /**
     * Asks [pager], an inbox's load-more, for its next page of chats, as a scroll near the end of the
     * list does. The patch writes the body at patch time, and a build without it does nothing.
     */
    public static void nextPage(Object pager) {
    }

    /**
     * While the hidden chats show by themselves, one Instagram hasn't loaded yet has no row: after a
     * fresh start the inbox holds about its first page of chats, and with every other chat left out
     * there's no list to scroll for more (#661). So while a hidden chat is missing from [summaries],
     * Instagram's own list, each inbox's load-more is asked for its next page on the main thread, once
     * for each list that grew, up to {@link #PAGES_ASKED} pages a showing. A page comes back through
     * {@link #inbox}, which asks again until every hidden chat shows or Instagram has no more.
     */
    private static void pageIn(@Nullable List<Object> summaries, View view) {
        if (!view.shown || summaries == null) return;
        if (only(summaries, view.hidden, Collections.emptySet()).size() >= view.hidden.size()) return;
        List<Object> asking = new ArrayList<>();
        synchronized (pagers) {
            if (asked >= PAGES_ASKED || summaries.size() <= askedAt) return;
            for (Iterator<WeakReference<Object>> it = pagers.iterator(); it.hasNext(); ) {
                Object kept = it.next().get();
                if (kept == null) it.remove();
                else asking.add(kept);
            }
            if (asking.isEmpty()) return;
            asked++;
            askedAt = summaries.size();
        }
        HookStatus.counted(FamilyNames.MESSAGES_LOCK, PAGED);
        Utils.runOnMainThread(() -> {
            for (Object pager : asking) {
                try {
                    pages.next(pager);
                } catch (Throwable t) {
                    HookStatus.threw(FamilyNames.MESSAGES_LOCK, PAGE, t);
                }
            }
        });
    }

    /** Forgets every load-more and what was asked of them, and asks through the patch's bridge again. */
    static void pagersForgottenForTests() {
        synchronized (pagers) {
            pagers.clear();
            asked = 0;
            askedAt = -1;
        }
        pages = HiddenChats::nextPage;
    }

    /**
     * The thread id of a thread summary, or null when it has none. The patch writes the body at
     * patch time, from the summary's own chat key getter and the key's thread id field, and a build
     * without it answers null, so no chat is hidden from the inbox.
     */
    public static String threadId(Object summary) {
        return null;
    }

    // ---------------------------------------------------------------- the inbox

    /**
     * Asked with the inbox's holder and the list of chats it is about to keep and turn into rows,
     * each time Instagram hands it one: the one read from the phone at start, a fresh page from the
     * server, a pull to refresh, a switch of folder tab. A list with no hidden chat in it comes back
     * as it is, and otherwise a copy without the hidden ones does. Instagram's own list is left as it
     * was, so the inbox's state, its next page and what it saves to the phone still have every chat.
     * The holder is remembered weakly with Instagram's own list, so a change to the hidden chats can
     * draw it again ({@link #changed}).
     */
    public static List<Object> inbox(Object holder, List<Object> summaries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            View view = view();
            remember(holder, summaries, view);
            if (summaries == null || summaries.isEmpty()) return summaries;
            List<Object> rows = drawnRows(summaries, view, true);
            pageIn(summaries, view);
            return rows;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, FILTER, t);
            return summaries;
        }
    }

    /**
     * What the inbox shows of Instagram's list: the chats it leaves out, or the ones it shows by
     * themselves, and the ones it puts first.
     */
    static final class View {
        final Set<String> hidden;
        final List<String> pins;
        /** It shows the hidden chats by themselves. */
        final boolean shown;
        /** Chats in a chat group, left out while no group shows ({@link ChatGroups}). */
        final Set<String> grouped;
        /** The chats of the group it shows by itself, or null while it shows all chats. */
        @Nullable final Set<String> group;

        View(Set<String> hidden, List<String> pins, boolean shown, Set<String> grouped, @Nullable Set<String> group) {
            this.hidden = hidden;
            this.pins = pins;
            this.shown = shown;
            this.grouped = grouped;
            this.group = group;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof View)) return false;
            View that = (View) other;
            return shown == that.shown && hidden.equals(that.hidden) && pins.equals(that.pins)
                    && grouped.equals(that.grouped) && Objects.equals(group, that.group);
        }

        @Override public int hashCode() {
            return Objects.hash(hidden, pins, shown, grouped, group);
        }
    }

    /** What the inbox shows now, for the account in front. */
    private static View view() {
        Set<String> hidden = ids();
        boolean shown = revealed && !hidden.isEmpty();
        String account = ChatAccounts.active();
        String group = shown ? null : ChatGroups.showing(account);
        return new View(hidden, PinnedChats.ids(), shown, ChatGroups.grouped(account),
                group == null ? null : ChatGroups.in(account, group));
    }

    /** A new list of the summaries whose chats are [kept] and not [except], in their order. */
    private static List<Object> only(List<Object> summaries, Set<String> kept, Set<String> except) {
        ArrayList<Object> rows = new ArrayList<>();
        for (Object summary : summaries) {
            String id = idOf(summary);
            if (id != null && kept.contains(id) && !except.contains(id)) rows.add(summary);
        }
        return rows;
    }

    /**
     * What an inbox draws from [summaries]: the hidden chats alone while they're shown, a chat
     * group's chats alone while it's shown, and else every chat neither hidden nor in a group, pins
     * first. With [counted], what's left out is counted for the diagnostic report.
     */
    private static List<Object> drawnRows(List<Object> summaries, View view, boolean counted) {
        List<Object> rows;
        if (view.shown) {
            rows = only(summaries, view.hidden, Collections.emptySet());
        } else if (view.group != null) {
            rows = only(summaries, view.group, view.hidden);
            if (counted) HookStatus.counted(FamilyNames.MESSAGES_LOCK, ChatGroups.SHOWN);
        } else {
            rows = without(without(summaries, view.hidden, counted ? LEFT_OUT : null),
                    view.grouped, counted ? ChatGroups.LEFT_OUT : null);
        }
        return PinnedChats.first(rows, view.pins);
    }

    /**
     * Asked with one folder tab's chats, which Instagram counts the tab's unread chats from (the
     * "1 unread message" on a folder pill). The same as {@link #inbox}, so a hidden chat's unread
     * messages don't count on any tab.
     */
    public static List<Object> folder(List<Object> summaries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (summaries == null || summaries.isEmpty()) return summaries;
            return without(summaries, ids(), LEFT_OUT_FOLDERS);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, FOLDER_FILTER, t);
            return summaries;
        }
    }

    /** [summaries] as they are when none is [hidden], and otherwise a copy without those, counted when [counted] isn't null. */
    private static List<Object> without(List<Object> summaries, Set<String> hidden, String counted) {
        if (hidden.isEmpty() || summaries.isEmpty()) return summaries;
        ArrayList<Object> shown = shown(summaries, hidden);
        if (shown.size() == summaries.size()) return summaries;
        if (counted != null) HookStatus.counted(FamilyNames.MESSAGES_LOCK, counted);
        return shown;
    }

    /**
     * Asked with the thread summaries Instagram's unread badge snapshot is about to read. A list
     * with no hidden chat in it comes back as it is. Otherwise a copy without the hidden ones does,
     * and Instagram's own list is left as it was.
     */
    public static ArrayList<Object> filter(ArrayList<Object> summaries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (summaries == null || summaries.isEmpty()) return summaries;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return summaries;
            ArrayList<Object> shown = shown(summaries, hidden);
            if (shown.size() != summaries.size()) HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_BADGE);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, BADGE_FILTER, t);
            return summaries;
        }
    }

    /**
     * Asked with the thread summaries Instagram's unread badge snapshot has just read, when it reads
     * them through the thread store's helper that wraps them so nothing can change them (456). A
     * list with no hidden chat in it comes back as it is. Otherwise a copy without the hidden ones
     * does, wrapped the same way.
     */
    public static List<Object> badge(List<Object> summaries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (summaries == null || summaries.isEmpty()) return summaries;
            List<Object> shown = without(summaries, ids(), LEFT_OUT_BADGE);
            return shown == summaries ? summaries : Collections.unmodifiableList(shown);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, BADGE_FILTER, t);
            return summaries;
        }
    }

    /** A new list of the summaries whose chats aren't hidden, in their order. */
    private static ArrayList<Object> shown(List<Object> summaries, Set<String> hidden) {
        ArrayList<Object> shown = new ArrayList<>(summaries.size());
        for (Object summary : summaries) {
            String id = idOf(summary);
            if (id == null || !hidden.contains(id)) shown.add(summary);
        }
        return shown;
    }

    static String idOf(Object summary) {
        try {
            String id = reader.threadId(summary);
            return id == null || id.trim().isEmpty() ? null : id.trim();
        } catch (Throwable t) {
            // One summary that can't be read stays in the inbox; the rest are still filtered.
            return null;
        }
    }

    // ---------------------------------------------------------------- drawing the inbox again

    /**
     * Hands the inbox holder [holder] the list [summaries] and runs its row factory on it: the two
     * steps Instagram's own subscriber takes each time the inbox's state hands the holder a list. The
     * patch writes the body at patch time, from the holder's list field and its own starter, and a
     * build without it does nothing. The starter does nothing while its inbox is paused or gone, and
     * the inbox runs it again when it resumes, so the rows are drawn from this list then.
     */
    public static void redraw(Object holder, List<Object> summaries) {
    }

    /** An inbox holder Instagram handed a list, that list, and what it drew of it. */
    private static final class Drawn {
        final WeakReference<Object> holder;
        final List<Object> summaries;
        View view;

        Drawn(Object holder, List<Object> summaries, View view) {
            this.holder = new WeakReference<>(holder);
            this.summaries = summaries;
            this.view = view;
        }
    }

    /** The inbox holders Instagram handed a list, one entry each. A holder Instagram dropped goes the next time it's read. */
    private static final ArrayList<Drawn> drawn = new ArrayList<>();

    /**
     * Keeps [holder] with Instagram's own list [summaries] and what it draws of it, in place of what
     * it had. A null list forgets the holder. Nothing is kept while HushGram is paused or its
     * settings aren't ready, so nothing is drawn again then either.
     */
    private static void remember(Object holder, List<Object> summaries, View view) {
        if (holder == null || !Utils.settingsReady() || HushgramPause.isPaused()) return;
        synchronized (drawn) {
            for (Iterator<Drawn> it = drawn.iterator(); it.hasNext(); ) {
                Object kept = it.next().holder.get();
                if (kept == null || kept == holder) it.remove();
            }
            if (summaries != null) drawn.add(new Drawn(holder, summaries, view));
        }
    }

    /**
     * Asked after the hidden chats were saved. Each inbox holder Instagram still has, whose rows the
     * change adds a chat to or takes one from, is handed its list again without the chats hidden now
     * and draws its rows from it ({@link #redraw}). Nothing runs while HushGram is paused or its
     * settings aren't ready, for a holder whose rows stay the same (nothing hidden before or after,
     * the same chats hidden, or a chat that isn't in its list), or for one Instagram dropped. No
     * listener or timer is left behind: this runs once for each change and is done.
     */
    static void changed() {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return;
            List<Object> holders = new ArrayList<>();
            List<List<Object>> lists = new ArrayList<>();
            View now;
            List<Object> longest = null;
            synchronized (drawn) {
                if (drawn.isEmpty()) return;
                now = view();
                for (Iterator<Drawn> it = drawn.iterator(); it.hasNext(); ) {
                    Drawn one = it.next();
                    Object holder = one.holder.get();
                    if (holder == null) {
                        it.remove();
                        continue;
                    }
                    if (longest == null || one.summaries.size() > longest.size()) longest = one.summaries;
                    if (one.view.equals(now)) continue;
                    List<Object> before = drawnRows(one.summaries, one.view, false);
                    List<Object> after = drawnRows(one.summaries, now, false);
                    one.view = now;
                    if (sameRows(before, after)) continue;
                    holders.add(holder);
                    lists.add(after);
                }
            }
            for (int i = 0; i < holders.size(); i++) {
                try {
                    redrawer.redraw(holders.get(i), lists.get(i));
                    HookStatus.counted(FamilyNames.MESSAGES_LOCK, REDRAWN);
                } catch (Throwable t) {
                    HookStatus.threw(FamilyNames.MESSAGES_LOCK, REDRAW, t);
                }
            }
            pageIn(longest, now);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, REDRAW, t);
        }
    }

    /** The same summaries in the same order. */
    private static boolean sameRows(List<Object> a, List<Object> b) {
        if (a == b) return true;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    /**
     * Asks {@link #changed} on the main thread, where Instagram hands the inbox its lists: at once
     * when it's asked there, as a screen coming to the front with another account is.
     */
    static void changedSoon() {
        Utils.runOnMainThreadNowOrLater(HiddenChats::changed);
    }

    /** Instagram dropped every inbox holder, as the garbage collector does once its screens are gone. */
    static void holdersDroppedForTests() {
        synchronized (drawn) {
            for (Drawn one : drawn) one.holder.clear();
        }
    }

    /** How many inbox holders are kept now. */
    static int keptForTests() {
        synchronized (drawn) {
            return drawn.size();
        }
    }

    // ---------------------------------------------------------------- search

    /**
     * The thread id of a chat in the inbox search's results, or null when it has none. The patch
     * writes the body at patch time, from Instagram's own result type, and a build without it
     * answers null, so no result is hidden. A result that is only a person has no thread, and stays.
     */
    public static String targetThreadId(Object result) {
        return null;
    }

    /**
     * Asked with the results Instagram's inbox search is about to turn into rows. A list with no
     * hidden chat in it comes back as it is, and otherwise a copy without the hidden ones does.
     * People who aren't a chat yet stay, so a hidden chat doesn't stop you finding its person.
     */
    public static List<Object> searchResults(List<Object> results) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (results == null || results.isEmpty()) return results;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return results;
            List<Object> shown = new ArrayList<>(results.size());
            for (Object result : results) {
                if (!inHiddenChat(result, hidden)) shown.add(result);
            }
            if (shown.size() == results.size()) return results;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_SEARCH);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SEARCH, t);
            return results;
        }
    }

    /** The same for the messages the server found: the ones said in a hidden chat are left out. */
    public static ArrayList<Object> searchHits(ArrayList<Object> hits) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (hits == null || hits.isEmpty()) return hits;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return hits;
            ArrayList<Object> shown = new ArrayList<>(hits.size());
            for (Object hit : hits) {
                if (!inHiddenChat(hit, hidden)) shown.add(hit);
            }
            if (shown.size() == hits.size()) return hits;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_SEARCH);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SEARCH, t);
            return hits;
        }
    }

    /**
     * Asked with the recent searches Instagram's inbox search lists before anything is typed. An
     * entry is either a chat or a wrapper holding one. A person or another kind of search has no
     * chat and stays. A list with no hidden chat in it comes back as it is, and otherwise a copy
     * without the hidden ones does.
     */
    public static List<Object> recents(List<Object> entries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (entries == null || entries.isEmpty()) return entries;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return entries;
            List<Object> shown = new ArrayList<>(entries.size());
            for (Object entry : entries) {
                if (!recentInHiddenChat(entry, hidden)) shown.add(entry);
            }
            if (shown.size() == entries.size()) return entries;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_RECENTS);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, RECENTS, t);
            return entries;
        }
    }

    /**
     * The entry is a chat on the list, or holds one in a field of Instagram's chat type. The
     * wrapper's field name changes with each build and its type doesn't. An entry that can't be
     * read stays.
     */
    private static boolean recentInHiddenChat(Object entry, Set<String> hidden) {
        try {
            if (entry == null) return false;
            Object target = isShareTarget(entry.getClass()) ? entry : heldShareTarget(entry);
            if (target == null) return false;
            String id = searchReader.threadId(target);
            return id != null && hidden.contains(id.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isShareTarget(Class<?> type) {
        for (; type != null; type = type.getSuperclass()) {
            if (SHARE_TARGET.equals(type.getName())) return true;
        }
        return false;
    }

    private static Object heldShareTarget(Object entry) throws IllegalAccessException {
        for (Class<?> type = entry.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !isShareTarget(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(entry);
                if (value != null) return value;
            }
        }
        return null;
    }

    /** The result is a chat on the list, or a message said in one. A result that can't be read isn't. */
    private static boolean inHiddenChat(Object result, Set<String> hidden) {
        try {
            if (result == null) return false;
            for (Class<?> type = result.getClass(); type != null; type = type.getSuperclass()) {
                String name = type.getName();
                if (SHARE_TARGET.equals(name)) {
                    String id = searchReader.threadId(result);
                    return id != null && hidden.contains(id.trim());
                }
                if (HIT_THREAD.equals(name) || HIT_MESSAGE.equals(name)) return carriesAny(result, type, hidden);
            }
        } catch (Throwable t) {
            // One result that can't be read stays in the search; the rest are still filtered.
        }
        return false;
    }

    /**
     * A message match keeps its chat's thread id in one of its text fields, under a name that
     * changes with each Instagram build. A thread id is a long number nothing else in the match
     * looks like, so any text field holding a hidden chat's is the chat.
     */
    private static boolean carriesAny(Object hit, Class<?> type, Set<String> hidden) throws IllegalAccessException {
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (field.getType() != String.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(hit);
            if (value instanceof String && hidden.contains(((String) value).trim())) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- notifications

    /**
     * Some id a push carries (its deep link's chat id, its thread id or its thread IG id) is a chat
     * hidden on [account], the one the push was sent to, or on any account when that isn't known.
     * Counted when it is, so the diagnostic report shows pushes that were dropped.
     */
    static boolean hides(Collection<String> pushIds, @Nullable String account) {
        try {
            Set<String> hidden = idsFor(account);
            if (hidden.isEmpty()) return false;
            for (String id : pushIds) {
                if (hidden.contains(id)) {
                    HookStatus.counted(FamilyNames.MESSAGES_LOCK, SILENCED);
                    return true;
                }
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return false;
    }

    // ---------------------------------------------------------------- the list

    /**
     * The list as last parsed, with the text it came from, and the ids it hides for the account they
     * were last asked for, so a list's worth of rows parse it once.
     */
    private static final class Parsed {
        final String raw;
        final List<ChatList.Row> rows;
        @Nullable final String account;
        final Set<String> ids;

        Parsed(String raw, List<ChatList.Row> rows, @Nullable String account) {
            this.raw = raw;
            this.rows = rows;
            this.account = account;
            this.ids = Collections.unmodifiableSet(ChatList.ids(rows, account));
        }
    }

    private static volatile Parsed parsed;

    /** The thread ids hidden now for the account in front, or for every account while none is known. */
    private static Set<String> ids() {
        return idsFor(ChatAccounts.active());
    }

    /**
     * The thread ids hidden now for [account], or for every account when it's null, none while
     * HushGram is paused; parsed again only when the list or the account changed.
     */
    private static Set<String> idsFor(@Nullable String account) {
        if (!Utils.settingsReady()) return Collections.emptySet();
        String text = Settings.HIDDEN_CHATS.get();
        if (text.isEmpty()) return Collections.emptySet();
        Parsed kept = parsed;
        if (kept == null || !kept.raw.equals(text)) {
            kept = new Parsed(text, Collections.unmodifiableList(ChatList.parse(text)), account);
            parsed = kept;
        } else if (!Objects.equals(kept.account, account)) {
            kept = new Parsed(text, kept.rows, account);
            parsed = kept;
        }
        return kept.ids;
    }

    /** The chats hidden now on [account], oldest first: none while HushGram is paused or for an account that isn't known. */
    public static List<ChatLocks.Chat> chats(@Nullable String account) {
        return ChatList.shown(rows(false), account);
    }

    /** The same for the account in front. */
    public static List<ChatLocks.Chat> chats() {
        return chats(ChatAccounts.active());
    }

    /** The chats hidden on [account] as chosen, paused or not: none for an account that isn't known. The settings screen shows these. */
    public static List<ChatLocks.Chat> saved(@Nullable String account) {
        return ChatList.shown(rows(true), account);
    }

    /** The same for the account in front. */
    public static List<ChatLocks.Chat> saved() {
        return saved(ChatAccounts.active());
    }

    /** Every account's hidden chats, as they are now or, with [chosen], as chosen whether paused or not. */
    private static List<ChatList.Row> rows(boolean chosen) {
        try {
            if (Utils.settingsReady()) {
                return ChatList.parse(chosen ? Settings.HIDDEN_CHATS.savedValue() : Settings.HIDDEN_CHATS.get());
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return new ArrayList<>();
    }

    /** The chat with [id] is hidden now on [account], or on any account when it's null. */
    public static boolean listed(@Nullable String account, String id) {
        return ChatList.protects(rows(false), account, id);
    }

    /** The same for the account in front, or any account while none is known. */
    public static boolean listed(String id) {
        return listed(ChatAccounts.active(), id);
    }

    /** The chat with [id] is on [account]'s chosen list, or any account's when it's null, paused or not. */
    public static boolean savedListed(@Nullable String account, String id) {
        return ChatList.protects(rows(true), account, id);
    }

    /** The last chat opened on [account], if it isn't hidden there. */
    public static ChatLocks.Chat lastOpened(@Nullable String account) {
        return ChatLocks.lastOpenedUnless(account, HiddenChats::savedListed);
    }

    /** The same for the account in front. */
    public static ChatLocks.Chat lastOpened() {
        return lastOpened(ChatAccounts.active());
    }

    /**
     * Hides a chat on [account], or renames it on that account's list, and the inbox leaves it out.
     * False, with nothing changed, for an account that isn't known, before the settings are read, or
     * when the list couldn't be saved.
     */
    public static boolean add(@Nullable String account, String id, String name) {
        if (empty(account) || empty(id)) return false;
        boolean saved = change(rows -> ChatList.added(rows, account, id, name));
        if (saved) changedSoon();
        return saved;
    }

    /** The same for the account in front. */
    public static boolean add(String id, String name) {
        return add(ChatAccounts.active(), id, name);
    }

    /**
     * Brings a chat back on [account], in the inbox too, and on no other account. False, with the
     * chat still hidden, for an account that isn't known, before the settings are read, or when the
     * list couldn't be saved.
     */
    public static boolean remove(@Nullable String account, String id) {
        if (empty(account) || empty(id)) return false;
        boolean saved = change(rows -> ChatList.removed(rows, account, id));
        if (saved) changedSoon();
        return saved;
    }

    /** The same for the account in front. */
    public static boolean remove(String id) {
        return remove(ChatAccounts.active(), id);
    }

    /**
     * [account], signed in, found its chat [id] under [name]: a chat hidden on it takes the name, and
     * one hidden from before accounts were gets a line of its own for it ({@link ChatList#named}).
     */
    static void named(String account, String id, String name) {
        if (empty(account) || empty(id)) return;
        change(rows -> ChatList.named(rows, account, id, name));
    }

    /**
     * Saves the list [edit] makes of the one chosen, read fresh, paused or not. True when it's saved
     * or there was nothing to change. A list that can't be read or saved is left as it was.
     */
    private static boolean change(UnaryOperator<List<ChatList.Row>> edit) {
        try {
            if (!Utils.settingsReady()) return false;
            List<ChatList.Row> rows = ChatList.parse(Settings.HIDDEN_CHATS.savedValue());
            List<ChatList.Row> changed = edit.apply(rows);
            return changed == rows || Settings.HIDDEN_CHATS.save(ChatList.format(changed));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
            return false;
        }
    }

    /** Another account came to the front: the list is parsed again for it. */
    static void accountChanged() {
        parsed = null;
    }

    private static boolean empty(String text) {
        return text == null || text.trim().isEmpty();
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        reader = HiddenChats::threadId;
        searchReader = HiddenChats::targetThreadId;
        redrawer = HiddenChats::redraw;
        parsed = null;
        revealed = false;
        ChatGroups.resetForTests();
        synchronized (drawn) {
            drawn.clear();
        }
    }
}

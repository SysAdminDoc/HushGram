/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for "Messages after a reel", under Swipe through a chat's reels (#489).
 *
 * <p>Swiping through a chat's reels, the messages the other person wrote between them went by unseen.
 * The opener of a reel shared in a chat hands each of the chat's loaded messages to the collector
 * {@link ChatReels#received(Object, Object)} answers for, which hands them on to
 * {@link #note(Object, Object)}: each message's id, time, side and reel, kept until the next tap. As the
 * viewer selects a page, the patch calls {@link #page(Object, int)} from the reply bar's page select,
 * and a banner over the viewer says when the other person sent a message after the reel on screen,
 * before the chain's next reel. A tap on it goes Back to the chat and scrolls to that message when the
 * chat's list holds it, or just back to the chat. Nothing is ever sent.
 */
public final class ChatReelsNotice {
    /** How many of the chat's messages are kept at most. */
    static final int NOTES_KEPT = 2_000;
    /** How many times a banner looks for the viewer it goes over, {@link #TRY_MS} apart. */
    static final int SHOW_TRIES = 10;
    /** How many times a tap looks for the chat's list after Back, {@link #TRY_MS} apart. */
    static final int BACK_TRIES = 30;
    static final long TRY_MS = 100;
    /** How far below the status bar the banner sits, clear of the viewer's top row. */
    static final int TOP_DP = 64;

    static final String SHOWN = "message after a chat reel pointed out";
    static final String TAPPED = "tapped a message after a chat reel";
    static final String WENT = "went back to a message after a chat reel";
    static final String NOT_ON_ROWS = "message after a chat reel not on the chat's rows";
    static final String NO_LIST = "no chat list after a chat reel's banner";
    private static final String FAILED = "chat reels message after";

    /** One of a chat's messages as the opener added it to the chain. */
    static final class Note {
        final String id;
        final long millis;
        /** Whether the viewer sent it. */
        final boolean viewer;
        /** Whether it holds a reel, and the reel's media id when that could be read. */
        final boolean reel;
        @Nullable final String media;

        Note(String id, long millis, boolean viewer, boolean reel, @Nullable String media) {
            this.id = id;
            this.millis = millis;
            this.viewer = viewer;
            this.reel = reel;
            this.media = media;
        }
    }

    /** The messages of the chat last opened, by id, in the order they came. */
    private static final Map<String, Note> NOTES = new LinkedHashMap<String, Note>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Note> eldest) {
            return size() > NOTES_KEPT;
        }
    };
    /** The tap the notes came after. Guarded by {@link #NOTES}. */
    private static int notesTap = -1;

    /** The bubble last tapped in a chat, whose list a tap on the banner goes back to. */
    @Nullable private static volatile WeakReference<View> bubble;
    /** The banner up, touched on the main thread. */
    @Nullable private static Banner banner;
    /** Counts page selects and taps, so a late show for an earlier page does nothing. */
    private static volatile int pages;
    @Nullable private static Handler handler;

    /** Reads a chat list's messages through the bridges this patch writes, with no need for Chat history tools. */
    static final ChatHistory.Reader READER = new ChatHistory.Reader() {
        @Override public boolean isMessage(Object candidate) {
            return ChatReelsNotice.isMessage(candidate);
        }

        @Override public String id(Object message) {
            return ChatReels.itemId(message);
        }

        @Override public String sender(Object message) {
            return null;
        }

        @Override public String text(Object message) {
            return null;
        }

        @Override public String time(Object message) {
            return timeOf(message);
        }

        @Override public String kind(Object message) {
            return null;
        }

        @Override public boolean sentByViewer(Object message) {
            return ChatReels.sentByViewer(message);
        }
    };

    private ChatReelsNotice() {
    }

    /** Whether the banner is wanted: both switches on, HushGram not paused and the settings read. */
    static boolean on() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.SWIPE_CHAT_REELS.get()
                && Settings.MESSAGES_AFTER_CHAT_REELS.get();
    }

    /**
     * Notes [message], one of the chat's messages the opener is adding to the chain, with its wrapper
     * [wrapper]: its id, time, whether the viewer sent it and the reel it holds. Nothing while the
     * switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    static void note(@Nullable Object message, @Nullable Object wrapper) {
        try {
            if (message == null || !on()) return;
            Object reel = wrapper == null ? null : ChatReels.reel(wrapper);
            remember(ChatReels.itemId(message), ChatHistory.millis(timeOf(message)), ChatReels.sentByViewer(message),
                    reel != null, reel == null ? null : ChatReels.mediaId(reel));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, FAILED, failure);
        }
    }

    /** Keeps a message, starting over after a new tap. One with no id or time is left out. */
    static void remember(@Nullable String id, long millis, boolean viewer, boolean reel, @Nullable String media) {
        if (id == null || id.isEmpty() || millis <= 0) return;
        synchronized (NOTES) {
            int tap = ChatReels.tapCount();
            if (notesTap != tap) {
                NOTES.clear();
                notesTap = tap;
            }
            NOTES.remove(id);
            NOTES.put(id, new Note(id, millis, viewer, reel, media));
        }
    }

    /**
     * The first message the other person sent after a message holding the reel [media] and before the
     * chain's next reel, or null. The chain holds every reel of the chat, except the viewer's own while
     * "Only received reels" is on, so those and the viewer's own messages don't end the stretch. A reel
     * shared more than once is looked after at each share.
     */
    @Nullable
    static Note after(@Nullable String media) {
        if (media == null) return null;
        List<Note> notes;
        synchronized (NOTES) {
            if (notesTap != ChatReels.tapCount() || NOTES.isEmpty()) return null;
            notes = new ArrayList<>(NOTES.values());
        }
        Collections.sort(notes, (a, b) -> Long.compare(a.millis, b.millis));
        boolean onlyReceived = Settings.ONLY_RECEIVED_CHAT_REELS.get();
        boolean past = false;
        for (Note note : notes) {
            if (note.reel) {
                if (media.equals(note.media)) past = true;
                else if (!note.viewer || !onlyReceived) past = false;
            } else if (past && !note.viewer) {
                return note;
            }
        }
        return null;
    }

    /** Forgets every message, for tests. */
    static void forget() {
        synchronized (NOTES) {
            NOTES.clear();
            notesTap = -1;
        }
    }

    /** A tap on [view], a bubble in a chat, is about to open its media. */
    static void opened(@Nullable View view) {
        bubble = view == null ? null : new WeakReference<>(view);
    }

    /**
     * The reply bar of a Reels viewer, [controller], selected the page at [position]: the banner shows
     * when a chat's viewer has a message from the other person after that page's reel, and goes away
     * for any other page or viewer, with the switch off, while HushGram is paused and before the
     * settings are ready. Never throws.
     */
    public static void page(@Nullable Object controller, int position) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            point(controller == null || position < 0 || !on() ? null : target(controller, position));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, FAILED, failure);
        }
    }

    /** Puts the banner up for [target] once the chat's viewer shows, or takes it down for null, unless a later page comes first. */
    static void point(@Nullable Note target) {
        int token = ++pages;
        if (target == null && banner == null) return;
        handler().post(() -> show(target, token, SHOW_TRIES));
    }

    /** The message after the reel at [position] of [controller]'s viewer, when that viewer is a chat's. */
    @Nullable
    private static Note target(Object controller, int position) {
        if (!ChatReels.fromChat(sourceOf(controller))) return null;
        Object adapter = adapterOf(controller);
        if (adapter == null || position >= ChatReels.count(adapter)) return null;
        Object item = ChatReels.item(adapter, position);
        return item == null ? null : after(ChatReels.mediaId(item));
    }

    /**
     * Puts the banner for [target] over the chat's viewer on screen, or takes it down for null. Asks
     * again a few times while no chat viewer shows yet. Nothing once a later page or a tap came.
     */
    static void show(@Nullable Note target, int token, int tries) {
        try {
            if (token != pages) return;
            if (target == null) {
                hide();
                return;
            }
            View pager = ChatReelsExit.shownPager();
            Activity owner = pager == null ? null : ChatReelsExit.ownerOf(pager);
            View content = owner == null ? null : owner.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) {
                if (tries > 1) handler().postDelayed(() -> show(target, token, tries - 1), TRY_MS);
                return;
            }
            Banner up = banner;
            if (up != null && (up.content != content || up.pager.get() != pager)) {
                hide();
                up = null;
            }
            if (up == null) {
                up = new Banner(pager, (FrameLayout) content);
                banner = up;
            }
            boolean fresh = up.target == null || !up.target.id.equals(target.id);
            up.target = target;
            up.place();
            if (fresh) HookStatus.counted(FamilyNames.CHAT_REELS, SHOWN);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, FAILED, failure);
        }
    }

    /** Takes the banner down, on the main thread. */
    static void hide() {
        Banner up = banner;
        banner = null;
        if (up != null) up.remove();
    }

    /** The banner's view while one is up, for tests. */
    @Nullable
    static TextView shownBanner() {
        Banner up = banner;
        return up == null ? null : up.view;
    }

    /** A tap on the banner: Back to the chat, then to the message once the chat's list shows. */
    private static void tapped(Banner tapped) {
        try {
            Note target = tapped.target;
            View pager = tapped.pager.get();
            pages++;
            hide();
            Activity owner = pager == null ? null : ChatReelsExit.ownerOf(pager);
            if (target == null || owner == null || owner.isFinishing() || owner.isDestroyed()) return;
            HookStatus.counted(FamilyNames.CHAT_REELS, TAPPED);
            owner.onBackPressed();
            WeakReference<Activity> left = new WeakReference<>(owner);
            handler().postDelayed(() -> goTo(target, left, BACK_TRIES), TRY_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, FAILED, failure);
        }
    }

    /** Scrolls the chat's list to [target] once it shows again, asking up to [tries] times. */
    static void goTo(Note target, WeakReference<Activity> left, int tries) {
        try {
            View list = chatList(left.get());
            if (list == null) {
                if (tries > 1) handler().postDelayed(() -> goTo(target, left, tries - 1), TRY_MS);
                else HookStatus.counted(FamilyNames.CHAT_REELS, NO_LIST);
                return;
            }
            ChatPager.ListSource source = new ChatPager.ListSource(list, READER);
            int position = positionOf(source.entries(), target.id);
            if (position < 0) {
                HookStatus.counted(FamilyNames.CHAT_REELS, NOT_ON_ROWS);
                return;
            }
            source.scrollTo(position);
            HookStatus.counted(FamilyNames.CHAT_REELS, WENT);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, FAILED, failure);
        }
    }

    /** The row of the message with [id] among [entries], or -1 when none of them is on a known row. */
    static int positionOf(@Nullable List<ChatHistory.Entry> entries, @Nullable String id) {
        if (entries == null || id == null) return -1;
        for (ChatHistory.Entry entry : entries) {
            if (entry.position >= 0 && id.equals(entry.id)) return entry.position;
        }
        return -1;
    }

    /**
     * The chat's list on screen: the list holding the bubble last tapped, else the biggest list in
     * [left]'s window, the activity the viewer was in. Never a list inside a chat's Reels viewer.
     */
    @Nullable
    static View chatList(@Nullable Activity left) {
        WeakReference<View> tapped = bubble;
        View at = tapped == null ? null : tapped.get();
        for (; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            if (ChatPager.ListSource.is(at.getClass(), ChatReelsExit.LIST_TYPE)) {
                if (usable(at)) return at;
                break;
            }
        }
        View decor = left == null || left.getWindow() == null ? null : left.getWindow().peekDecorView();
        if (decor == null) return null;
        List<View> lists = new ArrayList<>();
        collect(decor, lists);
        View best = null;
        for (View list : lists) {
            if (!usable(list)) continue;
            if (best == null || list.getHeight() * list.getWidth() > best.getHeight() * best.getWidth()) best = list;
        }
        return best;
    }

    private static void collect(View view, List<View> found) {
        if (ChatReelsExit.isChatPager(view)) return;
        if (ChatPager.ListSource.is(view.getClass(), ChatReelsExit.LIST_TYPE)) found.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), found);
        }
    }

    /** Whether [list] shows and sits outside every chat's Reels viewer. */
    private static boolean usable(View list) {
        if (!list.isShown()) return false;
        for (Object at = list; at instanceof View; at = ((View) at).getParent()) {
            if (ChatReelsExit.isChatPager((View) at)) return false;
        }
        return true;
    }

    private static Handler handler() {
        Looper main = Looper.getMainLooper();
        if (handler == null || handler.getLooper() != main) handler = new Handler(main);
        return handler;
    }

    /** Takes the banner down and forgets the bubble, for tests. */
    static void resetForTests() {
        hide();
        bubble = null;
        pages++;
        forget();
    }

    /** Stub: the patch writes this, answering [message]'s time as Instagram stores it. */
    @Nullable
    public static String timeOf(Object message) {
        return null;
    }

    /** Stub: the patch writes this, answering whether [candidate] is a direct message. */
    public static boolean isMessage(Object candidate) {
        return false;
    }

    /** Stub: the patch writes this, answering the ClipsViewerSource of [controller], a viewer's reply bar. */
    @Nullable
    public static Object sourceOf(Object controller) {
        return null;
    }

    /** Stub: the patch writes this, answering the adapter of [controller], a viewer's reply bar. */
    @Nullable
    public static Object adapterOf(Object controller) {
        return null;
    }

    /**
     * The banner over a chat's viewer, near its top. It goes away when the viewer's pager leaves its
     * window or stops showing, and a tap on it goes back to the chat.
     */
    private static final class Banner implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> pager;
        final FrameLayout content;
        final TextView view;
        @Nullable Note target;

        Banner(View pager, FrameLayout content) {
            this.pager = new WeakReference<>(pager);
            this.content = content;
            Context context = content.getContext();
            float density = context.getResources().getDisplayMetrics().density;
            view = new Button(context);
            view.setText(L10n.t(context, "New message after this reel"));
            ScreenColors.styleAction(view, true);
            view.setElevation(6 * density);
            view.setFocusable(true);
            view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            view.setOnClickListener(tap -> tapped(this));
            content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            pager.addOnAttachStateChangeListener(this);
            content.getViewTreeObserver().addOnPreDrawListener(this);
        }

        /** Below the status bar by {@link #TOP_DP}, again each time, so a turned screen moves it too. */
        @SuppressWarnings("deprecation")
        void place() {
            WindowInsets insets = content.getRootWindowInsets();
            int bar = insets == null ? 0 : insets.getSystemWindowInsetTop();
            int margin = Math.round(TOP_DP * content.getResources().getDisplayMetrics().density) + bar;
            ViewGroup.LayoutParams place = view.getLayoutParams();
            if (place instanceof ViewGroup.MarginLayoutParams && ((ViewGroup.MarginLayoutParams) place).topMargin != margin) {
                ((ViewGroup.MarginLayoutParams) place).topMargin = margin;
                view.setLayoutParams(place);
            }
        }

        @Override public boolean onPreDraw() {
            View at = pager.get();
            if (at == null || !at.isShown()) handler().post(this::leave);
            return true;
        }

        @Override public void onViewDetachedFromWindow(@NonNull View v) {
            handler().post(this::leave);
        }

        @Override public void onViewAttachedToWindow(@NonNull View v) { }

        /** Its viewer went away, so it goes too. */
        private void leave() {
            if (banner == this) hide();
            else remove();
        }

        void remove() {
            try {
                View at = pager.get();
                if (at != null) at.removeOnAttachStateChangeListener(this);
                ViewTreeObserver observer = content.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.CHAT_REELS, FAILED, failure);
            }
        }
    }
}

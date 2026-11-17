/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Search blocked accounts (#731): a search box above the list of blocked accounts in Instagram's
 * settings that keeps only the accounts whose user name or name holds every word typed in.
 *
 * <p>The list comes a page at a time as it's scrolled, and a search shows too few rows to scroll,
 * so while there are words in the box the next page is asked for each time one arrives, until
 * Instagram says there are no more. The line under the box says how many matched out of how many
 * loaded, and says so when Instagram stopped sending pages before the end.
 *
 * <p>The patch hands {@link #filter} the accounts each time Instagram builds the list's rows, after
 * it has picked between its loading, empty and error screens from the whole list, and calls
 * {@link #attach} once the screen has made its view and kept its list. The bridges further down
 * get their bodies from the patch. With the switch off, or paused, nothing is added and the list
 * goes through untouched. No hook here throws.
 */
public final class BlockedSearch {
    static final String HOOK = "blocked search";
    static final String COUNT_BOX = "blocked search box added";
    static final String COUNT_NO_PLACE = "blocked search found no place for the box";

    /** Marks the box, so a view made again gets one box. */
    static final String TAG = "hushgram_blocked_search";
    /** Part of the class name of a pull to refresh frame, which has to stay right around the list. */
    private static final String REFRESH = "Refresh";

    /** The bits {@link #pages} answers, the same as the patch's PAGES_MORE, PAGES_LOADING and PAGES_FAILED. */
    static final int MORE = 1;
    static final int LOADING = 2;
    static final int FAILED = 4;

    /** What the pager says to do next. */
    static final int STOP = 0;
    static final int WAIT = 1;
    static final int LOAD = 2;

    /** How long the pager waits between looks at the list's pages. */
    static final long TICK_MS = 350;
    /** Pages in a row that may add nobody before the pager gives up. */
    static final int STALLS = 3;

    /** The list's operations. Tests put a fake in. */
    interface Host {
        /** Has the screen build its rows again from the accounts loaded so far. */
        void redraw(Object screen);

        /** Asks for the next page. The loader does nothing while one is loading or when there are no more. */
        void loadMore(Object screen);

        /** {@link #MORE}, {@link #LOADING} and {@link #FAILED} for the screen's list, or -1 with no list. */
        int pages(Object screen);

        @Nullable
        String username(Object account);

        @Nullable
        String fullName(Object account);
    }

    static volatile Host host = new Bridges();

    /** What's in the box on the screen showing now. Empty for no search. */
    static volatile String query = "";
    /** How many accounts the last build was handed, and how many of them it kept. */
    static volatile int loaded;
    static volatile int shown;
    /** What was typed on each screen, so a screen whose view is made again gets its search back. */
    static final Map<Object, String> TYPED = Collections.synchronizedMap(new WeakHashMap<>());
    /** The pager of the box showing now, which a build tells to bring its line up to date. */
    static volatile WeakReference<Pump> live = new WeakReference<>(null);

    private BlockedSearch() {
    }

    /** Whether the switch is on and HushGram isn't paused. Never throws. */
    public static boolean on() {
        try {
            return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.BLOCKED_SEARCH.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BLOCKED_SEARCH, HOOK, failure);
            return false;
        }
    }

    /**
     * Injected where the list's model build starts walking its accounts. Hands back the accounts
     * matching the search, or [accounts] itself with no search, the switch off or anything amiss.
     * Never throws.
     */
    public static List<?> filter(List<?> accounts) {
        try {
            HookStatus.invoked(FamilyNames.BLOCKED_SEARCH);
            if (accounts == null) return null;
            int all = accounts.size();
            List<String> words = words(query);
            List<?> kept = accounts;
            if (!words.isEmpty() && on()) {
                List<Object> matching = new ArrayList<>();
                for (Object account : accounts) {
                    if (matches(account, words)) matching.add(account);
                }
                kept = matching;
            }
            loaded = all;
            shown = kept.size();
            Pump pump = live.get();
            if (pump != null) pump.refreshSoon();
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BLOCKED_SEARCH, HOOK, failure);
            return accounts;
        }
    }

    /** Whether [account]'s user name or name holds every one of [words]. */
    static boolean matches(Object account, List<String> words) {
        String text = normalize(host.username(account)) + " " + normalize(host.fullName(account));
        for (String word : words) {
            if (!text.contains(word)) return false;
        }
        return true;
    }

    /** The words of [typed], each without a leading @, which a user name is often written with. */
    static List<String> words(@Nullable String typed) {
        List<String> words = new ArrayList<>();
        for (String word : normalize(typed).split("\\s+")) {
            String bare = word.startsWith("@") ? word.substring(1) : word;
            if (!bare.isEmpty()) words.add(bare);
        }
        return words;
    }

    /** [text] in lower case with its accents off, so "jose" finds a name with an accented e. */
    static String normalize(@Nullable String text) {
        if (text == null) return "";
        String apart = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder bare = new StringBuilder(apart.length());
        for (int at = 0; at < apart.length(); ) {
            int point = apart.codePointAt(at);
            int type = Character.getType(point);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK && type != Character.ENCLOSING_MARK) {
                bare.appendCodePoint(point);
            }
            at += Character.charCount(point);
        }
        return bare.toString().toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Injected in the blocked list screen's onViewCreated, right after it keeps its list. Puts the
     * box above the list and brings back what was typed on this screen before. Never throws.
     *
     * @param screen the blocked accounts fragment
     * @param root the screen's root view
     * @param list the screen's RecyclerView
     */
    public static void attach(Object screen, View root, View list) {
        try {
            HookStatus.invoked(FamilyNames.BLOCKED_SEARCH);
            String typed = TYPED.get(screen);
            query = typed == null ? "" : typed;
            if (root == null || list == null || !on()) return;
            place(screen, root, list);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BLOCKED_SEARCH, HOOK, failure);
        }
    }

    /**
     * Puts the box right above [list], or above the pull to refresh frame around it. In a vertical
     * LinearLayout the box goes in next to it. Anywhere else the list moves into a column of its own
     * that takes its place and its layout, with the box on top. False when the list is the screen's
     * whole view, which leaves nowhere to put it.
     */
    static boolean place(Object screen, View root, View list) {
        View target = list;
        while (target != root && target.getParent() instanceof ViewGroup && refresh(target.getParent())) {
            target = (View) target.getParent();
        }
        if (target == root || !(target.getParent() instanceof ViewGroup)) {
            HookStatus.counted(FamilyNames.BLOCKED_SEARCH, COUNT_NO_PLACE);
            return false;
        }
        ViewGroup parent = (ViewGroup) target.getParent();
        if (parent.findViewWithTag(TAG) != null) return true;
        View box = box(parent.getContext(), screen);
        if (parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL) {
            parent.addView(box, parent.indexOfChild(target),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            int index = parent.indexOfChild(target);
            ViewGroup.LayoutParams where = target.getLayoutParams();
            parent.removeViewAt(index);
            LinearLayout column = new LinearLayout(parent.getContext());
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(target, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            if (where != null) {
                parent.addView(column, index, where);
            } else {
                parent.addView(column, index);
            }
        }
        HookStatus.counted(FamilyNames.BLOCKED_SEARCH, COUNT_BOX);
        return true;
    }

    /** Whether [parent] is a pull to refresh frame, by its class or one it extends. */
    private static boolean refresh(Object parent) {
        for (Class<?> kind = parent.getClass(); kind != null && kind != View.class; kind = kind.getSuperclass()) {
            if (kind.getName().contains(REFRESH)) return true;
        }
        return false;
    }

    /** The box: the field, then the line saying what was searched. */
    static LinearLayout box(Context context, Object screen) {
        int ink = ink(context);
        LinearLayout box = new LinearLayout(context);
        box.setTag(TAG);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 4));

        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setHint(L10n.t("Search blocked accounts"));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setTextColor(ink);
        field.setHintTextColor(withAlpha(ink, 0x8C));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(withAlpha(ink, 0x17));
        fill.setCornerRadius(dp(context, 10));
        field.setBackground(fill);
        field.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(withAlpha(ink, 0xA6));
        status.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), 0);
        status.setVisibility(View.GONE);
        box.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String typed = TYPED.get(screen);
        if (typed != null && !typed.isEmpty()) field.setText(typed);

        Pump pump = new Pump(box, screen, status);
        live = new WeakReference<>(pump);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                try {
                    typedIn(screen, text == null ? "" : text.toString(), pump);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.BLOCKED_SEARCH, HOOK, failure);
                }
            }
        });
        field.setOnEditorActionListener((view, action, event) -> {
            try {
                InputMethodManager keyboard = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.BLOCKED_SEARCH, HOOK, failure);
            }
            return true;
        });
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View attached) {
                try {
                    live = new WeakReference<>(pump);
                    pump.start();
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.BLOCKED_SEARCH, HOOK, failure);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View detached) {
                pump.stop();
            }
        });
        return box;
    }

    /** The box's text changed to [text]: shows the matches at once and loads the rest while there are words. */
    static void typedIn(Object screen, String text, Pump pump) {
        TYPED.put(screen, text);
        query = text;
        live = new WeakReference<>(pump);
        host.redraw(screen);
        pump.start();
    }

    /**
     * Asks for one page after another while there are words in the box, and keeps the line under
     * the box up to date. Runs on the main thread only.
     */
    static final class Pump implements Runnable {
        private final View box;
        private final Object screen;
        private final TextView status;
        private final Pager pager = new Pager();
        private final Runnable refresh = this::refreshNow;
        private boolean running;
        private boolean refreshing;

        Pump(View box, Object screen, TextView status) {
            this.box = box;
            this.screen = screen;
            this.status = status;
        }

        boolean running() {
            return running;
        }

        /** Starts paging when there are words to search for, and stops it when there aren't. */
        void start() {
            if (words(query).isEmpty() || !on()) {
                stop();
            } else if (!running) {
                running = true;
                box.post(this);
            }
            refreshNow();
        }

        void stop() {
            running = false;
            box.removeCallbacks(this);
        }

        @Override
        public void run() {
            try {
                if (!running) return;
                if (!box.isAttachedToWindow() || words(query).isEmpty() || !on()) {
                    stop();
                    refreshNow();
                    return;
                }
                int step = pager.next(host.pages(screen), loaded);
                if (step == LOAD) host.loadMore(screen);
                if (step == STOP) running = false;
                refreshNow();
                if (running) box.postDelayed(this, TICK_MS);
            } catch (Throwable failure) {
                running = false;
                HookStatus.threw(FamilyNames.BLOCKED_SEARCH, HOOK, failure);
            }
        }

        /** Brings the line up to date once the build that's going on now is done. */
        void refreshSoon() {
            if (refreshing) return;
            refreshing = true;
            box.post(refresh);
        }

        void refreshNow() {
            try {
                refreshing = false;
                if (words(query).isEmpty() || !on()) {
                    status.setVisibility(View.GONE);
                    return;
                }
                status.setText(statusText(host.pages(screen), running, loaded, shown));
                status.setVisibility(View.VISIBLE);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.BLOCKED_SEARCH, HOOK, failure);
            }
        }
    }

    /**
     * Decides, from the list's pages and how many accounts have loaded, whether to ask for the next
     * page, wait for one on its way, or stop. It stops when there are no more pages, after a page
     * that failed fails again, and after {@link #STALLS} pages in a row that added nobody, so a list
     * Instagram won't finish isn't asked for forever.
     */
    static final class Pager {
        private int failures;
        private int stalls;
        private int lastLoaded = -1;

        int next(int pages, int loadedNow) {
            if (pages < 0) return STOP;
            if ((pages & LOADING) != 0) return WAIT;
            if ((pages & MORE) == 0) return STOP;
            if (lastLoaded >= 0 && loadedNow > lastLoaded) {
                stalls = 0;
                failures = 0;
            }
            if ((pages & FAILED) != 0) {
                if (failures >= 1) return STOP;
                failures++;
                lastLoaded = loadedNow;
                return LOAD;
            }
            if (lastLoaded >= 0 && loadedNow <= lastLoaded && ++stalls >= STALLS) return STOP;
            lastLoaded = loadedNow;
            return LOAD;
        }
    }

    /** The line under the box: how many matched out of how many loaded, and whether that's all of them. */
    static String statusText(int pages, boolean paging, int loaded, int shown) {
        int known = Math.max(pages, 0);
        if (paging && (known & (MORE | LOADING)) != 0) {
            return L10n.quantity(loaded,
                    "Matches: %1$d of %2$d account loaded so far. Loading the rest of your list.",
                    "Matches: %1$d of %2$d accounts loaded so far. Loading the rest of your list.",
                    shown, loaded);
        }
        if ((known & MORE) != 0) {
            return L10n.quantity(loaded,
                    "Matches: %1$d of the %2$d account that loaded. Instagram didn't send the rest.",
                    "Matches: %1$d of the %2$d accounts that loaded. Instagram didn't send the rest.",
                    shown, loaded);
        }
        return L10n.quantity(loaded, "Matches: %1$d of %2$d blocked account", "Matches: %1$d of %2$d blocked accounts", shown, loaded);
    }

    /** The bridges, as the host. */
    static final class Bridges implements Host {
        @Override
        public void redraw(Object screen) {
            BlockedSearch.redraw(screen);
        }

        @Override
        public void loadMore(Object screen) {
            BlockedSearch.loadMore(screen);
        }

        @Override
        public int pages(Object screen) {
            return BlockedSearch.pages(screen);
        }

        @Override
        public String username(Object account) {
            return BlockedSearch.username(account);
        }

        @Override
        public String fullName(Object account) {
            return BlockedSearch.fullName(account);
        }
    }

    /**
     * Builds the screen's rows again from its loader's factory, state and a copy of the accounts
     * its store holds, and hands them to the screen: the steps the loader takes as it starts a page.
     * The patch writes the body at patch time, and a build without it does nothing.
     */
    public static void redraw(Object screen) {
    }

    /** Starts the screen's loader on the next page. The patch writes the body. */
    public static void loadMore(Object screen) {
    }

    /** The screen's list's more, loading and failed flags as bits, or -1 with no list. The patch writes the body. */
    public static int pages(Object screen) {
        return -1;
    }

    /** A blocked account's user name. The patch writes the body. */
    @Nullable
    public static String username(Object account) {
        return null;
    }

    /** A blocked account's full name. The patch writes the body. */
    @Nullable
    public static String fullName(Object account) {
        return null;
    }

    /** The theme's main text color, or white or black for the night mode in use. */
    static int ink(Context context) {
        TypedArray read = context.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try {
            ColorStateList colors = read.getColorStateList(0);
            if (colors != null) return colors.getDefaultColor();
        } finally {
            read.recycle();
        }
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? Color.WHITE : Color.BLACK;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.download.PictureFetch;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.ui.Dim;

/**
 * Helper for the "See who a story mentions" patch.
 *
 * <p>Instagram's story viewer gives each of its pages the story it shows in one place, its item
 * binder's bindMedia step. The patch calls {@link #bind} right after that, with the page and the
 * story. Once Instagram has bound the page's header, a pill goes after the name and time in it
 * (right under the header when the name has no row of its own, as on Instagram 453, or when the
 * row would cut the name short, and under the name when there's no column to put it in) saying
 * how many accounts the story mentions, from the
 * story's own list of mentions, so a mention sticker
 * that's hidden, shrunk or dragged off screen still counts. A story with none gets no pill.
 * Tapping the pill lists the accounts with their pictures, names and usernames, and tapping one
 * opens that profile through Instagram's own handling of a link to it. The story viewer takes every
 * touch on a story for its own taps and swipes before the header's views see it, so the patch also
 * hands each touch on the screen to {@link #touch} first, which catches a tap on the pill.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not ready or anything
 * thrown, the header is Instagram's own.
 */
public final class StoryMentions {
    /** The id of the header's column with the name in it, which the pill goes at the end of. */
    static final String HEADER = "reel_viewer_text_container";

    /** How far up from the story's media view the header is looked for. */
    private static final int MAX_DEPTH = 8;
    /** How far into 453's Compose name row the name's text starts, measured on the S22. */
    static final int COMPOSE_NAME_INSET_DP = 12;

    /**
     * When a page's header is looked for, in milliseconds after the bind, while Instagram hasn't
     * built it yet. The header starts as a stub Instagram fills in when it first binds it.
     */
    static final long[] TRIES = {0, 250, 750};

    /** The id of the header's name, whose row the pill goes at the end of when it has one. */
    static final String TITLE = "reel_viewer_title";

    /** Set by tests that have no Instagram resources: the header column's id. */
    static int headerIdForTests;
    /** The header column's id in this app, or -1 before the first lookup. */
    private static volatile int headerId = -1;
    /** Set by tests that have no Instagram resources: the name's id. */
    static int titleIdForTests;
    /** The name's id in this app, or -1 before the first lookup. */
    private static volatile int titleId = -1;

    /** Set by tests: fetches a profile picture instead of the network. */
    @Nullable static PictureSource picturesForTests;

    /** The latest bind of each page's media view, so an older bind's late look doesn't undo it. */
    private static final Map<View, Object> LATEST = new WeakHashMap<>();

    /** Profile pictures fetched for the list, so opening it again doesn't fetch them again. */
    private static final LruCache<String, Bitmap> PICTURES = new LruCache<>(48);

    /** Whether any pill was made, so a bind with nothing to show skips the header until one was. */
    private static boolean anyPill;

    /** Every pill made, so a touch can find the one under it. Weak, so a closed story lets its pills go. */
    private static final Set<Pill> PILLS = Collections.newSetFromMap(new WeakHashMap<>());

    /** The activity the last touch went down in, for a pill whose context doesn't lead to one (#125). */
    private static WeakReference<Activity> touched = new WeakReference<>(null);

    /**
     * The pill a finger went down on and hasn't come up from yet, where it went down, and whether it
     * moved since. Weak, so a story closed in the middle of a touch lets its pill and activity go.
     */
    private static WeakReference<Pill> pressed = new WeakReference<>(null);
    private static float downX;
    private static float downY;
    private static boolean moved;

    /** What the diagnostics report counts, so a pill that shows but never opens its list can be told apart. */
    static final String PILL_SHOWN = "pill shown";
    static final String HEADER_NOT_FOUND = "header not found";
    static final String PILL_TAPPED = "pill tapped";
    static final String LIST_SHOWN = "list shown";
    static final String LIST_NO_ACTIVITY = "list not shown, no activity";

    private StoryMentions() {
    }

    /** How long a list left open for a profile still comes back with the story (#403). */
    static final long BACK_TO_LIST_MS = 10 * 60 * 1000L;

    /**
     * The list a tap on an account left for that profile. When the story's pill shows again after
     * the pill went away, the same list opens again, until Close, Back or a different story ends it.
     */
    private static final class BackToList {
        final List<String> usernames = new ArrayList<>();
        final long at = SystemClock.uptimeMillis();
        boolean away;

        BackToList(List<Mention> people) {
            for (Mention person : people) usernames.add(person.username);
        }

        boolean matches(List<Mention> people) {
            if (people.size() != usernames.size()) return false;
            for (int i = 0; i < people.size(); i++) if (!usernames.get(i).equals(people.get(i).username)) return false;
            return true;
        }

        boolean stale() {
            return SystemClock.uptimeMillis() - at > BACK_TO_LIST_MS;
        }
    }

    @Nullable private static BackToList backToList;

    /** [pill] went off screen: if a profile is open over its story, the list waits for it to return. */
    private static void pillLeft(Pill pill) {
        BackToList waiting = backToList;
        if (waiting != null && waiting.matches(pill.people)) waiting.away = true;
    }

    /** [pill] shows: the list opens again if it went away for a profile and the pill left and came back. */
    private static void pillShown(Pill pill) {
        BackToList waiting = backToList;
        if (waiting == null || pill.people.isEmpty()) return;
        if (waiting.stale()) {
            backToList = null;
        } else if (!waiting.matches(pill.people)) {
            if (waiting.away) backToList = null;
        } else if (waiting.away && pill.isShown()) {
            backToList = null;
            pill.post(() -> {
                try {
                    if (pill.isShown()) list(activityOf(pill) != null ? activityOf(pill) : pill.getContext(), pill.people);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.STORY_MENTIONS, "story mentions list", failure);
                }
            });
        }
    }

    /** One account a story mentions, as the list shows it. */
    static final class Mention {
        final String username;
        @Nullable final String fullName;
        @Nullable final String picture;

        Mention(String username, @Nullable String fullName, @Nullable String picture) {
            this.username = username;
            this.fullName = fullName;
            this.picture = picture;
        }
    }

    /** Where the list's profile pictures come from. */
    interface PictureSource {
        @Nullable Bitmap fetch(Context context, String url, int size);
    }

    /** What the hook reads of Instagram's objects: the stubs the patch fills, or a test's stand-ins. */
    interface Reads {
        @Nullable View itemView(Object page);

        @Nullable Object media(Object item);

        @Nullable List<?> mentions(Object media);

        @Nullable Object mentionUser(Object mention);

        @Nullable String username(Object user);

        @Nullable String fullName(Object user);

        /** The address of the account's picture as its profile shows it, or null. */
        @Nullable String picture(Object user);
    }

    /** The stubs below, and InstagramMedia's bridges for an account. */
    static final Reads PATCHED = new Reads() {
        @Override public View itemView(Object page) {
            return StoryMentions.itemView(page);
        }

        @Override public Object media(Object item) {
            return StoryMentions.media(item);
        }

        @Override public List<?> mentions(Object media) {
            return StoryMentions.mentions(media);
        }

        @Override public Object mentionUser(Object mention) {
            return StoryMentions.mentionUser(mention);
        }

        @Override public String username(Object user) {
            return InstagramMedia.username(user);
        }

        @Override public String fullName(Object user) {
            return StoryMentions.fullName(user);
        }

        @Override public String picture(Object user) {
            Object shown = InstagramMedia.profilePicture(user);
            return shown == null ? null : InstagramMedia.candidateUrl(shown);
        }
    };

    /** Set by tests: what to read in place of the patched stubs. */
    @Nullable static Reads readsForTests;

    private static Reads reads() {
        Reads forTests = readsForTests;
        return forTests != null ? forTests : PATCHED;
    }

    /** The pill under the name, a view of HushGram's own so a later bind finds it again. */
    static final class Pill extends TextView {
        List<Mention> people = Collections.emptyList();
        /** The header's column while the pill sits in the name's row, so it can go under the header. */
        @Nullable LinearLayout header;
        private boolean moving;

        Pill(Context context) {
            super(context);
        }

        @Override
        public void onVisibilityAggregated(boolean visible) {
            super.onVisibilityAggregated(visible);
            settle(visible);
        }

        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            settle(visibility == VISIBLE && isShown());
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            settle(isShown());
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            settle(false);
        }

        private void settle(boolean visible) {
            try {
                if (visible) pillShown(this);
                else pillLeft(this);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_MENTIONS, "story mentions list", failure);
            }
        }

        /**
         * In the name's row, the pill takes its width from the name: Instagram's row shrinks the
         * name to fit, so a long name or a date like "January 16" left only the verified badge
         * showing. Once a layout shows the name cut short, the pill goes under the header instead.
         */
        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            try {
                LinearLayout column = header;
                ViewParent row = getParent();
                if (column == null || moving || !(row instanceof LinearLayout) || !crowdsName((LinearLayout) row)) return;
                moving = true;
                post(() -> {
                    moving = false;
                    moveUnder(this, column);
                });
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_MENTIONS, "story header", failure);
            }
        }
    }

    /**
     * Injected right after Instagram's story viewer gives [page], one of its pages, the story [item].
     * Puts the pill in the page's header once Instagram has bound it, or takes it away. Never
     * throws.
     */
    public static void bind(Object page, Object item) {
        try {
            HookStatus.invoked(FamilyNames.STORY_MENTIONS);
            Reads reads = reads();
            View view = reads.itemView(page);
            if (view == null) return;
            List<Mention> people = on() ? people(reads, reads.media(item)) : Collections.<Mention>emptyList();
            if (people.isEmpty() && !anyPill) {
                LATEST.remove(view);
                return;
            }
            Object token = new Object();
            LATEST.put(view, token);
            // Instagram goes on to bind the header after this, and may only build it then.
            place(view, people, token, 0);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_MENTIONS, "story header", failure);
        }
    }

    /** Looks for the header [TRIES][attempt] after the bind, and again at the next, until it's there or they run out. */
    private static void place(View view, List<Mention> people, Object token, int attempt) {
        Runnable look = () -> {
            if (LATEST.get(view) != token) return;
            if (show(view, people)) return;
            if (attempt + 1 >= TRIES.length) {
                HookStatus.counted(FamilyNames.STORY_MENTIONS, HEADER_NOT_FOUND);
                return;
            }
            place(view, people, token, attempt + 1);
        };
        if (attempt == 0) view.post(look);
        else view.postDelayed(look, TRIES[attempt] - TRIES[attempt - 1]);
    }

    /** The accounts [media] mentions, each once, in the story's order. Empty when it has none. */
    static List<Mention> people(Reads reads, @Nullable Object media) {
        if (media == null) return Collections.emptyList();
        List<?> mentions = reads.mentions(media);
        if (mentions == null || mentions.isEmpty()) return Collections.emptyList();
        List<Mention> people = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object mention : mentions) {
            Object user = mention == null ? null : reads.mentionUser(mention);
            String username = user == null ? null : reads.username(user);
            if (username == null || username.trim().isEmpty() || !seen.add(username)) continue;
            people.add(new Mention(username, blankToNull(reads.fullName(user)), reads.picture(user)));
        }
        return people;
    }

    /**
     * Puts [people]'s pill in the header of the page [itemView] is in, or hides it for none. True
     * once that's settled: the pill is placed or hidden, or there's nothing to show. False while
     * the header isn't there yet.
     */
    static boolean show(View itemView, List<Mention> people) {
        try {
            LinearLayout header = header(itemView);
            if (header == null) return people.isEmpty();
            Pill pill = pillIn(header);
            if (people.isEmpty()) {
                if (pill != null) pill.setVisibility(View.GONE);
                return true;
            }
            Context context = header.getContext();
            if (pill == null) {
                LinearLayout row = titleRow(header);
                LinearLayout under = row == null ? below(header) : null;
                LinearLayout into = row != null ? row : under != null ? under : header;
                pill = newPill(context, into.getOrientation() == LinearLayout.HORIZONTAL);
                if (under != null) {
                    putUnder(pill, header, under);
                } else {
                    into.addView(pill);
                    if (row != null) pill.header = header;
                }
                anyPill = true;
            }
            String text = label(context, people.size());
            pill.people = people;
            pill.setText(text);
            pill.setContentDescription(L10n.f(context, "%1$s, see who this story mentions", text));
            pill.setVisibility(View.VISIBLE);
            pillShown(pill);
            HookStatus.counted(FamilyNames.STORY_MENTIONS, PILL_SHOWN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_MENTIONS, "story header", failure);
            return true;
        }
    }

    /** "1 mention" or "3 mentions", in the phone's language. */
    static String label(Context context, int count) {
        return L10n.quantity(context, count, "1 mention", "%1$d mentions");
    }

    /**
     * The header's column with the name in it: going up from [itemView], the first view holding it.
     * Null when there's none yet, or it isn't a layout a pill can go at the end of.
     */
    @Nullable
    static LinearLayout header(View itemView) {
        int id = headerId(itemView.getContext());
        if (id == 0) return null;
        View at = itemView;
        for (int depth = 0; depth < MAX_DEPTH && at != null; depth++) {
            View found = at.findViewById(id);
            if (found != null) return found instanceof LinearLayout ? (LinearLayout) found : null;
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** The pill already in [header]: in the name's row, under the whole header, or in the column itself. Null for none. */
    @Nullable
    static Pill pillIn(ViewGroup header) {
        LinearLayout row = titleRow(header);
        if (row != null) {
            Pill inRow = pillAmong(row);
            if (inRow != null) return inRow;
        }
        LinearLayout under = below(header);
        if (under != null) {
            Pill underIt = pillAmong(under);
            if (underIt != null) return underIt;
        }
        return pillAmong(header);
    }

    /**
     * The column the whole header's row sits in, so the pill goes right under the header, or null
     * when there's no such column. On Instagram 453 the name's row is a Compose view with no name
     * view inside it, so there's no row to put the pill at the end of, and under the name it made
     * the name's column taller. The header centers that column, so on every story with mentions
     * the name moved up under the progress bars, and a story with a music line lost the top of its
     * name (#405). Under the header, the pill changes nothing above it.
     */
    @Nullable
    static LinearLayout below(ViewGroup header) {
        ViewParent headerRow = header.getParent();
        ViewParent column = headerRow instanceof View ? headerRow.getParent() : null;
        if (!(column instanceof LinearLayout)) return null;
        LinearLayout found = (LinearLayout) column;
        return found.getOrientation() == LinearLayout.VERTICAL ? found : null;
    }

    /** Puts [pill] right under the header's row in [under], lined up with the name's text. */
    private static void putUnder(Pill pill, LinearLayout header, LinearLayout under) {
        View headerRow = (View) header.getParent();
        LinearLayout.LayoutParams place = placement(false);
        place.setMarginStart(headerRow.getLeft() + header.getLeft() + nameStart(header));
        pill.setLayoutParams(place);
        under.addView(pill, under.indexOfChild(headerRow) + 1);
    }

    /**
     * Where the name's text starts inside [header]: the name view's left, summed up to the header,
     * or with no name view (453's Compose row) the room that row keeps before its text. Lined up
     * with the header's edge instead, the pill sat a name's inset left of the name on the S22 (#405).
     */
    static int nameStart(ViewGroup header) {
        int id = titleId(header.getContext());
        View title = id == 0 ? null : header.findViewById(id);
        if (title == null) return Dim.dp(COMPOSE_NAME_INSET_DP);
        int start = 0;
        for (View at = title; at != null && at != header; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            start += at.getLeft();
        }
        return start;
    }

    /**
     * Moves [pill] from the name's row to under the header of [header]'s column, where it leaves the
     * name its whole width. With no column to go under, it stays in the row and stops checking.
     */
    static void moveUnder(Pill pill, LinearLayout header) {
        try {
            pill.header = null;
            LinearLayout under = below(header);
            ViewParent row = pill.getParent();
            if (under == null || !(row instanceof ViewGroup) || row == under) return;
            ((ViewGroup) row).removeView(pill);
            putUnder(pill, header, under);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_MENTIONS, "story header", failure);
        }
    }

    /** Whether the name in [row] is narrower than its text and badge need, so it shows cut short. */
    static boolean crowdsName(LinearLayout row) {
        int id = titleId(row.getContext());
        View found = id == 0 ? null : row.findViewById(id);
        if (!(found instanceof TextView) || found.getVisibility() != View.VISIBLE) return false;
        TextView title = (TextView) found;
        CharSequence text = title.getText();
        if (text == null || text.length() == 0) return false;
        float wanted = Layout.getDesiredWidth(text, title.getPaint())
                + title.getCompoundPaddingLeft() + title.getCompoundPaddingRight();
        return title.getWidth() + 1 < wanted;
    }

    @Nullable
    private static Pill pillAmong(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof Pill) return (Pill) group.getChildAt(i);
        }
        return null;
    }

    /**
     * The row the name sits in, when it's a row of its own inside the header's column, so the pill
     * goes at the end of it, after the name and the time. Under the name, the pill made the column
     * taller, and on each story with mentions the name moved up toward the progress bars (#151).
     * When the row can't hold the pill without cutting the name short, {@link Pill} moves it under
     * the header. Null when the name sits in the column itself or isn't there, and the pill goes under it.
     */
    @Nullable
    static LinearLayout titleRow(ViewGroup header) {
        int id = titleId(header.getContext());
        if (id == 0) return null;
        View title = header.findViewById(id);
        ViewParent parent = title == null ? null : title.getParent();
        if (!(parent instanceof LinearLayout) || parent == header) return null;
        LinearLayout row = (LinearLayout) parent;
        return row.getOrientation() == LinearLayout.HORIZONTAL ? row : null;
    }

    /** The header's id, looked up once: a name lookup on every story frame adds up. */
    private static int headerId(Context context) {
        if (headerIdForTests != 0) return headerIdForTests;
        if (headerId == -1) headerId = context.getResources().getIdentifier(HEADER, "id", context.getPackageName());
        return headerId;
    }

    /** The name's id, looked up once like the header's. */
    private static int titleId(Context context) {
        if (titleIdForTests != 0) return titleIdForTests;
        if (titleId == -1) titleId = context.getResources().getIdentifier(TITLE, "id", context.getPackageName());
        return titleId;
    }

    /** Where the pill sits: after the time in a row, or a little under what's above it in a column. */
    private static LinearLayout.LayoutParams placement(boolean row) {
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row) {
            place.setMarginStart(Dim.dp(6));
            place.gravity = Gravity.CENTER_VERTICAL;
        } else {
            place.topMargin = Dim.dp(4);
            place.gravity = Gravity.START;
        }
        return place;
    }

    /**
     * A small rounded pill in white on a dim backing, like the rest of a story's header: under the
     * name in a column, or after it in a row.
     */
    private static Pill newPill(Context context, boolean row) {
        Pill pill = new Pill(context);
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setSingleLine(true);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(Dim.dp(10), Dim.dp(3), Dim.dp(10), Dim.dp(3));
        GradientDrawable backing = new GradientDrawable();
        backing.setColor(0x59000000);
        backing.setCornerRadius(Dim.dp(12));
        pill.setBackground(backing);
        pill.setLayoutParams(placement(row));
        PILLS.add(pill);
        pill.setOnClickListener(view -> {
            try {
                Activity activity = activityOf(view);
                list(activity != null ? activity : view.getContext(), ((Pill) view).people);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_MENTIONS, "story mentions list", failure);
            }
        });
        return pill;
    }

    /** The accounts in a list centered over the story. A tap on one opens their profile. */
    @Nullable
    static AlertDialog list(Context context, List<Mention> people) {
        Activity activity = activity(context);
        if (activity == null) {
            HookStatus.counted(FamilyNames.STORY_MENTIONS, LIST_NO_ACTIVITY);
            return null;
        }
        if (people.isEmpty()) return null;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(L10n.t(activity, "Mentioned in this story"))
                .setNegativeButton(L10n.t(activity, "Close"), null);
        Context themed = builder.getContext();
        AlertDialog dialog = builder.create();
        boolean[] leaving = {false};
        LinearLayout rows = new LinearLayout(themed);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, Dim.dp(8), 0, Dim.dp(8));
        for (Mention person : people) {
            rows.addView(row(themed, person, () -> {
                leaving[0] = true;
                backToList = new BackToList(people);
                dialog.dismiss();
                openProfile(activity, person.username);
            }));
        }
        dialog.setOnDismissListener(shown -> {
            if (!leaving[0]) backToList = null;
        });
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(rows);
        dialog.setView(scroll);
        dialog.show();
        ScreenColors.dialog(dialog);
        HookStatus.counted(FamilyNames.STORY_MENTIONS, LIST_SHOWN);
        return dialog;
    }

    /** A row with [person]'s picture, then their full name over their @username. */
    private static View row(Context themed, Mention person, Runnable open) {
        LinearLayout row = new LinearLayout(themed);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dim.dp(64));
        row.setPadding(Dim.dp(16), Dim.dp(10), Dim.dp(16), Dim.dp(10));
        ScreenColors colors = ScreenColors.DEFAULT;
        int secondary = colors.summary;
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(colors.card);
        surface.setCornerRadius(Dim.dp(12));
        surface.setStroke(Dim.dp(1), colors.outline);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(colors.heading)), surface, null));
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        place.setMargins(Dim.dp(16), Dim.dp(4), Dim.dp(16), Dim.dp(4));
        row.setLayoutParams(place);
        row.setFocusable(true);

        ImageView picture = new ImageView(themed);
        int size = Dim.dp(40);
        LinearLayout.LayoutParams pictureSize = new LinearLayout.LayoutParams(size, size);
        pictureSize.setMarginEnd(Dim.dp(16));
        picture.setLayoutParams(pictureSize);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable blank = new GradientDrawable();
        blank.setShape(GradientDrawable.OVAL);
        blank.setColor((secondary & 0x00FFFFFF) | 0x33000000);
        picture.setImageDrawable(blank);
        picture.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        picture.setClipToOutline(true);
        picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(picture);
        if (person.picture != null) load(picture, person.picture, size);

        LinearLayout names = new LinearLayout(themed);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String handle = "@" + person.username;
        if (person.fullName != null) {
            TextView name = new TextView(themed);
            name.setText(person.fullName);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            name.setTextColor(colors.title);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            names.addView(name);
        }
        TextView username = new TextView(themed);
        username.setText(handle);
        username.setTextSize(TypedValue.COMPLEX_UNIT_SP, person.fullName != null ? 14 : 16);
        username.setTextColor(person.fullName != null ? secondary : colors.title);
        username.setSingleLine(true);
        username.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(username);
        row.addView(names);

        row.setContentDescription(person.fullName != null ? person.fullName + ", " + handle : handle);
        row.setOnClickListener(view -> {
            try {
                open.run();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_MENTIONS, "story mentions profile", failure);
            }
        });
        return row;
    }

    /**
     * Shows the picture at [url] in [view], fetched off the main thread the way a download is
     * fetched, from Meta's media servers alone. Anything else keeps the blank circle.
     */
    private static void load(ImageView view, String url, int size) {
        Bitmap cached = PICTURES.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        Context context = view.getContext().getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            PictureSource forTests = picturesForTests;
            Bitmap bitmap = forTests != null ? forTests.fetch(context, url, size) : PictureFetch.fetch(context, url, size);
            if (bitmap == null) return;
            PICTURES.put(url, bitmap);
            Utils.runOnMainThread(() -> view.setImageBitmap(bitmap));
        });
    }

    /**
     * Injected first thing in IgFragmentActivity.dispatchTouchEvent, which every touch on an
     * Instagram screen passes through before any of its views. A finger going down on a pill that's
     * showing, and coming up again without moving further than the touch slop, taps the pill.
     * Answers true for each event of a touch that went down on a pill, which Instagram then never
     * sees, so the story neither pauses nor skips. Answers false for every other touch, which
     * Instagram gets as it was. Never throws.
     */
    public static boolean touch(Activity activity, MotionEvent event) {
        try {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (activity != null && touched.get() != activity) touched = new WeakReference<>(activity);
                Pill down = PILLS.isEmpty() ? null : pillAt(activity, event.getX(), event.getY());
                pressed = new WeakReference<>(down);
                if (down == null) return false;
                downX = event.getX();
                downY = event.getY();
                moved = false;
                return true;
            }
            Pill pill = pressed.get();
            if (pill == null) return false;
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    if (beyondSlop(pill, event.getX(), event.getY())) moved = true;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // A second finger makes a pinch, not a tap.
                    moved = true;
                    break;
                case MotionEvent.ACTION_UP:
                    pressed = new WeakReference<>(null);
                    if (!moved && !beyondSlop(pill, event.getX(), event.getY()) && pill.isShown()) {
                        HookStatus.counted(FamilyNames.STORY_MENTIONS, PILL_TAPPED);
                        pill.performClick();
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    pressed = new WeakReference<>(null);
                    break;
                default:
                    break;
            }
            return true;
        } catch (Throwable failure) {
            pressed = new WeakReference<>(null);
            HookStatus.threw(FamilyNames.STORY_MENTIONS, "story mentions tap", failure);
            return false;
        }
    }

    /**
     * The pill showing in [activity]'s window whose box holds the point [x], [y] in that window, or
     * null. It goes by the window, not the pill's context: the story viewer's views on 450 carry a
     * context that doesn't lead back to the activity (#125).
     */
    @Nullable
    static Pill pillAt(Activity activity, float x, float y) {
        Window window = activity == null ? null : activity.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        if (decor == null) return null;
        for (Pill pill : PILLS) {
            if (!pill.isShown() || pill.getWidth() == 0 || pill.getRootView() != decor) continue;
            if (holds(pill, x, y, 0, 0)) return pill;
            // The pill is drawn about 22dp tall. A finger gets room around it, up to the 48dp
            // Android asks of a target, but only room: never past the row or column the pill is
            // in, and never on the name or anything else next to it, which are Instagram's taps.
            int padX = Math.max(0, Dim.dp(MIN_TARGET_DP) - pill.getWidth()) / 2;
            int padY = Math.max(0, Dim.dp(MIN_TARGET_DP) - pill.getHeight()) / 2;
            if (!holds(pill, x, y, padX, padY) || !(pill.getParent() instanceof ViewGroup)) continue;
            ViewGroup parent = (ViewGroup) pill.getParent();
            if (holds(parent, x, y, 0, 0) && !onAnother(parent, pill, x, y)) return pill;
        }
        return null;
    }

    /** Whether [view]'s box in its window, grown by [padX] and [padY] on each side, holds [x], [y]. */
    private static boolean holds(View view, float x, float y, int padX, int padY) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return x >= at[0] - padX && x < at[0] + view.getWidth() + padX
                && y >= at[1] - padY && y < at[1] + view.getHeight() + padY;
    }

    /** Whether [x], [y] is on a view showing in [parent] other than [pill]. */
    private static boolean onAnother(ViewGroup parent, View pill, float x, float y) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child != pill && child.getVisibility() == View.VISIBLE && holds(child, x, y, 0, 0)) return true;
        }
        return false;
    }

    /** The smallest box a tap on the pill is taken from, in dp. */
    static final int MIN_TARGET_DP = 48;

    private static boolean beyondSlop(Pill pill, float x, float y) {
        int slop = ViewConfiguration.get(pill.getContext()).getScaledTouchSlop();
        float dx = x - downX;
        float dy = y - downY;
        return dx * dx + dy * dy > (float) slop * slop;
    }

    /** Opens [username]'s profile the way a link to it opens inside Instagram. */
    static void openProfile(Activity activity, String username) {
        Intent open = new Intent(Intent.ACTION_VIEW, profileLink(username));
        open.setPackage(activity.getPackageName());
        activity.startActivity(open);
    }

    static Uri profileLink(String username) {
        return Uri.parse("https://www.instagram.com/" + Uri.encode(username) + "/");
    }

    /**
     * The activity [view] shows in: the one its context leads to, or else the one the last touch
     * went through when [view] is in that activity's window. The story viewer's views on 450 carry
     * a context that doesn't lead back to the activity, so a pill's own click needs the second (#125).
     */
    @Nullable
    static Activity activityOf(View view) {
        Activity own = activity(view.getContext());
        if (own != null) return own;
        Activity last = touched.get();
        Window window = last == null ? null : last.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        return decor != null && view.getRootView() == decor ? last : null;
    }

    @Nullable
    private static Activity activity(Context context) {
        Context at = context;
        while (at instanceof ContextWrapper) {
            if (at instanceof Activity) return (Activity) at;
            at = ((ContextWrapper) at).getBaseContext();
        }
        return null;
    }

    @Nullable
    private static String blankToNull(@Nullable String text) {
        return text == null || text.trim().isEmpty() ? null : text;
    }

    /** Whether the pill shows: the settings are read and the switch is on, which a pause answers off. */
    private static boolean on() {
        return Utils.settingsReady() && Settings.SHOW_STORY_MENTIONS.get();
    }

    /** Forgets every page and picture, for a test that starts over. */
    static void resetForTests() {
        LATEST.clear();
        PICTURES.evictAll();
        PILLS.clear();
        pressed = new WeakReference<>(null);
        touched = new WeakReference<>(null);
        anyPill = false;
        backToList = null;
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the view a story viewer's [page] shows its story's media in, or null. */
    @Nullable
    public static View itemView(Object page) {
        return null;
    }

    /** Filled in by the patch: the Media of the story [item], or null. */
    @Nullable
    public static Object media(Object item) {
        return null;
    }

    /** Filled in by the patch: the mentions [media] holds, or null when it has none. */
    @Nullable
    public static List<?> mentions(Object media) {
        return null;
    }

    /** Filled in by the patch: the account [mention] is of, or null. */
    @Nullable
    public static Object mentionUser(Object mention) {
        return null;
    }

    /** Filled in by the patch: [user]'s full name, or null. */
    @Nullable
    public static String fullName(Object user) {
        return null;
    }
}

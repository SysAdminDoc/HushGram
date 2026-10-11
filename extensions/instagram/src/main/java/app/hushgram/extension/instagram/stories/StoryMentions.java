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
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
 * story. Once Instagram has bound the page's header, a pill goes under the name in it saying how
 * many accounts the story mentions, from the story's own list of mentions, so a mention sticker
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

    /**
     * When a page's header is looked for, in milliseconds after the bind, while Instagram hasn't
     * built it yet. The header starts as a stub Instagram fills in when it first binds it.
     */
    static final long[] TRIES = {0, 250, 750};

    /** Set by tests that have no Instagram resources: the header column's id. */
    static int headerIdForTests;

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

        Pill(Context context) {
            super(context);
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
                pill = newPill(context, header.getOrientation() == LinearLayout.HORIZONTAL);
                header.addView(pill);
                anyPill = true;
            }
            String text = label(context, people.size());
            pill.people = people;
            pill.setText(text);
            pill.setContentDescription(L10n.f(context, "%1$s, see who this story mentions", text));
            pill.setVisibility(View.VISIBLE);
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

    @Nullable
    static Pill pillIn(ViewGroup header) {
        for (int i = 0; i < header.getChildCount(); i++) {
            if (header.getChildAt(i) instanceof Pill) return (Pill) header.getChildAt(i);
        }
        return null;
    }

    private static int headerId(Context context) {
        if (headerIdForTests != 0) return headerIdForTests;
        return context.getResources().getIdentifier(HEADER, "id", context.getPackageName());
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
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row) {
            place.setMarginStart(Dim.dp(6));
            place.gravity = Gravity.CENTER_VERTICAL;
        } else {
            place.topMargin = Dim.dp(4);
            place.gravity = Gravity.START;
        }
        pill.setLayoutParams(place);
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
        LinearLayout rows = new LinearLayout(themed);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, Dim.dp(8), 0, Dim.dp(8));
        for (Mention person : people) {
            rows.addView(row(themed, person, () -> {
                dialog.dismiss();
                openProfile(activity, person.username);
            }));
        }
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(rows);
        dialog.setView(scroll);
        dialog.show();
        HookStatus.counted(FamilyNames.STORY_MENTIONS, LIST_SHOWN);
        return dialog;
    }

    /** A row with [person]'s picture, then their full name over their @username. */
    private static View row(Context themed, Mention person, Runnable open) {
        LinearLayout row = new LinearLayout(themed);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dim.dp(56));
        row.setPadding(Dim.dp(24), Dim.dp(6), Dim.dp(24), Dim.dp(6));
        int secondary;
        TypedArray theme = themed.obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground, android.R.attr.textColorSecondary});
        try {
            Drawable touch = theme.getDrawable(0);
            if (touch != null) row.setBackground(touch);
            secondary = theme.getColor(1, Color.GRAY);
        } finally {
            theme.recycle();
        }

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
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            names.addView(name);
        }
        TextView username = new TextView(themed);
        username.setText(handle);
        username.setTextSize(TypedValue.COMPLEX_UNIT_SP, person.fullName != null ? 14 : 16);
        if (person.fullName != null) username.setTextColor(secondary);
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
        int[] at = new int[2];
        for (Pill pill : PILLS) {
            if (!pill.isShown() || pill.getWidth() == 0 || pill.getRootView() != decor) continue;
            pill.getLocationInWindow(at);
            if (x >= at[0] && x < at[0] + pill.getWidth() && y >= at[1] && y < at[1] + pill.getHeight()) return pill;
        }
        return null;
    }

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

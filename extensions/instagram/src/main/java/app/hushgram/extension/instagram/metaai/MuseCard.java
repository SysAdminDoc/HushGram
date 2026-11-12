/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.metaai;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.misc.ProfileSaved;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The Muse card's second net, for Hide the Muse card.
 *
 * <p>{@link MetaAi#dropsPromotion} leaves the Meet Muse card out where Instagram turns a quick promotion
 * into a card, but the card still shows now and then: one Instagram kept from an earlier start, or one
 * the server sends as a Bloks card that never passes there. So while the switch is on, the screen is
 * looked at after its layouts, at most every {@link #LOOK_EVERY_MS}, for a Bloks card ({@link #BLOKS})
 * whose words name Muse. On Home it's a row of the feed list, folded to no height so the posts close
 * up under it. Anywhere else, your profile's header included, the card itself is taken off.
 *
 * <p>A folded view is put back as it was when it's reused for something that doesn't name Muse, and
 * every one of them when the switch goes off or HushGram is paused. Nothing is tapped, so Instagram
 * never hears the card was dismissed.
 */
public final class MuseCard {
    static final String BLOKS = "bloks_container";

    /** How often a screen is looked at, at most. */
    static final long LOOK_EVERY_MS = 600;

    /** How many accessibility nodes of one card are read at most. */
    private static final int NODE_LIMIT = 64;

    private static boolean registered;
    private static final WeakHashMap<Activity, Boolean> watched = new WeakHashMap<>();

    /** Each view folded away, and how it was before. */
    static final WeakHashMap<View, Folded> folded = new WeakHashMap<>();

    private MuseCard() {
    }

    /** Called as the application starts. Does nothing in a build that doesn't carry Hide Meta AI. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.metaAi()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "Muse card watch", failure);
        }
    }

    static void watch(Activity activity) {
        try {
            synchronized (watched) {
                if (watched.containsKey(activity)) return;
                watched.put(activity, Boolean.TRUE);
            }
            View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                private long last;
                private boolean pending;

                @Override public void onGlobalLayout() {
                    // Off with nothing folded, there's nothing to do and nothing to put back.
                    if (!on() && folded.isEmpty()) return;
                    long now = SystemClock.uptimeMillis();
                    if (now - last < LOOK_EVERY_MS) {
                        // The last layout of a burst is the one that matters, so look once more after it.
                        if (!pending) {
                            pending = true;
                            decor.postDelayed(() -> {
                                pending = false;
                                last = SystemClock.uptimeMillis();
                                look(decor);
                            }, LOOK_EVERY_MS);
                        }
                        return;
                    }
                    last = now;
                    look(decor);
                }
            });
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "Muse card watch", failure);
        }
    }

    static boolean on() {
        return Utils.settingsReady() && Settings.HIDE_META_AI_PROFILE_BANNER.get();
    }

    /** Folds every Muse card under [root] away and puts back anything folded that isn't one any more. */
    static void look(View root) {
        try {
            boolean on = on();
            List<View> cards = new ArrayList<>();
            int bloks = on ? bloksId(root.getContext()) : 0;
            if (bloks != 0) collect(root, bloks, cards);
            List<View> keep = new ArrayList<>();
            for (View card : cards) {
                List<String> words = words(card);
                if (!MetaAi.mentionsMuse(words.toArray(new String[0]))) continue;
                View target = target(card);
                keep.add(target);
                if (!folded.containsKey(target)) {
                    fold(target);
                    HookStatus.invoked(FamilyNames.META_AI);
                    Logger.printDebug(() -> "Meta AI: folded a Muse card away: " + quoted(words));
                }
            }
            for (Map.Entry<View, Folded> entry : new ArrayList<>(folded.entrySet())) {
                View view = entry.getKey();
                if (view == null || keep.contains(view) || view.getRootView() != root.getRootView()) continue;
                entry.getValue().restore(view);
                folded.remove(view);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "Muse card", failure);
        }
    }

    /** The Bloks card's view id, looked up once. */
    static volatile int bloksId;

    private static int bloksId(Context context) {
        if (bloksId == 0) {
            bloksId = context.getResources().getIdentifier(BLOKS, "id", context.getPackageName());
        }
        return bloksId;
    }

    /** Every view under [view] with the id [id], outermost first, without looking inside one already found. */
    static void collect(View view, int id, List<View> out) {
        if (view.getId() == id) {
            out.add(view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0, n = group.getChildCount(); i < n; i++) collect(group.getChildAt(i), id, out);
    }

    /**
     * What gets folded for a card: the row of a list it sits in (its ancestor right under a view with
     * Android's list id, the id Home's feed has), or the card itself when it isn't in a list's row.
     */
    static View target(View card) {
        View child = card;
        for (ViewParent parent = card.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (((View) parent).getId() == android.R.id.list) return child;
            child = (View) parent;
        }
        return card;
    }

    static void fold(View view) {
        Folded was = new Folded(view.getVisibility(), view.getLayoutParams());
        folded.put(view, was);
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && view.getParent() instanceof View && ((View) view.getParent()).getId() == android.R.id.list) {
            // A list lays its rows out whether or not they're gone, so the row also takes no height.
            params.height = 0;
            view.setLayoutParams(params);
        }
    }

    /**
     * Every word a card shows or says: the text of its text views, the description of every view, and
     * the text and description of the accessibility nodes a view draws itself, which is how Bloks and
     * Compose describe their pieces.
     */
    static List<String> words(View card) {
        List<String> out = new ArrayList<>();
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(card);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            add(out, view.getContentDescription());
            if (view instanceof TextView) add(out, ((TextView) view).getText());
            nodeWords(view, out);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0, n = group.getChildCount(); i < n; i++) queue.add(group.getChildAt(i));
            }
        }
        return out;
    }

    private static void nodeWords(View view, List<String> out) {
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider == null) return;
        Method childId = ProfileSaved.childId();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeProvider.HOST_VIEW_ID);
        int visited = 0;
        try {
            while (!queue.isEmpty() && visited++ < NODE_LIMIT) {
                AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(queue.poll());
                if (node == null) continue;
                add(out, node.getText());
                add(out, node.getContentDescription());
                if (childId == null) continue;
                for (int i = 0; i < node.getChildCount(); i++) {
                    long child = (Long) childId.invoke(node, i);
                    queue.add((int) (child >>> 32));
                }
            }
        } catch (Throwable unreadable) {
            // What was read so far still counts.
        }
    }

    private static void add(List<String> out, @Nullable CharSequence text) {
        if (text != null && text.length() > 0) out.add(text.toString());
    }

    private static String quoted(List<String> words) {
        String joined = String.join(" | ", words);
        return joined.length() > 160 ? joined.substring(0, 160) + "..." : joined;
    }

    /** How a folded view was before: its visibility and, for a list's row, its height. */
    static final class Folded {
        final int visibility;
        final int height;

        Folded(int visibility, @Nullable ViewGroup.LayoutParams params) {
            this.visibility = visibility;
            this.height = params == null ? Integer.MIN_VALUE : params.height;
        }

        void restore(View view) {
            view.setVisibility(visibility);
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null && height != Integer.MIN_VALUE && params.height != height) {
                params.height = height;
                view.setLayoutParams(params);
            }
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            watch(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.FollowersScreen;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The follower history's button on your own profile: a pill under your posts, followers and
 * following counts, drawn the way Show it as a chip draws its pill on other people's profiles
 * ({@link FriendshipChip}), that opens the history. It says how many people left since you last
 * opened it, in red, or just Follower history.
 *
 * <p>Like that chip it isn't a view among Instagram's: the counts block gets room for it as padding
 * at its bottom and the pill is drawn in the block's overlay. A press in that room is the button's,
 * since nothing of Instagram's is there to take it. The history is read off the main thread and kept
 * here, so binding the header never waits on the file.
 */
public final class FollowersChip {
    static final String SHOW = "followers button";

    /** The buttons showing, by the counts block they're under. */
    private static final Map<View, Button> SHOWN = new WeakHashMap<>();

    /** The history last read, or null before the first read. */
    private static volatile FollowerHistory.Log cached;
    private static volatile boolean reading;

    private FollowersChip() {
    }

    /** Whether the button is wanted: the tracker and its button switch on. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.TRACK_UNFOLLOWERS.get() && Settings.FOLLOWERS_PROFILE_BUTTON.get();
    }

    /**
     * Called as Instagram binds your own profile's header, with a view inside it and the pronouns
     * line for the typeface when there is one. Shows the button, or takes it away when it's off.
     */
    static void ownProfile(View inside, @Nullable TextView style) {
        try {
            View block = FriendshipChip.counts(inside);
            if (block == null) return;
            if (!switchedOn()) {
                Button button = SHOWN.remove(block);
                if (button != null) button.remove();
                return;
            }
            Button button = SHOWN.get(block);
            if (button == null) {
                button = new Button(block);
                SHOWN.put(block, button);
            }
            button.style = style != null ? new WeakReference<>(style) : null;
            FollowerHistory.Log log = cached;
            if (log == null) reload(inside.getContext());
            button.show(log);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, SHOW, failure);
        }
    }

    /** Reads the history again off the main thread, then redraws every button showing. */
    public static void reload(Context context) {
        if (context == null || reading) return;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        reading = true;
        boolean queued = Utils.runOnBackgroundThread(() -> {
            FollowerHistory.Log log = FollowerHistory.shown(application);
            cached = log;
            reading = false;
            Utils.runOnMainThread(() -> {
                List<Button> buttons = new ArrayList<>(SHOWN.values());
                for (Button button : buttons) button.show(log);
            });
        });
        if (!queued) reading = false;
    }

    /** The button's words for [log]: who left since it was opened, or its name. */
    static String text(@Nullable FollowerHistory.Log log) {
        int unseen = log == null ? 0 : log.unseenLeft();
        if (unseen > 0) return L10n.quantity(unseen, "1 unfollowed you", "%1$d unfollowed you", unseen);
        return L10n.t("Follower history");
    }

    /** One counts block's button: the room it took and the pill in its overlay. */
    static final class Button implements View.OnLayoutChangeListener, View.OnTouchListener {
        final WeakReference<View> block;
        final FriendshipChip.Pill pill;
        final int room;
        int ownBottom;
        @Nullable WeakReference<TextView> style;
        private boolean pressed;

        @SuppressLint("ClickableViewAccessibility") // The pill is drawn, not a view; a press in its room is its click.
        Button(View block) {
            this.block = new WeakReference<>(block);
            pill = new FriendshipChip.Pill(block.getContext());
            room = pill.getIntrinsicHeight() + pill.dp(8);
            ownBottom = block.getPaddingBottom();
            block.getOverlay().add(pill);
            block.addOnLayoutChangeListener(this);
            block.setOnTouchListener(this);
        }

        void show(@Nullable FollowerHistory.Log log) {
            View block = this.block.get();
            if (block == null) return;
            Context context = block.getContext();
            TextView style = this.style == null ? null : this.style.get();
            boolean light = FriendshipChip.light(context, style);
            boolean news = log != null && log.unseenLeft() > 0;
            int color = news ? FriendshipChip.color(FriendshipStatus.Relation.DOESNT_FOLLOW_YOU, light)
                    : light ? Color.rgb(0x26, 0x26, 0x26) : Color.rgb(0xE0, 0xE0, 0xE0);
            Drawable icon = icon(context, news ? FriendshipChip.NOT_FOLLOWING_ICON : FriendshipChip.FOLLOWING_ICON);
            pill.set(text(log), icon, color, style != null ? style.getTypeface() : null);
            makeRoom(block);
            place(block);
        }

        private void makeRoom(View block) {
            int bottom = block.getPaddingBottom();
            if (bottom != ownBottom + room) {
                ownBottom = bottom;
                block.setPaddingRelative(block.getPaddingStart(), block.getPaddingTop(), block.getPaddingEnd(), ownBottom + room);
            }
        }

        private void place(View block) {
            pill.setLayoutDirection(block.getLayoutDirection());
            int width = pill.getIntrinsicWidth();
            int height = pill.getIntrinsicHeight();
            boolean rtl = block.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int left = rtl ? block.getWidth() - block.getPaddingEnd() - width : block.getPaddingStart();
            int bottom = block.getHeight() - ownBottom;
            pill.setBounds(left, bottom - height, left + width, bottom);
        }

        void remove() {
            View block = this.block.get();
            if (block == null) return;
            block.getOverlay().remove(pill);
            block.removeOnLayoutChangeListener(this);
            block.setOnTouchListener(null);
            if (block.getPaddingBottom() == ownBottom + room) {
                block.setPaddingRelative(block.getPaddingStart(), block.getPaddingTop(), block.getPaddingEnd(), ownBottom);
            }
        }

        @Override
        public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            place(view);
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            Rect bounds = new Rect(pill.getBounds());
            // A finger is wider than the pill's outline.
            bounds.inset(-pill.dp(6), -pill.dp(6));
            boolean inside = bounds.contains((int) event.getX(), (int) event.getY());
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!inside) return false;
                    pressed = true;
                    pill.setAlpha(140);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (pressed && !inside) {
                        pressed = false;
                        pill.setAlpha(255);
                    }
                    return pressed;
                case MotionEvent.ACTION_UP:
                    if (!pressed) return false;
                    pressed = false;
                    pill.setAlpha(255);
                    if (inside) open(view);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    pill.setAlpha(255);
                    return false;
                default:
                    return pressed;
            }
        }

        private static void open(View view) {
            FollowersScreen.showFrom(view);
        }

        @Nullable
        private static Drawable icon(Context context, String name) {
            int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
            if (id == 0) return null;
            try {
                Drawable icon = context.getDrawable(id);
                return icon == null ? null : icon.mutate();
            } catch (RuntimeException missing) {
                return null;
            }
        }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.misc.GlassReelsHide;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for Fill the screen, under Full screen Reels (#288).
 *
 * <p>Instagram decides for each reel whether its video fills the viewer, cut at the sides, or fits
 * inside it with bars above and below. It works out how far the viewer's shape is from the video's,
 * as a percentage, and crops only below thresholds it gets from its server. With the status bar and
 * the navigation bar hidden the viewer is taller, so a 9:16 reel on a 20:9 or 21:9 screen is about
 * 18 to 24 percent off and often lands just past the threshold: black bars on some reels and not
 * others. The patch asks here right after Instagram works the percentage out, and true makes the
 * reel fill the screen.
 *
 * <p>Hiding the comment bar makes the viewer taller the same way, with neither of those switches on:
 * its height goes to the viewer, so on a 1080x2400 screen a 9:16 reel is about 13 percent off and gets
 * a black band above and below (#371). So a reel whose bar is hidden fills too: every reel with the
 * switch for all reels, only reposted ones with the switch for those. Keep the comment bar's space
 * leaves that room empty instead, so the viewer doesn't grow and those reels are left to Instagram.
 *
 * <p>The tab bar's room does the same on the Reels tab (#544): a Reels auto-hide gives the Reels tab's
 * viewer the bar's height, and so does running reels behind the floating glass bar, so on a 1080x2316
 * screen a 9:16 reel there is about 13 percent off and came up low with a wide black band above it,
 * the top bar hidden or not. So the Reels tab's own viewer fills while either is on.
 *
 * <p>On a foldable or a large screen wider than 9:16, such as a 16:10 cover screen, Instagram keeps a
 * reel in a 9:16 column before the crop decision runs, so Fill also answers there ({@link #wide}, #491).
 *
 * <p>Show the whole reel (piko #2183) goes the other way and asks first, before Instagram's early
 * crop for media it lets crop: true makes every reel fit inside the viewer with bars where its shape
 * differs, so nothing is cut at the sides. It doesn't need Full screen Reels and wins over Fill.
 *
 * <p>Stories fill the screen (#436) answers for the story viewer, which lays a story out as a card
 * above the reply bar on a screen taller than 9:16 and over the whole screen on a shorter one.
 *
 * <p>Instagram's newer story viewer lays a story out only once its state has loaded, and for a reel
 * shared to a story that took about a second, so the card and its reply bar showed first (#618). The
 * story binder now asks as it binds ({@link #storyBound}) and lays out the whole screen right away.
 * A shared reel's frame is then kept at 9:16 across the screen's width ({@link #storyFrame}), since
 * filling the screen cut its sides off.
 */
public final class ReelFill {
    /**
     * The most a reel is cut to fill the screen, as a percentage of its shape. A 9:16 video on a 21:9
     * screen is just under it, and a square or 4:5 video, which would lose a third or more of its
     * picture, still fits with bars.
     */
    static final float MOST_CROP = 25f;

    /** What a reel cut to fill the screen is counted under. */
    static final String COUNT_FILLED = "reels filled to the screen";

    /** What a reel kept whole inside the screen is counted under. */
    static final String COUNT_FITTED = "reels shown whole";

    /** What a story laid out over the whole screen is counted under. */
    static final String COUNT_STORY = "stories laid out full screen";

    /** What a reel given the whole width of a screen wider than 9:16 is counted under. */
    static final String COUNT_WIDE = "reels laid out full width";

    /** What a story laid out over the whole screen as it binds is counted under. */
    static final String COUNT_STORY_BOUND = "stories laid out as they open";

    /** What a reel shared to a story and kept whole is counted under. */
    static final String COUNT_SHARED_REEL = "shared reels kept whole in stories";

    /** The shape Instagram keeps a reel to on a foldable or large screen of another shape. */
    static final float REEL_SHAPE = 9f / 16f;

    /**
     * Whether the story the binder last bound to each of the story viewer's item holders shares a reel.
     * Weak, so a holder Instagram lets go of isn't kept.
     */
    static final Map<Object, Boolean> SHARED_REELS = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The story frames last laid out for a story that shares a reel, for a layout that comes from the
     * newer story viewer's state with a holder the binder never saw. Weak, like {@link #SHARED_REELS}.
     */
    static final Map<View, Boolean> SHARED_FRAMES = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean loggedSharedReel;

    private ReelFill() {
    }

    /**
     * Asked first as Instagram decides whether a reel fills the viewer. True when Show the whole reel
     * is on, which makes the decision answer no: the reel fits with bars instead of being cut. False
     * hands the decision on to Fill the screen and Instagram, and so does anything that throws.
     */
    public static boolean fit() {
        try {
            if (!Utils.settingsReady() || !Settings.FIT_REEL_SCREEN.get()) return false;
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_FITTED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "reel fit", failure);
            return false;
        }
    }

    /**
     * Asked as Instagram decides whether a reel fills the viewer, with the viewer's ClipsViewerSource
     * and how far the viewer's shape is from the reel's in percent. True when Full screen Reels and
     * Fill the screen are both on, or this reel's comment bar is hidden, or this is the Reels tab's own
     * viewer and it has the tab bar's room ({@link #tabBarRoom}), and the reel would lose no
     * more than {@link #MOST_CROP}, which crops it to fill the screen. False hands the decision back
     * to Instagram, and so does anything that throws.
     */
    public static boolean fill(Object source, float cropPercent) {
        try {
            if (!Utils.settingsReady() || !wanted(source)) return false;
            if (!(cropPercent >= 0f && cropPercent <= MOST_CROP)) return false;
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_FILLED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "reel fill", failure);
            return false;
        }
    }

    /**
     * Asked as Instagram lays a reel out on a foldable or a large screen whose shape isn't 9:16 (#491),
     * with the shape of the room the reel has, its width over its height. Instagram then puts the reel
     * in a 9:16 column with empty sides, or a 9:16 box with empty bands on a narrower screen, so the
     * crop decision never sees anything to fill. True when Fill the screen and Full screen Reels are on,
     * Show the whole reel is off and the room is no more than {@link #MOST_CROP} percent off 9:16 either
     * way, which lays the reel out over all of it like on a phone; the crop decision then fills it within
     * the same cap. False keeps Instagram's column, and so does anything that throws. A tablet held
     * sideways is far past the cap and keeps it.
     */
    public static boolean wide(float shape) {
        try {
            if (!Utils.settingsReady() || Settings.FIT_REEL_SCREEN.get()) return false;
            if (!Settings.FILL_REEL_SCREEN.get() || !Settings.FULL_SCREEN_REELS.get()) return false;
            float off = Math.abs(shape - REEL_SHAPE) / REEL_SHAPE * 100f;
            if (!(off <= MOST_CROP)) return false;
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_WIDE);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "reel width", failure);
            return false;
        }
    }

    /**
     * Asked as Instagram lays a story out (#436). On a screen taller than 9:16 it shows the story as a
     * rounded card with the reply bar below it. True when Stories fill the screen is on, which makes it
     * lay the story out over the whole screen with the reply bar on top, as on a shorter phone. False
     * keeps the card, and so does anything that throws.
     */
    public static boolean story() {
        try {
            if (!Utils.settingsReady() || !Settings.FILL_STORY_SCREEN.get()) return false;
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_STORY);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "story fill", failure);
            return false;
        }
    }

    /**
     * Asked first thing as Instagram's story binder binds a story to an item holder (#618), with the
     * holder and whether the story shares a reel, non-zero for yes. Remembers that for
     * {@link #storyFrame}. Non-zero when Stories fill the screen is on, and the binder then lays the
     * whole screen out right away, before the story's first frame. Instagram's newer story viewer
     * otherwise leaves that to its state, which for a shared reel arrives about a second later, and the
     * card with its reply bar shows until then. Zero leaves the binder as it was, and so does anything
     * that throws.
     */
    public static int storyBound(Object holder, int sharedReel) {
        try {
            if (holder == null) return 0;
            SHARED_REELS.put(holder, sharedReel != 0);
            if (!Utils.settingsReady() || !Settings.FILL_STORY_SCREEN.get()) return 0;
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_STORY_BOUND);
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "story bind", failure);
            return 0;
        }
    }

    /**
     * Asked each time Instagram lays a story out over the whole screen (#618), with the item holder and
     * its story frame, the rounded frame the picture or video fills. With Stories fill the screen on
     * and a story that shares a reel, the frame goes back to 9:16, as wide as the screen and centered
     * with a band above and below, so the reel isn't cut at the sides. A screen wider than 9:16 gets
     * the frame's full height instead. A holder the binder never saw goes by what the frame last
     * showed. Anything else, or anything that throws, leaves Instagram's whole-screen frame.
     */
    public static void storyFrame(Object holder, Object frame) {
        try {
            if (holder == null || !(frame instanceof View)) return;
            if (!Utils.settingsReady() || !Settings.FILL_STORY_SCREEN.get()) return;
            View view = (View) frame;
            Boolean bound = SHARED_REELS.get(holder);
            if (Boolean.TRUE.equals(bound)) {
                SHARED_FRAMES.put(view, Boolean.TRUE);
            } else if (bound != null) {
                SHARED_FRAMES.remove(view);
            }
            if (!SHARED_FRAMES.containsKey(view)) return;
            int roomWidth = 0;
            int roomHeight = 0;
            if (view.getParent() instanceof View) {
                View room = (View) view.getParent();
                roomWidth = room.getWidth();
                roomHeight = room.getHeight();
            }
            if (roomWidth <= 0 || roomHeight <= 0) {
                // Not laid out yet when it first binds: the viewer takes the whole screen.
                DisplayMetrics screen = view.getResources().getDisplayMetrics();
                roomWidth = screen.widthPixels;
                roomHeight = screen.heightPixels;
            }
            if (roomWidth <= 0 || roomHeight <= 0) return;
            int[] size = wholeFrame(roomWidth, roomHeight);
            view.setLayoutParams(new FrameLayout.LayoutParams(size[0], size[1], Gravity.CENTER));
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_SHARED_REEL);
            if (!loggedSharedReel) {
                loggedSharedReel = true;
                Logger.printDebug(() -> "Story fill: keeping a shared reel's whole frame");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "shared reel frame", failure);
        }
    }

    /**
     * The width and height of a 9:16 frame as big as fits [roomWidth] by [roomHeight]: the room's whole
     * width on a taller room, its whole height on a wider one, MATCH_PARENT for the side it fills.
     */
    static int[] wholeFrame(int roomWidth, int roomHeight) {
        if ((long) roomWidth * 16 <= (long) roomHeight * 9) {
            return new int[] {ViewGroup.LayoutParams.MATCH_PARENT, Math.round(roomWidth / REEL_SHAPE)};
        }
        return new int[] {Math.round(roomHeight * REEL_SHAPE), ViewGroup.LayoutParams.MATCH_PARENT};
    }

    private static boolean wanted(Object source) {
        boolean reelsTab = source instanceof Enum && ReelsTopBar.REELS_TAB.contains(((Enum<?>) source).name());
        return (commentBarRoom(source) && (reelsTab || !ReelDeclutter.keepsCommentBarSpace(source)))
                || (Settings.FILL_REEL_SCREEN.get() && Settings.FULL_SCREEN_REELS.get())
                || (tabBarRoom() && reelsTab);
    }

    /**
     * Whether a switch hides the comment bar of a reel opened from [source]. Its room goes to the
     * viewer unless Keep the comment bar's space leaves it empty, which it does everywhere but the
     * Reels tab, whose bar is a component that keeps no room (#523).
     */
    private static boolean commentBarRoom(Object source) {
        return Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.get()
                || (Settings.HIDE_REEL_COMMENT_BAR.get() && ReelDeclutter.opensRepost(source));
    }

    /**
     * Whether the Reels tab's viewer gets the tab bar's height: a Reels auto-hide, which slides the bar
     * away (Instagram's own bar or the glass one), or reels run behind the floating glass bar.
     */
    static boolean tabBarRoom() {
        GlassReelsHide hide = Settings.GLASS_TAB_BAR_REELS_HIDE.get();
        return (hide != null && hide != GlassReelsHide.NEVER)
                || (Settings.GLASS_TAB_BAR.get() && Settings.GLASS_TAB_BAR_FLOAT.get()
                        && Settings.GLASS_TAB_BAR_REELS_BEHIND.get());
    }
}

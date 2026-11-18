/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Playback speed and Block in the menu of a feed post (#743), each behind its own switch.
 *
 * <p>Playback speed shows on a post whose video is the one a feed player has loaded. Instagram's feed
 * makes one 2x controller per feed, which can reach that feed's player and tell which post it holds,
 * so the patch hands each new controller here. A pick goes to the same player setter Instagram's
 * own hold for 2x uses. The feed reuses one player from post to post and it doesn't say what speed
 * it's at, so a pick is kept with the record the feed makes each time it prepares a video. That way
 * it lasts for that playback only, the list can mark it, and the end of a hold for 2x, which Instagram
 * has set 1x, goes back to it.
 *
 * <p>Block shows on anyone else's post and opens Instagram's own block sheet, with Instagram's own
 * confirm in it, so nothing is blocked by the tap alone.
 *
 * <p>Every part fails open. With a switch off, while paused, or on a build where the patch couldn't
 * find what a row needs, the row isn't offered and nothing throws into Instagram.
 */
public final class FeedPostRows {
    static final String SPEED_OPTION = "HUSHGRAM_POST_SPEED";
    static final String BLOCK_OPTION = "HUSHGRAM_POST_BLOCK";
    /** The feed's module name, which Instagram's block sheet is opened with. */
    static final String MODULE = "feed_timeline";
    private static final float NORMAL_SPEED = 1f;

    private static Object speedOption;
    private static Object blockOption;

    /** Every feed's 2x controller still alive, newest first. */
    private static final List<WeakReference<Object>> CONTROLLERS = new ArrayList<>();
    /** The speed picked for each playback, by the record the feed made for it. */
    private static final Map<Object, Float> PICKED = new WeakHashMap<>();
    /** The player and playback the last Playback speed row was offered for, since opening the menu can unload them. */
    private static WeakReference<Object> offeredManager;
    private static WeakReference<Object> offeredPlayback;
    @Nullable private static String offeredId;

    private FeedPostRows() {
    }

    /** A feed's player manager, and the record of the playback a Playback speed tap is about, if it has one. */
    private static final class Playing {
        final Object manager;
        @Nullable final Object playback;

        Playing(Object manager, @Nullable Object playback) {
            this.manager = manager;
            this.playback = playback;
        }
    }

    /** What the rows read from Instagram and run. Replaced by tests. */
    interface Bridge {
        /** Whether the patch found Instagram's block sheet. */
        boolean canBlock();

        /** Opens Instagram's block sheet for [owner] with the feed menu [helper]'s activity and session. */
        void block(Object helper, Object owner, String module, String ownerId);

        /** The feed player manager of a 2x [controller]. */
        @Nullable
        Object managerOf(Object controller);

        /** The post [manager] has loaded, or null. */
        @Nullable
        Object mediaOf(Object manager);

        /** The player [manager] is driving, or null when there is none now. */
        @Nullable
        Object playerOf(Object manager);

        /** The record [manager] made when it last prepared a video, or null once it stopped. */
        @Nullable
        Object playbackOf(Object manager);

        void setSpeed(Object player, float speed);
    }

    static Bridge bridge = new Bridge() {
        @Override public boolean canBlock() {
            return blockReady();
        }

        @Override public void block(Object helper, Object owner, String module, String ownerId) {
            blockRun(helper, owner, module, ownerId);
        }

        @Override public Object managerOf(Object controller) {
            return managerRun(controller);
        }

        @Override public Object mediaOf(Object manager) {
            return mediaRun(manager);
        }

        @Override public Object playerOf(Object manager) {
            return playerRun(manager);
        }

        @Override public Object playbackOf(Object manager) {
            return playbackRun(manager);
        }

        @Override public void setSpeed(Object player, float speed) {
            speedRun(player, speed);
        }
    };

    /** Whether the patch found Instagram's block sheet. The patch replaces this body. */
    public static boolean blockReady() {
        return false;
    }

    /** Instagram's block sheet for [owner], from the feed menu [helper]. The patch replaces this body. */
    public static Object blockRun(Object helper, Object owner, String module, String ownerId) {
        return null;
    }

    /** The feed player manager of a 2x [controller]. The patch replaces this body. */
    public static Object managerRun(Object controller) {
        return null;
    }

    /** The post [manager] has loaded. The patch replaces this body. */
    public static Object mediaRun(Object manager) {
        return null;
    }

    /** The player [manager] is driving. The patch replaces this body. */
    public static Object playerRun(Object manager) {
        return null;
    }

    /** The record of what [manager] last prepared. The patch replaces this body. */
    public static Object playbackRun(Object manager) {
        return null;
    }

    /** Sets [player]'s speed. The patch replaces this body. */
    public static void speedRun(Object player, float speed) {
    }

    /**
     * The speed the end of a hold for 2x on [controller]'s feed goes back to: the one picked for the
     * playback under way, or Instagram's own [speed] when none was or the switch is off. Never throws.
     */
    public static float restingSpeed(Object controller, float speed) {
        try {
            if (controller == null || !speedOn()) return speed;
            Object manager = bridge.managerOf(controller);
            Object playback = manager == null ? null : bridge.playbackOf(manager);
            if (playback == null) return speed;
            synchronized (PICKED) {
                Float picked = PICKED.get(playback);
                return picked == null ? speed : picked;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed speed after a hold", failure);
            return speed;
        }
    }

    /** Playback speed's option, made once. Null when it can't be made. Never throws. */
    public static synchronized Object speedOption() {
        try {
            if (speedOption == null) speedOption = InstagramMedia.feedOption(SPEED_OPTION);
            return speedOption;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed speed option", failure);
            return null;
        }
    }

    /** Block's option, made once. Null when it can't be made. Never throws. */
    public static synchronized Object blockOption() {
        try {
            if (blockOption == null) blockOption = InstagramMedia.feedOption(BLOCK_OPTION);
            return blockOption;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed block option", failure);
            return null;
        }
    }

    /** Whether either row's switch is on. Paused answers no. Never throws. */
    static boolean on() {
        return speedOn() || blockOn();
    }

    private static boolean speedOn() {
        try {
            return Utils.settingsReady() && Settings.POST_MENU_SPEED.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed speed switch", failure);
            return false;
        }
    }

    private static boolean blockOn() {
        try {
            return Utils.settingsReady() && Settings.POST_MENU_BLOCK.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed block switch", failure);
            return false;
        }
    }

    /** Remembers a feed's new 2x [controller], which the feed keeps for as long as it lives. Never throws. */
    public static void controllerMade(Object controller) {
        try {
            if (controller == null) return;
            synchronized (CONTROLLERS) {
                for (Iterator<WeakReference<Object>> kept = CONTROLLERS.iterator(); kept.hasNext(); ) {
                    if (kept.next().get() == null) kept.remove();
                }
                CONTROLLERS.add(0, new WeakReference<>(controller));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed player", failure);
        }
    }

    /**
     * Adds Playback speed and Block to [rows], the list the feed menu's builder [menu] fills, each
     * when its switch is on and it applies to this post. Never throws.
     */
    public static void offer(Object menu, ArrayList<?> rows) {
        try {
            if (menu == null || rows == null) return;
            boolean speed = speedOn();
            boolean block = blockOn();
            if (!speed && !block) return;
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            Object post = InstagramMedia.feedMenuMedia(menu);
            if (post == null) return;
            if (speed && offersSpeed(post, InstagramMedia.feedMenuItemState(menu))) {
                Object option = speedOption();
                if (option != null) InstagramMedia.addSaveAllRow(menu, rows, option, L10n.t("Playback speed"));
            }
            if (block && offersBlock(post)) {
                Object option = blockOption();
                if (option != null) InstagramMedia.addSaveAllRow(menu, rows, option, L10n.t("Block"));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed post rows", failure);
        }
    }

    /** Whether what's on screen of [post] is a video a feed player has loaded, which it then remembers. */
    private static boolean offersSpeed(Object post, @Nullable Object itemState) {
        Object shown = VideoDownload.shown(post, itemState);
        if (!VideoDownload.hasVideo(shown)) {
            Logger.printDebug(() -> "Playback speed row: no video on screen of post " + InstagramMedia.mediaId(post)
                    + ", showing " + (shown == null ? null : InstagramMedia.mediaId(shown)));
            return false;
        }
        Object manager = managerPlaying(post, shown);
        if (manager == null) {
            Logger.printDebug(() -> "Playback speed row: no feed player holds " + InstagramMedia.mediaId(shown)
                    + " of post " + InstagramMedia.mediaId(post));
            return false;
        }
        Object playback = bridge.playbackOf(manager);
        synchronized (CONTROLLERS) {
            offeredManager = new WeakReference<>(manager);
            offeredPlayback = playback == null ? null : new WeakReference<>(playback);
            offeredId = InstagramMedia.mediaId(shown);
        }
        return true;
    }

    private static boolean offersBlock(Object post) {
        if (!bridge.canBlock() || InstagramMedia.owner(post) == null) return false;
        String id = ReelBlockButton.ownerOf(InstagramMedia.mediaId(post));
        return id != null && !ReelBlockButton.yours(id);
    }

    /** The manager of the newest feed whose player holds [shown] or its [post], or null. */
    @Nullable
    private static Object managerPlaying(Object post, Object shown) {
        List<WeakReference<Object>> controllers;
        synchronized (CONTROLLERS) {
            controllers = new ArrayList<>(CONTROLLERS);
        }
        for (WeakReference<Object> kept : controllers) {
            Object controller = kept.get();
            Object manager = controller == null ? null : bridge.managerOf(controller);
            if (manager == null) continue;
            Object playing = bridge.mediaOf(manager);
            if (same(playing, shown) || same(playing, post)) return manager;
        }
        return null;
    }

    private static boolean same(@Nullable Object one, @Nullable Object other) {
        if (one == null || other == null) return false;
        if (one == other) return true;
        String id = InstagramMedia.mediaId(one);
        return id != null && id.equals(InstagramMedia.mediaId(other));
    }

    /**
     * [options], the options the short feed menu keeps, with each row whose switch is on after
     * HushGram's own rows that are on it, [ours], or in front when none is. Never throws from a
     * list it can read.
     */
    static List<?> withRows(List<?> options, Object... ours) {
        List<?> allowed = options;
        Object speed = speedOn() ? speedOption() : null;
        allowed = placed(allowed, speed, ours);
        if (blockOn() && bridge.canBlock()) {
            Object[] before = new Object[ours.length + 1];
            System.arraycopy(ours, 0, before, 0, ours.length);
            before[ours.length] = speed;
            allowed = placed(allowed, blockOption(), before);
        }
        return allowed;
    }

    private static List<?> placed(List<?> options, @Nullable Object row, Object... ours) {
        if (row == null || options.contains(row)) return options;
        List<Object> allowed = new ArrayList<>(options);
        int after = -1;
        for (Object mine : ours) {
            if (mine != null) after = Math.max(after, allowed.indexOf(mine));
        }
        allowed.add(after + 1, row);
        return allowed;
    }

    /**
     * Opens the speeds for the video of [media], or of the carousel page on screen that [itemState]
     * names, over [activity], when its Playback speed row is tapped. Never throws.
     */
    public static void speed(Object media, Object itemState, Activity activity) {
        try {
            if (!speedOn() || media == null || activity == null || activity.isFinishing()) return;
            Object shown = VideoDownload.shown(media, itemState);
            Playing playing = shown == null ? null : playingFor(media, shown);
            Object player = playing == null ? null : bridge.playerOf(playing.manager);
            if (player == null) {
                didntChange(activity);
                return;
            }
            ReelSpeedButton.lists.show(activity, picked(playing.playback), speed -> pick(playing, speed, activity));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed speed tap", failure);
        }
    }

    /**
     * The manager holding [shown] and its current playback, or what its row was offered for if the
     * menu unloaded it. That keeps the offered playback rather than asking the manager, which may
     * have moved on to another video by now.
     */
    @Nullable
    private static Playing playingFor(Object post, Object shown) {
        Object manager = managerPlaying(post, shown);
        if (manager != null) return new Playing(manager, bridge.playbackOf(manager));
        synchronized (CONTROLLERS) {
            String id = InstagramMedia.mediaId(shown);
            if (offeredManager == null || id == null || !id.equals(offeredId)) return null;
            Object offered = offeredManager.get();
            if (offered == null) return null;
            return new Playing(offered, offeredPlayback == null ? null : offeredPlayback.get());
        }
    }

    private static float picked(@Nullable Object playback) {
        if (playback == null) return NORMAL_SPEED;
        synchronized (PICKED) {
            Float speed = PICKED.get(playback);
            return speed == null ? NORMAL_SPEED : speed;
        }
    }

    private static void pick(Playing playing, float speed, Activity activity) {
        try {
            Object player = bridge.playerOf(playing.manager);
            if (player == null) {
                didntChange(activity);
                return;
            }
            bridge.setSpeed(player, speed);
            if (playing.playback == null) return;
            synchronized (PICKED) {
                PICKED.put(playing.playback, speed);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed speed pick", failure);
            didntChange(activity);
        }
    }

    private static void didntChange(Activity activity) {
        Utils.showToastShort(L10n.t(activity, "Speed didn't change"));
    }

    /**
     * Opens Instagram's block sheet for who posted [media], from the feed menu [helper], when its
     * Block row is tapped. Never throws.
     */
    public static void block(Object media, Object helper) {
        try {
            if (!blockOn() || media == null || helper == null || !bridge.canBlock()) return;
            Object owner = InstagramMedia.owner(media);
            String id = ReelBlockButton.ownerOf(InstagramMedia.mediaId(media));
            if (owner != null && id != null) bridge.block(helper, owner, MODULE, id);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed block tap", failure);
        }
    }

    /** Forgets every controller and pick. For tests. */
    static void reset() {
        synchronized (CONTROLLERS) {
            CONTROLLERS.clear();
            offeredManager = null;
            offeredPlayback = null;
            offeredId = null;
        }
        synchronized (PICKED) {
            PICKED.clear();
        }
    }
}

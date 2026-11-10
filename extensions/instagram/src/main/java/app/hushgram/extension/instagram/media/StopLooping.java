/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Stop videos looping" patch.
 *
 * <p>Instagram's video player, the one feed videos and reels play through, hands its player engine
 * a loop flag each time it starts a video and each time something changes it. The patch passes
 * that flag through {@link #loop(Object, int)} on its way in, so with the switch on the engine is
 * told not to loop, and a video stops on its last frame instead of playing again. Stories play
 * through another player and keep their own timer, so they move on as before.
 *
 * <p>Instagram's Reels auto scroll, and Move on when a reel ends, move to the next reel when the
 * engine reports that the reel looped. A reel that doesn't loop never reports it and would sit on
 * its last frame, so a reel in the Reels viewer keeps looping while either of them is on, and the
 * scroller moves on as before.
 *
 * <p>Loop this video, a row in a feed post's and a reel's menu with the switch on (#610), makes
 * that one video loop anyway. The row marks the video by its media ID ({@link #toggle}). Each player
 * that hands its engine a flag is remembered, weakly, and asked for the media ID of what it plays
 * ({@link #videoId}), so a marked video loops in whichever player shows it, and the player playing
 * it now is handed its flag again at once ({@link #reloop}). A player that already stopped on the
 * video's last frame plays it again from the start ({@link #restart}), since a new flag alone
 * doesn't move it. Stop looping this video takes the mark off the same way. Marks last until the
 * app closes.
 */
public final class StopLooping {
    /** Counted under the patch's name for each video looped, and each loop ended, from a menu. */
    static final String LOOPED = "video looped from a menu";
    static final String UNLOOPED = "video loop ended from a menu";

    /** How near its end, in milliseconds, a video counts as stopped on its last frame. */
    static final int END_MS = 1000;

    /** Whether auto scroll is moving Reels on, when a test says so instead of the settings. */
    @Nullable
    static volatile Boolean autoScrollsForTests;

    /** Whether a player is the Reels viewer's, when a test says so instead of {@link #reels(Object)}. */
    @Nullable
    static volatile Boolean reelsForTests;

    /** What this class reads from and does to a player. {@link #PATCHED} is the patch's; tests stand in. */
    interface Player {
        /** The media ID of what [player] plays, or null. */
        @Nullable
        String videoId(Object player);

        /** Hands [player]'s engine its loop flag again, through the player's own setter. */
        void reloop(Object player);

        /** Where [player] is in its video, in milliseconds, or -1. */
        int position(Object player);

        /** How long [player]'s video is, in milliseconds, or -1. */
        int length(Object player);

        /** Takes [player] back to the start of its video and plays it. */
        void restart(Object player);
    }

    static final Player PATCHED = new Player() {
        @Override
        public String videoId(Object player) {
            return StopLooping.videoId(player);
        }

        @Override
        public void reloop(Object player) {
            StopLooping.reloop(player);
        }

        @Override
        public int position(Object player) {
            return StopLooping.position(player);
        }

        @Override
        public int length(Object player) {
            return StopLooping.length(player);
        }

        @Override
        public void restart(Object player) {
            StopLooping.restart(player);
        }
    };

    static volatile Player access = PATCHED;

    /** The players that have handed their engine a loop flag, weakly. */
    private static final Set<Object> players = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /** The videos looped from a menu, by the media's own number, the part of its ID before the owner's. */
    private static final Set<String> looping = Collections.synchronizedSet(new HashSet<>());

    private StopLooping() {
    }

    /**
     * The loop flag for [player]'s engine: Instagram's ([loop] nonzero for yes), or no while the
     * switch is on, except for a video looped from a menu and a reel in the Reels viewer while auto
     * scroll is moving Reels on. Instagram's flag while the switch is off, HushGram is paused or the
     * settings aren't ready. Never throws.
     */
    public static boolean loop(Object player, int loop) {
        boolean instagram = loop != 0;
        try {
            HookStatus.invoked(FamilyNames.STOP_LOOPING);
            if (!on()) {
                return instagram;
            }
            if (player != null) players.add(player);
            if (looped(player)) {
                return true;
            }
            return instagram && autoScrolls() && inReels(player);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STOP_LOOPING, "video loop flag", failure);
            return instagram;
        }
    }

    /**
     * Whether [player] plays in the Reels viewer. The patch writes the body, an instance-of on the
     * video logger the player was made with; false in an unpatched build.
     */
    public static boolean reels(Object player) {
        return false;
    }

    /**
     * The media ID of what [player] plays, read from the video source it was handed. The patch
     * writes the body; null for anything else and in an unpatched build.
     */
    @Nullable
    public static String videoId(Object player) {
        return null;
    }

    /**
     * Hands [player]'s engine its loop flag again, through the player's own setter, so the flag goes
     * through {@link #loop} once more. The patch writes the body; nothing in an unpatched build.
     */
    public static void reloop(Object player) {
    }

    /**
     * Where [player] is in its video, in milliseconds, through the player's own reader. The patch
     * writes the body; -1 for anything else and in an unpatched build.
     */
    public static int position(Object player) {
        return -1;
    }

    /**
     * How long [player]'s video is, in milliseconds, through the player's own reader. The patch
     * writes the body; -1 for anything else and in an unpatched build.
     */
    public static int length(Object player) {
        return -1;
    }

    /**
     * Seeks [player] to the start of its video and plays it, the way Instagram resumes a video. The
     * patch writes the body; nothing in an unpatched build or a build it couldn't read.
     */
    public static void restart(Object player) {
    }

    // ------------------------------------------------------------------ Loop this video (#610)

    /**
     * Whether a menu offers Loop this video on a video: the patch is in the build and its switch is
     * on, and for a reel's menu ([reel]), auto scroll isn't keeping Reels looping anyway. The caller
     * checks that there's a video. Never throws.
     */
    public static boolean offersRow(boolean reel) {
        try {
            return SettingsStatus.stopLooping() && on() && !(reel && autoScrolls());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STOP_LOOPING, "video loop row", failure);
            return false;
        }
    }

    /** Whether [media], a feed post, carousel page or reel, is looped from a menu, for the row's label. Never throws. */
    public static boolean looping(Object media) {
        try {
            String id = number(InstagramMedia.mediaId(media));
            return id != null && looping.contains(id);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STOP_LOOPING, "video loop row", failure);
            return false;
        }
    }

    /**
     * Loops [media], or takes its loop off when it has one, and hands the engine of each player
     * showing it the new flag at once. A player that already stopped at the end of it plays it again
     * from the start when it loops now. Answers whether it loops now, or null when nothing changed:
     * the switch is off, HushGram is paused or [media] has no ID. Never throws.
     */
    @Nullable
    public static Boolean toggle(Object media) {
        try {
            if (!on()) return null;
            String id = number(InstagramMedia.mediaId(media));
            if (id == null) return null;
            boolean loop = !looping.remove(id);
            if (loop) looping.add(id);
            for (Object player : showing(id)) {
                access.reloop(player);
                if (loop && ended(player)) access.restart(player);
            }
            HookStatus.counted(FamilyNames.STOP_LOOPING, loop ? LOOPED : UNLOOPED);
            return loop;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STOP_LOOPING, "video loop", failure);
            return null;
        }
    }

    /** The players remembered whose video is [id]. */
    private static List<Object> showing(String id) {
        List<Object> seen;
        synchronized (players) {
            seen = new ArrayList<>(players);
        }
        List<Object> showing = new ArrayList<>();
        for (Object player : seen) {
            if (player != null && id.equals(number(access.videoId(player)))) showing.add(player);
        }
        return showing;
    }

    /**
     * Whether [player] stopped on its video's last frame: within {@link #END_MS} of the end of a
     * video whose length it knows. A video that ended still reads as playing, so the position is
     * all there is to go on.
     */
    static boolean ended(Object player) {
        int length = access.length(player);
        int position = access.position(player);
        return length > 0 && position >= 0 && length - position <= END_MS;
    }

    private static boolean looped(@Nullable Object player) {
        if (player == null || looping.isEmpty()) return false;
        String id = number(access.videoId(player));
        return id != null && looping.contains(id);
    }

    /**
     * The media's own number from [id]: a post's ID is that number and its owner's joined by an
     * underscore, and a video source may carry either form.
     */
    @Nullable
    static String number(@Nullable String id) {
        if (id == null) return null;
        int cut = id.indexOf('_');
        String number = (cut < 0 ? id : id.substring(0, cut)).trim();
        return number.isEmpty() ? null : number;
    }

    // ------------------------------------------------------------------ the rule

    /**
     * Whether Reels moves on by itself when a reel ends: Move on when a reel ends, or Instagram's own
     * auto scroll as Keep Reels auto scroll on last saw it. Both need that patch in the build.
     */
    static boolean autoScrolls() {
        Boolean forced = autoScrollsForTests;
        if (forced != null) {
            return forced;
        }
        return SettingsStatus.reelAutoScroll()
                && (Settings.REEL_AUTO_ADVANCE.get() || Settings.REEL_AUTO_SCROLL_ON.savedValue());
    }

    private static boolean inReels(Object player) {
        Boolean forced = reelsForTests;
        return forced != null ? forced : reels(player);
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.STOP_VIDEO_LOOPING.get();
    }

    /** Forgets the players and the loops. For tests. */
    static void forget() {
        players.clear();
        looping.clear();
        access = PATCHED;
    }
}

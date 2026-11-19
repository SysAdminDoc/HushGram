/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Pauses the Home and Reels videos under the HushGram settings and plays them again when the
 * settings close (#762).
 *
 * <p>The settings are a dialog over Instagram's screen, so Instagram's fragments never pause and the
 * video under them keeps playing and loading where nobody can see it. While they show, each player
 * that was playing is paused through Instagram's own pause, and a player that starts meanwhile (the
 * feed scrolling on, a reel looping into the next) is paused once its start is done. Closing the
 * settings plays those players again. Leaving Instagram with the settings open keeps the list, so
 * coming back and closing them still plays the video you were on.
 *
 * <p>The players come from the "Keep playing in the background" patch's start hook, which remembers
 * the recent players whether or not its switch is on. A build without that patch sees no players,
 * and this does nothing.
 */
public final class SettingsPause {
    private static final Object LOCK = new Object();
    /** The players paused under the settings, oldest first. Under {@link #LOCK}. */
    private static final List<WeakReference<Object>> PAUSED = new ArrayList<>();
    private static volatile boolean showing;

    private SettingsPause() {
    }

    /** From the settings dialog's onStart, each time it shows. */
    public static void opened() {
        try {
            showing = true;
            for (Object player : BackgroundPlay.recentPlayers()) {
                if (BackgroundPlay.playingNow(player)) hold(player);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "settings pause", t);
        }
    }

    /**
     * From {@link BackgroundPlay#started}, each time a player starts. Under the settings it's paused
     * once Instagram's start is done, since pausing in the middle of it is undone by the start.
     */
    static void started(Object player) {
        if (!showing) return;
        Utils.runOnMainThread(() -> {
            try {
                if (showing) hold(player);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "settings pause start", t);
            }
        });
    }

    /**
     * From the settings dialog's onStop when the dialog is going away: plays the paused ones.
     *
     * <p>Each one goes through Instagram's own play, which skips a player it already plays (its
     * play flag). Its "is playing" can't decide that: a feed video Instagram was still preparing when
     * the pause came (the launcher shortcut pauses and restarts the feed as the settings open) reads
     * as playing once it's prepared, though it never started again.
     */
    public static void closed() {
        try {
            List<Object> again = take();
            showing = false;
            for (Object player : again) BackgroundPlay.playAgain(player);
            if (!again.isEmpty()) {
                int count = again.size();
                Logger.printInfo(() -> "SettingsPause: playing " + count + " again");
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "settings resume", t);
        }
    }

    /**
     * From the settings dialog's onStop when Instagram itself stopped under it (Home, another app):
     * Instagram pauses its own videos then, and the next onStart pauses whatever it restarts.
     */
    public static void hidden() {
        showing = false;
    }

    /** From the settings dialog's onStop when its activity is finishing: nothing comes back. */
    public static void dropped() {
        showing = false;
        synchronized (LOCK) {
            PAUSED.clear();
        }
    }

    static boolean showing() {
        return showing;
    }

    private static void hold(Object player) {
        BackgroundPlay.pauseUnderSettings(player);
        synchronized (LOCK) {
            for (Iterator<WeakReference<Object>> it = PAUSED.iterator(); it.hasNext(); ) {
                Object seen = it.next().get();
                if (seen == null) it.remove();
                else if (seen == player) return;
            }
            PAUSED.add(new WeakReference<>(player));
        }
        Logger.printInfo(() -> "SettingsPause: paused under the settings");
    }

    private static List<Object> take() {
        List<Object> players = new ArrayList<>();
        synchronized (LOCK) {
            for (WeakReference<Object> ref : PAUSED) {
                Object player = ref.get();
                if (player != null) players.add(player);
            }
            PAUSED.clear();
        }
        return players;
    }

    /** Forgets every paused player. For tests. */
    static void resetForTests() {
        showing = false;
        synchronized (LOCK) {
            PAUSED.clear();
        }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stands in for Instagram's players the way the "Keep playing in the background" stubs reach them,
 * for tests of {@link SettingsPause} outside this package (the settings dialog's).
 */
public final class SettingsPauseForTests {
    private SettingsPauseForTests() {
    }

    /**
     * Players a test starts, pauses and plays. Their pause asks the hooks first, as the patched one
     * does, and their play skips a player that already plays, as IgVideoPlayerImpl's play does on
     * its play flag.
     */
    public static final class Players implements BackgroundPlay.Control {
        /** The players that play: their frames move and their play flag is set. */
        private final Set<Object> playing = new HashSet<>();
        /** Paused players whose state still reads playing, see {@link #prepared}. */
        private final Set<Object> sayPlaying = new HashSet<>();
        /** Each player whose pause went through, in order. */
        public final List<Object> paused = new ArrayList<>();
        /** Each player a play started, in order. */
        public final List<Object> played = new ArrayList<>();

        /** Instagram starts [player]: it plays, and the start hook hears of it. */
        public void start(Object player) {
            playing.add(player);
            sayPlaying.remove(player);
            BackgroundPlay.started(player);
        }

        /** The user pauses [player] from Instagram's own controls. */
        public void stop(Object player) {
            playing.remove(player);
            sayPlaying.remove(player);
        }

        /**
         * The video [player] was preparing when its pause came is ready now: Instagram's state says
         * it plays, though it stays paused and its play flag stays cleared (#762, seen on 456).
         */
        public void prepared(Object player) {
            if (!playing.contains(player)) sayPlaying.add(player);
        }

        public boolean isPlaying(Object player) {
            return playing.contains(player);
        }

        @Override
        public boolean playing(Object player) {
            return playing.contains(player) || sayPlaying.contains(player);
        }

        @Override
        public void pause(Object player) {
            if (BackgroundPlay.hold(player, BackgroundPlay.PAUSE_REASON)) return;
            // IgVideoPlayerImpl's pause reaches IgGrootPlayer's, where Tap to play's hook sits.
            TapToPlay.paused(player, BackgroundPlay.PAUSE_REASON);
            playing.remove(player);
            sayPlaying.remove(player);
            paused.add(player);
        }

        @Override
        public void play(Object player) {
            if (playing.contains(player)) return;
            played.add(player);
            playing.add(player);
            sayPlaying.remove(player);
        }
    }

    /** Forgets every player and pause, and stands in for the stubs with new {@link Players}. */
    public static Players install() {
        Players players = new Players();
        BackgroundPlay.resetForTests();
        BackgroundPlay.controlForTests = players;
        return players;
    }

    /** Puts the stubs back and forgets every player and pause. */
    public static void forget() {
        BackgroundPlay.resetForTests();
    }
}

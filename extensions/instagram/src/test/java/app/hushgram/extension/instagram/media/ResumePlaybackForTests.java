/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Stands in for IgVideoPlayerImpl and the IgVideoSource it plays, the way the patch's filled-in
 * stubs would reach them, and runs the resume Instagram's looper would run later.
 */
public final class ResumePlaybackForTests {
    /** Stands in for Instagram's ProductType: only the constant names matter. */
    public enum ProductType { FEED, CLIPS, LIVE, LIVE_VOD, AD }

    /** Stands in for IgVideoSource: the fields the rule reads. */
    public static final class Video {
        public String id;
        public int length;
        public ProductType product = ProductType.FEED;
        public boolean sponsored;

        public Video(String id, int length) {
            this.id = id;
            this.length = length;
        }
    }

    /** Stands in for IgVideoPlayerImpl. */
    /** The user ID of the account every test player was made for, unless a test says otherwise. */
    public static final String ACCOUNT = "17841400000000001";

    public static final class Player {
        public Video video;
        public int position;
        /** What its length reader does once it has let go of its video: throw, as Instagram's does. */
        public boolean released;
        /** Every seek made on it, in milliseconds. */
        public final List<Integer> seeks = new ArrayList<>();
        /** Runs inside a seek, after the seek has told its hook. */
        public Runnable duringSeek;
        /** The user ID of the account it was made for, as its UserSession holds it. */
        public String account = ACCOUNT;
        /** Whether that UserSession's account has signed out since. */
        public boolean signedOut;

        public Player(Video video) {
            this.video = video;
        }
    }

    /** Stands in for a UserSession as it ends: its account's user ID and whether it signed out. */
    public static final class Session {
        public final String userId;
        public final boolean loggedOut;

        public Session(String userId, boolean loggedOut) {
            this.userId = userId;
            this.loggedOut = loggedOut;
        }
    }

    /** What the patched session stubs do, on {@link Session}s. */
    static final ResumePlayback.Session SESSIONS = new ResumePlayback.Session() {
        @Override
        public String userId(Object session) {
            return ((Session) session).userId;
        }

        @Override
        public boolean loggedOut(Object session) {
            return ((Session) session).loggedOut;
        }
    };

    /** Resumes the rule posted, not yet run. */
    static final Deque<Runnable> LATER = new ArrayDeque<>();

    /** What the patched stubs do, on {@link Player}s. */
    static final ResumePlayback.Player ACCESS = new ResumePlayback.Player() {
        @Override
        public int position(Object player) {
            return ((Player) player).position;
        }

        @Override
        public int duration(Object player) {
            Player playing = (Player) player;
            if (playing.released) throw new IllegalStateException("Required value was null.");
            return playing.video == null ? 0 : playing.video.length;
        }

        @Override
        public ResumePlayback.Facts facts(Object player) {
            Player playing = (Player) player;
            Video video = playing.video;
            if (video == null) return null;
            Session session = playing.account == null ? null : new Session(playing.account, playing.signedOut);
            String owner = ResumePlayback.owner(session, SESSIONS);
            return ResumePlayback.factsOf(ResumePlayback.ownedKey(owner, video.id), video.product, video.sponsored);
        }

        @Override
        public boolean seek(Object player, int positionMs) {
            Player seeking = (Player) player;
            // Instagram's seek tells the seek hook first.
            ResumePlayback.seeking(player, positionMs);
            if (seeking.duringSeek != null) seeking.duringSeek.run();
            seeking.seeks.add(positionMs);
            seeking.position = positionMs;
            return true;
        }
    };

    private ResumePlaybackForTests() {
    }

    /** Stands in for the patched stubs and Instagram's looper, and forgets every player and point. */
    public static void install() {
        ResumePlayback.forget();
        LATER.clear();
        ResumePlayback.access = ACCESS;
        ResumePlayback.later = LATER::add;
        ResumePlayback.sessions = SESSIONS;
    }

    /** Runs every resume posted so far. */
    public static void runLater() {
        Runnable task;
        while ((task = LATER.poll()) != null) task.run();
    }

    /** Puts the app's own player, looper and store back. */
    public static void forget() {
        LATER.clear();
        ResumePlayback.forget();
    }
}

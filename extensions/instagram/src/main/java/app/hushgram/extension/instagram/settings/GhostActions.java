/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import java.util.Set;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Block likes, replies and posts in Ghost mode" patch (#876): while Ghost mode is
 * on and its switch is on, a tap that would tell someone you were there does nothing and says why.
 *
 * <p>The patch calls {@link #like} first thing in the handlers a like goes through (a post's Like
 * button and double tap, a story's heart and super like, and through Ask before a like's guard a
 * reel's Like button and a comment's heart), {@link #reply} first thing in a story's typed reply and
 * quick reaction, {@link #share} first thing in Copy link and in each step that makes a share link,
 * {@link #comment} first thing in the comment sheet's posts, and {@link #post} first thing in the
 * story composer's Your story and Close friends buttons and the post and reel composer's Share. A
 * true answer returns before Instagram changes anything on screen, so the heart never turns red and
 * nothing is sent or posted.
 *
 * <p>Ghost mode is the master switch's own reading: on while every switch it turns is saved on.
 * The block only answers with the switch on, HushGram not paused and its settings read. Once those
 * say block, a failure reading Ghost mode blocks too, since letting a like out is the one thing the
 * switch is there to stop.
 */
public final class GhostActions {
    /** What's counted each time a like, a reply, a share link, a comment or a post is held back. */
    static final String BLOCKED_LIKE = "like blocked in Ghost mode";
    static final String BLOCKED_REPLY = "reply blocked in Ghost mode";
    static final String BLOCKED_SHARE = "share link blocked in Ghost mode";
    static final String BLOCKED_COMMENT = "comment blocked in Ghost mode";
    static final String BLOCKED_POST = "post blocked in Ghost mode";

    private GhostActions() {
    }

    /** Injected first thing where a like or super like starts. True returns before Instagram does anything. Never throws. */
    public static boolean like() {
        if (!blocks("like")) return false;
        HookStatus.counted(FamilyNames.GHOST_ACTIONS, BLOCKED_LIKE);
        Utils.showToastShort(L10n.t("Likes are off in Ghost mode."));
        return true;
    }

    /** Injected first thing where a story reply or quick reaction starts. True returns before it's sent. Never throws. */
    public static boolean reply() {
        if (!blocks("reply")) return false;
        HookStatus.counted(FamilyNames.GHOST_ACTIONS, BLOCKED_REPLY);
        Utils.showToastShort(L10n.t("Replies are off in Ghost mode."));
        return true;
    }

    /** Injected first thing in Copy link and where a share link is made. True returns before the link is asked for. Never throws. */
    public static boolean share() {
        if (!blocks("share link")) return false;
        HookStatus.counted(FamilyNames.GHOST_ACTIONS, BLOCKED_SHARE);
        Utils.showToastShort(L10n.t("Share links are off in Ghost mode."));
        return true;
    }

    /** Injected first thing where the comment sheet posts a comment. True returns before it's posted. Never throws. */
    public static boolean comment() {
        if (!blocks("comment")) return false;
        HookStatus.counted(FamilyNames.GHOST_ACTIONS, BLOCKED_COMMENT);
        Utils.showToastShort(L10n.t("Comments are off in Ghost mode."));
        return true;
    }

    /** Injected first thing in Your story, Close friends and a post's or reel's Share. True returns before anything is posted. Never throws. */
    public static boolean post() {
        if (!blocks("post")) return false;
        HookStatus.counted(FamilyNames.GHOST_ACTIONS, BLOCKED_POST);
        Utils.showToastShort(L10n.t("Posting is off in Ghost mode."));
        return true;
    }

    /** Whether the switch is on, HushGram isn't paused and Ghost mode is on. */
    static boolean blocks(String what) {
        try {
            HookStatus.invoked(FamilyNames.GHOST_ACTIONS);
            if (!Utils.settingsReady() || HushgramPause.isPaused() || !Settings.BLOCK_GHOST_ACTIONS.get()) return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GHOST_ACTIONS, what, failure);
            return false;
        }
        try {
            return ghostModeOn(PatchFamily.inThisBuild());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GHOST_ACTIONS, what, failure);
            Logger.printException(() -> "Ghost mode: could not read it, so the " + what + " is held back", failure);
            return true;
        }
    }

    /** Whether Ghost mode is on in [build], as its master switch and the inbox's long press read it. */
    static boolean ghostModeOn(Set<PatchFamily> build) {
        return GhostMode.on(GhostMode.picked(GhostMode.switches(build)));
    }
}

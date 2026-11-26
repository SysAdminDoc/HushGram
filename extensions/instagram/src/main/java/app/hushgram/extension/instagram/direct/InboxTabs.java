/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Primary and General tabs" patch (#832).
 *
 * <p>Professional accounts get two tabs at the top of their messages, Primary and General, with
 * requests beside them. Instagram draws them for any account a server test lets in, and reads that
 * test when your messages are set up, in the main activity's start-up and in the gates that decide
 * the tabs' folders. The patch answers every read of it through {@link #tabs(int)}, so all of them
 * agree, the way they would for an account in the test. Which chats go in which tab stays the
 * server's call.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answer goes through untouched, so a professional account keeps
 * its tabs.
 */
public final class InboxTabs {
    private InboxTabs() {
    }

    /** The test's answer: Instagram's ([answer] nonzero for yes), or yes while the switch is on. Never throws. */
    public static boolean tabs(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FamilyNames.INBOX_TABS);
            return instagram || on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.INBOX_TABS, "inbox tabs flag", failure);
            return instagram;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.INBOX_TABS.get();
    }
}

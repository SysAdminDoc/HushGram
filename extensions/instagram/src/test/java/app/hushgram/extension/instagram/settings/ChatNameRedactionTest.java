/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;
import android.widget.ListView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.instagram.direct.ChatLocks;
import app.hushgram.extension.instagram.direct.HiddenChats;
import app.hushgram.extension.instagram.direct.MessagesLockForTests;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * Locking, unlocking, hiding and showing a chat again each toast the chat's name, and with Debug
 * logging on every toast is written to the diagnostics buffer. The names went into an exported
 * report as they were (audit 2026-10-12). They go in isolated now, which the report leaves out,
 * including a name holding a line break or an isolate mark of its own that would have ended the
 * isolate early. Run through the real rows and the real export, in English and in German.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ChatNameRedactionTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String ACCOUNT = "17841400000000001";

    /** Synthetic names, each word of them found nowhere else in a report. */
    private static final String[] NAMES = {
            "Qorvath and Brindle",
            "Zentha⁩ Mollusk",
            "Pyrrho\rCastellan",
            "Ulvana Drummock",
            "Fennick⁦Ostrava⁩Plumb",
    };
    private static final List<String> WORDS = Arrays.asList(
            "Qorvath", "Brindle", "Zentha", "Mollusk", "Pyrrho", "Castellan", "Ulvana", "Drummock",
            "Fennick", "Ostrava", "Plumb");

    private final List<Runnable[]> asks = new ArrayList<>();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before
    public void setup() throws Exception {
        MessagesLockForTests.reset();
        MessagesLockForTests.askWith(asks);
        MessagesLockForTests.signIn(ACCOUNT);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.MESSAGES_LOCK);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        BaseSettings.DEBUG.save(true);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        for (int i = 0; i < NAMES.length; i++) {
            assertTrue(ChatLocks.add(ACCOUNT, "34000000000000" + i, NAMES[i]));
            assertTrue(HiddenChats.add(ACCOUNT, "35000000000000" + i, NAMES[i]));
        }
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, page, "redaction-page").commitNow();
        settle();
        LogBufferManager.clearLogBuffer();
        settle();
    }

    @After
    public void close() throws Exception {
        controller.close();
        settle();
        LogBufferManager.clearLogBuffer();
        MessagesLockForTests.signOut();
        MessagesLockForTests.reset();
        Settings.LOCKED_CHATS.resetToDefault();
        Settings.HIDDEN_CHATS.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        PatchFamily.inBuildForTests = null;
    }

    @Test
    public void theFourToastsLeaveNoChatNameInAReport() throws Exception {
        String report = runEveryAction();
        for (String done : new String[]{"is locked", "is unlocked", "is hidden", "is shown again"}) {
            assertTrue("no toast saying \"" + done + "\" reached the report, so this saw nothing",
                    report.contains("[name omitted] " + done));
        }
        for (String word : WORDS) assertFalse(word + " is in the report", report.contains(word));
    }

    @Test
    @Config(qualifiers = "de")
    public void theSameInGerman() throws Exception {
        String report = runEveryAction();
        for (String done : new String[]{"ist gesperrt", "ist entsperrt", "ist versteckt", "wird wieder angezeigt"}) {
            assertTrue("no toast saying \"" + done + "\" reached the report, so this saw nothing",
                    report.contains("[name omitted] " + done));
        }
        for (String word : WORDS) assertFalse(word + " is in the report", report.contains(word));
    }

    /** The toast you see still names the chat, as one line, with nothing of its own breaking out. */
    @Test
    public void theToastStillNamesTheChat() throws Exception {
        AlertDialog locked = open(HushgramPreferenceFragment.LOCKED_CHATS_ROW);
        toggle(locked, indexOf(locked, "Zentha"));
        String shown = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(shown, shown.equals(L10n.isolate("Zentha Mollusk") + " is locked")
                || shown.equals(L10n.isolate("Zentha Mollusk") + " is unlocked"));
        toggle(locked, indexOf(locked, "Pyrrho"));
        shown = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(shown, shown.startsWith(L10n.isolate("Pyrrho Castellan") + " is "));
        assertEquals("one isolate, closed once", 1, shown.chars().filter(c -> c == 0x2069).count());
    }

    /** Each chat in both lists checked and unchecked, which locks, unlocks, hides and shows it again. */
    private String runEveryAction() throws Exception {
        for (String row : new String[]{HushgramPreferenceFragment.LOCKED_CHATS_ROW, HushgramPreferenceFragment.HIDDEN_CHATS_ROW}) {
            AlertDialog list = open(row);
            for (int i = 0; i < NAMES.length; i++) {
                toggle(list, i);
                toggle(list, i);
            }
            list.dismiss();
            settle();
        }
        return LogBufferManager.buildExportText();
    }

    private AlertDialog open(String key) throws Exception {
        Preference row = page.getPreferenceScreen().findPreference(key);
        assertNotNull("no row " + key, row);
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        answerAsks();
        AlertDialog dialog = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no list opened from " + key, dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    /** Where the list shows the chat whose name holds [word]. */
    private static int indexOf(AlertDialog list, String word) {
        ListView items = list.getListView();
        for (int i = 0; i < items.getAdapter().getCount(); i++) {
            if (String.valueOf(items.getAdapter().getItem(i)).contains(word)) return i;
        }
        throw new AssertionError("no " + word + " in the list");
    }

    /** A tap on a chat in the list: checked if it wasn't, unchecked if it was. */
    private void toggle(AlertDialog list, int position) throws Exception {
        ListView items = list.getListView();
        items.performItemClick(null, position, items.getItemIdAtPosition(position));
        answerAsks();
    }

    /** Says yes to whatever the lock asked, as the phone's lock would for you. */
    private void answerAsks() throws Exception {
        settle();
        while (!asks.isEmpty()) {
            asks.remove(0)[0].run();
            settle();
        }
    }

    private static void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }
}

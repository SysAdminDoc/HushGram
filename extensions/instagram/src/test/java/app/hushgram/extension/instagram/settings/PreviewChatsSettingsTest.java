/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.SwitchPreference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import app.hushgram.extension.instagram.direct.ChatPreview;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Preview chats switch (#477): on the Messages page, off to start, and read each time the menu opens. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class PreviewChatsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.PREVIEW_CHATS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.PREVIEW_CHATS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.THREAD_SEEN));
        assertNull(page.getPreferenceScreen().findPreference(Settings.PREVIEW_CHATS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.READ_WITHOUT_SEEN_RECEIPT.key));
    }
    /** Without the other Messages patches, Messages still opens for this switch alone, and it starts off. */
    @Test public void aloneItStillGetsMessagesAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.PREVIEW_CHATS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PREVIEW_CHATS.key);
        assertNotNull(row);
        assertEquals("Messages", row.getParent().getTitle().toString());
        assertEquals("Preview chats", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("tap Preview"));
        assertFalse(row.isChecked());
        assertFalse(Settings.PREVIEW_CHATS.rebootApp);
    }
    /** Flipping the switch reaches the menu the next time it opens, with no restart. */
    @Test public void flippingItReachesTheNextMenu() throws Exception {
        open(EnumSet.of(PatchFamily.PREVIEW_CHATS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PREVIEW_CHATS.key);
        row.setChecked(true);
        assertTrue(Settings.PREVIEW_CHATS.get());
        assertEquals(Arrays.asList(Menu.THREAD_LEVEL_DEBUG, Menu.DELETE), offered());
        row.setChecked(false);
        assertEquals(List.of(Menu.DELETE), offered());
    }

    /** Instagram's chat menu actions, with the debug row the preview borrows. */
    enum Menu { THREAD_LEVEL_DEBUG, DELETE }

    private static List<Object> offered() {
        List<Object> rows = new ArrayList<>(List.of(Menu.DELETE));
        ChatPreview.offer(rows, Menu.class);
        return rows;
    }
}

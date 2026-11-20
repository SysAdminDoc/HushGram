/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.widget.ListView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.instagram.direct.CollectionLocks;
import app.hushgram.extension.instagram.direct.MessagesLockForTests;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Locked collections sits under Locked chats and works the way it does: the collection you opened
 * last is offered, a check locks it at once, and unchecking one asks the lock first (#678).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class LockedCollectionsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String ACCOUNT = "17841400000000001";

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
        Settings.LOCK_MESSAGES.save(false);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, page, "collections-page").commitNow();
        settle();
    }

    @After
    public void close() throws Exception {
        controller.close();
        settle();
        MessagesLockForTests.signOut();
        MessagesLockForTests.reset();
        Settings.LOCKED_COLLECTIONS.resetToDefault();
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        PatchFamily.inBuildForTests = null;
    }

    @Test
    public void theRowSitsRightUnderLockedChats() {
        Preference row = row(HushgramPreferenceFragment.LOCKED_COLLECTIONS_ROW);
        Preference chats = row(HushgramPreferenceFragment.LOCKED_CHATS_ROW);
        assertNotNull(row);
        assertNotNull(chats);
        assertEquals("Locked collections", row.getTitle().toString());
        PreferenceGroup group = row.getParent();
        assertSame("not in Locked chats' group", chats.getParent(), group);
        int at = -1;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (group.getPreference(i) == chats) at = i;
        }
        assertSame(row, group.getPreference(at + 1));
        assertFalse("kept in a backup", ConfigurationBackup.eligible().containsKey(Settings.LOCKED_COLLECTIONS.key));
    }

    @Test
    public void notBuiltNoRow() throws Exception {
        controller.close();
        PatchFamily.inBuildForTests = Collections.emptySet();
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, page, "collections-page").commitNow();
        settle();
        assertNull(row(HushgramPreferenceFragment.LOCKED_COLLECTIONS_ROW));
    }

    @Test
    public void theCollectionOpenedLastIsOfferedLockedAndUnlockedOnlyOnceTheLockSaysSo() throws Exception {
        assertFalse(MessagesLockForTests.wantsCollectionName());
        AlertDialog empty = open();
        assertEquals("Long press a collection in Saved to lock it, or open one and come back here.",
                String.valueOf(Shadows.shadowOf(empty).getMessage()));
        assertTrue("opening the list didn't start looking for a collection's name", MessagesLockForTests.wantsCollectionName());
        empty.dismiss();
        settle();

        MessagesLockForTests.collectionShown("Trips");
        AlertDialog offered = open();
        ListView items = offered.getListView();
        assertEquals(1, items.getAdapter().getCount());
        assertEquals("Trips (opened last)", String.valueOf(items.getAdapter().getItem(0)));
        assertFalse(items.isItemChecked(0));

        toggle(offered, 0);
        assertTrue(CollectionLocks.listed(ACCOUNT, "Trips"));
        assertEquals(L10n.isolate("Trips") + " is locked", String.valueOf(ShadowToast.getTextOfLatestToast()));
        assertEquals("locking one asked", 0, asks.size());
        offered.dismiss();
        settle();

        AlertDialog listed = open();
        items = listed.getListView();
        assertEquals(1, items.getAdapter().getCount());
        assertEquals("Trips", String.valueOf(items.getAdapter().getItem(0)));
        assertTrue(items.isItemChecked(0));

        items.performItemClick(null, 0, items.getItemIdAtPosition(0));
        settle();
        assertEquals("unchecking it didn't ask", 1, asks.size());
        assertTrue("unlocked before the lock said so", CollectionLocks.listed(ACCOUNT, "Trips"));
        assertTrue(items.isItemChecked(0));

        asks.remove(0)[0].run();
        settle();
        assertFalse(CollectionLocks.listed(ACCOUNT, "Trips"));
        assertFalse(items.isItemChecked(0));
        assertEquals(L10n.isolate("Trips") + " is unlocked", String.valueOf(ShadowToast.getTextOfLatestToast()));
    }

    private Preference row(String key) {
        return page.getPreferenceScreen().findPreference(key);
    }

    private AlertDialog open() throws Exception {
        Preference row = row(HushgramPreferenceFragment.LOCKED_COLLECTIONS_ROW);
        assertNotNull("no Locked collections row", row);
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        settle();
        AlertDialog dialog = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no list opened", dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private void toggle(AlertDialog list, int position) throws Exception {
        ListView items = list.getListView();
        items.performItemClick(null, position, items.getItemIdAtPosition(position));
        settle();
    }

    private static void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }
}

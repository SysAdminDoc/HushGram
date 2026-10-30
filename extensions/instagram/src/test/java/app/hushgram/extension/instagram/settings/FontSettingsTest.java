/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.preference.ListPreference;
import android.preference.PreferenceGroup;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.misc.FontChoice;
import app.hushgram.extension.instagram.misc.Fonts;
import app.hushgram.extension.instagram.misc.FontsForTests;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.WorkerPoolForTests;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.FailingStore;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.preference.SharedPrefCategory;

/** The Font row: under Layout, Instagram's font to start, and a file chosen only once it loads as a font. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FontSettingsTest {
    private static final Uri DOCUMENT = Uri.parse("content://test-documents/font.ttf");
    private static final byte[] TRUE_TYPE = {0x00, 0x01, 0x00, 0x00, 0x00, 0x0c, 0x00, (byte) 0x80};

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final SettingsLockTest.Phone phone = new SettingsLockTest.Phone();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.FONT.resetToDefault();
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        SettingsLock.clocks = phone;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        FontsForTests.reset();
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.FONT.resetToDefault();
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        SettingsLock.clocks = SettingsLock.PHONE;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        FontsForTests.reset();
        File kept = FontsForTests.kept();
        if (kept.exists()) kept.delete();
        File[] leftovers = kept.getParentFile().listFiles();
        if (leftovers != null) for (File file : leftovers) if (file.isFile()) file.delete();
    }

    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private ListPreference row() {
        return (ListPreference) page.getPreferenceScreen().findPreference(Settings.FONT.key);
    }

    @Test public void missingPatchHasNoFontRow() throws Exception {
        open(EnumSet.of(PatchFamily.EMOJI_STYLE));
        assertNull(row());
    }

    /** The row alone opens Layout, first there, with the three choices and Instagram's own to start. */
    @Test public void theRowStartsOnInstagramsFontUnderLayout() throws Exception {
        open(EnumSet.of(PatchFamily.FONT));
        ListPreference row = row();
        assertNotNull(row);
        PreferenceGroup layout = row.getParent();
        assertEquals("Layout", layout.getTitle().toString());
        assertEquals(Settings.FONT.key, layout.getPreference(0).getKey());
        assertEquals("Font", row.getTitle().toString());
        assertEquals("Instagram's font", row.getEntries()[0].toString());
        assertEquals("Your phone's font", row.getEntries()[1].toString());
        assertEquals("A font file", row.getEntries()[2].toString());
        assertEquals(FontChoice.INSTAGRAM.name(), row.getValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Instagram's own font."));
        assertEquals("Font", PatchFamily.FONT.patchName);
        assertTrue(PatchFamily.FONT.switches.isEmpty());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FONT.key));
    }

    /** With its neighbors, the row sits after Remove the empty space at the bottom and before the emoji switch. */
    @Test public void theRowSitsBeforeTheEmojiSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.BOTTOM_SPACE, PatchFamily.FONT, PatchFamily.EMOJI_STYLE));
        PreferenceGroup layout = row().getParent();
        String[] keys = new String[layout.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = layout.getPreference(i).getKey();
        assertArrayEquals(new String[] {Settings.REMOVE_BOTTOM_SPACE.key, Settings.FONT.key, Settings.NOTO_EMOJI.key,
                LayoutCategory.PURE_BLACK_HINT_ROW}, keys);
    }

    /** The phone's font is an ordinary choice. Paused, Instagram's own answers and the saved choice stays. */
    @Test public void thePhonesFontIsAChoiceThatPauseSetsAside() throws Exception {
        open(EnumSet.of(PatchFamily.FONT));
        ListPreference row = row();
        assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, FontChoice.SYSTEM.name()));
        row.setValue(FontChoice.SYSTEM.name());
        ShadowLooper.idleMainLooper();
        assertEquals(FontChoice.SYSTEM, Settings.FONT.savedValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("your phone's font"));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.get());
        assertEquals(FontChoice.SYSTEM, Settings.FONT.savedValue());
    }

    /** A font file opens the picker and changes nothing until a file that loads comes back. */
    @Test public void aFontFileIsChosenOnlyOnceItLoads() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        ListPreference row = row();

        assertFalse(row.getOnPreferenceChangeListener().onPreferenceChange(row, FontChoice.FILE.name()));
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        assertNotNull(picked);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picked.intent.getAction());
        assertTrue(picked.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.savedValue());

        Shadows.shadowOf(controller.get().getContentResolver()).registerInputStream(DOCUMENT,
                new ByteArrayInputStream("not a font".getBytes()));
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
        finish();
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.savedValue());
        assertEquals(FontChoice.INSTAGRAM.name(), row.getValue());
        assertEquals("That file isn't a font HushGram can use. Your font hasn't changed.", ShadowToast.getTextOfLatestToast());
        assertFalse(FontsForTests.kept().exists());

        row.getOnPreferenceChangeListener().onPreferenceChange(row, FontChoice.FILE.name());
        picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        Shadows.shadowOf(controller.get().getContentResolver()).registerInputStream(DOCUMENT, new ByteArrayInputStream(TRUE_TYPE));
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
        finish();
        assertEquals(FontChoice.FILE, Settings.FONT.savedValue());
        assertEquals(FontChoice.FILE.name(), row.getValue());
        assertArrayEquals(TRUE_TYPE, Files.readAllBytes(FontsForTests.kept().toPath()));
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("font file you picked"));
    }

    /** A canceled picker changes nothing and says nothing. */
    @Test public void aCanceledPickerChangesNothing() throws Exception {
        open(EnumSet.of(PatchFamily.FONT));
        ListPreference row = row();
        row.getOnPreferenceChangeListener().onPreferenceChange(row, FontChoice.FILE.name());
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_CANCELED, null);
        finish();
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.savedValue());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void theProviderMustCloseBeforeTheLiveFontIsReplaced() throws Exception {
        byte[] before = font(1), after = font(2);
        keep(before);
        Settings.FONT.save(FontChoice.FILE);
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        GateInputStream input = new GateInputStream(after, false);
        try {
            receive(pick(), input);
            input.awaitEntered();
            assertArrayEquals("the provider has not finished its successful close", before,
                    Files.readAllBytes(FontsForTests.kept().toPath()));
        } finally {
            input.release.countDown();
        }
        finish();
        assertArrayEquals(after, Files.readAllBytes(FontsForTests.kept().toPath()));
        assertOnlyLiveFontRemains();
    }

    @Test public void aProviderCloseFailureKeepsThePreviousFont() throws Exception {
        byte[] before = font(1);
        keep(before);
        Settings.FONT.save(FontChoice.FILE);
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        receive(pick(), new ByteArrayInputStream(font(2)) {
            @Override public void close() throws IOException {
                throw new IOException("injected provider close failure");
            }
        });
        finish();
        assertArrayEquals(before, Files.readAllBytes(FontsForTests.kept().toPath()));
        assertEquals(FontChoice.FILE, Settings.FONT.savedValue());
        assertEquals("That file isn't a font HushGram can use. Your font hasn't changed.",
                ShadowToast.getTextOfLatestToast());
        assertOnlyLiveFontRemains();
    }

    @Test public void aFontResultArrivingAfterTheSettingsRelockChangesNothing() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        openTimedLock();
        ShadowActivity.IntentForResult picked = pick();
        phone.pass(SettingsLock.OPEN_MS);
        receive(picked, new ByteArrayInputStream(TRUE_TYPE));
        finish();
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.savedValue());
        assertFalse(FontsForTests.kept().exists());
        assertEquals("These settings are locked. Ask to unlock first.", ShadowToast.getTextOfLatestToast());
        assertOnlyLiveFontRemains();
    }

    @Test public void aFontCopyCrossingTheSettingsDeadlineChangesNothing() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        openTimedLock();
        GateInputStream input = new GateInputStream(TRUE_TYPE, false);
        try {
            receive(pick(), input);
            input.awaitEntered();
            phone.pass(SettingsLock.OPEN_MS);
        } finally {
            input.release.countDown();
        }
        finish();
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.savedValue());
        assertFalse(FontsForTests.kept().exists());
        assertEquals("These settings are locked. Ask to unlock first.", ShadowToast.getTextOfLatestToast());
        assertOnlyLiveFontRemains();
    }

    @Test public void aNewerSavedFontChoiceWinsOverThePendingCopy() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        GateInputStream input = new GateInputStream(TRUE_TYPE, true);
        try {
            receive(pick(), input);
            input.awaitEntered();
            assertTrue(Settings.FONT.save(FontChoice.SYSTEM));
        } finally {
            input.release.countDown();
        }
        finish();
        assertEquals("a completed older import must not replace a newer saved choice", FontChoice.SYSTEM,
                Settings.FONT.savedValue());
        assertEquals(FontChoice.SYSTEM.name(), row().getValue());
        assertOnlyLiveFontRemains();
    }

    @Test public void reopeningSettingsCannotStartASecondCopyOverTheFirst() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        GateInputStream input = new GateInputStream(TRUE_TYPE, true);
        try {
            receive(pick(), input);
            input.awaitEntered();
            controller.get().getFragmentManager().beginTransaction().remove(page).commitNow();
            page = new HushgramPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
            ShadowLooper.idleMainLooper();
            assertFalse("the active copy still owns the font row", row().isEnabled());
            assertFalse(row().getOnPreferenceChangeListener().onPreferenceChange(row(), FontChoice.FILE.name()));
            assertNull("a second picker would race the pending font", Shadows.shadowOf(controller.get())
                    .getNextStartedActivityForResult());
        } finally {
            input.release.countDown();
        }
        finish();
        assertEquals(FontChoice.FILE, Settings.FONT.savedValue());
        assertTrue(row().isEnabled());
        assertArrayEquals(TRUE_TYPE, Files.readAllBytes(FontsForTests.kept().toPath()));
        assertOnlyLiveFontRemains();
    }

    @Test public void replacingTheRunningFileOffersRestartWithoutChangingTheChoice() throws Exception {
        keep(font(1));
        Settings.FONT.save(FontChoice.FILE);
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        Fonts.prismTypeface(Typeface.DEFAULT);
        assertFalse(page.restartActionAvailable());
        receive(pick(), new ByteArrayInputStream(font(2)));
        finish();
        assertEquals(FontChoice.FILE, Settings.FONT.savedValue());
        assertTrue("the running Typeface is still the previous file", page.restartActionAvailable());
        assertOnlyLiveFontRemains();
    }

    @Test public void aFullWorkerQueueDoesNotLeaveTheFontImportOwned() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        try (WorkerPoolForTests full = WorkerPoolForTests.fill()) {
            receive(pick(), new ByteArrayInputStream(TRUE_TYPE));
            ShadowLooper.idleMainLooper();
            assertEquals(FontChoice.INSTAGRAM, Settings.FONT.savedValue());
        }
        finish();
        assertTrue(row().isEnabled());
        ShadowActivity.IntentForResult retried = pick();
        Shadows.shadowOf(controller.get()).receiveResult(retried.intent, Activity.RESULT_CANCELED, null);
        finish();
        assertOnlyLiveFontRemains();
    }

    @Test public void aFailedFontChoiceSaveRestoresThePreviousFileAndChoice() throws Exception {
        byte[] before = font(1);
        keep(before);
        assertTrue(Settings.FONT.save(FontChoice.SYSTEM));
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        try (FailingStore store = FailingStore.install(FailingStore.Fault.LOST)) {
            receive(pick(), new ByteArrayInputStream(font(2)));
            finish();
            assertTrue("the typed setting write and its rollback must be exercised", store.editors.get() >= 2);
        }
        assertEquals(FontChoice.SYSTEM, Settings.FONT.savedValue());
        assertEquals(FontChoice.SYSTEM.name(), row().getValue());
        assertArrayEquals(before, Files.readAllBytes(FontsForTests.kept().toPath()));
        assertFalse(page.restartActionAvailable());
        assertEquals("The setting couldn't finish updating. Its saved value is shown.", ShadowToast.getTextOfLatestToast());
        assertOnlyLiveFontRemains();
    }

    @Test public void aRefusedFontReceiptRestoresTheChoiceThatThisImportChanged() throws Exception {
        byte[] before = font(1);
        keep(before);
        assertTrue(Settings.FONT.save(FontChoice.SYSTEM));
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        try (JournalAfterCommit receipt = new JournalAfterCommit(Settings.FONT, FontChoice.FILE.name(),
                FontsForTests.kept().getParentFile(), true)) {
            receive(pick(), new ByteArrayInputStream(font(2)));
            finish();
            assertTrue("the real deletion barrier was acquired after the option persisted", receipt.observed && receipt.deleteDenied);
            assertEquals(FontChoice.FILE.name(), receipt.committed);
            assertArrayEquals(before, Files.readAllBytes(FontsForTests.kept().toPath()));
            assertEquals(FontChoice.SYSTEM, Settings.FONT.savedValue());
            assertEquals(FontChoice.SYSTEM, Setting.preferences.getEnum(Settings.FONT.key, FontChoice.INSTAGRAM));
            assertEquals(FontChoice.SYSTEM.name(), row().getValue());
            assertFalse(page.restartActionAvailable());
            assertEquals("The setting couldn't finish updating. Its saved value is shown.", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void aReleasedFontReceiptKeepsTheNewFileAndChoice() throws Exception {
        keep(font(1));
        assertTrue(Settings.FONT.save(FontChoice.SYSTEM));
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        try (JournalAfterCommit receipt = new JournalAfterCommit(Settings.FONT, FontChoice.FILE.name(),
                FontsForTests.kept().getParentFile(), false)) {
            receive(pick(), new ByteArrayInputStream(font(2)));
            finish();
            assertTrue(receipt.observed && receipt.deleteDenied);
            assertEquals(FontChoice.FILE.name(), receipt.committed);
            assertEquals(FontChoice.FILE, Settings.FONT.savedValue());
            assertEquals(FontChoice.FILE.name(), row().getValue());
            assertArrayEquals(font(2), Files.readAllBytes(FontsForTests.kept().toPath()));
            assertEquals("Font saved. Restart Instagram to see it.", ShadowToast.getTextOfLatestToast());
            assertOnlyLiveFontRemains();
        }
    }

    @Test public void aRefusedChoiceRollbackShowsTheValueThatIsStillSaved() throws Exception {
        byte[] before = font(1);
        keep(before);
        assertTrue(Settings.FONT.save(FontChoice.SYSTEM));
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        try (FailingStore refusal = FailingStore.loseNextStringCommit(Settings.FONT.key, FontChoice.SYSTEM.name());
             JournalAfterCommit receipt = new JournalAfterCommit(Settings.FONT, FontChoice.FILE.name(),
                     FontsForTests.kept().getParentFile(), true)) {
            receive(pick(), new ByteArrayInputStream(font(2)));
            finish();
            assertTrue(receipt.observed && receipt.deleteDenied);
            assertTrue("the prior-choice restore was attempted and refused", refusal.refusedStringCommit.get());
            assertArrayEquals(before, Files.readAllBytes(FontsForTests.kept().toPath()));
            assertEquals(FontChoice.FILE, Settings.FONT.savedValue());
            assertEquals(FontChoice.FILE, Setting.preferences.getEnum(Settings.FONT.key, FontChoice.INSTAGRAM));
            assertEquals(FontChoice.FILE.name(), row().getValue());
            assertFalse(Fonts.restartPending());
            assertEquals("The setting couldn't finish updating. Its saved value is shown.", ShadowToast.getTextOfLatestToast());
        }
    }

    private void openTimedLock() {
        assertTrue(SettingsLock.lock(1));
        assertTrue(SettingsLock.ask());
        phone.pass(3_600_000L);
        page.showLock();
        assertEquals(SettingsLock.State.OPEN, SettingsLock.state());
    }

    private ShadowActivity.IntentForResult pick() {
        assertFalse(row().getOnPreferenceChangeListener().onPreferenceChange(row(), FontChoice.FILE.name()));
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        assertNotNull("the font file picker did not start", picked);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picked.intent.getAction());
        return picked;
    }

    private void receive(ShadowActivity.IntentForResult picked, InputStream input) {
        Shadows.shadowOf(controller.get().getContentResolver()).registerInputStream(DOCUMENT, input);
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
    }

    private static byte[] font(int marker) {
        byte[] bytes = Arrays.copyOf(TRUE_TYPE, TRUE_TYPE.length + 1);
        bytes[bytes.length - 1] = (byte) marker;
        return bytes;
    }

    private static void keep(byte[] bytes) throws Exception {
        File target = FontsForTests.kept();
        assertTrue(target.getParentFile().isDirectory() || target.getParentFile().mkdirs());
        Files.write(target.toPath(), bytes);
    }

    private static void assertOnlyLiveFontRemains() {
        File target = FontsForTests.kept();
        File[] files = target.getParentFile().listFiles();
        if (files == null) return;
        for (File file : files) assertEquals("an import left a temporary file", target, file);
    }

    /** Acquires the real file barrier only after the requested preference commit has landed. */
    static final class JournalAfterCommit implements AutoCloseable {
        private final Field field;
        private final SharedPreferences store;
        private FileChannel held;
        volatile boolean observed, deleteDenied;
        volatile Object committed;

        JournalAfterCommit(Setting<?> setting, Object value, File directory, boolean blockAcceptance) throws Exception {
            assumeTrue(System.getProperty("os.name").startsWith("Windows"));
            OpenOption denyDelete = (OpenOption) Class.forName("com.sun.nio.file.ExtendedOpenOption")
                    .getField("NOSHARE_DELETE").get(null);
            field = SharedPrefCategory.class.getDeclaredField("preferences");
            field.setAccessible(true);
            store = (SharedPreferences) field.get(Setting.preferences);
            field.set(Setting.preferences, Proxy.newProxyInstance(store.getClass().getClassLoader(),
                    new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("edit")) return call(method, store, args);
                        SharedPreferences.Editor editor = store.edit();
                        boolean[] matches = {false};
                        return Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, edit, values) -> {
                                    String name = edit.getName();
                                    if (name.equals("clear")) matches[0] = false;
                                    else if ((name.startsWith("put") || name.equals("remove")) && setting.key.equals(values[0])) {
                                        matches[0] = name.startsWith("put") && value.equals(values[1]);
                                    }
                                    Object result = call(edit, editor, values);
                                    if (name.equals("commit") && Boolean.TRUE.equals(result) && matches[0] && !observed) {
                                        committed = store.getAll().get(setting.key);
                                        File[] journals = directory.listFiles((parent, file) -> file.endsWith(".font-transaction.tmp"));
                                        assertNotNull(journals);
                                        assertEquals("the import must own one journal", 1, journals.length);
                                        held = FileChannel.open(journals[0].toPath(), StandardOpenOption.READ, denyDelete);
                                        deleteDenied = !journals[0].delete();
                                        observed = true;
                                        if (!blockAcceptance) {
                                            held.close();
                                            held = null;
                                        }
                                    }
                                    return result instanceof SharedPreferences.Editor ? editorProxy : result;
                                });
                    }));
        }

        @Override public void close() throws Exception {
            try {
                if (held != null) held.close();
            } finally {
                field.set(Setting.preferences, store);
            }
        }

        private static Object call(Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    private static final class GateInputStream extends ByteArrayInputStream {
        private final boolean holdRead;
        private boolean held;
        private final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        GateInputStream(byte[] bytes, boolean holdRead) {
            super(bytes);
            this.holdRead = holdRead;
        }

        @Override public synchronized int read(byte[] buffer, int offset, int length) {
            if (holdRead && !held) {
                held = true;
                hold();
            }
            return super.read(buffer, offset, length);
        }

        @Override public void close() throws IOException {
            if (!holdRead) hold();
            super.close();
        }

        void awaitEntered() throws Exception {
            assertTrue("the font worker never reached the provider gate", entered.await(10, TimeUnit.SECONDS));
        }

        private void hold() {
            entered.countDown();
            try {
                assertTrue("the test never released the provider", release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    private static void finish() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }
}

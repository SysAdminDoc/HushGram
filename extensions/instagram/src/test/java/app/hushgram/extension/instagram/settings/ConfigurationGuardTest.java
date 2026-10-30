/* Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import app.hushgram.extension.shared.SettingsContextRule;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ConfigurationGuardTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before public void prepare() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        ConfigurationBackup.forgetUndo();
        Settings.HIDE_ADS.save(true);
    }

    @After public void restore() {
        ConfigurationBackup.forgetUndo();
        Settings.HIDE_ADS.resetToDefault();
        PatchFamily.inBuildForTests = null;
    }

    @Test public void aRefusedRestorePreservesChoicesAndThePreviousUndo() throws Exception {
        ConfigurationBackup.restore(file(false));
        long token = ConfigurationBackup.undoToken();
        long deadline = ConfigurationBackup.undoDeadline(token);
        try {
            ConfigurationBackup.restore(file(true), () -> false);
            fail("a refused restore ran");
        } catch (ConfigurationBackup.Held expected) { }
        assertFalse(Settings.HIDE_ADS.savedValue());
        assertEquals(token, ConfigurationBackup.undoToken());
        assertEquals(deadline, ConfigurationBackup.undoDeadline(token));
        assertTrue(ConfigurationBackup.undo(token, () -> true).applied > 0);
        assertTrue(Settings.HIDE_ADS.savedValue());
    }

    @Test public void aRefusedUndoKeepsItsTokenAndCanBeRetried() throws Exception {
        ConfigurationBackup.restore(file(false));
        long token = ConfigurationBackup.undoToken();
        long deadline = ConfigurationBackup.undoDeadline(token);
        try {
            ConfigurationBackup.undo(token, () -> false);
            fail("a refused Undo ran");
        } catch (ConfigurationBackup.Held expected) { }
        assertFalse(Settings.HIDE_ADS.savedValue());
        assertEquals(token, ConfigurationBackup.undoToken());
        assertEquals(deadline, ConfigurationBackup.undoDeadline(token));
        assertNotNull(ConfigurationBackup.undo(token, () -> true));
        assertTrue(Settings.HIDE_ADS.savedValue());
    }

    @Test public void validationFinishesBeforeTheFinalPermissionCheck() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try {
            ConfigurationBackup.restore("invalid".getBytes(StandardCharsets.UTF_8), () -> {
                checks.incrementAndGet();
                return false;
            });
            fail("invalid input was accepted");
        } catch (ConfigurationBackup.Held wrong) {
            fail("invalid input reached mutation authorization");
        } catch (IOException expected) { }
        assertEquals(0, checks.get());
        assertTrue(Settings.HIDE_ADS.savedValue());
    }

    @Test public void unreadablePermissionHoldsRatherThanGrantingTheWrite() throws Exception {
        try {
            ConfigurationBackup.restore(file(false), () -> { throw new IllegalStateException("unavailable"); });
            fail("an unreadable permit granted a write");
        } catch (ConfigurationBackup.Held expected) { }
        assertTrue(Settings.HIDE_ADS.savedValue());
        assertFalse(ConfigurationBackup.canUndo());
    }

    private static byte[] file(boolean value) throws Exception {
        JSONObject entry = new JSONObject().put("type", "boolean").put("value", value);
        return new JSONObject().put("project", "HushGram").put("schema", 1)
                .put("settings", new JSONObject().put(Settings.HIDE_ADS.key, entry))
                .toString().getBytes(StandardCharsets.UTF_8);
    }
}

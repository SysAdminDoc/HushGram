/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide comments' switches: under Comments, off to start, and Hide Reply on comments right after Hide comments. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class HideCommentsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_COMMENTS.resetToDefault();
        Settings.HIDE_COMMENT_REPLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        PatchFamily.commentReplyForTests = null;
        Settings.HIDE_COMMENTS.resetToDefault();
        Settings.HIDE_COMMENT_REPLY.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.HIDE_COMMENTS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoRow() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENTS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_REPLY.key));
    }
    @Test public void theSwitchSitsUnderCommentsAndStartsOff() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENTS.key);
        assertNotNull(row);
        assertEquals("Hide comments", row.getTitle().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(section);
        assertEquals("Comments", section.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_COMMENTS.get());
        assertEquals(Arrays.asList(Settings.HIDE_COMMENTS, Settings.HIDE_COMMENT_REPLY), PatchFamily.HIDE_COMMENTS.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_COMMENTS.key));
        row.setChecked(true);
        assertTrue(Settings.HIDE_COMMENTS.get());
    }
    /** #619: Reply's own switch, named with Instagram's word, sits right under Hide comments and starts off. */
    @Test public void theReplySwitchFollowsHideCommentsAndStartsOff() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_REPLY.key);
        assertNotNull(row);
        assertEquals("Hide Reply on comments", row.getTitle().toString());
        assertEquals("Takes Reply off each comment in a post's or reel's comments. View more replies stays, so you can "
                + "still open them.", row.getSummary().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(section);
        assertEquals("Comments", section.getTitle().toString());
        assertEquals("right after Hide comments", Settings.HIDE_COMMENTS.key,
                section.getPreference(indexOf(section, row) - 1).getKey());
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_COMMENT_REPLY.get());
        assertFalse("each comment reads the switch as it's drawn, so no restart prompt", Settings.HIDE_COMMENT_REPLY.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_COMMENT_REPLY.key));
        row.setChecked(true);
        assertTrue(Settings.HIDE_COMMENT_REPLY.get());
        assertFalse("Hide comments keeps its own value", Settings.HIDE_COMMENTS.get());
        List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.HIDE_COMMENTS), false);
        for (String line : report) assertFalse(report.toString(), line.contains("Hide Reply on comments: not in this build"));
    }
    /** A build whose comment line moved keeps Hide comments, leaves out Reply's switch and says so in the report. */
    @Test public void aBuildWithoutReplyKeepsHideComments() throws Exception {
        PatchFamily.commentReplyForTests = false;
        open(true);
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENTS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_REPLY.key));
        List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.HIDE_COMMENTS), false);
        assertTrue(report.toString(), report.contains(
                "  Hide Reply on comments: not in this build (the line under a comment didn't match)"));
    }
    private static int indexOf(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }
    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
            if (child instanceof PreferenceGroup) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }
}

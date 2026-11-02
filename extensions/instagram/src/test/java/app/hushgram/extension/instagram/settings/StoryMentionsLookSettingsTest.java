/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.instagram.stories.StoryMentionsLook;

/**
 * Where the mention pill goes (#468): a choice under See who a story mentions, below Red story
 * mentions, that starts under the name and travels with an exported configuration.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StoryMentionsLookSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.SHOW_STORY_MENTIONS.resetToDefault();
        Settings.STORY_MENTIONS_LOOK.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SHOW_STORY_MENTIONS.resetToDefault();
        Settings.STORY_MENTIONS_LOOK.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.STORY_MENTIONS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void missingPatchHasNoChoice() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.STORY_MENTIONS_LOOK.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.STORY_MENTIONS_LOOK.key));
    }

    @Test public void theChoiceSitsBelowRedMentionsStartsUnderTheNameAndSaysWhereThePillGoes() throws Exception {
        Settings.SHOW_STORY_MENTIONS.save(true);
        open(true);
        Preference found = page.getPreferenceScreen().findPreference(Settings.STORY_MENTIONS_LOOK.key);
        assertTrue("no choice of where with the patch in the build", found instanceof HushgramPreferenceFragment.StoryMentionsLookRow);
        HushgramPreferenceFragment.StoryMentionsLookRow row = (HushgramPreferenceFragment.StoryMentionsLookRow) found;
        assertEquals("Where the mention pill goes", String.valueOf(row.getTitle()));
        PreferenceGroup section = StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row);
        assertEquals("Stories", String.valueOf(section.getTitle()));
        int redAt = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            if (Settings.RED_STORY_MENTIONS.key.equals(section.getPreference(i).getKey())) redAt = i;
        }
        assertTrue("Red story mentions is in the choice's section", redAt >= 0);
        assertEquals("right below Red story mentions", row, section.getPreference(redAt + 1));

        List<String> entries = new ArrayList<>();
        for (CharSequence entry : row.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Under the name, 1 mention", "Top bar, @1 mention", "Top bar, @1"), entries);
        List<String> values = new ArrayList<>();
        for (CharSequence value : row.getEntryValues()) values.add(String.valueOf(value));
        List<String> names = new ArrayList<>();
        for (StoryMentionsLook each : StoryMentionsLook.values()) names.add(each.name());
        assertEquals(names, values);

        assertEquals("UNDER_NAME", row.getValue());
        assertEquals("The pill sits under the story's name and reads like 1 mention. Its list opens over the story.",
                String.valueOf(row.getSummary()));

        row.setValue("TOP_BAR");
        ShadowLooper.idleMainLooper();
        assertEquals(StoryMentionsLook.TOP_BAR, Settings.STORY_MENTIONS_LOOK.savedValue());
        assertEquals("The pill sits in the top bar next to the menu and reads like @1 mention. Its list opens from the bottom of the screen.",
                String.valueOf(row.getSummary()));

        row.setValue("TOP_BAR_SHORT");
        ShadowLooper.idleMainLooper();
        assertEquals(StoryMentionsLook.TOP_BAR_SHORT, Settings.STORY_MENTIONS_LOOK.savedValue());

        // With the switch off, the choice asks for it and keeps what was picked.
        Settings.SHOW_STORY_MENTIONS.save(false);
        ShadowLooper.idleMainLooper();
        row.showSummary();
        assertEquals("Turn on See who a story mentions to use this choice.", String.valueOf(row.getSummary()));
        assertEquals(StoryMentionsLook.TOP_BAR_SHORT, Settings.STORY_MENTIONS_LOOK.savedValue());

        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_MENTIONS_LOOK.key));
    }
}

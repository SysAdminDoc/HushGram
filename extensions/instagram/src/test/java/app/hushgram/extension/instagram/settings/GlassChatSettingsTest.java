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
import java.util.Collections;
import java.util.EnumSet;
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
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.IntegerSetting;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Glass in chats (#449): a switch under Messages, and the stories' reply box one under Stories (#391), off to start. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class GlassChatSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        resetAll();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        resetAll();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.GLASS_CHAT) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    private static BooleanSetting[] all() {
        return new BooleanSetting[]{Settings.GLASS_CHAT, Settings.GLASS_CHAT_TOP_BAR, Settings.GLASS_STORY_REPLY_BOX};
    }
    private static void resetAll() {
        for (BooleanSetting each : all()) each.resetToDefault();
        for (IntegerSetting each : sliders()) each.resetToDefault();
    }
    private static IntegerSetting[] sliders() {
        return new IntegerSetting[]{Settings.GLASS_CHAT_TOP_BAR_OPACITY, Settings.GLASS_CHAT_BOX_OPACITY};
    }
    @Test public void missingPatchHasNoRow() throws Exception {
        open(false);
        for (BooleanSetting each : all()) assertNull(page.getPreferenceScreen().findPreference(each.key));
        for (IntegerSetting each : sliders()) {
            assertNull(each.key, page.getPreferenceScreen().findPreference(each.key));
            assertFalse(each.key, ConfigurationBackup.eligible().containsKey(each.key));
        }
    }
    /**
     * A tint slider each for a chat's top bar and its message bar (#688), right after the top bar's switch,
     * 0% to 150% of the standard look, shown at once and backed up. Each names the switch it waits on.
     */
    @Test public void theTintSlidersSitRightAfterTheTopBarSwitch() throws Exception {
        open(true);
        PreferenceCategory messages = categoryHolding(page.getPreferenceScreen(),
                page.getPreferenceScreen().findPreference(Settings.GLASS_CHAT_TOP_BAR.key));
        assertNotNull(messages);
        int topBarAt = -1;
        for (int i = 0; i < messages.getPreferenceCount(); i++) {
            if (Settings.GLASS_CHAT_TOP_BAR.key.equals(messages.getPreference(i).getKey())) topBarAt = i;
        }
        assertTrue(topBarAt >= 0);
        String[] titles = {"Top bar tint in chats", "Message bar tint"};
        HushgramPreferenceFragment.SliderRow[] rows = new HushgramPreferenceFragment.SliderRow[2];
        for (int i = 0; i < 2; i++) {
            IntegerSetting slider = sliders()[i];
            Preference found = messages.getPreference(topBarAt + 1 + i);
            assertEquals(titles[i], slider.key, found.getKey());
            assertTrue(titles[i], found instanceof HushgramPreferenceFragment.SliderRow);
            rows[i] = (HushgramPreferenceFragment.SliderRow) found;
            assertEquals(titles[i], String.valueOf(found.getTitle()));
            assertEquals(titles[i], 100, (int) slider.defaultValue);
            assertFalse("a change shows on the glass at once", slider.rebootApp);
            assertTrue(ConfigurationBackup.eligible().containsKey(slider.key));
            assertEquals("Turn on Glass in chats to use this slider.", String.valueOf(found.getSummary()));
        }
        assertEquals(Collections.singletonList(Settings.GLASS_CHAT_TOP_BAR), Settings.GLASS_CHAT_TOP_BAR_OPACITY.getParentSettings());
        assertEquals(Collections.singletonList(Settings.GLASS_CHAT), Settings.GLASS_CHAT_BOX_OPACITY.getParentSettings());

        ((SwitchPreference) page.getPreferenceScreen().findPreference(Settings.GLASS_CHAT.key)).setChecked(true);
        ShadowLooper.idleMainLooper();
        for (HushgramPreferenceFragment.SliderRow row : rows) assertEquals("100% (standard)", String.valueOf(row.getSummary()));

        rows[1].choose(30);
        ShadowLooper.idleMainLooper();
        assertEquals(30, (int) Settings.GLASS_CHAT_BOX_OPACITY.savedValue());
        assertEquals("30%", String.valueOf(rows[1].getSummary()));
        rows[0].choose(150);
        ShadowLooper.idleMainLooper();
        assertEquals(150, (int) Settings.GLASS_CHAT_TOP_BAR_OPACITY.savedValue());

        ((SwitchPreference) page.getPreferenceScreen().findPreference(Settings.GLASS_CHAT_TOP_BAR.key)).setChecked(false);
        ShadowLooper.idleMainLooper();
        assertEquals("Turn on Glass top bar in chats to use this slider.", String.valueOf(rows[0].getSummary()));
        assertEquals("the message bar's slider stays", "30%", String.valueOf(rows[1].getSummary()));
    }
    /** The top bar's glass (#670) sits under Glass in chats and starts on, so turning Glass in chats on looks as before. */
    @Test public void theSwitchesSitUnderMessagesAndStories() throws Exception {
        open(true);
        String[] titles = {"Glass in chats", "Glass top bar in chats", "Glass reply box on stories"};
        String[] sections = {"Messages", "Messages", "Stories"};
        boolean[] starts = {false, true, false};
        BooleanSetting[] switches = all();
        for (int i = 0; i < switches.length; i++) {
            SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(switches[i].key);
            assertNotNull(titles[i], row);
            assertEquals(titles[i], row.getTitle().toString());
            PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
            assertNotNull(section);
            assertEquals(sections[i], section.getTitle().toString());
            assertEquals(titles[i], starts[i], row.isChecked());
            assertEquals(titles[i], starts[i], switches[i].get());
            assertTrue(ConfigurationBackup.eligible().containsKey(switches[i].key));
            row.setChecked(!starts[i]);
            assertEquals(titles[i], !starts[i], switches[i].get());
        }
        assertEquals(Collections.singletonList(Settings.GLASS_CHAT), Settings.GLASS_CHAT_TOP_BAR.getParentSettings());
        assertEquals(java.util.Arrays.asList(switches), PatchFamily.GLASS_CHAT.switches);
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

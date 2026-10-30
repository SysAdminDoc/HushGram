/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Keep Home's top bar (#408): the scroll controller gets no views while the switch is on. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HomeTopBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private View header;
    private List<View> views;

    @Before
    public void prepare() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        header = new View(activity);
        views = Collections.singletonList(header);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_HOME_TOP_BAR.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.KEEP_HOME_TOP_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndAsksForARestart() {
        assertEquals(Boolean.FALSE, Settings.KEEP_HOME_TOP_BAR.defaultValue);
        assertTrue(Settings.KEEP_HOME_TOP_BAR.rebootApp);
    }

    @Test
    public void offToStartHandsOverInstagramsList() {
        header.setTranslationY(-40f);

        assertSame(views, HomeTopBar.slidingViews(views));

        assertEquals("the header is left where Instagram put it", -40f, header.getTranslationY(), 0f);
        assertTrue(HookStatus.missing(FamilyNames.HOME_TOP_BAR).toString(),
                HookStatus.missing(FamilyNames.HOME_TOP_BAR).isEmpty());
    }

    @Test
    public void onHandsOverNothingPutsASlidHeaderBackAndCounts() {
        Settings.KEEP_HOME_TOP_BAR.save(true);
        header.setTranslationY(-40f);

        List<?> given = HomeTopBar.slidingViews(views);

        assertTrue("the controller slides nothing", given.isEmpty());
        assertEquals("the header is back in place", 0f, header.getTranslationY(), 0f);
        assertEquals("Instagram's list is untouched", 1, views.size());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HomeTopBar.COUNT_KEPT + " 1"));
    }

    @Test
    public void pausedAndUnreadyReadAsOff() {
        Settings.KEEP_HOME_TOP_BAR.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", views, HomeTopBar.slidingViews(views));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("unready", views, HomeTopBar.slidingViews(views)));

        assertTrue("on again once resumed and ready", HomeTopBar.slidingViews(views).isEmpty());
    }

    @Test
    public void aNullListAndAThrowingSwitchFailOpen() {
        Settings.KEEP_HOME_TOP_BAR.save(true);
        assertEquals(null, HomeTopBar.slidingViews(null));

        assertSame(views, HomeTopBar.slidingViews(views, () -> {
            throw new IllegalStateException("boom");
        }));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HomeTopBar.HOOK));
    }
}

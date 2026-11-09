/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which of the Compose palettes' dark grays take the accent backgrounds' shades (#577). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ComposeSurfaceTest {
    private static final int BASE = 0xff112233;
    private static final int RAISED = 0xff223344;
    private static final int TOP = 0xff334455;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static long compose(int argb) {
        return (long) argb << 32;
    }

    @Before public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        Settings.ACCENT_BACKGROUNDS.save(true);
        AccentTheme.darkSurfacesForTests(new int[] {BASE, RAISED, TOP});
        HookStatus.clear();
    }

    @After public void end() {
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        Settings.ACCENT_BACKGROUNDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        AccentTheme.darkSurfacesForTests(null);
        HookStatus.clear();
    }

    @Test public void eachGrayTakesItsStep() {
        assertEquals(compose(BASE), AccentTheme.composeSurface(compose(0xff0c1014)));
        assertEquals(compose(RAISED), AccentTheme.composeSurface(compose(0xff191c1f)));
        assertEquals(compose(TOP), AccentTheme.composeSurface(compose(0xff25292e)));
        assertEquals(compose(TOP), AccentTheme.composeSurface(compose(0xff212328)));
        assertEquals(compose(TOP), AccentTheme.composeSurface(compose(0xff2b3036)));
    }

    @Test public void pureBlacksPageIsThePage() {
        assertEquals(compose(BASE), AccentTheme.composeSurface(compose(0xff000000)));
    }

    @Test public void alphaIsKept() {
        assertEquals(compose(0x80112233), AccentTheme.composeSurface(compose(0x800c1014)));
    }

    @Test public void otherColorsStay() {
        assertEquals(compose(0xff737373), AccentTheme.composeSurface(compose(0xff737373)));
        assertEquals("an already swapped color passes", compose(BASE), AccentTheme.composeSurface(compose(BASE)));
    }

    @Test public void aColorOutsideSrgbStays() {
        long wideGamut = compose(0xff0c1014) | 5;
        assertEquals(wideGamut, AccentTheme.composeSurface(wideGamut));
    }

    @Test public void offStays() {
        Settings.ACCENT_BACKGROUNDS.save(false);
        assertEquals(compose(0xff0c1014), AccentTheme.composeSurface(compose(0xff0c1014)));
    }

    @Test public void noColorPickedStays() {
        Settings.ACCENT_COLOR.save(false);
        assertEquals(compose(0xff0c1014), AccentTheme.composeSurface(compose(0xff0c1014)));
    }

    @Test public void pausedStays() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(compose(0xff0c1014), AccentTheme.composeSurface(compose(0xff0c1014)));
    }

    /** Material You's neutral 700 reads as a dark gray to Pure black, which blackened the note on a colored page. */
    @Test public void pureBlackLeavesTheFillsOfAPaintedPage() {
        int materialYouTop = 0xff44464f;
        assertEquals(materialYouTop, NoteBubble.color(materialYouTop));
        assertEquals(compose(materialYouTop), NoteBubble.card(compose(materialYouTop)));
        assertEquals(compose(materialYouTop), PeekCard.color(compose(materialYouTop)));
    }

    @Test public void pureBlackStillBlackensWithoutPaintedBackgrounds() {
        Settings.ACCENT_BACKGROUNDS.save(false);
        assertEquals(0xff000000, NoteBubble.color(0xff44464f));
        assertEquals(compose(0xff000000), NoteBubble.card(compose(0xff44464f)));
        assertEquals(compose(0xff000000), PeekCard.color(compose(0xff44464f)));
        Settings.ACCENT_BACKGROUNDS.save(true);
        AccentTheme.darkSurfacesForTests(null);
        assertEquals(0xff000000, NoteBubble.color(0xff44464f));
    }

    @Test public void aPaintedPageKeepsItsCommentsSheet() {
        LinearLayout painted = nightRoot();
        CommentsSheet.contentShown(new Object(), painted);
        assertNull(painted.getBackground().getColorFilter());

        Settings.ACCENT_BACKGROUNDS.save(false);
        LinearLayout black = nightRoot();
        CommentsSheet.contentShown(new Object(), black);
        assertNotNull(black.getBackground().getColorFilter());
    }

    private static LinearLayout nightRoot() {
        Application app = RuntimeEnvironment.getApplication();
        Configuration night = new Configuration(app.getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        LinearLayout root = new LinearLayout(app.createConfigurationContext(night));
        root.setBackground(new ColorDrawable(Color.rgb(25, 28, 31)));
        return root;
    }

    @Test public void noDarkThemeSeenStays() {
        AccentTheme.darkSurfacesForTests(null);
        assertEquals(compose(0xff0c1014), AccentTheme.composeSurface(compose(0xff0c1014)));
    }
}

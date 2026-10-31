/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Mark one-time photos and videos (#165): a view once bubble gets a drawn mark while the switch is on, and nothing else changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ViewOnceMarkTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private FrameLayout frame;

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.MARK_VIEW_ONCE.resetToDefault();
        HookStatus.clear();
        frame = new FrameLayout(RuntimeEnvironment.getApplication());
    }

    @After
    public void restore() {
        Settings.MARK_VIEW_ONCE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        assertEquals(Boolean.FALSE, Settings.MARK_VIEW_ONCE.defaultValue);
        assertFalse(Settings.MARK_VIEW_ONCE.rebootApp);
    }

    @Test
    public void offLeavesTheBubbleAsInstagramDrewIt() {
        ViewOnceMark.mark(frame, 1);
        assertNull(frame.getForeground());
        assertTrue(HookStatus.missing(FamilyNames.VIEW_ONCE_MARK).toString(),
                HookStatus.missing(FamilyNames.VIEW_ONCE_MARK).isEmpty());
        assertFalse(HookStatus.report().toString().contains(ViewOnceMark.COUNT_MARKED));
    }

    @Test
    public void onMarksAViewOnceBubbleAndCountsIt() {
        Settings.MARK_VIEW_ONCE.save(true);
        ViewOnceMark.mark(frame, 1);
        assertTrue(frame.getForeground() instanceof ViewOnceMark.Mark);
        assertTrue(HookStatus.report().toString().contains(ViewOnceMark.COUNT_MARKED + " 1"));
        Drawable first = frame.getForeground();
        ViewOnceMark.mark(frame, 2);
        assertSame("a rebind keeps the one mark", first, frame.getForeground());
    }

    @Test
    public void aBubbleThatIsNotViewOnceIsLeftAlone() {
        Settings.MARK_VIEW_ONCE.save(true);
        ViewOnceMark.mark(frame, 0);
        assertNull(frame.getForeground());
        ColorDrawable own = new ColorDrawable(0x11223344);
        frame.setForeground(own);
        ViewOnceMark.mark(frame, 0);
        assertSame("Instagram's own foreground stays", own, frame.getForeground());
        assertFalse(HookStatus.report().toString().contains(ViewOnceMark.COUNT_MARKED));
    }

    @Test
    public void aRecycledBubbleLosesTheMarkWhenItIsNotViewOnceOrTheSwitchIsOff() {
        Settings.MARK_VIEW_ONCE.save(true);
        ViewOnceMark.mark(frame, 1);
        assertNotNull(frame.getForeground());
        ViewOnceMark.mark(frame, 0);
        assertNull(frame.getForeground());

        ViewOnceMark.mark(frame, 1);
        Settings.MARK_VIEW_ONCE.save(false);
        ViewOnceMark.mark(frame, 1);
        assertNull("switched off, the next bind clears it", frame.getForeground());
    }

    @Test
    public void aForegroundInstagramSetItselfIsNeverCovered() {
        Settings.MARK_VIEW_ONCE.save(true);
        ColorDrawable own = new ColorDrawable(0x11223344);
        frame.setForeground(own);
        ViewOnceMark.mark(frame, 1);
        assertSame(own, frame.getForeground());
    }

    @Test
    public void pausedAndUnreadyReadAsOff() {
        Settings.MARK_VIEW_ONCE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ViewOnceMark.mark(frame, 1);
        assertNull("paused", frame.getForeground());

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            View bare = new FrameLayout(RuntimeEnvironment.getApplication());
            ViewOnceMark.mark(bare, 1);
            assertNull("unready", bare.getForeground());
        });

        ViewOnceMark.mark(frame, 1);
        assertNotNull("on again once resumed and ready", frame.getForeground());
    }

    @Test
    public void nullAndThrowingInputsFailOpen() {
        Settings.MARK_VIEW_ONCE.save(true);
        ViewOnceMark.mark(null, 1);
        ViewOnceMark.mark(null, true, () -> true);
        assertNull(frame.getForeground());

        ViewOnceMark.mark(frame, true, () -> {
            throw new IllegalStateException("boom");
        });
        assertNull(frame.getForeground());
        assertTrue(HookStatus.report().toString().contains(ViewOnceMark.HOOK));
    }

    @Test
    public void theMarkDrawsWithOrWithoutInstagramsIcon() {
        Bitmap bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        ViewOnceMark.Mark bare = new ViewOnceMark.Mark(null);
        bare.setBounds(0, 0, 200, 120);
        bare.draw(canvas);
        assertFalse(bare.hasGlyph());

        ViewOnceMark.Mark withIcon = new ViewOnceMark.Mark(new ColorDrawable(0xFFFFFFFF));
        withIcon.setBounds(0, 0, 200, 120);
        withIcon.draw(canvas);
        assertTrue(withIcon.hasGlyph());
        assertEquals(android.graphics.PixelFormat.TRANSLUCENT, withIcon.getOpacity());
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.view.MotionEvent;
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

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Pinch for clear mode (#744): the pinch flag says yes while the switch is on, and Instagram's own
 * answer otherwise. Hide the exit button in clear mode hands the button an icon of the same size that
 * draws nothing and drops its circle and tap, only while both switches are on and no zoom takes the
 * pinch. Off, paused or unready, everything is as Instagram has it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelPinchClearModeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Object without = new Object();
    private final Object with = new Object();

    @Before
    public void start() throws Exception {
        reset();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        build(null);
    }

    @After
    public void restore() throws Exception {
        reset();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        build(null);
    }

    private static void reset() {
        Settings.PINCH_CLEAR_MODE.resetToDefault();
        Settings.HIDE_CLEAR_MODE_EXIT_BUTTON.resetToDefault();
        Settings.PINCH_ZOOM_REELS.resetToDefault();
    }

    /** Says which patches this build has, the way the settings tests do. */
    private static void build(Set<PatchFamily> families) throws Exception {
        Field forced = PatchFamily.class.getDeclaredField("inBuildForTests");
        forced.setAccessible(true);
        forced.set(null, families);
    }

    private static Drawable icon() {
        ShapeDrawable icon = new ShapeDrawable();
        icon.setIntrinsicWidth(48);
        icon.setIntrinsicHeight(40);
        icon.setBounds(3, 5, 51, 45);
        return icon;
    }

    private void assertStock(String why) {
        Drawable icon = icon();
        assertSame(why + ": the icon", icon, ReelPinchClearMode.exitIcon(icon));
        assertSame(why + ": the circle and tap", with, ReelPinchClearMode.exitPart(without, with));
    }

    private void assertHidden(String why) {
        Drawable icon = icon();
        Drawable shown = ReelPinchClearMode.exitIcon(icon);
        assertNotSame(why + ": a stand-in", icon, shown);
        assertTrue(why, shown instanceof ReelPinchClearMode.Unseen);
        assertEquals("as wide", 48, shown.getIntrinsicWidth());
        assertEquals("as tall", 40, shown.getIntrinsicHeight());
        assertEquals("in the same place", new Rect(3, 5, 51, 45), shown.getBounds());
        assertSame(why + ": no circle or tap", without, ReelPinchClearMode.exitPart(without, with));
    }

    @Test
    public void thePinchFlagSaysYesOnlyWhileTheSwitchIsOn() {
        assertFalse("off, Instagram's no", ReelPinchClearMode.pinch(0));
        assertTrue("off, Instagram's yes", ReelPinchClearMode.pinch(1));

        Settings.PINCH_CLEAR_MODE.save(true);
        assertTrue(ReelPinchClearMode.pinch(0));
        assertTrue(ReelPinchClearMode.pinch(1));
        assertTrue("any nonzero answer is yes", ReelPinchClearMode.pinch(-1));

        Settings.PINCH_CLEAR_MODE.save(false);
        assertFalse("read at each pinch", ReelPinchClearMode.pinch(0));
    }

    @Test
    public void pausedOrUnreadyThePinchFlagIsInstagrams() {
        Settings.PINCH_CLEAR_MODE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ReelPinchClearMode.pinch(0));
        assertTrue("paused keeps Instagram's yes", ReelPinchClearMode.pinch(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(ReelPinchClearMode.pinch(0));

        SettingsContextRule.withoutContext(() -> {
            assertFalse("unready", ReelPinchClearMode.pinch(0));
            assertTrue(ReelPinchClearMode.pinch(1));
        });
    }

    @Test
    public void theExitButtonDrawsNothingAndTakesNoTapOnlyWithBothSwitchesOn() {
        assertStock("both off");
        Settings.HIDE_CLEAR_MODE_EXIT_BUTTON.save(true);
        assertStock("Hide the exit button waits for Pinch for clear mode");
        Settings.HIDE_CLEAR_MODE_EXIT_BUTTON.save(false);
        Settings.PINCH_CLEAR_MODE.save(true);
        assertStock("pinch alone keeps the button");

        Settings.HIDE_CLEAR_MODE_EXIT_BUTTON.save(true);
        assertHidden("both on");
        Drawable unseen = ReelPinchClearMode.exitIcon(icon());
        assertSame("a stand-in isn't wrapped again", unseen, ReelPinchClearMode.exitIcon(unseen));
        assertNull("no icon stays no icon", ReelPinchClearMode.exitIcon(null));
        assertSame("a part Instagram didn't wrap stays", with, ReelPinchClearMode.exitPart(null, with));
        assertEquals("nothing to see", PixelFormat.TRANSPARENT, unseen.getOpacity());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(FamilyNames.REEL_DECLUTTER));

        Settings.HIDE_CLEAR_MODE_EXIT_BUTTON.save(false);
        assertStock("read as each button draws");
    }

    @Test
    public void pinchToZoomKeepsTheButtonWhenItsInTheBuild() throws Exception {
        Settings.PINCH_CLEAR_MODE.save(true);
        Settings.HIDE_CLEAR_MODE_EXIT_BUTTON.save(true);
        Settings.PINCH_ZOOM_REELS.save(true);
        build(EnumSet.of(PatchFamily.REEL_DECLUTTER, PatchFamily.REEL_ZOOM));
        assertStock("two fingers zoom, so a pinch can't leave clear mode");
        assertTrue("the pinch flag still says yes", ReelPinchClearMode.pinch(0));

        build(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        assertHidden("a saved Pinch to zoom without its patch takes no pinch");

        build(EnumSet.of(PatchFamily.REEL_DECLUTTER, PatchFamily.REEL_ZOOM));
        Settings.PINCH_ZOOM_REELS.save(false);
        assertHidden("Pinch to zoom off");
    }

    /** A list around the reel that remembers whether it was asked to leave the gesture alone. */
    private static final class ReelList extends FrameLayout {
        int asked;
        boolean leftAlone;

        ReelList() {
            super(RuntimeEnvironment.getApplication());
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallow) {
            asked++;
            leftAlone = disallow;
            super.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private static View reelIn(ReelList list) {
        View reel = new View(RuntimeEnvironment.getApplication());
        list.addView(reel);
        return reel;
    }

    /** One finger down, or a second landing, at two points an inch or so apart. */
    private static MotionEvent fingers(int action, int count) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 500 + 60 * i;
            coords[i].y = 1100 + 60 * i;
        }
        return MotionEvent.obtain(0, 10, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
    }

    private static MotionEvent secondFinger() {
        return fingers(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2);
    }

    @Test
    public void aSecondFingerKeepsTheListOutOfThePinchWhileTheSwitchIsOn() {
        ReelList list = new ReelList();
        View reel = reelIn(list);

        ReelPinchClearMode.touch(reel, secondFinger());
        assertEquals("off, the list is never asked", 0, list.asked);

        Settings.PINCH_CLEAR_MODE.save(true);
        ReelPinchClearMode.touch(reel, fingers(MotionEvent.ACTION_DOWN, 1));
        ReelPinchClearMode.touch(reel, fingers(MotionEvent.ACTION_MOVE, 2));
        ReelPinchClearMode.touch(reel, fingers(MotionEvent.ACTION_UP, 1));
        assertEquals("one finger, a move or a lift leaves the list to scroll", 0, list.asked);

        ReelPinchClearMode.touch(reel, secondFinger());
        assertEquals("the second finger asks once", 1, list.asked);
        assertTrue("and the list leaves the gesture alone", list.leftAlone);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(ReelPinchClearMode.KEPT_SPREAD));

        Settings.PINCH_CLEAR_MODE.save(false);
        ReelPinchClearMode.touch(reel, secondFinger());
        assertEquals("read at each touch", 1, list.asked);
    }

    @Test
    public void aSecondFingerIsLeftToZoomOrToInstagramOtherwise() throws Exception {
        ReelList list = new ReelList();
        View reel = reelIn(list);
        Settings.PINCH_CLEAR_MODE.save(true);
        Settings.PINCH_ZOOM_REELS.save(true);
        build(EnumSet.of(PatchFamily.REEL_DECLUTTER, PatchFamily.REEL_ZOOM));
        ReelPinchClearMode.touch(reel, secondFinger());
        assertEquals("Pinch to zoom takes two fingers", 0, list.asked);

        build(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        ReelPinchClearMode.touch(reel, secondFinger());
        assertEquals("a saved Pinch to zoom without its patch takes nothing", 1, list.asked);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ReelPinchClearMode.touch(reel, secondFinger());
        assertEquals("paused", 1, list.asked);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> ReelPinchClearMode.touch(reel, secondFinger()));
        assertEquals("unready", 1, list.asked);

        ReelPinchClearMode.touch(new View(RuntimeEnvironment.getApplication()), secondFinger());
        ReelPinchClearMode.touch(null, secondFinger());
        ReelPinchClearMode.touch(reel, null);
        assertEquals("no list, no view or no event changes nothing", 1, list.asked);
    }

    @Test
    public void pausedOrUnreadyTheButtonIsInstagrams() {
        Settings.PINCH_CLEAR_MODE.save(true);
        Settings.HIDE_CLEAR_MODE_EXIT_BUTTON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertStock("paused");
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertHidden("resumed");
        SettingsContextRule.withoutContext(() -> assertStock("unready"));
    }
}

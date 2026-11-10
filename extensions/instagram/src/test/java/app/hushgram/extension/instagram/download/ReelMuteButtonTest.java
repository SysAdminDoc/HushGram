/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.reels.HiddenSounds;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The speaker icon beside a reel, for Mute button on reels (#608): it joins the column only with its
 * switch on, Control taps and volume on Reels in the build and an organic reel, shows the sound off
 * glyph only while the reel on screen is known to be muted, and a tap toggles that reel's sound. With
 * Mute button in clear mode on, the same speaker goes in first in the row holding clear mode's exit
 * button.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelMuteButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ReelDownloadButton.Bridge originalBridge = ReelDownloadButton.bridge;
    private final ReelNotInterestedButton.Bridge originalNotInterested = ReelNotInterestedButton.bridge;
    private final ReelBlockButton.Bridge originalBlock = ReelBlockButton.bridge;
    private final ReelSoundButton.Bridge originalSound = ReelSoundButton.bridge;
    private final Supplier<String> originalAccounts = DownloadHistory.accounts;
    private final FakeBridge bridge = new FakeBridge();
    private final FakeAudio audio = new FakeAudio();
    private ActivityController<Activity> controller;
    private Activity activity;

    private enum Kind { ORGANIC, AD }

    /** A reel: what kind it is. */
    private static final class Item {
        final Kind kind;

        Item(Kind kind) {
            this.kind = kind;
        }
    }

    /** The column beside a reel: the reel it shows and the viewer's config. */
    private static final class Column {
        final Item item;
        final Object config = new Object();

        Column(Item item) {
            this.item = item;
        }
    }

    /** Reads the fake column and keeps what the icon was made with. */
    private static class FakeBridge implements ReelDownloadButton.Bridge {
        final Object made = new Object();
        int icons;
        Drawable drawable;
        CharSequence description;
        Function1<Object, Object> click;
        long size;
        long margin;
        int moreTop;

        @Override public Object item(Object column) {
            return ((Column) column).item;
        }

        @Override public Object media(Object item) {
            return "media";
        }

        @Override public String kind(Object item) {
            return ((Item) item).kind.name();
        }

        @Override public int moreTop(Object column, Context context) {
            return moreTop;
        }

        @Override public Object icon(Drawable drawable, CharSequence description, Function1<Object, Object> click, long size,
                                     long margin) {
            icons++;
            this.drawable = drawable;
            this.description = description;
            this.click = click;
            this.size = size;
            this.margin = margin;
            return made;
        }
    }

    /** The reel on screen's sound, as the patch would read and toggle it. */
    private static final class FakeAudio implements ReelMuteButton.Audio {
        int sound = 1;
        boolean toggles = true;
        int toggled;

        @Override public int soundOn() {
            return sound;
        }

        @Override public boolean toggle() {
            toggled++;
            if (toggles) sound = sound == 1 ? 0 : 1;
            return toggles;
        }
    }

    /** The column's config, so the Not interested icon can tell whether it shows. */
    private static final class FakeNotInterested implements ReelNotInterestedButton.Bridge {
        @Override public Object config(Object column) {
            return ((Column) column).config;
        }

        @Override public Object state(Object column) {
            return new Object();
        }

        @Override public void run(Object delegate, Object item, Object state) {
        }
    }

    /** Someone else's reel, for the Block icon. */
    private static final class FakeBlock implements ReelBlockButton.Bridge {
        @Override public Object owner(Object item) {
            return "user 2";
        }

        @Override public String ownerId(Object item) {
            return "2";
        }

        @Override public void block(Object delegate, Object owner, String module, String ownerId) {
        }
    }

    /** Counts the redraws a speaker asks for. */
    private static final class Redraws implements Drawable.Callback {
        int count;

        @Override public void invalidateDrawable(Drawable who) {
            count++;
        }

        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
        }

        @Override public void unscheduleDrawable(Drawable who, Runnable what) {
        }
    }

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        ReelDownloadButton.bridge = bridge;
        ReelNotInterestedButton.bridge = new FakeNotInterested();
        ReelBlockButton.bridge = new FakeBlock();
        ReelSoundButton.bridge = item -> new HiddenSounds.Sound("111", "Espresso", "Sabrina Carpenter");
        DownloadHistory.accounts = () -> "1";
        resetIcons();
        ReelMuteButton.audio = audio;
        ReelMuteButton.tapAndVolume = 1;
        // No glyphs of Instagram's here, so two of the framework's stand in.
        ReelMuteButton.glyphOn = android.R.drawable.ic_lock_silent_mode_off;
        ReelMuteButton.glyphOff = android.R.drawable.ic_lock_silent_mode;
        ReelSpeedButton.glyph = android.R.drawable.ic_media_ff;
        ReelTextButton.glyph = android.R.drawable.ic_menu_search;
        ReelSoundButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        ReelBlockButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        ReelNotInterestedButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        Settings.MUTE_BUTTON_ON_REELS.save(true);
    }

    @After
    public void tearDown() {
        ReelDownloadButton.bridge = originalBridge;
        ReelNotInterestedButton.bridge = originalNotInterested;
        ReelBlockButton.bridge = originalBlock;
        ReelSoundButton.bridge = originalSound;
        DownloadHistory.accounts = originalAccounts;
        resetIcons();
        Settings.MUTE_BUTTON_ON_REELS.resetToDefault();
        Settings.MUTE_BUTTON_IN_CLEAR_MODE.resetToDefault();
        Settings.SPEED_BUTTON_ON_REELS.resetToDefault();
        Settings.TEXT_BUTTON_ON_REELS.resetToDefault();
        Settings.HIDE_SOUND_REELS.resetToDefault();
        Settings.REEL_BLOCK_BUTTON.resetToDefault();
        Settings.REEL_NOT_INTERESTED_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private static void resetIcons() {
        ReelNotInterestedButton.resetForTests();
        ReelBlockButton.resetForTests();
        ReelDownloadButton.resetForTests();
        ReelSoundButton.resetForTests();
        ReelTextButton.resetForTests();
        ReelSpeedButton.resetForTests();
        ReelFullScreenButton.resetForTests();
        ReelMuteButton.resetForTests();
    }

    private static Column reel() {
        return new Column(new Item(Kind.ORGANIC));
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    @Test
    public void theSwitchesStartOffAndNeedNoRestart() {
        assertFalse(Settings.MUTE_BUTTON_ON_REELS.defaultValue);
        assertFalse(Settings.MUTE_BUTTON_ON_REELS.rebootApp);
        assertTrue("it has its own switch", Settings.MUTE_BUTTON_ON_REELS.getParentSettings().isEmpty());
        assertFalse(Settings.MUTE_BUTTON_IN_CLEAR_MODE.defaultValue);
        assertFalse(Settings.MUTE_BUTTON_IN_CLEAR_MODE.rebootApp);
        assertEquals("clear mode's speaker hangs off the icon's switch",
                List.of(Settings.MUTE_BUTTON_ON_REELS), Settings.MUTE_BUTTON_IN_CLEAR_MODE.getParentSettings());
    }

    @Test
    public void offPausedOrWithoutTheTapPatchLeavesTheColumnAsItWas() {
        Column column = reel();
        Settings.MUTE_BUTTON_ON_REELS.save(false);
        assertFalse(ReelMuteButton.on());
        assertNull(ReelMuteButton.litho(column, null, 120, 30));
        assertNull(ReelMuteButton.lithoAboveMore(column, null, 120, 30));

        Settings.MUTE_BUTTON_ON_REELS.save(true);
        ReelMuteButton.tapAndVolume = -1;
        assertFalse("without Control taps and volume on Reels there's nothing to toggle with", ReelMuteButton.on());
        assertNull(ReelMuteButton.litho(column, null, 120, 30));

        ReelMuteButton.tapAndVolume = 1;
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelMuteButton.on());
        assertNull(ReelMuteButton.litho(column, null, 120, 30));
        assertEquals(0, bridge.icons);

        new ReelMuteButton.Click().invoke(new View(activity));
        assertEquals("a tap after it went off toggles nothing", 0, audio.toggled);
    }

    @Test
    public void anAdGetsNoIcon() {
        assertNull(ReelMuteButton.litho(new Column(new Item(Kind.AD)), null, 120, 30));
        assertEquals(0, bridge.icons);
    }

    @Test
    public void aBuildWithoutTheGlyphsGetsNoIcon() {
        ReelMuteButton.glyphOff = -1;
        assertNull(ReelMuteButton.litho(reel(), null, 120, 30));
        assertEquals(0, bridge.icons);
        assertFalse("nor does it take More's margin from the icons in front of it", ReelMuteButton.shows(reel()));
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelMuteButton.COUNT_REFUSED));
    }

    @Test
    public void theGlyphIsSoundOffOnlyWhileTheReelIsKnownToBeMuted() {
        ReelMuteButton.Speaker speaker = ReelMuteButton.speaker(activity, null);
        assertNotNull(speaker);
        assertEquals("white like the column's other icons",
                new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN), speaker.soundOn.getColorFilter());
        assertEquals(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN), speaker.soundOff.getColorFilter());

        audio.sound = 1;
        assertSame(speaker.soundOn, speaker.showing());
        audio.sound = 0;
        assertSame(speaker.soundOff, speaker.showing());
        audio.sound = -1;
        assertSame("a sound it can't read shows the sound on glyph, Instagram's start", speaker.soundOn, speaker.showing());
        ReelMuteButton.audio = new ReelMuteButton.Audio() {
            @Override public int soundOn() {
                throw new NoSuchMethodError("A1G");
            }

            @Override public boolean toggle() {
                return false;
            }
        };
        assertSame(speaker.soundOn, speaker.showing());
    }

    @Test
    public void aTapTogglesTheReelsSoundAndRedrawsTheSpeaker() {
        Column column = reel();
        assertTrue(ReelMuteButton.on());
        assertTrue(ReelMuteButton.shows(column));
        assertSame(bridge.made, ReelMuteButton.litho(column, null, 120, 30));
        assertEquals("Sound", String.valueOf(bridge.description));
        assertEquals(30, bridge.margin);
        assertNotNull(bridge.click);

        ReelMuteButton.Speaker speaker = ReelMuteButton.speaker(activity, null);
        Redraws redraws = new Redraws();
        speaker.setCallback(redraws);
        audio.sound = 1;
        assertNull(bridge.click.invoke(new View(activity)));
        assertEquals(1, audio.toggled);
        assertEquals(0, audio.sound);
        ShadowLooper.idleMainLooper();
        assertNull("one that toggled shows no toast", ShadowToast.getTextOfLatestToast());
        assertTrue("the speaker draws again with the new sound", redraws.count >= 1);
        assertSame(speaker.soundOff, speaker.showing());

        audio.toggles = false;
        bridge.click.invoke(new View(activity));
        ShadowLooper.idleMainLooper();
        assertEquals("one that couldn't reach the reel says so", "Sound didn't change", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void redrawsAskedTogetherComeAsOne() {
        ReelMuteButton.Speaker speaker = ReelMuteButton.speaker(activity, null);
        Redraws redraws = new Redraws();
        speaker.setCallback(redraws);
        ReelMuteButton.refresh();
        ReelMuteButton.refresh();
        ReelMuteButton.refresh();
        ShadowLooper.idleMainLooper();
        assertEquals(1, redraws.count);

        ReelMuteButton.refresh();
        ShadowLooper.idleMainLooper();
        assertEquals("a later one comes too", 2, redraws.count);
    }

    @Test
    public void onlyTheNewestSpeakersAreRemembered() {
        for (int i = 0; i < ReelMuteButton.REMEMBERED + 5; i++) assertNotNull(ReelMuteButton.speaker(activity, null));
        assertEquals(ReelMuteButton.REMEMBERED, ReelMuteButton.rememberedForTests());
    }

    @Test
    public void overMoreItMakesUpTheMarginOnlyWhenItIsTheLastIcon() {
        Column column = reel();
        ReelNotInterestedButton.remember(column.config, new Object());
        long eightDp = ReelDownloadButton.PX_BITS | 23;
        bridge.moreTop = -23;

        assertSame(bridge.made, ReelMuteButton.lithoAboveMore(column, null, 124, eightDp));
        assertEquals("alone over More it makes up Save's 23 pixels and the 23 More pulls up",
                ReelDownloadButton.PX_BITS | 46, bridge.margin);

        Settings.SPEED_BUTTON_ON_REELS.save(true);
        ReelMuteButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the speed icon after it, that one does and this keeps Save's", eightDp, bridge.margin);

        Settings.SPEED_BUTTON_ON_REELS.save(false);
        Settings.TEXT_BUTTON_ON_REELS.save(true);
        ReelMuteButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the Lens icon after it, the same", eightDp, bridge.margin);
    }

    @Test
    public void theFullScreenIconInFrontLeavesMoresMarginToTheSpeaker() {
        Column column = reel();
        ReelNotInterestedButton.remember(column.config, new Object());
        long eightDp = ReelDownloadButton.PX_BITS | 23;
        bridge.moreTop = -23;
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        try {
            ReelFullScreenButton.glyph = android.R.drawable.ic_menu_zoom;
            ReelFullScreenButton.lithoAboveMore(column, null, 124, eightDp);
            assertEquals("the speaker comes after Full screen, so Full screen keeps Save's", eightDp, bridge.margin);
        } finally {
            Settings.FULL_SCREEN_BUTTON_ON_REELS.resetToDefault();
        }
    }

    @Test
    public void inClearModeTheSpeakerGoesLeftOfTheExitButton() {
        Object exit = new Object();
        List<Object> row = new ArrayList<>(List.of(exit));
        ReelMuteButton.clearMode(row);
        assertEquals("its own switch starts off", List.of(exit), row);
        assertEquals(0, bridge.icons);

        Settings.MUTE_BUTTON_IN_CLEAR_MODE.save(true);
        ReelMuteButton.clearMode(row);
        assertEquals(List.of(bridge.made, exit), row);
        assertEquals("Sound", String.valueOf(bridge.description));
        assertEquals(0, bridge.margin);
        assertEquals("a box wider than its circle, so the two circles keep a gap",
                ReelDownloadButton.PX_BITS | dp(activity, ReelMuteButton.CLEAR_MODE_BOX_DP), bridge.size);
        assertTrue(bridge.drawable instanceof ReelMuteButton.Circled);
        assertEquals("a circle the size of the exit button's",
                dp(activity, ReelMuteButton.CIRCLE_DP), bridge.drawable.getIntrinsicWidth());

        audio.sound = 1;
        bridge.click.invoke(new View(activity));
        assertEquals("a tap there toggles the sound too", 1, audio.toggled);

        List<Object> next = new ArrayList<>(List.of(exit));
        Settings.MUTE_BUTTON_ON_REELS.save(false);
        ReelMuteButton.clearMode(next);
        assertEquals("with the icon's switch off clear mode is Instagram's own", List.of(exit), next);
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        ReelMuteButton.audio = new ReelMuteButton.Audio() {
            @Override public int soundOn() {
                return 1;
            }

            @Override public boolean toggle() {
                throw new NoSuchMethodError("A1G");
            }
        };
        assertNull(new ReelMuteButton.Click().invoke(new View(activity)));
        assertNull(new ReelMuteButton.Click().invoke(null));

        Settings.MUTE_BUTTON_IN_CLEAR_MODE.save(true);
        ReelMuteButton.clearMode(null);
        ReelMuteButton.clearMode("not a row");
        ReelMuteButton.clearMode(List.of(new Object()));

        ReelDownloadButton.bridge = new FakeBridge() {
            @Override public Object item(Object column) {
                throw new NoSuchMethodError("AZ1");
            }
        };
        assertNull(ReelMuteButton.litho(reel(), null, 120, 30));
        assertNull(ReelMuteButton.litho(null, null, 120, 30));
        assertFalse(ReelMuteButton.shows(reel()));
        assertFalse("the Errors were reported, not thrown", HookStatus.report().isEmpty());
    }
}

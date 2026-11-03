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
 * The Full screen icon beside a reel, for Full screen button on reels (#480): it joins the column only
 * with its switch on and only on an organic reel, makes up More's margin only as the last icon there,
 * and a tap hands clear mode the reel the column shows when tapped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelFullScreenButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ReelDownloadButton.Bridge originalBridge = ReelDownloadButton.bridge;
    private final ReelNotInterestedButton.Bridge originalNotInterested = ReelNotInterestedButton.bridge;
    private final ReelBlockButton.Bridge originalBlock = ReelBlockButton.bridge;
    private final ReelSoundButton.Bridge originalSound = ReelSoundButton.bridge;
    private final Supplier<String> originalAccounts = DownloadHistory.accounts;
    private final FakeBridge bridge = new FakeBridge();
    private final List<Object> entered = new ArrayList<>();
    private boolean opens = true;
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

    /** The column beside a reel: the reel it shows, which a recycled column changes, and the viewer's config. */
    private static final class Column {
        Item item;
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
            this.margin = margin;
            return made;
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

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        ReelDownloadButton.bridge = bridge;
        ReelNotInterestedButton.bridge = new FakeNotInterested();
        ReelBlockButton.bridge = new FakeBlock();
        ReelSoundButton.bridge = item -> new HiddenSounds.Sound("111", "Espresso", "Sabrina Carpenter");
        DownloadHistory.accounts = () -> "1";
        ReelNotInterestedButton.resetForTests();
        ReelBlockButton.resetForTests();
        ReelDownloadButton.resetForTests();
        ReelSoundButton.resetForTests();
        ReelTextButton.resetForTests();
        ReelSpeedButton.resetForTests();
        ReelFullScreenButton.resetForTests();
        ReelFullScreenButton.enter = item -> {
            entered.add(item);
            return opens;
        };
        // No glyph of Instagram's here, so one of the framework's stands in.
        ReelFullScreenButton.glyph = android.R.drawable.ic_menu_zoom;
        ReelSpeedButton.glyph = android.R.drawable.ic_media_ff;
        ReelTextButton.glyph = android.R.drawable.ic_menu_search;
        ReelSoundButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        ReelBlockButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        ReelNotInterestedButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
    }

    @After
    public void tearDown() {
        ReelDownloadButton.bridge = originalBridge;
        ReelNotInterestedButton.bridge = originalNotInterested;
        ReelBlockButton.bridge = originalBlock;
        ReelSoundButton.bridge = originalSound;
        DownloadHistory.accounts = originalAccounts;
        ReelNotInterestedButton.resetForTests();
        ReelBlockButton.resetForTests();
        ReelDownloadButton.resetForTests();
        ReelSoundButton.resetForTests();
        ReelTextButton.resetForTests();
        ReelSpeedButton.resetForTests();
        ReelFullScreenButton.resetForTests();
        Settings.FULL_SCREEN_BUTTON_ON_REELS.resetToDefault();
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

    private static Column reel() {
        return new Column(new Item(Kind.ORGANIC));
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        assertFalse(Settings.FULL_SCREEN_BUTTON_ON_REELS.defaultValue);
        assertFalse(Settings.FULL_SCREEN_BUTTON_ON_REELS.rebootApp);
        assertTrue("it has its own switch", Settings.FULL_SCREEN_BUTTON_ON_REELS.getParentSettings().isEmpty());
    }

    @Test
    public void offOrPausedLeavesTheColumnAsItWas() {
        Column column = reel();
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(false);
        assertFalse(ReelFullScreenButton.on());
        assertNull(ReelFullScreenButton.litho(column, null, 120, 30));
        assertNull(ReelFullScreenButton.lithoAboveMore(column, null, 120, 30));

        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelFullScreenButton.on());
        assertNull(ReelFullScreenButton.litho(column, null, 120, 30));
        assertEquals(0, bridge.icons);

        new ReelFullScreenButton.Click(column).invoke(new View(activity));
        assertTrue("a tap after it went off enters nothing", entered.isEmpty());
    }

    @Test
    public void anAdGetsNoIcon() {
        assertNull(ReelFullScreenButton.litho(new Column(new Item(Kind.AD)), null, 120, 30));
        assertEquals(0, bridge.icons);
    }

    @Test
    public void aBuildWithoutTheGlyphGetsNoIcon() {
        ReelFullScreenButton.glyph = -1;
        assertNull(ReelFullScreenButton.litho(reel(), null, 120, 30));
        assertEquals(0, bridge.icons);
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelFullScreenButton.COUNT_REFUSED));
    }

    @Test
    public void aTapPutsTheReelTheColumnShowsInClearMode() {
        Column column = reel();
        assertTrue(ReelFullScreenButton.on());
        assertSame(bridge.made, ReelFullScreenButton.litho(column, null, 120, 30));
        assertEquals("Full screen", String.valueOf(bridge.description));
        assertEquals(30, bridge.margin);
        assertEquals(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN), bridge.drawable.getColorFilter());
        assertNotNull(bridge.click);

        assertNull(bridge.click.invoke(new View(activity)));
        assertEquals(List.of(column.item), entered);
        ShadowLooper.idleMainLooper();
        assertNull("one that opened shows no toast", ShadowToast.getTextOfLatestToast());

        // Litho recycles the column for the next reel, and the tap goes to the reel it shows then.
        Item next = new Item(Kind.ORGANIC);
        column.item = next;
        opens = false;
        bridge.click.invoke(new View(activity));
        assertSame(next, entered.get(1));
        ShadowLooper.idleMainLooper();
        assertEquals("one that didn't open says so", "Full screen didn't open", ShadowToast.getTextOfLatestToast());

        column.item = new Item(Kind.AD);
        bridge.click.invoke(new View(activity));
        assertEquals("an ad the column went on to isn't touched", 2, entered.size());
    }

    @Test
    public void overMoreItMakesUpTheMarginOnlyWhenItIsTheLastIcon() {
        Column column = reel();
        ReelNotInterestedButton.remember(column.config, new Object());
        long eightDp = ReelDownloadButton.PX_BITS | 23;
        bridge.moreTop = -23;

        assertSame(bridge.made, ReelFullScreenButton.lithoAboveMore(column, null, 124, eightDp));
        assertEquals("alone over More it makes up Save's 23 pixels and the 23 More pulls up",
                ReelDownloadButton.PX_BITS | 46, bridge.margin);

        Settings.SPEED_BUTTON_ON_REELS.save(true);
        ReelFullScreenButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the speed icon after it, that one does and this keeps Save's", eightDp, bridge.margin);

        Settings.SPEED_BUTTON_ON_REELS.save(false);
        Settings.TEXT_BUTTON_ON_REELS.save(true);
        ReelFullScreenButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the Lens icon after it, the same", eightDp, bridge.margin);

        Settings.TEXT_BUTTON_ON_REELS.save(false);
        ReelSpeedButton.glyph = -1;
        Settings.SPEED_BUTTON_ON_REELS.save(true);
        ReelFullScreenButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("a speed icon this build can't draw leaves it last", ReelDownloadButton.PX_BITS | 46, bridge.margin);
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        ReelFullScreenButton.enter = item -> {
            throw new NoSuchMethodError("BV2");
        };
        assertNull(new ReelFullScreenButton.Click(reel()).invoke(new View(activity)));
        assertNull(new ReelFullScreenButton.Click(reel()).invoke(null));
        ReelDownloadButton.bridge = new FakeBridge() {
            @Override public Object item(Object column) {
                throw new NoSuchMethodError("AZ1");
            }
        };
        assertNull(new ReelFullScreenButton.Click(reel()).invoke(new View(activity)));
        assertNull(ReelFullScreenButton.litho(reel(), null, 120, 30));
        assertNull(ReelFullScreenButton.litho(null, null, 120, 30));
        assertFalse("the Errors were reported, not thrown", HookStatus.report().isEmpty());
    }
}

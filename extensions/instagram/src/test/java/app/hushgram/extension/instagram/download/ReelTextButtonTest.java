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
import android.view.ViewGroup;
import android.widget.FrameLayout;

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

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.reels.HiddenSounds;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The Lens icon beside a reel, for Copy text button on reels (#303): it joins the column only with its
 * switch on and only on an organic reel, makes up More's margin only as the last icon there, and a tap
 * hands the reel's page and activity on to Lens.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelTextButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ReelDownloadButton.Bridge originalBridge = ReelDownloadButton.bridge;
    private final ReelNotInterestedButton.Bridge originalNotInterested = ReelNotInterestedButton.bridge;
    private final ReelBlockButton.Bridge originalBlock = ReelBlockButton.bridge;
    private final ReelSoundButton.Bridge originalSound = ReelSoundButton.bridge;
    private final ReelTextButton.Grabber originalGrabber = ReelTextButton.grabber;
    private final Supplier<String> originalAccounts = DownloadHistory.accounts;
    private final FakeBridge bridge = new FakeBridge();
    private final List<Object[]> grabbed = new ArrayList<>();
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

    /** The column beside a reel: the reel it shows and the viewer's config its state holds. */
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
        ReelTextButton.grabber = (page, from) -> grabbed.add(new Object[] {page, from});
        DownloadHistory.accounts = () -> "1";
        ReelNotInterestedButton.resetForTests();
        ReelBlockButton.resetForTests();
        ReelDownloadButton.resetForTests();
        ReelSoundButton.resetForTests();
        ReelTextButton.resetForTests();
        // No glyph of Instagram's here, so one of the framework's stands in.
        ReelTextButton.glyph = android.R.drawable.ic_menu_search;
        ReelSoundButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        ReelBlockButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        ReelNotInterestedButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        Settings.TEXT_BUTTON_ON_REELS.save(true);
    }

    @After
    public void tearDown() {
        ReelDownloadButton.bridge = originalBridge;
        ReelNotInterestedButton.bridge = originalNotInterested;
        ReelBlockButton.bridge = originalBlock;
        ReelSoundButton.bridge = originalSound;
        ReelTextButton.grabber = originalGrabber;
        DownloadHistory.accounts = originalAccounts;
        ReelNotInterestedButton.resetForTests();
        ReelBlockButton.resetForTests();
        ReelDownloadButton.resetForTests();
        ReelSoundButton.resetForTests();
        ReelTextButton.resetForTests();
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
        assertFalse(Settings.TEXT_BUTTON_ON_REELS.defaultValue);
        assertFalse(Settings.TEXT_BUTTON_ON_REELS.rebootApp);
        assertTrue("it has its own switch", Settings.TEXT_BUTTON_ON_REELS.getParentSettings().isEmpty());
    }

    @Test
    public void offOrPausedLeavesTheColumnAsItWas() {
        Column column = reel();
        Settings.TEXT_BUTTON_ON_REELS.save(false);
        assertFalse(ReelTextButton.on());
        assertNull(ReelTextButton.litho(column, null, 120, 30));
        assertNull(ReelTextButton.lithoAboveMore(column, null, 120, 30));

        Settings.TEXT_BUTTON_ON_REELS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelTextButton.on());
        assertNull(ReelTextButton.litho(column, null, 120, 30));
        assertEquals(0, bridge.icons);

        new ReelTextButton.Click().invoke(new View(activity));
        assertTrue("a tap after it went off sends nothing", grabbed.isEmpty());
    }

    @Test
    public void anAdGetsNoIcon() {
        assertNull(ReelTextButton.litho(new Column(new Item(Kind.AD)), null, 120, 30));
        assertEquals(0, bridge.icons);
    }

    @Test
    public void aBuildWithoutTheGlyphGetsNoIcon() {
        ReelTextButton.glyph = -1;
        assertNull(ReelTextButton.litho(reel(), null, 120, 30));
        assertEquals(0, bridge.icons);
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelTextButton.COUNT_REFUSED));
    }

    @Test
    public void aTapSendsTheReelsPageAndItsActivityToLens() {
        assertTrue(ReelTextButton.on());
        assertSame(bridge.made, ReelTextButton.litho(reel(), null, 120, 30));
        assertEquals("Copy text", String.valueOf(bridge.description));
        assertEquals(30, bridge.margin);
        assertEquals(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN), bridge.drawable.getColorFilter());
        assertNotNull(bridge.click);

        // The reel's page fills the screen and the column's icon is a small view on it.
        FrameLayout page = new FrameLayout(activity);
        View icon = new View(activity);
        page.addView(icon, new FrameLayout.LayoutParams(40, 40));
        activity.setContentView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        ShadowLooper.idleMainLooper();

        assertNull(bridge.click.invoke(icon));
        assertEquals(1, grabbed.size());
        assertSame("the page the icon sits on, not the icon", page, grabbed.get(0)[0]);
        assertSame(activity, grabbed.get(0)[1]);
        assertNull("a small view with no big parent is no page", TextGrab.pageOf(new View(activity)));
        assertNull(TextGrab.pageOf(null));
    }

    @Test
    public void overMoreItMakesUpTheMarginOnlyWhenItIsTheLastIcon() {
        Column column = reel();
        ReelNotInterestedButton.remember(column.config, new Object());
        long eightDp = ReelDownloadButton.PX_BITS | 23;
        bridge.moreTop = -23;

        assertSame(bridge.made, ReelTextButton.lithoAboveMore(column, null, 124, eightDp));
        assertEquals("alone over More it makes up Save's 23 pixels and the 23 More pulls up",
                ReelDownloadButton.PX_BITS | 46, bridge.margin);

        Settings.HIDE_SOUND_REELS.save(true);
        ReelTextButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the sound icon after it, that one does and this keeps Save's", eightDp, bridge.margin);

        Settings.HIDE_SOUND_REELS.save(false);
        Settings.REEL_BLOCK_BUTTON.save(true);
        ReelTextButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the Block icon after it, the same", eightDp, bridge.margin);

        Settings.REEL_BLOCK_BUTTON.save(false);
        ReelSoundButton.bridge = item -> null;
        Settings.HIDE_SOUND_REELS.save(true);
        ReelTextButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("a sound icon that won't show on this reel leaves it last", ReelDownloadButton.PX_BITS | 46, bridge.margin);
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        ReelTextButton.grabber = (page, from) -> {
            throw new NoSuchMethodError("BV2");
        };
        assertNull(new ReelTextButton.Click().invoke(new View(activity)));
        assertNull(new ReelTextButton.Click().invoke(null));
        ReelDownloadButton.bridge = new FakeBridge() {
            @Override public Object item(Object column) {
                throw new NoSuchMethodError("AZ1");
            }
        };
        assertNull(ReelTextButton.litho(reel(), null, 120, 30));
        assertNull(ReelTextButton.litho(null, null, 120, 30));
        assertFalse("the Errors were reported, not thrown", HookStatus.report().isEmpty());
    }
}

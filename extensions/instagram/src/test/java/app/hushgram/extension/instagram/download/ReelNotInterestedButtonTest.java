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
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Not interested button on reels (#191): the icon joins the column beside a reel only with its switch
 * on, only on an organic reel whose viewer's helper is known, and a tap runs Instagram's own Not
 * interested through that helper with the reel's item and state.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelNotInterestedButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ReelDownloadButton.Bridge originalBridge = ReelDownloadButton.bridge;
    private final ReelDownloadButton.Actions originalActions = ReelDownloadButton.actions;
    private final ReelNotInterestedButton.Bridge originalNotInterested = ReelNotInterestedButton.bridge;
    private final FakeBridge bridge = new FakeBridge();
    private final FakeNotInterested notInterested = new FakeNotInterested();

    private enum Kind { ORGANIC, AD }

    private static final class Item {
        final Kind kind;

        Item(Kind kind) {
            this.kind = kind;
        }
    }

    /** The column beside a reel: the reel it shows and the viewer's config its state holds. */
    private static final class Column {
        final Item item;
        final Object config;

        Column(Item item, Object config) {
            this.item = item;
            this.config = config;
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

    /** The column's config and state, and a record of what Instagram's Not interested was run with. */
    private static final class FakeNotInterested implements ReelNotInterestedButton.Bridge {
        final List<String> runs = new ArrayList<>();
        final Object state = new Object();
        RuntimeException failure;
        boolean noState;

        @Override public Object config(Object column) {
            if (failure != null) throw failure;
            return ((Column) column).config;
        }

        @Override public Object state(Object column) {
            return noState ? null : state;
        }

        @Override public void run(Object delegate, Object item, Object state) {
            runs.add(delegate + "|" + (item == null ? "null" : ((Item) item).kind) + "|" + (state == this.state));
        }
    }

    private static final class Delegate {
        @Override public String toString() {
            return "delegate";
        }
    }

    @Before
    public void setUp() {
        Robolectric.buildActivity(Activity.class).setup();
        ReelDownloadButton.bridge = bridge;
        ReelNotInterestedButton.bridge = notInterested;
        ReelNotInterestedButton.resetForTests();
        ReelDownloadButton.resetForTests();
        // No glyph of Instagram's here, so one of the framework's stands in.
        ReelNotInterestedButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        Settings.REEL_NOT_INTERESTED_BUTTON.save(true);
    }

    @After
    public void tearDown() {
        ReelDownloadButton.bridge = originalBridge;
        ReelDownloadButton.actions = originalActions;
        ReelNotInterestedButton.bridge = originalNotInterested;
        ReelNotInterestedButton.resetForTests();
        ReelDownloadButton.resetForTests();
        Settings.REEL_NOT_INTERESTED_BUTTON.resetToDefault();
        Settings.REEL_DOWNLOAD_BUTTON.resetToDefault();
        Settings.DOWNLOAD_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        assertFalse(Settings.REEL_NOT_INTERESTED_BUTTON.defaultValue);
        assertFalse(Settings.REEL_NOT_INTERESTED_BUTTON.rebootApp);
        assertTrue("it has its own switch, not Download on reels'", Settings.REEL_NOT_INTERESTED_BUTTON.getParentSettings().isEmpty());
    }

    @Test
    public void offOrPausedLeavesTheColumnAsItWas() {
        Object config = new Object();
        Delegate delegate = new Delegate();
        assertTrue(ReelNotInterestedButton.remember(config, delegate));
        Column column = new Column(new Item(Kind.ORGANIC), config);

        Settings.REEL_NOT_INTERESTED_BUTTON.save(false);
        assertFalse(ReelNotInterestedButton.on());
        assertNull(ReelNotInterestedButton.litho(column, null, 120, 30));
        assertNull(ReelNotInterestedButton.lithoAboveMore(column, null, 120, 30));

        Settings.REEL_NOT_INTERESTED_BUTTON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelNotInterestedButton.on());
        assertNull(ReelNotInterestedButton.litho(column, null, 120, 30));
        assertEquals(0, bridge.icons);

        ReelNotInterestedButton.tap(column);
        assertTrue("a tap after it went off runs nothing", notInterested.runs.isEmpty());
    }

    @Test
    public void anAdOrACardGetsNoIcon() {
        Object config = new Object();
        ReelNotInterestedButton.remember(config, new Delegate());
        Column ad = new Column(new Item(Kind.AD), config);
        assertNull(ReelNotInterestedButton.litho(ad, null, 120, 30));
        assertEquals(0, bridge.icons);
        ReelNotInterestedButton.tap(ad);
        assertTrue(notInterested.runs.isEmpty());
    }

    @Test
    public void withoutTheHelperThereIsNoIconAndNothingThrows() {
        Column column = new Column(new Item(Kind.ORGANIC), new Object());
        assertNull("no delegate was reported for this config", ReelNotInterestedButton.litho(column, null, 120, 30));
        assertEquals(0, bridge.icons);
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelNotInterestedButton.COUNT_NO_HELPER));

        Object config = new Object();
        ReelNotInterestedButton.remember(config, new Delegate());
        notInterested.noState = true;
        ReelNotInterestedButton.tap(new Column(new Item(Kind.ORGANIC), config));
        assertTrue("and a tap with no state runs nothing", notInterested.runs.isEmpty());
    }

    @Test
    public void aReelWithTheHelperGetsTheIconWithSavesSizeAndMarginInWhite() {
        Object config = new Object();
        ReelNotInterestedButton.remember(config, new Delegate());
        Column column = new Column(new Item(Kind.ORGANIC), config);
        assertTrue(ReelNotInterestedButton.on());
        assertSame(bridge.made, ReelNotInterestedButton.litho(column, null, 120, 30));
        assertEquals(120, bridge.size);
        assertEquals(30, bridge.margin);
        assertEquals("Not interested", String.valueOf(bridge.description));
        assertNotNull(bridge.click);
        assertEquals(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN), bridge.drawable.getColorFilter());

        ColorFilter instagrams = new PorterDuffColorFilter(0xFF262626, PorterDuff.Mode.SRC_IN);
        assertSame(bridge.made, ReelNotInterestedButton.litho(column, instagrams, 120, 30));
        assertSame("Instagram's own tint where it has one", instagrams, bridge.drawable.getColorFilter());
        assertTrue("drawn in the middle of the box, not filling it", bridge.drawable instanceof ReelNotInterestedButton.Centered);
        float density = app.hushgram.extension.shared.Utils.getContext().getResources().getDisplayMetrics().density;
        assertEquals(Math.round(26 * density), ((ReelNotInterestedButton.Centered) bridge.drawable).side);
    }

    @Test
    public void theGlyphTakesTheMiddleOfTheColumnsBoxAtItsOwnSize() {
        Recording glyph = new Recording();
        ReelNotInterestedButton.Centered centered = new ReelNotInterestedButton.Centered(glyph, 52);
        assertEquals("no size of its own, so the column hands it the box unscaled", -1, centered.getIntrinsicWidth());
        assertEquals(-1, centered.getIntrinsicHeight());

        centered.setBounds(616, 1004, 704, 1092);
        assertEquals(new Rect(634, 1022, 686, 1074), glyph.getBounds());
        centered.setBounds(0, 0, 40, 30);
        assertEquals("a box smaller than the glyph", new Rect(5, 0, 35, 30), glyph.getBounds());

        ColorFilter white = new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        centered.setColorFilter(white);
        assertSame(white, glyph.getColorFilter());
        assertSame(white, centered.getColorFilter());
        centered.setAlpha(77);
        assertEquals(77, glyph.alpha);

        int[] redrawn = {0};
        centered.setCallback(new Drawable.Callback() {
            @Override public void invalidateDrawable(Drawable who) {
                if (who == centered) redrawn[0]++;
            }

            @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
            }

            @Override public void unscheduleDrawable(Drawable who, Runnable what) {
            }
        });
        glyph.invalidateSelf();
        assertEquals("a glyph that redraws itself redraws the box", 1, redrawn[0]);
    }

    /** A glyph that remembers what it was given. */
    private static final class Recording extends Drawable {
        int alpha = 255;
        ColorFilter filter;

        @Override public void draw(Canvas canvas) {
        }

        @Override public void setAlpha(int alpha) {
            this.alpha = alpha;
        }

        @Override public void setColorFilter(ColorFilter filter) {
            this.filter = filter;
        }

        @Override public ColorFilter getColorFilter() {
            return filter;
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    @Test
    public void aBuildWithoutTheGlyphGetsNoIcon() {
        Object config = new Object();
        ReelNotInterestedButton.remember(config, new Delegate());
        ReelNotInterestedButton.glyph = -1;
        assertNull(ReelNotInterestedButton.litho(new Column(new Item(Kind.ORGANIC), config), null, 120, 30));
        assertEquals(0, bridge.icons);
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelNotInterestedButton.COUNT_REFUSED));
    }

    @Test
    public void aTapRunsInstagramsNotInterestedThroughTheDelegateOfThatViewer() {
        Object config = new Object();
        Object other = new Object();
        Delegate delegate = new Delegate();
        ReelNotInterestedButton.remember(config, delegate);
        ReelNotInterestedButton.remember(other, new Object() {
            @Override public String toString() {
                return "other delegate";
            }
        });
        Column column = new Column(new Item(Kind.ORGANIC), config);
        ReelNotInterestedButton.litho(column, null, 120, 30);

        assertNull(bridge.click.invoke(new Object()));
        assertEquals("the delegate of this viewer's config, this reel's item and the column's state",
                List.of("delegate|ORGANIC|true"), notInterested.runs);
    }

    @Test
    public void theLastIconOverMoreMakesUpTheMarginMoreTakesBack() {
        Object config = new Object();
        ReelNotInterestedButton.remember(config, new Delegate());
        Column column = new Column(new Item(Kind.ORGANIC), config);
        long eightDp = ReelDownloadButton.PX_BITS | 23;
        bridge.moreTop = -23;

        assertSame(bridge.made, ReelNotInterestedButton.lithoAboveMore(column, null, 124, eightDp));
        assertEquals("alone over More it makes up Save's 23 pixels and the 23 More pulls up",
                ReelDownloadButton.PX_BITS | 46, bridge.margin);

        ReelDownloadButton.actions = new SavableActions();
        Settings.DOWNLOAD_REELS.save(true);
        Settings.REEL_DOWNLOAD_BUTTON.save(true);
        ReelNotInterestedButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the Download icon after it, that one does and this keeps Save's", eightDp, bridge.margin);

        ReelNotInterestedButton.litho(column, null, 124, eightDp);
        assertEquals("over Share it keeps Save's margin", eightDp, bridge.margin);
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        Object config = new Object();
        ReelNotInterestedButton.remember(config, new Delegate());
        Column column = new Column(new Item(Kind.ORGANIC), config);

        notInterested.failure = new IllegalStateException("unreadable");
        assertNull(ReelNotInterestedButton.litho(column, null, 120, 30));
        ReelNotInterestedButton.tap(column);
        assertNull(ReelNotInterestedButton.litho(null, null, 120, 30));
        assertFalse(ReelNotInterestedButton.remember(null, new Delegate()));
        assertFalse(ReelNotInterestedButton.remember(new Object(), null));

        notInterested.failure = null;
        ReelNotInterestedButton.bridge = new ReelNotInterestedButton.Bridge() {
            @Override public Object config(Object column) {
                return config;
            }

            @Override public Object state(Object column) {
                return new Object();
            }

            @Override public void run(Object delegate, Object item, Object state) {
                throw new NoSuchMethodError("handleNotInterested");
            }
        };
        ReelNotInterestedButton.tap(column);
        assertNull(new ReelNotInterestedButton.Click(column).invoke(new Object()));
        assertFalse("the Error was reported, not thrown", HookStatus.report().isEmpty());
    }

    @Test
    public void theDelegatesAreRememberedByIdentityAndKeptFew() {
        Object config = new Object();
        Delegate first = new Delegate();
        Delegate second = new Delegate();
        ReelNotInterestedButton.remember(config, first);
        assertSame(first, ReelNotInterestedButton.delegateFor(config));
        ReelNotInterestedButton.remember(config, second);
        assertSame("a viewer reporting again replaces its delegate", second, ReelNotInterestedButton.delegateFor(config));
        assertNull(ReelNotInterestedButton.delegateFor(new Object()));
        assertNull(ReelNotInterestedButton.delegateFor(null));

        List<Object> keep = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Object each = new Object();
            keep.add(each);
            ReelNotInterestedButton.remember(each, new Delegate());
        }
        assertNull("the oldest were let go", ReelNotInterestedButton.delegateFor(config));
        assertNotNull(ReelNotInterestedButton.delegateFor(keep.get(19)));
    }

    /** Download's side: every reel has something to save. */
    private static final class SavableActions implements ReelDownloadButton.Actions {
        @Override public boolean savable(Object media) {
            return true;
        }

        @Override public boolean music(Object media) {
            return false;
        }

        @Override public boolean save(String row, Object media, Activity activity) {
            return true;
        }

        @Override public void choose(android.view.View anchor, Runnable video, Runnable photo) {
        }

        @Override public void nothing(Context context) {
        }
    }
}

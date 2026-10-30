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
import org.robolectric.annotation.Config;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Block button on reels (#191): the icon joins the column beside a reel only with its switch on, only
 * on an organic reel of someone else whose viewer's delegate is known, and a tap opens Instagram's own
 * block sheet for the reel's owner through that delegate, with the Reels tab's module and the owner's id.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelBlockButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ReelDownloadButton.Bridge originalBridge = ReelDownloadButton.bridge;
    private final ReelNotInterestedButton.Bridge originalNotInterested = ReelNotInterestedButton.bridge;
    private final ReelBlockButton.Bridge originalBlock = ReelBlockButton.bridge;
    private final Supplier<String> originalAccounts = DownloadHistory.accounts;
    private final FakeBridge bridge = new FakeBridge();
    private final FakeBlock block = new FakeBlock();

    private enum Kind { ORGANIC, AD }

    /** A reel: what kind it is and who posted it. */
    private static final class Item {
        final Kind kind;
        final String ownerId;

        Item(Kind kind, String ownerId) {
            this.kind = kind;
            this.ownerId = ownerId;
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
    private static final class FakeBridge implements ReelDownloadButton.Bridge {
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

    /** The column's config for the Not interested side, which this icon borrows the delegate through. */
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

    /** The reel's owner and a record of what Instagram's block sheet was opened with. */
    private static final class FakeBlock implements ReelBlockButton.Bridge {
        final List<String> opened = new ArrayList<>();
        RuntimeException failure;

        @Override public Object owner(Object item) {
            if (failure != null) throw failure;
            String id = ((Item) item).ownerId;
            return id == null ? null : "user " + id;
        }

        @Override public String ownerId(Object item) {
            return ((Item) item).ownerId;
        }

        @Override public void block(Object delegate, Object owner, String module, String ownerId) {
            opened.add(delegate + "|" + owner + "|" + module + "|" + ownerId);
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
        ReelNotInterestedButton.bridge = new FakeNotInterested();
        ReelBlockButton.bridge = block;
        DownloadHistory.accounts = () -> "1";
        ReelNotInterestedButton.resetForTests();
        ReelBlockButton.resetForTests();
        ReelDownloadButton.resetForTests();
        // No glyph of Instagram's here, so one of the framework's stands in.
        ReelBlockButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        ReelNotInterestedButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        Settings.REEL_BLOCK_BUTTON.save(true);
    }

    @After
    public void tearDown() {
        ReelDownloadButton.bridge = originalBridge;
        ReelNotInterestedButton.bridge = originalNotInterested;
        ReelBlockButton.bridge = originalBlock;
        DownloadHistory.accounts = originalAccounts;
        ReelNotInterestedButton.resetForTests();
        ReelBlockButton.resetForTests();
        ReelDownloadButton.resetForTests();
        Settings.REEL_BLOCK_BUTTON.resetToDefault();
        Settings.REEL_NOT_INTERESTED_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Someone else's reel in a viewer whose delegate is known. */
    private Column theirs() {
        Object config = new Object();
        ReelNotInterestedButton.remember(config, new Delegate());
        return new Column(new Item(Kind.ORGANIC, "2"), config);
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        assertFalse(Settings.REEL_BLOCK_BUTTON.defaultValue);
        assertFalse(Settings.REEL_BLOCK_BUTTON.rebootApp);
        assertTrue("it has its own switch", Settings.REEL_BLOCK_BUTTON.getParentSettings().isEmpty());
    }

    @Test
    public void offOrPausedLeavesTheColumnAsItWas() {
        Column column = theirs();
        Settings.REEL_BLOCK_BUTTON.save(false);
        assertFalse(ReelBlockButton.on());
        assertNull(ReelBlockButton.litho(column, null, 120, 30));
        assertNull(ReelBlockButton.lithoAboveMore(column, null, 120, 30));

        Settings.REEL_BLOCK_BUTTON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelBlockButton.on());
        assertNull(ReelBlockButton.litho(column, null, 120, 30));
        assertEquals(0, bridge.icons);

        ReelBlockButton.tap(column);
        assertTrue("a tap after it went off opens nothing", block.opened.isEmpty());
    }

    @Test
    public void anAdGetsNoIcon() {
        Object config = new Object();
        ReelNotInterestedButton.remember(config, new Delegate());
        Column ad = new Column(new Item(Kind.AD, "2"), config);
        assertNull(ReelBlockButton.litho(ad, null, 120, 30));
        ReelBlockButton.tap(ad);
        assertEquals(0, bridge.icons);
        assertTrue(block.opened.isEmpty());
    }

    @Test
    public void yourOwnReelGetsNoIcon() {
        Object config = new Object();
        ReelNotInterestedButton.remember(config, new Delegate());
        Column yours = new Column(new Item(Kind.ORGANIC, "1"), config);
        assertNull(ReelBlockButton.litho(yours, null, 120, 30));
        ReelBlockButton.tap(yours);
        assertEquals(0, bridge.icons);
        assertTrue("never a block sheet for the account in front", block.opened.isEmpty());
    }

    @Test
    public void withoutTheDelegateOrTheOwnerThereIsNoIcon() {
        assertNull("no delegate was reported for this config",
                ReelBlockButton.litho(new Column(new Item(Kind.ORGANIC, "2"), new Object()), null, 120, 30));
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelBlockButton.COUNT_NO_HELPER));

        Object config = new Object();
        ReelNotInterestedButton.remember(config, new Delegate());
        Column nobody = new Column(new Item(Kind.ORGANIC, null), config);
        assertNull(ReelBlockButton.litho(nobody, null, 120, 30));
        ReelBlockButton.tap(nobody);
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelBlockButton.COUNT_NO_OWNER));
        assertEquals(0, bridge.icons);
        assertTrue(block.opened.isEmpty());
    }

    @Test
    public void aBuildWithoutTheGlyphGetsNoIcon() {
        ReelBlockButton.glyph = -1;
        assertNull(ReelBlockButton.litho(theirs(), null, 120, 30));
        assertEquals(0, bridge.icons);
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelBlockButton.COUNT_REFUSED));
    }

    @Test
    public void aTapOpensInstagramsBlockSheetForTheReelsOwner() {
        Column column = theirs();
        assertTrue(ReelBlockButton.on());
        assertSame(bridge.made, ReelBlockButton.litho(column, null, 120, 30));
        assertEquals("Block", String.valueOf(bridge.description));
        assertEquals(30, bridge.margin);
        assertEquals(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN), bridge.drawable.getColorFilter());
        assertNotNull(bridge.click);

        assertNull(bridge.click.invoke(new Object()));
        assertEquals("the viewer's delegate, the reel's owner, the Reels tab and the owner's id",
                List.of("delegate|user 2|clips_viewer_clips_tab|2"), block.opened);
    }

    @Test
    public void overMoreItMakesUpTheMarginOnlyWhenItIsTheLastIcon() {
        Column column = theirs();
        long eightDp = ReelDownloadButton.PX_BITS | 23;
        bridge.moreTop = -23;

        assertSame(bridge.made, ReelBlockButton.lithoAboveMore(column, null, 124, eightDp));
        assertEquals("alone over More it makes up Save's 23 pixels and the 23 More pulls up",
                ReelDownloadButton.PX_BITS | 46, bridge.margin);

        Settings.REEL_NOT_INTERESTED_BUTTON.save(true);
        ReelBlockButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the Not interested icon after it, that one does and this keeps Save's", eightDp, bridge.margin);
    }

    @Test
    public void theOwnersIdIsTheLastPartOfTheMediaId() {
        assertEquals("2", ReelBlockButton.ownerOf("3141_2"));
        assertNull(ReelBlockButton.ownerOf("3141"));
        assertNull(ReelBlockButton.ownerOf("3141_"));
        assertNull(ReelBlockButton.ownerOf(null));
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        Column column = theirs();
        block.failure = new IllegalStateException("unreadable");
        assertNull(ReelBlockButton.litho(column, null, 120, 30));
        ReelBlockButton.tap(column);
        assertNull(ReelBlockButton.litho(null, null, 120, 30));

        block.failure = null;
        ReelBlockButton.bridge = new ReelBlockButton.Bridge() {
            @Override public Object owner(Object item) {
                return "user 2";
            }

            @Override public String ownerId(Object item) {
                return "2";
            }

            @Override public void block(Object delegate, Object owner, String module, String ownerId) {
                throw new NoSuchMethodError("A04");
            }
        };
        ReelBlockButton.tap(column);
        assertNull(new ReelBlockButton.Click(column).invoke(new Object()));
        assertFalse("the Error was reported, not thrown", HookStatus.report().isEmpty());
    }
}

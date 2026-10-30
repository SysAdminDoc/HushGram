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

import java.util.Arrays;
import java.util.Collections;
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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.reels.HiddenSounds;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The sound icon beside a reel, for Hide reels by sound (#191): it joins the column only with its
 * switch on and only on an organic reel whose sound can be read, a tap puts that sound on the hidden
 * list and a second tap takes it off, each with a toast naming the sound.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelSoundButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ReelDownloadButton.Bridge originalBridge = ReelDownloadButton.bridge;
    private final ReelNotInterestedButton.Bridge originalNotInterested = ReelNotInterestedButton.bridge;
    private final ReelBlockButton.Bridge originalBlock = ReelBlockButton.bridge;
    private final ReelSoundButton.Bridge originalSound = ReelSoundButton.bridge;
    private final Supplier<String> originalAccounts = DownloadHistory.accounts;
    private final FakeBridge bridge = new FakeBridge();

    private enum Kind { ORGANIC, AD }

    /** A reel: what kind it is and its sound, which can be null. */
    private static final class Item {
        final Kind kind;
        final HiddenSounds.Sound sound;

        Item(Kind kind, HiddenSounds.Sound sound) {
            this.kind = kind;
            this.sound = sound;
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

    /** The column's config, so the Not interested and Block icons can tell whether they show. */
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
        Robolectric.buildActivity(Activity.class).setup();
        ReelDownloadButton.bridge = bridge;
        ReelNotInterestedButton.bridge = new FakeNotInterested();
        ReelBlockButton.bridge = new FakeBlock();
        ReelSoundButton.bridge = item -> ((Item) item).sound;
        DownloadHistory.accounts = () -> "1";
        ReelNotInterestedButton.resetForTests();
        ReelBlockButton.resetForTests();
        ReelDownloadButton.resetForTests();
        ReelSoundButton.resetForTests();
        // No glyph of Instagram's here, so one of the framework's stands in.
        ReelSoundButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        ReelBlockButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        ReelNotInterestedButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        Settings.HIDDEN_SOUNDS.save("");
        Settings.HIDE_SOUND_REELS.save(true);
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
        Settings.HIDE_SOUND_REELS.resetToDefault();
        Settings.HIDDEN_SOUNDS.resetToDefault();
        Settings.REEL_BLOCK_BUTTON.resetToDefault();
        Settings.REEL_NOT_INTERESTED_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static Column espresso() {
        return new Column(new Item(Kind.ORGANIC, new HiddenSounds.Sound("111", "Espresso", "Sabrina Carpenter")));
    }

    private static String toast() {
        ShadowLooper.idleMainLooper();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void theSwitchStartsOffNeedsNoRestartAndTheListStaysOutOfBackups() {
        assertFalse(Settings.HIDE_SOUND_REELS.defaultValue);
        assertFalse(Settings.HIDE_SOUND_REELS.rebootApp);
        assertTrue("it has its own switch", Settings.HIDE_SOUND_REELS.getParentSettings().isEmpty());
        assertEquals("", Settings.HIDDEN_SOUNDS.defaultValue);
        assertFalse(Settings.HIDDEN_SOUNDS.includeWithImportExport);
    }

    @Test
    public void offOrPausedLeavesTheColumnAsItWas() {
        Column column = espresso();
        Settings.HIDE_SOUND_REELS.save(false);
        assertFalse(ReelSoundButton.on());
        assertNull(ReelSoundButton.litho(column, null, 120, 30));
        assertNull(ReelSoundButton.lithoAboveMore(column, null, 120, 30));

        Settings.HIDE_SOUND_REELS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelSoundButton.on());
        assertNull(ReelSoundButton.litho(column, null, 120, 30));
        assertEquals(0, bridge.icons);

        ReelSoundButton.tap(column);
        assertEquals("a tap after it went off hides nothing", "", Settings.HIDDEN_SOUNDS.savedValue());
    }

    @Test
    public void anAdOrAReelWithoutASoundGetsNoIcon() {
        Column ad = new Column(new Item(Kind.AD, new HiddenSounds.Sound("111", "Espresso", "")));
        assertNull(ReelSoundButton.litho(ad, null, 120, 30));
        ReelSoundButton.tap(ad);

        Column silent = new Column(new Item(Kind.ORGANIC, null));
        assertNull(ReelSoundButton.litho(silent, null, 120, 30));
        ReelSoundButton.tap(silent);
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelSoundButton.COUNT_NO_SOUND));
        assertEquals(0, bridge.icons);
        assertEquals("", Settings.HIDDEN_SOUNDS.savedValue());
    }

    @Test
    public void aBuildWithoutTheGlyphGetsNoIcon() {
        ReelSoundButton.glyph = -1;
        assertNull(ReelSoundButton.litho(espresso(), null, 120, 30));
        assertEquals(0, bridge.icons);
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelSoundButton.COUNT_REFUSED));
    }

    @Test
    public void aTapHidesTheSoundAndASecondTapBringsItBack() {
        Column column = espresso();
        assertTrue(ReelSoundButton.on());
        assertSame(bridge.made, ReelSoundButton.litho(column, null, 120, 30));
        assertEquals("Hide this sound", String.valueOf(bridge.description));
        assertEquals(30, bridge.margin);
        assertEquals(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN), bridge.drawable.getColorFilter());
        assertNotNull(bridge.click);

        assertNull(bridge.click.invoke(new Object()));
        assertEquals("Hiding reels with " + L10n.isolate("Espresso by Sabrina Carpenter"), toast());
        assertTrue(HiddenSounds.hides("111"));
        assertEquals("111\tEspresso\tSabrina Carpenter", Settings.HIDDEN_SOUNDS.savedValue());

        bridge.click.invoke(new Object());
        assertEquals("Showing reels with " + L10n.isolate("Espresso by Sabrina Carpenter") + " again", toast());
        assertFalse(HiddenSounds.hides("111"));
        assertEquals("", Settings.HIDDEN_SOUNDS.savedValue());
    }

    @Test
    public void aReelsOwnSoundIsNamedByWhatIsKnown() {
        Context context = Utils.getContext();
        assertEquals("Espresso by Sabrina Carpenter",
                ReelSoundButton.label(context, new HiddenSounds.Sound("1", "Espresso", "Sabrina Carpenter")));
        assertEquals("Original audio", ReelSoundButton.label(context, new HiddenSounds.Sound("1", "Original audio", "")));
        assertEquals("@nasa", ReelSoundButton.label(context, new HiddenSounds.Sound("1", "", "@nasa")));
        assertEquals("this sound", ReelSoundButton.label(context, new HiddenSounds.Sound("1", "", "")));
        assertEquals("this sound", ReelSoundButton.label(null, new HiddenSounds.Sound("1", "", "")));

        ReelSoundButton.tap(new Column(new Item(Kind.ORGANIC, new HiddenSounds.Sound("222", "", ""))));
        assertEquals("Hiding reels with " + L10n.isolate("this sound"), toast());
        assertEquals(Collections.singletonList("222"), ids());
    }

    @Test
    public void overMoreItMakesUpTheMarginOnlyWhenItIsTheLastIcon() {
        Column column = espresso();
        ReelNotInterestedButton.remember(column.config, new Object());
        long eightDp = ReelDownloadButton.PX_BITS | 23;
        bridge.moreTop = -23;

        assertSame(bridge.made, ReelSoundButton.lithoAboveMore(column, null, 124, eightDp));
        assertEquals("alone over More it makes up Save's 23 pixels and the 23 More pulls up",
                ReelDownloadButton.PX_BITS | 46, bridge.margin);

        Settings.REEL_BLOCK_BUTTON.save(true);
        ReelSoundButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the Block icon after it, that one does and this keeps Save's", eightDp, bridge.margin);

        Settings.REEL_BLOCK_BUTTON.save(false);
        Settings.REEL_NOT_INTERESTED_BUTTON.save(true);
        ReelSoundButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the Not interested icon after it, the same", eightDp, bridge.margin);
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        Column column = espresso();
        ReelSoundButton.bridge = item -> {
            throw new NoSuchMethodError("BV2");
        };
        assertNull(ReelSoundButton.litho(column, null, 120, 30));
        ReelSoundButton.tap(column);
        assertNull(new ReelSoundButton.Click(column).invoke(new Object()));
        assertNull(ReelSoundButton.litho(null, null, 120, 30));
        assertEquals(0, bridge.icons);
        assertFalse("the Error was reported, not thrown", HookStatus.report().isEmpty());
    }

    private static List<String> ids() {
        String[] ids = HiddenSounds.saved().stream().map(sound -> sound.id).toArray(String[]::new);
        return Arrays.asList(ids);
    }
}

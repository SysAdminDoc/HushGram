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
import android.content.res.Resources;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.feed.FeedItemKinds;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Download button on reels (#178): the icon joins the column beside a reel only with its switch and
 * Download on reels on, only on a reel with something to save, and a tap saves the reel the column
 * shows at that moment through the menu's own save.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelDownloadButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ReelDownloadButton.Actions originalActions = ReelDownloadButton.actions;
    private final ReelDownloadButton.Bridge originalBridge = ReelDownloadButton.bridge;
    private final Recorder recorder = new Recorder();
    private final FakeBridge bridge = new FakeBridge();
    private Activity activity;

    /** A reel's kinds, as Instagram's enum names them. */
    enum Kind { ORGANIC, AD }

    /** A reel's item: its kind and its Media. */
    static final class Item {
        final Kind kind;
        final Object media;

        Item(Kind kind, Object media) {
            this.kind = kind;
            this.media = media;
        }
    }

    /** The column beside a reel, holding the reel it shows now. */
    static final class Column {
        Item item;

        Column(Item item) {
            this.item = item;
        }
    }

    /** Reads the fake column and item, answers More's top margin, and keeps what the icon was made with. */
    private static class FakeBridge implements ReelDownloadButton.Bridge {
        final Object made = new Object();
        int items;
        int icons;
        RuntimeException failure;
        Drawable drawable;
        CharSequence description;
        Function1<Object, Object> click;
        long size;
        long margin;
        int moreTop;
        int moreTops;

        @Override public Object item(Object column) {
            items++;
            if (failure != null) throw failure;
            return ((Column) column).item;
        }

        @Override public Object media(Object item) {
            return ((Item) item).media;
        }

        @Override public String kind(Object item) throws ReflectiveOperationException {
            return FeedItemKinds.kindOf(item, ReelDownloadButton.ORGANIC, FamilyNames.REEL_DOWNLOAD);
        }

        @Override public int moreTop(Object column, Context context) {
            moreTops++;
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

    /** The calls a tap makes, and the Media with nothing to save or with music. */
    private static final class Recorder implements ReelDownloadButton.Actions {
        final List<String> calls = new ArrayList<>();
        final Set<Object> empty = new HashSet<>();
        final Set<Object> music = new HashSet<>();
        Runnable video;
        Runnable photo;

        @Override public boolean savable(Object media) {
            return !empty.contains(media);
        }

        @Override public boolean music(Object media) {
            return music.contains(media);
        }

        @Override public boolean save(String row, Object media, Activity activity) {
            calls.add("save " + row + " " + media);
            return true;
        }

        @Override public void choose(View anchor, Runnable video, Runnable photo) {
            calls.add("choose");
            this.video = video;
            this.photo = photo;
        }

        @Override public void nothing(Context context) {
            calls.add("nothing");
        }
    }

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        ReelDownloadButton.actions = recorder;
        ReelDownloadButton.bridge = bridge;
        ReelDownloadButton.resetForTests();
        Settings.DOWNLOAD_REELS.save(true);
        Settings.REEL_DOWNLOAD_BUTTON.save(true);
    }

    @After
    public void tearDown() {
        ReelDownloadButton.actions = originalActions;
        ReelDownloadButton.bridge = originalBridge;
        ReelDownloadButton.resetForTests();
        Settings.REEL_DOWNLOAD_BUTTON.resetToDefault();
        Settings.DOWNLOAD_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndWaitsForDownloadOnReels() {
        assertFalse(Settings.REEL_DOWNLOAD_BUTTON.defaultValue);
        assertFalse("no restart needed", Settings.REEL_DOWNLOAD_BUTTON.rebootApp);
        assertEquals(Collections.singletonList(Settings.DOWNLOAD_REELS), Settings.REEL_DOWNLOAD_BUTTON.getParentSettings());
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse(Settings.REEL_DOWNLOAD_BUTTON.isAvailable());
        Settings.DOWNLOAD_REELS.save(true);
        assertTrue(Settings.REEL_DOWNLOAD_BUTTON.isAvailable());
    }

    @Test
    public void offOrPausedLeavesTheColumnAsItWas() {
        Column column = new Column(new Item(Kind.ORGANIC, "reel"));
        Settings.REEL_DOWNLOAD_BUTTON.save(false);
        assertFalse(ReelDownloadButton.on());
        assertNull(ReelDownloadButton.litho(column, null, 120, 30));

        Settings.REEL_DOWNLOAD_BUTTON.save(true);
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse("it needs Download on reels", ReelDownloadButton.on());
        assertNull(ReelDownloadButton.litho(column, null, 120, 30));

        Settings.DOWNLOAD_REELS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelDownloadButton.on());
        assertNull(ReelDownloadButton.litho(column, null, 120, 30));
        assertEquals("nothing was read while off", 0, bridge.items);
        assertEquals(0, bridge.icons);
    }

    @Test
    public void aReelGetsTheIconWithSavesSizeAndMarginInWhite() {
        Column column = new Column(new Item(Kind.ORGANIC, "reel"));
        assertTrue(ReelDownloadButton.on());
        assertSame(bridge.made, ReelDownloadButton.litho(column, null, 120, 30));
        assertEquals(120, bridge.size);
        assertEquals(30, bridge.margin);
        assertEquals("over Share, More's margin isn't asked", 0, bridge.moreTops);
        assertEquals("Download", String.valueOf(bridge.description));
        assertNotNull(bridge.click);
        assertTrue("this build has no glyph of Instagram's, so HushGram's own", bridge.drawable instanceof FeedDownloadButton.Glyph);
        ColorFilter tint = bridge.drawable.getColorFilter();
        assertEquals(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN), tint);

        ColorFilter instagrams = new PorterDuffColorFilter(0xFF262626, PorterDuff.Mode.SRC_IN);
        assertSame(bridge.made, ReelDownloadButton.litho(column, instagrams, 120, 30));
        assertSame("Instagram's own tint where it has one", instagrams, bridge.drawable.getColorFilter());
    }

    @Test
    public void theColumnsOwn44dpGlyphComesBeforeThe24dpOne() {
        Map<String, Integer> both = new HashMap<>();
        both.put("instagram_download_outline_24", 0x7f082462);
        both.put("instagram_download_outline_44", 0x7f082463);
        assertEquals("Save's size of glyph, so the icon has Save's weight (#198)", 0x7f082463,
                ReelDownloadButton.glyphId(new Named(both), "com.instagram.android"));

        Map<String, Integer> small = new HashMap<>();
        small.put("instagram_download_outline_24", 0x7f082462);
        assertEquals("a build without it gets the 24dp one", 0x7f082462,
                ReelDownloadButton.glyphId(new Named(small), "com.instagram.android"));
        assertEquals("and one with neither gets HushGram's own", -1,
                ReelDownloadButton.glyphId(new Named(new HashMap<>()), "com.instagram.android"));
    }

    /** Resources that know only the drawables [ids] names. */
    @SuppressWarnings("deprecation")
    private static final class Named extends Resources {
        private final Map<String, Integer> ids;

        Named(Map<String, Integer> ids) {
            super(Resources.getSystem().getAssets(), Resources.getSystem().getDisplayMetrics(),
                    Resources.getSystem().getConfiguration());
            this.ids = ids;
        }

        @Override
        public int getIdentifier(String name, String type, String packageName) {
            Integer id = "drawable".equals(type) && "com.instagram.android".equals(packageName) ? ids.get(name) : null;
            return id == null ? 0 : id;
        }
    }

    @Test
    public void overMoreTheIconMakesUpTheMarginMoreTakesBack() {
        Column column = new Column(new Item(Kind.ORGANIC, "reel"));
        long eightDp = ReelDownloadButton.PX_BITS | 23;
        // More's top margin is minus 8dp, 23 pixels on the S25, while the share count is hidden.
        bridge.moreTop = -23;
        assertSame(bridge.made, ReelDownloadButton.lithoAboveMore(column, null, 124, eightDp));
        assertEquals("Save's 23 pixels and the 23 More pulls up", ReelDownloadButton.PX_BITS | 46, bridge.margin);
        assertEquals(124, bridge.size);

        bridge.moreTop = 0;
        ReelDownloadButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the share count showing More takes no margin, so Save's stays", eightDp, bridge.margin);

        bridge.moreTop = -23;
        ReelDownloadButton.litho(column, null, 124, eightDp);
        assertEquals("over Share the icon keeps Save's margin", eightDp, bridge.margin);
        assertEquals("only the place over More asks for More's margin", 2, bridge.moreTops);
    }

    @Test
    public void theMarginOverMoreIsWorkedOutInPixelsOnly() {
        long px = ReelDownloadButton.PX_BITS;
        assertEquals(px | 46, ReelDownloadButton.marginAboveMore(px | 23, -23));
        assertEquals(px | 23, ReelDownloadButton.marginAboveMore(px | 23, 0));
        assertEquals("More pushed down takes from the margin", px | 3, ReelDownloadButton.marginAboveMore(px | 23, 20));
        assertEquals("never below none", px, ReelDownloadButton.marginAboveMore(px | 23, 40));
        assertEquals("no margin at all is none in pixels", px | 23, ReelDownloadButton.marginAboveMore(0, -23));
        long dp = Double.doubleToRawLongBits(8.0);
        assertEquals("a margin in dp is left alone", dp, ReelDownloadButton.marginAboveMore(dp, -23));
        long sp = 0x7ffa000000000000L | Float.floatToRawIntBits(8f);
        assertEquals("and one in sp", sp, ReelDownloadButton.marginAboveMore(sp, -23));
    }

    @Test
    public void anUnreadableMoreLeavesSavesMarginAndTheIcon() {
        long eightDp = ReelDownloadButton.PX_BITS | 23;
        // A rewritten bridge that no longer fits throws an Error, not an exception.
        for (Throwable thrown : new Throwable[] {
                new IncompatibleClassChangeError("moreTop"), new NoSuchMethodError("moreTop"), new IllegalStateException("unreadable")}) {
            HookStatus.clear();
            FakeBridge failing = new FakeBridge() {
                @Override public int moreTop(Object column, Context context) {
                    if (thrown instanceof Error) throw (Error) thrown;
                    throw (RuntimeException) thrown;
                }
            };
            ReelDownloadButton.bridge = failing;
            String name = thrown.getClass().getSimpleName();
            assertSame(name + " still gets the icon", failing.made,
                    ReelDownloadButton.lithoAboveMore(new Column(new Item(Kind.ORGANIC, "reel")), null, 124, eightDp));
            assertEquals(name + " keeps Save's margin", eightDp, failing.margin);
            assertTrue(name + " is counted", String.join("\n", HookStatus.report()).contains(ReelDownloadButton.COUNT_MORE_UNREAD + " 1"));
            assertTrue(name + " isn't a miss, since the button works", HookStatus.missing(FamilyNames.REEL_DOWNLOAD).isEmpty());
        }
    }

    @Test
    public void aMarginNotInPixelsIsKeptAndCounted() {
        Column column = new Column(new Item(Kind.ORGANIC, "reel"));
        long dp = Double.doubleToRawLongBits(8.0);
        bridge.moreTop = -23;
        assertSame(bridge.made, ReelDownloadButton.lithoAboveMore(column, null, 124, dp));
        assertEquals("a margin in dp is handed on as it came", dp, bridge.margin);
        String report = String.join("\n", HookStatus.report());
        assertTrue("and counted, so a gap that's gone again shows", report.contains(ReelDownloadButton.COUNT_NOT_PIXELS + " 1"));
        assertTrue(HookStatus.missing(FamilyNames.REEL_DOWNLOAD).isEmpty());

        bridge.moreTop = 0;
        ReelDownloadButton.lithoAboveMore(column, null, 124, dp);
        bridge.moreTop = -23;
        ReelDownloadButton.lithoAboveMore(column, null, 124, ReelDownloadButton.PX_BITS | 23);
        ReelDownloadButton.litho(column, null, 124, dp);
        assertTrue("with nothing to make up, in pixels, or over Share, nothing more is counted",
                String.join("\n", HookStatus.report()).contains(ReelDownloadButton.COUNT_NOT_PIXELS + " 1"));
        assertFalse(String.join("\n", HookStatus.report()).contains(ReelDownloadButton.COUNT_MORE_UNREAD));
    }

    @Test
    public void anAdOrAReelWithNothingToSaveGetsNoIcon() {
        assertNull(ReelDownloadButton.litho(new Column(new Item(Kind.AD, "ad")), null, 120, 30));
        recorder.empty.add("bare");
        assertNull(ReelDownloadButton.litho(new Column(new Item(Kind.ORGANIC, "bare")), null, 120, 30));
        assertNull(ReelDownloadButton.litho(new Column(new Item(Kind.ORGANIC, null)), null, 120, 30));
        assertNull(ReelDownloadButton.litho(new Column(null), null, 120, 30));
        assertEquals(0, bridge.icons);
    }

    @Test
    public void aTapSavesTheReelTheColumnShowsNow() {
        Column column = new Column(new Item(Kind.ORGANIC, "first"));
        ReelDownloadButton.litho(column, null, 120, 30);
        // The row is reused for the next reel before the tap.
        column.item = new Item(Kind.ORGANIC, "second");
        bridge.click.invoke(new View(activity));
        assertEquals(Collections.singletonList("save null second"), recorder.calls);
    }

    @Test
    public void aPhotoWithMusicAsksForVideoOrPhoto() {
        recorder.music.add("song");
        Column column = new Column(new Item(Kind.ORGANIC, "song"));
        ReelDownloadButton.litho(column, null, 120, 30);
        bridge.click.invoke(new View(activity));
        assertEquals(Collections.singletonList("choose"), recorder.calls);
        recorder.video.run();
        recorder.photo.run();
        assertEquals(java.util.Arrays.asList("choose", "save " + ReelDownload.VIDEO_OPTION + " song",
                "save " + ReelDownload.PHOTO_OPTION + " song"), recorder.calls);
    }

    @Test
    public void aTapWithNothingToSaveSaysSoAndOffSaysNothing() {
        Column column = new Column(new Item(Kind.ORGANIC, "reel"));
        ReelDownloadButton.litho(column, null, 120, 30);
        recorder.empty.add("reel");
        bridge.click.invoke(new View(activity));
        assertEquals(Collections.singletonList("nothing"), recorder.calls);

        recorder.calls.clear();
        Settings.REEL_DOWNLOAD_BUTTON.save(false);
        bridge.click.invoke(new View(activity));
        assertTrue("switched off, a tap does nothing", recorder.calls.isEmpty());
    }

    @Test
    public void aFailureLeavesTheColumnAndNeverThrows() {
        Column column = new Column(new Item(Kind.ORGANIC, "reel"));
        ReelDownloadButton.litho(column, null, 120, 30);
        Function1<Object, Object> click = bridge.click;
        bridge.failure = new IllegalStateException("unreadable");
        assertNull(ReelDownloadButton.litho(column, null, 120, 30));
        assertNull(click.invoke(new View(activity)));
        assertNull(click.invoke(null));
        assertTrue(recorder.calls.isEmpty());
        assertTrue(String.join("\n", HookStatus.report()).contains("reel download button"));
    }
}

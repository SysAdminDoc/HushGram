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
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.LocaleList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Download button on feed posts (#97): where the icon goes in the row that holds Save, that it
 * follows its switch, and that a tap reaches the same save the post's menu uses.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedDownloadButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final FeedDownloadButton.Actions original = FeedDownloadButton.actions;
    private final FeedDownloadButton.Litho originalLitho = FeedDownloadButton.litho;
    private final Recorder recorder = new Recorder();
    private Activity activity;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        FeedDownloadButton.actions = recorder;
        FeedDownloadButton.resetForTests();
        Settings.DOWNLOAD_VIDEOS.save(true);
        Settings.FEED_DOWNLOAD_BUTTON.save(true);
    }

    @After
    public void tearDown() {
        FeedDownloadButton.actions = original;
        FeedDownloadButton.resetForTests();
        Settings.FEED_DOWNLOAD_BUTTON.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The calls a tap makes, and the carousel pages the post answers with. */
    private static final class Recorder implements FeedDownloadButton.Actions {
        final List<String> calls = new ArrayList<>();
        List<?> pages;
        boolean saves = true;
        Runnable page;
        Runnable all;

        @Override public List<?> pages(Object post) {
            return pages;
        }

        @Override public boolean save(Object post, Object itemState, Activity activity) {
            calls.add("save " + post + " " + itemState);
            return saves;
        }

        @Override public void saveAll(Object post, Activity activity) {
            calls.add("saveAll " + post);
        }

        @Override public void choose(View anchor, Runnable page, Runnable all) {
            calls.add("choose");
            this.page = page;
            this.all = all;
        }

        @Override public void nothing(Context context) {
            calls.add("nothing");
        }
    }

    private ImageView save(Context context) {
        ImageView save = new ImageView(context);
        save.setId(0x7f0b370e);
        save.setScaleType(ImageView.ScaleType.FIT_CENTER);
        save.setPaddingRelative(6, 7, 8, 9);
        return save;
    }

    /** Like, Comment, a stretching spacer, Save: the left buttons, then the right one. */
    private LinearLayout linearRow(ImageView save) {
        LinearLayout row = new LinearLayout(activity);
        row.addView(new View(activity), new LinearLayout.LayoutParams(40, 40));
        row.addView(new View(activity), new LinearLayout.LayoutParams(40, 40));
        row.addView(new View(activity), new LinearLayout.LayoutParams(0, 40, 1f));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(60, 44);
        params.gravity = Gravity.BOTTOM;
        params.topMargin = 3;
        params.bottomMargin = 5;
        row.addView(save, params);
        return row;
    }

    private static ImageView buttonOf(ViewGroup row) {
        ImageView found = FeedDownloadButton.existing(row);
        assertNotNull("the row has no download button", found);
        return found;
    }

    @Test
    public void theSwitchStartsOffAndWaitsForTheVideoOrThePhotoSwitch() {
        Settings.FEED_DOWNLOAD_BUTTON.resetToDefault();
        assertFalse(Settings.FEED_DOWNLOAD_BUTTON.get());
        assertEquals("hushgram_feed_download_button", Settings.FEED_DOWNLOAD_BUTTON.key);
        Settings.DOWNLOAD_VIDEOS.save(false);
        Settings.DOWNLOAD_PHOTOS.save(false);
        assertFalse(Settings.FEED_DOWNLOAD_BUTTON.isAvailable());
        Settings.DOWNLOAD_PHOTOS.save(true);
        assertTrue("the photo switch alone is enough", Settings.FEED_DOWNLOAD_BUTTON.isAvailable());
        Settings.DOWNLOAD_PHOTOS.save(false);
        Settings.DOWNLOAD_VIDEOS.save(true);
        assertTrue("the video switch alone is enough", Settings.FEED_DOWNLOAD_BUTTON.isAvailable());
    }

    @Test
    public void switchedOffTheRowIsInstagramsOwn() {
        Settings.FEED_DOWNLOAD_BUTTON.save(false);
        ImageView save = save(activity);
        LinearLayout row = linearRow(save);
        FeedDownloadButton.bind(save, "post", "state", activity);
        assertEquals(4, row.getChildCount());
        assertNull(FeedDownloadButton.existing(row));
    }

    @Test
    public void aLinearRowGetsTheIconJustLeftOfSaveWithSavesSizeAndPadding() {
        ImageView save = save(activity);
        LinearLayout row = linearRow(save);
        FeedDownloadButton.bind(save, "post", "state", activity);

        assertEquals(5, row.getChildCount());
        ImageView button = buttonOf(row);
        assertEquals("just before Save", row.indexOfChild(save) - 1, row.indexOfChild(button));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
        assertEquals(60, params.width);
        assertEquals(44, params.height);
        assertEquals(0f, params.weight, 0f);
        assertEquals(Gravity.BOTTOM, params.gravity);
        assertEquals(3, params.topMargin);
        assertEquals(5, params.bottomMargin);
        assertEquals(6, button.getPaddingStart());
        assertEquals(7, button.getPaddingTop());
        assertEquals(8, button.getPaddingEnd());
        assertEquals(9, button.getPaddingBottom());
        assertEquals(ImageView.ScaleType.FIT_CENTER, button.getScaleType());
        assertEquals("Download", String.valueOf(button.getContentDescription()));
        assertTrue(button.isClickable());
        assertEquals(View.VISIBLE, button.getVisibility());
        assertTrue(button.getDrawable() instanceof FeedDownloadButton.Glyph);
    }

    @Test
    public void bindingAgainReusesTheIconAndPointsItAtTheNewPost() {
        ImageView save = save(activity);
        LinearLayout row = linearRow(save);
        FeedDownloadButton.bind(save, "first", "stateA", activity);
        ImageView button = buttonOf(row);
        FeedDownloadButton.bind(save, "second", "stateB", activity);
        assertEquals(5, row.getChildCount());
        assertSame(button, buttonOf(row));
        button.performClick();
        assertEquals(Collections.singletonList("save second stateB"), recorder.calls);
    }

    @Test
    public void aRowSavesAndAWidthOfZeroOrAStretchBecomesWrapContent() {
        ImageView save = save(activity);
        LinearLayout row = new LinearLayout(activity);
        row.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        FeedDownloadButton.bind(save, "post", "state", activity);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) buttonOf(row).getLayoutParams();
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.width);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.height);
        assertEquals(0f, params.weight, 0f);
    }

    @Test
    public void aRelativeRowPlacesItStartOfSaveAndOnSavesLine() {
        ImageView save = save(activity);
        RelativeLayout row = new RelativeLayout(activity);
        RelativeLayout.LayoutParams saveParams = new RelativeLayout.LayoutParams(60, 44);
        saveParams.addRule(RelativeLayout.ALIGN_PARENT_END);
        saveParams.addRule(RelativeLayout.CENTER_VERTICAL);
        row.addView(save, saveParams);
        FeedDownloadButton.bind(save, "post", "state", activity);

        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) buttonOf(row).getLayoutParams();
        params.resolveLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        assertEquals(save.getId(), params.getRule(RelativeLayout.LEFT_OF));
        assertEquals("on Save's line", RelativeLayout.TRUE, params.getRule(RelativeLayout.CENTER_VERTICAL));
        assertEquals("not at the end of the row", 0, params.getRule(RelativeLayout.ALIGN_PARENT_RIGHT));
    }

    @Test
    public void aFrameRowShiftsItInByThePlaceSaveTakes() {
        ImageView save = save(activity);
        FrameLayout row = new FrameLayout(activity);
        FrameLayout.LayoutParams saveParams = new FrameLayout.LayoutParams(60, 44, Gravity.END | Gravity.CENTER_VERTICAL);
        saveParams.setMarginEnd(10);
        row.addView(save, saveParams);
        FeedDownloadButton.bind(save, "post", "state", activity);

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) buttonOf(row).getLayoutParams();
        assertEquals(Gravity.END | Gravity.CENTER_VERTICAL, params.gravity);
        assertEquals("Save's end margin, its width and a gap", 10 + 60 + FeedDownloadButtonTest.gap(activity), params.getMarginEnd());
    }

    private static int gap(Context context) {
        return Math.round(4 * context.getResources().getDisplayMetrics().density);
    }

    @Test
    public void theSwitchTurnedOffOrAPauseHidesItAndBackOnShowsItAgain() {
        ImageView save = save(activity);
        LinearLayout row = linearRow(save);
        FeedDownloadButton.bind(save, "post", "state", activity);
        ImageView button = buttonOf(row);

        Settings.FEED_DOWNLOAD_BUTTON.save(false);
        FeedDownloadButton.bind(save, "post", "state", activity);
        assertEquals(View.GONE, button.getVisibility());

        Settings.FEED_DOWNLOAD_BUTTON.save(true);
        FeedDownloadButton.bind(save, "post", "state", activity);
        assertEquals(View.VISIBLE, button.getVisibility());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        FeedDownloadButton.bind(save, "post", "state", activity);
        assertEquals("paused", View.GONE, button.getVisibility());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        Settings.DOWNLOAD_VIDEOS.save(false);
        FeedDownloadButton.bind(save, "post", "state", activity);
        assertEquals("nothing a tap could save", View.GONE, button.getVisibility());
        Settings.DOWNLOAD_PHOTOS.save(true);
        FeedDownloadButton.bind(save, "post", "state", activity);
        assertEquals("photos alone", View.VISIBLE, button.getVisibility());
    }

    @Test
    public void aTapOnAPlainPostSavesItAndSaysWhenThereIsNothing() {
        ImageView save = save(activity);
        LinearLayout row = linearRow(save);
        FeedDownloadButton.bind(save, "post", "state", activity);
        buttonOf(row).performClick();
        assertEquals(Collections.singletonList("save post state"), recorder.calls);

        recorder.calls.clear();
        recorder.saves = false;
        buttonOf(row).performClick();
        assertEquals(Arrays.asList("save post state", "nothing"), recorder.calls);
    }

    @Test
    public void aTapOnACarouselAsksAndEachChoiceGoesToItsSave() {
        recorder.pages = Arrays.asList("one", "two", "three");
        ImageView save = save(activity);
        LinearLayout row = linearRow(save);
        FeedDownloadButton.bind(save, "post", "state", activity);
        buttonOf(row).performClick();
        assertEquals(Collections.singletonList("choose"), recorder.calls);

        recorder.page.run();
        assertEquals(Arrays.asList("choose", "save post state"), recorder.calls);
        recorder.all.run();
        assertEquals(Arrays.asList("choose", "save post state", "saveAll post"), recorder.calls);
    }

    @Test
    public void aCarouselOfOnePageIsSavedWithoutAsking() {
        recorder.pages = Collections.singletonList("one");
        ImageView save = save(activity);
        LinearLayout row = linearRow(save);
        FeedDownloadButton.bind(save, "post", "state", activity);
        buttonOf(row).performClick();
        assertEquals(Collections.singletonList("save post state"), recorder.calls);
    }

    @Test
    public void aTapAfterTheSwitchWentOffDoesNothing() {
        ImageView save = save(activity);
        LinearLayout row = linearRow(save);
        FeedDownloadButton.bind(save, "post", "state", activity);
        ImageView button = buttonOf(row);
        Settings.FEED_DOWNLOAD_BUTTON.save(false);
        button.performClick();
        assertTrue(recorder.calls.isEmpty());
    }

    @Test
    public void whatCannotBeBoundIsLeftAlone() {
        FeedDownloadButton.bind(null, "post", "state", activity);
        FeedDownloadButton.bind(save(activity), "post", "state", activity);
        ImageView save = save(activity);
        LinearLayout row = linearRow(save);
        FeedDownloadButton.bind(save, null, null, null);
        ImageView button = buttonOf(row);
        button.performClick();
        assertEquals(Collections.singletonList("nothing"), recorder.calls);
    }

    @Test
    public void theGlyphDrawsAndKeepsItsSize() {
        FeedDownloadButton.Glyph glyph = new FeedDownloadButton.Glyph(48);
        glyph.color = 0xFFFF0000;
        glyph.setBounds(0, 0, 48, 48);
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        glyph.draw(new Canvas(bitmap));
        glyph.draw(new Canvas(bitmap));
        glyph.setAlpha(255);
        glyph.setColorFilter(null);
        assertEquals(android.graphics.PixelFormat.TRANSLUCENT, glyph.getOpacity());
        assertEquals(48, glyph.getIntrinsicWidth());
        assertEquals(48, glyph.getIntrinsicHeight());
    }

    /** Instagram's classes behind the component row's bridges, as plain objects. */
    private static final class FakeLitho implements FeedDownloadButton.Litho {
        final List<String> calls = new ArrayList<>();
        int drawable = 77;
        Object icon = "icon";
        boolean throwing;
        Function1<Object, Object> click;
        Function1<Object, Object> consume;
        int id;
        CharSequence description;
        int usedDrawable;
        Object savedSpec;
        Object usedModifier;
        /** Save's modifier as Instagram builds it: its placement and size, its own binders and props, its padding. */
        List<Object> parts = new ArrayList<>(Arrays.asList(
                "align", "size", "description", "selected binder", "id prop", "tracker binder", "padding", "tap prop"));
        boolean partsReadable = true;

        @Override public Object post(Object state) {
            return "post of " + state;
        }

        @Override public Object item(Object state) {
            return "item of " + state;
        }

        @Override public int drawable(Context context) {
            return drawable;
        }

        @Override public List<Object> parts(Object save) {
            return partsReadable ? new ArrayList<>(parts) : null;
        }

        @Override public boolean saveOnly(Object part) {
            String name = part.toString();
            return name.endsWith(" binder") || name.endsWith(" prop");
        }

        @Override public Object modifier(List<Object> parts) {
            return "modifier " + parts;
        }

        @Override public Object icon(Object save, Object modifier, Function1<Object, Object> click, Function1<Object, Object> consume,
                                     int id, CharSequence description, int drawable) {
            if (throwing) throw new IllegalStateException("Instagram changed");
            this.savedSpec = save;
            this.usedModifier = modifier;
            this.click = click;
            this.consume = consume;
            this.id = id;
            this.description = description;
            this.usedDrawable = drawable;
            return icon;
        }
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test
    public void aComponentRowGetsTheIconSpecAheadOfSave() {
        FakeLitho fake = new FakeLitho();
        FeedDownloadButton.litho = fake;
        try {
            List<Object> row = new ArrayList<>(Collections.singletonList("share"));
            FeedDownloadButton.litho(row, "save spec", "state");
            assertEquals(Arrays.asList("share", "icon"), row);
            assertEquals("made from Save's spec", "save spec", fake.savedSpec);
            assertEquals(77, fake.usedDrawable);
            assertTrue("a view id of its own", fake.id != 0 && fake.id != View.NO_ID);
            assertEquals("Download", fake.description.toString());
            String report = report();
            assertTrue(report, report.contains("feed button placed (litho row)"));
        } finally {
            FeedDownloadButton.litho = originalLitho;
        }
    }

    /**
     * Save's binders (the view-interaction tracker's Save element, the selected state) and its
     * common props never reach the Download icon: its modifier is made of Save's other parts, in
     * their order.
     */
    @Test
    public void theComponentIconTakesSavesSizeAndPaddingButNoneOfSavesOwnParts() {
        FakeLitho fake = new FakeLitho();
        FeedDownloadButton.litho = fake;
        try {
            FeedDownloadButton.litho(new ArrayList<>(), "save spec", "state");
            assertEquals("modifier [align, size, description, padding]", fake.usedModifier);
            assertFalse(fake.usedModifier.toString().contains("binder"));
            assertFalse(fake.usedModifier.toString().contains("prop"));
        } finally {
            FeedDownloadButton.litho = originalLitho;
        }
    }

    /** A modifier whose parts can't be read, or where nothing reads as Save's own, gets no icon: a binder could hide in it. */
    @Test
    public void aSaveModifierThatCannotBeToldApartGetsNoIcon() {
        FakeLitho fake = new FakeLitho();
        FeedDownloadButton.litho = fake;
        try {
            List<Object> row = new ArrayList<>();
            fake.partsReadable = false;
            FeedDownloadButton.litho(row, "save", "state");
            fake.partsReadable = true;
            fake.parts = new ArrayList<>(Arrays.asList("size", "padding"));
            FeedDownloadButton.litho(row, "save", "state");
            assertTrue(row.isEmpty());
            assertNull("no icon was made", fake.savedSpec);
            String report = report();
            assertTrue(report, report.contains("feed button not placed (litho row)"));
        } finally {
            FeedDownloadButton.litho = originalLitho;
        }
    }

    /** Before the patch writes the bridges, Save's modifier reads as nothing, so the icon is never made from it whole. */
    @Test
    public void theUnpatchedBridgesGiveNoModifier() {
        assertNull(originalLitho.parts("save"));
        assertNull(FeedDownloadButton.iconModifier("save"));
        assertNull(originalLitho.modifier(new ArrayList<>(Collections.singletonList("size"))));
        assertFalse(originalLitho.saveOnly("part"));
        List<Object> collected = new ArrayList<>();
        assertNull(new FeedDownloadButton.Collect(collected).invoke("part"));
        assertEquals(Collections.singletonList("part"), collected);
    }

    @Test
    public void theComponentIconKeepsOneIdAndTakesTheLongPress() {
        FakeLitho fake = new FakeLitho();
        FeedDownloadButton.litho = fake;
        try {
            FeedDownloadButton.litho(new ArrayList<>(), "save", "state");
            int first = fake.id;
            FeedDownloadButton.litho(new ArrayList<>(), "save", "state");
            assertEquals("the same id on every compose", first, fake.id);
            assertEquals(Boolean.TRUE, fake.consume.invoke(new Object()));
        } finally {
            FeedDownloadButton.litho = originalLitho;
        }
    }

    @Test
    public void aComponentRowIsLeftAloneWhenTheSwitchIsOffOrPausedOrNothingCanBeSaved() {
        FakeLitho fake = new FakeLitho();
        FeedDownloadButton.litho = fake;
        try {
            List<Object> row = new ArrayList<>();
            Settings.FEED_DOWNLOAD_BUTTON.save(false);
            FeedDownloadButton.litho(row, "save", "state");
            Settings.FEED_DOWNLOAD_BUTTON.save(true);
            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            FeedDownloadButton.litho(row, "save", "state");
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
            Settings.DOWNLOAD_VIDEOS.save(false);
            Settings.DOWNLOAD_PHOTOS.save(false);
            FeedDownloadButton.litho(row, "save", "state");
            assertTrue("nothing was added", row.isEmpty());
            assertNull("Instagram's classes were not asked", fake.savedSpec);
        } finally {
            FeedDownloadButton.litho = originalLitho;
        }
    }

    @Test
    public void aComponentRowThatCannotTakeTheIconIsLeftAsItWasAndCounted() {
        FakeLitho fake = new FakeLitho();
        FeedDownloadButton.litho = fake;
        try {
            List<Object> row = new ArrayList<>();
            FeedDownloadButton.litho(null, "save", "state");
            FeedDownloadButton.litho(row, null, "state");
            FeedDownloadButton.litho(row, "save", null);
            fake.drawable = 0;
            FeedDownloadButton.litho(row, "save", "state");
            fake.drawable = 5;
            fake.icon = null;
            FeedDownloadButton.litho(row, "save", "state");
            fake.throwing = true;
            FeedDownloadButton.litho(row, "save", "state");
            assertTrue(row.isEmpty());
            String report = report();
            assertTrue(report, report.contains("feed button not placed (litho row)"));
        } finally {
            FeedDownloadButton.litho = originalLitho;
        }
    }

    @Test
    public void aTapOnTheComponentIconSavesThePostWithTheFeedStateAndAnchorsOnTheTappedView() {
        FakeLitho fake = new FakeLitho();
        FeedDownloadButton.litho = fake;
        try {
            FeedDownloadButton.litho(new ArrayList<>(), "save", "row");
            View tapped = new View(activity);
            fake.click.invoke(tapped);
            assertEquals(Collections.singletonList("save post of row item of row"), recorder.calls);

            recorder.calls.clear();
            recorder.pages = Arrays.asList("one", "two");
            fake.click.invoke(new Object() {
                @SuppressWarnings("unused") final View view = tapped;
            });
            assertEquals(Collections.singletonList("choose"), recorder.calls);
        } finally {
            FeedDownloadButton.litho = originalLitho;
        }
    }

    @Test
    public void aTapWithNoViewFallsBackToTheActivityAndNeverThrows() {
        FakeLitho fake = new FakeLitho();
        FeedDownloadButton.litho = fake;
        try {
            FeedDownloadButton.litho(new ArrayList<>(), "save", "row");
            Utils.setActivity(activity);
            assertNull(fake.click.invoke(new Object()));
            assertEquals(Collections.singletonList("save post of row item of row"), recorder.calls);

            Settings.FEED_DOWNLOAD_BUTTON.save(false);
            recorder.calls.clear();
            assertNull(fake.click.invoke(null));
            assertTrue("a switch turned off after the compose stops the tap", recorder.calls.isEmpty());
        } finally {
            FeedDownloadButton.litho = originalLitho;
        }
    }

    @Test
    public void theViewAClickEventCameFromIsFoundInTheEventOrItsFields() {
        View view = new View(activity);
        assertSame(view, FeedDownloadButton.viewOf(view));
        assertNull(FeedDownloadButton.viewOf(null));
        assertNull(FeedDownloadButton.viewOf("no view"));
        assertSame(view, FeedDownloadButton.viewOf(new Object() {
            @SuppressWarnings("unused") final String other = "x";
            @SuppressWarnings("unused") final View seen = view;
        }));
        assertSame(activity, FeedDownloadButton.activityOf(view));
    }

    @Test
    public void theDownloadGlyphIsFoundByItsResourceNameAndMissingMeansNoIcon() {
        assertEquals("a resource this app lacks", 0, originalLitho.drawable(activity));
    }

    /** The glyph is looked up by name once, found or not, and never again on the compose after. */
    @Test
    public void theDownloadGlyphIsLookedUpOnce() {
        int[] asked = {0};
        Context counting = new ContextWrapper(activity) {
            @Override public Resources getResources() {
                asked[0]++;
                return super.getResources();
            }
        };

        assertEquals(0, originalLitho.drawable(counting));
        assertEquals("missing is remembered too", 0, originalLitho.drawable(counting));
        assertEquals(1, asked[0]);
        assertEquals(-1, FeedDownloadButton.glyph);

        FeedDownloadButton.glyph = 0x7f080042;
        assertEquals("a glyph already found", 0x7f080042, originalLitho.drawable(counting));
        assertEquals(1, asked[0]);
        FeedDownloadButton.resetForTests();
        originalLitho.drawable(counting);
        assertEquals("a fresh start looks again", 2, asked[0]);
    }

    /** The description is written once per set of languages and again when they change. */
    @Test
    public void theDescriptionIsKeptUntilTheLanguageChanges() {
        assertEquals("Download", FeedDownloadButton.description(activity));
        FeedDownloadButton.Label first = FeedDownloadButton.label;
        assertNotNull(first);
        assertEquals(activity.getApplicationContext().getResources().getConfiguration().getLocales(), first.locales);

        FeedDownloadButton.label = new FeedDownloadButton.Label(first.locales, "kept");
        assertEquals("the same languages reuse it", "kept", FeedDownloadButton.description(activity));
        FeedDownloadButton.label = new FeedDownloadButton.Label(LocaleList.forLanguageTags("xx"), "stale");
        assertEquals("other languages write it again", "Download", FeedDownloadButton.description(activity));
        assertEquals(first.locales, FeedDownloadButton.label.locales);
    }
}

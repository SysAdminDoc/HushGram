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
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
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
    private final Recorder recorder = new Recorder();
    private Activity activity;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        FeedDownloadButton.actions = recorder;
        Settings.DOWNLOAD_VIDEOS.save(true);
        Settings.FEED_DOWNLOAD_BUTTON.save(true);
    }

    @After
    public void tearDown() {
        FeedDownloadButton.actions = original;
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
}

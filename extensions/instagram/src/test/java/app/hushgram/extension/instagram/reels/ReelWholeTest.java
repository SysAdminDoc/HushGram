/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Whole reel in the reel menu (#845): one reel fits inside the screen until it's swiped away. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelWholeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Instagram's video layout, by the name the extension looks for. */
    public static final class SimpleVideoLayout extends FrameLayout {
        public SimpleVideoLayout(Context context) {
            super(context);
        }
    }

    /** A reel's page as the viewer holds it: its video layout, and the texture in it. */
    public static final class Page {
        public final FrameLayout page;
        public final SimpleVideoLayout layout;
        public final TextureView texture;

        Page(FrameLayout page, SimpleVideoLayout layout, TextureView texture) {
            this.page = page;
            this.layout = layout;
            this.texture = texture;
        }
    }

    /**
     * An activity showing a reel's page at the top of the window, a 300x400 layout over the window's
     * middle holding a [width]x[height] texture moved [shiftX] across, and a second cut reel's page
     * below the window, as the pager keeps the next one.
     */
    public static Page[] screen(ActivityController<Activity> controller, int width, int height, float shiftX) {
        Activity activity = controller.get();
        controller.create();
        FrameLayout root = new FrameLayout(activity);
        Page shown = page(activity, width, height, shiftX);
        Page next = page(activity, 500, 400, -100f);
        next.page.setTranslationY(1000f);
        root.addView(shown.page, new FrameLayout.LayoutParams(300, 400));
        root.addView(next.page, new FrameLayout.LayoutParams(300, 400));
        activity.setContentView(root);
        controller.start().resume().visible();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return new Page[] {shown, next};
    }

    private static Page page(Activity activity, int width, int height, float shiftX) {
        FrameLayout page = new FrameLayout(activity);
        SimpleVideoLayout layout = new SimpleVideoLayout(activity);
        TextureView texture = new TextureView(activity);
        layout.addView(texture, new FrameLayout.LayoutParams(width, height));
        page.addView(layout, new ViewGroup.LayoutParams(300, 400));
        texture.setTranslationX(shiftX);
        return new Page(page, layout, texture);
    }

    /** Where [texture]'s middle is drawn in its layout, across or down: scaled around its pivot, then moved. */
    static float drawnMiddle(TextureView texture, boolean across) {
        float start = across ? texture.getLeft() + texture.getTranslationX() : texture.getTop() + texture.getTranslationY();
        float pivot = across ? texture.getPivotX() : texture.getPivotY();
        float scale = across ? texture.getScaleX() : texture.getScaleY();
        float size = across ? texture.getWidth() : texture.getHeight();
        return start + pivot + scale * (size / 2f - pivot);
    }

    private ActivityController<Activity> controller;

    @Before
    public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller = Robolectric.buildActivity(Activity.class);
    }

    @After
    public void tearDown() {
        ReelWhole.resetForTests();
        Settings.WHOLE_REEL_IN_REEL_MENU.resetToDefault();
        HookStatus.clear();
    }

    /** Off to start: nothing offered and a tap does nothing. */
    @Test
    public void startsOff() {
        Page shown = screen(controller, 500, 400, -100f)[0];
        assertEquals(ReelWhole.Offer.NONE, ReelWhole.offer(controller.get()));
        assertEquals(ReelWhole.Offer.NONE, ReelWhole.toggle(controller.get()));
        assertEquals(1f, shown.texture.getScaleX(), 0f);
    }

    /**
     * A reel cut at the sides, laid out from the left: the tap scales its texture to the layout's
     * width over the texture's (300 over 500) and centers it, the next reel's page stays as it is, and
     * the row turns to Fill the screen. Its tap puts back the scale and the spot Instagram gave it.
     */
    @Test
    public void aCutReelShowsWholeAndCropsAgain() {
        Settings.WHOLE_REEL_IN_REEL_MENU.save(true);
        Page[] pages = screen(controller, 500, 500, 0f);
        Page shown = pages[0];
        Activity activity = controller.get();
        assertEquals(ReelWhole.Offer.WHOLE, ReelWhole.offer(activity));

        assertEquals(ReelWhole.Offer.WHOLE, ReelWhole.toggle(activity));
        assertEquals(0.6f, shown.texture.getScaleX(), 0.0001f);
        assertEquals(0.6f, shown.texture.getScaleY(), 0.0001f);
        // The texture's middle, 250 across and 250 down, lands on the layout's, 150 and 200, around
        // whatever pivot the texture scales from.
        assertEquals(150f, drawnMiddle(shown.texture, true), 0.001f);
        assertEquals(200f, drawnMiddle(shown.texture, false), 0.001f);
        assertEquals(1f, pages[1].texture.getScaleX(), 0f);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(ReelWhole.COUNT_WHOLE));
        assertEquals(ReelWhole.Offer.CROP, ReelWhole.offer(activity));

        assertEquals(ReelWhole.Offer.CROP, ReelWhole.toggle(activity));
        assertEquals(1f, shown.texture.getScaleX(), 0f);
        assertEquals(1f, shown.texture.getScaleY(), 0f);
        assertEquals(0f, shown.texture.getTranslationX(), 0f);
        assertEquals(0f, shown.texture.getTranslationY(), 0f);
        assertEquals(ReelWhole.Offer.WHOLE, ReelWhole.offer(activity));
    }

    /** A reel that already fits its layout gets no row, and a tap leaves it alone. */
    @Test
    public void aReelThatFitsGetsNoRow() {
        Settings.WHOLE_REEL_IN_REEL_MENU.save(true);
        Page shown = screen(controller, 300, 400, 0f)[0];
        assertEquals(ReelWhole.Offer.NONE, ReelWhole.offer(controller.get()));
        assertEquals(ReelWhole.Offer.NONE, ReelWhole.toggle(controller.get()));
        assertEquals(1f, shown.texture.getScaleX(), 0f);
        assertEquals(ReelWhole.Offer.NONE, ReelWhole.offer(null));
    }

    /**
     * A small move of the reel's page, as when its comments open, keeps it whole. A swipe that moves
     * the page half its height or more crops it again, and the next time it's on screen it's cut.
     */
    @Test
    public void swipingAwayCropsItAgain() {
        Settings.WHOLE_REEL_IN_REEL_MENU.save(true);
        Page shown = screen(controller, 500, 400, -100f)[0];
        assertEquals(ReelWhole.Offer.WHOLE, ReelWhole.toggle(controller.get()));

        shown.page.setTranslationY(-150f);
        ReelWhole.recheck(shown.texture);
        assertEquals(0.6f, shown.texture.getScaleX(), 0.0001f);

        shown.page.setTranslationY(-200f);
        ReelWhole.recheck(shown.texture);
        assertEquals(1f, shown.texture.getScaleX(), 0f);
        assertEquals(-100f, shown.texture.getTranslationX(), 0f);
        assertTrue(ReelWhole.WHOLE.isEmpty());

        shown.page.setTranslationY(0f);
        assertEquals(ReelWhole.Offer.WHOLE, ReelWhole.offer(controller.get()));
    }

    /** A page the viewer lets go of is cropped again as it leaves the window. */
    @Test
    public void aPageLeavingTheWindowIsCroppedAgain() {
        Settings.WHOLE_REEL_IN_REEL_MENU.save(true);
        Page shown = screen(controller, 500, 400, -100f)[0];
        assertEquals(ReelWhole.Offer.WHOLE, ReelWhole.toggle(controller.get()));
        ViewGroup root = (ViewGroup) shown.page.getParent();
        assertNotNull(root);
        root.removeView(shown.page);
        assertEquals(1f, shown.texture.getScaleX(), 0f);
        assertTrue(ReelWhole.WHOLE.isEmpty());
    }

    /** Paused, nothing is offered. */
    @Test
    public void pausedOffersNothing() {
        Settings.WHOLE_REEL_IN_REEL_MENU.save(true);
        screen(controller, 500, 400, -100f);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);
        try {
            assertEquals(ReelWhole.Offer.NONE, ReelWhole.offer(controller.get()));
        } finally {
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
        }
    }
}

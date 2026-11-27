/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The tab bar's icons drawn at the size picked under Tab icon size (#541). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class TabIconsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private LinearLayout bar;
    private ImageView home;
    private ImageView search;
    private ImageView profile;
    private View badge;
    private View screenIcon;

    @Before
    public void prepare() {
        resetAll();
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        int barId = View.generateViewId();
        int iconId = View.generateViewId();
        int avatarId = View.generateViewId();
        TabIcons.idForTests(activity, TabIcons.TAB_BAR, barId);
        TabIcons.idForTests(activity, TabIcons.ICON, iconId);
        TabIcons.idForTests(activity, TabIcons.AVATAR, avatarId);

        root = new FrameLayout(activity);
        FrameLayout screen = new FrameLayout(activity);
        screenIcon = new ImageView(activity);
        screenIcon.setId(iconId);
        screen.addView(screenIcon, new FrameLayout.LayoutParams(60, 60));
        root.addView(screen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 700));

        bar = new LinearLayout(activity);
        bar.setId(barId);
        home = icon(iconId);
        bar.addView(tab(home), tabParams());
        search = icon(iconId);
        FrameLayout searchTab = tab(search);
        badge = new View(activity);
        searchTab.addView(badge, new FrameLayout.LayoutParams(12, 12, Gravity.TOP | Gravity.END));
        bar.addView(searchTab, tabParams());
        profile = icon(avatarId);
        bar.addView(tab(profile), tabParams());
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100, Gravity.BOTTOM));
        activity.setContentView(root);
        ShadowLooper.idleMainLooper();
        layOut();
    }

    private void layOut() {
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 800);
    }

    @After
    public void restore() {
        resetAll();
        controller.pause().stop().destroy();
    }

    private static void resetAll() {
        TabIcons.resetForTests();
        Settings.TAB_ICON_SIZE.resetToDefault();
        Settings.FULL_SIZE_REELS_ICON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private ImageView icon(int id) {
        ImageView view = new ImageView(activity);
        view.setId(id);
        return view;
    }

    private FrameLayout tab(View icon) {
        FrameLayout tab = new FrameLayout(activity);
        tab.addView(icon, new FrameLayout.LayoutParams(48, 48, Gravity.CENTER));
        return tab;
    }

    private static LinearLayout.LayoutParams tabParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private void frame() {
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        root.getViewTreeObserver().dispatchOnPreDraw();
    }

    private static void assertScale(String what, float expected, View view) {
        assertEquals(what, expected, view.getScaleX(), 0f);
        assertEquals(what, expected, view.getScaleY(), 0f);
    }

    /** A picture with a size of its own, like Instagram's vector icons. */
    private static GradientDrawable picture(int width, int height) {
        GradientDrawable picture = new GradientDrawable();
        picture.setSize(width, height);
        return picture;
    }

    /** Where [icon] draws its picture inside its padding, as ImageView itself places it. */
    private static RectF drawn(ImageView icon) {
        RectF rect = new RectF(icon.getDrawable().getBounds());
        icon.getImageMatrix().mapRect(rect);
        return rect;
    }

    /** [rect] grown [scale] times about ([x], [y]). */
    private static RectF about(RectF rect, float scale, float x, float y) {
        return new RectF(x + (rect.left - x) * scale, y + (rect.top - y) * scale,
                x + (rect.right - x) * scale, y + (rect.bottom - y) * scale);
    }

    private static void assertRect(String what, RectF expected, RectF actual) {
        String both = what + ": expected " + expected + " but was " + actual;
        assertEquals(both, expected.left, actual.left, 0.01f);
        assertEquals(both, expected.top, actual.top, 0.01f);
        assertEquals(both, expected.right, actual.right, 0.01f);
        assertEquals(both, expected.bottom, actual.bottom, 0.01f);
    }

    @Test
    public void everySizeIsAShareOfInstagramsAndInstagramsComesFirst() {
        TabIconSize[] sizes = TabIconSize.values();
        assertEquals(TabIconSize.INSTAGRAM, sizes[0]);
        assertEquals(100, TabIconSize.INSTAGRAM.percent());
        assertEquals(110, TabIconSize.PERCENT_110.percent());
        assertEquals(150, TabIconSize.PERCENT_150.percent());
        for (int i = 1; i < sizes.length; i++) assertTrue(sizes[i].scale > sizes[i - 1].scale);
    }

    @Test
    public void instagramsSizeLeavesEveryIconAlone() {
        TabIcons.watch(activity);
        assertTrue(TabIcons.listeningForTests(activity));

        frame();

        assertScale("home", 1f, home);
        assertScale("search", 1f, search);
        assertScale("profile", 1f, profile);
    }

    @Test
    public void aPickedSizeDrawsEachTabsIconAndPictureBigger() {
        Settings.TAB_ICON_SIZE.save(TabIconSize.PERCENT_130);
        TabIcons.watch(activity);

        frame();

        assertScale("home", 1.3f, home);
        assertScale("search", 1.3f, search);
        assertScale("the Profile tab's picture", 1.3f, profile);
        assertScale("the badge stays Instagram's", 1f, badge);
        assertScale("an icon outside the bar stays", 1f, screenIcon);
        assertEquals("the tab keeps its touch area", 1f, ((View) home.getParent()).getScaleX(), 0f);
        // No pivot of its own, so Android scales it from its center.
        assertFalse("from its own center", home.isPivotSet());
    }

    @Test
    public void anotherSizeTakesOverOnTheNextFrame() {
        Settings.TAB_ICON_SIZE.save(TabIconSize.PERCENT_120);
        TabIcons.watch(activity);
        frame();
        assertScale("home", 1.2f, home);

        Settings.TAB_ICON_SIZE.save(TabIconSize.PERCENT_150);
        root.getViewTreeObserver().dispatchOnPreDraw();

        assertScale("home", 1.5f, home);
    }

    @Test
    public void aPressedIconIsLeftToInstagramUntilItsBack() {
        Settings.TAB_ICON_SIZE.save(TabIconSize.PERCENT_130);
        TabIcons.watch(activity);
        frame();

        home.setScaleX(0.9f);
        home.setScaleY(0.9f);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertScale("mid press", 0.9f, home);

        home.setScaleX(1f);
        home.setScaleY(1f);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertScale("after the press", 1.3f, home);
    }

    /**
     * At 140% on a phone the Home icon lost its roof and the Profile picture came out square: each
     * was cut off where its parent's edges were. The parents up to the tab stop clipping while the
     * icons are bigger, the bar still does, and Instagram's size puts each parent back as it was.
     */
    @Test
    public void aBiggerIconIsNotCutOffByItsParents() {
        FrameLayout wrapper = new FrameLayout(activity);
        ImageView reels = icon(home.getId());
        wrapper.addView(reels, new FrameLayout.LayoutParams(48, 48));
        FrameLayout reelsTab = new FrameLayout(activity);
        reelsTab.setPadding(4, 4, 4, 4);
        reelsTab.addView(wrapper, new FrameLayout.LayoutParams(48, 48, Gravity.CENTER));
        bar.addView(reelsTab, tabParams());
        FrameLayout searchTab = (FrameLayout) search.getParent();
        searchTab.setClipChildren(false);
        Settings.TAB_ICON_SIZE.save(TabIconSize.PERCENT_140);
        TabIcons.watch(activity);

        frame();

        assertScale("reels", 1.4f, reels);
        assertFalse("the icon's own wrapper", wrapper.getClipChildren());
        assertFalse("the tab", reelsTab.getClipChildren());
        assertFalse("the tab's padding", reelsTab.getClipToPadding());
        assertFalse("the Profile picture's tab", ((ViewGroup) profile.getParent()).getClipChildren());
        assertTrue("the bar still holds them in", bar.getClipChildren());

        Settings.TAB_ICON_SIZE.save(TabIconSize.INSTAGRAM);
        root.getViewTreeObserver().dispatchOnPreDraw();

        assertScale("reels", 1f, reels);
        assertTrue("the wrapper clips again", wrapper.getClipChildren());
        assertTrue("the tab clips again", reelsTab.getClipChildren());
        assertTrue("the tab's padding clips again", reelsTab.getClipToPadding());
        assertFalse("a tab that didn't clip before still doesn't", searchTab.getClipChildren());
    }

    /**
     * #793: Full-size Reels icon draws the Reels tab's icon bigger, wherever the tab sits, and only that
     * icon. A picked Tab icon size sizes it on top, and with both back at Instagram's it's put back.
     */
    @Test
    public void fullSizeReelsIconDrawsOnlyTheReelsIconBigger() {
        int reelsTabId = View.generateViewId();
        TabIcons.idForTests(activity, TabIcons.REELS_TAB, reelsTabId);
        ImageView reels = icon(home.getId());
        FrameLayout reelsTab = tab(reels);
        reelsTab.setId(reelsTabId);
        bar.addView(reelsTab, 1, tabParams());
        Settings.FULL_SIZE_REELS_ICON.save(true);
        TabIcons.watch(activity);

        frame();

        assertScale("reels", TabIcons.REELS_BOOST, reels);
        assertScale("home stays Instagram's", 1f, home);
        assertScale("search stays Instagram's", 1f, search);
        assertScale("the Profile picture stays Instagram's", 1f, profile);
        assertFalse("the Reels tab doesn't cut it off", reelsTab.getClipChildren());

        Settings.TAB_ICON_SIZE.save(TabIconSize.PERCENT_120);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertScale("reels on top of the picked size", TabIconSize.PERCENT_120.scale * TabIcons.REELS_BOOST, reels);
        assertScale("home at the picked size", TabIconSize.PERCENT_120.scale, home);

        Settings.FULL_SIZE_REELS_ICON.save(false);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertScale("reels at the picked size alone", TabIconSize.PERCENT_120.scale, reels);

        Settings.TAB_ICON_SIZE.save(TabIconSize.INSTAGRAM);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertScale("reels back", 1f, reels);
        assertTrue("the Reels tab clips again", reelsTab.getClipChildren());
    }

    /**
     * #826: the Reels icon drawn bigger came out soft and stretched, since growing the whole view
     * stretched the bitmap Android draws a vector icon into at the view's size. An icon with a picture
     * is drawn bigger through the picture's own matrix, so the drawing is made at that size, and the
     * view Instagram animates keeps its own.
     */
    @Test
    public void anIconsPictureIsDrawnBiggerRatherThanTheViewStretched() {
        int reelsTabId = View.generateViewId();
        TabIcons.idForTests(activity, TabIcons.REELS_TAB, reelsTabId);
        ImageView reels = icon(home.getId());
        reels.setImageDrawable(picture(24, 24));
        reels.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout reelsTab = tab(reels);
        reelsTab.setId(reelsTabId);
        bar.addView(reelsTab, 1, tabParams());
        layOut();
        RectF stock = drawn(reels);
        assertRect("Instagram's own placement", new RectF(12, 12, 36, 36), stock);
        Settings.FULL_SIZE_REELS_ICON.save(true);
        TabIcons.watch(activity);

        frame();

        assertScale("the view keeps Instagram's size", 1f, reels);
        RectF bigger = about(stock, TabIcons.REELS_BOOST, 24, 24);
        assertRect("the picture is drawn bigger from the icon's center", bigger, drawn(reels));
        assertFalse("the Reels tab doesn't cut it off", reelsTab.getClipChildren());
        assertScale("home stays Instagram's", 1f, home);

        reels.setScaleX(0.9f);
        reels.setScaleY(0.9f);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertScale("Instagram's press keeps the view", 0.9f, reels);
        assertRect("the picture stays bigger under the press", bigger, drawn(reels));
        reels.setScaleX(1f);
        reels.setScaleY(1f);

        // Selected, Instagram shows another picture: it's drawn bigger from where Instagram puts it.
        reels.setImageDrawable(picture(30, 20));
        root.getViewTreeObserver().dispatchOnPreDraw();
        RectF swapped = new RectF(9, 14, 39, 34);
        assertRect("the new picture, bigger", about(swapped, TabIcons.REELS_BOOST, 24, 24), drawn(reels));

        Settings.FULL_SIZE_REELS_ICON.save(false);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("Instagram's own scale type", ImageView.ScaleType.CENTER_INSIDE, reels.getScaleType());
        assertRect("placed as Instagram does again", swapped, drawn(reels));
        assertTrue("the Reels tab clips again", reelsTab.getClipChildren());
    }

    /**
     * #826 on a phone: the Reels icon was still soft drawn bigger through its matrix, since it's a 72
     * pixel picture rather than a vector. While it's drawn at another size it's drawn as a path of the
     * same shape, selected or not, and Instagram's size gives it its own picture back.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aBiggerReelsPictureIsDrawnAsAPathOfItsShape() {
        Bitmap outline = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
        outline.eraseColor(Color.WHITE);
        Bitmap filled = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
        filled.eraseColor(Color.WHITE);
        filled.setPixel(0, 0, Color.TRANSPARENT);
        SharpReelsIcon.picturesForTests(activity.getResources(), outline, filled);
        StateListDrawable picture = new StateListDrawable();
        picture.addState(new int[]{android.R.attr.state_selected},
                new LayerDrawable(new Drawable[]{new BitmapDrawable(activity.getResources(), filled)}));
        picture.addState(new int[0], new LayerDrawable(new Drawable[]{new BitmapDrawable(activity.getResources(), outline)}));
        int reelsTabId = View.generateViewId();
        TabIcons.idForTests(activity, TabIcons.REELS_TAB, reelsTabId);
        ImageView reels = icon(home.getId());
        reels.setImageDrawable(picture);
        reels.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout reelsTab = tab(reels);
        reelsTab.setId(reelsTabId);
        bar.addView(reelsTab, 1, tabParams());
        layOut();
        Settings.FULL_SIZE_REELS_ICON.save(true);
        TabIcons.watch(activity);

        frame();

        Bitmap shot = shot(reels);
        assertEquals("the box's line", 255, Color.alpha(pixel(reels, shot, 9f, 36f)));
        assertEquals("inside the box, clear as in Instagram's picture", 0, Color.alpha(pixel(reels, shot, 15f, 36f)));
        assertEquals("the play triangle's line", 255, Color.alpha(pixel(reels, shot, 24f, 36f)));
        assertRect("still drawn bigger from the icon's center",
                about(new RectF(0, 0, 48, 48), TabIcons.REELS_BOOST, 24, 24), drawn(reels));

        reels.setSelected(true);
        root.getViewTreeObserver().dispatchOnPreDraw();
        shot = shot(reels);
        assertEquals("selected, the filled box", 255, Color.alpha(pixel(reels, shot, 15f, 36f)));
        assertEquals("selected, the play triangle cut out", 0, Color.alpha(pixel(reels, shot, 33f, 36f)));

        Settings.FULL_SIZE_REELS_ICON.save(false);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertSame("Instagram's own picture again", picture, reels.getDrawable());
    }

    /** [view] drawn on its own. */
    private static Bitmap shot(View view) {
        Bitmap shot = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(shot));
        return shot;
    }

    /** The pixel of [shot] where [icon] draws the point ([x], [y]) of its picture's 72 unit grid. */
    private static int pixel(ImageView icon, Bitmap shot, float x, float y) {
        Rect bounds = icon.getDrawable().getBounds();
        float[] point = {bounds.left + x * bounds.width() / 72f, bounds.top + y * bounds.height() / 72f};
        icon.getImageMatrix().mapPoints(point);
        return shot.getPixel((int) point[0] + icon.getPaddingLeft(), (int) point[1] + icon.getPaddingTop());
    }

    /** Paused, a picture placed by Instagram's own matrix gets that matrix back. */
    @Test
    public void pausedPutsAPictureBackOnInstagramsMatrix() {
        home.setImageDrawable(picture(20, 20));
        home.setScaleType(ImageView.ScaleType.MATRIX);
        Matrix own = new Matrix();
        own.setTranslate(14, 14);
        home.setImageMatrix(own);
        layOut();
        Settings.TAB_ICON_SIZE.save(TabIconSize.PERCENT_130);
        TabIcons.watch(activity);
        frame();
        assertScale("the view keeps Instagram's size", 1f, home);
        assertRect("bigger from the icon's center", about(new RectF(14, 14, 34, 34), 1.3f, 24, 24), drawn(home));
        assertScale("the Profile picture, a photo, is drawn bigger whole", 1.3f, profile);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        root.getViewTreeObserver().dispatchOnPreDraw();

        assertEquals(ImageView.ScaleType.MATRIX, home.getScaleType());
        assertEquals("Instagram's own matrix", own, home.getImageMatrix());
        assertScale("profile", 1f, profile);
    }

    /** Paused, the Reels icon goes back to Instagram's size too. */
    @Test
    public void pausedPutsTheReelsIconBack() {
        int reelsTabId = View.generateViewId();
        TabIcons.idForTests(activity, TabIcons.REELS_TAB, reelsTabId);
        ImageView reels = icon(home.getId());
        FrameLayout reelsTab = tab(reels);
        reelsTab.setId(reelsTabId);
        bar.addView(reelsTab, tabParams());
        Settings.FULL_SIZE_REELS_ICON.save(true);
        TabIcons.watch(activity);
        frame();
        assertScale("reels", TabIcons.REELS_BOOST, reels);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        root.getViewTreeObserver().dispatchOnPreDraw();

        assertScale("reels", 1f, reels);
    }

    @Test
    public void instagramsSizeAgainPutsTheIconsBack() {
        Settings.TAB_ICON_SIZE.save(TabIconSize.PERCENT_140);
        TabIcons.watch(activity);
        frame();
        assertScale("home", 1.4f, home);

        Settings.TAB_ICON_SIZE.save(TabIconSize.INSTAGRAM);
        root.getViewTreeObserver().dispatchOnPreDraw();

        assertScale("home", 1f, home);
        assertScale("profile", 1f, profile);
    }

    @Test
    public void pausedPutsTheIconsBack() {
        Settings.TAB_ICON_SIZE.save(TabIconSize.PERCENT_140);
        TabIcons.watch(activity);
        frame();
        assertScale("home", 1.4f, home);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        root.getViewTreeObserver().dispatchOnPreDraw();

        assertScale("home", 1f, home);
        assertScale("search", 1f, search);
    }

    @Test
    public void aHiddenBarIsLeftAlone() {
        Settings.TAB_ICON_SIZE.save(TabIconSize.PERCENT_120);
        bar.setVisibility(View.GONE);
        TabIcons.watch(activity);

        frame();

        assertScale("home", 1f, home);
        assertFalse(TabIcons.size(new LinearLayout(activity), 0, 0, 1.2f));
    }
}

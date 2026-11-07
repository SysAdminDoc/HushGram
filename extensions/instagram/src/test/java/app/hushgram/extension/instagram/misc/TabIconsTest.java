/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
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

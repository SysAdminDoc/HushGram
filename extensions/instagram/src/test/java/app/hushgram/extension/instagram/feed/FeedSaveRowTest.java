/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Save in the post menu (#569): the row a feed post's menu gets, the short menu keeping it, and the hidden button. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedSaveRowTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final Object SAVE = "SAVE";
    private static final Object UNSAVE = "UNSAVE";
    private static final Object MENU = new Object();
    private static final Object POST = new Object();

    /** What the builder's adder was handed, one entry per row. */
    private final List<String> added = new ArrayList<>();
    private final ArrayList<Object> rows = new ArrayList<>();

    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout screen;
    private View save;
    private View like;
    private View savedElsewhere;

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SAVE_IN_POST_MENU.save(true);
        Settings.HIDE_FEED_SAVE_BUTTON.resetToDefault();
        HookStatus.clear();

        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        FeedSaveRow.hider().resetForTests();
        int buttonsId = View.generateViewId();
        int saveId = View.generateViewId();
        FeedSaveRow.hider().idForTests(activity, FeedSaveRow.BUTTONS, buttonsId);
        FeedSaveRow.hider().idForTests(activity, FeedSaveRow.SAVE_BUTTON, saveId);
        screen = new LinearLayout(activity);
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setId(buttonsId);
        like = new View(activity);
        save = new View(activity);
        save.setId(saveId);
        buttons.addView(like);
        buttons.addView(save);
        savedElsewhere = new View(activity);
        savedElsewhere.setId(saveId);
        screen.addView(buttons);
        screen.addView(savedElsewhere);
        FrameLayout root = new FrameLayout(activity);
        root.addView(screen);
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After
    public void restore() {
        FeedSaveRow.hider().resetForTests();
        Settings.SAVE_IN_POST_MENU.resetToDefault();
        Settings.HIDE_FEED_SAVE_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private void offer(boolean saved) {
        FeedSaveRow.offer(MENU, rows, FeedSaveRow::wanted, menu -> menu == MENU ? POST : null, post -> saved,
                (menu, list, option, label) -> added.add(option + " " + label), () -> SAVE, () -> UNSAVE);
    }

    /** A post that isn't saved gets Instagram's Save option under the label Save. */
    @Test
    public void anUnsavedPostGetsSave() {
        offer(false);

        assertEquals(Collections.singletonList("SAVE Save"), added);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FeedSaveRow.COUNT_OFFERED));
        assertTrue(HookStatus.missing(FamilyNames.FEED_SAVE).toString(), HookStatus.missing(FamilyNames.FEED_SAVE).isEmpty());
    }

    /** A post saved already gets the Unsave option, Remove from saved. */
    @Test
    public void aSavedPostGetsRemoveFromSaved() {
        offer(true);

        assertEquals(Collections.singletonList("UNSAVE Remove from saved"), added);
    }

    /** Off, paused or before the settings are read, the menu keeps the rows it had. */
    @Test
    public void offPausedAndUnreadyAddNothing() {
        Settings.SAVE_IN_POST_MENU.save(false);
        offer(false);
        Settings.SAVE_IN_POST_MENU.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        offer(false);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> offer(false));

        assertEquals(Collections.emptyList(), added);
        Settings.SAVE_IN_POST_MENU.resetToDefault();
        assertFalse("the row starts off", FeedSaveRow.wanted());
    }

    /** A menu with no post, or a build where the stubs answer nothing, adds no row. */
    @Test
    public void noPostOrNoOptionAddsNothing() {
        FeedSaveRow.offer(MENU, rows, () -> true, menu -> null, post -> false,
                (menu, list, option, label) -> added.add("row"), () -> SAVE, () -> UNSAVE);
        FeedSaveRow.offer(MENU, rows, () -> true, menu -> POST, post -> false,
                (menu, list, option, label) -> added.add("row"), () -> null, () -> null);
        FeedSaveRow.offer(MENU, rows);
        FeedSaveRow.offer(null, rows);
        FeedSaveRow.offer(MENU, null);

        assertEquals(Collections.emptyList(), added);
        assertEquals(Collections.emptyList(), rows);
    }

    /** An adder that throws leaves the menu as it was and says which hook threw. */
    @Test
    public void aThrowingAdderIsReported() {
        FeedSaveRow.offer(MENU, rows, () -> true, menu -> POST, post -> false,
                (menu, list, option, label) -> {
                    throw new IllegalStateException("broken builder");
                }, () -> SAVE, () -> UNSAVE);

        String missing = HookStatus.missing(FamilyNames.FEED_SAVE).toString();
        assertTrue(missing, missing.contains(FeedSaveRow.HOOK));
    }

    /** The short menu's kept options take Save and Unsave on the end, so the row added above stays. */
    @Test
    public void theShortMenuKeepsTheSaveRow() {
        List<Object> options = Arrays.asList("HIDE", "REPORT");

        List<?> allowed = FeedSaveRow.allow(options, FeedSaveRow::wanted, () -> SAVE, () -> UNSAVE);

        assertEquals(Arrays.asList("HIDE", "REPORT", SAVE, UNSAVE), allowed);
        assertEquals("the list handed in is left as it was", 2, options.size());
        List<Object> both = Arrays.asList(SAVE, "HIDE", UNSAVE);
        assertSame(both, FeedSaveRow.allow(both, FeedSaveRow::wanted, () -> SAVE, () -> UNSAVE));
    }

    /** Off, unpatched or given nothing, the short menu's options come back as they came. */
    @Test
    public void theShortMenuIsLeftAloneWhenTheRowIsOff() {
        List<Object> options = Arrays.asList("HIDE", "REPORT");
        Settings.SAVE_IN_POST_MENU.save(false);
        assertSame(options, FeedSaveRow.allow(options, FeedSaveRow::wanted, () -> SAVE, () -> UNSAVE));
        Settings.SAVE_IN_POST_MENU.save(true);
        assertSame("the unpatched stubs answer no option", options, FeedSaveRow.allow(options));
        assertNull(FeedSaveRow.allow(null));
        assertSame(options, FeedSaveRow.allow(options, () -> {
            throw new IllegalStateException("broken switch");
        }, () -> SAVE, () -> UNSAVE));
        String missing = HookStatus.missing(FamilyNames.FEED_SAVE).toString();
        assertTrue(missing, missing.contains(FeedSaveRow.HOOK));
    }

    /** Hide the Save button takes it out of a feed post's buttons and leaves Like and a Save button anywhere else. */
    @Test
    public void theSaveButtonLeavesOnlyAFeedPostsButtons() {
        FeedSaveRow.hider().watch(activity);
        assertFalse("off to start", FeedSaveRow.hider().listeningForTests(activity));
        assertEquals(View.VISIBLE, save.getVisibility());

        Settings.HIDE_FEED_SAVE_BUTTON.save(true);
        FeedSaveRow.hider().watch(activity);

        assertTrue(screen.getViewTreeObserver().dispatchOnPreDraw());
        assertEquals(View.GONE, save.getVisibility());
        assertEquals("Like stays", View.VISIBLE, like.getVisibility());
        assertEquals("a Save button outside a feed post stays", View.VISIBLE, savedElsewhere.getVisibility());
    }
}

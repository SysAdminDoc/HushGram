/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

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

/**
 * Hide search suggestions in comments and Hide the gift button (#869): the Meta AI prompt above the
 * comments and the gift button in the comment box go, each on its own switch, and the rest stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentSheetExtrasTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout sheet;
    private LinearLayout prompt;
    private TextView promptText;
    private FrameLayout list;
    private LinearLayout box;
    private EditText field;
    private Button gift;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        CommentSheetExtras.resetForTests();
        int promptId = View.generateViewId();
        int giftId = View.generateViewId();
        CommentSheetExtras.promptHider().idForTests(activity, CommentSheetExtras.PROMPT, promptId);
        CommentSheetExtras.giftHider().idForTests(activity, CommentSheetExtras.GIFT, giftId);

        // 457's sheet, as the S22 dumped it: the prompt row the stub inflated (a scroller holding
        // the "Search" pill), the comments, then the box's row with the field and the gift button last.
        sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        prompt = new LinearLayout(activity);
        prompt.setId(promptId);
        HorizontalScrollView scroller = new HorizontalScrollView(activity);
        promptText = new TextView(activity);
        promptText.setText("Search");
        scroller.addView(promptText);
        prompt.addView(scroller);
        list = new FrameLayout(activity);
        list.setId(View.generateViewId());
        list.addView(new TextView(activity));
        box = new LinearLayout(activity);
        box.setId(View.generateViewId());
        field = new EditText(activity);
        box.addView(field);
        gift = new Button(activity);
        gift.setId(giftId);
        box.addView(gift);
        sheet.addView(prompt);
        sheet.addView(list);
        sheet.addView(box);
        FrameLayout root = new FrameLayout(activity);
        root.addView(sheet);
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_COMMENT_SEARCH_PROMPT.resetToDefault();
        Settings.HIDE_COMMENT_GIFT_BUTTON.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        CommentSheetExtras.resetForTests();
        Settings.HIDE_COMMENT_SEARCH_PROMPT.resetToDefault();
        Settings.HIDE_COMMENT_GIFT_BUTTON.resetToDefault();
        Settings.HIDE_COMMENT_BOX.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private boolean frame() {
        return sheet.getViewTreeObserver().dispatchOnPreDraw();
    }

    private void watch() {
        CommentSheetExtras.promptHider().watch(activity);
        CommentSheetExtras.giftHider().watch(activity);
    }

    @Test
    public void offToStartLeavesBothAlone() {
        assertFalse(Settings.HIDE_COMMENT_SEARCH_PROMPT.get());
        assertFalse(Settings.HIDE_COMMENT_GIFT_BUTTON.get());

        watch();

        assertFalse(CommentSheetExtras.promptHider().listeningForTests(activity));
        assertFalse(CommentSheetExtras.giftHider().listeningForTests(activity));
        assertFalse(frame());
        assertEquals(View.VISIBLE, prompt.getVisibility());
        assertEquals(View.VISIBLE, gift.getVisibility());
    }

    /** Hide the comment box on its own leaves both switches' views to the box's own row. */
    @Test
    public void theCommentBoxSwitchAloneDoesNotTurnThemOn() {
        Settings.HIDE_COMMENT_BOX.save(true);

        watch();

        assertFalse(CommentSheetExtras.promptHider().listeningForTests(activity));
        assertFalse(CommentSheetExtras.giftHider().listeningForTests(activity));
    }

    @Test
    public void theSuggestionGoesAndTheCommentsAndGiftStay() {
        Settings.HIDE_COMMENT_SEARCH_PROMPT.save(true);

        watch();

        assertTrue("the frame that would show the suggestion is canceled", frame());
        assertEquals(View.GONE, prompt.getVisibility());
        assertEquals("its text goes with its row, untouched", View.VISIBLE, promptText.getVisibility());
        assertEquals("the comments stay", View.VISIBLE, list.getVisibility());
        assertEquals("the gift button has its own switch", View.VISIBLE, gift.getVisibility());
        assertEquals("nothing is taken out", 3, sheet.getChildCount());
        assertTrue(HookStatus.missing(FamilyNames.HIDE_COMMENTS).toString(),
                HookStatus.missing(FamilyNames.HIDE_COMMENTS).isEmpty());
    }

    @Test
    public void theGiftButtonGoesAndTheBoxStays() {
        Settings.HIDE_COMMENT_GIFT_BUTTON.save(true);

        watch();

        assertTrue(frame());
        assertEquals(View.GONE, gift.getVisibility());
        assertEquals("the box stays", View.VISIBLE, box.getVisibility());
        assertEquals(View.VISIBLE, field.getVisibility());
        assertEquals("the suggestion has its own switch", View.VISIBLE, prompt.getVisibility());
    }

    /** Instagram showing the button again, as it does when it binds the box anew, is undone before the frame. */
    @Test
    public void aButtonShownAgainGoesBeforeTheNextFrame() {
        Settings.HIDE_COMMENT_GIFT_BUTTON.save(true);
        watch();
        frame();

        gift.setVisibility(View.VISIBLE);

        assertTrue(frame());
        assertEquals(View.GONE, gift.getVisibility());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.HIDE_COMMENT_SEARCH_PROMPT.save(true);
        Settings.HIDE_COMMENT_GIFT_BUTTON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        watch();

        assertFalse(CommentSheetExtras.promptHider().listeningForTests(activity));
        assertEquals(View.VISIBLE, prompt.getVisibility());
        assertEquals(View.VISIBLE, gift.getVisibility());
    }
}

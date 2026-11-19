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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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

/** Hide the comment box (#762): the box at the bottom of a comment sheet goes, the comments stay. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentBoxTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout sheet;
    private FrameLayout list;
    private LinearLayout row;
    private EditText box;
    private TextView post;
    private View compose;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        CommentBox.resetForTests();
        int rowId = View.generateViewId();
        int composeId = View.generateViewId();
        CommentBox.hider().idForTests(activity, CommentBox.ROW, rowId);
        CommentBox.hider().idForTests(activity, CommentBox.COMPOSE, composeId);

        // 456's sheet: the list on top, then the box's row (picture, field, Post), then the Compose box, gone.
        sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        list = new FrameLayout(activity);
        list.setId(View.generateViewId());
        list.addView(new TextView(activity));
        row = new LinearLayout(activity);
        row.setId(rowId);
        row.addView(new ImageView(activity));
        box = new EditText(activity);
        row.addView(box);
        post = new TextView(activity);
        row.addView(post);
        compose = new FrameLayout(activity);
        compose.setId(composeId);
        sheet.addView(list);
        sheet.addView(row);
        sheet.addView(compose);
        FrameLayout root = new FrameLayout(activity);
        root.addView(sheet);
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_COMMENT_BOX.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        CommentBox.resetForTests();
        Settings.HIDE_COMMENT_BOX.resetToDefault();
        Settings.HIDE_COMMENTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private boolean frame() {
        return sheet.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test
    public void offToStartLeavesTheBoxAlone() {
        assertFalse(Settings.HIDE_COMMENT_BOX.get());

        CommentBox.hider().watch(activity);

        assertFalse(CommentBox.listeningForTests(activity));
        assertFalse(frame());
        assertEquals(View.VISIBLE, row.getVisibility());
    }

    /** Hide comments on its own leaves the box where it is. */
    @Test
    public void hideCommentsAloneKeepsTheBox() {
        Settings.HIDE_COMMENTS.save(true);

        CommentBox.hider().watch(activity);

        assertFalse(CommentBox.listeningForTests(activity));
        assertEquals(View.VISIBLE, row.getVisibility());
    }

    @Test
    public void onTakesTheBoxOffAndLeavesTheComments() {
        Settings.HIDE_COMMENT_BOX.save(true);

        CommentBox.hider().watch(activity);

        assertTrue("the frame that would show the box is canceled", frame());
        assertEquals(View.GONE, row.getVisibility());
        assertEquals("the comments stay", View.VISIBLE, list.getVisibility());
        assertEquals("nothing is taken out", 3, sheet.getChildCount());
        assertEquals("the field goes with its row, untouched", View.VISIBLE, box.getVisibility());
        assertEquals(View.VISIBLE, post.getVisibility());
        assertTrue(HookStatus.missing(FamilyNames.HIDE_COMMENTS).toString(),
                HookStatus.missing(FamilyNames.HIDE_COMMENTS).isEmpty());
    }

    /** A sheet that shows the Compose box instead loses that one. */
    @Test
    public void theComposeBoxGoesToo() {
        Settings.HIDE_COMMENT_BOX.save(true);
        row.setId(View.NO_ID);

        CommentBox.hider().watch(activity);
        frame();

        assertEquals(View.GONE, compose.getVisibility());
        assertEquals("the comments stay", View.VISIBLE, list.getVisibility());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.HIDE_COMMENT_BOX.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        CommentBox.hider().watch(activity);

        assertFalse(CommentBox.listeningForTests(activity));
        assertEquals(View.VISIBLE, row.getVisibility());
    }
}

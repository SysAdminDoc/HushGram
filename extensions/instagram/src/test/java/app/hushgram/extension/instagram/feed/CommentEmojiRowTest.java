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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide the comment emoji row: the row of quick emoji above the comment box goes, the box stays. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentEmojiRowTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout composer;
    private LinearLayout parent;
    private LinearLayout strip;
    private EditText box;
    private TextView post;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        CommentEmojiRow.hider().resetForTests();
        int parentId = View.generateViewId();
        int stripId = View.generateViewId();
        CommentEmojiRow.hider().idForTests(activity, CommentEmojiRow.PARENT, parentId);
        CommentEmojiRow.hider().idForTests(activity, CommentEmojiRow.CONTAINER, stripId);

        composer = new LinearLayout(activity);
        composer.setOrientation(LinearLayout.VERTICAL);
        parent = new LinearLayout(activity);
        parent.setId(parentId);
        strip = new LinearLayout(activity);
        strip.setId(stripId);
        strip.addView(new TextView(activity));
        parent.addView(strip);
        box = new EditText(activity);
        box.setId(View.generateViewId());
        post = new TextView(activity);
        post.setId(View.generateViewId());
        composer.addView(parent);
        composer.addView(box);
        composer.addView(post);
        FrameLayout root = new FrameLayout(activity);
        root.addView(composer);
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_COMMENT_EMOJI_ROW.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        CommentEmojiRow.hider().resetForTests();
        Settings.HIDE_COMMENT_EMOJI_ROW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private boolean frame() {
        return composer.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test
    public void offToStartLeavesTheRowAlone() {
        assertFalse(Settings.HIDE_COMMENT_EMOJI_ROW.get());

        CommentEmojiRow.hider().watch(activity);

        assertFalse(CommentEmojiRow.hider().listeningForTests(activity));
        assertFalse(frame());
        assertEquals(View.VISIBLE, parent.getVisibility());
    }

    @Test
    public void onTakesTheRowOffAndLeavesTheBoxAndPost() {
        Settings.HIDE_COMMENT_EMOJI_ROW.save(true);

        CommentEmojiRow.hider().watch(activity);

        assertTrue("the frame that would show the row is canceled", frame());
        assertEquals(View.GONE, parent.getVisibility());
        assertEquals("the box stays", View.VISIBLE, box.getVisibility());
        assertEquals("Post stays", View.VISIBLE, post.getVisibility());
        assertEquals("nothing is taken out", 3, composer.getChildCount());
        assertTrue(HookStatus.missing("Hide the comment emoji row").toString(),
                HookStatus.missing("Hide the comment emoji row").isEmpty());
    }

    @Test
    public void onlyTheStripIsHiddenWhenTheRowViewIsAnotherOne() {
        Settings.HIDE_COMMENT_EMOJI_ROW.save(true);
        parent.setId(View.NO_ID);

        CommentEmojiRow.hider().watch(activity);
        frame();

        assertEquals(View.GONE, strip.getVisibility());
        assertEquals("the box stays", View.VISIBLE, box.getVisibility());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.HIDE_COMMENT_EMOJI_ROW.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);

        CommentEmojiRow.hider().watch(activity);

        assertFalse(CommentEmojiRow.hider().listeningForTests(activity));
        assertEquals(View.VISIBLE, parent.getVisibility());
        assertFalse("451's strip is drawn as Instagram found", CommentEmojiRow.hidesRow(0));
    }

    /** 451's sheet asks whether it has no emoji for its strip: off keeps its answer, on says none. */
    @Test
    public void theSheetsEmojiCheckAnswersEmptyOnlyWhileOn() {
        assertFalse("off, the strip is drawn", CommentEmojiRow.hidesRow(0));
        assertTrue("no emoji stays no emoji", CommentEmojiRow.hidesRow(1));

        Settings.HIDE_COMMENT_EMOJI_ROW.save(true);

        assertTrue("on, the strip isn't drawn", CommentEmojiRow.hidesRow(0));
        assertTrue(CommentEmojiRow.hidesRow(1));
        assertTrue(HookStatus.missing("Hide the comment emoji row").toString(),
                HookStatus.missing("Hide the comment emoji row").isEmpty());
    }
}

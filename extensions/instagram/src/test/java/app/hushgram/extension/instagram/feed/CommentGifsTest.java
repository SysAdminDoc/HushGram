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
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide GIFs in comments: which comments lose their row or their GIF, and what the comment box still offers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentGifsTest {
    private enum Kind { AVATAR, GIF }

    private static final Object GIF = new Object();

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private ImageView button;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        CommentGifs.hider().resetForTests();
        int buttonId = View.generateViewId();
        CommentGifs.hider().idForTests(activity, CommentGifs.BUTTON, buttonId);
        button = new ImageView(activity);
        button.setId(buttonId);
        root = new FrameLayout(activity);
        root.addView(button);
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_COMMENT_GIFS.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        CommentGifs.hider().resetForTests();
        Settings.HIDE_COMMENT_GIFS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    @Test
    public void offToStartLeavesEverythingAlone() {
        assertFalse(Settings.HIDE_COMMENT_GIFS.get());

        assertFalse(CommentGifs.hiding());
        assertFalse(CommentGifs.dropsComment(GIF, null, ""));
        assertFalse(CommentGifs.dropsComment(null, GIF, null));
        assertSame(GIF, CommentGifs.keptGif(GIF));
        assertFalse(CommentGifs.hidesMedia(GIF));
        Set<Kind> kinds = EnumSet.allOf(Kind.class);
        assertSame(kinds, CommentGifs.composerTypes(kinds));

        CommentGifs.hider().watch(activity);
        assertFalse(CommentGifs.hider().listeningForTests(activity));
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    @Test
    public void onDropsACommentWithNothingButAPicture() {
        Settings.HIDE_COMMENT_GIFS.save(true);

        assertTrue(CommentGifs.hiding());
        assertTrue("a GIF with no text", CommentGifs.dropsComment(GIF, null, null));
        assertTrue("a GIF with empty text", CommentGifs.dropsComment(GIF, null, ""));
        assertTrue("a sticker", CommentGifs.dropsComment(null, GIF, " "));
        assertTrue("a GIF reply's mention", CommentGifs.dropsComment(GIF, null, "@some.name "));
        assertTrue("two mentions", CommentGifs.dropsComment(GIF, null, "@some_name @other"));
        assertTrue(HookStatus.missing("Hide GIFs in comments").toString(),
                HookStatus.missing("Hide GIFs in comments").isEmpty());
    }

    @Test
    public void onKeepsACommentWithWordsOrNoPicture() {
        Settings.HIDE_COMMENT_GIFS.save(true);

        assertFalse("words", CommentGifs.dropsComment(GIF, null, "nice one"));
        assertFalse("a mention and words", CommentGifs.dropsComment(GIF, null, "@some.name nice"));
        assertFalse("no picture, no words", CommentGifs.dropsComment(null, null, ""));
        assertFalse("no picture", CommentGifs.dropsComment(null, null, "words"));
    }

    @Test
    public void onTakesThePictureOffAKeptComment() {
        Settings.HIDE_COMMENT_GIFS.save(true);

        assertNull(CommentGifs.keptGif(GIF));
        assertNull(CommentGifs.keptGif(null));
        assertTrue(CommentGifs.hidesMedia(GIF));
        assertFalse("a comment's own photo stays", CommentGifs.hidesMedia(null));
    }

    @Test
    public void onTakesOnlyTheGifKindOffTheBox() {
        Settings.HIDE_COMMENT_GIFS.save(true);

        assertEquals(Collections.singleton(Kind.AVATAR), CommentGifs.composerTypes(EnumSet.allOf(Kind.class)));
        assertTrue("nothing left, so no button", CommentGifs.composerTypes(EnumSet.of(Kind.GIF)).isEmpty());
        Set<Kind> stickersOnly = EnumSet.of(Kind.AVATAR);
        assertSame(stickersOnly, CommentGifs.composerTypes(stickersOnly));
        Set<Kind> none = EnumSet.noneOf(Kind.class);
        assertSame(none, CommentGifs.composerTypes(none));
        assertNull(CommentGifs.composerTypes(null));
        Set<Object> strangers = Collections.singleton("GIF");
        assertSame("only the enum's own kind counts", strangers, CommentGifs.composerTypes(strangers));
    }

    @Test
    public void onTakesTheOlderLayoutsButtonOff() {
        Settings.HIDE_COMMENT_GIFS.save(true);

        CommentGifs.hider().watch(activity);

        assertTrue(CommentGifs.hider().listeningForTests(activity));
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.GONE, button.getVisibility());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.HIDE_COMMENT_GIFS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        assertFalse(CommentGifs.hiding());
        assertFalse(CommentGifs.dropsComment(GIF, null, ""));
        assertSame(GIF, CommentGifs.keptGif(GIF));
        assertFalse(CommentGifs.hidesMedia(GIF));
        Set<Kind> kinds = EnumSet.allOf(Kind.class);
        assertSame(kinds, CommentGifs.composerTypes(kinds));
        CommentGifs.hider().watch(activity);
        assertFalse(CommentGifs.hider().listeningForTests(activity));
    }

    /** A set that fails while it's read hands back what came in and is reported, with nothing thrown into Instagram. */
    @Test
    public void aFailingSetStaysAsItCameAndIsReported() {
        Settings.HIDE_COMMENT_GIFS.save(true);
        Set<Object> broken = new AbstractSet<Object>() {
            @Override public Iterator<Object> iterator() { throw new IllegalStateException("read"); }
            @Override public int size() { return 1; }
        };

        assertSame(broken, CommentGifs.composerTypes(broken));
        assertFalse(Arrays.toString(HookStatus.missing("Hide GIFs in comments").toArray()),
                HookStatus.missing("Hide GIFs in comments").isEmpty());
    }
}

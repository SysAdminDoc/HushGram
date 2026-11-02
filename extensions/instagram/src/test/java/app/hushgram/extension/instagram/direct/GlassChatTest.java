/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.HashMap;
import java.util.Map;

import app.hushgram.extension.instagram.feed.GlassTopBar;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Glass in chats (#449): the chat's header gets the glass and its buttons discs, and it all comes off again. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class GlassChatTest {
    private static final int WIDE = 1080;
    private static final int TALL = 2000;
    private static final int HEADER = 150;
    private static final int ROOT_ID = 0x7f000001;
    private static final int BACK_ID = 0x7f000002;
    private static final int BUTTONS_ID = 0x7f000003;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private Activity activity;
    private FrameLayout root;
    private View messages;
    private FrameLayout header;
    private ImageView back;
    private LinearLayout buttons;
    private ImageView call;
    private ImageView video;

    @Before
    public void prepare() {
        resetAll();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        root = new FrameLayout(activity);
        root.setId(ROOT_ID);
        messages = new View(activity);
        messages.setBackgroundColor(Color.RED);
        header = new FrameLayout(activity);
        header.setBackgroundColor(Color.BLACK);
        back = new ImageView(activity);
        back.setId(BACK_ID);
        back.setBackgroundColor(Color.DKGRAY);
        buttons = new LinearLayout(activity);
        buttons.setId(BUTTONS_ID);
        call = new ImageView(activity);
        video = new ImageView(activity);
        buttons.addView(call, new LinearLayout.LayoutParams(100, 100));
        buttons.addView(video, new LinearLayout.LayoutParams(100, 100));
        header.addView(back, new FrameLayout.LayoutParams(100, 100));
        header.addView(buttons, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 100));
        root.addView(messages, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEADER));
        activity.setContentView(root);
        layout();
        Map<String, Integer> ids = new HashMap<>();
        ids.put(GlassChat.CHAT, ROOT_ID);
        ids.put(GlassChat.BACK, BACK_ID);
        ids.put(GlassChat.BUTTONS, BUTTONS_ID);
        GlassChat.idsForTests = ids;
        GlassChat.restore();
    }

    @After
    public void restore() {
        GlassChat.restore();
        GlassChat.idsForTests = null;
        resetAll();
    }

    private static void resetAll() {
        Settings.GLASS_CHAT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private void layout() {
        ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDE, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(TALL, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDE, TALL);
    }

    private void frame() {
        GlassChat.check(activity);
        header.getViewTreeObserver().dispatchOnPreDraw();
    }

    private static boolean hasDisc(View button) {
        return button.getBackground() instanceof GlassChat.Rim;
    }

    @Test
    public void theSwitchStartsOff() {
        assertEquals(Boolean.FALSE, Settings.GLASS_CHAT.defaultValue);
    }

    @Test
    public void theHeaderIsTheViewHoldingBothButtonViews() {
        assertSame(header, GlassChat.find(activity));
    }

    @Test
    public void offLeavesInstagramsHeaderAlone() {
        frame();

        assertFalse(GlassTopBar.showing(header));
        assertFalse(hasDisc(back));
        assertFalse(hasDisc(call));
        assertEquals(Color.BLACK, ((ColorDrawable) header.getBackground()).getColor());
        assertEquals(Color.DKGRAY, ((ColorDrawable) back.getBackground()).getColor());
    }

    @Test
    public void onGivesTheHeaderGlassAndEveryButtonADisc() {
        Settings.GLASS_CHAT.save(true);
        frame();

        assertTrue(GlassTopBar.showing(header));
        assertTrue(hasDisc(back));
        assertTrue(hasDisc(call));
        assertTrue(hasDisc(video));
        Drawable under = ((GlassChat.Rim) back.getBackground()).original;
        assertNotNull("the button's own background is kept under the disc", under);
        assertEquals(Color.DKGRAY, ((ColorDrawable) under).getColor());
        assertEquals("the messages are untouched", Color.RED, ((ColorDrawable) messages.getBackground()).getColor());
        assertTrue(back.isEnabled());
    }

    @Test
    public void framedTwiceItIsDressedOnce() {
        Settings.GLASS_CHAT.save(true);
        frame();
        Drawable first = call.getBackground();
        frame();
        assertSame(first, call.getBackground());
    }

    @Test
    public void turningItOffGivesBackEveryBackgroundAndTheHeader() {
        Settings.GLASS_CHAT.save(true);
        frame();

        Settings.GLASS_CHAT.save(false);
        frame();

        assertFalse(GlassTopBar.showing(header));
        assertEquals(Color.BLACK, ((ColorDrawable) header.getBackground()).getColor());
        assertEquals(Color.DKGRAY, ((ColorDrawable) back.getBackground()).getColor());
        assertNull(call.getBackground());
        assertNull(video.getBackground());
    }

    @Test
    public void pauseTakesItOffAndResumingPutsItBack() {
        Settings.GLASS_CHAT.save(true);
        frame();
        assertTrue(hasDisc(back));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();
        assertFalse(GlassTopBar.showing(header));
        assertFalse(hasDisc(back));
        assertEquals(Color.DKGRAY, ((ColorDrawable) back.getBackground()).getColor());

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        frame();
        assertTrue(GlassTopBar.showing(header));
        assertTrue(hasDisc(back));
    }

    @Test
    public void aScreenThatIsNotAChatIsNotTouched() {
        GlassChat.idsForTests = new HashMap<>();
        Settings.GLASS_CHAT.save(true);
        frame();

        assertNull(GlassChat.find(activity));
        assertFalse(GlassTopBar.showing(header));
        assertFalse(hasDisc(back));
    }
}

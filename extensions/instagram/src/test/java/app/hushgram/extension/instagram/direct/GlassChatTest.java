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
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import org.robolectric.annotation.GraphicsMode;
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

    /** Instagram 453's chat: the header sits beside the chat's root, three parents up, not inside it. */
    @Test
    public void aHeaderBesideTheChatsRootIsFoundAndDressed() {
        FrameLayout screen = new FrameLayout(activity);
        LinearLayout beside = new LinearLayout(activity);
        ImageView back453 = new ImageView(activity);
        back453.setId(BACK_ID);
        LinearLayout buttons453 = new LinearLayout(activity);
        buttons453.setId(BUTTONS_ID);
        ImageView call453 = new ImageView(activity);
        buttons453.addView(call453, new LinearLayout.LayoutParams(100, 100));
        beside.addView(back453, new LinearLayout.LayoutParams(100, 100));
        beside.addView(buttons453, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 100));
        FrameLayout below = new FrameLayout(activity);
        FrameLayout toggle = new FrameLayout(activity);
        LinearLayout chat = new LinearLayout(activity);
        chat.setId(ROOT_ID);
        toggle.addView(chat, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        below.addView(toggle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.addView(beside, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEADER));
        screen.addView(below, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setContentView(screen);
        ShadowLooper.idleMainLooper();

        assertSame(beside, GlassChat.find(activity));

        Settings.GLASS_CHAT.save(true);
        GlassChat.check(activity);
        beside.getViewTreeObserver().dispatchOnPreDraw();
        assertTrue(GlassTopBar.showing(beside));
        assertTrue(hasDisc(back453));
        assertTrue(hasDisc(call453));
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

    /** A chat's header has no line along its bottom: its glass runs on into the messages (#537). */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void aChatsHeaderHasNoLineAlongItsBottom() {
        Settings.GLASS_CHAT.save(true);
        frame();
        assertTrue(GlassTopBar.showing(header));

        Bitmap bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888);
        Drawable frost = header.getBackground();
        frost.setBounds(0, 0, 100, 50);
        frost.draw(new Canvas(bitmap));

        assertEquals("the bottom row is the glass, not a line", bitmap.getPixel(50, 20), bitmap.getPixel(50, 49));
    }

    /**
     * The discs are glass like the tab bar's (#537, #542), not flat grey: on a dark screen the rim is lit at
     * the top left and again, less, at the bottom right, and a sheen runs down from the top. 44 dp at a
     * density of 3 is 132 px across, a radius of 66 round (75, 75), with a rim 3 px wide just inside it.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void aDarkDiscIsLitAlongItsRimAndAcrossItsTop() {
        Bitmap bitmap = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        GlassChat.Disc disc = new GlassChat.Disc(true, 3f);
        disc.setBounds(0, 0, 150, 150);
        disc.draw(canvas);

        int topLeft = Color.red(bitmap.getPixel(29, 29));
        int topRight = Color.red(bitmap.getPixel(120, 29));
        int bottomRight = Color.red(bitmap.getPixel(120, 120));
        assertTrue("lit at the top left: " + topLeft + " against " + topRight, topLeft > topRight + 40);
        assertTrue("lit again at the bottom right: " + bottomRight + " against " + topRight, bottomRight > topRight + 20);
        int high = Color.red(bitmap.getPixel(75, 30));
        int low = Color.red(bitmap.getPixel(75, 110));
        assertTrue("the sheen at the top: " + high + " against " + low, high > low + 15);
        assertEquals("nothing past the disc", Color.BLACK, bitmap.getPixel(2, 2));
    }

    /** On a light screen the glass is frosted white over the header, with a soft shadow just under it. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void aLightDiscIsFrostedWhiteWithAShadowUnderIt() {
        int under = 0xffdddddd;
        Bitmap bitmap = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(under);
        GlassChat.Disc disc = new GlassChat.Disc(false, 3f);
        disc.setBounds(0, 0, 150, 150);
        disc.draw(canvas);

        assertTrue("whiter than the header in the middle", Color.red(bitmap.getPixel(75, 75)) > Color.red(under));
        assertTrue("the shadow just under the glass", Color.red(bitmap.getPixel(75, 140)) < Color.red(under));
        assertEquals("nothing past the disc", under, bitmap.getPixel(2, 2));
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

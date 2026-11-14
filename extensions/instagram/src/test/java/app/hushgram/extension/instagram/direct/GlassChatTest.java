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
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.feed.GlassTopBar;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Glass in chats (#449): the chat's header gets the glass and its buttons discs, the message bar and the stories'
 * reply boxes a pill of it (#391), and it all comes off again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class GlassChatTest {
    private static final int WIDE = 1080;
    private static final int TALL = 2000;
    private static final int HEADER = 150;
    private static final int BAR = 140;
    private static final int ROOT_ID = 0x7f000001;
    private static final int BACK_ID = 0x7f000002;
    private static final int BUTTONS_ID = 0x7f000003;
    private static final int MESSAGES_ID = 0x7f000004;
    private static final int PILL_ID = 0x7f000005;
    private static final int VIEWER_ID = 0x7f000006;
    private static final int PAGE_ID = 0x7f000007;
    private static final int STORY_BOX_ID = 0x7f000008;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private Activity activity;
    private FrameLayout root;
    private View messages;
    private FrameLayout header;
    private ImageView back;
    private LinearLayout buttons;
    private ImageView call;
    private ImageView video;
    private LinearLayout textArea;
    private View sendButton;

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
        ids.put(GlassChat.MESSAGES, MESSAGES_ID);
        ids.put(GlassChat.MESSAGE_BOX, PILL_ID);
        ids.put(GlassChat.STORY_VIEWER, VIEWER_ID);
        ids.put(GlassChat.STORY_PAGE, PAGE_ID);
        ids.put(GlassChat.STORY_BOX, STORY_BOX_ID);
        GlassChat.idsForTests = ids;
        // Takes the discs off and forgets when the stories were last searched.
        GlassChat.unfollow();
    }

    @After
    public void restore() {
        GlassChat.unfollow();
        GlassChat.idsForTests = null;
        resetAll();
    }

    private static void resetAll() {
        Settings.GLASS_CHAT.resetToDefault();
        Settings.GLASS_CHAT_TOP_BAR.resetToDefault();
        Settings.GLASS_STORY_REPLY_BOX.resetToDefault();
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

    /**
     * Instagram 454's message bar: the messages, then the bar holding the picture of its grey pill and, over the
     * whole picture, a row with no fill holding the text area, which has a grey fill of its own and a smaller
     * button, in the view drawing the messages before the bar.
     */
    private ImageView messageBar() {
        FrameLayout below = new FrameLayout(activity);
        below.setId(MESSAGES_ID);
        FrameLayout bar = new FrameLayout(activity);
        ImageView pill = new ImageView(activity);
        pill.setId(PILL_ID);
        pill.setBackgroundColor(Color.GRAY);
        LinearLayout row = new LinearLayout(activity);
        textArea = new LinearLayout(activity);
        textArea.setBackground(greyFill());
        sendButton = new View(activity);
        sendButton.setBackgroundColor(Color.BLUE);
        textArea.addView(sendButton, new LinearLayout.LayoutParams(100, 100));
        row.addView(textArea, new LinearLayout.LayoutParams(MATCH, BAR));
        bar.addView(pill, new FrameLayout.LayoutParams(MATCH, BAR));
        bar.addView(row, new FrameLayout.LayoutParams(MATCH, BAR));
        below.addView(new View(activity), new FrameLayout.LayoutParams(MATCH, MATCH));
        below.addView(bar, new FrameLayout.LayoutParams(MATCH, BAR, Gravity.BOTTOM));
        root.addView(below, 1, new FrameLayout.LayoutParams(MATCH, MATCH));
        layout();
        return pill;
    }

    /** The text area's own look: a grey pill with round ends, which isn't opaque over its corners. */
    private static GradientDrawable greyFill() {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(0xff262626);
        fill.setCornerRadius(500f);
        return fill;
    }

    private FrameLayout storyViewer() {
        FrameLayout viewer = new FrameLayout(activity);
        viewer.setId(VIEWER_ID);
        root.addView(viewer, new FrameLayout.LayoutParams(MATCH, MATCH));
        return viewer;
    }

    /** One person's page in Instagram 454's story viewer: the story, then a footer with the reply box and its text. */
    private FrameLayout storyBox(FrameLayout viewer) {
        FrameLayout page = new FrameLayout(activity);
        page.setId(PAGE_ID);
        FrameLayout footer = new FrameLayout(activity);
        FrameLayout box = new FrameLayout(activity);
        box.setId(STORY_BOX_ID);
        box.setBackground(outline());
        box.addView(new View(activity), new FrameLayout.LayoutParams(600, BAR));
        footer.addView(box, new FrameLayout.LayoutParams(800, BAR));
        page.addView(new View(activity), new FrameLayout.LayoutParams(MATCH, MATCH));
        page.addView(footer, new FrameLayout.LayoutParams(MATCH, 170, Gravity.BOTTOM));
        viewer.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));
        return box;
    }

    /** The reply box's own look: a thin light outline round a clear pill. */
    private static GradientDrawable outline() {
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(500f);
        outline.setStroke(4, 0x99ffffff);
        return outline;
    }

    @Test
    public void theSwitchesStartOff() {
        assertEquals(Boolean.FALSE, Settings.GLASS_CHAT.defaultValue);
        assertEquals(Boolean.FALSE, Settings.GLASS_STORY_REPLY_BOX.defaultValue);
        assertEquals("the top bar's glass comes with Glass in chats", Boolean.TRUE, Settings.GLASS_CHAT_TOP_BAR.defaultValue);
    }

    /**
     * #670: with the top bar's glass off, the header keeps Instagram's own fill while the round buttons and the
     * message bar keep their glass, and turning it back on brings the header's glass back.
     */
    @Test
    public void theTopBarSwitchLeavesTheButtonsAndMessageBarTheirGlass() {
        Drawable fill = new ColorDrawable(Color.BLACK);
        header.setBackground(fill);
        Settings.GLASS_CHAT.save(true);
        Settings.GLASS_CHAT_TOP_BAR.save(false);
        ImageView pill = messageBar();
        frame();

        assertFalse("no glass band behind the top bar", GlassTopBar.showing(header));
        assertSame("Instagram's own fill stays", fill, header.getBackground());
        assertTrue(hasDisc(back));
        assertTrue(hasDisc(call));
        assertTrue(hasDisc(video));
        assertTrue(GlassTopBar.boxShowing(pill));

        Settings.GLASS_CHAT_TOP_BAR.save(true);
        frame();
        assertTrue(GlassTopBar.showing(header));

        Settings.GLASS_CHAT_TOP_BAR.save(false);
        frame();
        assertFalse(GlassTopBar.showing(header));
        assertSame(fill, header.getBackground());
        assertTrue(hasDisc(back));
        assertTrue(GlassTopBar.boxShowing(pill));
    }

    /**
     * #391: the message bar takes the glass over Instagram's grey pill, which is a picture with nothing in it, and
     * the text area in the row laid over the whole pill loses its grey fill, which hid the glass on a Galaxy S25.
     */
    @Test
    public void onGivesTheMessageBarGlassOverInstagramsPill() {
        Settings.GLASS_CHAT.save(true);
        ImageView pill = messageBar();
        frame();

        assertTrue(GlassTopBar.boxShowing(pill));
        assertNotNull("the glass is over the pill", pill.getForeground());
        assertEquals("the pill itself is untouched", Color.GRAY, ((ColorDrawable) pill.getBackground()).getColor());
        assertNull("the text area's fill is off the glass", textArea.getBackground());
        assertEquals("a button over part of it keeps its own", Color.BLUE,
                ((ColorDrawable) sendButton.getBackground()).getColor());
    }

    @Test
    public void turningItOffTakesTheGlassOffTheMessageBar() {
        Settings.GLASS_CHAT.save(true);
        ImageView pill = messageBar();
        Drawable fill = textArea.getBackground();
        frame();

        Settings.GLASS_CHAT.save(false);
        frame();

        assertFalse(GlassTopBar.boxShowing(pill));
        assertNull(pill.getForeground());
        assertEquals(Color.GRAY, ((ColorDrawable) pill.getBackground()).getColor());
        assertSame("the text area has its fill back", fill, textArea.getBackground());
    }

    /** A view by the pill's name that isn't in the chat's messages isn't the message bar, and is left alone. */
    @Test
    public void aPillOutsideTheMessagesIsLeftAlone() {
        Settings.GLASS_CHAT.save(true);
        ImageView pill = new ImageView(activity);
        pill.setId(PILL_ID);
        root.addView(pill, new FrameLayout.LayoutParams(MATCH, BAR));
        layout();
        frame();

        assertTrue(GlassTopBar.showing(header));
        assertFalse(GlassTopBar.boxShowing(pill));
    }

    /**
     * #391: with Glass reply box on stories, every page's reply box takes the glass behind it, under its own
     * outline, and gives the outline back when it's turned off.
     */
    @Test
    public void theStorySwitchGivesEveryPagesReplyBoxGlass() {
        Settings.GLASS_STORY_REPLY_BOX.save(true);
        FrameLayout viewer = storyViewer();
        FrameLayout first = storyBox(viewer);
        FrameLayout second = storyBox(viewer);
        Drawable outline = first.getBackground();
        layout();
        frame();

        assertTrue(GlassTopBar.boxShowing(first));
        assertTrue(GlassTopBar.boxShowing(second));
        assertNull("the glass is behind the box's text, not over it", first.getForeground());

        Settings.GLASS_STORY_REPLY_BOX.save(false);
        frame();

        assertFalse(GlassTopBar.boxShowing(first));
        assertFalse(GlassTopBar.boxShowing(second));
        assertSame(outline, first.getBackground());
    }

    @Test
    public void theChatSwitchAloneLeavesStoriesAlone() {
        Settings.GLASS_CHAT.save(true);
        FrameLayout box = storyBox(storyViewer());
        layout();
        frame();

        assertTrue(GlassTopBar.showing(header));
        assertFalse(GlassTopBar.boxShowing(box));
    }

    /** The reply box's glass is a pill with round ends, nothing past them, and the box's outline drawn over it. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void aStorysReplyBoxIsAPillUnderItsOwnOutline() {
        Settings.GLASS_STORY_REPLY_BOX.save(true);
        FrameLayout box = storyBox(storyViewer());
        layout();
        frame();
        assertTrue(GlassTopBar.boxShowing(box));

        Bitmap bitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888);
        Drawable frost = box.getBackground();
        frost.setBounds(0, 0, 300, 100);
        frost.draw(new Canvas(bitmap));

        assertEquals("nothing past the pill's round end", 0, Color.alpha(bitmap.getPixel(3, 3)));
        int middle = bitmap.getPixel(150, 50);
        assertTrue("the glass inside it", Color.alpha(middle) > 0);
        int edge = bitmap.getPixel(150, 1);
        assertTrue("the outline over the glass: " + Integer.toHexString(edge) + " against " + Integer.toHexString(middle),
                Color.red(edge) > Color.red(middle) + 60);
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

    /**
     * A chat that opens on a frame too soon after the last look for one, and then sits still, still gets its glass:
     * no frame comes to look again, so the look waits until it's due (#513).
     */
    @Test
    public void aChatThatOpensOnAStillScreenStillGetsTheGlass() {
        Settings.GLASS_CHAT.save(true);
        root.setId(View.NO_ID);
        GlassChat.follow(activity);
        ViewTreeObserver frames = activity.getWindow().getDecorView().getViewTreeObserver();
        frames.dispatchOnPreDraw();
        root.setId(ROOT_ID);
        frames.dispatchOnPreDraw();
        assertFalse("too soon to look again", hasDisc(back));

        ShadowLooper.idleMainLooper(GlassChat.SEARCH_EVERY_MS + 100, TimeUnit.MILLISECONDS);

        assertTrue(hasDisc(back));
        assertTrue(hasDisc(call));
        assertTrue("a frame came to put the glass on", GlassTopBar.showing(header));
    }

    /** The message bar showing up just after the chat's header was dressed, on a still chat, gets the glass too. */
    @Test
    public void aMessageBarThatShowsUpOnAStillChatStillGetsTheGlass() {
        Settings.GLASS_CHAT.save(true);
        GlassChat.follow(activity);
        ViewTreeObserver frames = activity.getWindow().getDecorView().getViewTreeObserver();
        frames.dispatchOnPreDraw();
        assertTrue(hasDisc(back));
        ImageView pill = messageBar();
        frames.dispatchOnPreDraw();
        assertFalse("too soon to look again", GlassTopBar.boxShowing(pill));

        ShadowLooper.idleMainLooper(GlassChat.SEARCH_EVERY_MS + 100, TimeUnit.MILLISECONDS);
        // The frame the glass asks for: SDK 28's shadow never draws the new bar, so it drops the bar's invalidate.
        frames.dispatchOnPreDraw();

        assertTrue(GlassTopBar.boxShowing(pill));
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

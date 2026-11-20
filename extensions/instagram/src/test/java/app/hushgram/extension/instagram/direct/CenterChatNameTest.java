/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Center names in chats (#779): the chat's name and the username under it move onto the middle of the top bar,
 * or as near it as the picture and the call buttons leave room for, and go back when it's off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CenterChatNameTest {
    private static final int WIDE = 1080;
    private static final int TALL = 2000;
    private static final int HEADER = 150;
    private static final int ROOT_ID = 0x7f000001;
    private static final int BACK_ID = 0x7f000002;
    private static final int BUTTONS_ID = 0x7f000003;
    private static final int AREA_ID = 0x7f000004;
    private static final int MARGIN = 20;
    /** How wide the username draws, by its background's own width. */
    private static final int USERNAME = 150;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private Activity activity;
    private FrameLayout root;
    private ViewGroup bar;
    private LinearLayout area;
    private TextView name;
    private View username;

    @Before
    public void prepare() {
        resetAll();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Map<String, Integer> ids = new HashMap<>();
        ids.put(GlassChat.CHAT, ROOT_ID);
        ids.put(GlassChat.BACK, BACK_ID);
        ids.put(GlassChat.BUTTONS, BUTTONS_ID);
        ids.put(CenterChatName.NAME_AREA, AREA_ID);
        GlassChat.idsForTests = ids;
        CenterChatName.unfollow();
        Settings.CENTER_CHAT_NAME.save(true);
    }

    @After
    public void restore() {
        CenterChatName.unfollow();
        GlassChat.idsForTests = null;
        resetAll();
    }

    private static void resetAll() {
        Settings.CENTER_CHAT_NAME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /**
     * Instagram's chat header row: back, the picture, then the area holding the name over the username, weighted
     * to fill what's left with a margin each side, then the call buttons.
     */
    private void header(int buttonCount, int nameWidth) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(back(), new LinearLayout.LayoutParams(100, 100));
        row.addView(new ImageView(activity), new LinearLayout.LayoutParams(100, 100));
        LinearLayout.LayoutParams weighted = new LinearLayout.LayoutParams(0, 100, 1f);
        weighted.leftMargin = MARGIN;
        weighted.rightMargin = MARGIN;
        row.addView(nameArea(nameWidth), weighted);
        row.addView(buttons(buttonCount), new LinearLayout.LayoutParams(WRAP, 100));
        show(row);
    }

    private ImageView back() {
        ImageView back = new ImageView(activity);
        back.setId(BACK_ID);
        return back;
    }

    private LinearLayout buttons(int count) {
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setId(BUTTONS_ID);
        for (int i = 0; i < count; i++) buttons.addView(new ImageView(activity), new LinearLayout.LayoutParams(100, 100));
        return buttons;
    }

    /** The name as a TextView as wide as its text, and the username as a line whose background draws it. */
    private LinearLayout nameArea(int nameWidth) {
        area = new LinearLayout(activity);
        area.setId(AREA_ID);
        area.setOrientation(LinearLayout.VERTICAL);
        name = new TextView(activity);
        name.setPadding(0, 0, 0, 0);
        name.setText("Matt Parker");
        name.setMinWidth(nameWidth);
        name.setMaxWidth(nameWidth);
        area.addView(name, new LinearLayout.LayoutParams(WRAP, 60));
        username = new View(activity);
        GradientDrawable text = new GradientDrawable();
        text.setColor(Color.WHITE);
        text.setSize(USERNAME, 30);
        username.setBackground(text);
        area.addView(username, new LinearLayout.LayoutParams(MATCH, 30));
        return area;
    }

    private void show(ViewGroup header) {
        bar = header;
        root = new FrameLayout(activity);
        root.setId(ROOT_ID);
        root.addView(header, new FrameLayout.LayoutParams(MATCH, HEADER));
        activity.setContentView(root);
        layout();
    }

    private void layout() {
        ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDE, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(TALL, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDE, TALL);
    }

    /** Where the middle of [line] lands on the bar now, its drawn part when only part of it draws. */
    private float middleOnBar(View line, float drawnStart, float drawnEnd) {
        return area.getLeft() + area.getTranslationX() + line.getLeft() + line.getTranslationX()
                + (drawnStart + drawnEnd) / 2f;
    }

    private float nameMiddle() {
        return middleOnBar(name, 0, name.getWidth());
    }

    private float usernameMiddle() {
        return middleOnBar(username, 0, USERNAME);
    }

    @Test
    public void aShortNameAndItsUsernameSitInTheMiddleOfTheBar() {
        header(2, 200);

        CenterChatName.check(activity);

        assertEquals(WIDE / 2f, nameMiddle(), 1f);
        assertEquals(WIDE / 2f, usernameMiddle(), 1f);
        assertEquals("the area fills its room, so only its lines move", 0f, area.getTranslationX(), 0f);
    }

    @Test
    public void aNameTooLongForTheMiddleStopsShortOfTheButtons() {
        // Three call buttons leave less room on the right than the picture does on the left.
        header(3, 500);
        int roomEnd = bar.getChildAt(3).getLeft() - MARGIN;

        CenterChatName.check(activity);

        float nameEnd = area.getLeft() + area.getTranslationX() + name.getRight() + name.getTranslationX();
        assertEquals("as near the middle as the buttons allow", roomEnd, nameEnd, 1f);
        assertTrue("moved toward the middle", name.getTranslationX() > 0f);
        assertEquals("both lines share one middle", nameMiddle(), usernameMiddle(), 1f);
    }

    @Test
    public void aNameFillingItsRoomStaysPut() {
        header(3, 540);

        CenterChatName.check(activity);

        assertEquals(0f, name.getTranslationX(), 0f);
        assertEquals(0f, area.getTranslationX(), 0f);
    }

    @Test
    public void aHiddenLineIsLeftAloneAndDoesNotWidenTheName() {
        header(2, 200);
        View hidden = new View(activity);
        hidden.setVisibility(View.GONE);
        area.addView(hidden, new LinearLayout.LayoutParams(MATCH, 30));
        layout();

        CenterChatName.check(activity);

        assertEquals(0f, hidden.getTranslationX(), 0f);
        assertEquals(WIDE / 2f, nameMiddle(), 1f);
    }

    @Test
    public void anAreaNarrowerThanItsRoomMovesWithItsLines() {
        FrameLayout header = new FrameLayout(activity);
        header.addView(back(), new FrameLayout.LayoutParams(100, 100, Gravity.START));
        FrameLayout.LayoutParams after = new FrameLayout.LayoutParams(WRAP, 100, Gravity.START);
        after.leftMargin = 120;
        header.addView(nameArea(200), after);
        header.addView(buttons(2), new FrameLayout.LayoutParams(WRAP, 100, Gravity.END));
        show(header);

        CenterChatName.check(activity);

        assertTrue("the area itself moved", area.getTranslationX() > 0f);
        assertEquals(WIDE / 2f, nameMiddle(), 1f);
    }

    @Test
    public void aFullWidthNameCentersByItsText() {
        header(2, 200);
        area.removeView(name);
        name = new TextView(activity);
        name.setPadding(0, 0, 0, 0);
        name.setText("Matt Parker");
        area.addView(name, 0, new LinearLayout.LayoutParams(MATCH, 60));
        layout();

        CenterChatName.check(activity);

        Layout text = name.getLayout();
        assertTrue("the name has a laid out line", text != null && text.getLineRight(0) > text.getLineLeft(0));
        float middle = middleOnBar(name, name.getCompoundPaddingLeft() + text.getLineLeft(0),
                name.getCompoundPaddingLeft() + text.getLineRight(0));
        assertEquals(WIDE / 2f, middle, 1f);
    }

    @Test
    public void offPutsEveryLineBack() {
        header(3, 200);
        CenterChatName.check(activity);
        assertTrue(name.getTranslationX() != 0f);

        Settings.CENTER_CHAT_NAME.save(false);
        CenterChatName.check(activity);

        assertEquals(0f, name.getTranslationX(), 0f);
        assertEquals(0f, username.getTranslationX(), 0f);
        assertEquals(0f, area.getTranslationX(), 0f);
    }

    @Test
    public void startsOff() {
        Settings.CENTER_CHAT_NAME.resetToDefault();
        header(2, 200);

        CenterChatName.check(activity);

        assertEquals(0f, name.getTranslationX(), 0f);
    }

    /**
     * Like Instagram's username line: a background that draws its text at the start of the line and says nothing
     * of how wide that is.
     */
    private static final class StartInk extends Drawable {
        int inked;
        int draws;
        boolean throwsOnDraw;
        private final Paint paint = new Paint();

        StartInk(int inked) {
            this.inked = inked;
            paint.setColor(Color.BLACK);
        }

        @Override public void draw(Canvas canvas) {
            draws++;
            if (throwsOnDraw) throw new IllegalArgumentException("Software rendering doesn't support hardware bitmaps");
            Rect bounds = getBounds();
            canvas.drawRect(bounds.left, bounds.top, bounds.left + inked, bounds.bottom, paint);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Three call buttons, as in a chat, with the username drawn by [ink]. */
    private StartInk headerDrawnByItsBackground(StartInk ink) {
        header(3, 200);
        username.setBackground(ink);
        layout();
        return ink;
    }

    private float roomMiddle() {
        return (area.getLeft() + bar.getChildAt(3).getLeft() - MARGIN) / 2f;
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aUsernameDrawnWithoutAWidthCentersByItsInk() {
        StartInk ink = headerDrawnByItsBackground(new StartInk(USERNAME));
        Rect before = ink.copyBounds();
        assertTrue("the room is lopsided, so its middle isn't the bar's", Math.abs(roomMiddle() - WIDE / 2f) > 20f);

        CenterChatName.check(activity);

        assertEquals(WIDE / 2f, nameMiddle(), 1f);
        assertEquals(WIDE / 2f, usernameMiddle(), 1f);
        assertEquals("measured off screen at the line's size, then put back", before, ink.getBounds());
        assertSame("measuring doesn't take the view's place as the callback", username, ink.getCallback());
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aDrawnUsernameIsMeasuredAgainOnlyAfterAQuarterSecond() {
        StartInk ink = headerDrawnByItsBackground(new StartInk(USERNAME));
        int drawn = ink.draws;
        CenterChatName.check(activity);
        CenterChatName.check(activity);
        assertEquals("one off screen draw for frames close together", drawn + 1, ink.draws);

        // Instagram swaps the username for when they were last active.
        ink.inked = 300;
        ShadowLooper.idleMainLooper(CenterChatName.INK_EVERY_MS, TimeUnit.MILLISECONDS);
        layout();
        drawn = ink.draws;
        CenterChatName.check(activity);

        assertEquals(drawn + 1, ink.draws);
        assertEquals(WIDE / 2f, middleOnBar(username, 0, 300), 1f);
    }

    @Test
    public void aUsernameThatCannotBeDrawnCountsAsTheWholeLine() {
        StartInk ink = headerDrawnByItsBackground(new StartInk(USERNAME));
        ink.throwsOnDraw = true;
        try {
            CenterChatName.check(activity);
        } finally {
            ink.throwsOnDraw = false;
        }

        assertEquals("the area's lines center on the room, as for a line drawn across", roomMiddle(), nameMiddle(), 1f);
        assertSame(username, ink.getCallback());
    }

    @Test
    public void aChatOpeningJustAfterASearchIsCenteredWithoutAnotherFrame() {
        header(2, 200);
        area.setVisibility(View.GONE);
        layout();
        CenterChatName.follow(activity);
        CenterChatName.check(activity);

        area.setVisibility(View.VISIBLE);
        layout();
        CenterChatName.check(activity);
        assertEquals("searched too recently to look yet", 0f, name.getTranslationX(), 0f);

        ShadowLooper.idleMainLooper(CenterChatName.SEARCH_EVERY_MS, TimeUnit.MILLISECONDS);

        assertEquals(WIDE / 2f, nameMiddle(), 1f);
    }
}

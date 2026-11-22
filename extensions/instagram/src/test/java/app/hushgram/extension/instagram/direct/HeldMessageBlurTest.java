/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Blur the chat behind a held message (#684): the chat blurs while a held message's reactions and actions show,
 * the held row, the reactions and the actions stay sharp, and the blur fades in and out again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HeldMessageBlurTest {
    private static final int WIDE = 720;
    private static final int TALL = 1600;
    private static final int ROW = 130;
    private static final int FRAME_ID = 0x7f000011;
    private static final int ACTIONS_ID = 0x7f000012;
    private static final int TAG_ID = 0x7f000013;
    private static final int LIST_ID = 0x7f000014;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private Activity activity;
    private FrameLayout root;
    private View background;
    private View header;
    private FrameLayout content;
    private FrameLayout list;
    private View composer;
    private final View[] rows = new View[3];
    private FrameLayout frame;
    private FrameLayout actions;

    /** The view model Instagram hands its message actions screen, with the held row's Rect among its fields. */
    public static final class Model implements Parcelable {
        public int kind = 3;
        public Rect where;

        Model(Rect where) {
            this.where = where;
        }

        @Override public int describeContents() {
            return 0;
        }

        @Override public void writeToParcel(Parcel out, int flags) {
        }
    }

    /** The message actions screen: its arguments hold the view model. */
    public static final class Screen {
        private final Bundle arguments = new Bundle();

        Screen(Model model) {
            arguments.putParcelable(HeldMessageBlur.VIEW_MODEL, model);
        }

        public Bundle getArguments() {
            return arguments;
        }
    }

    @Before
    public void prepare() {
        resetAll();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        root = new FrameLayout(activity);
        background = new View(activity);
        header = new View(activity);
        content = new FrameLayout(activity);
        list = new FrameLayout(activity);
        list.setId(LIST_ID);
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new View(activity);
            FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(MATCH, ROW);
            at.topMargin = 400 + i * (ROW + 20);
            list.addView(rows[i], at);
        }
        composer = new View(activity);
        content.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(MATCH, 120);
        bottom.topMargin = TALL - 120;
        content.addView(composer, bottom);
        frame = new FrameLayout(activity);
        frame.setId(FRAME_ID);
        root.addView(background, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(header, new FrameLayout.LayoutParams(MATCH, 112));
        root.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(frame, new FrameLayout.LayoutParams(MATCH, MATCH));
        activity.setContentView(root);
        layout();
        Map<String, Integer> ids = new HashMap<>();
        ids.put(HeldMessageBlur.ACTIONS_FRAME, FRAME_ID);
        ids.put(HeldMessageBlur.ACTIONS, ACTIONS_ID);
        ids.put(HeldMessageBlur.FRAGMENT_TAG, TAG_ID);
        ids.put(GlassChat.MESSAGE_LIST, LIST_ID);
        GlassChat.idsForTests = ids;
        HeldMessageBlur.forget();
    }

    @After
    public void restore() {
        HeldMessageBlur.forget();
        GlassChat.idsForTests = null;
        resetAll();
    }

    private static void resetAll() {
        Settings.HELD_MESSAGE_BLUR.resetToDefault();
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

    /** Instagram holding the message in [row]: the actions screen shows in the frame, its view model pointing at the row. */
    private FrameLayout hold(View row) {
        int[] at = new int[2];
        row.getLocationOnScreen(at);
        return holdAt(new Rect(at[0], at[1], at[0] + row.getWidth(), at[1] + row.getHeight()));
    }

    private FrameLayout holdAt(Rect where) {
        frame.removeAllViews();
        actions = new FrameLayout(activity);
        actions.setId(ACTIONS_ID);
        actions.setTag(TAG_ID, new Screen(new Model(where)));
        actions.addView(new View(activity), new FrameLayout.LayoutParams(MATCH, 158));
        frame.addView(actions, new FrameLayout.LayoutParams(MATCH, MATCH));
        layout();
        return actions;
    }

    private static boolean blurred(View view) {
        return HeldMessageBlur.blurredForTests(view);
    }

    @Test
    public void theSwitchStartsOff() {
        assertEquals(Boolean.FALSE, Settings.HELD_MESSAGE_BLUR.defaultValue);
        hold(rows[1]);
        HeldMessageBlur.check(activity);
        assertFalse(blurred(background));
        assertFalse(blurred(rows[0]));
    }

    /** Everything else in the chat blurs, and the held row, the list holding it and the actions' frame stay sharp. */
    @Test
    @Config(sdk = 37)
    public void holdingAMessageBlursTheRestOfTheChat() {
        Settings.HELD_MESSAGE_BLUR.save(true);
        hold(rows[1]);
        HeldMessageBlur.step(activity, 1_000L);

        for (View each : new View[] {background, header, composer, rows[0], rows[2]}) assertTrue(blurred(each));
        for (View each : new View[] {rows[1], list, content, frame, actions}) assertFalse(blurred(each));
        assertEquals("it starts clear and fades in", 0f, HeldMessageBlur.strengthForTests(rows[0]), 0f);

        HeldMessageBlur.step(activity, 1_000L + HeldMessageBlur.FADE_MS / 2);
        float half = HeldMessageBlur.strengthForTests(rows[0]);
        assertTrue("part way in: " + half, half > 0.5f && half < 1f);
        assertEquals(half, HeldMessageBlur.strengthForTests(header), 0f);

        HeldMessageBlur.step(activity, 1_000L + HeldMessageBlur.FADE_MS);
        assertEquals(1f, HeldMessageBlur.strengthForTests(background), 0f);
        assertEquals(1f, HeldMessageBlur.strengthForTests(rows[2]), 0f);
        assertNotNull(HeldMessageBlur.effect(rows[2], 1f));
        assertNull("no effect at all once faded out", HeldMessageBlur.effect(rows[2], 0f));
    }

    /** Let go: the blur fades out, then every effect comes off, and holding again, even in the same view, blurs again. */
    @Test
    @Config(sdk = 37)
    public void lettingGoFadesTheBlurOutAndTheNextHoldBlursAgain() {
        Settings.HELD_MESSAGE_BLUR.save(true);
        hold(rows[1]);
        HeldMessageBlur.step(activity, 1_000L);
        HeldMessageBlur.step(activity, 2_000L);
        assertEquals(1f, HeldMessageBlur.strengthForTests(rows[0]), 0f);

        actions.setVisibility(View.GONE);
        HeldMessageBlur.step(activity, 3_000L);
        assertEquals("the fade out starts from where it was", 1f, HeldMessageBlur.strengthForTests(rows[0]), 0f);
        HeldMessageBlur.step(activity, 3_000L + HeldMessageBlur.FADE_MS / 2);
        float half = HeldMessageBlur.strengthForTests(rows[0]);
        assertTrue("part way out: " + half, half > 0f && half < 0.5f);
        HeldMessageBlur.step(activity, 3_000L + HeldMessageBlur.FADE_MS);
        for (View each : new View[] {background, header, composer, rows[0], rows[2]}) assertFalse(blurred(each));

        actions.setVisibility(View.VISIBLE);
        HeldMessageBlur.step(activity, 4_000L);
        assertTrue(blurred(rows[0]));
        assertFalse(blurred(rows[1]));
    }

    /** Another message held while the chat is blurred: its row comes out of the blur and the last one goes in. */
    @Test
    @Config(sdk = 37)
    public void anotherHeldMessageComesOutOfTheBlur() {
        Settings.HELD_MESSAGE_BLUR.save(true);
        hold(rows[1]);
        HeldMessageBlur.step(activity, 1_000L);
        HeldMessageBlur.step(activity, 2_000L);
        assertTrue(blurred(rows[0]));

        hold(rows[0]);
        HeldMessageBlur.step(activity, 2_016L);
        assertFalse(blurred(rows[0]));
        assertTrue(blurred(rows[1]));
        assertEquals("no flash to clear and back", 1f, HeldMessageBlur.strengthForTests(rows[1]), 0f);
    }

    /** No row under the Rect, or no Rect at all: nothing blurs, rather than blurring the held message too. */
    @Test
    @Config(sdk = 37)
    public void nothingBlursWithoutTheHeldRow() {
        Settings.HELD_MESSAGE_BLUR.save(true);
        holdAt(new Rect(0, 5, WIDE, 60));
        HeldMessageBlur.step(activity, 1_000L);
        assertFalse(blurred(background));
        assertFalse(blurred(rows[0]));

        holdAt(new Rect());
        HeldMessageBlur.step(activity, 2_000L);
        assertFalse(blurred(header));
    }

    /** The row covering most of the Rect is the held one, and the Rect is read from the first Rect field set. */
    @Test
    @Config(sdk = 37)
    public void theHeldRowIsTheOneCoveringMostOfTheRect() {
        int[] at = new int[2];
        rows[2].getLocationOnScreen(at);
        Rect mostlyLast = new Rect(0, at[1] - 30, WIDE, at[1] + ROW - 10);
        assertEquals(rows[2], HeldMessageBlur.rowOver(list, mostlyLast));
        assertNull(HeldMessageBlur.rowOver(list, new Rect(0, 0, WIDE, 50)));
        assertEquals(mostlyLast, HeldMessageBlur.rectIn(new Model(mostlyLast)));
        assertNull(HeldMessageBlur.rectIn(new Model(null)));
        assertNull(HeldMessageBlur.rectIn("not a view model"));
    }

    /** Turned off while the chat is blurred: every effect comes off on the next frame. */
    @Test
    @Config(sdk = 37)
    public void turningItOffTakesTheBlurOffAtOnce() {
        Settings.HELD_MESSAGE_BLUR.save(true);
        hold(rows[1]);
        HeldMessageBlur.check(activity);
        assertTrue(blurred(rows[0]));

        Settings.HELD_MESSAGE_BLUR.save(false);
        HeldMessageBlur.check(activity);
        assertFalse(blurred(rows[0]));
        assertFalse(blurred(background));
    }

    /** Before Android 12 there's no blur to give, so nothing is touched. */
    @Test
    @Config(sdk = 28)
    public void beforeAndroid12NothingIsTouched() {
        assertTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.S);
        Settings.HELD_MESSAGE_BLUR.save(true);
        hold(rows[1]);
        HeldMessageBlur.check(activity);
        assertFalse(blurred(rows[0]));
        assertFalse(blurred(background));
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.widget.Toast;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Android keeps five toasts of an app waiting and drops the rest unseen, and a burst of saves
 * lost some of its "Saved" toasts that way (#792). These pin what Feedback leaves with Android.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FeedbackTest {

    private Application app;
    /** Each toast handed to Android so far, and its text, in order. */
    private final List<Toast> toasts = new ArrayList<>();
    private final List<String> texts = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
    }

    /**
     * Six saves tapped in a row, each saying it started and then how it ended. Android is never
     * left more than four, the starts make way for results, and every result is shown.
     */
    @Test
    public void aBurstOfSavesLosesNoResult() {
        for (int i = 1; i <= 6; i++) {
            Feedback.note(app, "Saving from @a" + i + "...", false);
            step(Duration.ZERO);
            Feedback.show(app, "Saved " + i, false);
            step(Duration.ZERO);
        }
        assertTrue(texts.toString(), texts.contains("Saved 4"));
        assertFalse("a result was shown before there was room", texts.contains("Saved 5"));

        for (int i = 0; i < 300; i++) step(Duration.ofMillis(100));

        for (int i = 1; i <= 6; i++) {
            int at = texts.indexOf("Saved " + i);
            assertTrue("Saved " + i + " was lost: " + texts, at >= 0);
            assertFalse("Saved " + i + " was cut short", Shadows.shadowOf(toasts.get(at)).isCancelled());
        }
        // The fifth and sixth starts would only have come after results already waiting.
        assertFalse(texts.contains("Saving from @a5..."));
        assertFalse(texts.contains("Saving from @a6..."));
        assertTrue("the first start wasn't cut short to make room", Shadows.shadowOf(toasts.get(0)).isCancelled());
        assertEquals("Saved 6", texts.get(texts.size() - 1));
    }

    /** A result that waits goes to Android once the first toast is gone, and not before. */
    @Test
    public void aWaitingResultGoesOnceThereIsRoom() {
        for (int i = 1; i <= 5; i++) Feedback.show(app, "Result " + i, false);
        idle();
        assertEquals(4, ShadowToast.shownToastCount());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000));
        assertEquals("shown while the first was still up", 4, ShadowToast.shownToastCount());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(5, ShadowToast.shownToastCount());
        assertEquals("Result 5", ShadowToast.getTextOfLatestToast());
    }

    /**
     * The come back owed to another app's waiting toasts went with that app's main thread queue,
     * as a test's does when the next one starts, and this app's waiting result still shows.
     */
    @Test
    public void anotherAppsLostComeBackDoesNotHoldBackThisOnesResults() {
        Context other = new ContextWrapper(app) {
            @Override
            public Context getApplicationContext() {
                return this;
            }
        };
        for (int i = 1; i <= 5; i++) Feedback.show(other, "Old " + i, false);
        idle();
        assertEquals(4, ShadowToast.shownToastCount());
        Shadows.shadowOf(Looper.getMainLooper().getQueue()).reset();

        for (int i = 1; i <= 5; i++) Feedback.show(app, "Result " + i, false);
        idle();
        assertEquals(8, ShadowToast.shownToastCount());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_400));
        assertEquals("the fifth result never came", 9, ShadowToast.shownToastCount());
        assertEquals("Result 5", ShadowToast.getTextOfLatestToast());
    }

    /** A long message's lines are each a toast, in order. */
    @Test
    public void eachLineIsAToastOfItsOwn() {
        Feedback.show(app, "One\nTwo\n\nThree", true);
        idle();
        assertEquals(3, ShadowToast.shownToastCount());
        assertEquals("Three", ShadowToast.getTextOfLatestToast());
    }

    /**
     * Run the main thread [by] on and note the toast it showed, if any, with what Android was
     * left: never more than four, and none had time to go while the clock stood still.
     */
    private void step(Duration by) {
        int before = ShadowToast.shownToastCount();
        if (by.isZero()) idle();
        else Shadows.shadowOf(Looper.getMainLooper()).idleFor(by);
        int now = ShadowToast.shownToastCount();
        assertTrue("two toasts in one step, so their texts can't be told apart", now - before <= 1);
        if (now > before) {
            toasts.add(ShadowToast.getLatestToast());
            texts.add(ShadowToast.getTextOfLatestToast());
        }
        if (by.isZero()) {
            int waiting = 0;
            for (Toast toast : toasts) if (!Shadows.shadowOf(toast).isCancelled()) waiting++;
            assertTrue("more than four left with Android: " + texts, waiting <= Feedback.MOST_WAITING);
        }
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}

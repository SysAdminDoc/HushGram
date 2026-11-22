/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** Copy text in the story menu (#789): the words it picks out of Instagram's description, and Lens when there are none. */
@RunWith(RobolectricTestRunner.class)
public class StoryCopyTextTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String LEFT_SINGLE = String.valueOf((char) 0x2018);
    private static final String RIGHT_SINGLE = String.valueOf((char) 0x2019);
    private static final String LEFT_DOUBLE = String.valueOf((char) 0x201C);
    private static final String RIGHT_DOUBLE = String.valueOf((char) 0x201D);
    private static final String LOW_DOUBLE = String.valueOf((char) 0x201E);
    private static final String LEFT_GUILLEMET = String.valueOf((char) 0x00AB);
    private static final String RIGHT_GUILLEMET = String.valueOf((char) 0x00BB);
    private static final String LEFT_CORNER = String.valueOf((char) 0x300C);
    private static final String RIGHT_CORNER = String.valueOf((char) 0x300D);

    @After
    public void tearDown() {
        StoryCopyText.resetForTests();
        HookStatus.clear();
        Utils.setActivity(null);
    }

    /** The words are what the description quotes, whichever quotes its language uses, apostrophes and all. */
    @Test
    public void theWordsAreWhatTheDescriptionQuotes() {
        assertEquals("Doors open at 7", StoryCopyText.words(
                "Photo by Sam on November 20, 2026. May be an image of text that says 'Doors open at 7'."));
        assertEquals("I'm back, don't miss it", StoryCopyText.words(
                "May be an image of 1 person, smiling and text that says 'I'm back, don't miss it'"));
        assertEquals("SALE 50% OFF", StoryCopyText.words(
                "Photo by Bob's Shop on November 20, 2026. May be a graphic of text that says \"SALE 50% OFF\"."));
        assertEquals("Hello there", StoryCopyText.words(
                "May be an image of text that says " + LEFT_DOUBLE + "Hello there" + RIGHT_DOUBLE));
        assertEquals("Open late", StoryCopyText.words(
                "May be an image of text that says " + LEFT_SINGLE + "Open late" + RIGHT_SINGLE + "."));
        assertEquals("Heute geschlossen", StoryCopyText.words(
                "Kann ein Bild von Text sein, auf dem " + LOW_DOUBLE + "Heute geschlossen" + LEFT_DOUBLE + " steht"));
        assertEquals("Bonjour", StoryCopyText.words(
                "Peut " + "etre une image de texte qui dit " + LEFT_GUILLEMET + " Bonjour " + RIGHT_GUILLEMET));
        assertEquals("ABC", StoryCopyText.words("text " + LEFT_CORNER + "ABC" + RIGHT_CORNER));
        assertEquals("two lines of words", StoryCopyText.words("text that says '  two lines of words '"));
    }

    /** A description that quotes nothing, or quotes nothing but space, has no words, and neither does none at all. */
    @Test
    public void aDescriptionThatQuotesNothingHasNoWords() {
        assertNull(StoryCopyText.words(null));
        assertNull(StoryCopyText.words(""));
        assertNull(StoryCopyText.words("Photo by Bob's Burgers on November 20, 2026. May be an image of 2 people."));
        assertNull(StoryCopyText.words("May be an image of text that says ' '"));
        assertNull(StoryCopyText.words("An unclosed 'quote"));
    }

    /**
     * A story whose description quotes no words, here an unpatched build's, goes to Lens once the
     * menu has had time to close, for the activity the tap came from, and the clipboard is left alone.
     */
    @Test
    public void withoutWordsLensOpensOnTheStoryOnceTheMenuCloses() {
        List<Activity> sent = new ArrayList<>();
        List<Long> waited = new ArrayList<>();
        StoryCopyText.lens = sent::add;
        StoryCopyText.later = (task, delay) -> {
            waited.add(delay);
            task.run();
        };
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        try {
            Utils.setActivity(controller.get());

            StoryCopyText.copy(new Object());

            assertEquals(Collections.singletonList(controller.get()), sent);
            assertEquals(Collections.singletonList(StoryCopyText.LENS_DELAY_MS), waited);
            ClipboardManager clipboard = (ClipboardManager) RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE);
            assertTrue("the clipboard was written", !clipboard.hasPrimaryClip());
            String report = String.valueOf(HookStatus.report());
            assertTrue(report, report.contains(StoryCopyText.COUNT_TO_LENS + " 1"));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    /** A Lens that throws is counted, and nothing reaches Instagram's tap. */
    @Test
    public void aLensThatThrowsStaysInside() {
        StoryCopyText.lens = activity -> {
            throw new IllegalStateException("no Lens");
        };
        StoryCopyText.later = (task, delay) -> task.run();

        StoryCopyText.copy(null);

        String report = String.valueOf(HookStatus.report());
        assertTrue(report, report.contains("'" + StoryCopyText.HOOK + "' hook (it threw java.lang.IllegalStateException)"));
    }
}

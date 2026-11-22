/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide the line under names (#771): a feed post header keeps only Sponsored and Paid partnership. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HeaderLineTest {
    /** Label kinds named the way Instagram's enum names them on 456. */
    enum Kind { LOCATION, CLIPS_AUDIO_ATTRIBUTION, MUSIC_ATTRIBUTION, SPONSORED, ORGANIC_BRANDED_CONTENT, MEDIA_TOPIC, BASEL_ATTRIBUTION }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_FEED_HEADER_LINE.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.KEEP_FEED_HEADER_PLACE.resetToDefault();
        Settings.HIDE_FEED_HEADER_LINE.resetToDefault();
        Settings.HIDE_REEL_HEADER_LINE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void startsOff() {
        Settings.HIDE_FEED_HEADER_LINE.resetToDefault();
        List<Kind> kinds = Arrays.asList(Kind.MUSIC_ATTRIBUTION, Kind.LOCATION);
        assertSame(kinds, HeaderLine.kinds(kinds));
    }

    /** The song and the place both go, so the post gets the name-only header and nothing slides. */
    @Test
    public void theSongAndThePlaceLeaveSoNothingSlides() {
        List<Kind> kinds = Arrays.asList(Kind.CLIPS_AUDIO_ATTRIBUTION, Kind.LOCATION, Kind.MEDIA_TOPIC, Kind.BASEL_ATTRIBUTION);

        List<?> kept = HeaderLine.kinds(kinds);

        assertEquals(Collections.emptyList(), kept);
        assertEquals("the list handed in is left alone", Kind.LOCATION, kinds.get(1));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HeaderLine.COUNT_HIDDEN));
        assertTrue(HookStatus.missing(FamilyNames.FEED_HEADER_LINE).toString(),
                HookStatus.missing(FamilyNames.FEED_HEADER_LINE).isEmpty());
    }

    /**
     * #771: Keep the place leaves the place on the line on its own, so it stays put instead of taking
     * turns with the song. An ad keeps its Sponsored beside it, and with the line shown, nothing changes.
     */
    @Test
    public void keepThePlaceLeavesItOnItsOwn() {
        Settings.KEEP_FEED_HEADER_PLACE.save(true);

        assertEquals(Collections.singletonList(Kind.LOCATION),
                HeaderLine.kinds(Arrays.asList(Kind.CLIPS_AUDIO_ATTRIBUTION, Kind.LOCATION, Kind.MEDIA_TOPIC)));
        assertEquals(Arrays.asList(Kind.SPONSORED, Kind.LOCATION),
                HeaderLine.kinds(Arrays.asList(Kind.SPONSORED, Kind.MUSIC_ATTRIBUTION, Kind.LOCATION)));
        assertEquals("a post with no place gets the name alone", Collections.emptyList(),
                HeaderLine.kinds(Arrays.asList(Kind.MUSIC_ATTRIBUTION, Kind.BASEL_ATTRIBUTION)));

        Settings.HIDE_FEED_HEADER_LINE.save(false);
        List<Kind> kinds = Arrays.asList(Kind.MUSIC_ATTRIBUTION, Kind.LOCATION);
        assertSame("on its own it does nothing", kinds, HeaderLine.kinds(kinds));
    }

    /** An ad keeps Sponsored and a paid partnership its label, in their order, and the rest goes. */
    @Test
    public void disclosuresStay() {
        List<Kind> kinds = Arrays.asList(Kind.SPONSORED, Kind.MUSIC_ATTRIBUTION, Kind.ORGANIC_BRANDED_CONTENT, Kind.LOCATION);

        List<?> kept = HeaderLine.kinds(kinds);

        assertEquals(Arrays.asList(Kind.SPONSORED, Kind.ORGANIC_BRANDED_CONTENT), kept);
        assertNotSame(kinds, kept);
    }

    /** A list of disclosures only, or of things that aren't Instagram's kinds, comes back as the same list. */
    @Test
    public void nothingToTakeIsTheSameList() {
        List<Kind> ad = Collections.singletonList(Kind.SPONSORED);
        assertSame(ad, HeaderLine.kinds(ad));
        List<Object> unknown = Arrays.asList("not a kind", 7);
        assertSame(unknown, HeaderLine.kinds(unknown));
    }

    /** Off, paused or before the settings are read, every label stays. */
    @Test
    public void offPausedAndUnreadyKeepEveryLabel() {
        List<Kind> kinds = Arrays.asList(Kind.MUSIC_ATTRIBUTION, Kind.LOCATION);

        Settings.HIDE_FEED_HEADER_LINE.save(false);
        assertSame(kinds, HeaderLine.kinds(kinds));
        Settings.HIDE_FEED_HEADER_LINE.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(kinds, HeaderLine.kinds(kinds));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(kinds, HeaderLine.kinds(kinds)));
        assertEquals(Collections.emptyList(), HeaderLine.kinds(kinds));
    }

    /** A switch that throws keeps the labels and says which hook threw. */
    @Test
    public void aThrowingSwitchKeepsTheLabelsAndIsReported() {
        List<Kind> kinds = Arrays.asList(Kind.MUSIC_ATTRIBUTION, Kind.LOCATION);

        assertSame(kinds, HeaderLine.kinds(kinds, () -> {
            throw new IllegalStateException("broken switch");
        }));

        String missing = HookStatus.missing(FamilyNames.FEED_HEADER_LINE).toString();
        assertTrue(missing, missing.contains(HeaderLine.HOOK));
    }

    @Test
    public void nullAndEmptyStayAsTheyCame() {
        assertNull(HeaderLine.kinds(null));
        List<Kind> none = Collections.emptyList();
        assertSame(none, HeaderLine.kinds(none));
    }

    /** A reel line's kinds, named the way Instagram 456 names them. */
    enum ReelKind { MUSIC, SUGGESTED_IN_TOPIC, AIGM_ACCOUNT_LABEL, SPONSORED_LABEL, PAID_PARTNERSHIP }

    /** Stand-ins for the reel line's shapes on 456: an interface, an empty object, one label, a sliding line. */
    interface ReelLine { }

    static final class NoLine implements ReelLine { }

    static final class Song implements ReelLine {
        final Object state = "song state";
    }

    static final class Partnership implements ReelLine {
        final Object state = "paid partnership state";
    }

    static final class Turn {
        final ReelKind kind;
        final Object line;

        Turn(ReelKind kind, Object line) {
            this.kind = kind;
            this.line = line;
        }
    }

    static final class Sliding implements ReelLine {
        static final Object SHARED = "a static field isn't read";
        final Object state = "cyclic state";
        final List<Turn> turns;

        Sliding(Turn... turns) {
            this.turns = Arrays.asList(turns);
        }
    }

    private final NoLine noLine = new NoLine();

    private void reelOn() {
        Settings.HIDE_REEL_HEADER_LINE.save(true);
    }

    @Test
    public void theReelSwitchStartsOffAndIsItsOwn() {
        Settings.HIDE_REEL_HEADER_LINE.resetToDefault();
        Song song = new Song();
        assertSame("the feed switch doesn't touch reels", song, HeaderLine.reelLine(song, noLine));
        Settings.HIDE_FEED_HEADER_LINE.save(false);
        reelOn();
        assertSame("and the reel switch works alone", noLine, HeaderLine.reelLine(song, noLine));
    }

    /** A reel's song line becomes Instagram's empty line, so the name sits alone. */
    @Test
    public void aReelsSongBecomesTheEmptyLine() {
        reelOn();
        assertSame(noLine, HeaderLine.reelLine(new Song(), noLine));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HeaderLine.COUNT_REEL_HIDDEN));
        assertTrue(HookStatus.missing(FamilyNames.FEED_HEADER_LINE).toString(),
                HookStatus.missing(FamilyNames.FEED_HEADER_LINE).isEmpty());
    }

    /** The song and "Suggested for you" taking turns stop sliding, with nothing left. */
    @Test
    public void aSlidingLineWithoutADisclosureBecomesTheEmptyLine() {
        reelOn();
        Sliding sliding = new Sliding(new Turn(ReelKind.MUSIC, new Song()), new Turn(ReelKind.SUGGESTED_IN_TOPIC, new Song()),
                new Turn(ReelKind.AIGM_ACCOUNT_LABEL, new Song()));
        assertSame(noLine, HeaderLine.reelLine(sliding, noLine));
    }

    /** A paid partnership's turn is all that stays of a sliding line, as a line of its own. */
    @Test
    public void aSlidingLineKeepsItsPaidPartnershipTurn() {
        reelOn();
        Partnership partnership = new Partnership();
        Sliding sliding = new Sliding(new Turn(ReelKind.MUSIC, new Song()), new Turn(ReelKind.PAID_PARTNERSHIP, partnership));

        assertSame(partnership, HeaderLine.reelLine(sliding, noLine));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HeaderLine.COUNT_REEL_KEPT));
    }

    /** A disclosure turn showing something that isn't a reel line can't stand in for one, so the line goes. */
    @Test
    public void aDisclosureTurnThatIsntALineIsntKept() {
        reelOn();
        Sliding sliding = new Sliding(null, new Turn(ReelKind.SPONSORED_LABEL, "not a line"), new Turn(ReelKind.MUSIC, new Song()));
        assertSame(noLine, HeaderLine.reelLine(sliding, noLine));
        assertTrue(HeaderLine.sameKind(new Song(), noLine));
        assertTrue(!HeaderLine.sameKind("text", noLine) && !HeaderLine.sameKind(null, noLine)
                && !HeaderLine.sameKind(new Song(), new Object()));
    }

    @Test
    public void theEmptyLineAndNullStayAsTheyCame() {
        reelOn();
        assertSame(noLine, HeaderLine.reelLine(noLine, noLine));
        assertNull(HeaderLine.reelLine(null, noLine));
        Song song = new Song();
        assertSame("with no empty line to give, the line stays", song, HeaderLine.reelLine(song, null));
    }

    /** Off, paused or before the settings are read, a reel keeps its line. */
    @Test
    public void offPausedAndUnreadyKeepTheReelLine() {
        Song song = new Song();
        Settings.HIDE_REEL_HEADER_LINE.save(false);
        assertSame(song, HeaderLine.reelLine(song, noLine));
        reelOn();

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(song, HeaderLine.reelLine(song, noLine));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(song, HeaderLine.reelLine(song, noLine)));
        assertSame(noLine, HeaderLine.reelLine(song, noLine));
    }

    /** A switch that throws keeps the reel's line and says which hook threw. */
    @Test
    public void aThrowingReelSwitchKeepsTheLineAndIsReported() {
        Song song = new Song();
        assertSame(song, HeaderLine.reelLine(song, noLine, () -> {
            throw new IllegalStateException("broken switch");
        }));
        String missing = HookStatus.missing(FamilyNames.FEED_HEADER_LINE).toString();
        assertTrue(missing, missing.contains(HeaderLine.REEL_HOOK));
    }
}

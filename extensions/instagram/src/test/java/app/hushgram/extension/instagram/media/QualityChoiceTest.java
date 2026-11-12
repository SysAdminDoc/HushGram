/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * Default playback quality as Instagram's DASH format evaluator asks it: once, the first time the
 * evaluator chooses a track, right after it keeps the tracks it chooses among. The hook hands the
 * evaluator's own custom-quality setter the label the chosen quality plays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class QualityChoiceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The rungs of a reel's manifest as Meta labels them (meta-public-dash.mpd). */
    private static final String[] REEL = {"240p", "270p", "360p", "480p", "540p", "640p", "720p", "1080p"};

    /** Instagram's evaluator as far as the hook sees it: its tracks and the track it keeps to. */
    private static final class Evaluator {
        String customTrack;
        final Format[] formats;

        Evaluator(String... labels) {
            formats = new Format[labels.length];
            for (int i = 0; i < labels.length; i++) formats[i] = new Format("track-" + i, labels[i]);
        }

        /** What the custom-quality setter does: keeps the id of the track with [label], or none. */
        void setCustomQuality(String label) {
            customTrack = null;
            if (label == null) return;
            for (Format format : formats) {
                if (label.equals(format.label)) {
                    customTrack = format.id;
                    return;
                }
            }
        }

        /** The label of the track the evaluator keeps to, or null while it chooses by bandwidth. */
        String playing() {
            for (Format format : formats) {
                if (format.id.equals(customTrack)) return format.label;
            }
            return null;
        }
    }

    private static final class Format {
        final String id;
        final String label;

        Format(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** Reads and sets the fakes above the way the patch's stubs do Instagram's evaluator and tracks. */
    private static class FakeAccess implements QualityChoice.Evaluator {
        RuntimeException failure;
        boolean setterKeepsNothing;

        @Override
        public String customTrack(Object evaluator) {
            if (failure != null) throw failure;
            return ((Evaluator) evaluator).customTrack;
        }

        @Override
        public Object[] formats(Object evaluator) {
            if (failure != null) throw failure;
            return ((Evaluator) evaluator).formats;
        }

        @Override
        public String label(Object format) {
            return ((Format) format).label;
        }

        @Override
        public void setCustomQuality(Object evaluator, String label) {
            ((Evaluator) evaluator).setCustomQuality(setterKeepsNothing ? "no such label" : label);
        }
    }

    private FakeAccess access;

    @Before
    public void inBuild() {
        access = new FakeAccess();
        QualityChoice.access = access;
        QualityChoice.inBuildForTests = Boolean.TRUE;
        HookStatus.clear();
    }

    @After
    public void restore() {
        QualityChoice.access = QualityChoice.PATCHED;
        QualityChoice.inBuildForTests = null;
        QualityChoice.qualityForTests = null;
        QualityChoice.forget();
        PauseForTests.resume();
        Settings.DEFAULT_PLAYBACK_QUALITY.resetToDefault();
        Settings.PLAYBACK_QUALITY.resetToDefault();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PLAYBACK_QUALITY + ":")) return line;
        }
        return null;
    }

    /** What a new video plays: its evaluator's first choice, then the label of the track it keeps to. Null is Instagram's own choice. */
    private static String firstChoice(Evaluator evaluator) {
        QualityChoice.firstChoice(evaluator);
        return evaluator.playing();
    }

    /** The same, for a video that starts [atMs] into the clock {@link #choice} reads. */
    private static String firstChoice(Evaluator evaluator, long atMs) {
        QualityChoice.starting(evaluator, atMs);
        return evaluator.playing();
    }

    @Test
    public void eachChoicePicksItsRung() {
        assertNull(PlaybackQuality.AUTO.pick(Arrays.asList(REEL)));
        assertEquals("240p", PlaybackQuality.DATA_SAVER.pick(Arrays.asList(REEL)));
        assertEquals("480p", PlaybackQuality.P480.pick(Arrays.asList(REEL)));
        assertEquals("720p", PlaybackQuality.P720.pick(Arrays.asList(REEL)));
        assertEquals("1080p", PlaybackQuality.HIGHEST.pick(Arrays.asList(REEL)));

        // A ceiling takes the closest rung above when a video has nothing that low.
        assertEquals("720p", PlaybackQuality.P480.pick(Arrays.asList("1080p", "720p")));
        assertEquals("360p", PlaybackQuality.P720.pick(Arrays.asList("240p", "360p")));
        // Labels are Meta's, whatever order the tracks come in, and one it doesn't write as a
        // quality is skipped rather than guessed.
        assertEquals("406p", PlaybackQuality.P480.pick(Arrays.asList("HD", null, "", "1080p", "406p", "720P")));
        assertEquals("720P", PlaybackQuality.HIGHEST.pick(Arrays.asList("HD", "406p", "720P")));
        assertNull(PlaybackQuality.HIGHEST.pick(Arrays.asList(null, "HD", "p", "10000p")));
        assertNull(PlaybackQuality.DATA_SAVER.pick(Collections.emptyList()));
        assertNull(PlaybackQuality.DATA_SAVER.pick(null));

        for (PlaybackQuality quality : PlaybackQuality.values()) {
            assertSame(quality, PlaybackQuality.fromFile(quality.fileValue));
        }
        assertNull(PlaybackQuality.fromFile("P720"));
        assertNull(PlaybackQuality.fromFile(720));
    }

    /** Picked in Morphe Manager, the switch is on and the quality is Instagram's, so nothing changes. */
    @Test
    public void onItsDefaultsInstagramPicksTheQuality() {
        assertTrue("picking the patch is the choice to use it", Settings.DEFAULT_PLAYBACK_QUALITY.get());
        assertSame(PlaybackQuality.AUTO, Settings.PLAYBACK_QUALITY.get());
        Evaluator video = new Evaluator(REEL);
        assertNull(firstChoice(video));
        assertNull("the setter was handed a label", video.customTrack);
        assertEquals(FamilyNames.PLAYBACK_QUALITY + ": invoked 1, 0 found, 0 missing", statusLine());
    }

    /** The acceptance: a chosen quality is what a new video plays, where the video offers it. */
    @Test
    public void aNewVideoStartsAtTheChosenQuality() {
        String[][] expected = {
                {PlaybackQuality.DATA_SAVER.name(), "240p"},
                {PlaybackQuality.P480.name(), "480p"},
                {PlaybackQuality.P720.name(), "720p"},
                {PlaybackQuality.HIGHEST.name(), "1080p"},
        };
        for (String[] row : expected) {
            Settings.PLAYBACK_QUALITY.save(PlaybackQuality.valueOf(row[0]));
            assertEquals(row[0], row[1], firstChoice(new Evaluator(REEL)));
        }
        // A video whose lowest rung is above the ceiling plays that rung.
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.P480);
        assertEquals("720p", firstChoice(new Evaluator("1080p", "720p")));
        assertEquals(FamilyNames.PLAYBACK_QUALITY + ": invoked 5, 1 found, 0 missing. Counted: "
                + QualityChoice.APPLIED + " 5", statusLine());
    }

    /** A track group with no quality labels, such as a video's sound, is left to Instagram. */
    @Test
    public void tracksWithoutQualitiesAreLeftToInstagram() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.DATA_SAVER);
        assertNull(firstChoice(new Evaluator(null, null)));
        assertNull(firstChoice(new Evaluator()));
        Evaluator unread = new Evaluator(REEL);
        QualityChoice.access = new FakeAccess() {
            @Override
            public Object[] formats(Object evaluator) {
                return null;
            }
        };
        assertNull("an evaluator with no tracks yet", firstChoice(unread));
        assertEquals(FamilyNames.PLAYBACK_QUALITY + ": invoked 3, 0 found, 0 missing", statusLine());
    }

    /** An evaluator that already keeps to a track, whoever set it, keeps it. */
    @Test
    public void aTrackTheEvaluatorAlreadyKeepsToStays() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        Evaluator chosen = new Evaluator(REEL);
        chosen.setCustomQuality("540p");
        assertEquals("540p", firstChoice(chosen));

        Evaluator playing = new Evaluator(REEL);
        assertEquals("1080p", firstChoice(playing));
        // Instagram's own setter still changes it afterwards, as its debug overlay's pick does.
        playing.setCustomQuality("360p");
        assertEquals("360p", playing.playing());
    }

    @Test
    public void offPausedOrNotInTheBuildInstagramPicks() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.DATA_SAVER);
        Settings.DEFAULT_PLAYBACK_QUALITY.save(false);
        assertNull(firstChoice(new Evaluator(REEL)));
        Settings.DEFAULT_PLAYBACK_QUALITY.save(true);

        for (HushgramPause.Reason reason : new HushgramPause.Reason[] {
                HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertNull(reason.name(), firstChoice(new Evaluator(REEL)));
        }
        PauseForTests.resume();

        QualityChoice.inBuildForTests = Boolean.FALSE;
        assertNull(firstChoice(new Evaluator(REEL)));
        QualityChoice.inBuildForTests = Boolean.TRUE;
        assertEquals("240p", firstChoice(new Evaluator(REEL)));
    }

    /** A setter that keeps no track for the label read is reported, and the video plays as Instagram picks. */
    @Test
    public void aSetterThatKeepsNothingIsReported() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.P720);
        access.setterKeepsNothing = true;
        assertNull(firstChoice(new Evaluator(REEL)));
        assertFalse(HookStatus.missing(FamilyNames.PLAYBACK_QUALITY).isEmpty());
        assertFalse("counted a video the setter didn't keep to",
                statusLine().contains(QualityChoice.APPLIED));
    }

    @Test
    public void aFailureIsReportedAndInstagramPicks() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.P720);
        Evaluator video = new Evaluator(REEL);
        access.failure = new IllegalStateException("the stub failed");
        assertNull(firstChoice(video));
        assertNull(video.customTrack);
        assertTrue(HookStatus.missing(FamilyNames.PLAYBACK_QUALITY).get(0)
                .startsWith("a working 'first choice' hook (it threw "));
    }

    /** Until the patch fills the stubs in, no evaluator has tracks or a kept track, and every track has no label. */
    @Test
    public void theStubsAnswerNothing() {
        assertNull(QualityReader.customTrack(new Object()));
        assertNull(QualityReader.trackFormats(new Object()));
        assertNull(QualityReader.formatLabel(new Object()));
        QualityReader.setCustomQuality(new Object(), "720p");
        QualityChoice.access = QualityChoice.PATCHED;
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.P720);
        QualityChoice.firstChoice(new Object());
        assertEquals(FamilyNames.PLAYBACK_QUALITY + ": invoked 1, 0 found, 0 missing", statusLine());
    }

    /** A choice the evaluator makes [atMs] into the video's clock, at [positionMs] with [bufferedMs] buffered; then the rung it keeps to. */
    private static String choice(Evaluator evaluator, long atMs, long positionMs, long bufferedMs) {
        QualityChoice.choosing(evaluator, positionMs * 1000L, bufferedMs * 1000L, atMs);
        return evaluator.playing();
    }

    @Test
    public void theRungBelowIsTheNextLowerLabel() {
        assertEquals("720p", PlaybackQuality.below(Arrays.asList(REEL), "1080p"));
        assertEquals("240p", PlaybackQuality.below(Arrays.asList(REEL), "270p"));
        assertNull("nothing under the lowest", PlaybackQuality.below(Arrays.asList(REEL), "240p"));
        assertEquals("406p", PlaybackQuality.below(Arrays.asList("HD", null, "720P", "406p", "1080p"), "720p"));
        assertNull(PlaybackQuality.below(Arrays.asList(REEL), "HD"));
        assertNull(PlaybackQuality.below(null, "720p"));
    }

    /**
     * The acceptance (#599): Highest on a connection too slow for it. Each second plays a second
     * but loads less, so the buffer drains. Two choices in a row like that and the next takes the
     * rung below, and again while that rung can't keep up either. Once a rung keeps up, it stays.
     * For a while the next videos start no higher than that rung, then at the highest again.
     */
    @Test
    public void aRungTheConnectionCantCarryStepsDown() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        Evaluator video = new Evaluator(REEL);
        assertEquals("1080p", firstChoice(video, 0));

        assertEquals("1080p", choice(video, 0, 0, 2000));
        assertEquals("one chunk behind", "1080p", choice(video, 1000, 1000, 1600));
        assertEquals("two in a row", "720p", choice(video, 2000, 2000, 1200));
        assertEquals("720p", choice(video, 3000, 3000, 800));
        assertEquals("720p can't keep up either", "640p", choice(video, 4000, 4000, 400));
        // 640p loads faster than it plays, so the buffer grows back and the rung stays.
        assertEquals("640p", choice(video, 5000, 5000, 1500));
        assertEquals("640p", choice(video, 6000, 6000, 2500));
        assertEquals("640p", choice(video, 7000, 7000, 3500));
        assertEquals("640p", choice(video, 8000, 8000, 4500));
        assertTrue(statusLine(), statusLine().contains(QualityChoice.STEPPED_DOWN + " 2"));

        assertEquals("the next video starts where this one stepped to", "640p", firstChoice(new Evaluator(REEL), 9000));
        assertEquals("or the best under it", "480p", firstChoice(new Evaluator("1080p", "720p", "480p"), 9000));
        assertEquals("or the lowest it has", "720p", firstChoice(new Evaluator("1080p", "720p"), 9000));
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.P480);
        assertEquals("never higher than the pick", "480p", firstChoice(new Evaluator(REEL), 9000));
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        assertEquals("640p", firstChoice(new Evaluator(REEL), 4000 + QualityChoice.HELD_DOWN_MS - 1));
        assertEquals("then the highest again", "1080p", firstChoice(new Evaluator(REEL), 4000 + QualityChoice.HELD_DOWN_MS));
    }

    /**
     * After a step down Instagram chooses again at once, several times while the video still
     * waits for the new rung, and those don't step it further: the video is judged again once it
     * plays. Without that, this 1080p reel on the #599 device check fell to 270p in two seconds.
     */
    @Test
    public void theChoicesRightAfterAStepDownWaitForTheVideoToPlay() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        Evaluator video = new Evaluator(REEL);
        assertEquals("1080p", firstChoice(video, 959000));
        assertEquals("1080p", choice(video, 959560, 3497, 1502));
        assertEquals("720p", choice(video, 981306, 9765, 234));
        for (long atMs : new long[] {981628, 982001, 982516, 982792, 983188, 983404}) {
            assertEquals("still waiting at " + atMs, "720p", choice(video, atMs, 9765, 234));
        }
        assertEquals("720p", choice(video, 988617, 10946, 4053));
        assertEquals("720p", choice(video, 991677, 14003, 5996));
        assertEquals("new videos start at 720p", "720p", firstChoice(new Evaluator(REEL), 992000));
    }

    /**
     * A choice that loaded under half the time it took is a video already stalling, and it steps
     * down at once. These are a 1080p reel's choices at 1 Mbit on the #599 device check: 16 seconds
     * for 5 seconds of video. A later step down holds new videos under the lower of the two rungs.
     */
    @Test
    public void aVideoThatStallsStepsDownAtOnce() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        Evaluator video = new Evaluator(REEL);
        Evaluator early = new Evaluator(REEL);
        assertEquals("1080p", firstChoice(video, 550000));
        assertEquals("1080p", firstChoice(early, 550000));
        assertEquals("1080p", choice(video, 551006, 3006, 1993));
        assertEquals("720p", choice(video, 567023, 9140, 859));

        Evaluator next = new Evaluator(REEL);
        assertEquals("720p", firstChoice(next, 570000));
        choice(next, 571000, 0, 1500);
        assertEquals("stalling at 720p too", "640p", choice(next, 581000, 2000, 0));
        // A video from before either step down stalls and steps to 720p, and new videos stay under 640p.
        choice(early, 581500, 10000, 1000);
        assertEquals("720p", choice(early, 591500, 12000, 0));
        assertEquals("640p", firstChoice(new Evaluator(REEL), 592000));
    }

    /** A setter that keeps no track for the rung below is reported, and the video keeps the rung it had. */
    @Test
    public void aSetterThatKeepsNothingBelowIsReported() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        QualityChoice.access = new FakeAccess() {
            @Override
            public void setCustomQuality(Object evaluator, String label) {
                super.setCustomQuality(evaluator, "720p".equals(label) ? "no such label" : label);
            }
        };
        Evaluator video = new Evaluator(REEL);
        assertEquals("1080p", firstChoice(video));
        choice(video, 0, 0, 2000);
        choice(video, 1000, 800, 1500);
        assertEquals("1080p", choice(video, 2000, 1600, 1000));
        assertFalse(HookStatus.missing(FamilyNames.PLAYBACK_QUALITY).isEmpty());
        assertFalse(statusLine(), statusLine().contains(QualityChoice.STEPPED_DOWN));
    }

    /** A connection that keeps up never steps down, nor does a video that hasn't started, a load after a pause, or a seek. */
    @Test
    public void aRungThatKeepsUpStays() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        Evaluator fast = new Evaluator(REEL);
        firstChoice(fast);
        // Starting: little buffered and the position hasn't moved.
        assertEquals("1080p", choice(fast, 0, 0, 300));
        assertEquals("1080p", choice(fast, 1500, 0, 600));
        assertEquals("1080p", choice(fast, 3000, 0, 900));
        // Playing and filling.
        assertEquals("1080p", choice(fast, 3500, 400, 2600));
        assertEquals("1080p", choice(fast, 4000, 900, 4100));
        // Loading stopped with enough buffered and starts again lower: one slow-looking gap isn't two.
        assertEquals("1080p", choice(fast, 20000, 17000, 2000));
        assertEquals("1080p", choice(fast, 20400, 17400, 3600));
        // A seek forward empties the buffer, and a loop or a seek back moves the position back.
        assertEquals("1080p", choice(fast, 21000, 40000, 0));
        assertEquals("1080p", choice(fast, 22000, 40500, 200));
        assertEquals("1080p", choice(fast, 23000, 0, 100));
        assertEquals("1080p", choice(fast, 24000, 600, 400));
        assertFalse(statusLine(), statusLine().contains(QualityChoice.STEPPED_DOWN));
    }

    /**
     * A rung playing right at the loaded edge, nothing buffered at each choice but the position
     * keeping time with the clock, stays: the two tick a few milliseconds apart, and that isn't
     * falling behind. These are 640p's choices at 1 Mbit on the #599 device check. A real stall
     * there, the position held up while the next chunk loads, still steps down.
     */
    @Test
    public void aRungRightAtTheLoadedEdgeStays() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        Evaluator edge = new Evaluator(REEL);
        firstChoice(edge);
        assertEquals("1080p", choice(edge, 220025, 31638, 0));
        assertEquals("1080p", choice(edge, 226505, 38118, 0));
        assertEquals("1080p", choice(edge, 228376, 39987, 0));
        assertEquals("1080p", choice(edge, 231142, 42754, 0));
        assertEquals("1080p", choice(edge, 234314, 45926, 0));
        assertEquals("1080p", choice(edge, 240093, 51705, 0));
        assertEquals("1080p", choice(edge, 246772, 58381, 0));
        assertEquals("1080p", choice(edge, 255188, 66795, 0));
        assertEquals("1080p", choice(edge, 259488, 71090, 0));
        assertFalse(statusLine(), statusLine().contains(QualityChoice.STEPPED_DOWN));

        assertEquals("a second held up", "1080p", choice(edge, 263488, 74090, 0));
        assertEquals("and another", "720p", choice(edge, 267488, 77090, 0));
    }

    /** Data saver's lowest rung, and a rung with nothing under it, stay however far behind. */
    @Test
    public void theLowestRungStays() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        Evaluator one = new Evaluator("720p");
        assertEquals("720p", firstChoice(one));
        for (int i = 0; i < 6; i++) assertEquals("720p", choice(one, i * 1000L, i * 900L, 2000 - i * 300L));

        Settings.DEFAULT_PLAYBACK_QUALITY.save(false);
        Settings.DATA_SAVER.save(true);
        Settings.DATA_SAVER_MOBILE_DATA_ONLY.save(false);
        DataSaver.inBuildForTests = Boolean.TRUE;
        try {
            Evaluator saving = new Evaluator("480p", "240p", "360p");
            assertEquals("240p", firstChoice(saving));
            for (int i = 0; i < 6; i++) assertEquals("240p", choice(saving, i * 1000L, i * 900L, 2000 - i * 300L));
        } finally {
            DataSaver.inBuildForTests = null;
            Settings.DATA_SAVER.resetToDefault();
            Settings.DATA_SAVER_MOBILE_DATA_ONLY.resetToDefault();
        }
        assertFalse(statusLine(), statusLine().contains(QualityChoice.STEPPED_DOWN));
    }

    /** An evaluator this never kept to a track, such as Auto's, is Instagram's to step down. */
    @Test
    public void onlyATrackThisKeptStepsDown() {
        Evaluator auto = new Evaluator(REEL);
        assertNull(firstChoice(auto));
        QualityChoice.eachChoice(auto, null, null, null, null, null, null, null, null, 1f, 800_000L, 100_000L, 0L);
        for (int i = 0; i < 6; i++) assertNull(choice(auto, i * 1000L, i * 900L, 2000 - i * 300L));

        Evaluator chosen = new Evaluator(REEL);
        chosen.setCustomQuality("720p");
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        assertEquals("kept by someone else", "720p", firstChoice(chosen));
        for (int i = 0; i < 6; i++) assertEquals("720p", choice(chosen, i * 1000L, i * 900L, 2000 - i * 300L));
    }

    /** A failure while stepping down is reported, and the video keeps the rung it had. */
    @Test
    public void aFailureSteppingDownIsReported() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        Evaluator video = new Evaluator(REEL);
        firstChoice(video);
        choice(video, 0, 0, 2000);
        choice(video, 1000, 1000, 1600);
        access.failure = new IllegalStateException("the stub failed");
        assertEquals("1080p", choice(video, 2000, 2000, 1200));
        assertTrue(HookStatus.missing(FamilyNames.PLAYBACK_QUALITY).get(0)
                .startsWith("a working 'each choice' hook (it threw "));
    }

    /** Data saver starts a video at the lowest quality while it's saving, even with this patch's switch off. */
    @Test
    public void dataSaverStartsAtTheLowestQuality() {
        Settings.DEFAULT_PLAYBACK_QUALITY.save(false);
        Settings.DATA_SAVER.save(true);
        Settings.DATA_SAVER_MOBILE_DATA_ONLY.save(false);
        DataSaver.inBuildForTests = Boolean.TRUE;
        try {
            assertEquals("240p", firstChoice(new Evaluator(REEL)));
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(FamilyNames.DATA_SAVER + ":") && report.contains(DataSaver.LOWEST_VIDEO + " 1"));

            Settings.DATA_SAVER.save(false);
            assertNull("off, Instagram picks again", firstChoice(new Evaluator(REEL)));
        } finally {
            DataSaver.inBuildForTests = null;
            Settings.DATA_SAVER.resetToDefault();
            Settings.DATA_SAVER_MOBILE_DATA_ONLY.resetToDefault();
        }
    }
}

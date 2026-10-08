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

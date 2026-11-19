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
        Settings.HIDE_FEED_HEADER_LINE.resetToDefault();
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
}

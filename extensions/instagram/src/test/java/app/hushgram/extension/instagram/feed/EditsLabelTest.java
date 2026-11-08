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

/** Hide Made with Edits (#570): the Edits kind leaves a feed post header's labels, the rest keep turning. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class EditsLabelTest {
    /** The label kinds a header cycles through, named the way Instagram's enum is. */
    enum Kind { MUSIC, LOCATION, SOCIAL_CONTEXT, BASEL_ATTRIBUTION, SPONSORED }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_EDITS_LABEL.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_EDITS_LABEL.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void startsOff() {
        Settings.HIDE_EDITS_LABEL.resetToDefault();
        List<Kind> kinds = Arrays.asList(Kind.MUSIC, Kind.BASEL_ATTRIBUTION);
        assertSame(kinds, EditsLabel.labels(kinds));
    }

    /** On, only the Edits kind goes, the others stay in their order, and the list handed in is left alone. */
    @Test
    public void theEditsLabelLeavesAndTheOthersKeepTurning() {
        List<Object> kinds = Arrays.asList(Kind.MUSIC, Kind.BASEL_ATTRIBUTION, Kind.LOCATION, "not a kind", Kind.SOCIAL_CONTEXT);

        List<?> kept = EditsLabel.labels(kinds);

        assertEquals(Arrays.asList(Kind.MUSIC, Kind.LOCATION, "not a kind", Kind.SOCIAL_CONTEXT), kept);
        assertNotSame(kinds, kept);
        assertEquals(Kind.BASEL_ATTRIBUTION, kinds.get(1));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(EditsLabel.COUNT_HIDDEN));
        assertTrue(HookStatus.missing(FamilyNames.EDITS_LABEL).toString(), HookStatus.missing(FamilyNames.EDITS_LABEL).isEmpty());
    }

    /** A header with only the Edits label gets an empty list, so it shows no line rather than that one. */
    @Test
    public void aHeaderWithOnlyTheEditsLabelShowsNone() {
        assertEquals(Collections.emptyList(), EditsLabel.labels(Collections.singletonList(Kind.BASEL_ATTRIBUTION)));
    }

    /** Without the Edits kind the list comes back as the same list. */
    @Test
    public void aHeaderWithoutEditsIsTheSameList() {
        List<Kind> kinds = Arrays.asList(Kind.MUSIC, Kind.SPONSORED);
        assertSame(kinds, EditsLabel.labels(kinds));
    }

    /** Off, paused or before the settings are read, every label stays. */
    @Test
    public void offPausedAndUnreadyKeepEveryLabel() {
        List<Kind> kinds = Arrays.asList(Kind.MUSIC, Kind.BASEL_ATTRIBUTION);

        Settings.HIDE_EDITS_LABEL.save(false);
        assertSame(kinds, EditsLabel.labels(kinds));
        Settings.HIDE_EDITS_LABEL.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(kinds, EditsLabel.labels(kinds));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(kinds, EditsLabel.labels(kinds)));
        assertEquals(Collections.singletonList(Kind.MUSIC), EditsLabel.labels(kinds));
    }

    /** A switch that throws keeps the labels and says which hook threw. */
    @Test
    public void aThrowingSwitchKeepsTheLabelsAndIsReported() {
        List<Kind> kinds = Arrays.asList(Kind.MUSIC, Kind.BASEL_ATTRIBUTION);

        assertSame(kinds, EditsLabel.labels(kinds, () -> {
            throw new IllegalStateException("broken switch");
        }));

        String missing = HookStatus.missing(FamilyNames.EDITS_LABEL).toString();
        assertTrue(missing, missing.contains(EditsLabel.HOOK));
    }

    @Test
    public void nullAndEmptyStayAsTheyCame() {
        assertNull(EditsLabel.labels(null));
        List<Kind> none = Collections.emptyList();
        assertSame(none, EditsLabel.labels(none));
    }
}

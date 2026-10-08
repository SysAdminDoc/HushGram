/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

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

import java.util.List;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which animation the double-tap heart is set up with, and which names the settings list. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LikeAnimationTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for Instagram's animation enum, the plain heart's value first as on 450. */
    enum Kind { UNRECOGNIZED, RINGS_LIKE_ADRIAN, RINGS_LIKE_AKI_KOICHI, ANYWAY_LIKE_ACTIVATION }

    private static final Supplier<Object> THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.CHANGE_LIKE_ANIMATION.save(true);
        Settings.LIKE_ANIMATION.save("RINGS_LIKE_ADRIAN");
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.CHANGE_LIKE_ANIMATION.resetToDefault();
        Settings.LIKE_ANIMATION.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void thePickedAnimationReplacesInstagramsAndOpensItsCheck() {
        Object heart = Kind.UNRECOGNIZED;
        assertSame(Kind.RINGS_LIKE_ADRIAN, LikeAnimation.pick(heart, () -> Kind.RINGS_LIKE_ADRIAN));
        assertSame("a Rings creator's own too", Kind.RINGS_LIKE_ADRIAN, LikeAnimation.pick(Kind.ANYWAY_LIKE_ACTIVATION, () -> Kind.RINGS_LIKE_ADRIAN));
        assertSame("the plain heart's missing value too", Kind.RINGS_LIKE_ADRIAN, LikeAnimation.pick(null, () -> Kind.RINGS_LIKE_ADRIAN));
        assertSame(heart, LikeAnimation.pick(heart, () -> null));
        assertNull(LikeAnimation.pick(null, () -> null));
        assertEquals(List.of(FamilyNames.LIKE_ANIMATION + ": invoked 5, 0 found, 0 missing. Counted: "
                        + "picked animation 3, Instagram's animation 2"),
                HookStatus.report());

        assertTrue(LikeAnimation.allow(false, () -> Kind.RINGS_LIKE_ADRIAN));
        assertFalse(LikeAnimation.allow(false, () -> null));
        assertTrue("Instagram's yes stays", LikeAnimation.allow(true, () -> null));
        assertTrue(LikeAnimation.allow(true, THROWS));
    }

    @Test
    public void aFailureLeavesItToInstagram() {
        Object heart = Kind.UNRECOGNIZED;
        assertSame(heart, LikeAnimation.pick(heart, THROWS));
        assertFalse(LikeAnimation.allow(false, THROWS));
        String missing = HookStatus.missing(FamilyNames.LIKE_ANIMATION).toString();
        assertTrue(missing, missing.contains("'" + LikeAnimation.SET_UP + "'"));
        assertTrue(missing, missing.contains("'" + LikeAnimation.CHECK + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** On, ready and with a name Instagram has, the setting picks; off, paused, unready or blank, nothing is. */
    @Test
    public void theSwitchAndTheNamePick() {
        assertSame(Kind.RINGS_LIKE_ADRIAN, LikeAnimation.chosen(Kind.class, Kind.UNRECOGNIZED));
        Settings.LIKE_ANIMATION.save("ANYWAY_LIKE_ACTIVATION");
        assertSame(Kind.ANYWAY_LIKE_ACTIVATION, LikeAnimation.chosen(Kind.class, Kind.UNRECOGNIZED));

        Settings.LIKE_ANIMATION.save("UNRECOGNIZED");
        assertNull("never the plain heart's own value", LikeAnimation.chosen(Kind.class, Kind.UNRECOGNIZED));
        Settings.LIKE_ANIMATION.save("RINGS_LIKE_GONE");
        assertNull("a name this Instagram doesn't have", LikeAnimation.chosen(Kind.class, Kind.UNRECOGNIZED));
        Settings.LIKE_ANIMATION.save("");
        assertNull("nothing picked yet", LikeAnimation.chosen(Kind.class, Kind.UNRECOGNIZED));
        Settings.LIKE_ANIMATION.save("RINGS_LIKE_ADRIAN");
        assertNull("unpatched", LikeAnimation.chosen(null, null));

        Settings.CHANGE_LIKE_ANIMATION.save(false);
        assertNull(LikeAnimation.chosen(Kind.class, Kind.UNRECOGNIZED));
        Settings.CHANGE_LIKE_ANIMATION.save(true);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertNull(LikeAnimation.chosen(Kind.class, Kind.UNRECOGNIZED));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertNull(LikeAnimation.chosen(Kind.class, Kind.UNRECOGNIZED)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertNull(LikeAnimation.chosen(Kind.class, Kind.UNRECOGNIZED)));
        assertSame(Kind.RINGS_LIKE_ADRIAN, LikeAnimation.chosen(Kind.class, Kind.UNRECOGNIZED));
    }

    /** Unpatched, the stubs answer nothing, so the hooks leave Instagram's animation and check alone. */
    @Test
    public void unpatchedTheHooksChangeNothing() {
        Object heart = new Object();
        assertSame(heart, LikeAnimation.pick(heart));
        assertFalse(LikeAnimation.allow(0));
        assertTrue(LikeAnimation.allow(1));
        assertTrue("any non-zero answer is Instagram's yes", LikeAnimation.allow(2));
        assertNull(LikeAnimation.animationType());
        assertTrue(LikeAnimation.names().isEmpty());
    }

    /** The type comes off the plain heart's value, the one thing the patch fills in. */
    @Test
    public void theTypeIsThePlainHeartsEnum() {
        assertSame(Kind.class, LikeAnimation.typeOf(Kind.UNRECOGNIZED));
        assertNull(LikeAnimation.typeOf(null));
        assertNull("not an enum's value", LikeAnimation.typeOf("UNRECOGNIZED"));
    }

    @Test
    public void theNamesAndTheirLabels() {
        assertEquals(List.of("RINGS_LIKE_ADRIAN", "RINGS_LIKE_AKI_KOICHI", "ANYWAY_LIKE_ACTIVATION"),
                LikeAnimation.names(Kind.class, Kind.UNRECOGNIZED));
        assertTrue(LikeAnimation.names(null, null).isEmpty());
        assertTrue("not an enum", LikeAnimation.names(String.class, null).isEmpty());
        assertEquals("Adrian", LikeAnimation.label("RINGS_LIKE_ADRIAN"));
        assertEquals("Aki Koichi", LikeAnimation.label("RINGS_LIKE_AKI_KOICHI"));
        assertEquals("Anyway", LikeAnimation.label("ANYWAY_LIKE_ACTIVATION"));
        assertEquals("a name of another shape keeps its words", "New Style Heart", LikeAnimation.label("NEW_STYLE_HEART"));
        assertEquals("and one with none keeps itself", "RINGS_LIKE_", LikeAnimation.label("RINGS_LIKE_"));
    }
}

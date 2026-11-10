/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What Hide profile banners answers where a profile's header gets its row of banners: the list
 * without the kinds whose switches are on, on other people's profiles only.
 */
@RunWith(RobolectricTestRunner.class)
public class ProfileBannersTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ME = "1001";
    private static final String THEM = "2002";

    /** A banner as the row holds it; the test's reader answers its kind. */
    private static final class Banner {
        final String type;

        Banner(String type) {
            this.type = type;
        }

        @Override
        public String toString() {
            return type;
        }
    }

    /** The signed-in session, as far as the test's reader is concerned. */
    private static final class Session {
        final String userId;

        Session(String userId) {
            this.userId = userId;
        }
    }

    private Function<Object, String> typeReader;
    private Function<Object, String> viewerReader;

    private static List<BooleanSetting> switches() {
        return Arrays.asList(Settings.HIDE_PROFILE_THREADS_BADGE, Settings.HIDE_PROFILE_FACEBOOK_LINKS,
                Settings.HIDE_PROFILE_WHATSAPP_LINK, Settings.HIDE_PROFILE_SONG, Settings.HIDE_PROFILE_INTERESTS,
                Settings.HIDE_PROFILE_CHANNEL_BANNER, Settings.HIDE_OTHER_PROFILE_BANNERS);
    }

    @Before
    public void start() {
        typeReader = ProfileBanners.typeReader;
        viewerReader = ProfileBanners.viewerReader;
        ProfileBanners.typeReader = banner -> ((Banner) banner).type;
        ProfileBanners.viewerReader = session -> ((Session) session).userId;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        for (BooleanSetting setting : switches()) setting.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        ProfileBanners.typeReader = typeReader;
        ProfileBanners.viewerReader = viewerReader;
        for (BooleanSetting setting : switches()) setting.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The row Instagram builds for a profile with one banner of most kinds, in Instagram's order. */
    private static List<Banner> row() {
        List<Banner> row = new ArrayList<>();
        for (String type : new String[] {"THREADS", "FACEBOOK_PROFILE", "FACEBOOK_PAGE", "FACEBOOK_GROUP",
                "WHATSAPP_PROFILE", "MUSIC", "INTERESTS", "CHANNEL", "UPCOMING_EVENTS", "FUNDRAISER", "hours", "MUSE"}) {
            row.add(new Banner(type));
        }
        return row;
    }

    private static List<String> kinds(List<?> banners) {
        List<String> kinds = new ArrayList<>();
        for (Object banner : banners) kinds.add(((Banner) banner).type);
        return kinds;
    }

    /** Every switch starts off, and then the row goes through as Instagram built it, the same list. */
    @Test
    public void everySwitchStartsOffAndTheRowStands() {
        for (BooleanSetting setting : switches()) assertFalse(setting.key, setting.get());
        List<Banner> row = row();
        assertSame(row, ProfileBanners.filter(row, THEM, new Session(ME)));
        assertTrue(HookStatus.missing(FamilyNames.PROFILE_BANNERS).toString(), HookStatus.missing(FamilyNames.PROFILE_BANNERS).isEmpty());
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("Counted"));
    }

    /** Each switch takes only its own kinds off someone else's profile, and the rest keep their order. */
    @Test
    public void eachSwitchLeavesOutItsOwnKinds() {
        Settings.HIDE_PROFILE_THREADS_BADGE.save(true);
        assertEquals(Arrays.asList("FACEBOOK_PROFILE", "FACEBOOK_PAGE", "FACEBOOK_GROUP", "WHATSAPP_PROFILE", "MUSIC",
                "INTERESTS", "CHANNEL", "UPCOMING_EVENTS", "FUNDRAISER", "hours", "MUSE"),
                kinds(ProfileBanners.filter(row(), THEM, new Session(ME))));
        Settings.HIDE_PROFILE_THREADS_BADGE.save(false);

        Settings.HIDE_PROFILE_FACEBOOK_LINKS.save(true);
        Settings.HIDE_PROFILE_SONG.save(true);
        assertEquals(Arrays.asList("THREADS", "WHATSAPP_PROFILE", "INTERESTS", "CHANNEL", "UPCOMING_EVENTS", "FUNDRAISER",
                "hours", "MUSE"), kinds(ProfileBanners.filter(row(), THEM, new Session(ME))));
        Settings.HIDE_PROFILE_FACEBOOK_LINKS.save(false);
        Settings.HIDE_PROFILE_SONG.save(false);

        Settings.HIDE_PROFILE_WHATSAPP_LINK.save(true);
        Settings.HIDE_PROFILE_INTERESTS.save(true);
        Settings.HIDE_PROFILE_CHANNEL_BANNER.save(true);
        assertEquals(Arrays.asList("THREADS", "FACEBOOK_PROFILE", "FACEBOOK_PAGE", "FACEBOOK_GROUP", "MUSIC",
                "UPCOMING_EVENTS", "FUNDRAISER", "hours", "MUSE"), kinds(ProfileBanners.filter(row(), THEM, new Session(ME))));

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + ProfileBanners.LEFT_OUT + " 8"));
        assertTrue(HookStatus.missing(FamilyNames.PROFILE_BANNERS).toString(), HookStatus.missing(FamilyNames.PROFILE_BANNERS).isEmpty());
    }

    /** The other switch takes every kind no named switch covers, a server-named one too, and never Muse's. */
    @Test
    public void theOtherSwitchTakesTheRestButMuse() {
        Settings.HIDE_OTHER_PROFILE_BANNERS.save(true);
        assertEquals(Arrays.asList("THREADS", "FACEBOOK_PROFILE", "FACEBOOK_PAGE", "FACEBOOK_GROUP", "WHATSAPP_PROFILE",
                "MUSIC", "INTERESTS", "CHANNEL", "MUSE"), kinds(ProfileBanners.filter(row(), THEM, new Session(ME))));

        for (BooleanSetting setting : switches()) setting.save(true);
        assertEquals(Arrays.asList("MUSE"), kinds(ProfileBanners.filter(row(), THEM, new Session(ME))));
        assertNull("Muse is left to Hide Meta AI", ProfileBanners.switchFor("muse"));
        assertSame(Settings.HIDE_PROFILE_THREADS_BADGE, ProfileBanners.switchFor("threads"));
    }

    /** Your own profile, and an account it can't read, keep every banner with every switch on. */
    @Test
    public void yourOwnProfileKeepsItsBanners() {
        for (BooleanSetting setting : switches()) setting.save(true);
        List<Banner> row = row();
        assertSame("yours", row, ProfileBanners.filter(row, ME, new Session(ME)));
        assertSame("no session", row, ProfileBanners.filter(row, THEM, null));
        assertSame("no viewer", row, ProfileBanners.filter(row, THEM, new Session(null)));
        assertSame("no profile", row, ProfileBanners.filter(row, null, new Session(ME)));
        ProfileBanners.viewerReader = session -> {
            throw new IllegalStateException("logged out");
        };
        assertSame("unreadable viewer", row, ProfileBanners.filter(row, THEM, new Session(ME)));
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("Counted"));
    }

    /** A banner whose kind can't be read stays, and so does the rest of the row around it. */
    @Test
    public void anUnreadableBannerStays() {
        Settings.HIDE_PROFILE_THREADS_BADGE.save(true);
        List<Object> row = new ArrayList<>();
        row.add(new Banner("THREADS"));
        row.add("not a banner");
        row.add(new Banner(null));
        row.add(new Banner("MUSIC"));
        List<?> drawn = ProfileBanners.filter(row, THEM, new Session(ME));
        assertEquals(3, drawn.size());
        assertSame(row.get(1), drawn.get(0));
        assertEquals("MUSIC", ((Banner) drawn.get(2)).type);
    }

    /** Paused, or before the settings are read, the row goes through as Instagram built it. */
    @Test
    public void pausedAndUnreadyKeepTheRow() {
        for (BooleanSetting setting : switches()) setting.save(true);
        List<Banner> row = row();

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", row, ProfileBanners.filter(row, THEM, new Session(ME)));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() ->
                assertSame("no context", row, ProfileBanners.filter(row, THEM, new Session(ME))));
        SettingsContextRule.beforeThePauseIsDecided(() ->
                assertSame("pause undecided", row, ProfileBanners.filter(row, THEM, new Session(ME))));

        assertEquals("back on", Arrays.asList("MUSE"), kinds(ProfileBanners.filter(row, THEM, new Session(ME))));
    }
}

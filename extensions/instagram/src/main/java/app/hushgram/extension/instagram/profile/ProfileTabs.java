/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Hide profile tabs" patch (#254): leaves the Reels, Reposts and Tagged tabs off
 * profiles and keeps the grid, with one pick for your own profile and one for everyone else's
 * (#296).
 *
 * <p>Instagram names each profile tab with a string key ({@code profile_media_grid},
 * {@code profile_clips}, {@code profile_reposts}, {@code profile_tagged_media_photos_of_you}) and
 * builds the list of keys for the profile it's showing in one method, which the tab strip, the pager
 * and the tab providers are all made from. The patch hands that list to {@link #tabs} just before the
 * method returns, with the builder and the profile's user. A tab is dropped by its key, never by its
 * place in the list, and a key Instagram didn't put in the list is never added. With one tab left
 * Instagram draws no strip at all, as it already does for an account with nothing but a grid.
 *
 * <p>A profile is your own when its user's ID is the signed-in account's. When either ID can't be
 * read, the profile counts as someone else's.
 *
 * <p>Fails open: with nothing saved, HushGram paused, the settings not read yet or anything thrown,
 * the list goes back as Instagram made it.
 */
public final class ProfileTabs {
    /** The grid of posts. Never offered, never dropped. */
    static final String GRID = "profile_media_grid";

    /** The tabs the setting can leave off, as Instagram's keys, in the order the tab strip shows them. */
    public static final List<String> OFFERED = Collections.unmodifiableList(Arrays.asList(
            "profile_clips", "profile_reposts", "profile_tagged_media_photos_of_you"));

    /**
     * What {@link Settings#HIDDEN_OWN_PROFILE_TABS} holds until it's saved: your own profile follows
     * {@link Settings#HIDDEN_PROFILE_TABS}, which covered every profile before #296.
     */
    public static final String SAME_AS_OTHERS = "same_as_other_profiles";

    /** What's counted for each profile whose tab list lost a tab. */
    static final String LEFT_OFF = "Profile tabs left off";

    /** Reads the two IDs that tell your own profile from someone else's. */
    interface Reader {
        @Nullable
        String profileId(Object user);

        @Nullable
        String viewerId(Object builder);
    }

    /** The readers the patch writes. */
    static final Reader PATCHED = new Reader() {
        @Override
        public String profileId(Object user) {
            return ProfileTabs.profileId(user);
        }

        @Override
        public String viewerId(Object builder) {
            return ProfileTabs.viewerId(builder);
        }
    };

    private ProfileTabs() {
    }

    /**
     * Injected where Instagram's builder of a profile's tab keys returns its list, with the builder
     * and the user whose profile it is. Answers a copy of [tabs] without the tabs the setting for
     * that profile names, or [tabs] itself when there's nothing to drop. Never throws, and never
     * waits for the settings.
     */
    public static List<String> tabs(@Nullable Object builder, @Nullable Object user, List<String> tabs) {
        return tabs(builder, user, tabs, PATCHED);
    }

    static List<String> tabs(@Nullable Object builder, @Nullable Object user, List<String> tabs, Reader reader) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_TABS);
            if (tabs == null || !Utils.settingsReady() || HushgramPause.isPaused()) return tabs;
            Set<String> hidden = parse(hiddenFor(isOwn(builder, user, reader)));
            List<String> kept = without(tabs, hidden);
            if (kept == null) return tabs;
            HookStatus.counted(FamilyNames.PROFILE_TABS, LEFT_OFF);
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_TABS, "profile tabs", failure);
            return tabs;
        }
    }

    /**
     * Whether [user]'s profile is the signed-in account's own. False when either ID can't be read,
     * so a profile nobody can place gets the other-profiles setting.
     */
    static boolean isOwn(@Nullable Object builder, @Nullable Object user, Reader reader) {
        if (builder == null || user == null) return false;
        try {
            String profile = reader.profileId(user);
            if (profile == null || profile.isEmpty()) return false;
            return profile.equals(reader.viewerId(builder));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_TABS, "whose profile", failure);
            return false;
        }
    }

    /** The saved tabs to leave off your own profile when [own], else off other people's. */
    static String hiddenFor(boolean own) {
        String others = Settings.HIDDEN_PROFILE_TABS.get();
        if (!own) return others;
        String mine = Settings.HIDDEN_OWN_PROFILE_TABS.get();
        return SAME_AS_OTHERS.equals(mine) ? others : mine;
    }

    /** What the user picked for your own profile, following the other-profiles pick until it's saved. */
    public static String savedForOwn() {
        String mine = Settings.HIDDEN_OWN_PROFILE_TABS.savedValue();
        return SAME_AS_OTHERS.equals(mine) ? Settings.HIDDEN_PROFILE_TABS.savedValue() : mine;
    }

    /** Saves [hidden] as the tabs to leave off your own profile. */
    public static void saveOwn(Set<String> hidden) {
        Settings.HIDDEN_OWN_PROFILE_TABS.save(join(hidden));
    }

    /**
     * Saves [hidden] as the tabs to leave off other people's profiles. Your own profile was
     * following that pick until now, so it keeps the old one as its own first.
     */
    public static void saveOthers(Set<String> hidden) {
        if (SAME_AS_OTHERS.equals(Settings.HIDDEN_OWN_PROFILE_TABS.savedValue())) {
            Settings.HIDDEN_OWN_PROFILE_TABS.save(join(parse(Settings.HIDDEN_PROFILE_TABS.savedValue())));
        }
        Settings.HIDDEN_PROFILE_TABS.save(join(hidden));
    }

    /**
     * A copy of [tabs] without the keys in [hidden], or null when nothing would change or the
     * result would have no grid left (Instagram's list always starts with it, so a list without it
     * isn't one this knows how to thin).
     */
    @Nullable
    static List<String> without(List<String> tabs, Set<String> hidden) {
        if (hidden.isEmpty() || !tabs.contains(GRID)) return null;
        List<String> kept = new ArrayList<>(tabs.size());
        for (String tab : tabs) {
            if (tab == null || !hidden.contains(tab)) kept.add(tab);
        }
        return kept.size() == tabs.size() ? null : kept;
    }

    /** The offered keys in [saved], without blanks, repeats or anything this doesn't offer. */
    public static Set<String> parse(@Nullable String saved) {
        Set<String> keys = new LinkedHashSet<>();
        if (saved == null) return keys;
        for (String part : saved.split(",")) {
            String key = part.trim();
            if (OFFERED.contains(key)) keys.add(key);
        }
        return keys;
    }

    /** [hidden] as the saved value, in the order the tabs are offered. */
    public static String join(Set<String> hidden) {
        List<String> keys = new ArrayList<>();
        for (String key : OFFERED) {
            if (hidden.contains(key)) keys.add(key);
        }
        return String.join(",", keys);
    }

    /** Filled in by the patch: the ID of [user], the user whose profile is showing, or null. */
    @Nullable
    public static String profileId(Object user) {
        return null;
    }

    /** Filled in by the patch: the signed-in account's ID, read off [builder], the builder of the tab list, or null. */
    @Nullable
    public static String viewerId(Object builder) {
        return null;
    }
}

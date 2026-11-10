/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * Helper for the "Hide profile banners" patch (#627): takes chosen banners out of the row under the
 * bio on other people's profiles.
 *
 * <p>That row (the Threads badge, links to a Facebook profile, page or group or to WhatsApp, the
 * profile's song, its interests, a broadcast channel invite, events, fundraisers and more) is one
 * list, and every banner in it carries the name of its kind, such as {@code THREADS} or
 * {@code FACEBOOK_PAGE}. The patch hands that list to {@link #filter} just before Instagram wraps it
 * for the profile's header, along with the profile's user ID and the signed-in session, and the
 * header draws what comes back. Each kind has its own switch, so the reporter's "hide the Threads
 * badge, keep the Facebook link" works.
 *
 * <p>Your own profile keeps every banner, and so does Edit featured, which builds the same row for
 * your profile. The Muse banner is left to Hide Meta AI on your profile, which already drops it
 * before it gets here.
 *
 * <p>The hook fails open: with every switch off, HushGram paused, the settings not read yet, a
 * profile or account it can't tell apart, a banner whose kind it can't read or anything thrown, the
 * list goes through as it came.
 */
public final class ProfileBanners {
    /** What's counted for each banner left out of a profile. */
    static final String LEFT_OUT = "Profile banners left out";

    /** The kind Instagram gives the Muse banner, which Hide Meta AI on your profile handles. */
    static final String MUSE = "MUSE";

    /** Reads a banner's kind. The patch fills {@link #bannerType}; tests swap in their own. */
    static volatile Function<Object, String> typeReader = ProfileBanners::bannerType;

    /** Reads the signed-in account's user ID from the session. The patch fills {@link #viewerId}. */
    static volatile Function<Object, String> viewerReader = ProfileBanners::viewerId;

    private ProfileBanners() {
    }

    /**
     * Injected where a profile's header gets its row of banners. Answers the list to draw: [banners]
     * itself, or a copy without the kinds whose switches are on when [profileId] isn't the account
     * signed in on [session]. Never throws, and never waits for the settings.
     */
    public static List<?> filter(List<?> banners, String profileId, Object session) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_BANNERS);
            if (banners == null || banners.isEmpty() || !Utils.settingsReady() || !anyOn()) return banners;
            if (profileId == null || profileId.isEmpty()) return banners;
            String viewer = read(viewerReader, session);
            // An account it can't read might be yours, so nothing is left out.
            if (viewer == null || viewer.isEmpty() || viewer.equals(profileId)) return banners;
            List<Object> kept = new ArrayList<>(banners.size());
            List<String> left = new ArrayList<>();
            for (Object banner : banners) {
                String type = read(typeReader, banner);
                if (type != null && hides(type)) {
                    left.add(type);
                    HookStatus.counted(FamilyNames.PROFILE_BANNERS, LEFT_OUT);
                } else {
                    kept.add(banner);
                }
            }
            if (left.isEmpty()) return banners;
            Logger.printDebug(() -> "Profile banners: left out " + left + ", kept " + kept.size());
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_BANNERS, "profile banners", failure);
            return banners;
        }
    }

    /**
     * The switch that leaves a banner of [type] out, or null for a kind no switch here covers. A
     * kind none of the named switches takes falls to Hide the other profile banners.
     */
    static BooleanSetting switchFor(String type) {
        switch (type.toUpperCase(Locale.ROOT)) {
            case MUSE:
                return null;
            case "THREADS":
                return Settings.HIDE_PROFILE_THREADS_BADGE;
            case "FACEBOOK_PROFILE":
            case "FACEBOOK_PAGE":
            case "FACEBOOK_GROUP":
                return Settings.HIDE_PROFILE_FACEBOOK_LINKS;
            case "WHATSAPP_PROFILE":
                return Settings.HIDE_PROFILE_WHATSAPP_LINK;
            case "MUSIC":
                return Settings.HIDE_PROFILE_SONG;
            case "INTERESTS":
                return Settings.HIDE_PROFILE_INTERESTS;
            case "CHANNEL":
                return Settings.HIDE_PROFILE_CHANNEL_BANNER;
            default:
                return Settings.HIDE_OTHER_PROFILE_BANNERS;
        }
    }

    private static boolean hides(String type) {
        BooleanSetting setting = switchFor(type);
        return setting != null && setting.get();
    }

    private static boolean anyOn() {
        return Settings.HIDE_PROFILE_THREADS_BADGE.get() || Settings.HIDE_PROFILE_FACEBOOK_LINKS.get()
                || Settings.HIDE_PROFILE_WHATSAPP_LINK.get() || Settings.HIDE_PROFILE_SONG.get()
                || Settings.HIDE_PROFILE_INTERESTS.get() || Settings.HIDE_PROFILE_CHANNEL_BANNER.get()
                || Settings.HIDE_OTHER_PROFILE_BANNERS.get();
    }

    /** [reader]'s answer for [value], or null when it has none or throws, which keeps what it was asked about. */
    private static String read(Function<Object, String> reader, Object value) {
        if (value == null) return null;
        try {
            return reader.apply(value);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The kind of one banner in the row, such as THREADS or MUSIC. A stub: the patch writes its body,
     * which reads the kind out of [banner]. Answers null unpatched, which leaves the banner in.
     */
    public static String bannerType(Object banner) {
        return null;
    }

    /**
     * The user ID of the account signed in on [session]. A stub: the patch writes its body. Answers
     * null unpatched, which keeps every banner.
     */
    public static String viewerId(Object session) {
        return null;
    }
}

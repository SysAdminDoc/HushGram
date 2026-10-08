/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.HashMap;
import java.util.Map;

import app.hushgram.extension.instagram.profile.FriendshipStatus.Relation;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/**
 * The label goes by the profile screen's own answer first, the one Instagram's options sheet reads
 * to offer Remove follower, and only without one by the status kept on the account (#40).
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = FriendshipScreenAnswerTest.Patched.class)
public class FriendshipScreenAnswerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        Settings.SHOW_FRIENDSHIP_STATUS.resetToDefault();
    }

    /** The case reported in #40: the kept status says no, the screen has heard they do. */
    @Test
    public void theScreensYesBeatsAKeptNo() {
        Profile profile = new Profile(false, null).screen(true, null);
        assertEquals(Relation.FOLLOWS_YOU, FriendshipStatus.relation(profile));
        assertEquals("Follows you", FriendshipStatus.label(profile));
    }

    @Test
    public void theScreensNoBeatsAKeptYes() {
        Profile profile = new Profile(true, true).screen(false, true);
        assertEquals(Relation.DOESNT_FOLLOW_YOU, FriendshipStatus.relation(profile));
        assertEquals("Doesn't follow you", FriendshipStatus.label(profile));
    }

    /** Following each other goes by the screen too, and by the kept status where the screen doesn't say. */
    @Test
    public void whetherYouFollowThemGoesByTheScreenFirst() {
        assertEquals(Relation.FOLLOWING_EACH_OTHER, FriendshipStatus.relation(new Profile(false, false).screen(true, true)));
        assertEquals(Relation.FOLLOWS_YOU, FriendshipStatus.relation(new Profile(false, true).screen(true, false)));
        assertEquals(Relation.FOLLOWING_EACH_OTHER, FriendshipStatus.relation(new Profile(false, true).screen(true, null)));
    }

    /** Without a screen answer it's the order it always was: the kept status, then the account's own field. */
    @Test
    public void withoutAnAnswerTheKeptStatusDecides() {
        assertEquals(Relation.DOESNT_FOLLOW_YOU, FriendshipStatus.relation(new Profile(false, null)));
        assertEquals(Relation.FOLLOWING_EACH_OTHER, FriendshipStatus.relation(new Profile(true, true)));
        Profile ownField = new Profile(null, null);
        ownField.own = true;
        assertEquals(Relation.FOLLOWS_YOU, FriendshipStatus.relation(ownField));
        assertNull("nobody has said", FriendshipStatus.relation(new Profile(null, null)));
    }

    /** A screen answer whose friendship status doesn't say whether they follow you counts as none. */
    @Test
    public void anAnswerThatDoesntSayCountsAsNone() {
        assertEquals(Relation.DOESNT_FOLLOW_YOU, FriendshipStatus.relation(new Profile(false, null).screen(null, true)));
    }

    /** A screen answer that can't be read leaves the kept status to decide, and the hooks don't throw. */
    @Test
    public void anAnswerThatThrowsLeavesTheKeptStatus() {
        Profile profile = new Profile(false, null).screen(true, null);
        profile.screenThrows = true;
        assertEquals(Relation.DOESNT_FOLLOW_YOU, FriendshipStatus.relation(profile));
        Profile flag = new Profile(true, null).screen(false, null);
        flag.flagThrows = true;
        assertEquals(Relation.FOLLOWS_YOU, FriendshipStatus.relation(flag));
    }

    @Test
    public void yourOwnProfileHasNoAnswer() {
        Profile own = new Profile(false, null).screen(true, null);
        own.viewer = own.id;
        assertNull(FriendshipStatus.relation(own));
    }

    @Test
    public void withTheSwitchOffTheScreenIsntAsked() {
        Settings.SHOW_FRIENDSHIP_STATUS.save(false);
        assertNull(FriendshipStatus.relation(new Profile(false, null).screen(true, null)));
    }

    /** A profile header that is its own user, with the status kept on it and the screen's answer. */
    static final class Profile {
        final Boolean kept;
        final Boolean keptFollowing;
        Boolean own;
        Map<Integer, Boolean> screen;
        boolean screenThrows;
        boolean flagThrows;
        final String id = "51234567";
        String viewer = "1";

        Profile(Boolean kept, Boolean keptFollowing) {
            this.kept = kept;
            this.keptFollowing = keptFollowing;
        }

        /** Gives the profile screen an answer whose friendship status says these, where they aren't null. */
        Profile screen(Boolean followedBy, Boolean following) {
            screen = new HashMap<>();
            if (followedBy != null) screen.put("followed_by".hashCode(), followedBy);
            if (following != null) screen.put("following".hashCode(), following);
            return this;
        }
    }

    /** The stubs as the patch fills them, over [Profile]. */
    @Implements(value = FriendshipStatus.class, isInAndroidSdk = false)
    public static class Patched {
        @Implementation protected static Object profileUser(Object header) { return header; }
        @Implementation protected static Boolean friendshipFollowedBy(Object user) { return ((Profile) user).kept; }
        @Implementation protected static Boolean friendshipFollowing(Object user) { return ((Profile) user).keptFollowing; }
        @Implementation protected static Boolean followedBy(Object user) { return ((Profile) user).own; }
        @Implementation protected static String userId(Object user) { return ((Profile) user).id; }
        @Implementation protected static String viewerId(Object header) { return ((Profile) header).viewer; }

        @Implementation
        protected static Object screenFriendship(Object header) {
            Profile profile = (Profile) header;
            if (profile.screenThrows) throw new IllegalStateException("the screen's tree went away");
            return profile.screen == null ? null : profile;
        }

        @Implementation
        protected static Boolean statusFlag(Object status, int key) {
            Profile profile = (Profile) status;
            if (profile.flagThrows) throw new IllegalStateException("no such field");
            return profile.screen.get(key);
        }
    }
}

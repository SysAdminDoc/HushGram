/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.download.ProfileHistory.Field;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * Profile history (#590, #611): off until switched on, silent the first time a profile is seen,
 * a toast naming what changed after that, old values listed newest change first, each picture
 * copied while its address still works, kept in one file, and cleared on request.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ProfileHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    private final List<String> told = new ArrayList<>();
    private final AtomicInteger fetches = new AtomicInteger();
    /** What the fake profile shows: id, username, bio, picture address. */
    private final String[] shown = {"", "", "", ""};

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ProfileHistory.forgetForTests();
        ProfileHistory.clear(context);
        swap(true);
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Settings.PROFILE_HISTORY.resetToDefault();
        ProfileHistory.clear(context);
        ProfileHistory.forgetForTests();
    }

    /** Swaps in the fake profile, the toast list and a fetch that gives a picture when [pictures]. */
    private void swap(boolean pictures) {
        ProfileHistory.reads = new ProfileHistory.Reads() {
            @Override public String id(Object user) {
                return shown[0];
            }

            @Override public String username(Object user) {
                return shown[1];
            }

            @Override public String bio(Object user) {
                return shown[2];
            }

            @Override public String picture(Object user) {
                return shown[3];
            }
        };
        ProfileHistory.say = told::add;
        ProfileHistory.fetch = (c, url, size) -> {
            fetches.incrementAndGet();
            return pictures ? Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) : null;
        };
    }

    private void show(String id, String username, String bio, String picture) {
        shown[0] = id;
        shown[1] = username;
        shown[2] = bio;
        shown[3] = picture;
    }

    private void open() throws Exception {
        ProfileHistory.seen(new Object());
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void itIsOffUntilSwitchedOnAndNotesNothingMeanwhile() throws Exception {
        assertFalse(Settings.PROFILE_HISTORY.get());
        assertFalse(ProfileHistory.on());
        show("1", "ann", "hello", "https://cdn.example/a.jpg");
        open();
        assertEquals(0, ProfileHistory.count(context));
        Settings.PROFILE_HISTORY.save(true);
        assertTrue(ProfileHistory.on());
    }

    @Test public void theFirstVisitIsSilentAndANewUsernameSaysTheOldOne() throws Exception {
        Settings.PROFILE_HISTORY.save(true);
        show("1", "ann", "hello", "https://cdn.example/a.jpg");
        open();
        assertEquals(1, ProfileHistory.count(context));
        assertTrue(told.isEmpty());
        open();
        assertTrue("the same profile again changes nothing", told.isEmpty());
        show("1", "ann.new", "hello", "https://cdn.example/a.jpg");
        open();
        assertEquals(Collections.singletonList("Used to be @ann"), told);
    }

    @Test public void eachKindOfChangeHasItsOwnToast() {
        assertNull(ProfileHistory.record(context, "2", "bo", "one", "https://cdn.example/p1.jpg", 1_000L));
        assertEquals("Their bio changed",
                ProfileHistory.record(context, "2", "bo", "two", "https://cdn.example/p1.jpg", 2_000L));
        assertEquals("New profile picture",
                ProfileHistory.record(context, "2", "bo", "two", "https://cdn.example/p2.jpg", 3_000L));
        assertEquals("New profile picture and bio",
                ProfileHistory.record(context, "2", "bo", "three", "https://cdn.example/p3.jpg", 4_000L));
        assertEquals("Used to be @bo",
                ProfileHistory.record(context, "2", "bea", "four", "https://cdn.example/p4.jpg", 5_000L));
    }

    @Test public void aMissingBioOrAnotherSizeOfTheSamePictureIsNoChange() {
        ProfileHistory.record(context, "3", "cy", "bio", "https://cdn.example/v/abc_n.jpg?stp=s150x150&oh=1", 1_000L);
        // Reached from a post, the profile can come with no bio yet, and Instagram asks for the same
        // picture at another size with another signature.
        assertNull(ProfileHistory.record(context, "3", "cy", null, "https://cdn.example/v/abc_n.jpg?stp=s320x320&oh=2", 2_000L));
        ProfileHistory.Account account = ProfileHistory.account(context, "3");
        assertEquals("bio", account.latest(Field.BIO).value);
        assertEquals("abc_n.jpg", account.latest(Field.PICTURE).value);
        assertTrue(ProfileHistory.past(context).isEmpty());
    }

    @Test public void theOldValuesComeNewestChangeFirstUnderTheUsernameNow() {
        ProfileHistory.record(context, "4", "dee", null, "", 1_000L);
        ProfileHistory.record(context, "5", "eve", "old bio", "", 1_500L);
        ProfileHistory.record(context, "4", "dee2", null, "", 2_000L);
        ProfileHistory.record(context, "5", "eve", "new bio", "", 3_000L);
        List<ProfileHistory.Past> past = ProfileHistory.past(context);
        assertEquals(2, past.size());
        assertEquals("eve", past.get(0).username);
        assertEquals(Field.BIO, past.get(0).entry.field);
        assertEquals("old bio", past.get(0).entry.value);
        assertEquals(3_000L, past.get(0).until);
        assertEquals("dee2", past.get(1).username);
        assertEquals("dee", past.get(1).entry.value);
        assertEquals(2_000L, past.get(1).until);
    }

    @Test public void onlyTheNewestTwentyOfEachKindAreKept() {
        for (int i = 0; i < ProfileHistory.MAX_PER_FIELD + 5; i++) {
            ProfileHistory.record(context, "6", "fay" + i, "same", "", 1_000L * (i + 1));
        }
        ProfileHistory.Account account = ProfileHistory.account(context, "6");
        int usernames = 0;
        for (ProfileHistory.Entry entry : account.entries) if (entry.field == Field.USERNAME) usernames++;
        assertEquals(ProfileHistory.MAX_PER_FIELD, usernames);
        assertEquals("fay" + (ProfileHistory.MAX_PER_FIELD + 4), account.username());
        assertEquals(ProfileHistory.MAX_PER_FIELD - 1, ProfileHistory.past(context).size());
    }

    @Test public void thePictureIsCopiedOnceAndAFailedCopyIsTriedAgainNextVisit() {
        swap(false);
        ProfileHistory.record(context, "7", "gus", null, "https://cdn.example/g.jpg", 1_000L);
        assertEquals(1, fetches.get());
        ProfileHistory.Entry picture = ProfileHistory.account(context, "7").latest(Field.PICTURE);
        assertFalse(ProfileHistory.pictureFile(context, "7", picture).isFile());
        swap(true);
        ProfileHistory.record(context, "7", "gus", null, "https://cdn.example/g.jpg", 2_000L);
        assertEquals(2, fetches.get());
        assertTrue(ProfileHistory.pictureFile(context, "7", picture).isFile());
        ProfileHistory.record(context, "7", "gus", null, "https://cdn.example/g.jpg", 3_000L);
        assertEquals("a kept copy isn't fetched again", 2, fetches.get());
    }

    @Test public void theListSurvivesARestartAndClearWipesItThePicturesAndTheFile() {
        ProfileHistory.record(context, "8", "hal", "a", "https://cdn.example/h1.jpg", 1_000L);
        ProfileHistory.record(context, "8", "hal", "b", "https://cdn.example/h2.jpg", 2_000L);
        File file = new File(new File(context.getFilesDir(), "hushgram-profiles"), "history.json");
        assertTrue(file.isFile());
        File[] pictures = ProfileHistory.pictures(context).listFiles();
        assertEquals(2, pictures == null ? 0 : pictures.length);

        ProfileHistory.forgetForTests();
        assertEquals(2, ProfileHistory.past(context).size());

        assertTrue(ProfileHistory.clear(context));
        assertFalse(file.exists());
        pictures = ProfileHistory.pictures(context).listFiles();
        assertEquals(0, pictures == null ? 0 : pictures.length);
        assertEquals(0, ProfileHistory.count(context));
        ProfileHistory.forgetForTests();
        assertEquals(0, ProfileHistory.count(context));
    }
}

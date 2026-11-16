/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.download.ProfileHistory.Field;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * Profile history (#590, #611): off until switched on, silent the first time a profile is seen,
 * a toast naming what changed after that, old values listed newest change first, each picture
 * copied while its address still works, at the full size Instagram loaded when it loaded one,
 * kept in one file, saved to the gallery as it was kept, and cleared on request.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 30)
public class ProfileHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    private final List<String> told = new ArrayList<>();
    private final AtomicInteger fetches = new AtomicInteger();
    /** What the fake profile shows: id, username, bio, picture address. */
    private final String[] shown = {"", "", "", ""};
    /** The full size picture the fake profile loaded, and its width, when it loaded one. */
    private String fullShown = "";
    private int fullWidthShown;
    /** Each address the fake fetch was asked for, in order. */
    private final List<String> asked = new ArrayList<>();
    /** How wide a picture each address answers with, 4 pixels for any other. */
    private final Map<String, Integer> widths = new HashMap<>();
    /** Addresses whose fetch fails. */
    private final Set<String> failing = new HashSet<>();
    /** The bytes each address answered with last. */
    private final Map<String, byte[]> sent = new HashMap<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ProfileHistory.forgetForTests();
        ProfileHistory.clear(context);
        swap(true);
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Settings.PROFILE_HISTORY.resetToDefault();
        Settings.PROFILE_HISTORY_SAVE_PICTURES.resetToDefault();
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

            @Override public String fullPicture(Object user) {
                return fullShown;
            }

            @Override public int fullPictureWidth(Object user) {
                return fullWidthShown;
            }
        };
        ProfileHistory.say = told::add;
        ProfileHistory.fetch = (c, url, into) -> {
            fetches.incrementAndGet();
            asked.add(url);
            if (!pictures || failing.contains(url)) return false;
            Integer side = widths.get(url);
            Bitmap picture = Bitmap.createBitmap(side == null ? 4 : side, side == null ? 4 : side, Bitmap.Config.ARGB_8888);
            picture.eraseColor(0xff336699);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(picture.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            sent.put(url, bytes.toByteArray());
            try {
                Files.write(into.toPath(), bytes.toByteArray());
                return true;
            } catch (java.io.IOException failure) {
                return false;
            }
        };
    }

    private File kept(String id) {
        return ProfileHistory.pictureFile(context, id, ProfileHistory.account(context, id).latest(Field.PICTURE));
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

    /**
     * #611: a profile first reached from a post can come with no bio and no picture. The ones its own
     * page shows next have nothing older, so they're no change: no toast, and nothing on the page.
     */
    @Test public void aBioOrPictureFirstSeenOnALaterVisitIsNoChange() {
        assertNull(ProfileHistory.record(context, "10", "ivy", null, "", 1_000L));
        assertNull("a bio where none was seen yet", ProfileHistory.record(context, "10", "ivy", "hi", "", 2_000L));
        assertNull("a picture where none was seen yet",
                ProfileHistory.record(context, "10", "ivy", "hi", "https://cdn.example/i1.jpg", 3_000L));
        assertTrue(ProfileHistory.past(context).isEmpty());
        assertEquals("Their bio changed", ProfileHistory.record(context, "10", "ivy", "bye", "https://cdn.example/i1.jpg", 4_000L));
        assertEquals("New profile picture", ProfileHistory.record(context, "10", "ivy", "bye", "https://cdn.example/i2.jpg", 5_000L));
        assertEquals(2, ProfileHistory.past(context).size());
    }

    /** #611: with the full size loaded, that's the copy kept, byte for byte, and never the size shown. */
    @Test public void theFullSizeIsKeptAsItCameRatherThanTheSizeShown() throws Exception {
        Settings.PROFILE_HISTORY.save(true);
        String small = "https://cdn.example/v/k_n.jpg?stp=s150x150&oh=1";
        String full = "https://cdn.example/v/k_n.jpg?oh=2";
        widths.put(small, 150);
        widths.put(full, 640);
        show("12", "kim", "", small);
        fullShown = full;
        fullWidthShown = 640;
        open();
        assertEquals(Collections.singletonList(full), asked);
        assertEquals(640, PictureFetch.width(kept("12")));
        assertArrayEquals("as it came, never compressed again", sent.get(full), Files.readAllBytes(kept("12").toPath()));
        open();
        assertEquals("a full size copy isn't fetched again", 1, fetches.get());
        assertTrue(told.isEmpty());
    }

    /**
     * #611: a copy kept at the size shown, from a profile reached from a post, is replaced by the
     * full size once a later visit loads it. A width the answer doesn't have is tried once a process.
     */
    @Test public void aSmallerCopyIsReplacedOnceTheFullSizeTurnsUp() throws Exception {
        String small = "https://cdn.example/v/m_n.jpg?stp=s150x150&oh=1";
        String other = "https://cdn.example/v/m_n.jpg?stp=s320x320&oh=2";
        String full = "https://cdn.example/v/m_n.jpg?oh=3";
        widths.put(small, 150);
        widths.put(full, 640);
        assertNull(ProfileHistory.record(context, "13", "max", null, small, "", 0, 1_000L));
        assertEquals(150, PictureFetch.width(kept("13")));
        assertNull("the same picture at its full size is no change",
                ProfileHistory.record(context, "13", "max", null, other, full, 640, 2_000L));
        assertEquals(Arrays.asList(small, full), asked);
        assertEquals(640, PictureFetch.width(kept("13")));
        assertArrayEquals(sent.get(full), Files.readAllBytes(kept("13").toPath()));
        ProfileHistory.record(context, "13", "max", null, other, full, 640, 3_000L);
        assertEquals("a copy as wide as the full size isn't fetched again", 2, fetches.get());

        ProfileHistory.record(context, "13", "max", null, other, full, 1080, 4_000L);
        assertEquals(3, fetches.get());
        assertEquals("an answer no wider than the copy doesn't replace it", 640, PictureFetch.width(kept("13")));
        ProfileHistory.record(context, "13", "max", null, other, full, 1080, 5_000L);
        assertEquals("tried once a process", 3, fetches.get());
        assertTrue(ProfileHistory.past(context).isEmpty());
        File[] left = ProfileHistory.pictures(context).listFiles((folder, name) -> name.endsWith(".part"));
        assertEquals("no work file stays", 0, left == null ? 0 : left.length);
    }

    /** #611: when the full size can't be fetched, the size shown is kept, as before. */
    @Test public void aFullSizeThatFailsFallsBackToTheSizeShown() {
        String small = "https://cdn.example/v/n_n.jpg?stp=s150x150";
        String full = "https://cdn.example/v/n_n.jpg";
        widths.put(small, 150);
        failing.add(full);
        ProfileHistory.record(context, "14", "nia", null, small, full, 640, 1_000L);
        assertEquals(Arrays.asList(full, small), asked);
        assertEquals(150, PictureFetch.width(kept("14")));
        ProfileHistory.record(context, "14", "nia", null, small, full, 640, 2_000L);
        ProfileHistory.record(context, "14", "nia", null, small, full, 640, 3_000L);
        assertEquals("the full size is tried again once a process", Arrays.asList(full, small, full), asked);
        assertEquals(150, PictureFetch.width(kept("14")));
    }

    /** #611: Save picture puts the copy kept in the gallery byte for byte, named as a profile picture. */
    @Test public void anOldPictureSavesTheCopyKeptAsItIs() throws Exception {
        context.getApplicationInfo().targetSdkVersion = 37;
        MediaSaveTest.Gallery rows = Robolectric.setupContentProvider(MediaSaveTest.Gallery.class, MediaStore.AUTHORITY);
        ByteArrayOutputStream published = new ByteArrayOutputStream();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1), published);
        widths.put("https://cdn.example/s1.jpg", 64);
        ProfileHistory.record(context, "15", "oli", null, "https://cdn.example/s1.jpg", 1_000L);
        ProfileHistory.record(context, "15", "oli", null, "https://cdn.example/s2.jpg", 2_000L);
        List<ProfileHistory.Past> past = ProfileHistory.past(context);
        assertEquals(1, past.size());
        List<PostDetails> named = new ArrayList<>();
        List<MediaSave.Job> jobs = new ArrayList<>();
        ProfileHistory.gallery = (application, details, job) -> {
            named.add(details);
            jobs.add(job);
        };
        assertTrue(ProfileHistory.savePicture(context, past.get(0)));
        assertEquals(1, jobs.size());
        assertEquals("the username it has now names it", "oli", named.get(0).owner);
        assertTrue(named.get(0).profile);
        Downloader.Result result = jobs.get(0).run(new MediaStoreWriter(context, false, named.get(0)), Downloader.SILENT);
        assertTrue(result.toString(), result.ok());
        assertEquals(1, rows.inserts.size());
        ContentValues row = rows.rows.get(1L);
        assertEquals("image/png", row.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertArrayEquals(sent.get("https://cdn.example/s1.jpg"), published.toByteArray());
        assertTrue("the copy stays for the page", ProfileHistory.pictureFile(context, "15", past.get(0).entry).isFile());

        // With no copy kept there's nothing to save, and it says so.
        assertTrue(ProfileHistory.pictureFile(context, "15", past.get(0).entry).delete());
        assertFalse(ProfileHistory.savePicture(context, past.get(0)));
        assertEquals(1, jobs.size());
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals("Download failed", ShadowToast.getTextOfLatestToast());
    }

    /** Swaps in a gallery that keeps each save's name and job in [named] and [jobs]. */
    private void galleryInto(List<PostDetails> named, List<MediaSave.Job> jobs) {
        ProfileHistory.gallery = (application, details, job) -> {
            named.add(details);
            jobs.add(job);
        };
    }

    /**
     * #715: with Save changed pictures on, a new picture puts the old copy and the new one in the
     * gallery, byte for byte and named as a profile picture, and the next change only the new one.
     */
    @Test public void aChangedPictureSavesTheOldAndTheNewCopyEachOnce() throws Exception {
        context.getApplicationInfo().targetSdkVersion = 37;
        MediaSaveTest.Gallery rows = Robolectric.setupContentProvider(MediaSaveTest.Gallery.class, MediaStore.AUTHORITY);
        ByteArrayOutputStream first = new ByteArrayOutputStream(), second = new ByteArrayOutputStream();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1), first);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2), second);
        String one = "https://cdn.example/p1.jpg", two = "https://cdn.example/p2.jpg", three = "https://cdn.example/p3.jpg";
        widths.put(one, 48);
        widths.put(two, 64);
        List<PostDetails> named = new ArrayList<>();
        List<MediaSave.Job> jobs = new ArrayList<>();
        galleryInto(named, jobs);

        Settings.PROFILE_HISTORY_SAVE_PICTURES.save(true);
        assertNull(ProfileHistory.record(context, "16", "pia", "hi", one, 1_000L));
        assertTrue("a first sighting has nothing that changed", jobs.isEmpty());
        assertEquals("Their bio changed", ProfileHistory.record(context, "16", "pia", "bye", one, 2_000L));
        assertEquals("Used to be @pia", ProfileHistory.record(context, "16", "pia.b", "bye", one, 3_000L));
        assertTrue("only a picture that changed goes", jobs.isEmpty());

        assertEquals("New profile picture", ProfileHistory.record(context, "16", "pia.b", "bye", two, 4_000L));
        assertEquals("the old picture and the new one", 2, jobs.size());
        for (int at = 0; at < 2; at++) {
            assertEquals("the username it has now names it", "pia.b", named.get(at).owner);
            assertTrue(named.get(at).profile);
            Downloader.Result result = jobs.get(at).run(new MediaStoreWriter(context, false, named.get(at)), Downloader.SILENT);
            assertTrue(result.toString(), result.ok());
        }
        assertEquals(2, rows.inserts.size());
        assertArrayEquals("the old copy, as it was kept", sent.get(one), first.toByteArray());
        assertArrayEquals("then the new one", sent.get(two), second.toByteArray());

        assertEquals("New profile picture", ProfileHistory.record(context, "16", "pia.b", "bye", three, 5_000L));
        assertEquals("only the new picture, the old one went already", 3, jobs.size());

        // What went to the gallery stays noted across a restart, and a picture it goes back to isn't saved again.
        ProfileHistory.forgetForTests();
        swap(true);
        galleryInto(named, jobs);
        assertEquals("New profile picture", ProfileHistory.record(context, "16", "pia.b", "bye", two, 6_000L));
        assertEquals(3, jobs.size());
        assertNull(ProfileHistory.record(context, "16", "pia.b", "bye", two, 7_000L));
        assertEquals(3, jobs.size());
    }

    /** #715: with the switch off, which is how it starts, a new picture goes nowhere but the history. */
    @Test public void aChangedPictureStaysOutOfTheGalleryWithTheSwitchOff() throws Exception {
        assertFalse(Settings.PROFILE_HISTORY_SAVE_PICTURES.get());
        List<PostDetails> named = new ArrayList<>();
        List<MediaSave.Job> jobs = new ArrayList<>();
        galleryInto(named, jobs);
        ProfileHistory.record(context, "17", "quin", null, "https://cdn.example/q1.jpg", 1_000L);
        assertEquals("New profile picture",
                ProfileHistory.record(context, "17", "quin", null, "https://cdn.example/q2.jpg", 2_000L));
        assertTrue(jobs.isEmpty());

        // Turned on later, the next change takes the picture that was replaced too, since none went yet.
        Settings.PROFILE_HISTORY_SAVE_PICTURES.save(true);
        // A copy that can't be had is left out, and the one that can still goes.
        failing.add("https://cdn.example/q3.jpg");
        assertEquals("New profile picture",
                ProfileHistory.record(context, "17", "quin", null, "https://cdn.example/q3.jpg", 3_000L));
        assertEquals(1, jobs.size());
        assertFalse(ProfileHistory.pictureFile(context, "17",
                ProfileHistory.account(context, "17").latest(Field.PICTURE)).isFile());
    }
}

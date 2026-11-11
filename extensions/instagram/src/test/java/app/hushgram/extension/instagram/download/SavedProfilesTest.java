/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Saved profiles (#629): off until switched on, a Save profile row in the menu on someone's profile
 * that turns into Remove from saved once it's saved, a toast for each, the newest first, kept in one
 * file across restarts, brought up to date when a saved profile opens again, and searched by username
 * or name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SavedProfilesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    private final AtomicInteger fetches = new AtomicInteger();
    /** What the fake profile shows: id, username, name, picture address. */
    private final String[] shown = {"", "", "", ""};

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        SavedProfiles.clearForTests(context);
        restart();
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Settings.SAVED_PROFILES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        SavedProfiles.clearForTests(context);
        SavedProfiles.forgetForTests();
    }

    /** Forgets what the process holds, as a new process would, with the fake profile and fetch in place. */
    private void restart() {
        SavedProfiles.forgetForTests();
        SavedProfiles.reads = new SavedProfiles.Reads() {
            @Override public String id(Object user) {
                return shown[0];
            }

            @Override public String username(Object user) {
                return shown[1];
            }

            @Override public String name(Object user) {
                return shown[2];
            }

            @Override public String picture(Object user) {
                return shown[3];
            }
        };
        SavedProfiles.fetch = (c, url, size) -> {
            fetches.incrementAndGet();
            return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        };
    }

    private void show(String id, String username, String name, String picture) {
        shown[0] = id;
        shown[1] = username;
        shown[2] = name;
        shown[3] = picture;
    }

    private List<String> usernames() {
        List<String> names = new ArrayList<>();
        for (SavedProfiles.Profile profile : SavedProfiles.all(context)) names.add(profile.username);
        return names;
    }

    private static SavedProfiles.Profile profile(String id, String username, String name) {
        return new SavedProfiles.Profile(id, username, name, "", 0);
    }

    /** The rows the profile's menu gets, by label, for an account whose username is [username]. */
    private Map<String, View.OnClickListener> menu(String username) {
        Map<String, View.OnClickListener> rows = new LinkedHashMap<>();
        for (ProfilePicture.Entry entry : ProfilePicture.entries(new Object(), context, new Account(username),
                (at, sizes, details) -> true, (at, sizes, owner, save) -> true)) {
            rows.put(entry.label, entry.click);
        }
        return rows;
    }

    private static String toast() {
        ShadowLooper.idleMainLooper();
        return ShadowToast.getTextOfLatestToast();
    }

    @Test public void itIsOffUntilSwitchedOnAndTheMenuHasNoRowMeanwhile() throws Exception {
        assertFalse(Settings.SAVED_PROFILES.get());
        assertFalse(SavedProfiles.on());
        show("1", "ann", "Ann Lee", "");
        assertFalse(menu("ann").containsKey("Save profile"));
        assertNull(SavedProfiles.row(context, new Object(), "ann"));
        SavedProfiles.seen(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertTrue(SavedProfiles.all(context).isEmpty());
        Settings.SAVED_PROFILES.save(true);
        assertTrue(SavedProfiles.on());
    }

    @Test public void pausedTheMenuHasNoRow() {
        Settings.SAVED_PROFILES.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        show("1", "ann", "", "");
        assertFalse(menu("ann").containsKey("Save profile"));
    }

    /** The row saves an account you don't follow as well as one you do: nothing asks Instagram about it. */
    @Test public void theMenuRowSavesThenRemovesWithAToastForEach() throws Exception {
        Settings.SAVED_PROFILES.save(true);
        show("17", "ann", "Ann Lee", "https://cdn.example/v/ann_n.jpg?stp=s150x150");
        Map<String, View.OnClickListener> rows = menu("ann");
        assertTrue(rows.keySet().toString(), rows.containsKey("Save profile"));
        assertFalse(rows.containsKey("Remove from saved"));
        rows.get("Save profile").onClick(null);
        assertEquals("Saved @ann", toast());
        assertEquals(Arrays.asList("ann"), usernames());
        SavedProfiles.Profile saved = SavedProfiles.all(context).get(0);
        assertEquals("17", saved.id);
        assertEquals("Ann Lee", saved.name);
        assertTrue(saved.time > 0);

        rows = menu("ann");
        assertFalse("once saved, the menu offers the other way", rows.containsKey("Save profile"));
        assertTrue(rows.containsKey("Remove from saved"));
        rows.get("Remove from saved").onClick(null);
        assertEquals("Removed @ann from saved", toast());
        assertTrue(SavedProfiles.all(context).isEmpty());
        assertTrue(menu("ann").containsKey("Save profile"));
    }

    /** A menu left open while the account was saved elsewhere still does what its label said. */
    @Test public void aRowDoesWhatItsLabelSaidAndNothingOnceSwitchedOff() throws Exception {
        Settings.SAVED_PROFILES.save(true);
        show("5", "bo", "", "");
        View.OnClickListener save = menu("bo").get("Save profile");
        View.OnClickListener again = menu("bo").get("Save profile");
        save.onClick(null);
        again.onClick(null);
        assertEquals("a second Save leaves one entry", 1, SavedProfiles.all(context).size());
        View.OnClickListener remove = menu("bo").get("Remove from saved");
        Settings.SAVED_PROFILES.save(false);
        ShadowLooper.idleMainLooper();
        ShadowToast.reset();
        remove.onClick(null);
        ShadowLooper.idleMainLooper();
        assertEquals("switched off, a tap does nothing", 1, SavedProfiles.all(context).size());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test public void theRowNeedsAUsername() {
        Settings.SAVED_PROFILES.save(true);
        show("6", "", "", "");
        assertNull("no username, nothing to open it by", SavedProfiles.row(context, new Object(), ""));
        assertNull(SavedProfiles.row(context, new Object(), null));
        assertNotNull(SavedProfiles.row(context, new Object(), "cy"));
    }

    @Test public void theNewestIsFirstAndAnAccountIsSavedOnce() {
        SavedProfiles.save(context, profile("1", "ann", ""), 1_000L);
        SavedProfiles.save(context, profile("2", "bo", ""), 2_000L);
        SavedProfiles.save(context, profile("3", "cy", ""), 3_000L);
        assertEquals(Arrays.asList("cy", "bo", "ann"), usernames());
        assertTrue(SavedProfiles.save(context, profile("1", "ann", ""), 4_000L));
        assertEquals("saving one already there leaves it where it was", Arrays.asList("cy", "bo", "ann"), usernames());
        assertEquals(1_000L, SavedProfiles.all(context).get(2).time);
        // By id when both have one, so a renamed account is the same account.
        SavedProfiles.save(context, profile("2", "bo.renamed", ""), 5_000L);
        assertEquals(3, SavedProfiles.all(context).size());
        // By username, in any case, when the build couldn't read an id.
        SavedProfiles.save(context, profile("", "CY", ""), 6_000L);
        assertEquals(3, SavedProfiles.all(context).size());
        assertTrue(SavedProfiles.remove(context, "", "Ann"));
        assertFalse(SavedProfiles.remove(context, "", "ann"));
        assertEquals(Arrays.asList("cy", "bo"), usernames());
    }

    @Test public void theListIsKeptAcrossRestarts() throws Exception {
        SavedProfiles.save(context, new SavedProfiles.Profile("1", "ann", "Ann Lee", "https://cdn.example/a_n.jpg", 0), 1_000L);
        SavedProfiles.save(context, profile("", "bo", ""), 2_000L);
        Utils.awaitBackgroundTasksForTests();
        restart();
        List<SavedProfiles.Profile> read = SavedProfiles.all(context);
        assertEquals(2, read.size());
        assertEquals("bo", read.get(0).username);
        assertEquals("", read.get(0).id);
        assertEquals(2_000L, read.get(0).time);
        assertEquals("1", read.get(1).id);
        assertEquals("ann", read.get(1).username);
        assertEquals("Ann Lee", read.get(1).name);
        assertEquals("https://cdn.example/a_n.jpg", read.get(1).url);
        assertEquals(1_000L, read.get(1).time);

        SavedProfiles.remove(context, "1", "ann");
        Utils.awaitBackgroundTasksForTests();
        restart();
        assertEquals(Arrays.asList("bo"), usernames());
    }

    @Test public void aFullListSavesNoMoreAndSaysSo() throws Exception {
        JSONArray full = new JSONArray();
        for (int at = 0; at < SavedProfiles.MAX_PROFILES; at++) {
            full.put(new JSONObject().put("id", String.valueOf(at)).put("username", "user" + at).put("time", at));
        }
        File file = new File(new File(context.getFilesDir(), "hushgram-saved"), "profiles.json");
        assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
        Files.write(file.toPath(), full.toString().getBytes(StandardCharsets.UTF_8));
        restart();
        assertEquals(SavedProfiles.MAX_PROFILES, SavedProfiles.all(context).size());
        assertFalse(SavedProfiles.save(context, profile("x", "one.more", ""), 1L));
        assertTrue("one already there still counts as saved", SavedProfiles.save(context, profile("7", "user7", ""), 1L));

        Settings.SAVED_PROFILES.save(true);
        show("x", "one.more", "", "");
        menu("one.more").get("Save profile").onClick(null);
        assertEquals("Saved profiles is full.", toast());
        assertEquals(SavedProfiles.MAX_PROFILES, SavedProfiles.all(context).size());
    }

    @Test public void openingASavedProfileBringsItUpToDate() throws Exception {
        Settings.SAVED_PROFILES.save(true);
        SavedProfiles.save(context, new SavedProfiles.Profile("1", "ann", "Ann", "https://cdn.example/v/one_n.jpg", 0), 1_000L);
        SavedProfiles.save(context, profile("", "bo", "Bo"), 2_000L);
        Utils.awaitBackgroundTasksForTests();

        show("1", "ann.new", "Ann New", "https://cdn.example/v/two_n.jpg?stp=s150x150");
        SavedProfiles.seen(new Object());
        Utils.awaitBackgroundTasksForTests();
        SavedProfiles.Profile ann = SavedProfiles.all(context).get(1);
        assertEquals("ann.new", ann.username);
        assertEquals("Ann New", ann.name);
        assertEquals("https://cdn.example/v/two_n.jpg?stp=s150x150", ann.url);
        assertEquals("its place on the list stays", 1_000L, ann.time);
        assertTrue("the new picture was copied", SavedProfiles.pictureFile(context, ann).isFile());

        // Reached from a post, a profile can come with no name or picture yet: the saved ones stay.
        show("", "bo", "", "");
        SavedProfiles.seen(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertEquals("Bo", SavedProfiles.all(context).get(0).name);
        // An id read later is kept, and the account is known by it from then on.
        show("22", "bo", "", "");
        SavedProfiles.seen(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertEquals("22", SavedProfiles.all(context).get(0).id);

        show("3", "cy", "Cy", "");
        SavedProfiles.seen(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertEquals("a profile that isn't saved stays off the list", 2, SavedProfiles.all(context).size());

        restart();
        assertEquals("and it's all in the file", Arrays.asList("bo", "ann.new"), usernames());
        assertEquals("22", SavedProfiles.all(context).get(0).id);
    }

    /** Native graphics, so the copy on the phone is a real JPEG that decodes. */
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void thePictureIsCopiedOnceAndGoesWithTheAccount() throws Exception {
        SavedProfiles.save(context, new SavedProfiles.Profile("1", "ann", "", "https://cdn.example/v/one_n.jpg", 0), 1_000L);
        Utils.awaitBackgroundTasksForTests();
        SavedProfiles.Profile ann = SavedProfiles.all(context).get(0);
        File kept = SavedProfiles.pictureFile(context, ann);
        assertTrue(kept.isFile());
        assertEquals(1, fetches.get());
        assertNotNull(SavedProfiles.picture(context, ann, 48));
        assertEquals("the copy is read, not fetched again", 1, fetches.get());
        SavedProfiles.remove(context, "1", "ann");
        Utils.awaitBackgroundTasksForTests();
        assertFalse("removed, its picture is deleted", kept.exists());
    }

    @Test public void searchGoesByUsernameOrNameInAnyCase() {
        List<SavedProfiles.Profile> all = Arrays.asList(profile("1", "ann.lee", "Ann Lee"),
                profile("2", "bo_photos", "Bo"), profile("3", "cy", ""));
        assertEquals(3, SavedProfiles.matching(all, "").size());
        assertEquals(3, SavedProfiles.matching(all, null).size());
        assertEquals(1, SavedProfiles.matching(all, "@ANN").size());
        assertEquals("ann.lee", SavedProfiles.matching(all, "lee").get(0).username);
        assertEquals("bo_photos", SavedProfiles.matching(all, " photos ").get(0).username);
        assertEquals("by the name too", "ann.lee", SavedProfiles.matching(all, "ann lee").get(0).username);
        assertTrue(SavedProfiles.matching(all, "nobody").isEmpty());
    }

    /** An account with a username and nothing else, as the menu reads it. */
    private static final class Account implements ProfilePicture.Native {
        private final String username;

        Account(String username) {
            this.username = username;
        }

        @Override public boolean addRow(Object sheet, Context context, View.OnClickListener listener, String label) {
            return false;
        }
        @Override public Object fullSize(Object user) { return null; }
        @Override public String fullSizeUrl(Object info) { return null; }
        @Override public int fullSizeWidth(Object info) { return 0; }
        @Override public int fullSizeHeight(Object info) { return 0; }
        @Override public Object shown(Object user) { return null; }
        @Override public String shownUrl(Object image) { return null; }
        @Override public int shownWidth(Object image) { return 0; }
        @Override public int shownHeight(Object image) { return 0; }
        @Override public String username(Object user) { return username; }
        @Override public String biography(Object user) { return null; }
    }
}

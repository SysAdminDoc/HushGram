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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
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
        return menu(context, username);
    }

    private static Map<String, View.OnClickListener> menu(Context context, String username) {
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

    /** Under Remove from saved, once something is saved, a row opens the list over the profile (#629). */
    @Test public void theMenuOpensTheListOnceSomethingIsSaved() throws Exception {
        Settings.SAVED_PROFILES.save(true);
        show("17", "ann", "", "");
        assertFalse("nothing saved, nothing to open", menu("ann").containsKey("Your saved profiles"));
        menu("ann").get("Save profile").onClick(null);
        List<String> labels = new ArrayList<>(menu("ann").keySet());
        assertEquals(labels.toString(), labels.indexOf("Remove from saved") + 1, labels.indexOf("Your saved profiles"));
        show("18", "bo", "", "");
        assertTrue("any profile's menu has it", menu("bo").containsKey("Your saved profiles"));

        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            menu(controller.get(), "bo").get("Your saved profiles").onClick(null);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            Dialog page = ShadowDialog.getLatestDialog();
            assertNotNull(page);
            assertTrue(page.isShowing());
            page.dismiss();
        }
        Settings.SAVED_PROFILES.save(false);
        assertFalse(menu("bo").containsKey("Your saved profiles"));
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

    /** A folder's name is cleaned as typed: one space between words, no control characters, at most 30 (#629). */
    @Test public void aFolderNameIsCleanedAsTyped() {
        assertEquals("Close friends", SavedProfiles.folderName("  Close \n\t friends  "));
        assertEquals("ab", SavedProfiles.folderName("a" + (char) 7 + "b"));
        assertEquals("", SavedProfiles.folderName("   "));
        assertEquals("", SavedProfiles.folderName(null));
        StringBuilder long_ = new StringBuilder();
        for (int at = 0; at < 40; at++) long_.append('x');
        assertEquals(SavedProfiles.MAX_FOLDER, SavedProfiles.folderName(long_).length());
        String face = new String(Character.toChars(0x1F600));
        String nearlyFull = long_.substring(0, SavedProfiles.MAX_FOLDER - 1);
        assertEquals("a character that doesn't fit whole is left off, not split",
                nearlyFull, SavedProfiles.folderName(nearlyFull + face));
    }

    @Test public void foldersAreListedOnceWhateverTheirCase() {
        List<SavedProfiles.Profile> all = Arrays.asList(
                new SavedProfiles.Profile("1", "ann", "", "", "Work", 0),
                new SavedProfiles.Profile("2", "bo", "", "", "friends", 0),
                profile("3", "cy", ""),
                new SavedProfiles.Profile("4", "dee", "", "", "Friends", 0),
                new SavedProfiles.Profile("5", "eve", "", "", Arrays.asList("work", " Gym ", "WORK", ""), 0));
        assertEquals("each of an account's folders once, alphabetically", Arrays.asList("Gym", "work"), all.get(4).folders);
        assertEquals(Arrays.asList("friends", "Gym", "Work"), SavedProfiles.folders(all));
        assertEquals(all, SavedProfiles.inFolder(all, null));
        assertEquals(Arrays.asList("bo", "dee"), usernames(SavedProfiles.inFolder(all, "FRIENDS")));
        assertEquals("an account shows in each of its folders", Arrays.asList("ann", "eve"),
                usernames(SavedProfiles.inFolder(all, "work")));
        assertEquals(Arrays.asList("eve"), usernames(SavedProfiles.inFolder(all, "gym")));
        assertTrue(SavedProfiles.inFolder(all, "Home").isEmpty());
    }

    private static List<String> usernames(List<SavedProfiles.Profile> profiles) {
        List<String> usernames = new ArrayList<>();
        for (SavedProfiles.Profile profile : profiles) usernames.add(profile.username);
        return usernames;
    }

    /**
     * Putting an account in folders, several at once (#687), renaming one and taking one away keep
     * every account saved, and the file has it all.
     */
    @Test public void foldersPutInRenameAndGoAwayWithTheAccountsKept() throws Exception {
        SavedProfiles.save(context, profile("1", "ann", ""), 1_000L);
        SavedProfiles.save(context, profile("2", "bo", ""), 2_000L);
        SavedProfiles.save(context, profile("3", "cy", ""), 3_000L);
        assertEquals(Arrays.asList("Close friends"),
                SavedProfiles.putIn(context, "1", "ann", Arrays.asList("  Close   friends ")).folders);
        assertEquals("a name in another case is that folder, and an account sits in several",
                Arrays.asList("Close friends", "Work"),
                SavedProfiles.putIn(context, "2", "bo", Arrays.asList("Work", "close FRIENDS", "")).folders);
        assertNull("not saved, nothing moves", SavedProfiles.putIn(context, "9", "nobody", Arrays.asList("Work")));
        Utils.awaitBackgroundTasksForTests();
        restart();
        assertEquals(Arrays.asList("Close friends", "Work"), SavedProfiles.folders(SavedProfiles.all(context)));
        assertEquals("the file kept both", Arrays.asList("Close friends", "Work"), SavedProfiles.all(context).get(1).folders);
        assertTrue(SavedProfiles.all(context).get(0).folders.isEmpty());

        assertEquals("Besties", SavedProfiles.renameFolder(context, "close friends", "Besties"));
        assertEquals("a rename in another case only", "besties", SavedProfiles.renameFolder(context, "Besties", "besties"));
        assertEquals("its other folder stays", Arrays.asList("besties", "Work"), SavedProfiles.all(context).get(1).folders);
        SavedProfiles.putIn(context, "3", "cy", Arrays.asList("Work"));
        assertEquals("renamed onto another folder, the two are one", "Work", SavedProfiles.renameFolder(context, "besties", "work"));
        assertEquals(Arrays.asList("Work"), SavedProfiles.folders(SavedProfiles.all(context)));
        assertEquals("in it once", Arrays.asList("Work"), SavedProfiles.all(context).get(1).folders);
        assertEquals(3, SavedProfiles.inFolder(SavedProfiles.all(context), "Work").size());
        SavedProfiles.putIn(context, "2", "bo", Arrays.asList("Work", "Gym"));

        assertEquals("", SavedProfiles.renameFolder(context, "Work", ""));
        assertEquals("taken out of one folder, kept in its other", Arrays.asList("Gym"), SavedProfiles.all(context).get(1).folders);
        assertEquals("", SavedProfiles.renameFolder(context, "Gym", ""));
        Utils.awaitBackgroundTasksForTests();
        restart();
        assertTrue(SavedProfiles.folders(SavedProfiles.all(context)).isEmpty());
        assertEquals("the folder went, its accounts stayed", Arrays.asList("cy", "bo", "ann"), usernames());
    }

    /** A list file from before #687 names one folder per account, and this build reads it. */
    @Test public void aListFileFromBeforeSeveralFoldersStillHasItsFolder() throws Exception {
        File file = new File(new File(context.getFilesDir(), "hushgram-saved"), "profiles.json");
        assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
        JSONArray old = new JSONArray()
                .put(new JSONObject().put("id", "1").put("username", "ann").put("name", "").put("url", "")
                        .put("time", 1_000L).put("folder", "Friends"));
        Files.write(file.toPath(), old.toString().getBytes(StandardCharsets.UTF_8));
        restart();
        assertEquals(Arrays.asList("Friends"), SavedProfiles.all(context).get(0).folders);

        SavedProfiles.putIn(context, "1", "ann", Arrays.asList("Friends", "Work"));
        Utils.awaitBackgroundTasksForTests();
        JSONObject written = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getJSONObject(0);
        assertEquals("what a HushGram from before #687 reads", "Friends", written.getString("folder"));
        assertEquals(2, written.getJSONArray("folders").length());
        restart();
        assertEquals(Arrays.asList("Friends", "Work"), SavedProfiles.all(context).get(0).folders);
    }

    /**
     * A settings file carries each account's folders, an older HushGram's one folder too, and an
     * import puts an account here into the file's folders as well, spelled as this phone does (#687).
     */
    @Test public void aBackupCarriesTheFoldersAndAnImportJoinsTheOnesHere() throws Exception {
        SavedProfiles.save(context, new SavedProfiles.Profile("1", "ann", "", "", Arrays.asList("Gym", "Friends"), 0), 1_000L);
        SavedProfiles.save(context, profile("2", "bo", ""), 2_000L);
        JSONArray backup = SavedProfiles.backup(context, false);
        assertFalse("no folder, no field", backup.getJSONObject(0).has("folder"));
        assertFalse(backup.getJSONObject(0).has("folders"));
        assertEquals("what a HushGram from before #687 reads", "Friends", backup.getJSONObject(1).getString("folder"));
        assertEquals("Gym", backup.getJSONObject(1).getJSONArray("folders").getString(1));
        assertEquals(Arrays.asList("Friends", "Gym"), SavedProfiles.fromBackup(backup.getJSONObject(1)).folders);

        SavedProfiles.Profile cy = SavedProfiles.fromBackup(
                new JSONObject().put("id", "3").put("username", "cy").put("folder", "  friends\n").put("time", 3_000L));
        assertEquals("a file from before #687", Arrays.asList("friends"), cy.folders);
        SavedProfiles.Profile dee = SavedProfiles.fromBackup(new JSONObject().put("id", "4").put("username", "dee")
                .put("folder", 7).put("folders", "Gym").put("time", 4_000L));
        assertNotNull("folders that aren't text are dropped, the account kept", dee);
        assertTrue(dee.folders.isEmpty());
        JSONArray many = new JSONArray().put(" Work ").put(5).put("work").put("Gym");
        for (int at = 0; at < 40; at++) many.put("F" + at);
        SavedProfiles.Profile eve = SavedProfiles.fromBackup(
                new JSONObject().put("id", "5").put("username", "eve").put("folders", many).put("time", 5_000L));
        assertEquals("each once, and no more than the most", SavedProfiles.MAX_FOLDERS, eve.folders.size());
        assertTrue(eve.isIn("WORK"));
        assertTrue(eve.isIn("gym"));

        SavedProfiles.Profile annElsewhere = new SavedProfiles.Profile("1", "ann", "", "", Arrays.asList("work", "gym"), 500L);
        SavedProfiles.Merged merged = SavedProfiles.merge(context, Arrays.asList(cy, dee, annElsewhere));
        assertEquals(2, merged.added.size());
        assertEquals("an account here goes into the file's folders too", 1, merged.filed.size());
        Map<String, List<String>> folders = new LinkedHashMap<>();
        for (SavedProfiles.Profile profile : SavedProfiles.all(context)) folders.put(profile.username, profile.folders);
        assertEquals(Arrays.asList("Friends"), folders.get("cy"));
        assertTrue(folders.get("dee").isEmpty());
        assertEquals("its own folders kept, the file's added, spelled as here", Arrays.asList("Friends", "Gym", "work"),
                folders.get("ann"));
        assertEquals(Arrays.asList("Friends", "Gym", "work"), SavedProfiles.folders(SavedProfiles.all(context)));

        assertEquals(2, SavedProfiles.takeBack(context, merged));
        assertEquals("Undo takes the file's folder back off", Arrays.asList("Friends", "Gym"),
                SavedProfiles.find(context, "1", "ann").folders);
        assertEquals(Arrays.asList("bo", "ann"), usernames());
    }

    /**
     * Once there's a folder, Save profile asks which ones with tick boxes, New folder included, and
     * says where it went (#629, #687).
     */
    @Test public void withAFolderSaveAsksWhichOnes() throws Exception {
        Settings.SAVED_PROFILES.save(true);
        SavedProfiles.save(context, new SavedProfiles.Profile("1", "ann", "", "", Arrays.asList("Friends", "Work"), 0), 1_000L);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            show("2", "bo", "", "");
            menu(controller.get(), "bo").get("Save profile").onClick(null);
            ShadowLooper.idleMainLooper();
            AlertDialog choose = (AlertDialog) ShadowDialog.getLatestDialog();
            ListAdapter items = choose.getListView().getAdapter();
            assertEquals(2, items.getCount());
            assertEquals("Friends", String.valueOf(items.getItem(0)));
            assertEquals("Work", String.valueOf(items.getItem(1)));
            assertEquals(ListView.CHOICE_MODE_MULTIPLE, choose.getListView().getChoiceMode());
            assertEquals("a new account has none ticked", 0, choose.getListView().getCheckedItemCount());
            assertEquals("New folder", String.valueOf(choose.getButton(DialogInterface.BUTTON_NEUTRAL).getText()));
            assertEquals("Save", String.valueOf(choose.getButton(DialogInterface.BUTTON_POSITIVE).getText()));
            choose.getListView().performItemClick(null, 0, 0);
            choose.getListView().performItemClick(null, 1, 1);
            choose.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            assertEquals("Saved @bo to 2 folders", toast());
            assertFalse(choose.isShowing());

            show("3", "cy", "", "");
            menu(controller.get(), "cy").get("Save profile").onClick(null);
            ShadowLooper.idleMainLooper();
            AlertDialog second = (AlertDialog) ShadowDialog.getLatestDialog();
            second.getListView().performItemClick(null, 1, 1);
            second.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
            ShadowLooper.idleMainLooper();
            AlertDialog name = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals("the keyboard pushes the dialog up rather than covering its buttons",
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    name.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            EditText field = SavedProfilesRows.field(name.getWindow().getDecorView());
            Button create = name.getButton(DialogInterface.BUTTON_POSITIVE);
            assertEquals("Create", String.valueOf(create.getText()));
            assertFalse("nothing typed, nothing to create", create.isEnabled());
            field.setText("   ");
            assertFalse(create.isEnabled());
            field.setText(" Gym ");
            assertTrue(create.isEnabled());
            create.performClick();
            assertEquals("the ticked folder and the new one", "Saved @cy to 2 folders", toast());
            assertEquals(Arrays.asList("Gym", "Work"), SavedProfiles.find(context, "3", "cy").folders);

            show("4", "dee", "", "");
            menu(controller.get(), "dee").get("Save profile").onClick(null);
            ShadowLooper.idleMainLooper();
            AlertDialog third = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals("the new folder is on the list now", 3, third.getListView().getAdapter().getCount());
            third.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
            ShadowLooper.idleMainLooper();
            AlertDialog again = (AlertDialog) ShadowDialog.getLatestDialog();
            SavedProfilesRows.field(again.getWindow().getDecorView()).setText("FRIENDS");
            again.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            assertEquals("a new name in another case joins the folder", "Saved @dee to Friends", toast());

            show("5", "eve", "", "");
            menu(controller.get(), "eve").get("Save profile").onClick(null);
            ShadowLooper.idleMainLooper();
            ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            assertEquals("nothing ticked, saved in no folder", "Saved @eve", toast());
        }
        assertEquals(Arrays.asList("Friends", "Gym", "Work"), SavedProfiles.folders(SavedProfiles.all(context)));
        assertEquals(Arrays.asList("eve", "dee", "cy", "bo", "ann"), usernames());
        assertTrue(SavedProfiles.find(context, "5", "eve").folders.isEmpty());
    }

    /**
     * #629: a saved account's profile menu names the folders it's in, under Your saved profiles, and
     * the row opens the folder picker with those ticked, so one can be added or taken off there.
     */
    @Test public void aSavedProfilesMenuNamesItsFoldersAndChangesThem() throws Exception {
        Settings.SAVED_PROFILES.save(true);
        SavedProfiles.save(context, new SavedProfiles.Profile("1", "ann", "", "", Arrays.asList("Friends", "Work"), 0), 1_000L);
        SavedProfiles.save(context, new SavedProfiles.Profile("2", "bo", "", "", 0), 2_000L);
        show("1", "ann", "", "");
        List<String> labels = new ArrayList<>(menu("ann").keySet());
        String folders = labels.get(labels.indexOf("Your saved profiles") + 1);
        assertTrue(labels.toString(), folders.startsWith("Folders: ") && folders.contains("Friends, Work"));
        show("2", "bo", "", "");
        labels = new ArrayList<>(menu("bo").keySet());
        assertEquals("in no folder, it offers to choose", labels.indexOf("Your saved profiles") + 1, labels.indexOf("Choose folders"));
        show("3", "cy", "", "");
        for (String label : menu("cy").keySet()) {
            assertFalse("not saved, no folders row: " + label, label.startsWith("Folders") || label.equals("Choose folders"));
        }

        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            show("1", "ann", "", "");
            menu(controller.get(), "ann").get(folders).onClick(null);
            ShadowLooper.idleMainLooper();
            AlertDialog choose = (AlertDialog) ShadowDialog.getLatestDialog();
            ListView list = choose.getListView();
            assertEquals(2, list.getAdapter().getCount());
            // The dialog ticks an item as it draws it.
            for (int at = 0; at < 2; at++) list.getAdapter().getView(at, null, list);
            assertTrue("Friends is ticked", list.isItemChecked(0));
            assertTrue("Work is ticked", list.isItemChecked(1));
            assertEquals("Save", String.valueOf(choose.getButton(DialogInterface.BUTTON_POSITIVE).getText()));
            list.performItemClick(null, 1, 1);
            choose.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            String said = toast();
            assertTrue(said, said.startsWith("@") && said.contains("ann") && said.contains("Friends") && said.endsWith(" now"));
            assertEquals(Arrays.asList("Friends"), SavedProfiles.find(context, "1", "ann").folders);
        }

        Settings.SAVED_PROFILES.save(false);
        assertNull(SavedProfiles.foldersRow(context, null));
        assertTrue(menu("ann").isEmpty());
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

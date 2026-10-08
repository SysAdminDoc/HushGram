/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.*;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Save and View profile picture's rows, and Copy username and Copy bio's: what they read when the
 * menu opens, and what a tap does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ProfilePictureTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String FULL = "https://scontent.cdninstagram.com/v/t51.2885-19/full_n.jpg?stp=dst-jpg_s1080x1080";
    private static final String SHOWN = "https://scontent.cdninstagram.com/v/t51.2885-19/full_n.jpg?stp=dst-jpg_s150x150";
    private final Object sheet = new Object();
    private final Object user = new Object();
    private final FakeNative reads = new FakeNative();
    private final List<List<MediaSave.Rendition>> saved = new ArrayList<>();
    private final List<PostDetails> named = new ArrayList<>();
    private boolean saveStarts = true;
    private final ProfilePicture.Save save = (context, sizes, details) -> {
        saved.add(sizes);
        named.add(details);
        return saveStarts;
    };
    private final List<List<MediaSave.Rendition>> viewed = new ArrayList<>();
    private final List<String> viewedOwners = new ArrayList<>();
    private final List<ProfilePicture.Save> viewerSaves = new ArrayList<>();
    private boolean viewerOpens = true;
    private final ProfilePicture.Viewer viewer = (context, sizes, owner, save) -> {
        viewed.add(sizes);
        viewedOwners.add(owner);
        viewerSaves.add(save);
        return viewerOpens;
    };
    private Context context;

    @Before public void enable() {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SAVE_PROFILE_PICTURES.save(true);
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After public void restore() {
        Settings.SAVE_PROFILE_PICTURES.resetToDefault();
        Settings.VIEW_PROFILE_PICTURES.resetToDefault();
        Settings.COPY_PROFILE_TEXT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private List<String> counted() {
        return HookStatus.report();
    }

    @Test public void theSwitchStartsOff() {
        Settings.SAVE_PROFILE_PICTURES.resetToDefault();
        assertFalse(Settings.SAVE_PROFILE_PICTURES.get());
    }

    @Test public void offTheMenuIsInstagramsAndNothingIsRead() {
        Settings.SAVE_PROFILE_PICTURES.save(false);
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertNull(reads.row);
        assertEquals("nothing of the account was read", 0, reads.calls);
    }

    @Test public void pausedOrUnreadyTheMenuIsInstagrams() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertNull(reads.row);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> ProfilePicture.offer(sheet, user, context, reads, save));
        assertNull(reads.row);
        assertEquals(0, reads.calls);
    }

    @Test public void theRowSavesTheSizesReadWhenTheMenuOpened() {
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertSame(sheet, reads.sheet);
        assertSame(context, reads.context);
        assertEquals("Save profile picture", reads.label);
        assertTrue("showing the menu saves nothing", saved.isEmpty());

        // What the account holds later doesn't change what the row saves.
        reads.fullUrl = "https://scontent.cdninstagram.com/later.jpg";
        reads.row.onClick(null);
        assertEquals(1, saved.size());
        List<MediaSave.Rendition> sizes = saved.get(0);
        assertEquals(2, sizes.size());
        assertEquals(FULL, sizes.get(0).url);
        assertEquals(1080, sizes.get(0).width);
        assertEquals(1080, sizes.get(0).height);
        assertEquals(SHOWN, sizes.get(1).url);
        assertThrows(UnsupportedOperationException.class, () -> sizes.add(MediaSave.Rendition.of(FULL)));
        assertEquals("someone", named.get(0).owner);
        assertFalse(named.get(0).hasPosted());
        assertTrue("named as a profile picture", named.get(0).profile);
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: full size picture 1"), counted());
    }

    @Test public void anAccountWithoutTheFullSizeSavesTheShownOne() {
        reads.full = null;
        ProfilePicture.offer(sheet, user, context, reads, save);
        reads.row.onClick(new View(context));
        assertEquals(1, saved.get(0).size());
        assertEquals(SHOWN, saved.get(0).get(0).url);
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: shown size only 1"), counted());
    }

    /** The same address twice is one size. */
    @Test public void theSameAddressIsKeptOnce() {
        reads.shownUrl = FULL;
        ProfilePicture.offer(sheet, user, context, reads, save);
        reads.row.onClick(null);
        assertEquals(1, saved.get(0).size());
    }

    @Test public void anAccountWithNoPictureGetsNoRow() {
        reads.full = null;
        reads.shown = null;
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertNull(reads.row);
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: no profile picture 1"), counted());
    }

    /** Only Meta's media servers count: a picture anywhere else is never fetched. */
    @Test public void anAddressOffMetasServersIsLeftOut() {
        reads.fullUrl = "https://example.com/full.jpg";
        reads.shownUrl = "http://scontent.cdninstagram.com/shown.jpg";
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertNull(reads.row);
    }

    @Test public void aRowThatDidntGoInIsCounted() {
        reads.adds = false;
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: full size picture 1, row not added 1"), counted());
    }

    @Test public void aTapAfterTheSwitchWentOffSavesNothing() {
        ProfilePicture.offer(sheet, user, context, reads, save);
        Settings.SAVE_PROFILE_PICTURES.save(false);
        reads.row.onClick(null);
        assertTrue(saved.isEmpty());
    }

    @Test public void aSaveThatCantStartSaysSo() {
        saveStarts = false;
        ProfilePicture.offer(sheet, user, context, reads, save);
        reads.row.onClick(null);
        ShadowLooper.idleMainLooper();
        assertEquals("Download failed", ShadowToast.getTextOfLatestToast());
    }

    /** A read that throws leaves Instagram's menu as it was, and the report says where. */
    @Test public void aReadThatThrowsLeavesTheMenuAlone() {
        reads.broken = true;
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertNull(reads.row);
        assertTrue(String.valueOf(counted()), counted().get(0).contains("profile menu"));
    }

    @Test public void nothingToWorkWithAddsNothing() {
        ProfilePicture.offer(null, user, context, reads, save);
        ProfilePicture.offer(sheet, null, context, reads, save);
        ProfilePicture.offer(sheet, user, null, reads, save);
        assertNull(reads.row);
        assertEquals(0, reads.calls);
    }

    @Test public void theViewSwitchStartsOff() {
        Settings.VIEW_PROFILE_PICTURES.resetToDefault();
        assertFalse(Settings.VIEW_PROFILE_PICTURES.get());
    }

    @Test public void withBothOnViewComesAfterSave() {
        Settings.VIEW_PROFILE_PICTURES.save(true);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertEquals(Arrays.asList("Save profile picture", "View profile picture"), new ArrayList<>(reads.rows.keySet()));
        assertTrue("showing the menu opens nothing", viewed.isEmpty());
        assertEquals("the sizes are read once for both rows", Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: full size picture 1"), counted());
    }

    @Test public void theViewRowOpensTheSizesReadWhenTheMenuOpened() {
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.VIEW_PROFILE_PICTURES.save(true);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertEquals(Collections.singletonList("View profile picture"), new ArrayList<>(reads.rows.keySet()));

        reads.fullUrl = "https://scontent.cdninstagram.com/later.jpg";
        reads.rows.get("View profile picture").onClick(null);
        assertEquals(1, viewed.size());
        assertEquals(2, viewed.get(0).size());
        assertEquals(FULL, viewed.get(0).get(0).url);
        assertEquals(SHOWN, viewed.get(0).get(1).url);
        assertEquals("someone", viewedOwners.get(0));
        assertSame("the viewer's Save is the row's save", save, viewerSaves.get(0));
        assertTrue("opening the viewer saves nothing", saved.isEmpty());
    }

    @Test public void offTheViewSwitchAddsNoViewRow() {
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertEquals(Collections.singletonList("Save profile picture"), new ArrayList<>(reads.rows.keySet()));
    }

    @Test public void aViewTapAfterTheSwitchWentOffOpensNothing() {
        Settings.VIEW_PROFILE_PICTURES.save(true);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        Settings.VIEW_PROFILE_PICTURES.save(false);
        reads.rows.get("View profile picture").onClick(null);
        assertTrue(viewed.isEmpty());
    }

    @Test public void aViewerThatCantOpenSaysSo() {
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.VIEW_PROFILE_PICTURES.save(true);
        viewerOpens = false;
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        reads.rows.get("View profile picture").onClick(null);
        ShadowLooper.idleMainLooper();
        assertEquals("Couldn't open the picture", ShadowToast.getTextOfLatestToast());
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: full size picture 1, picture not opened 1"), counted());
    }

    @Test public void aViewRowThatDidntGoInIsCounted() {
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.VIEW_PROFILE_PICTURES.save(true);
        reads.adds = false;
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: full size picture 1, view row not added 1"), counted());
    }

    @Test public void bothOffNothingOfTheAccountIsRead() {
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.VIEW_PROFILE_PICTURES.save(false);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertTrue(reads.rows.isEmpty());
        assertEquals(0, reads.calls);
    }

    @Test public void theCopySwitchStartsOff() {
        Settings.COPY_PROFILE_TEXT.resetToDefault();
        assertFalse(Settings.COPY_PROFILE_TEXT.get());
    }

    @Test public void withEverySwitchOnTheCopyRowsComeLast() {
        Settings.VIEW_PROFILE_PICTURES.save(true);
        Settings.COPY_PROFILE_TEXT.save(true);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertEquals(Arrays.asList("Save profile picture", "View profile picture", "Copy username", "Copy bio"),
                new ArrayList<>(reads.rows.keySet()));
    }

    /** With only the copy switch on, the picture's sizes aren't read at all. */
    @Test public void copyAloneReadsOnlyTheUsernameAndTheBio() {
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.COPY_PROFILE_TEXT.save(true);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertEquals(Arrays.asList("Copy username", "Copy bio"), new ArrayList<>(reads.rows.keySet()));
        assertEquals("the username and the bio, nothing else", 2, reads.calls);
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE + ": invoked 1, 0 found, 0 missing"), counted());
    }

    @Test public void copyUsernamePutsTheUsernameOnTheClipboard() {
        Settings.COPY_PROFILE_TEXT.save(true);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        reads.rows.get("Copy username").onClick(null);
        ShadowLooper.idleMainLooper();
        assertEquals("someone", clipboard());
        assertEquals("Username copied", ShadowToast.getTextOfLatestToast());
        assertTrue("copying saves nothing", saved.isEmpty());
    }

    /** The bio goes on the clipboard exactly as it is: emoji, right-to-left text, line breaks and trailing spaces. */
    @Test public void copyBioPutsTheExactBioOnTheClipboard() {
        reads.bio = "Caf\u00e9 \u2615\n\u05e9\u05dc\u05d5\u05dd \ud83d\ude00  ";
        Settings.COPY_PROFILE_TEXT.save(true);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);

        // What the account holds later doesn't change what the row copies.
        String opened = reads.bio;
        reads.bio = "changed";
        reads.rows.get("Copy bio").onClick(null);
        ShadowLooper.idleMainLooper();
        assertEquals(opened, clipboard());
        assertEquals("Bio copied", ShadowToast.getTextOfLatestToast());
    }

    @Test public void anAccountWithNoBioGetsNoCopyBioRow() {
        reads.bio = "";
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.COPY_PROFILE_TEXT.save(true);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertEquals(Collections.singletonList("Copy username"), new ArrayList<>(reads.rows.keySet()));
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: no bio 1"), counted());
        reads.bio = null;
        HookStatus.clear();
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: no bio 1"), counted());
    }

    @Test public void anAccountWithNoUsernameGetsNoCopyUsernameRow() {
        reads.username = null;
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.COPY_PROFILE_TEXT.save(true);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertEquals(Collections.singletonList("Copy bio"), new ArrayList<>(reads.rows.keySet()));
    }

    /** No picture leaves out Save and View, not the copy rows. */
    @Test public void anAccountWithNoPictureStillGetsTheCopyRows() {
        reads.full = null;
        reads.shown = null;
        Settings.VIEW_PROFILE_PICTURES.save(true);
        Settings.COPY_PROFILE_TEXT.save(true);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertEquals(Arrays.asList("Copy username", "Copy bio"), new ArrayList<>(reads.rows.keySet()));
    }

    @Test public void aCopyTapAfterTheSwitchWentOffCopiesNothing() {
        Settings.COPY_PROFILE_TEXT.save(true);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        Settings.COPY_PROFILE_TEXT.save(false);
        reads.rows.get("Copy username").onClick(null);
        reads.rows.get("Copy bio").onClick(null);
        assertNull(clipboard());
    }

    @Test public void aCopyRowThatDidntGoInIsCounted() {
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.COPY_PROFILE_TEXT.save(true);
        reads.adds = false;
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: copy row not added 2"), counted());
    }

    @Test public void pausedTheCopyRowsStayOut() {
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.COPY_PROFILE_TEXT.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ProfilePicture.offer(sheet, user, context, reads, save, viewer);
        assertTrue(reads.rows.isEmpty());
        assertEquals(0, reads.calls);
    }

    private String clipboard() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        return clipboard.hasPrimaryClip() ? String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()) : null;
    }

    /** As built, with no patch, the row's adder adds nothing and every read answers nothing. */
    @Test public void unpatchedTheMenuIsInstagrams() {
        ProfilePicture.offer(sheet, user, context);
        assertFalse(ProfilePicture.addRow(sheet, context, view -> { }, "label"));
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: no profile picture 1"), counted());
    }

    private static final class FakeNative implements ProfilePicture.Native {
        final Object fullInfo = new Object(), shownImage = new Object();
        Object full = fullInfo, shown = shownImage;
        String fullUrl = FULL, shownUrl = SHOWN;
        String username = "someone", bio = "Bakes on weekends";
        boolean adds = true, broken;
        int calls;
        Object sheet;
        Context context;
        View.OnClickListener row;
        String label;
        final Map<String, View.OnClickListener> rows = new LinkedHashMap<>();

        @Override public boolean addRow(Object sheet, Context context, View.OnClickListener listener, String label) {
            this.sheet = sheet;
            this.context = context;
            this.label = label;
            if (adds) {
                row = listener;
                rows.put(label, listener);
            }
            return adds;
        }
        @Override public Object fullSize(Object user) {
            calls++;
            if (broken) throw new ClassCastException("not an account");
            return full;
        }
        @Override public String fullSizeUrl(Object info) { calls++; return info == fullInfo ? fullUrl : null; }
        @Override public int fullSizeWidth(Object info) { calls++; return 1080; }
        @Override public int fullSizeHeight(Object info) { calls++; return 1080; }
        @Override public Object shown(Object user) { calls++; return shown; }
        @Override public String shownUrl(Object image) { calls++; return image == shownImage ? shownUrl : null; }
        @Override public int shownWidth(Object image) { calls++; return 150; }
        @Override public int shownHeight(Object image) { calls++; return 150; }
        @Override public String username(Object user) { calls++; return username; }
        @Override public String biography(Object user) { calls++; return bio; }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
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
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** #402: a comment GIF Save kept, picked from the comment box's gallery, goes up through Instagram's GIF send. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentGifPickTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String PATH = "/storage/emulated/0/Pictures/Instagram/hushgram_comment_17900001.gif";
    private final Object screen = new Object();
    private final FakePicks picks = new FakePicks();
    private Context context;

    @Before public void enable() {
        context = RuntimeEnvironment.getApplication();
        picks.activity = Robolectric.buildActivity(Activity.class).setup().get();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SEND_SAVED_GIFS.save(true);
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After public void restore() {
        Settings.SEND_SAVED_GIFS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        HookStatus.clear();
        File folder = CommentGifStore.folder(context);
        File[] records = folder.listFiles();
        if (records != null) for (File record : records) record.delete();
        folder.delete();
    }

    private static List<String> counted(String counts) {
        return Collections.singletonList(FamilyNames.COMMENT_PHOTO + ": invoked 0, 0 found, 0 missing. Counted: " + counts);
    }

    private String latestToast() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    /**
     * A kept GIF ends the stock pick, then on the main thread the gallery closes with nothing attached
     * and the GIF goes up through Instagram's send, from the Activity on screen.
     */
    @Test public void aKeptGifEndsTheStockPickAndGoesUpThroughTheGifSend() {
        assertEquals(1, CommentGifPick.picked(PATH, screen, picks));
        assertEquals("nothing touches the screen off the main thread", Arrays.asList("kept", "info", "activity"), picks.calls);
        assertEquals(PATH, picks.path);
        assertSame(picks.kept, picks.read);
        assertSame(screen, picks.readThrough);
        picks.runMain();
        assertEquals(Arrays.asList("kept", "info", "activity", "clear", "send"), picks.calls);
        assertSame(screen, picks.cleared);
        assertSame(screen, picks.sentOn);
        assertSame(picks.activity, picks.sentFrom);
        assertSame(picks.info, picks.sent);
        assertEquals(counted(CommentGifPick.SENT + " 1"), HookStatus.report());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowToast.getLatestToast());
    }

    /** A WebP counts as a GIF too, whatever the case of its name. */
    @Test public void aGifOrWebpOfAnyCaseIsLookedUp() {
        assertTrue(CommentGifPick.animated("a.gif"));
        assertTrue(CommentGifPick.animated("A.GIF"));
        assertTrue(CommentGifPick.animated("a.WebP"));
        assertFalse(CommentGifPick.animated("a.jpg"));
        assertFalse(CommentGifPick.animated("a.gif.jpg"));
        assertFalse(CommentGifPick.animated("a.mp4"));
        assertEquals(1, CommentGifPick.picked("/sdcard/DCIM/KEPT.WEBP", screen, picks));
    }

    /** Off, paused, unready, a still picture or a missing input leaves the pick to Instagram, unread. */
    @Test public void offPausedUnreadyStillsAndMissingInputsAreLeftToInstagram() {
        Settings.SEND_SAVED_GIFS.save(false);
        assertEquals(0, CommentGifPick.picked(PATH, screen, picks));
        Settings.SEND_SAVED_GIFS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, CommentGifPick.picked(PATH, screen, picks));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals(0, CommentGifPick.picked(PATH, screen, picks)));
        assertEquals(0, CommentGifPick.picked("/storage/emulated/0/DCIM/Camera/photo.jpg", screen, picks));
        assertEquals(0, CommentGifPick.picked(null, screen, picks));
        assertEquals(0, CommentGifPick.picked(PATH, null, picks));
        assertTrue("nothing is read", picks.calls.isEmpty());
        assertTrue(picks.main.isEmpty());
        assertEquals(Collections.emptyList(), HookStatus.report());
    }

    /**
     * A GIF Save didn't keep goes up as a still, as before. One it kept that this Instagram can't read
     * back is counted, and one with no Activity on screen is left alone too.
     */
    @Test public void aGifNotKeptOrNotReadableIsLeftToInstagram() {
        picks.kept = null;
        assertEquals(0, CommentGifPick.picked(PATH, screen, picks));
        assertEquals(Collections.singletonList("kept"), picks.calls);
        assertEquals(Collections.emptyList(), HookStatus.report());

        picks.kept = new byte[] { 1 };
        picks.info = null;
        picks.calls.clear();
        assertEquals(0, CommentGifPick.picked(PATH, screen, picks));
        assertEquals(Arrays.asList("kept", "info"), picks.calls);
        assertEquals(counted(CommentGifPick.UNREADABLE + " 1"), HookStatus.report());

        HookStatus.clear();
        picks.info = new Object();
        picks.activity = null;
        assertEquals(0, CommentGifPick.picked(PATH, screen, picks));
        assertTrue(picks.main.isEmpty());

        picks.failKept = true;
        assertEquals("a file that can't be read is Instagram's to read", 0, CommentGifPick.picked(PATH, screen, picks));
        assertTrue(picks.main.isEmpty());
        assertNull(picks.sent);
    }

    /** A send that fails says so, and isn't counted as sent. */
    @Test public void aFailedSendSaysSo() {
        picks.failSend = true;
        assertEquals(1, CommentGifPick.picked(PATH, screen, picks));
        picks.runMain();
        assertEquals("Couldn't send this GIF", latestToast());
        assertFalse(String.valueOf(HookStatus.report()).contains(CommentGifPick.SENT));
    }

    private File gallery(String name, String content) throws IOException {
        File folder = new File(context.getCacheDir(), "gallery");
        assertTrue(folder.mkdirs() || folder.isDirectory());
        File file = new File(folder, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return file;
    }

    /**
     * Save gets a keeper for a comment's GIF when the switch is on and the build can send it. Once the
     * file is published, its send model is kept for that file and counted; the pick finds it again.
     */
    @Test public void saveKeepsTheGifsSendModelForTheFileItPublished() throws IOException {
        Object gif = new Object();
        CommentGifStoreTest.Model model = new CommentGifStoreTest.Model("17900001", 200);
        List<Object> converted = new ArrayList<>();
        Consumer<String> keeper = CommentGifPick.keeping(context, gif, () -> true, from -> {
            converted.add(from);
            return model;
        });
        assertNotNull(keeper);
        assertEquals(Collections.singletonList(gif), converted);
        File saved = gallery("hushgram_comment_17900001.gif", "GIF89a kept");
        keeper.accept(CommentGifStoreTest.item(saved));
        assertEquals(counted(CommentGifPick.KEPT + " 1"), HookStatus.report());
        assertArrayEquals(CommentGifStore.marshal(model), CommentGifStore.find(context, saved.getPath()));

        HookStatus.clear();
        keeper.accept(CommentGifStoreTest.item(new File(saved.getParentFile(), "gone.gif")));
        assertFalse("a keep that fails isn't counted", String.valueOf(HookStatus.report()).contains(CommentGifPick.KEPT));
    }

    /**
     * No keeper, and the save goes ahead as it always did: switch off, paused, not in this build, no
     * GIF, no context, a GIF with no send model or a model that can't be kept, or a read that fails.
     */
    @Test public void noKeeperWhenOffNotInBuildOrTheGifHasNoSendModel() {
        Object gif = new Object();
        CommentGifStoreTest.Model model = new CommentGifStoreTest.Model("17900001", 200);
        Settings.SEND_SAVED_GIFS.save(false);
        assertNull(CommentGifPick.keeping(context, gif, () -> true, from -> model));
        Settings.SEND_SAVED_GIFS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertNull(CommentGifPick.keeping(context, gif, () -> true, from -> model));
        PauseForTests.resume();
        assertNull(CommentGifPick.keeping(context, gif, () -> false, from -> model));
        assertNull(CommentGifPick.keeping(context, null, () -> true, from -> model));
        assertNull(CommentGifPick.keeping(null, gif, () -> true, from -> model));
        assertNull(CommentGifPick.keeping(context, gif, () -> true, from -> null));
        assertNull("not a Parcelable", CommentGifPick.keeping(context, gif, () -> true, from -> "model"));
        assertNull(CommentGifPick.keeping(context, gif, () -> true, from -> { throw new IllegalStateException("read failed"); }));
        assertNull("the unpatched build's stubs", CommentGifPick.keeping(context, gif));
        assertNotNull(CommentGifPick.keeping(context, gif, () -> true, from -> model));
    }

    /** The comment box's steps, in the order a pick takes them, the main thread's held until run. */
    static final class FakePicks implements CommentGifPick.Picks {
        final List<String> calls = new ArrayList<>();
        final List<Runnable> main = new ArrayList<>();
        byte[] kept = new byte[] { 7, 1, 2 };
        Object info = new Object();
        Activity activity;
        boolean failKept, failSend;
        String path;
        Object read, readThrough, cleared, sentOn, sentFrom, sent;

        public byte[] kept(Context context, String path) throws IOException {
            calls.add("kept");
            this.path = path;
            if (failKept) throw new IOException("unreadable");
            return kept;
        }
        public Object info(byte[] kept, Object screen) {
            calls.add("info");
            read = kept;
            readThrough = screen;
            return info;
        }
        public Activity activity() { calls.add("activity"); return activity; }
        public void onMain(Runnable step) { main.add(step); }
        public void clear(Object screen) { calls.add("clear"); cleared = screen; }
        public void send(Object screen, Object activity, Object info) {
            calls.add("send");
            if (failSend) throw new IllegalStateException("send failed");
            sentOn = screen;
            sentFrom = activity;
            sent = info;
        }

        void runMain() {
            List<Runnable> steps = new ArrayList<>(main);
            main.clear();
            for (Runnable step : steps) step.run();
        }
    }
}

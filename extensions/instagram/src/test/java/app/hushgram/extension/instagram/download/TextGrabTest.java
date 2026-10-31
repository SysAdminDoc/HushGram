/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Copy text (#303): the screen picture goes to Google Lens through Instagram's own FileProvider, the
 * share sheet stands in when Lens isn't there, a video's own layer is drawn under the window's pixels,
 * and every failure says so in a toast instead of throwing into Instagram.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37})
public class TextGrabTest {
    private static final String AUTHORITY = "com.instagram.android.fileprovider";
    private static final String FAMILY = FamilyNames.REEL_DOWNLOAD;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final TextGrab.Copier originalCopier = TextGrab.copier;
    private final TextGrab.Authority originalAuthority = TextGrab.authority;
    private final TextGrab.Starter originalStarter = TextGrab.starter;
    private final Executor originalWriter = TextGrab.writer;
    private final Activity originalLast = Utils.getActivity();
    private final List<Intent> started = new ArrayList<>();
    private final List<ActivityController<Activity>> controllers = new ArrayList<>();
    private Activity activity;

    @Before
    public void setUp() {
        activity = open();
        TextGrab.authority = context -> AUTHORITY;
        TextGrab.starter = (from, intent) -> started.add(intent);
        TextGrab.writer = Runnable::run;
        TextGrab.resetForTests();
        HookStatus.clear();
    }

    @After
    public void tearDown() {
        TextGrab.copier = originalCopier;
        TextGrab.authority = originalAuthority;
        TextGrab.starter = originalStarter;
        TextGrab.writer = originalWriter;
        Utils.setActivity(originalLast);
        TextGrab.resetForTests();
        HookStatus.clear();
        for (ActivityController<Activity> controller : controllers) {
            if (!controller.get().isDestroyed()) controller.pause().stop().destroy();
        }
    }

    private Activity open() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        controllers.add(controller);
        return controller.get();
    }

    /** A page filling [owner]'s window, laid out. */
    private static FrameLayout pageIn(Activity owner) {
        FrameLayout page = new FrameLayout(owner);
        owner.setContentView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        ShadowLooper.idleMainLooper();
        return page;
    }

    /** Answers every copy at once with white, and remembers which window each was told it's in. */
    private static final class Answering implements TextGrab.Copier {
        final List<Window> windows = new ArrayList<>();
        final List<Rect> areas = new ArrayList<>();

        @Override public void window(View view, Window window, Rect area, Bitmap into, Consumer<Boolean> done) {
            windows.add(window);
            areas.add(new Rect(area));
            into.eraseColor(Color.WHITE);
            done.accept(true);
        }

        @Override public void surface(SurfaceView view, Bitmap into, Consumer<Boolean> done) {
            done.accept(false);
        }
    }

    private File picture() {
        Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        File file = TextGrab.write(activity, bitmap);
        assertNotNull(file);
        assertTrue("the bitmap is let go once written", bitmap.isRecycled());
        return file;
    }

    private static Uri expected(File file) {
        return Uri.parse("content://" + AUTHORITY + "/cache/hushgram-text/" + file.getName());
    }

    private static String toast() {
        ShadowLooper.idleMainLooper();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test
    public void lensGetsTheJpegThroughInstagramsOwnProvider() {
        File file = picture();
        TextGrab.send(activity, file, FAMILY);
        assertEquals(1, started.size());
        Intent lens = started.get(0);
        assertEquals(Intent.ACTION_SEND, lens.getAction());
        assertEquals("Lens by name, so Android asks nothing first",
                new ComponentName(TextGrab.LENS_PACKAGE, TextGrab.LENS_ENTRY), lens.getComponent());
        assertEquals("image/jpeg", lens.getType());
        assertEquals(expected(file), lens.getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals(expected(file), lens.getClipData().getItemAt(0).getUri());
        assertTrue("Lens may read it", (lens.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertTrue(report().contains(TextGrab.COUNT_SENT));
    }

    private static boolean toGoogle(Intent intent) {
        ComponentName named = intent.getComponent();
        return TextGrab.LENS_PACKAGE.equals(intent.getPackage())
                || named != null && TextGrab.LENS_PACKAGE.equals(named.getPackageName());
    }

    @Test
    public void aGoogleAppWithoutThatLensTargetStillGetsItByPackage() {
        TextGrab.starter = (from, intent) -> {
            if (intent.getComponent() != null) throw new ActivityNotFoundException();
            started.add(intent);
        };
        File file = picture();
        TextGrab.send(activity, file, FAMILY);
        assertEquals(1, started.size());
        assertEquals(TextGrab.LENS_PACKAGE, started.get(0).getPackage());
        assertEquals(expected(file), started.get(0).getParcelableExtra(Intent.EXTRA_STREAM));
        assertTrue(report().contains(TextGrab.COUNT_SENT));
    }

    @Test
    public void withoutLensTheShareSheetOffersTheRest() {
        TextGrab.starter = (from, intent) -> {
            if (toGoogle(intent)) throw new ActivityNotFoundException();
            started.add(intent);
        };
        File file = picture();
        TextGrab.send(activity, file, FAMILY);
        assertEquals(1, started.size());
        Intent chooser = started.get(0);
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        assertEquals("Copy text with", String.valueOf(chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)));
        Intent inner = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNull("any app that reads pictures, not just Lens", inner.getPackage());
        assertEquals(expected(file), inner.getParcelableExtra(Intent.EXTRA_STREAM));
        assertTrue((chooser.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void nothingToSendToSaysSo() {
        TextGrab.starter = (from, intent) -> {
            throw new ActivityNotFoundException();
        };
        TextGrab.send(activity, picture(), FAMILY);
        assertEquals("No app here can read text", toast());
        assertTrue(report().contains(TextGrab.COUNT_NO_APP));
    }

    @Test
    public void withoutTheProviderNothingIsSent() {
        TextGrab.authority = context -> null;
        TextGrab.send(activity, picture(), FAMILY);
        assertTrue(started.isEmpty());
        assertEquals("Couldn't copy this screen", toast());
        assertTrue(report().contains(TextGrab.COUNT_NO_PROVIDER));
    }

    @Test
    public void theProviderIsFoundByItsClassWhateverItsAuthority() {
        assertNull("this test app has none", TextGrab.fileProvider(activity));
        ProviderInfo other = new ProviderInfo();
        other.packageName = activity.getPackageName();
        other.name = "com.facebook.secure.content.FileProvider";
        other.authority = "com.instagram.android.secure";
        shadowOf(activity.getPackageManager()).addOrUpdateProvider(other);
        ProviderInfo files = new ProviderInfo();
        files.packageName = activity.getPackageName();
        files.name = TextGrab.FILE_PROVIDER;
        // A second install renames it, and a provider may answer to more than one authority.
        files.authority = "com.instagram.android.clone.fileprovider;com.instagram.android.clone.other";
        shadowOf(activity.getPackageManager()).addOrUpdateProvider(files);
        assertEquals("com.instagram.android.clone.fileprovider", TextGrab.fileProvider(activity));
    }

    @Test
    public void onlyTheLatestPictureIsKept() throws Exception {
        File first = picture();
        File second = picture();
        File folder = new File(activity.getCacheDir(), TextGrab.FOLDER);
        assertEquals(Collections.singletonList(second.getName()), Arrays.asList(folder.list()));
        byte[] bytes = Files.readAllBytes(second.toPath());
        assertArrayEquals("a JPEG", new byte[] {(byte) 0xFF, (byte) 0xD8}, Arrays.copyOf(bytes, 2));
        assertTrue(first.getName().endsWith(".jpg"));
    }

    @Test
    public void onlyShownVideosAreCopied() {
        FrameLayout page = new FrameLayout(activity);
        SurfaceView playing = new SurfaceView(activity);
        SurfaceView gone = new SurfaceView(activity);
        gone.setVisibility(View.GONE);
        FrameLayout inner = new FrameLayout(activity);
        inner.addView(playing);
        page.addView(inner);
        page.addView(gone);
        activity.setContentView(page);
        List<SurfaceView> found = new ArrayList<>();
        TextGrab.surfacesUnder(page, found);
        assertEquals(Collections.singletonList(playing), found);
    }

    @Test
    public void aVideoIsDrawnUnderTheWindowsPixelsOnBlack() {
        Bitmap top = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        // The window is clear over the video on the left and draws white text on the right.
        for (int x = 5; x < 10; x++) for (int y = 0; y < 10; y++) top.setPixel(x, y, Color.WHITE);
        Bitmap frame = Bitmap.createBitmap(5, 10, Bitmap.Config.ARGB_8888);
        frame.eraseColor(Color.RED);
        Bitmap picture = TextGrab.compose(top, new Bitmap[] {frame}, Collections.singletonList(new Rect(0, 0, 5, 10)));
        assertEquals(Color.RED, picture.getPixel(2, 5));
        assertEquals(Color.WHITE, picture.getPixel(7, 5));
        assertTrue(top.isRecycled() && frame.isRecycled());

        Bitmap clear = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        Bitmap unread = TextGrab.compose(clear, new Bitmap[] {null}, Collections.singletonList(new Rect(0, 0, 5, 10)));
        assertEquals("a video that won't copy stays black", Color.BLACK, unread.getPixel(2, 5));
    }

    @Test
    public void aTapSendsThePageAsShown() {
        FrameLayout page = pageIn(activity);
        Answering copier = new Answering();
        TextGrab.copier = copier;
        TextGrab.grab(page, activity, FAMILY);
        ShadowLooper.idleMainLooper();

        assertEquals(1, copier.areas.size());
        int[] at = new int[2];
        page.getLocationInWindow(at);
        assertEquals("the page's own rectangle", new Rect(at[0], at[1], at[0] + page.getWidth(), at[1] + page.getHeight()),
                copier.areas.get(0));
        assertSame("its activity's own window", activity.getWindow(), copier.windows.get(0));
        assertEquals(1, started.size());
        Uri sent = started.get(0).getParcelableExtra(Intent.EXTRA_STREAM);
        File file = new File(new File(activity.getCacheDir(), TextGrab.FOLDER), sent.getLastPathSegment());
        Bitmap read = BitmapFactory.decodeFile(file.getPath());
        assertEquals(page.getWidth(), read.getWidth());
        assertEquals(page.getHeight(), read.getHeight());
    }

    @Test
    public void aStoryInAWindowOverTheMainScreenIsSentFromTheActivityThatShowsIt() {
        // Instagram shows a story in a see-through activity over its main one, and the button can be
        // handed either.
        Activity story = open();
        FrameLayout page = pageIn(story);
        Utils.setActivity(story);
        assertSame(story, TextGrab.ownerOf(page, activity));
        Utils.setActivity(activity);
        assertSame(story, TextGrab.ownerOf(page, story));

        Answering copier = new Answering();
        TextGrab.copier = copier;
        List<Context> from = new ArrayList<>();
        TextGrab.starter = (sender, intent) -> {
            from.add(sender);
            started.add(intent);
        };
        Utils.setActivity(story);
        TextGrab.grab(page, activity, FAMILY);
        ShadowLooper.idleMainLooper();
        assertSame(story.getWindow(), copier.windows.get(0));
        assertEquals(Collections.singletonList(story), from);
    }

    @Test
    public void aStorysPlainContextViewsAreSentFromTheActivityHoldingThem() {
        // What the S22 showed: the story's views carry no activity, and none was handed in or recorded.
        Activity story = open();
        FrameLayout holder = pageIn(story);
        Context plain = new ContextThemeWrapper(story.getApplicationContext(), android.R.style.Theme_DeviceDefault);
        FrameLayout page = new FrameLayout(plain);
        holder.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        ShadowLooper.idleMainLooper();
        Utils.setActivity(null);
        assertSame("found through the parents", story, TextGrab.ownerOf(page, null));

        Answering copier = new Answering();
        TextGrab.copier = copier;
        List<Context> from = new ArrayList<>();
        TextGrab.starter = (sender, intent) -> {
            from.add(sender);
            started.add(intent);
        };
        TextGrab.grab(page, null, FAMILY);
        ShadowLooper.idleMainLooper();
        assertSame(story.getWindow(), copier.windows.get(0));
        assertEquals(Collections.singletonList(story), from);
        assertEquals(0, started.get(0).getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test
    public void withNoActivityOpenThePageStillSendsInATaskOfItsOwn() {
        Activity closing = open();
        FrameLayout page = pageIn(closing);
        closing.finish();
        Utils.setActivity(null);
        assertNull(TextGrab.ownerOf(page, closing));

        Answering copier = new Answering();
        TextGrab.copier = copier;
        List<Context> from = new ArrayList<>();
        TextGrab.starter = (sender, intent) -> {
            from.add(sender);
            started.add(intent);
        };
        TextGrab.grab(page, closing, FAMILY);
        ShadowLooper.idleMainLooper();
        assertNull(copier.windows.get(0));
        assertEquals(1, started.size());
        assertSame(closing.getApplicationContext(), from.get(0));
        assertTrue("outside an activity it needs a task", (started.get(0).getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals(new ComponentName(TextGrab.LENS_PACKAGE, TextGrab.LENS_ENTRY), started.get(0).getComponent());
    }

    @Test
    public void aPageInNoKnownWindowIsStillReadByItsView() {
        Activity elsewhere = open();
        FrameLayout page = pageIn(elsewhere);
        elsewhere.finish();
        Utils.setActivity(null);
        assertSame("the one it was handed, when nothing open holds it", activity, TextGrab.ownerOf(page, activity));
        Answering copier = new Answering();
        TextGrab.copier = copier;
        TextGrab.grab(page, activity, FAMILY);
        ShadowLooper.idleMainLooper();
        assertNull("no window of the owner's to fall back on", copier.windows.get(0));
        assertEquals(1, started.size());
    }

    @Test
    public void aClosingActivityHandsOverToTheOneShowing() {
        Activity closing = open();
        closing.finish();
        Utils.setActivity(activity);
        assertSame(activity, TextGrab.ownerOf(null, closing));
        Utils.setActivity(null);
        assertNull(TextGrab.ownerOf(null, closing));

        TextGrab.copier = new Answering();
        TextGrab.grab(null, closing, FAMILY);
        assertEquals("with nothing open it still says so", "Couldn't copy this screen", toast());
        assertTrue(report().contains(TextGrab.COUNT_NO_COPY));
        assertTrue(started.isEmpty());
    }

    @Test
    public void aCopyThatNeverAnswersSaysSoInTime() {
        FrameLayout page = pageIn(activity);
        List<Consumer<Boolean>> waiting = new ArrayList<>();
        TextGrab.copier = new TextGrab.Copier() {
            @Override public void window(View view, Window window, Rect area, Bitmap into, Consumer<Boolean> done) {
                waiting.add(done);
            }

            @Override public void surface(SurfaceView view, Bitmap into, Consumer<Boolean> done) {
                waiting.add(done);
            }
        };
        TextGrab.grab(page, activity, FAMILY);
        ShadowLooper.idleMainLooper();
        assertNull("nothing said before the wait is up", ShadowToast.getTextOfLatestToast());
        ShadowLooper.idleMainLooper(TextGrab.COPY_WAIT_MS, TimeUnit.MILLISECONDS);
        assertEquals("Couldn't copy this screen", toast());
        assertTrue(report().contains(TextGrab.COUNT_NO_COPY));

        ShadowToast.reset();
        waiting.get(0).accept(true);
        ShadowLooper.idleMainLooper();
        assertTrue("a late answer sends nothing", started.isEmpty());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aWriteOffTheSharedPoolStillSends() throws Exception {
        TextGrab.writer = originalWriter;
        FrameLayout page = pageIn(activity);
        TextGrab.copier = new Answering();
        TextGrab.grab(page, activity, FAMILY);
        for (int i = 0; i < 200 && started.isEmpty(); i++) {
            Thread.sleep(10);
            ShadowLooper.idleMainLooper();
        }
        assertEquals("written on its own thread, sent from the main one", 1, started.size());
    }

    @Test
    public void aCopyThatFailsSaysSo() {
        TextGrab.copier = new TextGrab.Copier() {
            @Override public void window(View view, Window window, Rect area, Bitmap into, Consumer<Boolean> done) {
                done.accept(false);
            }

            @Override public void surface(SurfaceView view, Bitmap into, Consumer<Boolean> done) {
                done.accept(false);
            }
        };
        TextGrab.grab(null, activity, FAMILY);
        assertEquals("Couldn't copy this screen", toast());
        assertTrue(report().contains(TextGrab.COUNT_NO_COPY));
        assertTrue(started.isEmpty());
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        TextGrab.copier = new TextGrab.Copier() {
            @Override public void window(View view, Window window, Rect area, Bitmap into, Consumer<Boolean> done) {
                throw new NoSuchMethodError("PixelCopy");
            }

            @Override public void surface(SurfaceView view, Bitmap into, Consumer<Boolean> done) {
                throw new NoSuchMethodError("PixelCopy");
            }
        };
        TextGrab.grab(null, activity, FAMILY);
        TextGrab.grab(null, null, FAMILY);
        TextGrab.starter = (from, intent) -> {
            throw new SecurityException("not exported");
        };
        TextGrab.send(activity, picture(), FAMILY);
        assertFalse("the failures were reported, not thrown", HookStatus.report().isEmpty());
    }
}

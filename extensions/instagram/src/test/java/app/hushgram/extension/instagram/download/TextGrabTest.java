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
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
    private final List<Intent> started = new ArrayList<>();
    private ActivityController<Activity> controller;
    private Activity activity;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        TextGrab.authority = context -> AUTHORITY;
        TextGrab.starter = (from, intent) -> started.add(intent);
        TextGrab.resetForTests();
        HookStatus.clear();
    }

    @After
    public void tearDown() {
        TextGrab.copier = originalCopier;
        TextGrab.authority = originalAuthority;
        TextGrab.starter = originalStarter;
        TextGrab.resetForTests();
        HookStatus.clear();
        controller.pause().stop().destroy();
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
        assertEquals(TextGrab.LENS_PACKAGE, lens.getPackage());
        assertEquals("image/jpeg", lens.getType());
        assertEquals(expected(file), lens.getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals(expected(file), lens.getClipData().getItemAt(0).getUri());
        assertTrue("Lens may read it", (lens.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertTrue(report().contains(TextGrab.COUNT_SENT));
    }

    @Test
    public void withoutLensTheShareSheetOffersTheRest() {
        TextGrab.starter = (from, intent) -> {
            if (TextGrab.LENS_PACKAGE.equals(intent.getPackage())) throw new ActivityNotFoundException();
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
    public void aTapSendsThePageAsShown() throws Exception {
        FrameLayout page = new FrameLayout(activity);
        activity.setContentView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        ShadowLooper.idleMainLooper();
        List<Rect> asked = new ArrayList<>();
        TextGrab.copier = new TextGrab.Copier() {
            @Override public void window(android.view.Window window, Rect area, Bitmap into, java.util.function.Consumer<Boolean> done) {
                asked.add(new Rect(area));
                into.eraseColor(Color.WHITE);
                done.accept(true);
            }

            @Override public void surface(SurfaceView view, Bitmap into, java.util.function.Consumer<Boolean> done) {
                done.accept(false);
            }
        };
        TextGrab.grab(page, activity, FAMILY);
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();

        assertEquals(1, asked.size());
        int[] at = new int[2];
        page.getLocationInWindow(at);
        assertEquals("the page's own rectangle", new Rect(at[0], at[1], at[0] + page.getWidth(), at[1] + page.getHeight()),
                asked.get(0));
        assertEquals(1, started.size());
        Uri sent = started.get(0).getParcelableExtra(Intent.EXTRA_STREAM);
        File file = new File(new File(activity.getCacheDir(), TextGrab.FOLDER), sent.getLastPathSegment());
        Bitmap read = BitmapFactory.decodeFile(file.getPath());
        assertEquals(page.getWidth(), read.getWidth());
        assertEquals(page.getHeight(), read.getHeight());
    }

    @Test
    public void aCopyThatFailsSaysSo() {
        TextGrab.copier = new TextGrab.Copier() {
            @Override public void window(android.view.Window window, Rect area, Bitmap into, java.util.function.Consumer<Boolean> done) {
                done.accept(false);
            }

            @Override public void surface(SurfaceView view, Bitmap into, java.util.function.Consumer<Boolean> done) {
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
            @Override public void window(android.view.Window window, Rect area, Bitmap into, java.util.function.Consumer<Boolean> done) {
                throw new NoSuchMethodError("PixelCopy");
            }

            @Override public void surface(SurfaceView view, Bitmap into, java.util.function.Consumer<Boolean> done) {
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

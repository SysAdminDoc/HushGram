/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.net.URL;
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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.ui.Dim;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** View profile picture's screen: a real loopback fetch, the picture it shows, its Save and its zoom. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, shadows = CarouselSaveTest.LocalCandidates.class)
public class ProfilePictureViewerTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context application;
    private LocalServer server;
    private ActivityController<Activity> activity;
    private final List<List<MediaSave.Rendition>> saved = new ArrayList<>();
    private final List<PostDetails> named = new ArrayList<>();
    private final ProfilePicture.Save save = (context, sizes, details) -> {
        saved.add(sizes);
        named.add(details);
        return true;
    };

    @Before public void setup() throws Exception {
        application = RuntimeEnvironment.getApplication();
        application.getApplicationInfo().targetSdkVersion = 37;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.VIEW_PROFILE_PICTURES.save(true);
        HookStatus.clear();
        ShadowToast.reset();
        server = new LocalServer();
        CarouselSaveTest.LocalCandidates.origin = server.origin();
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[]{InetAddress.getByName("10.9.8.7")}) {
            @Override Refusal refusal(URL url) {
                return url.toString().startsWith(server.origin() + "/") ? null : super.refusal(url);
            }
        };
        activity = Robolectric.buildActivity(Activity.class).setup();
    }

    @After public void close() throws Exception {
        Dialog shown = ShadowDialog.getLatestDialog();
        if (shown != null) shown.dismiss();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        activity.close();
        server.close();
        MediaSave.policyForTests = null;
        Settings.VIEW_PROFILE_PICTURES.resetToDefault();
        HookStatus.clear();
    }

    /** A real JPEG [width] by [height], as Instagram's servers send a profile picture. */
    private static byte[] jpeg(int width, int height) {
        Bitmap picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        picture.eraseColor(0xff336699);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(picture.compress(Bitmap.CompressFormat.JPEG, 90, bytes));
        return bytes.toByteArray();
    }

    private List<MediaSave.Rendition> sizes() {
        server.serve("/full.jpg", "image/jpeg", jpeg(120, 80));
        server.serve("/shown.jpg", "image/jpeg", jpeg(30, 20));
        return Arrays.asList(
                new MediaSave.Rendition(server.origin() + "/shown.jpg?stp=dst-jpg_s150x150", 150, 150, 0),
                new MediaSave.Rendition(server.origin() + "/full.jpg?stp=dst-jpg_s1080x1080", 1080, 1080, 0));
    }

    private void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private static TextView labeled(View view, String label) {
        if (view instanceof TextView && label.equals(((TextView) view).getText().toString())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = labeled(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void theLargestStatedSizeIsShownAndItsWidthCounted() throws Exception {
        assertTrue(ProfilePictureViewer.open(activity.get(), sizes(), "someone", save));
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        settle();
        assertEquals(0, server.hits("/shown.jpg"));
        assertEquals(1, server.hits("/full.jpg"));
        assertTrue(dialog.isShowing());
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 0, 0 found, 0 missing. Counted: viewed under 1080 px wide 1"), HookStatus.report());
        assertTrue("opening it saves nothing", saved.isEmpty());
    }

    /** Every width counts under one of two labels, so a family that keeps few labels never fills up. */
    @Test public void widthsShareTwoLabels() {
        assertEquals(ProfilePictureViewer.VIEWED_SMALLER, ProfilePictureViewer.viewed(150));
        assertEquals(ProfilePictureViewer.VIEWED_SMALLER, ProfilePictureViewer.viewed(1079));
        assertEquals(ProfilePictureViewer.VIEWED_FULL, ProfilePictureViewer.viewed(1080));
        assertEquals(ProfilePictureViewer.VIEWED_FULL, ProfilePictureViewer.viewed(1440));
    }

    @Test public void saveGoesThroughTheRowsSaveAndCloseShutsIt() throws Exception {
        List<MediaSave.Rendition> sizes = sizes();
        assertTrue(ProfilePictureViewer.open(activity.get(), sizes, "someone", save));
        settle();
        Dialog dialog = ShadowDialog.getLatestDialog();
        View root = dialog.getWindow().getDecorView();
        labeled(root, "Save").performClick();
        assertEquals(1, saved.size());
        assertSame(sizes, saved.get(0));
        assertEquals("someone", named.get(0).owner);
        assertTrue("named as a profile picture", named.get(0).profile);
        labeled(root, "Close").performClick();
        assertFalse(dialog.isShowing());
    }

    @Test public void loadingKeepsCloseAvailableAndSaveDisabledUntilThePictureIsReady() {
        ProfilePictureViewer.Screen screen = new ProfilePictureViewer.Screen(activity.get(), sizes(), "someone", save);
        screen.dialog.show();
        assertTrue(screen.close.isEnabled());
        assertFalse(screen.save.isEnabled());
        assertEquals(View.INVISIBLE, screen.picture.getVisibility());
        assertEquals(View.VISIBLE, screen.spinner.getVisibility());
        assertEquals("Loading the picture...", screen.spinner.getContentDescription());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, screen.spinner.getAccessibilityLiveRegion());
        ViewGroup root = (ViewGroup) screen.picture.getParent().getParent();
        root.measure(View.MeasureSpec.makeMeasureSpec(Dim.dp(300), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Dim.dp(600), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        TextView caption = labeled(root, "Loading the picture...");
        assertNotNull("loading has a visible explanation even between spinner animation frames", caption);
        assertTrue(caption.isShown());
        assertTrue(caption.getWidth() > 0 && caption.getHeight() > 0);
        assertEquals(14f, caption.getTextSize() / caption.getResources().getDisplayMetrics().scaledDensity, 0.01f);
        assertEquals(Dim.dp(48), screen.spinner.getWidth());
        assertEquals(Dim.dp(48), screen.spinner.getHeight());
        assertTrue("the caption sits below the spinner", caption.getTop() >= screen.spinner.getBottom());
        assertEquals("the spinner already announces the loading label", View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                caption.getImportantForAccessibility());
        screen.save.performClick();
        assertTrue("a programmatic click can't queue a save while loading", saved.isEmpty());

        screen.shown(new ProfilePictureViewer.Picture(Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888), 120));
        assertTrue(screen.save.isEnabled());
        assertEquals(View.VISIBLE, screen.picture.getVisibility());
        assertEquals(View.GONE, screen.spinner.getVisibility());
        assertEquals(View.GONE, ((View) screen.spinner.getParent()).getVisibility());
        assertFalse("the loading explanation leaves with the spinner", caption.isShown());
        screen.save.performClick();
        assertEquals(1, saved.size());
        screen.close.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("closing still cancels the viewer's fetch", screen.watch.cancelled());
    }

    @Test public void closingWhileLoadingNeverBringsThePictureOrSaveBack() {
        ProfilePictureViewer.Screen screen = new ProfilePictureViewer.Screen(activity.get(), sizes(), "someone", save);
        screen.dialog.show();
        screen.close.performClick();
        screen.shown(new ProfilePictureViewer.Picture(Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888), 120));
        assertFalse(screen.dialog.isShowing());
        ShadowLooper.idleMainLooper();
        assertTrue(screen.watch.cancelled());
        assertNull(screen.picture.getDrawable());
        assertFalse(screen.save.isEnabled());
    }

    @Test public void failingToQueueASaveKeepsTheViewerOpenAndSaysWhy() {
        ProfilePictureViewer.Screen screen = new ProfilePictureViewer.Screen(activity.get(), sizes(), "someone",
                (context, renditions, details) -> false);
        screen.dialog.show();
        screen.shown(new ProfilePictureViewer.Picture(Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888), 120));
        screen.save.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(screen.dialog.isShowing());
        assertTrue(screen.close.isEnabled());
        assertTrue(screen.save.isEnabled());
        assertEquals("Download failed", ShadowToast.getTextOfLatestToast());
    }

    @Test @SuppressWarnings("deprecation")
    public void viewerKeepsItsBlackSurfaceAndReadableSystemBarsOverALightHost() {
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        ProfilePictureViewer.Screen screen = new ProfilePictureViewer.Screen(activity.get(), sizes(), "someone", save);
        screen.dialog.show();
        View root = (View) screen.picture.getParent().getParent();
        assertEquals("the surface behind transparent system bars stays black", Color.BLACK,
                ((ColorDrawable) root.getBackground()).getColor());
        // Android 15+ forces a transparent navigation bar for this app's target SDK.
        assertEquals(Build.VERSION.SDK_INT >= 35 ? Color.TRANSPARENT : Color.BLACK,
                screen.dialog.getWindow().getNavigationBarColor());
        int flags = screen.dialog.getWindow().getDecorView().getSystemUiVisibility();
        assertEquals(0, flags & (View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController bars = screen.dialog.getWindow().getInsetsController();
            assertNotNull(bars);
            assertEquals(0, bars.getSystemBarsAppearance() & (WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS));
        }
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                screen.dialog.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
        assertTrue(screen.close.getMinimumHeight() >= Dim.dp(48));
        assertTrue(screen.save.getMinimumHeight() >= Dim.dp(48));
        assertTrue(screen.close.isFocusable());
    }

    @Test @SuppressWarnings("deprecation")
    public void navigationGestureAndKeyboardInsetsKeepTheToolbarAndPictureClear() throws Exception {
        ProfilePictureViewer.Screen screen = new ProfilePictureViewer.Screen(activity.get(), sizes(), "someone", save);
        screen.dialog.show();
        ViewGroup root = (ViewGroup) screen.picture.getParent().getParent();
        WindowInsets insets = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(11, 24, 13, 30))
                    .setInsets(WindowInsets.Type.systemGestures(), Insets.of(19, 0, 21, 42))
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 320)).build()
                : WindowInsets.class.getConstructor(Rect.class).newInstance(new Rect(11, 24, 13, 30));
        root.dispatchApplyWindowInsets(insets);
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? 19 : 11, root.getPaddingLeft());
        assertEquals(24, root.getPaddingTop());
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? 21 : 13, root.getPaddingRight());
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? 320 : 30, root.getPaddingBottom());
        View bar = (View) screen.save.getParent();
        screen.close.setText("Close the profile picture viewer");
        screen.close.setTextSize(32);
        screen.save.setText("Save the profile picture");
        screen.save.setTextSize(32);
        root.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 900, 1600);
        View preview = (View) screen.picture.getParent();
        assertEquals("the preview ends above the action bar", preview.getBottom(), bar.getTop());
        assertTrue("the picture still has room above the expanded toolbar", preview.getHeight() > 0);
        assertEquals(1600 - root.getPaddingBottom(), bar.getBottom());
        assertTrue(screen.close.getHeight() >= Dim.dp(48));
        assertTrue(screen.save.getHeight() >= Dim.dp(48));

        WindowInsets clear = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? new WindowInsets.Builder().build()
                : WindowInsets.class.getConstructor(Rect.class).newInstance(new Rect());
        root.dispatchApplyWindowInsets(clear);
        assertEquals("insets can disappear without accumulating padding", 0, root.getPaddingBottom());
    }

    @Test public void saveAfterTheSwitchWentOffSavesNothing() throws Exception {
        assertTrue(ProfilePictureViewer.open(activity.get(), sizes(), "someone", save));
        settle();
        Settings.VIEW_PROFILE_PICTURES.save(false);
        labeled(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "Save").performClick();
        assertTrue(saved.isEmpty());
    }

    @Test public void aPictureThatCantBeFetchedClosesAndSaysSo() throws Exception {
        server.serve("/gone.jpg", 404, "text/html", new byte[16], 16);
        List<MediaSave.Rendition> sizes = Collections.singletonList(
                new MediaSave.Rendition(server.origin() + "/gone.jpg", 1080, 1080, 0));
        assertTrue(ProfilePictureViewer.open(activity.get(), sizes, "someone", save));
        settle();
        assertFalse(ShadowDialog.getLatestDialog().isShowing());
        assertEquals("Couldn't open the picture", ShadowToast.getTextOfLatestToast());
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 0, 0 found, 0 missing. Counted: picture not opened 1"), HookStatus.report());
    }

    @Test public void loadReadsThePictureAndLeavesNoWorkFile() {
        server.serve("/full.jpg", "image/jpeg", jpeg(120, 80));
        ProfilePictureViewer.Picture picture =
                ProfilePictureViewer.load(application, server.origin() + "/full.jpg", Downloader.SILENT);
        assertNotNull(picture);
        assertEquals(120, picture.width);
        assertEquals(120, picture.bitmap.getWidth());
        assertEquals(80, picture.bitmap.getHeight());
        File[] left = DashSave.workFolder(application).listFiles((folder, name) -> name.startsWith("image"));
        assertEquals(0, left == null ? 0 : left.length);
    }

    /** Only Meta's media servers: an address anywhere else is never fetched. */
    @Test public void anAddressOffMetasServersIsNeverFetched() {
        assertNull(ProfilePictureViewer.load(application, "https://example.com/full.jpg", Downloader.SILENT));
    }

    @Test public void anAnswerThatIsntAPictureIsNotShown() {
        server.serve("/page.jpg", "text/html", "<html></html>".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertNull(ProfilePictureViewer.load(application, server.origin() + "/page.jpg", Downloader.SILENT));
    }

    @Test public void largestIsTheMostStatedPixels() {
        MediaSave.Rendition small = new MediaSave.Rendition("https://a/s.jpg", 150, 150, 0);
        MediaSave.Rendition big = new MediaSave.Rendition("https://a/b.jpg", 1080, 1080, 0);
        assertSame(big, ProfilePictureViewer.largest(Arrays.asList(small, big)));
        assertSame(big, ProfilePictureViewer.largest(Arrays.asList(big, small)));
        assertNull(ProfilePictureViewer.largest(Collections.emptyList()));
        assertNull(ProfilePictureViewer.largest(null));
    }

    @Test public void aLargePictureIsReadAtAPowerOfTwoBelowItsSize() {
        assertEquals(1, ProfilePictureViewer.sampleSize(1080, 1080, 4096));
        assertEquals(1, ProfilePictureViewer.sampleSize(4096, 4096, 4096));
        assertEquals(2, ProfilePictureViewer.sampleSize(5000, 3000, 4096));
        assertEquals(4, ProfilePictureViewer.sampleSize(100, 9000, 4096));
    }

    @Test public void zoomStaysBetweenFittingTheScreenAndItsMost() {
        ProfilePictureViewer.ZoomView view = new ProfilePictureViewer.ZoomView(activity.get());
        view.setImageBitmap(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888));
        view.layout(0, 0, 1000, 1000);
        float fit = view.fitScale();
        // Twice as wide as it's tall, so the width fills the square view.
        assertEquals(1000f / view.getDrawable().getIntrinsicWidth(), fit, 0.001f);
        assertEquals(fit, view.scale(), 0.001f);
        view.zoomBy(100f, 500f, 500f);
        assertEquals(fit * ProfilePictureViewer.MAX_ZOOM, view.scale(), 0.001f);
        view.zoomBy(0.001f, 500f, 500f);
        assertEquals(fit, view.scale(), 0.001f);
    }

    @Test public void noScreenToShowItOnOpensNothing() {
        activity.get().finish();
        assertFalse(ProfilePictureViewer.open(activity.get(), sizes(), "someone", save));
    }
    @Test @Config(sdk = 28)
    public void closingAViewerDoesNotQueueItsSocketCloserBehindFetches() throws Exception {
        withBusyBackgroundWorkers(() -> {
            ProfilePictureViewer.Watch watch = new ProfilePictureViewer.Watch();
            CountDownLatch closed = new CountDownLatch(1);
            watch.reading(closed::countDown);
            watch.cancel();
            assertTrue("the viewer's connection closer waited behind occupied fetch workers",
                    closed.await(2, TimeUnit.SECONDS));
            assertTrue(watch.cancelled());
        });
    }

    @Test @Config(sdk = 28)
    public void aConnectionRegisteredAfterDismissalClosesWithoutTheFetchPool() throws Exception {
        withBusyBackgroundWorkers(() -> {
            ProfilePictureViewer.Watch watch = new ProfilePictureViewer.Watch();
            watch.cancel();
            CountDownLatch closed = new CountDownLatch(1);
            watch.reading(closed::countDown);
            assertTrue("a dismissed viewer left its newly registered connection waiting",
                    closed.await(2, TimeUnit.SECONDS));
        });
    }

    private interface BusyWorkerCheck { void run() throws Exception; }

    /** Occupies every available general worker without shutting down or replacing the shared pool. */
    private static void withBusyBackgroundWorkers(BusyWorkerCheck check) throws Exception {
        Utils.awaitBackgroundTasksForTests();
        java.lang.reflect.Field field = Utils.class.getDeclaredField("backgroundThreadPool");
        field.setAccessible(true);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(null);
        int previousCore = pool.getCorePoolSize();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(pool.getMaximumPoolSize());
        try {
            // Earlier tests may have grown the pool above its core size. Occupy those workers too.
            pool.setCorePoolSize(pool.getMaximumPoolSize());
            pool.prestartAllCoreThreads();
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                assertTrue(Utils.runOnBackgroundThread(() -> {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                }));
            }
            assertTrue("the fetch workers did not start", entered.await(3, TimeUnit.SECONDS));
            check.run();
        } finally {
            release.countDown();
            pool.setCorePoolSize(previousCore);
            Utils.awaitBackgroundTasksForTests();
        }
    }


}

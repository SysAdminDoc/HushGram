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
import android.view.View;
import android.view.ViewGroup;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

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
        application.getApplicationInfo().targetSdkVersion = 36;
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
}

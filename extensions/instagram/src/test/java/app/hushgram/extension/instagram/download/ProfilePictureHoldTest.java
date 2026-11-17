/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Long press a profile picture to view it (#737): the patch hands Instagram's long press listener
 * on the profile header's picture to {@link ProfilePictureHold#hold}. Off, paused, or with no
 * account or picture, a long press stays Instagram's; on, it opens the viewer on the account the
 * profile around the picture shows.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ProfilePictureHoldTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String FULL = "https://scontent.cdninstagram.com/v/t51.2885-19/full_n.jpg?stp=dst-jpg_s1080x1080";
    private static final String SHOWN = "https://scontent.cdninstagram.com/v/t51.2885-19/full_n.jpg?stp=dst-jpg_s150x150";
    private static final String OTHER = "https://scontent.cdninstagram.com/v/t51.2885-19/other_n.jpg?stp=dst-jpg_s1080x1080";

    private ActivityController<Activity> activity;
    private final Object someone = new Object();
    private final Object other = new Object();
    private final List<String> instagram = new ArrayList<>();
    private final List<Opened> opened = new ArrayList<>();
    private boolean opens = true;
    private View.OnLongClickListener instagrams;
    private FrameLayout bar;
    private ImageView picture;

    /** One call of the viewer. */
    private static final class Opened {
        final Context context;
        final List<MediaSave.Rendition> sizes;
        final String owner;
        final ProfilePicture.Save save;
        final BooleanSupplier allowed;

        Opened(Context context, List<MediaSave.Rendition> sizes, String owner, ProfilePicture.Save save, BooleanSupplier allowed) {
            this.context = context;
            this.sizes = sizes;
            this.owner = owner;
            this.save = save;
            this.allowed = allowed;
        }
    }

    @Before public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        HookStatus.clear();
        activity = Robolectric.buildActivity(Activity.class).setup();
        LinearLayout screen = new LinearLayout(activity.get());
        screen.setOrientation(LinearLayout.VERTICAL);
        activity.get().setContentView(screen);
        bar = new FrameLayout(activity.get());
        picture = new ImageView(activity.get());
        profile(screen, bar, picture, someone);
        instagrams = view -> instagram.add("preview");
        ProfilePictureHold.reads = new Reads();
        ProfilePictureHold.viewer = (context, sizes, owner, save, allowed) -> {
            opened.add(new Opened(context, sizes, owner, save, allowed));
            return opens;
        };
    }

    @After public void restore() {
        activity.close();
        ProfilePictureHold.account = ProfileExtras::accountAround;
        ProfilePictureHold.reads = ProfilePicture.NATIVE;
        ProfilePictureHold.save = ProfilePicture.SAVE;
        ProfilePictureHold.viewer = ProfilePictureViewer::open;
        Settings.PROFILE_PICTURE_LONG_PRESS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** A profile in [screen]: its top bar, showing [user], and the header around [picture]. */
    private static void profile(LinearLayout screen, FrameLayout bar, ImageView picture, Object user) {
        LinearLayout page = new LinearLayout(screen.getContext());
        page.setOrientation(LinearLayout.VERTICAL);
        FrameLayout header = new FrameLayout(screen.getContext());
        header.addView(picture);
        page.addView(bar);
        page.addView(header);
        screen.addView(page);
        ProfileExtras.user(bar, user);
    }

    /** What the patch does: Instagram's call, sent through the hook. */
    private void bind(View view) {
        ProfilePictureHold.hold(view, instagrams);
    }

    private static List<String> urls(List<MediaSave.Rendition> sizes) {
        List<String> urls = new ArrayList<>();
        for (MediaSave.Rendition size : sizes) urls.add(size.url);
        return urls;
    }

    private static String counted(String what) {
        return FamilyNames.PROFILE_PICTURE + ": invoked 1, 0 found, 0 missing. Counted: " + what;
    }

    @Test public void theSwitchStartsOff() {
        assertFalse(Settings.PROFILE_PICTURE_LONG_PRESS.get());
        assertEquals("hushgram_profile_picture_long_press", Settings.PROFILE_PICTURE_LONG_PRESS.key);
    }

    @Test public void offTheLongPressStaysInstagrams() {
        bind(picture);
        View.OnLongClickListener set = Shadows.shadowOf(picture).getOnLongClickListener();
        assertTrue("Instagram's listener is wrapped", set instanceof ProfilePictureHold.Held);
        assertSame(instagrams, ((ProfilePictureHold.Held) set).original);
        assertTrue(picture.performLongClick());
        assertEquals(Collections.singletonList("preview"), instagram);
        assertTrue("no viewer while the switch is off", opened.isEmpty());
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE + ": invoked 1, 0 found, 0 missing"),
                HookStatus.report());
    }

    @Test public void onTheLongPressOpensTheViewerForTheProfilesAccount() {
        Settings.PROFILE_PICTURE_LONG_PRESS.save(true);
        bind(picture);
        assertTrue(picture.performLongClick());
        assertTrue("Instagram's preview doesn't open as well", instagram.isEmpty());
        assertEquals(1, opened.size());
        Opened open = opened.get(0);
        assertSame(activity.get(), open.context);
        assertEquals(java.util.Arrays.asList(FULL, SHOWN), urls(open.sizes));
        assertEquals("someone", open.owner);
        assertSame(ProfilePicture.SAVE, open.save);
        assertTrue("Save works while the switch is on", open.allowed.getAsBoolean());
        Settings.PROFILE_PICTURE_LONG_PRESS.save(false);
        assertFalse("Save stops once the switch goes off", open.allowed.getAsBoolean());
        assertTrue(HookStatus.report().get(0).endsWith("picture long press opened the viewer 1"));
    }

    @Test public void theSwitchCountsAtThePressWithNoRebind() {
        bind(picture);
        Settings.PROFILE_PICTURE_LONG_PRESS.save(true);
        assertTrue(picture.performLongClick());
        assertEquals(1, opened.size());
        Settings.PROFILE_PICTURE_LONG_PRESS.save(false);
        assertTrue(picture.performLongClick());
        assertEquals(1, opened.size());
        assertEquals(Collections.singletonList("preview"), instagram);
    }

    @Test public void eachPictureOpensItsOwnProfilesAccount() {
        Settings.PROFILE_PICTURE_LONG_PRESS.save(true);
        LinearLayout screen = (LinearLayout) bar.getParent().getParent();
        FrameLayout otherBar = new FrameLayout(activity.get());
        ImageView otherPicture = new ImageView(activity.get());
        profile(screen, otherBar, otherPicture, other);
        bind(picture);
        bind(otherPicture);
        assertTrue(otherPicture.performLongClick());
        assertTrue(picture.performLongClick());
        assertEquals(2, opened.size());
        assertEquals(Collections.singletonList(OTHER), urls(opened.get(0).sizes));
        assertEquals("other", opened.get(0).owner);
        assertEquals("someone", opened.get(1).owner);
    }

    @Test public void aBarOffScreenIsntTheProfileAround() {
        Settings.PROFILE_PICTURE_LONG_PRESS.save(true);
        // A bar the page keeps but hides, as one a screen left behind, beside the shown one.
        FrameLayout hiddenBar = new FrameLayout(activity.get());
        hiddenBar.setVisibility(View.GONE);
        ((LinearLayout) bar.getParent()).addView(hiddenBar);
        ProfileExtras.user(hiddenBar, other);
        bind(picture);
        assertTrue(picture.performLongClick());
        assertEquals("someone", opened.get(0).owner);
    }

    @Test public void withNoAccountTheLongPressStaysInstagrams() {
        Settings.PROFILE_PICTURE_LONG_PRESS.save(true);
        // A picture in a tree with no profile bar anywhere above it.
        ImageView loose = new ImageView(activity.get());
        FrameLayout alone = new FrameLayout(activity.get());
        alone.addView(loose);
        bind(loose);
        assertTrue(loose.performLongClick());
        assertEquals(Collections.singletonList("preview"), instagram);
        assertTrue(opened.isEmpty());
        assertEquals(Collections.singletonList(counted("picture long press found no account 1")), HookStatus.report());
    }

    @Test public void aProfileStillLoadingItsAccountLeavesThePressToInstagram() {
        Settings.PROFILE_PICTURE_LONG_PRESS.save(true);
        ProfileExtras.user(bar, null);
        bind(picture);
        assertTrue(picture.performLongClick());
        assertEquals(Collections.singletonList("preview"), instagram);
        assertTrue(opened.isEmpty());
    }

    @Test public void twoBarsShowingDifferentAccountsLeaveThePressToInstagram() {
        Settings.PROFILE_PICTURE_LONG_PRESS.save(true);
        LinearLayout page = (LinearLayout) bar.getParent();
        FrameLayout second = new FrameLayout(activity.get());
        page.addView(second);
        ProfileExtras.user(second, other);
        bind(picture);
        assertTrue(picture.performLongClick());
        assertEquals(Collections.singletonList("preview"), instagram);
        assertTrue(opened.isEmpty());
    }

    @Test public void pausedTheLongPressStaysInstagrams() {
        Settings.PROFILE_PICTURE_LONG_PRESS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        bind(picture);
        assertTrue(picture.performLongClick());
        assertEquals(Collections.singletonList("preview"), instagram);
        assertTrue(opened.isEmpty());
    }

    @Test public void anAccountWithNoPictureLeavesThePressToInstagram() {
        Settings.PROFILE_PICTURE_LONG_PRESS.save(true);
        ProfilePictureHold.reads = new Reads() {
            @Override public Object fullSize(Object user) { return null; }
            @Override public Object shown(Object user) { return null; }
        };
        bind(picture);
        assertTrue(picture.performLongClick());
        assertEquals(Collections.singletonList("preview"), instagram);
        assertTrue(opened.isEmpty());
        assertTrue(HookStatus.report().get(0).endsWith("picture long press found no picture 1"));
    }

    @Test public void aViewerThatDoesntOpenOrThrowsLeavesThePressToInstagram() {
        Settings.PROFILE_PICTURE_LONG_PRESS.save(true);
        opens = false;
        bind(picture);
        assertTrue(picture.performLongClick());
        assertEquals(1, opened.size());
        assertEquals(Collections.singletonList("preview"), instagram);
        assertTrue(HookStatus.report().get(0).endsWith("picture long press couldn't open the viewer 1"));
        ProfilePictureHold.viewer = (context, sizes, owner, save, allowed) -> {
            throw new IllegalStateException("no window");
        };
        assertTrue(picture.performLongClick());
        assertEquals(java.util.Arrays.asList("preview", "preview"), instagram);
    }

    @Test public void aNullListenerIsSetAsItIsAndAWrappedOneIsntWrappedTwice() {
        ProfilePictureHold.hold(picture, null);
        assertNull(Shadows.shadowOf(picture).getOnLongClickListener());
        bind(picture);
        View.OnLongClickListener held = Shadows.shadowOf(picture).getOnLongClickListener();
        ProfilePictureHold.hold(picture, held);
        assertSame(held, Shadows.shadowOf(picture).getOnLongClickListener());
    }

    @Test public void aTapStaysInstagrams() {
        Settings.PROFILE_PICTURE_LONG_PRESS.save(true);
        List<String> taps = new ArrayList<>();
        picture.setOnClickListener(view -> taps.add("tap"));
        bind(picture);
        assertTrue(picture.performClick());
        assertEquals(Collections.singletonList("tap"), taps);
        assertTrue(opened.isEmpty());
        assertTrue(instagram.isEmpty());
    }

    /** Instagram's reads of an account's picture: [someone]'s two sizes, [other]'s one. */
    private class Reads implements ProfilePicture.Native {
        @Override public boolean addRow(Object sheet, Context context, View.OnClickListener listener, String label) { return false; }
        @Override public Object fullSize(Object user) { return user; }
        @Override public String fullSizeUrl(Object info) { return info == someone ? FULL : info == other ? OTHER : null; }
        @Override public int fullSizeWidth(Object info) { return 1080; }
        @Override public int fullSizeHeight(Object info) { return 1080; }
        @Override public Object shown(Object user) { return user == someone ? user : null; }
        @Override public String shownUrl(Object image) { return SHOWN; }
        @Override public int shownWidth(Object image) { return 150; }
        @Override public int shownHeight(Object image) { return 150; }
        @Override public String username(Object user) { return user == someone ? "someone" : "other"; }
        @Override public String biography(Object user) { return null; }
    }
}

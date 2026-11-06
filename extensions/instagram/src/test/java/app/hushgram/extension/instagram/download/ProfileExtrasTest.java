/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.Menu;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The Profile extras button: first in the profile bar's right row after every bind, gone when the
 * switch is off, HushGram is paused or nothing is switched on, and a tap lists only the enabled
 * profile actions (#234).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ProfileExtrasTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private Context context;
    private ActivityController<Activity> activity;
    private FrameLayout bar;
    private LinearLayout left;
    private LinearLayout right;
    private ImageView dots;
    private final Object user = new Object();
    private final Object screen = new Object();
    private final Object session = new Object();
    private final List<String> ran = new ArrayList<>();

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        // Attached, as Instagram's bar is: a view's post() waits for a window.
        activity = Robolectric.buildActivity(Activity.class).setup();
        bar = new FrameLayout(activity.get());
        activity.get().setContentView(bar);
        left = new LinearLayout(context);
        right = new LinearLayout(context);
        bar.addView(left);
        bar.addView(right);
        dots = new ImageView(context);
        dots.setPaddingRelative(7, 9, 7, 9);
        right.addView(dots);
        Settings.PROFILE_EXTRAS_BUTTON.save(true);
        Settings.SAVE_PROFILE_PICTURES.save(true);
        HookStatus.clear();
        ShadowToast.reset();
        ProfileExtras.account = held -> held == screen ? user : null;
        ProfileExtras.source = (account, at) -> Arrays.asList(
                new ProfilePicture.Entry("Save profile picture", view -> ran.add("save")),
                new ProfilePicture.Entry("Copy bio", view -> ran.add("bio")));
    }

    @After public void restore() {
        activity.close();
        ProfileExtras.source = ProfilePicture::entries;
        ProfileExtras.account = ProfileExtras::accountOf;
        Settings.PROFILE_EXTRAS_BUTTON.resetToDefault();
        Settings.SAVE_PROFILE_PICTURES.resetToDefault();
        Settings.VIEW_PROFILE_PICTURES.resetToDefault();
        Settings.COPY_PROFILE_TEXT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** What Instagram's bind does: empties both rows, fills them, and the extension hears of it. */
    private void bind() {
        right.removeAllViews();
        right.addView(dots);
        ProfileExtras.bound(bar, right, session);
        ProfileExtras.screen(bar, screen);
        ShadowLooper.idleMainLooper();
    }

    private ImageView button() {
        return ProfileExtras.find(right);
    }

    @Test public void aScreenWithNoAccountReadsAsNone() {
        ProfileExtras.account = ProfileExtras::accountOf;
        assertNull(ProfileExtras.accountOf(new Object()));
        right.removeAllViews();
        right.addView(dots);
        ProfileExtras.bound(bar, right, session);
        ProfileExtras.screen(bar, new Object());
        ShadowLooper.idleMainLooper();
        assertNull(button());
    }

    @Test public void theSwitchStartsOff() {
        Settings.PROFILE_EXTRAS_BUTTON.resetToDefault();
        assertFalse(Settings.PROFILE_EXTRAS_BUTTON.get());
    }

    @Test public void theButtonGoesFirstInTheRightRow() {
        bind();
        assertNotNull(button());
        assertSame(button(), right.getChildAt(0));
        assertSame(dots, right.getChildAt(1));
        assertEquals("only the right row changes", 0, left.getChildCount());
        // Sized like the three dots beside it.
        assertEquals(dots.getPaddingStart(), button().getPaddingStart());
        assertEquals(dots.getPaddingTop(), button().getPaddingTop());
        assertTrue(button().isClickable());
    }

    @Test public void itIsPlacedAgainAfterEveryBind() {
        bind();
        ImageView first = button();
        assertEquals(1, right.indexOfChild(dots));
        bind();
        assertNotNull("the bind emptied the row, the button is back", button());
        assertSame(button(), right.getChildAt(0));
        assertEquals(2, right.getChildCount());
        assertTrue("a new one, since the old one was removed", first != button() || first.getParent() == right);
        assertEquals(1, count());
    }

    @Test public void aBindThatLeavesTheButtonAloneAddsNoSecond() {
        bind();
        ProfileExtras.bound(bar, right, session);
        ShadowLooper.idleMainLooper();
        assertEquals(1, count());
    }

    @Test public void offTheRowIsInstagrams() {
        Settings.PROFILE_EXTRAS_BUTTON.save(false);
        bind();
        assertNull(button());
        assertEquals(1, right.getChildCount());
    }

    @Test public void switchedOffAfterwardsTheButtonLeavesAtTheNextBind() {
        bind();
        Settings.PROFILE_EXTRAS_BUTTON.save(false);
        ProfileExtras.place(bar);
        assertNull(button());
        assertSame(dots, right.getChildAt(0));
    }

    @Test public void pausedOrUnreadyTheRowIsInstagrams() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        bind();
        assertNull(button());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(this::bind);
        assertNull(button());
    }

    @Test public void withNothingToListThereIsNoButton() {
        Settings.SAVE_PROFILE_PICTURES.save(false);
        bind();
        assertNull(button());
    }

    @Test public void theRowIsGreyedUntilOneOfItsActionsIsOn() {
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.VIEW_PROFILE_PICTURES.save(false);
        Settings.COPY_PROFILE_TEXT.save(false);
        assertFalse(Settings.PROFILE_EXTRAS_BUTTON.isAvailable());
        Settings.SAVE_PROFILE_PICTURES.save(true);
        assertTrue("Save profile picture alone is enough", Settings.PROFILE_EXTRAS_BUTTON.isAvailable());
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.VIEW_PROFILE_PICTURES.save(true);
        assertTrue("View profile picture alone is enough", Settings.PROFILE_EXTRAS_BUTTON.isAvailable());
        Settings.VIEW_PROFILE_PICTURES.save(false);
        Settings.COPY_PROFILE_TEXT.save(true);
        assertTrue("Copy username and bio alone is enough", Settings.PROFILE_EXTRAS_BUTTON.isAvailable());
    }

    @Test public void withoutAnAccountThereIsNoButton() {
        right.removeAllViews();
        right.addView(dots);
        ProfileExtras.bound(bar, right, session);
        ShadowLooper.idleMainLooper();
        assertNull(button());
        ProfileExtras.screen(bar, null);
        ProfileExtras.place(bar);
        assertNull(button());
    }

    @Test public void nothingToWorkWithThrowsNothing() {
        ProfileExtras.bound(null, right, session);
        ProfileExtras.bound(bar, null, session);
        ProfileExtras.bound(bar, new View(context), session);
        ProfileExtras.screen(null, screen);
        ProfileExtras.place(new FrameLayout(context));
        ShadowLooper.idleMainLooper();
        assertNull(button());
    }

    @Test public void aTapListsTheRowsAndARowRunsItsAction() {
        bind();
        button().performClick();
        PopupMenu menu = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull(menu);
        Menu items = menu.getMenu();
        assertEquals(2, items.size());
        assertEquals("Save profile picture", items.getItem(0).getTitle().toString());
        assertEquals("Copy bio", items.getItem(1).getTitle().toString());
        Shadows.shadowOf(menu).getOnMenuItemClickListener().onMenuItemClick(items.getItem(1));
        assertEquals(Collections.singletonList("bio"), ran);
    }

    /** The real rows: only the actions switched on are listed, and they are the three-dot menu's own. */
    @Test public void aTapListsOnlyTheActionsSwitchedOn() {
        ProfileExtras.source = (account, at) -> ProfilePicture.entries(account, at, new Reads(), (c, s, d) -> true,
                (c, s, o, save) -> true);
        Settings.VIEW_PROFILE_PICTURES.save(true);
        Settings.COPY_PROFILE_TEXT.save(false);
        bind();
        button().performClick();
        Menu items = ShadowPopupMenu.getLatestPopupMenu().getMenu();
        assertEquals(Arrays.asList("Save profile picture", "View profile picture"), titles(items));

        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.COPY_PROFILE_TEXT.save(true);
        bind();
        button().performClick();
        assertEquals(Arrays.asList("View profile picture", "Copy username", "Copy bio"),
                titles(ShadowPopupMenu.getLatestPopupMenu().getMenu()));
    }

    /** A profile opened from a link hands its rows a context with no activity under it; the screen above them has one. */
    @Test public void theActionsGetTheScreensActivityWhenTheRowHasNone() {
        List<Context> opened = new ArrayList<>();
        ProfileExtras.source = (account, at) -> ProfilePicture.entries(account, at, new Reads(), (c, s, d) -> true,
                (c, s, o, save) -> opened.add(c));
        Settings.SAVE_PROFILE_PICTURES.save(false);
        Settings.VIEW_PROFILE_PICTURES.save(true);
        bind();
        assertFalse(button().getContext() instanceof Activity);
        button().performClick();
        PopupMenu menu = ShadowPopupMenu.getLatestPopupMenu();
        assertEquals("View profile picture", menu.getMenu().getItem(0).getTitle().toString());
        Shadows.shadowOf(menu).getOnMenuItemClickListener().onMenuItemClick(menu.getMenu().getItem(0));
        assertEquals(Collections.singletonList(activity.get()), opened);
    }

    @Test public void aTapWithNothingToListSaysSo() {
        ProfileExtras.source = (account, at) -> Collections.emptyList();
        bind();
        button().performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("Nothing to do for this profile", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aTapAfterTheSwitchWentOffListsNothing() {
        bind();
        ImageView tapped = button();
        PopupMenu before = ShadowPopupMenu.getLatestPopupMenu();
        Settings.PROFILE_EXTRAS_BUTTON.save(false);
        tapped.performClick();
        assertTrue("no new menu", ShadowPopupMenu.getLatestPopupMenu() == before);
    }

    @Test public void aSourceThatThrowsLeavesTheScreenAlone() {
        ProfileExtras.source = (account, at) -> {
            throw new IllegalStateException("not an account");
        };
        bind();
        button().performClick();
        assertTrue(String.valueOf(HookStatus.report()), HookStatus.report().get(0).contains("profile extras tap"));
    }

    private int count() {
        int found = 0;
        for (int i = 0; i < right.getChildCount(); i++) {
            if (right.getChildAt(i) instanceof ImageView && ((ImageView) right.getChildAt(i)).getDrawable() instanceof ProfileExtras.Info) found++;
        }
        return found;
    }

    private ProfileExtras.AboutButton about() {
        return ProfileExtras.findAbout(right);
    }

    private void aboutOn() {
        Settings.ABOUT_ACCOUNT_BUTTON.save(true);
        ProfileExtras.available = () -> true;
        ProfileExtras.ids = held -> held == user ? "1234" : null;
        ProfileExtras.opener = (at, held, id, referer) -> ran.add("about " + id + (held == session ? " session" : "") + " from " + referer);
    }

    private void aboutOff() {
        Settings.ABOUT_ACCOUNT_BUTTON.resetToDefault();
        ProfileExtras.available = ProfileExtras::aboutAvailable;
        ProfileExtras.ids = ProfileExtras::userId;
        ProfileExtras.opener = (at, held, id, referer) -> { };
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void theTwoButtonsBesideEachOtherDontLookTheSame() {
        aboutOn();
        try {
            bind();
            android.graphics.Bitmap extras = pictureOf(button());
            android.graphics.Bitmap about = pictureOf(about());
            assertFalse("it draws at all", extras.sameAs(android.graphics.Bitmap.createBitmap(72, 72, android.graphics.Bitmap.Config.ARGB_8888)));
            assertFalse(extras.sameAs(about));
        } finally {
            aboutOff();
        }
    }

    private static android.graphics.Bitmap pictureOf(ImageView button) {
        android.graphics.Bitmap picture = android.graphics.Bitmap.createBitmap(72, 72, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.drawable.Drawable drawable = button.getDrawable();
        drawable.setBounds(0, 0, 72, 72);
        drawable.draw(new android.graphics.Canvas(picture));
        return picture;
    }

    @Test public void theAboutButtonStartsOff() {
        Settings.ABOUT_ACCOUNT_BUTTON.resetToDefault();
        assertFalse(Settings.ABOUT_ACCOUNT_BUTTON.defaultValue);
        ProfileExtras.available = () -> true;
        try {
            bind();
            assertNull(about());
        } finally {
            aboutOff();
        }
    }

    @Test public void theAboutButtonSitsAfterTheExtrasOneAndOpensTheScreenForTheAccount() {
        aboutOn();
        try {
            bind();
            bind();
            assertNotNull(about());
            assertSame(button(), right.getChildAt(0));
            assertSame(about(), right.getChildAt(1));
            assertSame(dots, right.getChildAt(2));
            assertEquals(1, countOf(ProfileExtras.AboutButton.class));
            about().performClick();
            assertEquals("the profile menu's own referer, since the call's default is its feed ads one and that fails on a Page",
                Collections.singletonList("about 1234 session from ProfileMore"), ran);
        } finally {
            aboutOff();
        }
    }

    @Test public void theAboutButtonNeedsNoOtherSwitchAndLeadsAloneWhenTheExtrasOneIsOff() {
        aboutOn();
        Settings.PROFILE_EXTRAS_BUTTON.save(false);
        try {
            bind();
            assertNull(button());
            assertSame(about(), right.getChildAt(0));
        } finally {
            aboutOff();
        }
    }

    @Test public void theAboutButtonGoesWhenPausedOrWhenThisBuildHasNoCall() {
        aboutOn();
        try {
            bind();
            assertNotNull(about());
            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            bind();
            assertNull(about());
            PauseForTests.resume();
            BaseSettings.PAUSED.save(false);
            bind();
            assertNotNull(about());
            ProfileExtras.available = () -> false;
            bind();
            assertNull(about());
        } finally {
            aboutOff();
        }
    }

    @Test public void aTapWhilePausedOrWithoutASessionOrIdOpensNothing() {
        aboutOn();
        try {
            bind();
            ProfileExtras.AboutButton button = about();
            ProfileExtras.ids = held -> null;
            button.performClick();
            ProfileExtras.ids = held -> "1234";
            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            button.performClick();
            assertTrue(ran.isEmpty());
        } finally {
            PauseForTests.resume();
            BaseSettings.PAUSED.save(false);
            aboutOff();
        }
    }

    @Test public void noSessionNoAboutButton() {
        aboutOn();
        try {
            right.removeAllViews();
            right.addView(dots);
            ProfileExtras.bound(bar, right, null);
            ProfileExtras.screen(bar, screen);
            ShadowLooper.idleMainLooper();
            assertNull(about());
        } finally {
            aboutOff();
        }
    }

    private int countOf(Class<?> type) {
        int found = 0;
        for (int i = 0; i < right.getChildCount(); i++) if (type.isInstance(right.getChildAt(i))) found++;
        return found;
    }

    private static List<String> titles(Menu menu) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) titles.add(menu.getItem(i).getTitle().toString());
        return titles;
    }

    /** An account with a picture, a username and a bio. */
    private static final class Reads implements ProfilePicture.Native {
        private static final String FULL = "https://scontent.cdninstagram.com/v/t51.2885-19/full_n.jpg?stp=dst-jpg_s1080x1080";
        private final Object info = new Object();

        @Override public boolean addRow(Object sheet, Context context, View.OnClickListener listener, String label) { return false; }
        @Override public Object fullSize(Object user) { return info; }
        @Override public String fullSizeUrl(Object info) { return FULL; }
        @Override public int fullSizeWidth(Object info) { return 1080; }
        @Override public int fullSizeHeight(Object info) { return 1080; }
        @Override public Object shown(Object user) { return null; }
        @Override public String shownUrl(Object image) { return null; }
        @Override public int shownWidth(Object image) { return 0; }
        @Override public int shownHeight(Object image) { return 0; }
        @Override public String username(Object user) { return "someone"; }
        @Override public String biography(Object user) { return "Bakes on weekends"; }
    }
}

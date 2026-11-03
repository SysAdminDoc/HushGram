/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;

import java.util.EnumSet;

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
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Black settings page (#455): on to start, with black cards and dialogs, HushGram's name at the top
 * and one line per category, and the classic page back at once when it's turned off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class BlackSettingsPageTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        Settings.CATEGORY_PAGES.save(true);
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED,
                PatchFamily.NOTIFICATION_GROUPS);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        SettingsEntry.onClosedByUser();
    }

    private void open() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private SwitchPreference blackSwitch() {
        Preference row = page.findPreference(Settings.SETTINGS_BLACK_LOOK.key);
        assertTrue("no Black settings page switch", row instanceof SwitchPreference);
        return (SwitchPreference) row;
    }

    /** What a tap on the switch does: its change listener, then the new state. */
    private void flip(boolean on) {
        SwitchPreference row = blackSwitch();
        assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, on));
        row.setChecked(on);
        ShadowLooper.idleMainLooper();
    }

    private HushgramPreferenceFragment.Heading categoryRow(String title) {
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item instanceof HushgramPreferenceFragment.Heading && title.contentEquals(item.getTitle())) {
                return (HushgramPreferenceFragment.Heading) item;
            }
        }
        throw new AssertionError("no row for " + title);
    }

    private int positionOf(Preference wanted) {
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        for (int i = 0; i < adapter.getCount(); i++) if (adapter.getItem(i) == wanted) return i;
        throw new AssertionError("not on the page: " + wanted.getKey());
    }

    /** [wanted] drawn by the page's own adapter into [recycled], or a new row when that's null. */
    private View render(Preference wanted, View recycled) {
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        View row = adapter.getView(positionOf(wanted), recycled, new FrameLayout(controller.get()));
        int width = Math.round(320 * row.getResources().getDisplayMetrics().density);
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, width, row.getMeasuredHeight());
        return row;
    }

    /** The colour of the rounded card behind a row, or behind the search field. */
    private static int cardColor(View row) {
        Drawable background = row.getBackground().getCurrent();
        if (background instanceof RippleDrawable) background = ((RippleDrawable) background).getDrawable(0);
        assertTrue("no inset card behind the row: " + background, background instanceof InsetDrawable);
        Drawable surface = ((InsetDrawable) background).getDrawable();
        assertTrue(surface instanceof GradientDrawable);
        return ((GradientDrawable) surface).getColor().getDefaultColor();
    }

    @Test public void itStartsOnWithTheHeaderBlackCardsAndOneLineCategories() throws Exception {
        open();
        SwitchPreference black = blackSwitch();
        assertEquals("Black settings page", black.getTitle().toString());
        assertTrue("on by default", black.isChecked());
        assertSame(ScreenColors.BLACK, ScreenColors.current());
        assertEquals("the header leads the page", SettingsHeader.KEY,
                page.getPreferenceScreen().getPreference(0).getKey());
        assertNull("one line per category", categoryRow("Feed").getSummary());
        assertEquals(Color.BLACK, cardColor(render(categoryRow("Feed"), null)));
    }

    @Test public void turnedOffThePageKeepsItsClassicLook() throws Exception {
        Settings.SETTINGS_BLACK_LOOK.save(false);
        open();
        assertFalse(blackSwitch().isChecked());
        assertSame(ScreenColors.DEFAULT, ScreenColors.current());
        assertNull("no header", page.getPreferenceScreen().findPreference(SettingsHeader.KEY));
        assertEquals("hushgram_settings_search", page.getPreferenceScreen().getPreference(0).getKey());
        assertNotNull("a category row keeps its line", categoryRow("Feed").getSummary());
    }

    @Test public void turningItOnBlackensTheCardsAndShortensTheCategoriesAtOnce() throws Exception {
        Settings.SETTINGS_BLACK_LOOK.save(false);
        open();
        int charcoal = cardColor(render(categoryRow("Feed"), null));
        flip(true);
        assertTrue(Settings.SETTINGS_BLACK_LOOK.savedValue());
        assertEquals(Color.BLACK, ScreenColors.current().card);
        assertEquals(Color.BLACK, ScreenColors.current().dialog);
        assertEquals("the header leads the page", SettingsHeader.KEY,
                page.getPreferenceScreen().getPreference(0).getKey());
        HushgramPreferenceFragment.Heading feed = categoryRow("Feed");
        assertNull("one line per category", feed.getSummary());
        assertTrue("still a row that opens its category", feed.collapsible);
        assertEquals(Color.BLACK, cardColor(render(feed, null)));

        flip(false);
        assertFalse(Settings.SETTINGS_BLACK_LOOK.savedValue());
        assertSame(ScreenColors.DEFAULT, ScreenColors.current());
        assertNull(page.getPreferenceScreen().findPreference(SettingsHeader.KEY));
        assertNotNull(categoryRow("Feed").getSummary());
        assertEquals(charcoal, cardColor(render(categoryRow("Feed"), null)));
    }

    @Test public void theHeaderShowsTheNameInTheGradientAndTheVersion() throws Exception {
        Settings.SETTINGS_BLACK_LOOK.save(true);
        open();
        Preference header = page.getPreferenceScreen().getPreference(0);
        assertTrue(header instanceof SettingsHeader);
        assertFalse("nothing to tap", header.isSelectable());
        View view = render(header, null);
        TextView name = view.findViewWithTag(SettingsHeader.NAME);
        assertEquals("HushGram", name.getText().toString());
        assertTrue("in Instagram's gradient", ShimmerTitle.wears(name));
        assertTrue(name.isAccessibilityHeading());
        TextView version = view.findViewWithTag(SettingsHeader.VERSION);
        assertTrue(version.getText().toString(), version.getText().toString().startsWith("Version "));
        ImageView icon = view.findViewWithTag(SettingsHeader.ICON);
        assertNotNull(icon.getDrawable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
        assertTrue("the icon sits above the name", view.getHeight() > name.getHeight() + version.getHeight());
    }

    @Test public void aSearchKeepsTheHeaderAndFindsTheSwitchByAmoled() throws Exception {
        Settings.SETTINGS_BLACK_LOOK.save(true);
        open();
        page.searchSettings("amoled");
        ShadowLooper.idleMainLooper();
        assertEquals("the header stays put while typing", SettingsHeader.KEY,
                page.getPreferenceScreen().getPreference(0).getKey());
        assertTrue("a search for amoled finds it", positionOf(blackSwitch()) > 0);
        page.searchSettings("");
        ShadowLooper.idleMainLooper();
        assertEquals(SettingsHeader.KEY, page.getPreferenceScreen().getPreference(0).getKey());
    }

    @Test public void theSearchFieldsCardFollowsTheSwitchOnItsNextBind() throws Exception {
        Settings.SETTINGS_BLACK_LOOK.save(false);
        open();
        Preference search = page.findPreference("hushgram_settings_search");
        View field = render(search, null);
        assertEquals(ScreenColors.DEFAULT.card, cardColor(field));
        int start = field.getPaddingStart(), end = field.getPaddingEnd();
        flip(true);
        assertEquals(Color.BLACK, cardColor(render(search, field)));
        assertEquals("the field keeps its place in the card", start, field.getPaddingStart());
        assertEquals(end, field.getPaddingEnd());
        flip(false);
        assertEquals(ScreenColors.DEFAULT.card, cardColor(render(search, field)));
    }
}

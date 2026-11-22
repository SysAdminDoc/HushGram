/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

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
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;

/**
 * The dot beside a setting added since the settings were last opened (#274): none on a first
 * open, one on the row and on its category's opener when the stored set lacks it, gone once the
 * page has been left, none in a search, and no harm done by a stored value that isn't a set.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w411dp-h891dp-xxhdpi")
@SuppressWarnings("deprecation")
public class NewRowDotsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Built when asked: a static field would load Settings before the rule gives it a context. */
    private static String key() {
        return Settings.SCROLL_BREAK.key;
    }

    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;
    private String categoryTitle;
    private String rowTitle;

    @Before public void prepare() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        locate(key());
    }

    /** Reads [key]'s page and title once from the long list, then starts again with nothing stored. */
    private void locate(String key) {
        Settings.CATEGORY_PAGES.save(false);
        open();
        Preference row = page.findPreference(key);
        assertNotNull(row);
        categoryTitle = String.valueOf(((PreferenceCategory) row.getParent()).getTitle());
        rowTitle = String.valueOf(row.getTitle());
        controller.close();
        controller = null;
        Settings.CATEGORY_PAGES.save(true);
        SharedPreferences store = store();
        assertTrue(store.edit().clear().commit());
    }

    @After public void restore() {
        if (controller != null) controller.close();
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.DESCRIPTIONS_BEHIND_INFO.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        BaseSettings.PAUSED.resetToDefault();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
    }

    /** Opens the settings on a fresh screen, as after the app was closed. */
    private void open() {
        if (controller != null) controller.close();
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        page = DownloadSettingsTest.pageIn(controller);
        ShadowLooper.idleMainLooper();
    }

    private SharedPreferences store() {
        return RuntimeEnvironment.getApplication().getSharedPreferences(NewRowDots.FILE, 0);
    }

    private ListView list() {
        return page.getView().findViewById(android.R.id.list);
    }

    /** The views the list draws now, in order. */
    private List<View> views() {
        ListView list = list();
        List<View> views = new ArrayList<>();
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            View row = list.getAdapter().getView(i, null, list);
            // These inspection views never attach, so their Material button/switch animators
            // won't receive the detach cleanup the real list performs when recycling rows.
            row.jumpDrawablesToCurrentState();
            views.add(row);
        }
        return views;
    }

    private static boolean dotted(View row) {
        TextView title = row.findViewById(android.R.id.title);
        return title != null && NewRowDots.TAG.equals(title.getTag());
    }

    private List<String> dottedTitles() {
        List<String> titles = new ArrayList<>();
        for (View row : views()) {
            if (dotted(row)) titles.add(String.valueOf(((TextView) row.findViewById(android.R.id.title)).getText()));
        }
        return titles;
    }

    private void forget(String key) {
        open();
        // Closed before the store is edited, so its leaving can't write over the edit.
        controller.close();
        controller = null;
        Set<String> seen = new HashSet<>(store().getStringSet(NewRowDots.SEEN, new HashSet<>()));
        assertTrue(key + " was never seen", seen.remove(key));
        assertTrue(store().edit().putStringSet(NewRowDots.SEEN, seen).commit());
        open();
    }

    private Preference opener() {
        for (int i = 0; i < page.getPreferenceScreen().getPreferenceCount(); i++) {
            Preference each = page.getPreferenceScreen().getPreference(i);
            String key = each.getKey();
            if (key != null && key.startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY)
                    && categoryTitle.contentEquals(String.valueOf(each.getTitle()))) return each;
        }
        throw new AssertionError("no opener for " + categoryTitle);
    }

    /** Scrolls the page until [key]'s row is on screen, as a person scrolling to it would. */
    private void scrollTo(String key) {
        ListView list = list();
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            Object item = list.getAdapter().getItem(i);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                list.setSelection(i);
                ShadowLooper.idleMainLooper();
                assertTrue(key + " came on screen",
                        i >= list.getFirstVisiblePosition() && i <= list.getLastVisiblePosition());
                return;
            }
        }
        throw new AssertionError("no row for " + key);
    }

    private void enter() {
        Preference opener = opener();
        assertTrue(opener.getOnPreferenceClickListener().onPreferenceClick(opener));
        ShadowLooper.idleMainLooper();
    }

    @Test public void aFirstOpenShowsNoDotAnywhere() {
        open();
        assertTrue(store().getStringSet(NewRowDots.SEEN, null).contains(key()));
        assertEquals(new ArrayList<String>(), dottedTitles());
        enter();
        assertEquals(new ArrayList<String>(), dottedTitles());
        enter();
        ShadowLooper.idleMainLooper();
        assertEquals(new ArrayList<String>(), dottedTitles());
    }

    @Test public void aKeyMissingFromTheStoredSetDotsItsRowAndItsOpenerUntilThePageIsLeft() {
        forget(key());
        assertEquals("only the category's opener", Collections.singletonList(categoryTitle), dottedTitles());

        enter();
        assertEquals("only the new row", Collections.singletonList(rowTitle), dottedTitles());
        TextView title = null;
        for (View view : views()) if (dotted(view)) title = view.findViewById(android.R.id.title);
        assertNotNull(title);
        assertTrue(title.getContentDescription().toString().endsWith(", New"));
        // Opening it doesn't clear it: the dot shows for the whole visit.
        assertFalse(store().getStringSet(NewRowDots.SEEN, null).contains(key()));

        enter();
        ShadowLooper.idleMainLooper();
        assertTrue(store().getStringSet(NewRowDots.SEEN, null).contains(key()));
        assertEquals("the opener is clear once the page was seen", new ArrayList<String>(), dottedTitles());
        enter();
        assertEquals(new ArrayList<String>(), dottedTitles());
    }

    /**
     * Leaving a page counts only the new rows that came on screen. One further down that was never
     * scrolled to keeps its dot, and its category's, for the next visit (#689): going back before
     * reaching it used to clear it unseen.
     */
    @Test public void aNewRowThatNeverCameOnScreenKeepsItsDot() {
        open();
        enter();
        ListView list = list();
        int lastOnScreen = list.getLastVisiblePosition();
        assertTrue("the page is laid out", lastOnScreen >= 0);
        String near = null;
        String far = null;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            Object item = list.getAdapter().getItem(i);
            if (!(item instanceof Preference)) continue;
            String rowKey = ((Preference) item).getKey();
            if (rowKey == null || Setting.getSettingFromPath(rowKey) == null) continue;
            if (i <= lastOnScreen && near == null) near = rowKey;
            if (i > lastOnScreen + 2) far = rowKey;
        }
        assertNotNull("a setting on screen", near);
        assertNotNull("the page runs on past the screen", far);
        controller.close();
        controller = null;
        Set<String> seen = new HashSet<>(store().getStringSet(NewRowDots.SEEN, new HashSet<>()));
        assertTrue(seen.remove(near));
        assertTrue(seen.remove(far));
        assertTrue(store().edit().putStringSet(NewRowDots.SEEN, seen).commit());

        open();
        enter();
        enter();
        ShadowLooper.idleMainLooper();

        Set<String> after = store().getStringSet(NewRowDots.SEEN, null);
        assertTrue("the new row on screen is seen", after.contains(near));
        assertFalse("the one never scrolled to is still new", after.contains(far));
        assertEquals("so its category keeps its dot", Collections.singletonList(categoryTitle), dottedTitles());
    }

    @Test public void closingTheSettingsOnAPageCountsWhatCameOnScreenAsSeen() {
        forget(key());
        enter();
        scrollTo(key());
        open();
        assertEquals(new ArrayList<String>(), dottedTitles());
    }

    @Test public void leavingTheListWithoutOpeningTheCategoryKeepsItsDot() {
        forget(key());
        open();
        assertEquals(Collections.singletonList(categoryTitle), dottedTitles());
        assertFalse(store().getStringSet(NewRowDots.SEEN, null).contains(key()));
    }

    @Test public void withTheCategoriesOffTheListShowsTheRowAndClosingClearsIt() {
        Settings.CATEGORY_PAGES.save(false);
        forget(key());
        assertEquals(Collections.singletonList(rowTitle), dottedTitles());
        open();
        assertEquals(new ArrayList<String>(), dottedTitles());
    }

    @Test public void searchResultsShowNoDots() {
        forget(key());
        page.searchSettings(rowTitle);
        ShadowLooper.idleMainLooper();
        assertTrue(list().getAdapter().getCount() > 0);
        assertEquals(new ArrayList<String>(), dottedTitles());
        page.searchSettings("");
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList(categoryTitle), dottedTitles());
    }

    @Test public void aStoredValueThatIsNotASetFailsOpenAndIsStartedAgain() {
        open();
        assertTrue(store().edit().remove(NewRowDots.SEEN).putString(NewRowDots.SEEN, "garbage").commit());
        open();
        assertEquals(new ArrayList<String>(), dottedTitles());
        assertTrue(store().getStringSet(NewRowDots.SEEN, null).contains(key()));
    }

    @Test public void theSeenSetIsNotInAConfigurationBackup() {
        open();
        for (String key : ConfigurationBackup.eligible().keySet()) {
            assertFalse(key, key.contains(NewRowDots.FILE) || key.equals(NewRowDots.SEEN));
        }
    }

    @Test public void theDotIsAfterTheTitleAndTheSameSizeAsTheOthersAcrossTheRow() {
        forget(key());
        enter();
        int width = controller.get().getResources().getDisplayMetrics().widthPixels;
        int checked = 0;
        for (View row : views()) {
            if (!dotted(row)) continue;
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, width, row.getMeasuredHeight());
            TextView title = row.findViewById(android.R.id.title);
            Drawable[] sides = title.getCompoundDrawablesRelative();
            assertNotNull("the dot is not after the title", sides[2]);
            assertNull(sides[0]);
            int size = Math.round(8 * controller.get().getResources().getDisplayMetrics().density);
            assertEquals(size, sides[2].getBounds().width());
            checked++;
        }
        assertEquals(1, checked);
    }

    /** The dotted row's view, measured and laid out at the screen's width. */
    private View dottedRow() {
        int width = controller.get().getResources().getDisplayMetrics().widthPixels;
        View found = null;
        for (View row : views()) if (dotted(row)) found = row;
        assertNotNull("no dotted row", found);
        found.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        found.layout(0, 0, width, found.getMeasuredHeight());
        return found;
    }

    /**
     * #689: with descriptions behind the "i" (on to start since #650), the "i" took the dot's place on
     * every new switch, so a category's opener said something was new and its page showed no dot.
     * The new switch shows both, the "i" still opens its description, and the visit after shows the
     * "i" alone, on a reused row too.
     */
    @Test
    // Real text widths, so the title is as wide as its words and the start of it is clear of the i.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aNewSwitchWithItsDescriptionBehindTheInfoMarkKeepsItsDot() {
        Settings.DESCRIPTIONS_BEHIND_INFO.resetToDefault();
        assertTrue(Settings.DESCRIPTIONS_BEHIND_INFO.savedValue());
        String key = Settings.HIDE_CHAT_BLEND.key;
        locate(key);
        forget(key);
        assertEquals(Collections.singletonList(categoryTitle), dottedTitles());

        enter();
        assertEquals("the new switch keeps its dot", Collections.singletonList(rowTitle), dottedTitles());
        View row = dottedRow();
        TextView title = row.findViewById(android.R.id.title);
        assertEquals("its description is behind the i", View.GONE,
                row.findViewById(android.R.id.summary).getVisibility());
        assertTrue(title.getContentDescription().toString().endsWith(", New"));
        Drawable end = title.getCompoundDrawablesRelative()[2];
        assertTrue("the dot and the i side by side: " + end, end instanceof DescriptionInfo.Beside);
        DescriptionInfo.Beside both = (DescriptionInfo.Beside) end;
        float density = controller.get().getResources().getDisplayMetrics().density;
        assertEquals("the dot comes first, its usual size", Math.round(8 * density), both.first.getBounds().width());
        assertTrue("then the i", both.second instanceof DescriptionInfo.Mark);
        assertEquals(both.first.getBounds().width() + both.gap + both.second.getBounds().width(),
                both.getBounds().width());
        assertEquals("the dot sits as far from the title as on any new row", Math.round(8 * density),
                title.getCompoundDrawablePadding());
        int right = title.getWidth() - title.getPaddingRight();
        assertTrue("a tap on the i still opens the description", DescriptionInfo.onMark(title, right - 1));
        int reach = both.second.getBounds().width() + both.gap + Math.round(12 * density);
        assertTrue("with as much room around it as on a row that isn't new",
                DescriptionInfo.onMark(title, right - reach + 1));
        assertTrue("the title is wider than its dot and i, " + title.getWidth(), title.getWidth() > 2 * reach);
        assertFalse("the title before it still flips the switch",
                DescriptionInfo.onMark(title, title.getPaddingLeft() + 1));

        enter();
        enter();
        assertEquals("seen now", new ArrayList<String>(), dottedTitles());
        View reused = page.findPreference(key).getView(row, list());
        TextView again = reused.findViewById(android.R.id.title);
        assertEquals(DescriptionInfo.TAG, again.getTag());
        assertTrue("the i alone", again.getCompoundDrawablesRelative()[2] instanceof DescriptionInfo.Mark);
        assertNull(again.getContentDescription());
    }
}

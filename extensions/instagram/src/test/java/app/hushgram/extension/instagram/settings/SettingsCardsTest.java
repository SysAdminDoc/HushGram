/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.text.Layout;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumSet;
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
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The overview and dedicated category pages use the same monochrome component system. Rows retain
 * their original icons, bindings and accessibility semantics, and text wraps at larger font sizes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class SettingsCardsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        // Keep the retired appearance key non-default to verify it no longer changes the palette.
        Settings.SETTINGS_BLACK_LOOK.save(false);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        RuntimeEnvironment.setFontScale(1f);
        SettingsEntry.onClosedByUser();
    }

    private void open(boolean pages, EnumSet<PatchFamily> build) throws Exception {
        Settings.CATEGORY_PAGES.save(pages);
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private List<Preference> onScreen() {
        PreferenceScreen screen = page.getPreferenceScreen();
        List<Preference> shown = new ArrayList<>();
        for (int i = 0; i < screen.getPreferenceCount(); i++) shown.add(screen.getPreference(i));
        return shown;
    }

    private List<Preference> categoryRows() {
        List<Preference> rows = new ArrayList<>();
        for (Preference item : onScreen()) {
            if (item instanceof HushgramPreferenceFragment.Heading
                    && ((HushgramPreferenceFragment.Heading) item).collapsible) rows.add(item);
        }
        return rows;
    }

    private Preference categoryRow(String title) {
        for (Preference row : categoryRows()) if (title.contentEquals(row.getTitle())) return row;
        throw new AssertionError("no row for " + title);
    }

    @SuppressWarnings("unchecked")
    private <T extends Preference> T titled(String title) {
        for (Preference item : onScreen()) {
            if (title.contentEquals(item.getTitle())) return (T) item;
            if (!(item instanceof PreferenceCategory)) continue;
            PreferenceCategory group = (PreferenceCategory) item;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                if (title.contentEquals(group.getPreference(i).getTitle())) return (T) group.getPreference(i);
            }
        }
        throw new AssertionError("nothing titled " + title);
    }

    private void tap(Preference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
    }

    /** [wanted] drawn by the page's own adapter, at the test screen's 320 dp width. */
    private View render(Preference wanted) {
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i) != wanted) continue;
            View row = adapter.getView(i, null, new FrameLayout(controller.get()));
            int width = Math.round(320 * row.getResources().getDisplayMetrics().density);
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, width, row.getMeasuredHeight());
            return row;
        }
        throw new AssertionError("not on the page: " + wanted.getTitle());
    }

    /** The rounded surface a row draws its card with. */
    private static GradientDrawable card(View row) {
        Drawable background = row.getBackground();
        if (background instanceof RippleDrawable) background = ((RippleDrawable) background).getDrawable(0);
        assertTrue("no inset card behind the row: " + background, background instanceof InsetDrawable);
        Drawable surface = ((InsetDrawable) background).getDrawable();
        assertTrue(surface instanceof GradientDrawable);
        return (GradientDrawable) surface;
    }

    private static boolean roundedTop(GradientDrawable card) {
        float[] radii = card.getCornerRadii();
        return radii[0] > 0 && radii[2] > 0;
    }

    private static boolean roundedBottom(GradientDrawable card) {
        float[] radii = card.getCornerRadii();
        return radii[4] > 0 && radii[6] > 0;
    }

    private static boolean hasChevron(View row) {
        View chevron = row.findViewWithTag(ScreenColors.CHEVRON);
        return chevron != null && chevron.getVisibility() == View.VISIBLE
                && ((View) chevron.getParent()).getVisibility() == View.VISIBLE;
    }

    @Test public void everyCategoryOpensASeparatePageAndBackReturnsToTheOverview() throws Exception {
        open(true, EnumSet.allOf(PatchFamily.class));
        List<Preference> categories = categoryRows();
        assertEquals("every HushGram category remains available", 23, categories.size());
        for (Preference row : categories) {
            HushgramPreferenceFragment.Heading section = (HushgramPreferenceFragment.Heading) row;
            assertEquals(0, section.getPreferenceCount());
            tap(section);
            PreferenceScreen screen = page.getPreferenceScreen();
            assertSame(section, screen.findPreference(section.getKey()));
            assertTrue(section.pageHeader);
            assertFalse(section.collapsible);
            assertNull("category descriptions are not displayed", section.getSummary());
            if (!"About".equals(section.getTitle().toString())) {
                assertTrue(section.getTitle() + " has its original controls", section.getPreferenceCount() > 0);
                assertSame("the section heading precedes its original rows", section.getPreference(0),
                        screen.getRootAdapter().getItem(1));
            }
            assertNull("overview search is not shown on a section page", screen.findPreference("hushgram_settings_search"));
            assertEquals(List.of(section.getTitle().toString()), visibleHeadings());
            assertTrue("Back returns to the overview", page.closeCategory());
            assertEquals(23, categoryRows().size());
        }
    }

    private List<String> visibleHeadings() {
        List<String> titles = new ArrayList<>();
        for (Preference item : onScreen()) {
            if (item instanceof HushgramPreferenceFragment.Heading) titles.add(String.valueOf(item.getTitle()));
        }
        return titles;
    }

    @Test public void searchKeepsFeatureRowsAndClearingItReturnsToTheOverviewModel() throws Exception {
        open(true, EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED));
        String feedKey = categoryRow("Feed").getKey();
        page.searchSettings("Hide ads");
        Preference ads = page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key);
        assertNotNull("search finds the original switch", ads);
        assertNotNull(ads.getParent());
        assertNull("an unrelated section is omitted from matching results",
                page.getPreferenceScreen().findPreference(feedKey));
        page.searchSettings("");
        HushgramPreferenceFragment.Heading feed = (HushgramPreferenceFragment.Heading) categoryRow("Feed");
        assertTrue(feed.collapsible);
        assertFalse(feed.pageHeader);
        assertNull("category descriptions are not displayed", feed.getSummary());
        tap(feed);
        assertTrue(feed.pageHeader);
        assertFalse(feed.collapsible);
        assertTrue(page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key) != null);
        assertTrue(page.closeCategory());
    }

    @Test public void aSectionsRowsShareOneRoundedCard() throws Exception {
        open(false, EnumSet.of(PatchFamily.FOLLOWING_FEED));
        tap(categoryRow("Feed"));
        Preference first = page.findPreference(Settings.START_ON_FOLLOWING.key);
        Preference middle = page.findPreference(Settings.ONLY_FOLLOWING.key);
        Preference last = page.findPreference(Settings.FOLLOWING_MIX.key);
        assertSame("one section holds them all", first.getParent(), last.getParent());
        assertTrue(ScreenColors.opensCard(first));
        assertFalse(ScreenColors.closesCard(first));
        assertFalse(ScreenColors.opensCard(middle));
        assertFalse(ScreenColors.closesCard(middle));
        assertFalse(ScreenColors.opensCard(last));
        assertTrue(ScreenColors.closesCard(last));

        GradientDrawable top = card(render(first));
        assertTrue(roundedTop(top));
        assertFalse(roundedBottom(top));
        GradientDrawable inside = card(render(middle));
        assertFalse(roundedTop(inside));
        assertFalse(roundedBottom(inside));
        GradientDrawable bottom = card(render(last));
        assertFalse(roundedTop(bottom));
        assertTrue(roundedBottom(bottom));
        assertEquals("the card takes the page's colours", ScreenColors.DEFAULT.card, top.getColor().getDefaultColor());
        assertTrue(page.closeCategory());
    }

    @Test public void theOverviewUsesMonochromeConnectedCategoryRows() throws Exception {
        open(true, EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED, PatchFamily.NOTIFICATION_GROUPS));
        List<Preference> rows = categoryRows();
        assertEquals(23, rows.size());
        Preference first = categoryRow("Ads and privacy");
        Preference entry = categoryRow("Settings entry");
        Preference backup = categoryRow("Settings backup");
        Preference about = categoryRow("About");
        assertSame("the everyday feature sections appear first", first, rows.get(0));
        assertTrue(ScreenColors.opensCard(first));
        assertFalse(ScreenColors.closesCard(first));
        assertTrue("settings entry remains before backup", rows.indexOf(entry) < rows.indexOf(backup));
        assertTrue("About remains at the end of the category list", rows.get(rows.size() - 1) == about);
        for (Preference row : rows.subList(1, rows.size())) {
            assertFalse(row.getTitle() + " is not the first connected row", ScreenColors.opensCard(row));
            assertFalse(row.getTitle() + " is joined to the next row", ScreenColors.closesCard(row));
        }
        assertNull("the obsolete list/grid option is not rendered", page.getPreferenceScreen().findPreference("hushgram_view_toggle"));
        assertNull(page.getPreferenceScreen().findPreference("hushgram_grid_toggle"));
        assertNull(page.getPreferenceScreen().findPreference(Settings.CATEGORY_PAGES.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.SETTINGS_BLACK_LOOK.key));
        Preference report = page.findPreference(HushgramPreferenceFragment.REPORT_ROW_KEY);
        Preference support = page.findPreference(HushgramPreferenceFragment.SUPPORT_ROW_KEY);
        assertFalse(ScreenColors.opensCard(report));
        assertFalse(ScreenColors.closesCard(report));
        assertFalse(ScreenColors.opensCard(support));
        assertTrue(ScreenColors.closesCard(support));
        View drawn = render(entry);
        ImageView icon = drawn.findViewById(android.R.id.icon);
        assertNull("the line icon has no decorative tile", icon.getBackground());
        assertTrue("rows keep a touch target", drawn.getHeight() >= 48 * drawn.getResources().getDisplayMetrics().density);
    }

    @Test public void navigationRowsAndActionRowsUseChevronsConsistently() throws Exception {
        open(true, EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED));
        Preference ads = categoryRow("Ads and privacy");
        assertTrue("a category row opens a section", hasChevron(render(ads)));
        assertEquals(1, ((ScreenColors.Chevron) ((ImageView) render(ads).findViewWithTag(ScreenColors.CHEVRON)).getDrawable()).direction);
        tap(ads);
        HushgramPreferenceFragment.Heading section = (HushgramPreferenceFragment.Heading)
                page.getPreferenceScreen().findPreference(ads.getKey());
        assertTrue(section.pageHeader);
        assertFalse("the large section headline is not a second navigation control",
                hasChevron(render(section)));
        assertNull("the obsolete presentation option is hidden", page.getPreferenceScreen().findPreference(Settings.CATEGORY_PAGES.key));
        assertTrue(page.closeCategory());
        tap(categoryRow("Settings entry"));
        assertTrue("a choice row opens its native editor",
                hasChevron(render(page.findPreference(Settings.NAVIGATION_SETTINGS_TARGET.key))));
        assertTrue(page.closeCategory());
        tap(categoryRow("About"));
        assertFalse("Version only reports", hasChevron(render(titled("Version"))));
        assertTrue("Licenses opens a dialog", hasChevron(render(titled("Licenses"))));
        assertTrue(page.closeCategory());
    }

    @Test public void everyFeatureRowKeepsItsIconAndItsOriginalAccessibleSwitchState() throws Exception {
        open(false, EnumSet.allOf(PatchFamily.class));
        List<Preference> categories = new ArrayList<>(categoryRows());
        int rows = 0;
        for (Preference item : categories) {
            tap(item);
            HushgramPreferenceFragment.Heading group = (HushgramPreferenceFragment.Heading)
                    page.getPreferenceScreen().findPreference(item.getKey());
            for (int i = 0; i < group.getPreferenceCount(); i++, rows++) {
                Preference row = group.getPreference(i);
                assertNotNull(group.getTitle() + " > " + row.getTitle() + " has an icon", row.getIcon());
            }
            page.closeCategory();
        }
        assertTrue("the feature inventory covers many settings", rows > 100);
        tap(categoryRow("Ads and privacy"));
        Preference toggle = page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key);
        View row = render(toggle);
        ImageView icon = row.findViewById(android.R.id.icon);
        assertNotNull(icon);
        assertEquals(View.VISIBLE, icon.getVisibility());
        assertEquals("the icon is decoration", View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
        assertNull(icon.getContentDescription());
        AccessibilityNodeInfo node = row.createAccessibilityNodeInfo();
        assertEquals("the switch reads its name and explanation", toggle.getTitle() + "\n" + toggle.getSummary(),
                String.valueOf(node.getContentDescription()));
        node.recycle();
        page.closeCategory();
    }

    @Test public void aCategoryButtonReadsItsLabelAndSkipsTheDecorativeIcon() throws Exception {
        open(true, EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED));
        Preference feed = categoryRow("Feed");
        assertNotNull(feed.getIcon());
        View row = render(feed);
        ImageView icon = row.findViewById(android.R.id.icon);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
        AccessibilityNodeInfo node = row.createAccessibilityNodeInfo();
        assertEquals(Button.class.getName(), String.valueOf(node.getClassName()));
        assertTrue(String.valueOf(node.getContentDescription()).startsWith(feed.getTitle() + "\n" + feed.getSummary()));
        node.recycle();
    }

    @Test public void aRowsIconFollowsTheWordsOfItsKey() {
        String fallback = SettingsIcons.FEED;
        assertSame(SettingsIcons.SEARCH, SettingsIcons.forKey(Settings.DONT_SAVE_RECENT_SEARCHES.key, fallback));
        assertSame(SettingsIcons.DOWNLOADS, SettingsIcons.forKey(Settings.DOWNLOAD_REELS.key, fallback));
        assertSame(SettingsIcons.LOCK, SettingsIcons.forKey(Settings.LOCK_MESSAGES.key, fallback));
        assertSame(SettingsIcons.LIKE, SettingsIcons.forKey(Settings.SHOW_HIDDEN_LIKE_COUNTS.key, fallback));
        assertSame(SettingsIcons.HIDDEN, SettingsIcons.forKey(Settings.VIEW_STORIES_ANONYMOUSLY.key, fallback));
        assertSame("whole words only: block isn't lock", SettingsIcons.STORIES,
                SettingsIcons.forKey(Settings.BLOCK_STORY_AUTO_ADVANCE.key, fallback));
        assertSame(fallback, SettingsIcons.forKey(Settings.HIDE_SUGGESTED_POSTS.key, fallback));
        assertSame(fallback, SettingsIcons.forKey(null, fallback));
    }

    @Test public void aHistoryRowWearsTheClockAndClearingOneKeepsTheBin() {
        String fallback = SettingsIcons.FEED;
        assertSame(SettingsIcons.UPDATES, SettingsIcons.forKey(Settings.VIEWING_HISTORY.key, fallback));
        assertSame(SettingsIcons.DELETE, SettingsIcons.forKey(HushgramPreferenceFragment.CLEAR_VIEWING_HISTORY, fallback));
        assertSame("an earlier word still wins", SettingsIcons.DOWNLOADS,
                SettingsIcons.forKey(HushgramPreferenceFragment.CLEAR_DOWNLOAD_HISTORY, fallback));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void categoryDescriptionsAreOmittedAndRowsScaleAtTwiceTheSize() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        open(true, EnumSet.of(PatchFamily.HIDE_ADS));
        Preference adsRow = categoryRow("Ads and privacy");
        assertNull("overview categories have no descriptions", adsRow.getSummary());
        assertWhole(render(adsRow));
        tap(adsRow);
        View header = render(adsRow);
        assertNull("section headings have no descriptions", adsRow.getSummary());
        assertTrue(header.isAccessibilityHeading());
        assertWhole(header);
        assertWhole(render(page.findPreference(Settings.HIDE_ADS.key)));
    }

    /** Every line of [row]'s text is drawn in full, inside the row. */
    private static void assertWhole(View row) {
        int texts = 0;
        for (int which : new int[]{android.R.id.title, android.R.id.summary}) {
            TextView text = row.findViewById(which);
            if (text == null || text.getVisibility() != View.VISIBLE || text.getText().length() == 0) continue;
            texts++;
            Layout layout = text.getLayout();
            assertNotNull(text.getText() + " was never laid out", layout);
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals(text.getText() + " line " + line + " is cut short", 0, layout.getEllipsisCount(line));
            }
            assertTrue(text.getText() + " is taller than its view",
                    layout.getHeight() <= text.getHeight() - text.getTotalPaddingTop() - text.getTotalPaddingBottom() + 1);
            int bottom = text.getHeight();
            for (View at = text; at != row; at = (View) at.getParent()) bottom += at.getTop();
            assertTrue(text.getText() + " runs past the bottom of its row", bottom <= row.getHeight());
        }
        assertTrue("the row drew no text", texts > 0);
    }
}

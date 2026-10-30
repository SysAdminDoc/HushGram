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
 * Categories expand directly under their buttons, without repeating their title or introduction.
 * Rows share rounded cards under the section buttons, every row leads with a decorative icon, and
 * only a row that opens something ends in a chevron. Text still wraps at twice the size.
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
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
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

    @Test public void everyCategoryExpandsItsControlsBelowTheSameButton() throws Exception {
        open(true, EnumSet.allOf(PatchFamily.class));
        List<Preference> categories = categoryRows();
        assertEquals("every category, Pause and diagnostics included", 23, categories.size());
        for (Preference row : categories) {
            HushgramPreferenceFragment.Heading group = (HushgramPreferenceFragment.Heading) row;
            assertEquals(0, group.getPreferenceCount());
            tap(group);
            assertSame(group, categoryRow(group.getTitle().toString()));
            assertTrue(group.getTitle() + " has controls", group.getPreferenceCount() > 0);
            assertEquals(group.intro, group.getSummary());
            assertTrue(group.getSummary().length() <= 90);
            ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
            int at = -1;
            for (int i = 0; i < adapter.getCount(); i++) if (adapter.getItem(i) == group) at = i;
            assertTrue(at >= 0);
            assertSame("no duplicate heading or intro separates the controls", group.getPreference(0), adapter.getItem(at + 1));
            assertEquals(categories, categoryRows());
            tap(group);
            assertEquals(0, group.getPreferenceCount());
        }
    }

    @Test public void searchAndTheLongListUsePlainHeadingsThenRestoreTheExpandedButton() throws Exception {
        open(true, EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED));
        HushgramPreferenceFragment.Heading feed = (HushgramPreferenceFragment.Heading) categoryRow("Feed");
        tap(feed);
        assertTrue(feed.isSelectable());
        page.searchSettings("Hide ads");
        assertFalse(feed.isSelectable());
        assertNull(feed.getSummary());
        page.searchSettings("");
        assertTrue(feed.isSelectable());
        assertTrue(feed.expanded);
        assertEquals(feed.intro, feed.getSummary());
        Settings.CATEGORY_PAGES.save(false);
        page.searchSettings("");
        assertFalse(feed.isSelectable());
        assertNull(feed.getSummary());
        ShadowLooper.idleMainLooper();
        assertFalse(hasChevron(render(feed)));
    }

    @Test public void aSectionsRowsShareOneRoundedCard() throws Exception {
        open(false, EnumSet.of(PatchFamily.FOLLOWING_FEED));
        Preference first = page.findPreference(Settings.START_ON_FOLLOWING.key);
        Preference middle = page.findPreference(Settings.ONLY_FOLLOWING.key);
        Preference last = page.findPreference(Settings.LOGO_ON_FOLLOWING.key);
        assertSame("one section holds all three", first.getParent(), last.getParent());
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
    }

    @Test public void theOverviewUsesFlatConnectedRows() throws Exception {
        open(true, EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED, PatchFamily.NOTIFICATION_GROUPS));
        List<Preference> rows = categoryRows();
        Preference entry = categoryRow("Settings entry");
        Preference backup = categoryRow("Settings backup");
        Preference about = categoryRow("About");
        Preference first = categoryRow("Ads and privacy");
        assertSame("the list starts with the everyday controls", first, rows.get(0));
        assertTrue(ScreenColors.opensCard(first));
        assertFalse(ScreenColors.closesCard(first));
        assertTrue("Settings entry comes after the everyday controls, before backup: " + rows,
                rows.indexOf(entry) > rows.indexOf(categoryRow("Feed")) && rows.indexOf(entry) < rows.indexOf(backup));
        assertFalse("Settings entry sits inside the card", ScreenColors.opensCard(entry) || ScreenColors.closesCard(entry));
        Preference pause = categoryRow("Pause and diagnostics");
        assertEquals("Pause and diagnostics folds between backup and About: " + rows,
                List.of(backup, pause, about), rows.subList(rows.indexOf(backup), rows.size()));
        for (Preference row : rows.subList(1, rows.size())) {
            assertFalse(row.getTitle() + " sits inside the card", ScreenColors.opensCard(row) || ScreenColors.closesCard(row));
        }
        Preference report = page.findPreference(HushgramPreferenceFragment.REPORT_ROW_KEY);
        assertFalse(ScreenColors.opensCard(report));
        assertFalse(ScreenColors.closesCard(report));
        Preference support = page.findPreference(HushgramPreferenceFragment.SUPPORT_ROW_KEY);
        assertFalse(ScreenColors.opensCard(support));
        assertTrue(ScreenColors.closesCard(support));
        assertNull("the active status strip is absent", page.getPreferenceScreen().findPreference("hushgram_status"));
        assertEquals("hushgram_settings_search", onScreen().get(0).getKey());
        assertEquals("hushgram_view_toggle", onScreen().get(1).getKey());
        assertFalse(roundedTop(card(render(entry))));
        assertFalse(roundedBottom(card(render(entry))));
        assertFalse(roundedTop(card(render(rows.get(1)))));
        assertFalse(roundedBottom(card(render(backup))));
        View drawn = render(entry);
        ImageView icon = drawn.findViewById(android.R.id.icon);
        assertNull("the line icon has no decorative tile", icon.getBackground());
        assertTrue("rows keep a touch target", drawn.getHeight() >= 48 * drawn.getResources().getDisplayMetrics().density);
    }

    @Test public void onlyARowThatOpensSomethingEndsInAChevron() throws Exception {
        open(true, EnumSet.of(PatchFamily.HIDE_ADS));
        Preference ads = categoryRow("Ads and privacy");
        assertTrue("a category button expands its controls", hasChevron(render(ads)));
        assertEquals(1, ((ScreenColors.Chevron) ((ImageView) render(ads).findViewWithTag(ScreenColors.CHEVRON)).getDrawable()).direction);
        tap(ads);
        assertEquals(-1, ((ScreenColors.Chevron) ((ImageView) render(ads).findViewWithTag(ScreenColors.CHEVRON)).getDrawable()).direction);
        assertNull("an active status card does not take space", page.getPreferenceScreen().findPreference("hushgram_status"));
        tap(categoryRow("Settings entry"));
        assertTrue("a list row opens its choices",
                hasChevron(render(page.findPreference(Settings.NAVIGATION_SETTINGS_TARGET.key))));
        assertFalse("a switch says what it does with its switch",
                hasChevron(render(page.findPreference(Settings.CATEGORY_PAGES.key))));
        tap(categoryRow("Settings entry"));
        tap(categoryRow("About"));
        assertFalse("Version only reports", hasChevron(render(titled("Version"))));
        assertTrue("Licenses opens a dialog", hasChevron(render(titled("Licenses"))));
    }

    @Test public void everyRowLeadsWithAnIconAScreenReaderSkips() throws Exception {
        open(false, EnumSet.allOf(PatchFamily.class));
        int rows = 0;
        for (Preference item : onScreen()) {
            // The search field draws its magnifier inside the field, not as a row's icon.
            if ("hushgram_settings_search".equals(item.getKey())) continue;
            if (!(item instanceof PreferenceCategory)) {
                assertNotNull(item.getTitle() + " has no icon", item.getIcon());
                continue;
            }
            PreferenceCategory group = (PreferenceCategory) item;
            for (int i = 0; i < group.getPreferenceCount(); i++, rows++) {
                Preference row = group.getPreference(i);
                assertNotNull(group.getTitle() + " > " + row.getTitle() + " has no icon", row.getIcon());
            }
        }
        assertTrue("too few rows to mean anything: " + rows, rows > 100);

        Preference toggle = page.findPreference(Settings.HIDE_ADS.key);
        View row = render(toggle);
        ImageView icon = row.findViewById(android.R.id.icon);
        assertNotNull(icon);
        assertEquals(View.VISIBLE, icon.getVisibility());
        assertEquals("the icon is decoration", View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
        assertNull(icon.getContentDescription());
        AccessibilityNodeInfo node = row.createAccessibilityNodeInfo();
        assertEquals("the switch still reads its name and explanation, and nothing else",
                toggle.getTitle() + "\n" + toggle.getSummary(), String.valueOf(node.getContentDescription()));
        node.recycle();
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

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void textWrapsAndTheCardsGrowAtTwiceTheSize() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        open(true, EnumSet.of(PatchFamily.HIDE_ADS));
        Preference adsRow = categoryRow("Ads and privacy");
        assertWhole(render(adsRow));
        tap(adsRow);
        View header = render(adsRow);
        TextView summary = header.findViewById(android.R.id.summary);
        assertTrue("the category description wraps", summary.getLayout().getLineCount() > 1);
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

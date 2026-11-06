/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListAdapter;

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

import app.hushgram.extension.instagram.download.ViewingHistory;
import app.hushgram.extension.instagram.download.ViewingHistory.Kind;
import app.hushgram.extension.instagram.download.ViewingHistoryRows;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.ui.Dim;

/**
 * The page's side edges, as the S25 showed them (2026-11-03): main-page rows drew a grey hairline
 * down both screen edges, Restart now sat 12dp in while the cards and the search field sit 18dp in,
 * and the viewing history's search sat 16dp in.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class SettingsEdgeInsetsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;
    private SettingsDialog dialog;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (dialog != null) dialog.dismissAllowingStateLoss();
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        ViewingHistory.clear(RuntimeEnvironment.getApplication());
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        Settings.VIEWING_HISTORY.resetToDefault();
        SettingsEntry.onClosedByUser();
    }

    private void open(boolean black, boolean pages) throws Exception {
        Settings.SETTINGS_BLACK_LOOK.save(black);
        Settings.CATEGORY_PAGES.save(pages);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private int dp(int value) {
        return Math.round(value * RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density);
    }

    private Preference categoryRow(String title) {
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item instanceof HushgramPreferenceFragment.Heading
                    && ((HushgramPreferenceFragment.Heading) item).collapsible
                    && title.contentEquals(item.getTitle())) return item;
        }
        throw new AssertionError("no row for " + title);
    }

    /** Where [wanted]'s card surface lands when the page's adapter draws it 320 dp wide. */
    private Rect surfaceOf(Preference wanted) {
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i) != wanted) continue;
            View row = adapter.getView(i, null, new FrameLayout(controller.get()));
            int width = dp(320);
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, width, row.getMeasuredHeight());
            Drawable background = row.getBackground();
            background.setBounds(0, 0, row.getWidth(), row.getHeight());
            Drawable inset = background instanceof RippleDrawable ? ((RippleDrawable) background).getDrawable(0) : background;
            assertTrue("no inset card behind the row: " + inset, inset instanceof InsetDrawable);
            Drawable surface = ((InsetDrawable) inset).getDrawable();
            assertTrue(surface instanceof GradientDrawable);
            return new Rect(surface.getBounds());
        }
        throw new AssertionError("not on the page: " + wanted.getTitle());
    }

    private void mainPageRowsKeepTheirStrokesOffTheScreenEdges(boolean black) throws Exception {
        open(black, true);
        int width = dp(320);
        Rect overview = surfaceOf(categoryRow("Ads and privacy"));
        assertTrue("the stroke's left side is off the screen: " + overview, overview.left <= -dp(1));
        assertTrue("and its right side: " + overview, overview.right >= width + dp(1));

        Preference ads = categoryRow("Ads and privacy");
        assertTrue(ads.getOnPreferenceClickListener().onPreferenceClick(ads));
        ShadowLooper.idleMainLooper();
        Rect card = surfaceOf(page.findPreference(Settings.HIDE_ADS.key));
        assertEquals("an expanded row keeps its card 18dp in", dp(18), card.left);
        assertEquals(width - dp(18), card.right);
    }

    @Test public void mainPageRowsKeepTheirStrokesOffTheScreenEdges() throws Exception {
        mainPageRowsKeepTheirStrokesOffTheScreenEdges(false);
    }

    @Test public void mainPageRowsKeepTheirStrokesOffTheScreenEdgesOnTheBlackPage() throws Exception {
        mainPageRowsKeepTheirStrokesOffTheScreenEdges(true);
    }

    @Test public void restartNowLinesUpWithTheCards() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "edge_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        View bar = dialog.getView().findViewById(SettingsDialog.RESTART_ID);
        ViewGroup.MarginLayoutParams space = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
        assertEquals("the cards' 18dp, not 12", dp(18), space.leftMargin);
        assertEquals(dp(18), space.rightMargin);
    }

    @Test public void theViewingHistorySearchSitsWhereTheSettingsSearchDoes() throws Exception {
        ViewingHistoryRows.add(RuntimeEnvironment.getApplication(), Kind.POST, "1_1", "amy",
                System.currentTimeMillis() - 1_000L);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        controller = Robolectric.buildActivity(Activity.class).setup();
        HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
        Preference row = settings.findPreference(HushgramPreferenceFragment.VIEWING_HISTORY_ROW);
        ViewingHistoryPage history = ViewingHistoryPage.open(controller.get(), row);
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        try {
            ViewGroup.MarginLayoutParams space = (ViewGroup.MarginLayoutParams) history.search.getLayoutParams();
            // Dim keeps the density of whichever test loaded it first, so this reads Dim too.
            assertEquals("the settings search's 18dp", Dim.dp(18), space.getMarginStart());
            assertEquals(Dim.dp(18), space.getMarginEnd());
        } finally {
            history.dialog.dismiss();
        }
    }
}

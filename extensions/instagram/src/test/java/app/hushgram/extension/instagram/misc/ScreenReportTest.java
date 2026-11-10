/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.DiagnosticRedactor;

/**
 * The [SCREEN] report section (#535): a page that's there but blank shows up as the view that's
 * hidden, faded, moved or missing its video surface, the tab bar names the selected tab, a hidden
 * page isn't walked into, and nothing in it is something the redactor would take out.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w360dp-h640dp")
public class ScreenReportTest {
    private static final int BAR_ID = 0x7f0b0301;
    private static final int HOME_TAB_ID = 0x7f0b0302;
    private static final int REELS_TAB_ID = 0x7f0b0303;
    private static final int HOME_PAGE_ID = 0x7f0b0304;
    private static final int HOME_LIST_ID = 0x7f0b0305;
    private static final int REELS_PAGE_ID = 0x7f0b0306;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @After public void tearDown() {
        GlassTabBar.idForTests(ScreenReport.TAB_BAR, null);
    }

    @Test public void namesTheBlankPageAndTheSelectedTab() {
        GlassScrollHideTest.Probe activity = Robolectric.buildActivity(GlassScrollHideTest.Probe.class).setup().get();
        Context named = new Named(activity);
        FrameLayout root = new FrameLayout(activity);

        FrameLayout homePage = new FrameLayout(named);
        homePage.setId(HOME_PAGE_ID);
        FrameLayout homeList = new FrameLayout(named);
        homeList.setId(HOME_LIST_ID);
        homePage.addView(homeList, full());
        root.addView(homePage, full());

        FrameLayout reelsPage = new FrameLayout(named);
        reelsPage.setId(REELS_PAGE_ID);
        reelsPage.setAlpha(0f);
        reelsPage.setTranslationY(100f);
        reelsPage.setBackground(new ColorDrawable(0xFF000000));
        reelsPage.addView(new TextureView(activity), full());
        root.addView(reelsPage, full());

        LinearLayout bar = new LinearLayout(named);
        bar.setId(BAR_ID);
        View homeTab = new View(named);
        homeTab.setId(HOME_TAB_ID);
        homeTab.setSelected(true);
        View reelsTab = new View(named);
        reelsTab.setId(REELS_TAB_ID);
        bar.addView(homeTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bar.addView(reelsTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60, Gravity.BOTTOM));

        activity.setContentView(root);
        GlassTabBar.idForTests(ScreenReport.TAB_BAR, BAR_ID);
        ShadowLooper.idleMainLooper();
        // A tab's page is laid out while it shows and keeps its size once another tab hides it.
        homePage.setVisibility(View.GONE);
        ShadowLooper.idleMainLooper();

        List<String> lines = ScreenReport.describe(activity);
        String text = String.join("\n", lines);

        assertTrue(text, lines.get(0).startsWith("activity: Probe"));
        assertTrue(text, lines.contains("tabs: feed_tab (selected), clips_tab"));
        String home = only(lines, " id home_page ");
        assertTrue(home, home.contains(" gone"));
        assertFalse("a hidden page was walked into:\n" + text, text.contains("home_list"));
        String reels = only(lines, " id reels_page ");
        assertTrue(reels, reels.contains(" alpha 0"));
        assertTrue(reels, reels.contains(" moved 0,100"));
        assertTrue(reels, reels.contains(" background FF000000"));
        assertTrue(reels, reels.contains(" children 1"));
        assertTrue(text, only(lines, " TextureView ").contains(" no surface"));
        assertFalse("the tab bar covers too little to be listed:\n" + text, text.contains(" id tab_bar "));
        for (String line : lines) assertEquals(line, DiagnosticRedactor.redact(line));
    }

    @Test public void saysSoWithNoActivity() {
        assertEquals(Collections.singletonList("activity: none"), ScreenReport.describe(null));
    }

    @Test public void stopsAtTheLineLimit() {
        GlassScrollHideTest.Probe activity = Robolectric.buildActivity(GlassScrollHideTest.Probe.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        for (int i = 0; i < ScreenReport.MAX_LINES * 2; i++) root.addView(new View(activity), full());
        activity.setContentView(root);
        ShadowLooper.idleMainLooper();

        List<String> lines = ScreenReport.describe(activity);

        assertEquals("more views left out", lines.get(lines.size() - 1));
        int heading = lines.indexOf("views covering most of the window, outermost first:");
        assertEquals(ScreenReport.MAX_LINES, lines.size() - heading - 2);
    }

    @Test public void readsTheViewsOnTheMainThread() throws Exception {
        AtomicBoolean onMain = new AtomicBoolean();
        AtomicReference<List<String>> answer = new AtomicReference<>();
        Thread export = new Thread(() -> answer.set(ScreenReport.onMainThread(() -> {
            onMain.set(Looper.myLooper() == Looper.getMainLooper());
            return new ArrayList<>(Collections.singletonList("read"));
        })));
        export.start();
        long deadline = System.currentTimeMillis() + 5_000;
        while (export.isAlive() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(5);
        }
        export.join(1_000);

        assertTrue(onMain.get());
        assertEquals(Collections.singletonList("read"), answer.get());
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static String only(List<String> lines, String part) {
        String found = null;
        for (String line : lines) {
            if (!line.contains(part)) continue;
            assertEquals("more than one line holds" + part + ":\n" + String.join("\n", lines), null, found);
            found = line;
        }
        assertTrue("no line holds" + part + ":\n" + String.join("\n", lines), found != null);
        return found;
    }

    /** A context whose resources name the test's ids the way Instagram's name its own. */
    private static final class Named extends ContextWrapper {
        private static final Map<Integer, String> NAMES = new HashMap<>();

        static {
            NAMES.put(BAR_ID, "tab_bar");
            NAMES.put(HOME_TAB_ID, "feed_tab");
            NAMES.put(REELS_TAB_ID, "clips_tab");
            NAMES.put(HOME_PAGE_ID, "home_page");
            NAMES.put(HOME_LIST_ID, "home_list");
            NAMES.put(REELS_PAGE_ID, "reels_page");
        }

        private final Resources resources;

        @SuppressWarnings("deprecation")
        Named(Context base) {
            super(base);
            Resources real = base.getResources();
            resources = new Resources(real.getAssets(), real.getDisplayMetrics(), real.getConfiguration()) {
                @Override
                public String getResourceEntryName(int id) {
                    String name = NAMES.get(id);
                    return name != null ? name : super.getResourceEntryName(id);
                }
            };
        }

        @Override
        public Resources getResources() {
            return resources;
        }
    }
}

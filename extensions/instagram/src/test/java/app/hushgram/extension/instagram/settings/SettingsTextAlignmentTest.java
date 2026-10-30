/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.preference.Preference;
import android.text.Layout;
import android.view.View;
import android.widget.FrameLayout;
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

import app.hushgram.extension.instagram.download.SavesForTests;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * Every row's title and summary start at the row's start edge: the right in a right-to-left
 * layout, where HushGram's English used to sit flush left once it wrapped, and the left otherwise,
 * as before. Version, a switch, the last carousel save and a running save are checked at
 * normal and twice-size text; the recovery message stays centred.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SettingsTextAlignmentTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;
    private int save;

    @Before public void host() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        RuntimeEnvironment.setFontScale(1f);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        SavesForTests.endAll();
        SavesForTests.resetCarouselOutcome();
        Utils.awaitBackgroundTasksForTests();
        RuntimeEnvironment.setFontScale(1f);
        PatchFamily.inBuildForTests = null;
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp-xhdpi")
    public void rightToLeftRowsStartAtTheRight() throws Exception {
        assertEveryLineAtTheStart(true, 1f);
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp-xhdpi")
    public void rightToLeftRowsStartAtTheRightAtTwiceTheTextSize() throws Exception {
        assertEveryLineAtTheStart(true, 2f);
    }

    @Test public void leftToRightRowsStayAtTheLeft() throws Exception {
        assertEveryLineAtTheStart(false, 1f);
    }

    @Test public void leftToRightRowsStayAtTheLeftAtTwiceTheTextSize() throws Exception {
        assertEveryLineAtTheStart(false, 2f);
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp-xhdpi")
    public void theRecoveryMessageStaysCentred() throws Exception {
        open();
        HushgramPreferenceFragment.Row message = new HushgramPreferenceFragment.Row(controller.get());
        message.setTitle(page.initializationErrorTitle(controller.get()));
        message.setSummary("Try again, or go back to Instagram.");
        message.setSelectable(false);
        page.getPreferenceScreen().addPreference(message);
        ShadowLooper.idleMainLooper();
        View row = render(message, true);
        ScreenColors.recoveryMessage(row);
        lay(row, true);
        for (int which : new int[]{android.R.id.title, android.R.id.summary}) {
            TextView text = row.findViewById(which);
            assertEquals(View.TEXT_ALIGNMENT_GRAVITY, text.getTextAlignment());
            Layout layout = text.getLayout();
            for (int line = 0; line < layout.getLineCount(); line++) {
                float left = layout.getLineLeft(line);
                // Layout rounds its exposed left edge down and its right edge up.
                // Adding a fractional line width to the rounded left loses that symmetry.
                float right = layout.getWidth() - layout.getLineRight(line);
                assertEquals(text.getText() + " line " + line + " is centred", left, right, 1.5f);
            }
        }
    }

    private void assertEveryLineAtTheStart(boolean rightToLeft, float fontScale) throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        open();
        List<Preference> rows = rows();
        assertEquals("version, a switch, the last carousel save and a running save", 4, rows.size());
        for (Preference preference : rows) {
            View row = render(preference, rightToLeft);
            int lines = 0;
            Integer edge = null;
            for (int which : new int[]{android.R.id.title, android.R.id.summary}) {
                TextView text = row.findViewById(which);
                if (text == null || text.getVisibility() != View.VISIBLE || text.getText().length() == 0) continue;
                String what = preference.getClass().getSimpleName() + " " + preference.getTitle() + " "
                        + (which == android.R.id.title ? "title" : "summary");
                assertEquals(what, View.TEXT_ALIGNMENT_VIEW_START, text.getTextAlignment());
                // The title and the summary start at the same edge of the row.
                int start = rightToLeft ? offsetInRow(text, row) + text.getWidth() - text.getTotalPaddingRight()
                        : offsetInRow(text, row) + text.getTotalPaddingLeft();
                if (edge == null) edge = start;
                assertEquals(what + " starts where the title does", (int) edge, start);
                Layout layout = text.getLayout();
                for (int line = 0; line < layout.getLineCount(); line++, lines++) {
                    float left = layout.getLineLeft(line);
                    if (rightToLeft) {
                        assertEquals(what + " line " + line, layout.getWidth(), left + layout.getLineMax(line), 1f);
                    } else {
                        assertEquals(what + " line " + line, 0f, left, 0.5f);
                    }
                }
            }
            assertTrue(preference.getTitle() + " drew no text", lines > 0);
        }
    }

    private void open() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.VIDEO_DOWNLOAD);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        int carousel = SavesForTests.beginCarousel(RuntimeEnvironment.getApplication(), 32);
        SavesForTests.finishCarousel(carousel, 3, 2, 27, 2, true);
        save = SavesForTests.begin(RuntimeEnvironment.getApplication(), true);
        SavesForTests.transferred(save, 3_500_000, 12_000_000);
        ShadowLooper.idleMainLooper();
    }

    /** Version, Hide ads, the last carousel save and the running save, in that order. */
    private List<Preference> rows() {
        List<Preference> found = new ArrayList<>();
        found.add(page.about.getPreference(0));
        found.add(page.findPreference(Settings.HIDE_ADS.key));
        found.add(page.findPreference("hushgram_last_carousel_save"));
        found.add(page.findPreference("running_save_" + save));
        for (Preference row : found) assertNotNull(found.toString(), row);
        assertEquals("Version", String.valueOf(found.get(0).getTitle()));
        return found;
    }

    private View render(Preference wanted, boolean rightToLeft) {
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i) != wanted) continue;
            View row = adapter.getView(i, null, new FrameLayout(controller.get()));
            lay(row, rightToLeft);
            return row;
        }
        throw new AssertionError("not on the page: " + wanted.getTitle());
    }

    private static void lay(View row, boolean rightToLeft) {
        row.setLayoutDirection(rightToLeft ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        int width = Math.round(320 * row.getResources().getDisplayMetrics().density);
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, width, row.getMeasuredHeight());
    }

    private static int offsetInRow(View view, View row) {
        int left = 0;
        for (View at = view; at != row; at = (View) at.getParent()) left += at.getLeft();
        return left;
    }
}

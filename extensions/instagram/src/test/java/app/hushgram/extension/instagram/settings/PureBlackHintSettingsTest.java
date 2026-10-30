/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/** A build without Pure black dark mode says where to get it under Layout, and search finds either row by "amoled" (#359). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class PureBlackHintSettingsTest {
    private static final String HINT = "Pure black dark mode isn't in this build";
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }
    private static List<Preference> rows(PreferenceGroup group) {
        List<Preference> result = new ArrayList<>();
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            result.add(row);
            if (row instanceof PreferenceGroup) result.addAll(rows((PreferenceGroup) row));
        }
        return result;
    }
    private Preference titled(String title) {
        for (Preference row : rows(page.getPreferenceScreen())) {
            if (row.getTitle() != null && title.equals(row.getTitle().toString())) return row;
        }
        return null;
    }
    @Test public void aBuildWithoutPureBlackShowsTheHintUnderLayout() throws Exception {
        open(EnumSet.of(PatchFamily.ACCENT_COLOR));
        Preference hint = titled(HINT);
        assertNotNull(hint);
        assertSame(hint, page.getPreferenceScreen().findPreference(LayoutCategory.PURE_BLACK_HINT_ROW));
        assertEquals("Layout", hint.getParent().getTitle().toString());
        assertTrue(hint.getSummary().toString().contains("Morphe Manager"));
        assertFalse(hint.isSelectable());
        assertFalse(hint.isPersistent());
        assertNull(titled("Pure black dark mode"));
    }
    @Test public void aBuildWithPureBlackShowsItsRowAndNoHint() throws Exception {
        open(EnumSet.of(PatchFamily.ACCENT_COLOR, PatchFamily.PURE_BLACK));
        assertNull(titled(HINT));
        assertNotNull(titled("Pure black dark mode"));
    }
    @Test public void searchFindsEitherRowByAmoled() throws Exception {
        open(EnumSet.of(PatchFamily.ACCENT_COLOR));
        page.searchSettings("amoled");
        assertNotNull(titled(HINT));
        assertNull(titled("Accent color"));
        controller.close();
        controller = null;
        PatchFamily.inBuildForTests = null;
        open(EnumSet.of(PatchFamily.ACCENT_COLOR, PatchFamily.PURE_BLACK));
        page.searchSettings("oled");
        assertNotNull(titled("Pure black dark mode"));
        assertNull(titled("Accent color"));
    }
}

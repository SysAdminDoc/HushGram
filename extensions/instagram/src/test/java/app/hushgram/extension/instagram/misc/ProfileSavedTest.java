/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** What counts as the menu's Saved row, and what the bookmark on a profile holds on to. */
@RunWith(RobolectricTestRunner.class)
public class ProfileSavedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void aRowLabelledSavedIsTheSavedRowInAnyCase() {
        assertTrue(ProfileSaved.isSavedLabel("Saved"));
        assertTrue(ProfileSaved.isSavedLabel("  saved "));
    }

    /** The menu's rows are found through this lookup, so a refusal shows the menu at once. */
    @Test
    public void theChildLookupIsFoundOnce() {
        assertNotNull(ProfileSaved.childId());
        assertSame(ProfileSaved.childId(), ProfileSaved.childId());
    }

    @Test
    public void otherRowsAreNot() {
        assertFalse(ProfileSaved.isSavedLabel("Archive"));
        assertFalse(ProfileSaved.isSavedLabel("Saved posts and more"));
        assertFalse(ProfileSaved.isSavedLabel(null));
    }

    /**
     * The row kept for a profile's tabs is keyed weakly on them, and it mustn't hold them itself: a
     * row that did kept every profile screen ever looked at, activity and all, for as long as
     * Instagram ran.
     */
    @Test
    public void aKeptRowLetsTheProfilesTabsGoWithTheScreen() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            int before = ProfileSaved.rememberedForTests();
            WeakReference<ViewGroup> tabs = remembered(controller.get());
            assertEquals(before + 1, ProfileSaved.rememberedForTests());
            for (int attempt = 0; attempt < 50 && (tabs.get() != null || ProfileSaved.rememberedForTests() != before); attempt++) {
                System.gc();
                Thread.sleep(5);
            }
            assertNull("the kept row held the tabs it was keyed on", tabs.get());
            assertEquals(before, ProfileSaved.rememberedForTests());
        } finally {
            HookStatus.clear();
        }
    }

    /** Made in a method of its own, so no local of the test keeps the tabs reachable. */
    private static WeakReference<ViewGroup> remembered(Activity activity) {
        LinearLayout tabs = new LinearLayout(activity);
        ProfileSaved.rememberForTests(activity, tabs);
        return new WeakReference<>(tabs);
    }
}

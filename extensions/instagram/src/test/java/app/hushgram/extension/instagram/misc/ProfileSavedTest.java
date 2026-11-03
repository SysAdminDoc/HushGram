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
    public void aKeptRowLetsTheProfileScreenGoWhenItCloses() throws Exception {
        try {
            int before = ProfileSaved.rememberedForTests();
            WeakReference<?>[] held = shownThenClosed(before);
            for (int attempt = 0; attempt < 100 && (held[0].get() != null || held[1].get() != null
                    || ProfileSaved.rememberedForTests() != before); attempt++) {
                System.gc();
                Thread.sleep(5);
            }
            assertNull("the kept row held the tabs it was keyed on", held[1].get());
            assertNull("the kept row held the profile's activity", held[0].get());
            assertEquals(before, ProfileSaved.rememberedForTests());
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * Puts tabs on a profile screen, keeps a row for them the way a look does, then closes the screen.
     * Made in a method of its own, so no local of the test keeps the activity or the tabs reachable.
     */
    private static WeakReference<?>[] shownThenClosed(int before) {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        LinearLayout tabs = new LinearLayout(activity);
        activity.setContentView(tabs);
        ProfileSaved.rememberForTests(activity, tabs);
        assertEquals(before + 1, ProfileSaved.rememberedForTests());
        WeakReference<?>[] held = {new WeakReference<>(activity), new WeakReference<ViewGroup>(tabs)};
        controller.close();
        return held;
    }
}

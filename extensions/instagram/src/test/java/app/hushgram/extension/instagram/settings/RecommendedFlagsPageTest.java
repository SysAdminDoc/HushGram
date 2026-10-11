/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.instagram.misc.OverrideImport;
import app.hushgram.extension.instagram.misc.OverrideImportTest;
import app.hushgram.extension.instagram.misc.OverrideImportTest.NativeTable;
import app.hushgram.extension.instagram.misc.RecommendedFlags;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/** An Error from the override store still tells the user and frees the override rows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = {OverrideImportTest.NativeTable.class, RecommendedFlagsPageTest.Flags.class})
@SuppressWarnings("deprecation")
public class RecommendedFlagsPageTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private static final String ROW = "hushgram_recommended_flags";
    private static final String EXPORT_OVERRIDES = "hushgram_export_overrides";
    private ActivityController<OverrideDocumentsTest.HostActivity> host;
    private HushgramPreferenceFragment page;

    /** Stands in for the store: a page with the first switch at Instagram's own, or the Error a test stages. */
    @Implements(value = RecommendedFlags.class, isInAndroidSdk = false)
    public static class Flags {
        static Error loadFailure, setFailure;
        static int loads, sets;

        @Implementation protected static RecommendedFlags.Page load(Activity activity) {
            loads++;
            if (loadFailure != null) throw loadFailure;
            RecommendedFlags.Page page = new RecommendedFlags.Page();
            page.flags.add(RecommendedFlags.catalogue().get(0));
            page.choices.add(RecommendedFlags.Choice.DEFAULT);
            return page;
        }

        @Implementation protected static OverrideImport.Result set(Activity activity, RecommendedFlags.Flag flag,
                                                                   RecommendedFlags.Choice wanted) {
            sets++;
            if (setFailure != null) throw setFailure;
            throw new AssertionError("no change staged");
        }
    }

    @Before public void open() throws Exception {
        NativeTable.reset();
        Flags.loadFailure = null;
        Flags.setFailure = null;
        Flags.loads = 0;
        Flags.sets = 0;
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        OverrideImportTest.install();
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.DEVELOPER_OPTIONS);
        host = Robolectric.buildActivity(OverrideDocumentsTest.HostActivity.class).setup();
        NativeTable.file = OverrideImportTest.store(host.get(), "mobileconfig");
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(host.get().getFragmentManager(), SettingsEntry.DIALOG_TAG);
        host.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
    }

    @After public void close() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        if (host != null) host.close();
        PatchFamily.inBuildForTests = null;
        Settings.ALLOW_OVERRIDE_IMPORT.resetToDefault();
    }

    @Test public void anErrorReadingTheStoreSaysSoAndFreesTheRows() throws Exception {
        Flags.loadFailure = new NoClassDefFoundError("private detail");
        openFlags();
        assertEquals(1, Flags.loads);
        assertEquals("Couldn't read Instagram's overrides. Open settings from Home while signed in.",
                ShadowToast.getTextOfLatestToast());
        assertTrue(page.findPreference(EXPORT_OVERRIDES).isEnabled());
        Flags.loadFailure = null;
        openFlags();
        assertEquals("the page still counted as busy", 2, Flags.loads);
    }

    @Test public void anErrorChangingASwitchPutsItBackSaysSoAndFreesTheRows() throws Exception {
        openFlags();
        RadioGroup group = radios(ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView());
        assertNotNull(group);
        RadioButton on = (RadioButton) group.getChildAt(1);
        assertEquals(RecommendedFlags.Choice.ON, on.getTag());
        Flags.setFailure = new NoClassDefFoundError("private detail");
        group.check(on.getId());
        settle();
        assertEquals(1, Flags.sets);
        assertEquals("Couldn't change that setting. Open settings from Home while signed in. Nothing changed.",
                ShadowToast.getTextOfLatestToast());
        RadioButton checked = group.findViewById(group.getCheckedRadioButtonId());
        assertEquals(RecommendedFlags.Choice.DEFAULT, checked.getTag());
        for (int i = 0; i < group.getChildCount(); i++) assertTrue(group.getChildAt(i).isEnabled());
        assertTrue(page.findPreference(EXPORT_OVERRIDES).isEnabled());
    }

    private void openFlags() throws Exception {
        Preference row = page.findPreference(ROW);
        assertNotNull(row);
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        settle();
    }

    private static void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private static RadioGroup radios(View view) {
        if (view instanceof RadioGroup) return (RadioGroup) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup parent = (ViewGroup) view;
        for (int i = 0; i < parent.getChildCount(); i++) {
            RadioGroup found = radios(parent.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}

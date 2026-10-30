/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When Hide Share profile takes Share profile out of a profile's action row (#253), and how the row fills the space. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ShareProfileButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;

    @Before
    public void prepare() {
        context = RuntimeEnvironment.getApplication();
        Settings.HIDE_SHARE_PROFILE_BUTTON.resetToDefault();
        Settings.HIDE_PROFILE_SUGGESTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_SHARE_PROFILE_BUTTON.resetToDefault();
        Settings.HIDE_PROFILE_SUGGESTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Off to start, and unpatched the stub never names a Share model, so nothing goes even with the switch on. */
    @Test
    public void offToStartAndUnpatchedNothingGoes() {
        assertEquals(Boolean.FALSE, Settings.HIDE_SHARE_PROFILE_BUTTON.defaultValue);
        assertFalse("the next profile opened shows a change", Settings.HIDE_SHARE_PROFILE_BUTTON.rebootApp);
        Settings.HIDE_SHARE_PROFILE_BUTTON.save(true);
        assertFalse(ShareProfileButton.isShare(new Object()));
        View button = new View(context);
        ShareProfileButton.button(button, new Object());
        ShareProfileButton.button(null, new Object());
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals(List.of(FamilyNames.THREADS_BUTTON + ": invoked 1, 0 found, 0 missing"), HookStatus.report());
    }

    /** Share profile goes and Edit profile takes the whole row; switched off, both are back at Instagram's widths. */
    @Test
    public void editProfileFillsTheRow() {
        LinearLayout row = row();
        View edit = add(row, "generic", 400);
        View share = add(row, "generic", 400);
        assertEquals(List.of(400, 400), measure(row, edit, share));

        ShareProfileButton.button(share, true, () -> true);
        ShareProfileButton.button(edit, false, () -> true);
        ActionRow.fitRow(share, FamilyNames.THREADS_BUTTON);
        assertEquals(View.GONE, share.getVisibility());
        assertEquals(View.VISIBLE, edit.getVisibility());
        assertEquals(List.of(1000), measure(row, edit));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ShareProfileButton.HIDDEN + " 1"));
        assertTrue(report, report.contains(ActionRow.ROW_SPREAD + " 1"));

        ShareProfileButton.button(share, true, () -> false);
        ActionRow.fitRow(share, FamilyNames.THREADS_BUTTON);
        assertEquals(View.VISIBLE, share.getVisibility());
        assertEquals(List.of(400, 400), measure(row, edit, share));
        assertEquals(0f, ((LinearLayout.LayoutParams) edit.getLayoutParams()).weight, 0f);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(ActionRow.ROW_PUT_BACK + " 1"));
    }

    /** Instagram pools the row's buttons, so a hidden Share view built again for Follow or Message shows again. */
    @Test
    public void aPooledButtonBuiltForAnotherModelShows() {
        View button = new View(context);
        button.setVisibility(View.INVISIBLE);
        ShareProfileButton.button(button, true, () -> true);
        assertEquals(View.GONE, button.getVisibility());
        ShareProfileButton.button(button, true, () -> true);
        ShareProfileButton.button(button, false, () -> true);
        assertEquals("its own visibility from before the first hide", View.INVISIBLE, button.getVisibility());

        View other = new View(context);
        ShareProfileButton.button(other, false, () -> true);
        assertEquals(View.VISIBLE, other.getVisibility());
        other.setVisibility(View.INVISIBLE);
        ShareProfileButton.button(other, false, () -> true);
        assertEquals("a button it never hid is left as Instagram set it", View.INVISIBLE, other.getVisibility());
    }

    /** With the person-plus button hidden too, Edit profile takes both spaces, and gets Share's back when it returns. */
    @Test
    public void composesWithTheHiddenPersonPlusButton() {
        Settings.HIDE_PROFILE_SUGGESTIONS.save(true);
        LinearLayout row = row();
        View edit = add(row, "generic", 400);
        View share = add(row, "generic", 400);
        View chaining = add(row, "chaining_icon", 150);

        ProfileSuggestions.chainingButton(chaining);
        ShareProfileButton.button(share, true, () -> true);
        ActionRow.fitRow(share, FamilyNames.THREADS_BUTTON);
        assertEquals(View.GONE, chaining.getVisibility());
        assertEquals(List.of(1000), measure(row, edit));

        ShareProfileButton.button(share, true, () -> false);
        ActionRow.fitRow(share, FamilyNames.THREADS_BUTTON);
        assertEquals("the person-plus button's space is still shared", List.of(500, 500), measure(row, edit, share));

        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        ProfileSuggestions.chainingButton(chaining);
        ProfileSuggestions.fitRow(chaining);
        assertEquals(List.of(400, 400, 150), measure(row, edit, share, chaining));
    }

    /** A switch that throws leaves the button showing and is counted. */
    @Test
    public void aThrowingSwitchLeavesTheButton() {
        View button = new View(context);
        ShareProfileButton.button(button, true, () -> {
            throw new IllegalStateException("settings went away");
        });
        assertEquals(View.VISIBLE, button.getVisibility());
        String missing = HookStatus.missing(FamilyNames.THREADS_BUTTON).toString();
        assertTrue(missing, missing.contains("'" + ShareProfileButton.BUTTON + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private View add(LinearLayout row, String tag, int width) {
        View view = new View(context);
        view.setTag(tag);
        row.addView(view, new LinearLayout.LayoutParams(width, 100));
        return view;
    }

    /** The widths [views] get in a row 1000 px wide, as a phone measures it. */
    private static List<Integer> measure(LinearLayout row, View... views) {
        row.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        List<Integer> widths = new ArrayList<>();
        for (View view : views) widths.add(view.getMeasuredWidth());
        return widths;
    }
}

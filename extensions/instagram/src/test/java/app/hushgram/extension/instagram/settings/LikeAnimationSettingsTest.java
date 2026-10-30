/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Change the like animation's rows: its switch, off to start, under Reels, and the Like animation
 * choice right under it, which waits for it and lists Instagram's animations by name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class LikeAnimationSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.CHANGE_LIKE_ANIMATION.resetToDefault();
        Settings.LIKE_ANIMATION.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.CHANGE_LIKE_ANIMATION.resetToDefault();
        Settings.LIKE_ANIMATION.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed
                ? EnumSet.of(PatchFamily.LIKE_ANIMATION) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoRows() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.CHANGE_LIKE_ANIMATION.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.LIKE_ANIMATION.key));
    }
    @Test public void theChoiceSitsUnderItsSwitchUnderReelsAndWaitsForIt() throws Exception {
        open(true);
        SwitchPreference change = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.CHANGE_LIKE_ANIMATION.key);
        ListPreference choice = (ListPreference) page.getPreferenceScreen().findPreference(Settings.LIKE_ANIMATION.key);
        assertNotNull(change);
        assertNotNull(choice);
        assertEquals("Change the like animation", change.getTitle().toString());
        assertFalse("off to start", change.isChecked());
        assertEquals("Like animation", choice.getTitle().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), change);
        assertNotNull(section);
        assertEquals("Reels", section.getTitle().toString());
        assertSame(section, categoryHolding(page.getPreferenceScreen(), choice));
        assertEquals("right under its switch", indexIn(section, change) + 1, indexIn(section, choice));

        assertFalse("waits for the switch", choice.isEnabled());
        assertEquals("Turn on Change the like animation to use this choice.", choice.getSummary().toString());
        change.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(choice.isEnabled());
        assertEquals("Pick an animation. Until you do, the heart stays Instagram's.", choice.getSummary().toString());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.LIKE_ANIMATION.key));
    }
    @Test public void theChoiceListsInstagramsAnimationsByName() throws Exception {
        open(true);
        Settings.CHANGE_LIKE_ANIMATION.save(true);
        List<String> names = Arrays.asList("RINGS_LIKE_ADRIAN", "RINGS_LIKE_AKI_KOICHI", "ANYWAY_LIKE_ACTIVATION");
        HushgramPreferenceFragment.LikeAnimationRow row =
                HushgramPreferenceFragment.likeAnimationRow(controller.get(), names);
        assertArrayEquals(new CharSequence[]{"Adrian", "Aki Koichi", "Anyway"}, row.getEntries());
        assertArrayEquals(names.toArray(new CharSequence[0]), row.getEntryValues());
        assertEquals("Pick an animation. Until you do, the heart stays Instagram's.", row.getSummary().toString());

        row.setValue("RINGS_LIKE_AKI_KOICHI");
        assertEquals("The heart plays Aki Koichi when you double tap a post.",
                row.getSummary().toString().replaceAll("[⁦-⁩]", ""));
        Settings.LIKE_ANIMATION.save("RINGS_LIKE_GONE");
        assertEquals("a name this Instagram doesn't have", "Pick an animation. Until you do, the heart stays Instagram's.",
                HushgramPreferenceFragment.likeAnimationRow(controller.get(), names).getSummary().toString());
    }

    /** #299: with Instagram's hearts to hand, a tap shows and plays the heart under the list, and only OK saves it. */
    @Test public void theListShowsEachHeartBeforeItsPicked() throws Exception {
        HushgramPreferenceFragment.LikeAnimationRow row = rowWithTwo();
        Map<String, Drawable> hearts = new HashMap<>();
        for (CharSequence name : row.getEntryValues()) hearts.put(name.toString(), new ColorDrawable());
        List<Drawable> played = new ArrayList<>();
        row.hearts = hearts::get;
        row.player = played::add;
        row.setValue("RINGS_LIKE_ADRIAN");

        row.showDialog(null);
        AlertDialog dialog = (AlertDialog) row.getDialog();
        assertTrue(dialog.isShowing());
        assertSame("the picked one shows", hearts.get("RINGS_LIKE_ADRIAN"), row.preview.getDrawable());
        assertEquals("and plays as the list opens", Collections.singletonList(hearts.get("RINGS_LIKE_ADRIAN")), played);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, row.preview.getImportantForAccessibility());

        tap(dialog.getListView(), 1);
        assertTrue("a tap leaves the list open", dialog.isShowing());
        assertSame(hearts.get("RINGS_LIKE_AKI_KOICHI"), row.preview.getDrawable());
        assertSame("and plays the one tapped", hearts.get("RINGS_LIKE_AKI_KOICHI"), played.get(played.size() - 1));
        assertEquals("nothing saved yet", "RINGS_LIKE_ADRIAN", row.getValue());
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals("OK saves it", "RINGS_LIKE_AKI_KOICHI", row.getValue());
        assertNull(row.preview);

        row.showDialog(null);
        dialog = (AlertDialog) row.getDialog();
        tap(dialog.getListView(), 0);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals("Cancel keeps the saved one", "RINGS_LIKE_AKI_KOICHI", row.getValue());
    }

    /**
     * Instagram has dozens of animations. A list that long took the whole dialog, leaving the heart
     * and the buttons no room, so the list gives way for them.
     */
    @Test public void aLongListLeavesRoomForTheHeartAndButtons() throws Exception {
        HushgramPreferenceFragment.LikeAnimationRow row = rowWithTwo();
        CharSequence[] entries = new CharSequence[60];
        CharSequence[] values = new CharSequence[60];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = "Animation " + i;
            values[i] = "RINGS_LIKE_" + i;
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.hearts = name -> new ColorDrawable();
        row.player = heart -> { };

        row.showDialog(null);
        AlertDialog dialog = (AlertDialog) row.getDialog();
        View window = dialog.getWindow().getDecorView();
        window.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
        window.layout(0, 0, window.getMeasuredWidth(), window.getMeasuredHeight());
        assertTrue("the list scrolls inside the dialog", dialog.getListView().getHeight() < 1000);
        assertTrue("the heart has room", row.preview.getHeight() > 0);
        assertTrue("OK has room", dialog.getButton(DialogInterface.BUTTON_POSITIVE).getHeight() > 0);
        assertTrue("Cancel has room", dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getHeight() > 0);
    }

    /** Without Instagram's hearts (the patch couldn't fill them in) the list saves on a tap, as before. */
    @Test public void withoutHeartsATapSavesAsBefore() throws Exception {
        HushgramPreferenceFragment.LikeAnimationRow row = rowWithTwo();
        List<Drawable> played = new ArrayList<>();
        row.hearts = name -> null;
        row.player = played::add;

        row.showDialog(null);
        AlertDialog dialog = (AlertDialog) row.getDialog();
        assertNull("no heart under the list", row.preview);
        ShadowLooper.idleMainLooper();
        tap(dialog.getListView(), 1);
        assertFalse(dialog.isShowing());
        assertEquals("RINGS_LIKE_AKI_KOICHI", row.getValue());
        assertTrue(played.isEmpty());
    }

    private HushgramPreferenceFragment.LikeAnimationRow rowWithTwo() throws Exception {
        Settings.CHANGE_LIKE_ANIMATION.save(true);
        open(true);
        HushgramPreferenceFragment.LikeAnimationRow row = (HushgramPreferenceFragment.LikeAnimationRow)
                page.getPreferenceScreen().findPreference(Settings.LIKE_ANIMATION.key);
        assertNotNull(row);
        row.setEntries(new CharSequence[]{"Adrian", "Aki Koichi"});
        row.setEntryValues(new CharSequence[]{"RINGS_LIKE_ADRIAN", "RINGS_LIKE_AKI_KOICHI"});
        return row;
    }
    /**
     * A tap on a choice. Android 17's list dismisses through a post on the dialog's window, which runs
     * only once that window is attached, so a test idles the main thread after opening the list.
     */
    private static void tap(ListView list, int at) {
        assertTrue(list.performItemClick(list.getAdapter().getView(at, null, list), at, list.getItemIdAtPosition(at)));
        ShadowLooper.idleMainLooper();
    }
    private static int indexIn(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (group.getPreference(i) == row) return i;
        }
        return -1;
    }
    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
            if (child instanceof PreferenceGroup) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }
}

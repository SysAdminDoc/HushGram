/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.misc.HeaderPicture;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The settings page's header (#872): HushGram's logo and name to start, Instagram's icon or a
 * picked picture in place of the logo, a typed name in place of HushGram (trimmed, one line,
 * capped, blank meaning HushGram), a picture that's gone or broken falling back to HushGram's
 * logo, the picture kept across a restart, and Reset putting both back. The version line stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SettingsHeaderLookTest {
    private static final Uri DOCUMENT = Uri.parse("content://test-documents/logo.png");

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        forgetEverything();
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        forgetEverything();
        SettingsEntry.onClosedByUser();
    }

    private static void forgetEverything() {
        Settings.SETTINGS_HEADER_NAME.resetToDefault();
        Settings.SETTINGS_HEADER_LOGO.resetToDefault();
        SettingsHeader.forgetPicture();
        for (File file : new File[]{copy(), partial(), HeaderPicture.file(app())}) {
            if (file.exists()) assertTrue(file.delete());
        }
    }

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static File copy() {
        return SettingsHeader.pictureFile(app());
    }

    private static File partial() {
        return new File(copy().getPath() + ".part");
    }

    /** A [width] by [height] picture all [color], as the bytes of a PNG file. */
    private static byte[] picture(int width, int height, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        return bytes.toByteArray();
    }

    /** The header drawn on its own, as the page draws it. */
    private static View header() {
        Context context = app();
        View view = new SettingsHeader(context).onCreateView(new FrameLayout(context));
        int width = Math.round(320 * context.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
        return view;
    }

    private static String nameIn(View header) {
        return ((TextView) header.findViewWithTag(SettingsHeader.NAME)).getText().toString();
    }

    private static Drawable logoIn(View header) {
        return ((ImageView) header.findViewWithTag(SettingsHeader.ICON)).getDrawable();
    }

    private void open() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        finish();
    }

    private static void finish() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    /** [text] without the direction marks L10n.isolate puts around a name. */
    private static String bare(CharSequence text) {
        StringBuilder kept = new StringBuilder();
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c < 0x2066 || c > 0x2069) kept.append(c);
        }
        return kept.toString();
    }

    private static int indexIn(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (group.getPreference(i) == row) return i;
        }
        return -1;
    }

    /** The page's own header row, drawn by the page's adapter. */
    private View pageHeader() {
        Preference header = page.getPreferenceScreen().findPreference(SettingsHeader.KEY);
        assertNotNull("the header shows with Black settings page on", header);
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i) == header) return adapter.getView(i, null, new FrameLayout(controller.get()));
        }
        throw new AssertionError("the header isn't on the page");
    }

    @Test public void itStartsOnHushGramsLogoAndName() {
        assertEquals("", Settings.SETTINGS_HEADER_NAME.defaultValue);
        assertEquals(SettingsHeader.Logo.HUSHGRAM, Settings.SETTINGS_HEADER_LOGO.defaultValue);
        assertEquals("HushGram", SettingsHeader.name());
        assertEquals(SettingsHeader.Logo.HUSHGRAM, SettingsHeader.shownLogo(app()));
        View header = header();
        assertEquals("HushGram", nameIn(header));
        assertNotNull(logoIn(header));
        TextView version = header.findViewWithTag(SettingsHeader.VERSION);
        assertTrue(version.getText().toString(), version.getText().toString().startsWith("Version "));
    }

    @Test public void aBlankNameShowsHushGram() {
        Settings.SETTINGS_HEADER_NAME.save("");
        assertEquals("HushGram", SettingsHeader.name());
        Settings.SETTINGS_HEADER_NAME.save("   \n\t  ");
        assertEquals("only spaces is blank too", "HushGram", SettingsHeader.name());
        assertEquals("HushGram", nameIn(header()));
    }

    @Test public void aTypedNameIsTrimmedToOneLineAndCapped() {
        assertEquals("My Gram", SettingsHeader.cleanName("  My \n  Gram  "));
        assertEquals("", SettingsHeader.cleanName(null));
        assertEquals(24, SettingsHeader.MAX_NAME);
        assertEquals("abcdefghij abcdefghij ab", SettingsHeader.cleanName("abcdefghij abcdefghij abcdefghij abcdefghij"));
        assertEquals("a cut that lands on a space drops it", "abcdefghijk abcdefghijk",
                SettingsHeader.cleanName("abcdefghijk abcdefghijk xyz"));

        Settings.SETTINGS_HEADER_NAME.save("  Gram   Two ");
        assertEquals("Gram Two", SettingsHeader.name());
        View header = header();
        assertEquals("Gram Two", nameIn(header));
        TextView version = header.findViewWithTag(SettingsHeader.VERSION);
        assertTrue("the version line stays", version.getText().toString().startsWith("Version "));
    }

    @Test public void instagramsIconCanTakeTheLogosPlace() {
        Settings.SETTINGS_HEADER_LOGO.save(SettingsHeader.Logo.INSTAGRAM);
        assertEquals(SettingsHeader.Logo.INSTAGRAM, SettingsHeader.shownLogo(app()));
        assertNotNull(logoIn(header()));
        assertEquals("HushGram", nameIn(header()));
    }

    @Test public void aPickedPictureIsShrunkKeptAndShownAfterARestart() throws Exception {
        SettingsHeader.keepPicture(app(), new ByteArrayInputStream(picture(2000, 1000, Color.RED)));
        assertEquals(SettingsHeader.Logo.PICTURE, Settings.SETTINGS_HEADER_LOGO.savedValue());
        assertTrue(copy().isFile());
        assertFalse("nothing left half written", partial().exists());
        assertFalse("the Home header's picture is untouched", HeaderPicture.file(app()).exists());
        Bitmap kept = BitmapFactory.decodeFile(copy().getPath());
        assertEquals(256, kept.getWidth());
        assertEquals(128, kept.getHeight());

        // A restart keeps nothing in memory: the saved choice and the file are all there is.
        SettingsHeader.forgetPicture();
        assertEquals(SettingsHeader.Logo.PICTURE, SettingsHeader.shownLogo(app()));
        Drawable drawn = logoIn(header());
        assertTrue(String.valueOf(drawn), drawn instanceof BitmapDrawable);
        assertEquals(256, ((BitmapDrawable) drawn).getBitmap().getWidth());
        assertEquals(Color.RED, ((BitmapDrawable) drawn).getBitmap().getPixel(10, 10));
    }

    @Test public void aMissingOrBrokenPictureFallsBackToHushGramsLogo() throws Exception {
        Settings.SETTINGS_HEADER_LOGO.save(SettingsHeader.Logo.PICTURE);
        assertEquals("no copy", SettingsHeader.Logo.HUSHGRAM, SettingsHeader.shownLogo(app()));
        assertNotNull(logoIn(header()));

        Files.write(copy().toPath(), "not a picture".getBytes(StandardCharsets.UTF_8));
        assertEquals("a copy that won't decode", SettingsHeader.Logo.HUSHGRAM, SettingsHeader.shownLogo(app()));
        Drawable drawn = logoIn(header());
        assertNotNull(drawn);
        assertFalse("not a broken picture",
                drawn instanceof BitmapDrawable && ((BitmapDrawable) drawn).getBitmap().getPixel(0, 0) == Color.RED);
        assertEquals("the choice stays for when a picture comes back",
                SettingsHeader.Logo.PICTURE, Settings.SETTINGS_HEADER_LOGO.savedValue());
    }

    @Test public void aFileThatIsntAPictureIsTurnedDownAndTheLogoStays() throws Exception {
        Settings.SETTINGS_HEADER_LOGO.save(SettingsHeader.Logo.INSTAGRAM);
        try {
            SettingsHeader.keepPicture(app(), new ByteArrayInputStream("nope".getBytes(StandardCharsets.UTF_8)));
            fail("not a picture");
        } catch (HeaderPicture.Refused refused) {
            assertEquals(HeaderPicture.Refusal.NOT_A_PICTURE, refused.reason);
        }
        assertEquals(SettingsHeader.Logo.INSTAGRAM, Settings.SETTINGS_HEADER_LOGO.savedValue());
        assertFalse(copy().exists());
        assertFalse(partial().exists());
    }

    @Test public void resetPutsTheLogoAndTheNameBack() throws Exception {
        Settings.SETTINGS_HEADER_NAME.save("Mine");
        SettingsHeader.keepPicture(app(), new ByteArrayInputStream(picture(100, 100, Color.BLUE)));
        assertTrue(copy().isFile());

        assertTrue(SettingsHeader.reset(app()));
        assertEquals("", Settings.SETTINGS_HEADER_NAME.savedValue());
        assertEquals(SettingsHeader.Logo.HUSHGRAM, Settings.SETTINGS_HEADER_LOGO.savedValue());
        assertFalse("the picture's copy goes too", copy().exists());
        assertEquals("HushGram", SettingsHeader.name());
        assertEquals(SettingsHeader.Logo.HUSHGRAM, SettingsHeader.shownLogo(app()));
    }

    @Test public void theNameIsBackedUpAndTheLogoStaysOnThePhone() {
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SETTINGS_HEADER_NAME.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.SETTINGS_HEADER_LOGO.key));
        assertTrue(ConfigurationBackup.textAllowed(Settings.SETTINGS_HEADER_NAME, "Gram Two"));
        assertFalse("the row never saves this", ConfigurationBackup.textAllowed(Settings.SETTINGS_HEADER_NAME, " Gram\nTwo"));
    }

    @Test public void theRowsFollowTheLookSwitchesAndYourPictureOpensThePicker() throws Exception {
        open();
        Preference logo = page.findPreference(Settings.SETTINGS_HEADER_LOGO.key);
        Preference name = page.findPreference(Settings.SETTINGS_HEADER_NAME.key);
        Preference reset = page.findPreference(HushgramPreferenceFragment.RESET_SETTINGS_HEADER_KEY);
        assertNotNull(logo);
        PreferenceGroup entry = (PreferenceGroup) logo.getParent();
        assertEquals("Settings entry", entry.getTitle().toString());
        int look = indexIn(entry, page.findPreference(Settings.SETTINGS_INSTAGRAM_LOOK.key));
        assertTrue(look >= 0);
        assertEquals("right under the look switches", look + 1, indexIn(entry, logo));
        assertEquals(look + 2, indexIn(entry, name));
        assertEquals(look + 3, indexIn(entry, reset));

        ListPreference logos = (ListPreference) logo;
        assertEquals("Settings page logo", logos.getTitle().toString());
        List<String> entries = new ArrayList<>();
        for (CharSequence choice : logos.getEntries()) entries.add(choice.toString());
        assertEquals(Arrays.asList("HushGram's logo", "Instagram's icon", "Your picture"), entries);
        assertEquals("HUSHGRAM", logos.getValue());
        assertEquals("HushGram's logo shows at the top of this page.", logos.getSummary().toString());
        assertEquals("Settings page name", name.getTitle().toString());
        assertEquals("HushGram shows at the top of this page.", name.getSummary().toString());
        assertEquals("Reset the settings page header", reset.getTitle().toString());

        // Your picture opens the picker and changes nothing until a picture comes back.
        assertFalse(logos.getOnPreferenceChangeListener().onPreferenceChange(logos, "PICTURE"));
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        assertNotNull(picked);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picked.intent.getAction());
        assertEquals("image/*", picked.intent.getType());
        assertEquals(SettingsHeader.Logo.HUSHGRAM, Settings.SETTINGS_HEADER_LOGO.savedValue());

        Shadows.shadowOf(controller.get().getContentResolver()).registerInputStream(DOCUMENT,
                new ByteArrayInputStream(picture(600, 600, Color.GREEN)));
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
        finish();
        assertEquals(SettingsHeader.Logo.PICTURE, Settings.SETTINGS_HEADER_LOGO.savedValue());
        assertEquals("PICTURE", logos.getValue());
        assertTrue(copy().isFile());
        assertEquals("Settings page logo set.", ShadowToast.getTextOfLatestToast());
        Drawable drawn = logoIn(pageHeader());
        assertTrue(String.valueOf(drawn), drawn instanceof BitmapDrawable);
        assertEquals(Color.GREEN, ((BitmapDrawable) drawn).getBitmap().getPixel(10, 10));

        // Instagram's icon saves at once.
        assertTrue(logos.getOnPreferenceChangeListener().onPreferenceChange(logos, "INSTAGRAM"));

        reset.getOnPreferenceClickListener().onPreferenceClick(reset);
        finish();
        assertEquals(SettingsHeader.Logo.HUSHGRAM, Settings.SETTINGS_HEADER_LOGO.savedValue());
        assertEquals("HUSHGRAM", logos.getValue());
        assertFalse(copy().exists());
        assertEquals("Settings page header reset.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aCanceledPickerLeavesTheLogoAsItWas() throws Exception {
        open();
        ListPreference logos = (ListPreference) page.findPreference(Settings.SETTINGS_HEADER_LOGO.key);
        logos.getOnPreferenceChangeListener().onPreferenceChange(logos, "PICTURE");
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_CANCELED, null);
        finish();
        assertEquals(SettingsHeader.Logo.HUSHGRAM, Settings.SETTINGS_HEADER_LOGO.savedValue());
        assertEquals("HUSHGRAM", logos.getValue());
        assertFalse(copy().exists());
    }

    @Test public void aTypedNameShowsInTheHeaderAndResetTakesItAway() throws Exception {
        open();
        HushgramPreferenceFragment.SettingsNameRow row =
                (HushgramPreferenceFragment.SettingsNameRow) page.findPreference(Settings.SETTINGS_HEADER_NAME.key);
        row.showDialog(null);
        AlertDialog dialog = (AlertDialog) row.getDialog();
        row.getEditText().setText("  Quiet   Gram ");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        finish();
        assertFalse(dialog.isShowing());
        assertEquals("Quiet Gram", Settings.SETTINGS_HEADER_NAME.savedValue());
        assertEquals("The top of this page says Quiet Gram.", bare(row.getSummary()));
        assertEquals("Quiet Gram", nameIn(pageHeader()));

        Preference reset = page.findPreference(HushgramPreferenceFragment.RESET_SETTINGS_HEADER_KEY);
        reset.getOnPreferenceClickListener().onPreferenceClick(reset);
        finish();
        assertEquals("", Settings.SETTINGS_HEADER_NAME.savedValue());
        assertEquals("HushGram shows at the top of this page.", row.getSummary().toString());
        assertEquals("HushGram", nameIn(pageHeader()));
    }
}

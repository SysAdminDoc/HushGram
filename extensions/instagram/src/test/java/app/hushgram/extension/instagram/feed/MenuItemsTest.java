/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What Three-dot menu items does (#697, #707): a feed menu loses the rows whose option is unticked,
 * a reel's row adder skips an unticked option, a reel's Edits rows go by their keys, and a story
 * menu loses Report and Mute by the icon Instagram gives each label. Nothing saved, paused or
 * anything thrown leaves the menu as built.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class MenuItemsTest {
    /** Stand-in for Instagram's option enum: the names are all the hooks read. */
    private enum Option { REPORT, REPORT_AD, MUTE_USER, UNFOLLOW_USER, NOT_INTERESTED, INTERESTED, WHY_AM_I_SEEING_THIS,
        PLAYBACK_SPEED, PLAYBACK_CONTROLS, SAVE, DOWNLOAD }

    /** A feed menu row the way Instagram's hold theirs: the option in a field of its own type. */
    private static final class Row {
        private final String label;
        private final Option option;

        Row(String label, Option option) {
            this.label = label;
            this.option = option;
        }

        @Override public String toString() {
            return label + "/" + option;
        }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDDEN_MENU_ITEMS.resetToDefault();
        HookStatus.clear();
        MenuItems.resetForTests();
    }

    @After
    public void restore() {
        Settings.HIDDEN_MENU_ITEMS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        MenuItems.resetForTests();
    }

    private static Set<String> hiding(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }

    private static List<Row> feedMenu() {
        return new ArrayList<>(Arrays.asList(new Row("Save", Option.SAVE), new Row("Report", Option.REPORT),
                new Row("Mute", Option.MUTE_USER), new Row("Unfollow", Option.UNFOLLOW_USER),
                new Row("Why", Option.WHY_AM_I_SEEING_THIS), new Row("Ad", Option.REPORT_AD)));
    }

    @Test
    public void aFeedMenuLosesTheUntickedRowsInOrder() {
        List<Row> rows = feedMenu();
        MenuItems.feedRows(rows, () -> hiding("REPORT", "MUTE"), () -> Option.REPORT, FeedSaveRow::optionOf);
        assertEquals("[Save/SAVE, Unfollow/UNFOLLOW_USER, Why/WHY_AM_I_SEEING_THIS]", rows.toString());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + MenuItems.LEFT_OFF + " 3"));
    }

    @Test
    public void nothingTickedOffLeavesTheFeedMenuAlone() {
        List<Row> rows = feedMenu();
        String before = rows.toString();
        MenuItems.feedRows(rows, Collections::emptySet, () -> Option.REPORT, FeedSaveRow::optionOf);
        assertEquals(before, rows.toString());
        MenuItems.feedRows(rows, () -> hiding("REPORT"), () -> null, FeedSaveRow::optionOf);
        assertEquals("no Report option to read rows against", before, rows.toString());
        MenuItems.feedRows("not a list", () -> hiding("REPORT"), () -> Option.REPORT, FeedSaveRow::optionOf);
    }

    @Test
    public void aFeedMenuThatCantBeReadStaysAsBuilt() {
        List<Row> rows = feedMenu();
        String before = rows.toString();
        MenuItems.feedRows(rows, () -> hiding("REPORT"), () -> Option.REPORT, (row, sample) -> {
            throw new IllegalAccessException("moved");
        });
        assertEquals(before, rows.toString());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("IllegalAccessException"));
    }

    @Test
    public void aReelRowIsSkippedOnlyWhenItsItemIsUnticked() {
        assertTrue(MenuItems.reelRow(Option.PLAYBACK_CONTROLS, () -> hiding("PLAYBACK")));
        assertTrue(MenuItems.reelRow(Option.INTERESTED, () -> hiding("INTERESTED")));
        assertFalse("Interested and Not interested are two items",
                MenuItems.reelRow(Option.NOT_INTERESTED, () -> hiding("INTERESTED")));
        assertFalse(MenuItems.reelRow(Option.DOWNLOAD, () -> hiding("REPORT", "MUTE", "PLAYBACK")));
        assertFalse(MenuItems.reelRow(null, () -> hiding("REPORT")));
        assertFalse(MenuItems.reelRow("REPORT", () -> hiding("REPORT")));
        assertFalse(MenuItems.reelRow(Option.REPORT, () -> {
            throw new IllegalStateException("settings");
        }));
    }

    @Test
    public void theSavedSettingDecidesAndPauseShowsEverything() {
        Settings.HIDDEN_MENU_ITEMS.save("WHY,UNFOLLOW");
        assertEquals(hiding("UNFOLLOW", "WHY"), MenuItems.hidden());
        assertTrue(MenuItems.reelRow(Option.WHY_AM_I_SEEING_THIS));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(Collections.emptySet(), MenuItems.hidden());
        assertFalse(MenuItems.reelRow(Option.WHY_AM_I_SEEING_THIS));
    }

    private static final int REPORT_ICON = 0x7f08290c;
    private static final int MUTE_ICON = 0x7f08228a;
    private static final int UNMUTE_ICON = 0x7f08228d;
    private static final int OTHER_ICON = 0x7f082aa4;

    private static CharSequence[] storyMenu() {
        return new CharSequence[] {"Download", "Report", "Mute", "Unmute", "About this account", "Odd"};
    }

    private static int iconOf(Object menu, CharSequence label) {
        switch (label.toString()) {
            case "Report":
                return REPORT_ICON;
            case "Mute":
                return MUTE_ICON;
            case "Unmute":
                return UNMUTE_ICON;
            case "About this account":
                return OTHER_ICON;
            case "Odd":
                return 0x7f0fffff;
            default:
                return 0;
        }
    }

    private static String nameOf(int icon) {
        Map<Integer, String> names = new HashMap<>();
        names.put(REPORT_ICON, MenuItems.REPORT_ICON);
        names.put(MUTE_ICON, MenuItems.MUTE_ICON);
        names.put(UNMUTE_ICON, "instagram_alert_outline_24");
        names.put(OTHER_ICON, "instagram_user_circle_outline_24");
        String name = names.get(icon);
        if (name == null) throw new Resources.NotFoundException("#0x" + Integer.toHexString(icon));
        return name;
    }

    @Test
    public void aStoryMenuLosesReportAndMuteByTheirIcons() {
        Object menu = new Object();
        CharSequence[] kept = MenuItems.storyLabels(storyMenu(), menu, () -> hiding("REPORT", "MUTE"),
                MenuItemsTest::iconOf, MenuItemsTest::nameOf);
        assertArrayEquals(new CharSequence[] {"Download", "Unmute", "About this account", "Odd"}, kept);
        assertEquals(CharSequence[].class, kept.getClass());
        kept = MenuItems.storyLabels(storyMenu(), menu, () -> hiding("MUTE"), MenuItemsTest::iconOf, MenuItemsTest::nameOf);
        assertArrayEquals(new CharSequence[] {"Download", "Report", "Unmute", "About this account", "Odd"}, kept);
    }

    @Test
    public void aStoryMenuIsLeftAloneWithoutItsMenuOrAnItemItCanLose() {
        CharSequence[] labels = storyMenu();
        assertSame(labels, MenuItems.storyLabels(labels, null, () -> hiding("REPORT"), MenuItemsTest::iconOf, MenuItemsTest::nameOf));
        assertSame(labels, MenuItems.storyLabels(labels, new Object(), () -> hiding("PLAYBACK", "UNFOLLOW"), (menu, label) -> {
            throw new AssertionError("no icon asked for when stories have nothing to lose");
        }, MenuItemsTest::nameOf));
        assertSame(labels, MenuItems.storyLabels(labels, new Object(), () -> hiding("REPORT"), (menu, label) -> {
            throw new IllegalStateException("moved");
        }, MenuItemsTest::nameOf));
        assertSame(null, MenuItems.storyLabels(null, new Object(), () -> hiding("REPORT"), MenuItemsTest::iconOf, MenuItemsTest::nameOf));
    }

    @Test
    public void theBuilderOnThisThreadNamesTheMenu() {
        MenuItems.storyMenu(new Object());
        // The stub answers no icon until the patch writes it, so the labels come back as they were.
        CharSequence[] labels = storyMenu();
        Settings.HIDDEN_MENU_ITEMS.save("REPORT");
        assertSame(labels, MenuItems.storyLabels(labels));
    }

    @Test
    public void theSavedValueKeepsOnlyOfferedNamesInListOrder() {
        assertEquals(hiding("REPORT", "WHY"), MenuItems.parse(" WHY , REPORT,REPORT,, SAVE"));
        assertEquals("REPORT,MUTE,PLAYBACK", MenuItems.join(hiding("PLAYBACK", "MUTE", "REPORT", "DOWNLOAD")));
        assertEquals("", MenuItems.join(Collections.emptySet()));
        assertEquals(Collections.emptySet(), MenuItems.parse(null));
        for (String name : MenuItems.OFFERED) {
            boolean read = MenuItems.itemOf(optionFor(name)) != null || name.equals(MenuItems.itemOfEditsKey(keyFor(name)))
                    || name.equals("LOOP");
            assertTrue(name, read);
        }
    }

    /** An Edits row the way Instagram holds one: its key in a String field. */
    private static final class EditsRow {
        final String key;

        EditsRow(String key) {
            this.key = key;
        }

        @Override public String toString() {
            return key;
        }
    }

    private static List<EditsRow> editsRows() {
        return Arrays.asList(new EditsRow("show_as_template"), new EditsRow("remix_and_sequence"),
                new EditsRow("use_as_reference"), new EditsRow("basel_made_with_edits_audio_menu"), new EditsRow("new_row"));
    }

    private static String keyOf(Object row) {
        return ((EditsRow) row).key;
    }

    @Test
    public void theEditsRowsLoseTheUntickedOnesAsANewList() {
        List<EditsRow> rows = editsRows();
        List<?> kept = MenuItems.editsRows(rows, () -> hiding("REMIX", "GET_EDITS"), MenuItemsTest::keyOf);
        assertEquals("[show_as_template, use_as_reference, new_row]", kept.toString());
        assertEquals("Instagram's list is left as it was", 5, rows.size());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + MenuItems.LEFT_OFF + " 2"));
        kept = MenuItems.editsRows(rows, () -> hiding("TEMPLATE", "REFERENCE"), MenuItemsTest::keyOf);
        assertEquals("[remix_and_sequence, basel_made_with_edits_audio_menu, new_row]", kept.toString());
    }

    @Test
    public void theEditsRowsStayAsBuiltWithNothingToLoseOrAKeyThatCantBeRead() {
        List<EditsRow> rows = editsRows();
        assertSame(rows, MenuItems.editsRows(rows, Collections::emptySet, MenuItemsTest::keyOf));
        assertSame(rows, MenuItems.editsRows(rows, () -> hiding("REPORT", "LOOP"), MenuItemsTest::keyOf));
        assertSame(rows, MenuItems.editsRows(rows, () -> hiding("REMIX"), row -> {
            throw new ClassCastException("moved");
        }));
        assertSame(null, MenuItems.editsRows(null, () -> hiding("REMIX"), MenuItemsTest::keyOf));
        List<EditsRow> none = Collections.emptyList();
        assertSame(none, MenuItems.editsRows(none, () -> hiding("REMIX"), MenuItemsTest::keyOf));
        // The stub answers no key until the patch writes it.
        Settings.HIDDEN_MENU_ITEMS.save("REMIX,REFERENCE,TEMPLATE,GET_EDITS");
        assertSame(rows, MenuItems.editsRows(rows));
    }

    @Test
    public void madeWithEditsGoesOnlyWithTheLastEditsRow() {
        List<EditsRow> rows = editsRows();
        List<?> left = MenuItems.editsRows(rows, () -> hiding("REMIX", "REFERENCE", "TEMPLATE", "GET_EDITS"), MenuItemsTest::keyOf);
        assertEquals(1, left.size());
        assertEquals("Made with Edits", MenuItems.editsTitle("Made with Edits", rows, left));
        List<?> gone = Collections.emptyList();
        assertEquals(null, MenuItems.editsTitle("Made with Edits", rows, gone));
        assertEquals("nothing there to begin with", "Made with Edits", MenuItems.editsTitle("Made with Edits", gone, gone));
        assertEquals(null, MenuItems.editsTitle(null, rows, rows));
        assertEquals("Made with Edits", MenuItems.editsTitle("Made with Edits", null, gone));
    }

    @Test
    public void loopNeedsThePatchInTheBuild() {
        Settings.HIDDEN_MENU_ITEMS.save("LOOP");
        assertFalse("SettingsStatus.menuItems() answers no until the patch writes it", MenuItems.hidesLoop());
    }

    private static String keyFor(String item) {
        switch (item) {
            case "REMIX":
                return "remix_and_sequence";
            case "REFERENCE":
                return "use_as_reference";
            case "TEMPLATE":
                return "show_as_template";
            case "GET_EDITS":
                return "basel_made_with_edits_audio_menu";
            default:
                return null;
        }
    }

    private static String optionFor(String item) {
        switch (item) {
            case "MUTE":
                return "MUTE_USER";
            case "UNFOLLOW":
                return "UNFOLLOW_USER";
            case "WHY":
                return "WHY_AM_I_SEEING_THIS";
            case "PLAYBACK":
                return "PLAYBACK_CONTROLS";
            default:
                return item;
        }
    }
}

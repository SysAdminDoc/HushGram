/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * The name a saved video gets. The template ships as the IG_VID_ naming, so nothing changes
 * for anyone who leaves it; its tokens fill in per save; and whatever it holds, the name is one
 * clean file name of bounded length that no path can come out of, that the gallery doesn't hide,
 * reserve or read as a photo, and that doesn't come out the same for every save.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FileNameTemplateTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;

    /** 2026-09-25 14:30:05 on the phone's clock, whatever zone the test runs in. */
    private static Date when() {
        return at(5);
    }

    /** The same minute, [second] seconds in. */
    private static Date at(int second) {
        Calendar calendar = new java.util.GregorianCalendar();
        calendar.clear();
        calendar.set(2026, Calendar.SEPTEMBER, 25, 14, 30, second);
        return calendar.getTime();
    }

    private static final String STAMP = "20260925_143005";
    private static final String ID = "1234567890123456";

    /** When the post went up: noon on 2026-09-01 on the phone's clock, so the day is the 1st in any zone. */
    private static Date posted() {
        Calendar calendar = new GregorianCalendar();
        calendar.clear();
        calendar.set(2026, Calendar.SEPTEMBER, 1, 12, 0, 0);
        return calendar.getTime();
    }

    private static final String DAY = "20260901";

    /** A save that knows everything of its post. */
    private static PostDetails full() {
        return new PostDetails(ID, "Stevi Ous", posted());
    }

    /** The extensions MediaStoreWriter gives a file, which no template may end with. */
    private static final String[] EXTENSIONS = {".mp4", ".m4v", ".mov", ".webm", ".3gp", ".jpg", ".jpeg", ".png",
            ".webp", ".heic", ".heif", ".avif", ".gif"};

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        LogBufferManager.clearLogBuffer();
        SaveLeftovers.forgetSweepForTests();
    }

    @After
    public void tearDown() {
        Settings.FILENAME_TEMPLATE.resetToDefault();
        Settings.SAVE_NAME_BY_POST.resetToDefault();
        MediaSave.policyForTests = null;
        MediaSave.detailsForTests = null;
        LogBufferManager.clearLogBuffer();
    }

    // ---- The name -------------------------------------------------------------------------------

    /** The template as it ships makes the name every save always had. */
    @Test
    public void theDefaultIsTheIgVidName() {
        assertEquals("IG_VID_{date}", FileNameTemplate.DEFAULT);
        assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName(FileNameTemplate.DEFAULT, when(), ID));
        assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName(FileNameTemplate.DEFAULT, when(), (String) null));
        assertEquals(FileNameTemplate.DEFAULT, FileNameTemplate.current());
        assertTrue(FileNameTemplate.isClean(FileNameTemplate.DEFAULT));
    }

    @Test
    public void theTokensFillInPerSave() {
        assertEquals(STAMP + "_" + ID, FileNameTemplate.videoName("{date}_{video_id}", when(), ID));
        assertEquals("Reel " + ID, FileNameTemplate.videoName("Reel {video_id}", when(), ID));
        assertEquals(ID, FileNameTemplate.videoName("{video_id}", when(), ID));
        assertEquals(ID + " " + ID, FileNameTemplate.videoName("{video_id} {video_id}", when(), ID));
        // Other braces stay as they are.
        assertEquals("{creator}_" + STAMP, FileNameTemplate.videoName("{creator}_{date}", when(), ID));
        // With no id, or one that isn't a number, the token is left out.
        assertEquals(STAMP + "_", FileNameTemplate.videoName("{date}_{video_id}", when(), (String) null));
        assertEquals(STAMP, FileNameTemplate.videoName("{date}{video_id}", when(), "12a4"));
        // The poster and the day the post went up fill in the same way, and only when known.
        assertEquals("Stevi Ous_" + DAY, FileNameTemplate.videoName("{owner}_{posted}", when(), full()));
        assertEquals("Stevi Ous_" + DAY + "_" + ID, FileNameTemplate.videoName("{owner}_{posted}_{video_id}", when(), full()));
        assertEquals(STAMP + " Stevi Ous", FileNameTemplate.videoName("{date} {owner}", when(), full()));
        assertEquals(STAMP + "_", FileNameTemplate.videoName("{date}_{owner}", when(), PostDetails.of(ID)));
        assertEquals(STAMP, FileNameTemplate.videoName("{date}{posted}", when(), new PostDetails(ID, "Stevi Ous", null)));
        assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName("{owner}{posted}", when(), PostDetails.NONE));
        assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName("{owner}", when(), (PostDetails) null));
        // The separators typed between tokens the save didn't know aren't a name either.
        assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName("{owner}_{posted}", when(), PostDetails.NONE));
        assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName("{video_id}-{owner}", when(), PostDetails.NONE));
        assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName("{owner} - {posted}", when(), PostDetails.of("x")));
        // A name that fills in to nothing is the default one.
        assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName("{video_id}", when(), (String) null));
        assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName("", when(), ID));
        assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName(null, when(), ID));
        // The date is written in Western digits and the Gregorian calendar whatever the locale.
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist-nu-thai"));
            assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName(FileNameTemplate.DEFAULT, when(), (String) null));
        } finally {
            Locale.setDefault(saved);
        }
    }

    /**
     * MediaStore numbers a name that's taken, (1) to (31), and then refuses the save. So no two
     * saves a second apart may get one name: a template without any token gets the date, and one
     * that counts on something the save doesn't know, the id, the poster or the post day, gets the
     * date and time on the end. Known, the poster and the day stand on their own: every video one
     * person posted on one day gets one name, as two saves of one video named by its id do, and
     * the writer puts the time on the end when the folder has it already
     * ({@link #aTakenNameGetsTheTimeOfTheSaveOnTheEnd}).
     */
    @Test
    public void noTemplateNamesEverySaveTheSame() {
        assertEquals("Clip_" + STAMP, FileNameTemplate.videoName("Clip", when(), ID));
        assertEquals("Reel_" + STAMP, FileNameTemplate.videoName("Reel {video_id}", when(), (String) null));
        for (String notAnId : new String[]{"12a4", "../../1", "12345678901234567890123456", "", " 1"}) {
            assertEquals(notAnId, "Reel_" + STAMP, FileNameTemplate.videoName("Reel {video_id}", when(), notAnId));
        }
        assertEquals("Reel_" + STAMP, FileNameTemplate.videoName("Reel_{video_id}", when(), (String) null));
        assertEquals("Reel_" + STAMP, FileNameTemplate.videoName("Reel {owner}", when(), PostDetails.NONE));
        // An unknown token goes, and the separator typed beside it stays, as it does for the id.
        assertEquals("_" + DAY + "_" + STAMP,
                FileNameTemplate.videoName("{owner}_{posted}", when(), new PostDetails(null, null, posted())));
        assertEquals("Stevi Ous_" + STAMP,
                FileNameTemplate.videoName("{owner}_{posted}", when(), new PostDetails(null, "Stevi Ous", null)));
        // A known id keeps saves apart on its own, whatever else is missing.
        assertEquals(ID + "_", FileNameTemplate.videoName("{video_id}_{owner}", when(), PostDetails.of(ID)));
        assertEquals("Stevi Ous_" + DAY, FileNameTemplate.videoName("{owner}_{posted}", when(), full()));
        assertEquals("Stevi Ous", FileNameTemplate.videoName("{owner}", when(), full()));

        String[] templates = {"Clip", "Reel {video_id}", "{video_id}", "Clip_", "x-", FileNameTemplate.DEFAULT,
                "{date}", "{creator}", "{owner}", "{posted}", "{owner}_{posted}", "{owner} {video_id}", "{posted}{date}",
                "Reel {owner}"};
        PostDetails[] known = {PostDetails.NONE, PostDetails.of(ID), new PostDetails(null, "Stevi Ous", posted()), full()};
        for (String template : templates) {
            for (PostDetails details : known) {
                String clean = FileNameTemplate.sanitize(template);
                String first = FileNameTemplate.videoName(template, at(5), details);
                String second = FileNameTemplate.videoName(template, at(6), details);
                boolean byThePost = !FileNameTemplate.usesDate(clean) && FileNameTemplate.keepsApart(clean,
                        details.hasVideoId(), details.hasOwner(), details.hasPosted());
                if (byThePost) {
                    // Named by what the post is: the same name for the same post.
                    assertEquals(template + " with " + details, first, second);
                } else {
                    assertNotEquals(template + " with " + details, first, second);
                }
            }
        }
    }

    /**
     * The poster's name comes in cleaned the way a folder name is and bounded like one, and it's
     * what gets cut when the name would run long, so a date at the end of the template survives.
     */
    @Test
    public void thePosterIsCleanedLikeTheFolderAndCutToWhatFits() {
        assertEquals("Stevi_Ous_" + DAY,
                FileNameTemplate.videoName("{owner}_{posted}", when(), new PostDetails(null, " Stevi/Ous. ", posted())));
        assertEquals("ab_" + STAMP,
                FileNameTemplate.videoName("{owner}_{date}", when(), new PostDetails(null, "a" + u(0x200B) + "b", null)));
        assertEquals("IG_VID_a_b", FileNameTemplate.videoName("IG_IMG_{owner}", when(), new PostDetails(ID, "a:b", null)));
        // A name of nothing but what a folder can't hold is no poster at all.
        assertFalse(new PostDetails(null, " .. ", null).hasOwner());
        assertFalse(new PostDetails(null, "", null).hasOwner());
        assertEquals("IG_VID_" + STAMP, FileNameTemplate.videoName("{owner}", when(), new PostDetails(null, "...", null)));

        String emoji = new String(Character.toChars(0x1F3AC));
        PostDetails longName = new PostDetails(null, repeat(emoji, 80), null);
        assertEquals(FileNameTemplate.MAX_OWNER_CODE_POINTS, longName.owner.codePointCount(0, longName.owner.length()));
        String name = FileNameTemplate.videoName(repeat("x", 30) + "{owner}_{date}", when(), longName);
        assertTrue(name, name.endsWith("_" + STAMP));
        assertTrue(name, name.startsWith(repeat("x", 30) + emoji));
        assertTrue(name, name.getBytes(StandardCharsets.UTF_8).length <= FileNameTemplate.MAX_NAME_BYTES);
        assertTrue(name, name.getBytes(StandardCharsets.UTF_8).length > FileNameTemplate.MAX_NAME_BYTES - 4);
        // Two places for the poster share the room.
        String twice = FileNameTemplate.videoName("{owner}_{owner}_{date}", when(), longName);
        assertTrue(twice, twice.endsWith("_" + STAMP));
        assertTrue(twice, twice.getBytes(StandardCharsets.UTF_8).length <= FileNameTemplate.MAX_NAME_BYTES);
        assertTrue(twice, twice.startsWith(emoji) && twice.contains("_" + emoji));
        assertTrue(twice, twice.getBytes(StandardCharsets.UTF_8).length > FileNameTemplate.MAX_NAME_BYTES - 8);
    }

    /**
     * A name the save folder already has gets the time of the save on the end, before MediaStore's
     * numbering runs out at (31). Only a name without the date and time of the save needs it: one
     * with them can only be taken by a save in the same second, which MediaStore numbers, so there
     * the answer is null. That covers a name that counts on something the save doesn't know too,
     * since the date and time went on its end already.
     */
    @Test
    public void aTakenNameGetsTheTimeOfTheSaveOnTheEnd() {
        assertEquals("Stevi Ous_" + DAY + "_143005", FileNameTemplate.takenVideoName("{owner}_{posted}", when(), full()));
        assertEquals(ID + "_143005", FileNameTemplate.takenVideoName("{video_id}", when(), PostDetails.of(ID)));
        assertEquals("Reel " + ID + "_143006", FileNameTemplate.takenVideoName("Reel {video_id}", at(6), PostDetails.of(ID)));
        assertEquals("Stevi Ous_143005", FileNameTemplate.takenVideoName("{owner}", when(), full()));
        assertEquals(DAY + "_143005", FileNameTemplate.takenVideoName("{posted}", when(), full()));
        // A separator the name already ends with isn't doubled.
        assertEquals("Stevi Ous_143005", FileNameTemplate.takenVideoName("{owner}_", when(), full()));
        assertEquals("Stevi Ous-143005", FileNameTemplate.takenVideoName("{owner}-", when(), full()));
        // Photos' prefix stays out of it, as it does of the first name.
        assertEquals("IG_VID_Stevi Ous_143005", FileNameTemplate.takenVideoName("IG_IMG_{owner}", when(), full()));

        for (String template : new String[]{FileNameTemplate.DEFAULT, "{date}", "{owner}_{date}", "{video_id} {date}",
                "{posted}{date}"}) {
            assertNull(template, FileNameTemplate.takenVideoName(template, when(), full()));
        }
        assertNull(FileNameTemplate.takenVideoName("Reel {video_id}", when(), PostDetails.NONE));
        assertNull(FileNameTemplate.takenVideoName("{owner}_{posted}", when(), new PostDetails(null, "Stevi Ous", null)));
        assertNull(FileNameTemplate.takenVideoName("{owner}_{posted}", when(), PostDetails.NONE));
        assertNull(FileNameTemplate.takenVideoName("{video_id}", when(), (PostDetails) null));

        // Western digits whatever the locale, like the date.
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist-nu-thai"));
            assertEquals(ID + "_143005", FileNameTemplate.takenVideoName("{video_id}", when(), PostDetails.of(ID)));
        } finally {
            Locale.setDefault(saved);
        }
    }

    /** The time takes its room from the poster's name, so the end of the template stays whole. */
    @Test
    public void aTakenNameShortensThePosterNotTheEnd() {
        String emoji = new String(Character.toChars(0x1F3AC));
        PostDetails longName = new PostDetails(null, repeat(emoji, 80), posted());
        String template = repeat("x", 30) + "{owner}_{posted}";

        // 30 bytes of x and 9 of "_" and the day leave 161 for the poster: 40 four-byte characters.
        assertEquals(repeat("x", 30) + repeat(emoji, 40) + "_" + DAY, FileNameTemplate.videoName(template, when(), longName));
        // Seven more go to "_143005": 38 characters.
        String taken = FileNameTemplate.takenVideoName(template, when(), longName);
        assertEquals(repeat("x", 30) + repeat(emoji, 38) + "_" + DAY + "_143005", taken);
        assertTrue(taken, taken.getBytes(StandardCharsets.UTF_8).length <= FileNameTemplate.MAX_NAME_BYTES);
    }

    /** The post day is the phone's day: the same moment is one day in New York and the next in Tokyo. */
    @Test
    public void thePostDayIsThePhonesDay() {
        Calendar utc = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(2026, Calendar.SEPTEMBER, 1, 23, 30, 0);
        Date late = utc.getTime();
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            assertEquals("20260901", FileNameTemplate.videoName("{posted}", when(), new PostDetails(null, null, late)));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            assertEquals("20260902", FileNameTemplate.videoName("{posted}", when(), new PostDetails(null, null, late)));
        } finally {
            TimeZone.setDefault(saved);
        }
        // Western digits and the Gregorian calendar whatever the locale, like the date.
        Locale savedLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist-nu-thai"));
            assertEquals(DAY, FileNameTemplate.videoName("{posted}", when(), new PostDetails(null, null, posted())));
        } finally {
            Locale.setDefault(savedLocale);
        }
    }

    /** Each typed or imported template, and the one template a save uses for it. */
    @Test
    public void everyTemplateBecomesOneCleanName() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("IG_VID_{date}", "IG_VID_{date}");
        cases.put("{date} {video_id}", "{date} {video_id}");
        cases.put("My/{date}", "My_{date}");
        cases.put("../../{video_id}", "{video_id}");
        cases.put("a\\b:c*d?e\"f<g>h|i{date}", "a_b_c_d_e_f_g_h_i{date}");
        cases.put(".{date}", "{date}");
        cases.put("{date}.", "{date}");
        cases.put("..", FileNameTemplate.DEFAULT);
        cases.put("   ", FileNameTemplate.DEFAULT);
        cases.put("", FileNameTemplate.DEFAULT);
        cases.put(null, FileNameTemplate.DEFAULT);
        cases.put("a" + u(0x200B) + "b{date}", "ab{date}");
        cases.put(u(0x202E) + "Clip{date}", "Clip{date}");
        cases.put("a\nb{date}", "a b{date}");
        cases.put(u(0xFF0F) + "{date}", "{date}");
        // A template with no token gets the date; any one of the four is a token.
        cases.put("Clip", "Clip_{date}");
        cases.put("Clip_", "Clip_{date}");
        cases.put("Clip-", "Clip-{date}");
        cases.put("a\\b", "a_b_{date}");
        cases.put("{creator}", "{creator}_{date}");
        cases.put("{owner}", "{owner}");
        cases.put("{posted}", "{posted}");
        cases.put("{owner}_{posted}", "{owner}_{posted}");
        cases.put("Reel {owner}.mp4", "Reel {owner}");
        cases.put("IG_IMG_{posted}", "IG_VID_{posted}");
        cases.put("../{owner}", "{owner}");
        // The extension is the writer's to give.
        cases.put("{date}.mp4", "{date}");
        cases.put("{video_id}.jpg.WEBM", "{video_id}");
        cases.put("clip.MP4", "clip_{date}");
        cases.put("{date} .mov", "{date}");
        cases.put("{date}.mp4v", "{date}.mp4v");
        // The photo names stay photos'.
        cases.put("IG_IMG_{date}", "IG_VID_{date}");
        cases.put("ig_img_{video_id}", "IG_VID_{video_id}");
        cases.put("IG_IMG_", FileNameTemplate.DEFAULT);
        cases.put("IG_IMG_Clip", "IG_VID_Clip_{date}");
        // MediaStore's hidden and reserved names start with a dot, and none can.
        cases.put(".pending-1234-{date}", "pending-1234-{date}");
        cases.put(".trashed-1234-clip", "trashed-1234-clip_{date}");
        cases.put(".nomedia", "nomedia_{date}");
        StringBuilder wrong = new StringBuilder();
        for (Map.Entry<String, String> entry : cases.entrySet()) {
            String got = FileNameTemplate.sanitize(entry.getKey());
            if (!entry.getValue().equals(got)) {
                wrong.append('\n').append(entry.getKey()).append(" -> ").append(got).append(", expected ").append(entry.getValue());
            }
            assertTrue(got, FileNameTemplate.isClean(got));
            assertEquals(got, FileNameTemplate.sanitize(got));
        }
        assertEquals("", wrong.toString());
        for (String unclean : new String[]{"a/b{date}", "", null, " {date}", "Clip", "{date}.mp4", "IG_IMG_{date}",
                ".nomedia{date}"}) {
            assertFalse(String.valueOf(unclean), FileNameTemplate.isClean(unclean));
        }
    }

    /**
     * Whatever the template and the id, the name is one file name: no separator, no dot or space
     * at either end, nothing invisible, no photo's name, and no more bytes than leave room for an
     * extension. And the template a save keeps always has a token, and no extension of its own.
     */
    @Test
    public void noTemplateMakesAPathOrAnOverlongName() {
        assertEquals(FileNameTemplate.MAX_TEMPLATE_CODE_POINTS, FileNameTemplate.sanitize(repeat("a", 300)).length());
        assertEquals(repeat("a", 43) + "_{date}", FileNameTemplate.sanitize(repeat("a", 300)));
        String emoji = new String(Character.toChars(0x1F3AC));
        String fifty = FileNameTemplate.sanitize(repeat(emoji, 80));
        assertEquals(50, fifty.codePointCount(0, fifty.length()));
        assertTrue(FileNameTemplate.videoName(fifty, when(), ID).getBytes(StandardCharsets.UTF_8).length
                <= FileNameTemplate.MAX_NAME_BYTES);
        String ids = FileNameTemplate.sanitize(repeat(emoji, 40) + "{video_id}");
        assertTrue(FileNameTemplate.videoName(ids, when(), (String) null).getBytes(StandardCharsets.UTF_8).length
                <= FileNameTemplate.MAX_NAME_BYTES);

        Random random = new Random(20260925L);
        String[] pool = {"a", "Z", "0", " ", ".", "/", "\\", ":", "_", "-", "{date}", "{video_id}", "{", "}", "\u0000",
                "\n", u(0x200B), u(0x202E), u(0xFF0F), u(0x00E9), u(0x0301), emoji, u(0xAC00), u(0x3164),
                "IG_IMG_", "ig_img_", ".mp4", ".JPG", "mp", "4", ".pending-", "{owner}", "{posted}"};
        PostDetails[] known = {PostDetails.NONE, PostDetails.of(ID), PostDetails.of("x/1"), full(),
                new PostDetails(ID, "Stevi/Ous " + emoji + u(0x202E) + ".", posted()),
                new PostDetails(null, repeat(emoji, 80), null), new PostDetails(null, " . ", posted())};
        Pattern bad = Pattern.compile("[/\\\\:*?\"<>|\\p{Cntrl}\\p{Cf}]");
        for (int round = 0; round < 5_000; round++) {
            StringBuilder template = new StringBuilder();
            int length = random.nextInt(40);
            for (int i = 0; i < length; i++) template.append(pool[random.nextInt(pool.length)]);
            PostDetails details = known[random.nextInt(known.length)];
            String name = FileNameTemplate.videoName(template.toString(), when(), details);
            assertFalse(template + " -> " + name, name.isEmpty() || bad.matcher(name).find()
                    || name.startsWith(".") || name.endsWith(".") || name.startsWith(" ") || name.endsWith(" "));
            assertFalse(template + " -> " + name, name.regionMatches(true, 0, "IG_IMG_", 0, 7));
            assertTrue(template + " -> " + name,
                    name.getBytes(StandardCharsets.UTF_8).length <= FileNameTemplate.MAX_NAME_BYTES);
            // The name for a folder that has this one already: none when the date and time are in
            // it, and otherwise one file name the same way, with the time of the save on the end.
            String taken = FileNameTemplate.takenVideoName(template.toString(), when(), details);
            assertEquals(template + " -> " + name + ", taken " + taken, name.contains(STAMP), taken == null);
            if (taken != null) {
                assertFalse(template + " -> " + taken, taken.isEmpty() || bad.matcher(taken).find()
                        || taken.startsWith(".") || taken.endsWith(".") || taken.startsWith(" "));
                assertFalse(template + " -> " + taken, taken.regionMatches(true, 0, "IG_IMG_", 0, 7));
                assertTrue(template + " -> " + taken, taken.endsWith("143005"));
                assertTrue(template + " -> " + taken,
                        taken.getBytes(StandardCharsets.UTF_8).length <= FileNameTemplate.MAX_NAME_BYTES);
            }
            String once = FileNameTemplate.sanitize(template.toString());
            assertEquals(template.toString(), once, FileNameTemplate.sanitize(once));
            boolean tokened = false;
            for (String token : FileNameTemplate.TOKENS) tokened |= once.contains(token);
            assertTrue(template + " -> " + once, tokened);
            assertFalse(template + " -> " + once, once.regionMatches(true, 0, "IG_IMG_", 0, 7));
            for (String extension : EXTENSIONS) {
                assertFalse(template + " -> " + once, once.toLowerCase(Locale.ROOT).endsWith(extension));
            }
            assertTrue(template + " -> " + once, once.codePointCount(0, once.length())
                    <= FileNameTemplate.MAX_TEMPLATE_CODE_POINTS);
        }
    }

    /** A template from a phone on a newer Android can hold a character this one doesn't know yet. */
    @Test
    public void aTemplateFromANewerAndroidComesInAsTheNameSavesUseHere() {
        String unknown = new String(Character.toChars(0x50000));
        assertEquals(Character.UNASSIGNED, Character.getType(0x50000));
        assertTrue(FileNameTemplate.isImportable("Clip " + unknown + " {date}"));
        assertTrue(FileNameTemplate.isImportable(FileNameTemplate.DEFAULT));
        for (String refused : new String[]{"../" + unknown, "a/{date}", " {date}", "{date}.", "", null,
                "Clip " + unknown, "{date}.mp4", "IG_IMG_{date}"}) {
            assertFalse(String.valueOf(refused), FileNameTemplate.isImportable(refused));
        }
        assertEquals("Clip {date}", FileNameTemplate.sanitize("Clip " + unknown + " {date}"));
    }

    @Test
    public void theTemplateFollowsTheSetting() {
        Settings.FILENAME_TEMPLATE.save("../My/{video_id}");
        assertEquals("a value written past the settings row still makes one clean template",
                "My_{video_id}", FileNameTemplate.current());
        Settings.FILENAME_TEMPLATE.save("Clip.mp4");
        assertEquals("Clip_{date}", FileNameTemplate.current());
        SettingsContextRule.withoutContext(() -> assertEquals(FileNameTemplate.DEFAULT, FileNameTemplate.current()));
    }

    // ---- The gallery ----------------------------------------------------------------------------

    private SaveProgressTest.Gallery gallery() {
        return Robolectric.setupContentProvider(SaveProgressTest.Gallery.class, MediaStore.AUTHORITY);
    }

    private static String nameOf(ContentValues row) {
        return row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
    }

    /** With the template as it ships, a video and a photo get exactly the names they always did. */
    @Test
    public void withTheDefaultEveryFileKeepsItsName() throws Exception {
        SaveProgressTest.Gallery gallery = gallery();
        writable(gallery, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 1);
        writable(gallery, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2);
        MediaStoreWriter video = new MediaStoreWriter(context, true, ID);
        video.open("video/mp4").close();
        MediaStoreWriter photo = new MediaStoreWriter(context, false, ID);
        photo.open("image/webp").close();
        assertTrue(nameOf(gallery.rows.get(1L)), nameOf(gallery.rows.get(1L)).matches("IG_VID_\\d{8}_\\d{6}\\.mp4"));
        assertTrue(nameOf(gallery.rows.get(2L)), nameOf(gallery.rows.get(2L)).matches("IG_IMG_\\d{8}_\\d{6}\\.webp"));
        video.abandon();
        photo.abandon();
    }

    /** A template names videos and leaves photos alone; the id fills in when the save has one. */
    @Test
    public void aTemplateNamesVideosAndNotPhotos() throws Exception {
        Settings.FILENAME_TEMPLATE.save("Reel {video_id}");
        SaveProgressTest.Gallery gallery = gallery();
        writable(gallery, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 1);
        writable(gallery, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2);
        writable(gallery, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 3);
        new MediaStoreWriter(context, true, ID).open("video/webm").close();
        new MediaStoreWriter(context, false, ID).open("image/jpeg").close();
        new MediaStoreWriter(context, true, (String) null).open("video/mp4").close();
        assertEquals("Reel " + ID + ".webm", nameOf(gallery.rows.get(1L)));
        assertTrue(nameOf(gallery.rows.get(2L)), nameOf(gallery.rows.get(2L)).matches("IG_IMG_\\d{8}_\\d{6}\\.jpg"));
        // No id: the date and time keep this save's name apart from the next one's.
        assertTrue(nameOf(gallery.rows.get(3L)), nameOf(gallery.rows.get(3L)).matches("Reel_\\d{8}_\\d{6}\\.mp4"));

        // The report says the id was missing, and never names one.
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the file name asks for the video id and this save has none, "
                + "so the date and time go on the end"));
        assertFalse(report, report.contains(ID));
    }

    private void writable(SaveProgressTest.Gallery gallery, android.net.Uri table, long id) {
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                android.content.ContentUris.withAppendedId(table, id), new ByteArrayOutputStream());
    }

    /**
     * A template of the poster and the post day names a video after them, as issue #6 asked; a
     * save that knows neither falls back to the default name, and the report says so without
     * naming anyone.
     */
    @Test
    public void aTemplateNamesVideosAfterThePosterAndTheDay() throws Exception {
        Settings.FILENAME_TEMPLATE.save("{owner}_{posted}");
        SaveProgressTest.Gallery gallery = gallery();
        writable(gallery, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 1);
        writable(gallery, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 2);
        writable(gallery, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 3);
        new MediaStoreWriter(context, true, full()).open("video/mp4").close();
        new MediaStoreWriter(context, true, PostDetails.of(ID)).open("video/mp4").close();
        new MediaStoreWriter(context, true, new PostDetails(ID, "Stevi Ous", null)).open("video/webm").close();

        assertEquals("Stevi Ous_" + DAY + ".mp4", nameOf(gallery.rows.get(1L)));
        assertTrue(nameOf(gallery.rows.get(2L)), nameOf(gallery.rows.get(2L)).matches("IG_VID_\\d{8}_\\d{6}\\.mp4"));
        assertTrue(nameOf(gallery.rows.get(3L)), nameOf(gallery.rows.get(3L)).matches("Stevi Ous_\\d{8}_\\d{6}\\.webm"));

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the file name asks for the poster and the post date and this save has "
                + "neither, so the date and time go on the end"));
        assertTrue(report, report.contains("the file name asks for the post date and this save has none, so the "
                + "date and time go on the end"));
        assertFalse(report, report.contains("Stevi"));
        assertFalse(report, report.contains(ID));
    }

    /**
     * A whole save through the job every single-file route runs, with the id the route hands to
     * the save: the gallery row is named from the template when it's published.
     */
    @Test
    public void aSaveIsNamedFromTheTemplateWithItsVideosId() throws Exception {
        Settings.FILENAME_TEMPLATE.save("{video_id}_{date}");
        SaveProgressTest.Gallery gallery = gallery();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), new ByteArrayOutputStream());
        try (LocalServer server = new LocalServer()) {
            int port = server.port();
            MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
                @Override
                Refusal refusal(URL url) {
                    if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                    return super.refusal(url);
                }
            };
            byte[] body = new byte[64_000];
            byte[] head = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2' };
            System.arraycopy(head, 0, body, 0, head.length);
            server.serve("/clip.mp4", 200, "video/mp4", body, body.length);

            Thread worker = MediaSave.start(context, true, ID,
                    MediaSave.fileJob(context, server.origin() + "/clip.mp4", Downloader.Kind.VIDEO));
            worker.join(30_000);
            assertFalse(worker.isAlive());
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            String name = nameOf(gallery.rows.get(1L));
            assertTrue(name, name.matches(ID + "_\\d{8}_\\d{6}\\.mp4"));
            assertEquals(Integer.valueOf(0), gallery.rows.get(1L).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        }
    }

    // ---- A name the folder already has ------------------------------------------------------------

    private static final Uri VIDEOS = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

    private FolderGallery folderGallery() {
        return Robolectric.setupContentProvider(FolderGallery.class, MediaStore.AUTHORITY);
    }

    private void writableVideos(long... ids) {
        for (long id : ids) {
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                    ContentUris.withAppendedId(VIDEOS, id), new ByteArrayOutputStream());
        }
    }

    /** A video save through to the gallery's publish, as a finished download makes it. */
    private static void save(MediaStoreWriter writer) throws IOException {
        writer.open("video/mp4").write(new byte[] {0, 0, 0, 0x18});
        writer.commit();
    }

    private static int count(String text, String of) {
        int count = 0;
        for (int at = text.indexOf(of); at >= 0; at = text.indexOf(of, at + 1)) count++;
        return count;
    }

    private static final String TIME_WENT_ON = "the file name was already in the save folder, so the time of the save went on the end";

    /**
     * Issue #6's {owner}_{posted} gives every video one person posted on one day the same name.
     * The second one's save finds it in the folder and takes the time of the save on the end,
     * where MediaStore would have numbered it. Another poster's video that day keeps its name, and
     * the report says what happened without naming anyone.
     */
    @Test
    public void aNameAlreadyInTheFolderGetsTheTimeOfTheSave() throws Exception {
        Settings.FILENAME_TEMPLATE.save("{owner}_{posted}");
        FolderGallery gallery = folderGallery();
        writableVideos(1, 2, 3);
        save(new MediaStoreWriter(context, true, full()));
        save(new MediaStoreWriter(context, true, full()));
        save(new MediaStoreWriter(context, true, new PostDetails(ID, "Someone Else", posted())));

        assertEquals("Stevi Ous_" + DAY + ".mp4", nameOf(gallery.rows.get(1L)));
        String second = nameOf(gallery.rows.get(2L));
        assertTrue(second, second.matches("Stevi Ous_" + DAY + "_\\d{6}\\.mp4"));
        assertEquals("Someone Else_" + DAY + ".mp4", nameOf(gallery.rows.get(3L)));
        assertEquals(Arrays.asList("Stevi Ous_" + DAY + ".mp4", "Stevi Ous_" + DAY + ".mp4", "Someone Else_" + DAY + ".mp4"),
                gallery.lookedUp);

        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, count(report, TIME_WENT_ON));
        assertFalse(report, report.contains("Stevi"));
        assertFalse(report, report.contains("Someone"));
    }

    /** Saving one video twice under a name made of its id is the same case. */
    @Test
    public void aVideoSavedTwiceUnderItsIdGetsTheTimeTheSecondTime() throws Exception {
        Settings.FILENAME_TEMPLATE.save("HF_{video_id}");
        FolderGallery gallery = folderGallery();
        writableVideos(1, 2);
        save(new MediaStoreWriter(context, true, ID));
        save(new MediaStoreWriter(context, true, ID));

        assertEquals("HF_" + ID + ".mp4", nameOf(gallery.rows.get(1L)));
        String second = nameOf(gallery.rows.get(2L));
        assertTrue(second, second.matches("HF_" + ID + "_\\d{6}\\.mp4"));
    }

    /**
     * Only a published video of that name in the same folder takes it. Not one in another folder,
     * not one still pending, whose file has another name until it's published, and not a picture.
     */
    @Test
    public void onlyAPublishedVideoInTheSameFolderTakesAName() throws Exception {
        Settings.FILENAME_TEMPLATE.save("{owner}_{posted}");
        FolderGallery gallery = folderGallery();
        String name = "Stevi Ous_" + DAY + ".mp4";
        gallery.put(VIDEOS, "Movies/Other", name, false);
        gallery.put(VIDEOS, "Movies/Instagram", name, true);
        gallery.put(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Movies/Instagram", name, false);
        writableVideos(4);
        save(new MediaStoreWriter(context, true, full()));

        assertEquals(name, nameOf(gallery.rows.get(4L)));
        assertEquals(Collections.singletonList(name), gallery.lookedUp);
        assertFalse(LogBufferManager.buildExportText().contains(TIME_WENT_ON));
    }

    /**
     * The case this is for. With the name and (1) to (31) in the folder, MediaStore refuses the
     * next save of that name, which the control shows; the writer's save still goes in.
     */
    @Test
    public void aFolderWhoseNumberingRanOutStillTakesTheNextSave() throws Exception {
        Settings.FILENAME_TEMPLATE.save("{owner}_{posted}");
        FolderGallery gallery = folderGallery();
        String base = "Stevi Ous_" + DAY;
        gallery.put(VIDEOS, "Movies/Instagram", base + ".mp4", false);
        for (int n = 1; n <= 31; n++) gallery.put(VIDEOS, "Movies/Instagram", base + " (" + n + ").mp4", false);
        ContentValues plain = new ContentValues();
        plain.put(MediaStore.MediaColumns.DISPLAY_NAME, base + ".mp4");
        plain.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Instagram");
        plain.put(MediaStore.MediaColumns.IS_PENDING, 1);
        assertNull("the gallery took a 33rd video of one name", context.getContentResolver().insert(VIDEOS, plain));

        writableVideos(33);
        save(new MediaStoreWriter(context, true, full()));
        String name = nameOf(gallery.rows.get(33L));
        assertTrue(name, name.matches(base + "_\\d{6}\\.mp4"));
    }

    /**
     * A name with the date and time of the save in it can only be taken by a save in the same
     * second. The writer leaves that to MediaStore's numbering rather than put the time on twice.
     */
    @Test
    public void aNameWithTheDateAndTimeIsLeftToMediaStore() throws Exception {
        Settings.FILENAME_TEMPLATE.save("{owner}_{date}");
        FolderGallery gallery = folderGallery();
        gallery.everyNameTaken = true;
        writableVideos(1);
        save(new MediaStoreWriter(context, true, full()));

        String name = nameOf(gallery.rows.get(1L));
        assertTrue(name, name.matches("Stevi Ous_\\d{8}_\\d{6}\\.mp4"));
        assertEquals(1, gallery.lookedUp.size());
        assertFalse(LogBufferManager.buildExportText().contains(TIME_WENT_ON));
    }

    /**
     * A lookup MediaStore refuses leaves the name as it was, and MediaStore numbers it as before.
     * The report names the kind of failure, never the name.
     */
    @Test
    public void aLookupMediaStoreRefusesLeavesTheNameToItsNumbering() throws Exception {
        Settings.FILENAME_TEMPLATE.save("{owner}_{posted}");
        FolderGallery gallery = folderGallery();
        gallery.put(VIDEOS, "Movies/Instagram", "Stevi Ous_" + DAY + ".mp4", false);
        gallery.refuseLookups = true;
        writableVideos(2);
        save(new MediaStoreWriter(context, true, full()));

        assertEquals("Stevi Ous_" + DAY + " (1).mp4", nameOf(gallery.rows.get(2L)));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("could not look for the file name in the save folder (IllegalArgumentException)"));
        assertFalse(report, report.contains("Stevi"));
        assertFalse(report, report.contains(TIME_WENT_ON));
    }

    /**
     * MediaStore's video and image tables as a save meets them in a folder that already holds
     * files. A name stays unique in its folder: the next one is numbered (1) to (31), and after that
     * the insert is refused. A folder is kept with a slash on the end. The writer's lookup of a name
     * in a folder finds only published rows of the table it asks, as MediaStore's own query does
     * unless told to include pending and trashed ones.
     */
    public static final class FolderGallery extends ContentProvider {
        final Map<Long, ContentValues> rows = new LinkedHashMap<>();
        private final Map<Long, Uri> tables = new HashMap<>();
        final List<String> lookedUp = new ArrayList<>();
        boolean refuseLookups;
        boolean everyNameTaken;
        private long nextId = 1;

        /** A row already in [table], in [folder] and named [name], published or still [pending]. */
        void put(Uri table, String folder, String name, boolean pending) {
            ContentValues row = new ContentValues();
            row.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            row.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
            row.put(MediaStore.MediaColumns.IS_PENDING, pending ? 1 : 0);
            add(table, row);
        }

        private long add(Uri table, ContentValues row) {
            String folder = row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
            if (!folder.endsWith("/")) row.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/");
            long id = nextId++;
            rows.put(id, row);
            tables.put(id, table);
            return id;
        }

        private boolean published(Uri table, String folder, String name) {
            for (Map.Entry<Long, ContentValues> entry : rows.entrySet()) {
                ContentValues row = entry.getValue();
                if (table.equals(tables.get(entry.getKey()))
                        && folder.equals(row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
                        && name.equals(row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
                        && !Integer.valueOf(1).equals(row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))) {
                    return true;
                }
            }
            return false;
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri table, ContentValues values) {
            ContentValues row = new ContentValues(values);
            String name = row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
            String folder = row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
            if (!folder.endsWith("/")) folder += "/";
            int dot = name.lastIndexOf('.');
            String unique = name;
            for (int n = 1; published(table, folder, unique); n++) {
                if (n > 31) return null;
                unique = name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
            }
            row.put(MediaStore.MediaColumns.DISPLAY_NAME, unique);
            return ContentUris.withAppendedId(table, add(table, row));
        }

        /** Answers the writer's lookup and nothing else, the way MediaStore would. */
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            MatrixCursor cursor = new MatrixCursor(projection == null ? new String[0] : projection);
            if (!MediaStoreWriter.SAME_NAME_IN_FOLDER.equals(selection)) return cursor;
            if (refuseLookups) throw new IllegalArgumentException("Invalid token");
            lookedUp.add(selectionArgs[0]);
            boolean found = everyNameTaken;
            for (String folder : new String[] {selectionArgs[1], selectionArgs[2]}) {
                found |= published(uri, folder, selectionArgs[0]);
            }
            if (found) cursor.addRow(new Object[] {1L});
            return cursor;
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }

        @Override public String getType(Uri uri) {
            return "video/mp4";
        }
    }

    // ---- Name saves by account and post time (#20) -------------------------------------------------

    /** When the post went up, as a name by post writes it: noon on 2026-09-01 on the phone's clock. */
    private static final String POSTED_STAMP = DAY + "_120000";

    /**
     * A name by post is the account and the post's time, with a carousel page's number and, when
     * the name's already taken, the time of the save. A save that doesn't know the account or the
     * time gets none, and the account is what gets cut to fit, never the times.
     */
    @Test
    public void aNameByPostIsTheAccountAndThePostTime() {
        String base = "Stevi Ous_" + POSTED_STAMP;
        assertEquals(base, FileNameTemplate.postName(when(), full(), false));
        assertEquals(base + "_143005", FileNameTemplate.postName(when(), full(), true));
        assertEquals(base + "_3", FileNameTemplate.postName(when(), full().onPage(3), false));
        assertEquals(base + "_3_143005", FileNameTemplate.postName(when(), full().onPage(3), true));
        // No page, or one past any carousel, is no page.
        assertEquals(base, FileNameTemplate.postName(when(), full().onPage(0), false));
        assertEquals(base, FileNameTemplate.postName(when(), full().onPage(-1), false));
        assertEquals(base, FileNameTemplate.postName(when(), full().onPage(PostDetails.MAX_PAGE + 1), false));
        assertEquals(base + "_" + PostDetails.MAX_PAGE,
                FileNameTemplate.postName(when(), full().onPage(PostDetails.MAX_PAGE), false));

        assertNull(FileNameTemplate.postName(when(), null, false));
        assertNull(FileNameTemplate.postName(when(), PostDetails.NONE, false));
        assertNull(FileNameTemplate.postName(when(), new PostDetails(ID, null, posted()).onPage(2), false));
        assertNull(FileNameTemplate.postName(when(), new PostDetails(ID, "Stevi Ous", null).onPage(2), true));
        assertNull(FileNameTemplate.postName(when(), new PostDetails(ID, " .. ", posted()), false));
        // The account comes cleaned the way a folder name is.
        assertEquals("Stevi_Ous_" + POSTED_STAMP,
                FileNameTemplate.postName(when(), new PostDetails(null, " Stevi/Ous. ", posted()), false));

        String emoji = new String(Character.toChars(0x1F3AC));
        PostDetails longest = new PostDetails(null, repeat(emoji, 80), posted()).onPage(12);
        String name = FileNameTemplate.postName(when(), longest, true);
        assertTrue(name, name.endsWith("_" + POSTED_STAMP + "_12_143005"));
        assertTrue(name, name.startsWith(emoji));
        assertTrue(name, name.getBytes(StandardCharsets.UTF_8).length <= FileNameTemplate.MAX_NAME_BYTES);
        assertTrue(name, name.getBytes(StandardCharsets.UTF_8).length > FileNameTemplate.MAX_NAME_BYTES - 4);
    }

    /** A page number rides along with the rest of what the save knows, and NONE stays NONE without one. */
    @Test
    public void aPageKeepsTheRestOfWhatTheSaveKnows() {
        PostDetails page = full().onPage(4);
        assertEquals(4, page.page);
        assertTrue(page.hasPage());
        assertEquals(ID, page.videoId);
        assertEquals("Stevi Ous", page.owner);
        assertEquals(posted(), page.posted);
        assertSame(page, page.onPage(4));
        assertFalse(full().hasPage());
        assertSame(PostDetails.NONE, PostDetails.NONE.onPage(0));
        assertEquals("PostDetails(id known, poster known, posted known, page 4)", page.toString());
        // A carousel page's Item keeps its number through the copy every save makes.
        assertEquals(4, new MediaSave.Item(false, null, null, page).details.page);
    }

    /**
     * A profile picture has an account and no post time, so a name by post is the account,
     * {@code _profile_} and the time of the save. Taken can only mean the same second, so it keeps
     * the name for MediaStore to number. With no account it gets none, like any other save.
     */
    @Test
    public void aProfilePictureIsNamedForTheAccountAndTheTimeOfTheSave() {
        PostDetails picture = PostDetails.profilePicture("Stevi Ous");
        assertTrue(picture.profile);
        assertEquals("Stevi Ous", picture.owner);
        assertFalse(picture.hasPosted());
        assertFalse(full().profile);
        assertEquals("Stevi Ous_profile_" + STAMP, FileNameTemplate.postName(when(), picture, false));
        assertEquals("Stevi Ous_profile_" + STAMP, FileNameTemplate.postName(when(), picture, true));
        assertEquals("Stevi_Ous_profile_" + STAMP,
                FileNameTemplate.postName(when(), PostDetails.profilePicture(" Stevi/Ous. "), false));
        assertSame(PostDetails.NONE, PostDetails.profilePicture(null));
        assertNull(FileNameTemplate.postName(when(), PostDetails.profilePicture(" .. "), false));
        assertEquals("PostDetails(id unknown, poster known, posted unknown, profile picture)", picture.toString());

        String emoji = new String(Character.toChars(0x1F3AC));
        String name = FileNameTemplate.postName(when(), PostDetails.profilePicture(repeat(emoji, 80)), false);
        assertTrue(name, name.endsWith("_profile_" + STAMP));
        assertTrue(name, name.startsWith(emoji));
        assertTrue(name, name.getBytes(StandardCharsets.UTF_8).length <= FileNameTemplate.MAX_NAME_BYTES);
    }

    /** Through to the gallery: on, the account's name; off, the IG_IMG_ name every photo gets. */
    @Test
    @Config(sdk = 37)
    public void aSavedProfilePictureCarriesTheAccountWithNamesByPostOn() throws Exception {
        FolderGallery gallery = folderGallery();
        writableBoth(1, 2);
        Settings.SAVE_NAME_BY_POST.save(true);
        savePhoto(new MediaStoreWriter(context, false, PostDetails.profilePicture("Stevi Ous")));
        Settings.SAVE_NAME_BY_POST.save(false);
        savePhoto(new MediaStoreWriter(context, false, PostDetails.profilePicture("Stevi Ous")));

        assertTrue(nameOf(gallery.rows.get(1L)), nameOf(gallery.rows.get(1L)).matches("Stevi Ous_profile_\\d{8}_\\d{6}\\.jpg"));
        assertTrue(nameOf(gallery.rows.get(2L)), nameOf(gallery.rows.get(2L)).matches("IG_IMG_\\d{8}_\\d{6}\\.jpg"));
        String report = LogBufferManager.buildExportText();
        assertFalse(report, report.contains("Stevi"));
    }

    private void writableBoth(long... ids) {
        for (long id : ids) {
            writable(null, VIDEOS, id);
            writable(null, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        }
    }

    /** A photo save through to the gallery's publish, as a finished download makes it. */
    private static void savePhoto(MediaStoreWriter writer) throws IOException {
        writer.open("image/jpeg").write(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
        writer.commit();
    }

    private static final String UNNAMED_BY_POST =
            "saves are named by account and post time, and this save doesn't know the account, so it keeps its usual name";

    /**
     * Today's MediaStore. With Name saves by account and post time on, a photo and a video are
     * both named for the account and the post's time, whatever the video template says, and the
     * same photo saved again takes the time of the save rather than a number. A save that doesn't
     * know the account keeps its usual name and the report says why, naming nobody. Off, every
     * save is named the way it always was.
     */
    @Test
    @Config(sdk = 37)
    public void namesByPostCoverPhotosAndVideosAndNeverCollide() throws Exception {
        assertFalse("Name saves by account and post time starts off", Settings.SAVE_NAME_BY_POST.get());
        Settings.SAVE_NAME_BY_POST.save(true);
        Settings.FILENAME_TEMPLATE.save("Reel {video_id}");
        FolderGallery gallery = folderGallery();
        writableBoth(1, 2, 3, 4, 5, 6);
        save(new MediaStoreWriter(context, true, full()));
        savePhoto(new MediaStoreWriter(context, false, full()));
        savePhoto(new MediaStoreWriter(context, false, full()));
        savePhoto(new MediaStoreWriter(context, false, new PostDetails(ID, null, posted())));
        Settings.SAVE_NAME_BY_POST.save(false);
        savePhoto(new MediaStoreWriter(context, false, full()));
        save(new MediaStoreWriter(context, true, full()));

        String base = "Stevi Ous_" + POSTED_STAMP;
        assertEquals(base + ".mp4", nameOf(gallery.rows.get(1L)));
        assertEquals(base + ".jpg", nameOf(gallery.rows.get(2L)));
        String again = nameOf(gallery.rows.get(3L));
        assertTrue(again, again.matches(Pattern.quote(base) + "_\\d{6}\\.jpg"));
        assertTrue(nameOf(gallery.rows.get(4L)), nameOf(gallery.rows.get(4L)).matches("IG_IMG_\\d{8}_\\d{6}\\.jpg"));
        // Off, and likely in row 4's second: today's IG_IMG_ name, which MediaStore numbers as it always has.
        assertTrue(nameOf(gallery.rows.get(5L)), nameOf(gallery.rows.get(5L)).matches("IG_IMG_\\d{8}_\\d{6}( \\(1\\))?\\.jpg"));
        assertEquals("Reel " + ID + ".mp4", nameOf(gallery.rows.get(6L)));

        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, count(report, TIME_WENT_ON));
        assertEquals(report, 1, count(report, UNNAMED_BY_POST));
        assertFalse(report, report.contains("Stevi"));
        assertFalse(report, report.contains(ID));
    }

    /**
     * Android 9 writes the file into the folder itself, and a name by post works the same there:
     * the same carousel page saved twice keeps both files, the second with the time of the save,
     * and a video of the post goes under Movies with the same kind of name.
     */
    @Test
    @Config(sdk = 28)
    public void onAndroid9NamesByPostNeverCollide() throws Exception {
        assertTrue(MediaStoreWriter.legacyStorage());
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        Settings.SAVE_NAME_BY_POST.save(true);
        File pictures = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Instagram");
        File movies = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Instagram");
        for (File folder : new File[] {pictures, movies}) {
            File[] old = folder.listFiles();
            if (old != null) for (File file : old) assertTrue(file.delete());
        }

        savePhoto(new MediaStoreWriter(context, false, full().onPage(2)));
        savePhoto(new MediaStoreWriter(context, false, full().onPage(2)));
        save(new MediaStoreWriter(context, true, full()));

        String base = "Stevi Ous_" + POSTED_STAMP;
        List<String> photos = new ArrayList<>(Arrays.asList(pictures.list()));
        Collections.sort(photos);
        assertEquals(photos.toString(), 2, photos.size());
        assertEquals(base + "_2.jpg", photos.get(0));
        assertTrue(photos.get(1), photos.get(1).matches(Pattern.quote(base) + "_2_\\d{6}\\.jpg"));
        assertEquals(Collections.singletonList(base + ".mp4"), Arrays.asList(movies.list()));
    }

    // ---- Where the id comes from ----------------------------------------------------------------

    /**
     * Instagram writes a media id as {@code <pk>_<owner's id>}. The pk is the post's own number, so
     * that's the one the name gets; an id of any other shape is kept as it came and the name leaves
     * it out unless it's a number. Nothing known at all is no details.
     */
    @Test
    public void aMediaIdGivesThePostsOwnNumber() {
        PostDetails details = PostDetails.of("3456789012345678901_1234567", "Stevi Ous", posted());
        assertEquals("3456789012345678901", details.videoId);
        assertEquals("Stevi Ous", details.owner);
        assertEquals(posted(), details.posted);
        assertTrue(details.hasVideoId() && details.hasOwner() && details.hasPosted());

        assertEquals(ID, PostDetails.of(ID, null, null).videoId);
        // Not <number>_<number>: kept whole, and not a number, so not an id the name uses.
        for (String odd : new String[]{"_" + ID, ID + "_", ID + "_x", "x_" + ID, ID + "_1_2", "12a4"}) {
            PostDetails kept = PostDetails.of(odd, null, null);
            assertEquals(odd, odd, kept.videoId);
            assertFalse(odd, kept.hasVideoId());
        }
        assertSame(PostDetails.NONE, PostDetails.of(null, null, null));
        assertFalse(PostDetails.of(null, " .. ", null).hasOwner());
        assertEquals(DAY, FileNameTemplate.videoName("{posted}", when(), PostDetails.of(null, null, posted())));
        assertEquals("3456789012345678901_Stevi Ous", FileNameTemplate.videoName("{video_id}_{owner}", when(),
                PostDetails.of("3456789012345678901_1234567", "Stevi Ous", null)));
        // The details say nothing of the post in a report.
        assertFalse(details.toString(), details.toString().contains("Stevi") || details.toString().contains("345678"));
    }

    /**
     * Each entry point hands the save the details its caller gave, the DASH route too, and a
     * caller that gave none hands over no details.
     */
    @Test
    public void everyEntryPointHandsTheSaveWhatItKnowsOfThePost() throws Exception {
        List<PostDetails> handed = new ArrayList<>();
        MediaSave.detailsForTests = details -> {
            synchronized (handed) {
                handed.add(details);
            }
        };
        // Every Meta name answers a private address, so each save stops before it connects.
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
        String clip = "https://scontent-lga3-1.cdninstagram.com/o1/v/t16/f2/m86/clip_720p.mp4?oh=1&oe=2";
        String photo = "https://scontent-lga3-1.cdninstagram.com/v/t51.2885-15/photo_1080x1350.jpg?oh=1&oe=2";
        String manifest = "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">"
                + "<Representation codecs=\"avc1.64001f\" width=\"720\" height=\"1280\" bandwidth=\"900000\">"
                + "<BaseURL>" + clip.replace("&", "&amp;") + "</BaseURL></Representation>"
                + "</AdaptationSet></Period></MPD>";

        assertTrue(MediaSave.saveVideo(context, SavesForTests.renditions(clip), null,
                PostDetails.of("2233445566778899_101", "Reel Maker", posted())));
        waitForSaves();
        assertTrue(MediaSave.saveItem(context, SavesForTests.renditions(clip), manifest,
                PostDetails.of("3344556677889900", "Story Teller", posted())));
        waitForSaves();
        assertTrue(MediaSave.savePhoto(context, SavesForTests.renditions(photo),
                PostDetails.of("4455667788990011_202", "Photo Taker", null)));
        waitForSaves();
        assertTrue(MediaSave.saveVideo(context, SavesForTests.renditions(clip), manifest, null));
        waitForSaves();

        List<String> ids = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        List<Date> days = new ArrayList<>();
        for (PostDetails details : handed) {
            ids.add(details.videoId);
            owners.add(details.owner);
            days.add(details.posted);
        }
        assertEquals(Arrays.asList("2233445566778899", "3344556677889900", "4455667788990011", null), ids);
        assertEquals(Arrays.asList("Reel Maker", "Story Teller", "Photo Taker", null), owners);
        assertEquals(Arrays.asList(posted(), posted(), null, null), days);
        assertSame(PostDetails.NONE, handed.get(3));
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaSave.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /** Text made of these code points, so no character here hides in the source. */
    private static String u(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static String repeat(String text, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(text);
        return out.toString();
    }
}

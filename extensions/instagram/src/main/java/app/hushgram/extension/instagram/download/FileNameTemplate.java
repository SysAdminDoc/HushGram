/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;

/**
 * The name a saved video gets, from a template the person can change.
 *
 * <p>The default is {@code IG_VID_} and the date and time, Hushfacebook's {@code FB_VID_} naming
 * with Instagram's initials, so with the template as it ships every video is named the same way.
 * Four tokens fill in per save: {@link #DATE} is the moment the file goes into the gallery, as
 * {@code yyyyMMdd_HHmmss}; {@link #VIDEO_ID} is the media's number on Instagram; {@link #OWNER} is
 * the name of whoever posted it, cleaned the way the folder name is; and {@link #POSTED} is the day
 * the post went up, as {@code yyyyMMdd} in the phone's time zone. The last three fill in when the save knows them
 * ({@link PostDetails}) and as nothing when it doesn't. Anything else in braces stays as written.
 *
 * <p>A template is typed by a person or read from a settings file, and MediaStore takes the name
 * as given. So {@link #sanitize} cleans it the way {@link SaveFolder} cleans a folder name: no
 * separator can make a path, nothing invisible stays, no dot or space starts or ends it, and it's
 * cut at {@link #MAX_TEMPLATE_CODE_POINTS}. Then it holds the template to the gallery's own naming:
 *
 * <ul>
 *   <li>No leading dot, so MediaStore's hidden and reserved names ({@code .pending-},
 *       {@code .trashed-}, {@code .nomedia}) are out of reach.</li>
 *   <li>The extension is the writer's to give, from the bytes it checked, so a media extension typed
 *       at the end ({@code .mp4}, {@code .jpg}) goes.</li>
 *   <li>{@link #PHOTO_PREFIX} is how a saved photo is named, so a video template that starts with it
 *       starts with {@code IG_VID_} instead.</li>
 *   <li>MediaStore numbers a name that's taken, {@code (1)} to {@code (31)}, and then refuses the
 *       save. A template with no token at all names every video the same, so it gets {@code _{date}}
 *       on the end.</li>
 * </ul>
 *
 * <p>The filled-in name is cleaned again and cut at {@link #MAX_NAME_BYTES} of UTF-8, which leaves
 * room for the extension and the number MediaStore adds, inside the 255 bytes a name can have. The
 * poster's name is cut first, to what the rest of the name leaves room for, so a token at the end
 * of the template is never what gets cut. A name keeps saves apart when it has the date and time,
 * a video id the save knows, or a poster and a post day the save knows. When a template counts on
 * something this save doesn't know, the date and time go on the end, so two such saves still get
 * two names. A name made of what the post is comes out the same for the same post, and for every
 * video one person posted on one day under {@code {owner}_{posted}}: when it's already in the save
 * folder, the writer takes {@link #takenVideoName} instead, the same name with the time of the save
 * on the end, before MediaStore's numbering can run out.
 *
 * <p>Photos keep their {@code IG_IMG_} names. A photo has no video id, and one template for both
 * would name every photo a video.
 *
 * <p>Name saves by account and post time ({@link #byPost}, #20) sets all of that aside for a save
 * that knows who posted and when: {@link #postName} names a photo and a video alike for the account
 * and the post's time, with a carousel page's number on the end. A profile picture has no post
 * time, so it's named for the account, {@link #PROFILE} and the time of the save.
 */
public final class FileNameTemplate {

    private FileNameTemplate() {}

    /** Fills in as the date and time of the save. */
    public static final String DATE = "{date}";

    /** Fills in as the media's number on Instagram, or nothing when the save doesn't know it. */
    public static final String VIDEO_ID = "{video_id}";

    /** Fills in as the name of whoever posted the video, or nothing when the save doesn't know it. */
    public static final String OWNER = "{owner}";

    /** Fills in as the day the post went up, {@code yyyyMMdd} in the phone's time zone, or nothing when unknown. */
    public static final String POSTED = "{posted}";

    /** Every token, in the order the dialog names them. */
    static final String[] TOKENS = {DATE, VIDEO_ID, OWNER, POSTED};

    /** What stands between the account and the time of the save in a profile picture's name by post. */
    static final String PROFILE = "_profile_";

    /** What the name of a saved photo starts with. Photos keep it, whatever the template. */
    public static final String PHOTO_PREFIX = "IG_IMG_";

    /** What the name of a saved video starts with, as the template ships. */
    static final String VIDEO_PREFIX = "IG_VID_";

    /** The name a saved video gets while nobody has changed the template. */
    public static final String DEFAULT = VIDEO_PREFIX + DATE;

    /** The longest template kept, in code points: the folder name's bound. */
    static final int MAX_TEMPLATE_CODE_POINTS = SaveFolder.MAX_CODE_POINTS;

    /** The longest poster's name kept, in code points: the folder name's bound again. */
    static final int MAX_OWNER_CODE_POINTS = SaveFolder.MAX_CODE_POINTS;

    /** The longest filled-in name, in bytes of UTF-8, before the extension. */
    static final int MAX_NAME_BYTES = 200;

    /** A video id, as long as one gets. Longer is not an id. */
    private static final int MAX_ID_DIGITS = 25;

    /**
     * The extensions {@link MediaStoreWriter} gives a file, and the one other spelling of JPEG. A
     * template can't end with one: the writer adds the one the bytes call for.
     */
    private static final String[] MEDIA_EXTENSIONS = {
        ".mp4", ".m4v", ".mov", ".webm", ".3gp", ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".avif", ".gif",
    };

    /** The template the next save uses. Never throws, and never answers anything but a clean one. */
    public static String current() {
        try {
            if (!Utils.settingsReady()) return DEFAULT;
            return sanitize(Settings.FILENAME_TEMPLATE.get());
        } catch (Throwable t) {
            return DEFAULT;
        }
    }

    /**
     * The template to use for [raw]: cleaned like a folder name and held to the gallery's naming
     * (see the class), and {@link #DEFAULT} when nothing's left. Running it on its own answer
     * changes nothing.
     */
    public static String sanitize(String raw) {
        String clean = asVideo(withoutMediaExtension(SaveFolder.clean(raw, MAX_TEMPLATE_CODE_POINTS)));
        if (clean.isEmpty()) return DEFAULT;

        if (!usesAnyToken(clean)) {
            // Every video would get this one name. The date keeps them apart, and it has to fit.
            clean = SaveFolder.trim(firstCodePoints(clean, MAX_TEMPLATE_CODE_POINTS - 1 - DATE.length()));
            clean = joined(clean, DATE);
        }

        return clean;
    }

    /** Whether [template] is already a template this would use exactly as written. */
    public static boolean isClean(String template) {
        return template != null && !template.isEmpty() && template.equals(sanitize(template));
    }

    /**
     * Whether a settings file's [template] may be taken, the way {@link SaveFolder#isImportable}
     * decides for a folder: a character this phone doesn't know yet counts as a plain symbol, and
     * the rest has to be exactly as {@link #sanitize} leaves it.
     */
    public static boolean isImportable(String template) {
        if (template == null || template.isEmpty()) return false;
        return isClean(SaveFolder.withUnknownAsKnown(template));
    }

    /**
     * Whether Name saves by account and post time is on. Off while the settings aren't ready, and
     * never throws.
     */
    public static boolean byPost() {
        try {
            return Utils.settingsReady() && Settings.SAVE_NAME_BY_POST.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The name, without its extension, that Name saves by account and post time gives a save of
     * [details]'s media: the account that posted it, when it was posted as {@code yyyyMMdd_HHmmss}
     * in the phone's time zone, and a carousel page's number. A photo and a video get the same
     * kind of name, so a folder of one account's saves sorts by when they were posted. Null when
     * the save doesn't know the account or the time: it keeps the name it would have had.
     *
     * <p>When [taken], that name is already in the save folder, from an earlier save of the same
     * post, and the time of the save, {@code HHmmss}, goes on the end, as {@link #takenVideoName}
     * does. Only a save of the same post in the same second can take that one, and MediaStore's
     * numbering covers it. The account's name is what gets cut to fit, never the time.
     *
     * <p>A profile picture ({@link PostDetails#profile}) is {@code <account>_profile_} and the time
     * of the save, {@code yyyyMMdd_HHmmss}, null only when the account isn't known. That time is
     * already on it, so [taken] can only mean the same second, and it keeps the same name for
     * MediaStore to number.
     */
    public static String postName(Date when, PostDetails details, boolean taken) {
        if (details != null && details.profile) {
            if (!details.hasOwner() || when == null) return null;
            String rest = PROFILE + stamp(when);
            return cut(details.owner, MAX_NAME_BYTES - rest.length()) + rest;
        }
        if (details == null || !details.hasOwner() || !details.hasPosted()) return null;
        String rest = "_" + stamp(details.posted) + (details.hasPage() ? "_" + details.page : "")
            + (taken ? "_" + clock(when) : "");
        return cut(details.owner, MAX_NAME_BYTES - rest.length()) + rest;
    }

    /** Whether [template] asks for the video id. */
    public static boolean usesVideoId(String template) {
        return template != null && template.contains(VIDEO_ID);
    }

    /** Whether [template] fills in the date and time. */
    public static boolean usesDate(String template) {
        return template != null && template.contains(DATE);
    }

    /** Whether [template] asks for the poster's name. */
    public static boolean usesOwner(String template) {
        return template != null && template.contains(OWNER);
    }

    /** Whether [template] asks for the day the post went up. */
    public static boolean usesPosted(String template) {
        return template != null && template.contains(POSTED);
    }

    /** Whether [template] has any token at all. Without one it names every video the same. */
    static boolean usesAnyToken(String template) {
        for (String token : TOKENS) {
            if (template != null && template.contains(token)) return true;
        }
        return false;
    }

    /**
     * Whether a name from the clean [template] tells one save from the next: the date and time
     * are in it, or a video id it has, or the poster and the post day it has, each of those only
     * when the template asks for it. Not when the template counts on something the save doesn't
     * know: then {@link #videoName} puts the date and time on the end.
     */
    static boolean keepsApart(String template, boolean hasId, boolean hasOwner, boolean hasPosted) {
        if (usesDate(template)) return true;
        if (usesVideoId(template) && hasId) return true;
        boolean owner = usesOwner(template);
        boolean posted = usesPosted(template);
        if (!owner && !posted) return false;
        return (!owner || hasOwner) && (!posted || hasPosted);
    }

    /**
     * The name, without its extension, of a video saved at [when] under [template], knowing only
     * the video's id [videoId]: what every route knew before the poster was read.
     */
    public static String videoName(String template, Date when, String videoId) {
        return videoName(template, when, PostDetails.of(videoId));
    }

    /**
     * The name, without its extension, of a video saved at [when] under [template], with what the
     * save knows of the post in [details]. The id is used only when it's digits, so nothing from
     * the app can reach the name but a number, and the poster's name comes cleaned and bounded. A
     * name that fills in to nothing, or to separators alone, is the default's, and one that comes out the same for
     * every save, because something it counts on is missing, gets the date and time on the end.
     */
    public static String videoName(String template, Date when, PostDetails details) {
        return videoName(template, when, details, false);
    }

    /**
     * The name, without its extension, for a video saved at [when] under [template] whose
     * {@link #videoName} is already in the save folder: that name with the time of the save,
     * {@code HHmmss}, on the end. MediaStore would number the repeat instead, (1) to (31), and then
     * refuse the save, and a name made of what the post is, like {@code {owner}_{posted}} for a
     * poster who posts a lot, gets there. Null when the name already carries the date and time of
     * the save: only a save in the same second can have taken it, and MediaStore's numbering covers
     * that. The poster's name is what gets shorter to make room, as it is for the first name.
     */
    public static String takenVideoName(String template, Date when, PostDetails details) {
        return videoName(template, when, details, true);
    }

    /** {@link #videoName} or, when [taken], {@link #takenVideoName}. */
    private static String videoName(String template, Date when, PostDetails details, boolean taken) {
        if (details == null) details = PostDetails.NONE;
        String stamp = stamp(when);
        String clean = sanitize(template);
        boolean hasId = details.hasVideoId();
        String rest = clean.replace(DATE, stamp)
            .replace(VIDEO_ID, hasId ? details.videoId : "")
            .replace(POSTED, details.hasPosted() ? postedStamp(details.posted) : "");
        String owner = ownerFor(rest, details, MAX_NAME_BYTES);
        String name = filledIn(rest, owner);
        // Nothing left but the separators typed between tokens the save didn't know ({owner}_{posted}
        // with neither) is nothing.
        if (!hasLetterOrDigit(name)) return taken ? null : VIDEO_PREFIX + stamp;

        if (!keepsApart(clean, hasId, !owner.isEmpty(), details.hasPosted())) {
            // Something this template counts on is missing, so the date and time keep the name apart.
            return taken ? null : joined(cut(name, MAX_NAME_BYTES - 1 - stamp.length()), stamp);
        }
        if (!taken) return cut(name, MAX_NAME_BYTES);
        if (usesDate(clean)) return null;

        // Already in the folder: the time of the save goes on the end, and the poster's name makes room.
        String time = clock(when);
        int room = MAX_NAME_BYTES - 1 - time.length();
        return joined(cut(filledIn(rest, ownerFor(rest, details, room)), room), time);
    }

    /** [rest] with [owner] where {@link #OWNER} stands, cleaned the way the whole name is. */
    private static String filledIn(String rest, String owner) {
        return asVideo(SaveFolder.clean(rest.replace(OWNER, owner), Integer.MAX_VALUE));
    }

    /** The date and time of a save, in Western digits and the Gregorian calendar whatever the locale. */
    static String stamp(Date when) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(when);
    }

    /** The time of a save, {@code HHmmss}, for a name already in the folder. Western digits the same way. */
    static String clock(Date when) {
        return new SimpleDateFormat("HHmmss", Locale.US).format(when);
    }

    /** The day a post went up, in the phone's time zone, the same way. */
    static String postedStamp(Date posted) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(posted);
    }

    /**
     * The poster's name as [rest], the name with every other token filled in and {@link #OWNER}
     * still in it, leaves room for: the bytes up to [budget] that the rest doesn't take, shared
     * between the places the token stands. So the poster's name is what gets cut, never a date at
     * the end of the template.
     */
    private static String ownerFor(String rest, PostDetails details, int budget) {
        if (!details.hasOwner() || !rest.contains(OWNER)) return "";
        int places = 0;
        for (int at = rest.indexOf(OWNER); at >= 0; at = rest.indexOf(OWNER, at + OWNER.length())) places++;
        int room = budget - (rest.getBytes(StandardCharsets.UTF_8).length - places * OWNER.length());
        return cut(details.owner, Math.max(0, room / places));
    }

    /** Whether [text] says anything at all: a letter or a digit, not only separators. */
    private static boolean hasLetterOrDigit(String text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    /** [name] with {@link #VIDEO_PREFIX} where it starts with {@link #PHOTO_PREFIX}, in any case. */
    private static String asVideo(String name) {
        if (!name.regionMatches(true, 0, PHOTO_PREFIX, 0, PHOTO_PREFIX.length())) return name;
        return VIDEO_PREFIX + name.substring(PHOTO_PREFIX.length());
    }

    /** [head] and [tail] with an underscore between them, unless [head] already ends in one or a hyphen. */
    private static String joined(String head, String tail) {
        if (head.isEmpty() || head.endsWith("_") || head.endsWith("-")) return head + tail;
        return head + "_" + tail;
    }

    /** Whether [text] is a video id: digits, and not too many of them. */
    static boolean isVideoId(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_ID_DIGITS) return false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        }
        return true;
    }

    /** [name] without a media extension at its end, however many there are, and trimmed after each. */
    private static String withoutMediaExtension(String name) {
        boolean again = true;
        while (again) {
            again = false;
            for (String extension : MEDIA_EXTENSIONS) {
                int at = name.length() - extension.length();
                if (at >= 0 && name.regionMatches(true, at, extension, 0, extension.length())) {
                    name = SaveFolder.trim(name.substring(0, at));
                    again = true;
                    break;
                }
            }
        }
        return name;
    }

    /** The first [count] code points of [text], never half of a pair. */
    private static String firstCodePoints(String text, int count) {
        if (text.codePointCount(0, text.length()) <= count) return text;
        return text.substring(0, text.offsetByCodePoints(0, count));
    }

    /** [name] cut to [maxBytes] of UTF-8, never inside a character, with no space or dot left at its end. */
    private static String cut(String name, int maxBytes) {
        int bytes = 0;
        int end = 0;
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            int size = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + size > maxBytes) break;
            bytes += size;
            i += Character.charCount(codePoint);
            end = i;
        }
        return end == name.length() ? name : SaveFolder.trim(name.substring(0, end));
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.misc.Fonts;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Story text fonts" patch: font files picked in HushGram settings show up in the
 * story text editor's row of fonts, after Instagram's free fonts, which all stay (#275).
 *
 * <p>Instagram already draws story text in a font file for its own import route (a format named
 * IMPORT_FONT that carries the file's path). The patch fills {@link #importedFont} with the same
 * steps Instagram's import takes, and hands the editor's list of formats to {@link #formats} each
 * time it's built, which inserts one such format per picked file before Instagram's paid fonts.
 * The editor treats every position from its first paid font onward as paid, so appending after
 * that section wrongly opens a Plus offer for a local file (#325). Picking one in the editor draws
 * the text and the finished sticker in that file, the way Instagram's own import does. The editor
 * labels it with the file's name, since the import's own label is Instagram's default font's.
 *
 * <p>A sticker keeps its own format, and a saved draft keeps it too, so its text stays in its font.
 * Opening one to edit it again finds that format's row in the editor by name, though, and every
 * picked font is named IMPORT_FONT, so it would land on the first of them and Done would keep that
 * font. {@link #row} answers the picked font's own row first. A sticker whose file was taken out has
 * no row, and the editor opens it on its first row, the way it does for Instagram's own import.
 *
 * <p>Every hook fails open: with the switch off, HushGram paused, the settings not read yet, no
 * fonts picked or anything thrown, Instagram's own list and formats go through.
 */
public final class StoryFonts {
    /** The folder in Instagram's own files where picked story fonts are kept. */
    static final String DIRECTORY = "hushgram-story-fonts";

    /** Instagram's name for a format that draws its text in a font file. */
    static final String IMPORT_FONT = "IMPORT_FONT";

    /** What's counted for each editor list that got the picked fonts, and each row found for one. */
    static final String ADDED = "picked fonts added to the story text editor";
    static final String FOUND = "picked font's own row found";

    /** Where the hooks are counted when one throws. */
    static final String LIST = "story font list";
    static final String ROW = "story font row";

    /** The longest name a kept file gets, before any " (2)". */
    static final int LONGEST_NAME = 80;

    /** Makes a format for a font file's path, as {@link #importedFont} does. Tests stand in. */
    static volatile Function<String, Object> formatFor = path -> importedFont(path, label(path));

    /** Instagram's classification of one native format: 0 free, 1 paid, -1 unknown. Tests stand in. */
    static volatile ToIntFunction<Object> premiumOf = StoryFonts::nativePremium;

    /** The formats made this process, by path, so the editor sees the same one each time. */
    private static final Map<String, Object> FORMATS = new ConcurrentHashMap<>();

    /** The kept files' paths, sorted by name, or null until listed again. */
    @Nullable
    private static volatile List<String> paths;

    private StoryFonts() {
    }

    /**
     * Injected before the story text editor answers its list of formats. Adds a format for each
     * picked font file before the first native paid font, skipping one already there. Instagram's
     * formats keep their order and their subscription checks. An unreadable boundary adds nothing.
     * Never throws.
     */
    @SuppressWarnings("unchecked")
    public static void formats(List<?> list) {
        try {
            HookStatus.invoked(FamilyNames.STORY_FONTS);
            if (list == null || !on()) return;
            Context context = Utils.getContext();
            if (context == null) return;
            int insert = list.size();
            for (int index = 0; index < list.size(); index++) {
                int premium = premiumOf.applyAsInt(list.get(index));
                if (premium == 1) {
                    insert = index;
                    break;
                }
                if (premium != 0) return;
            }
            boolean added = false;
            for (String path : paths(context)) {
                Object format = format(path);
                if (format == null || list.contains(format)) continue;
                ((List<Object>) list).add(insert++, format);
                added = true;
            }
            if (added) HookStatus.counted(FamilyNames.STORY_FONTS, ADDED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_FONTS, LIST, failure);
        }
    }

    /**
     * Injected first where the story text editor finds a format's row, which it does by name. Answers
     * the row of the picked font equal to [format] in the editor's [list], or -1, and Instagram looks
     * it up as before. Never throws.
     */
    public static int row(Object format, List<?> list) {
        try {
            HookStatus.invoked(FamilyNames.STORY_FONTS);
            if (format == null || list == null || !on()) return -1;
            for (int index = 0; index < list.size(); index++) {
                Object entry = list.get(index);
                if (ours(entry) && entry.equals(format)) {
                    HookStatus.counted(FamilyNames.STORY_FONTS, FOUND);
                    return index;
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_FONTS, ROW, failure);
        }
        return -1;
    }

    /**
     * A format that draws text in the font file at [path], made the way Instagram's own import makes
     * one and named [label] in the editor, or null when it can't be told. The patch writes the body.
     * Null as built, so nothing is added.
     */
    public static Object importedFont(Object path, Object label) {
        return null;
    }

    /**
     * Instagram's own paid-font predicate applied to this format's native name. The patch writes
     * the body. Unknown as built, so the list stays native until its boundary can be read.
     */
    public static int nativePremium(Object format) {
        return -1;
    }

    /** What the editor calls the font kept at [path]: its file's name without the extension. */
    static String label(String path) {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @Nullable
    private static Object format(String path) {
        Object known = FORMATS.get(path);
        if (known != null) return known;
        Object made = formatFor.apply(path);
        if (made == null) return null;
        Object raced = FORMATS.putIfAbsent(path, made);
        return raced != null ? raced : made;
    }

    /** Whether [format] is one this class made, by identity: Instagram's formats compare by content. */
    private static boolean ours(Object format) {
        for (Object made : FORMATS.values()) {
            if (made == format) return true;
        }
        return false;
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.STORY_FONTS.get();
    }

    /** The kept files' paths, listed once until a font is added or taken out. */
    static List<String> paths(Context context) {
        if (!Fonts.readyForRead(directory(context))) return Collections.emptyList();
        List<String> known = paths;
        if (known != null) return known;
        List<String> listed = new ArrayList<>();
        for (File file : files(context)) listed.add(file.getAbsolutePath());
        known = Collections.unmodifiableList(listed);
        paths = known;
        return known;
    }

    /** The kept font files, sorted by name, leaving out a copy still being made. */
    static List<File> files(Context context) {
        File directory = directory(context);
        if (!Fonts.readyForRead(directory)) return Collections.emptyList();
        File[] all = directory.listFiles();
        if (all == null) return Collections.emptyList();
        List<File> kept = new ArrayList<>();
        for (File file : all) {
            if (file.isFile() && !file.getName().endsWith(".tmp")) kept.add(file);
        }
        Collections.sort(kept, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return kept;
    }

    /** The names of the kept fonts, as the settings list shows them. */
    public static List<String> names(Context context) {
        List<String> names = new ArrayList<>();
        for (File file : files(context)) names.add(file.getName());
        return names;
    }

    static File directory(Context context) {
        return new File(context.getFilesDir(), DIRECTORY);
    }

    /**
     * Copies a picked file into Instagram's storage under a name made from [shown], the file's name
     * in the picker, if it's a font that loads. Answers the name it's kept under, or null for a file
     * that isn't a font HushGram can use, keeping nothing. Off the main thread.
     */
    @Nullable
    public static synchronized String add(Context context, InputStream input, @Nullable String shown) {
        File directory = directory(context);
        String name = freeName(directory, cleanName(shown));
        if (!Fonts.keep(input, new File(directory, name))) return null;
        paths = null;
        return name;
    }

    /** A private stage, still absent from the story editor and the saved-font list. */
    @Nullable
    public static Fonts.Prepared prepare(Context context, InputStream input) {
        return Fonts.prepare(input, directory(context));
    }

    /** Keep this class locked until the prepared installation is accepted or rolled back. */
    @Nullable
    public static String prepareInstall(Context context, Fonts.Prepared prepared, @Nullable String shown) {
        File directory = directory(context);
        String name = freeName(directory, cleanName(shown));
        return prepared.prepareTarget(new File(directory, name)) ? name : null;
    }

    public static void installed() { paths = null; }

    /** Takes out the kept font called [name]. Answers whether it's gone. */
    public static boolean remove(Context context, String name) {
        File file = new File(directory(context), name);
        if (!file.getParentFile().equals(directory(context)) || name.endsWith(".tmp")) return false;
        String path = file.getAbsolutePath();
        boolean gone = !file.exists() || file.delete();
        if (gone) {
            FORMATS.remove(path);
        } else {
            Logger.printInfo(() -> "Story fonts: couldn't remove " + name);
        }
        paths = null;
        return gone;
    }

    /** [shown] as a file name: no folders or control characters, a sensible length, never empty. */
    static String cleanName(@Nullable String shown) {
        StringBuilder out = new StringBuilder();
        if (shown != null) {
            for (int i = 0; i < shown.length(); i++) {
                char c = shown.charAt(i);
                out.append(c == '/' || c == '\\' || c == ':' || Character.isISOControl(c) ? '_' : c);
            }
        }
        String name = out.toString().trim();
        while (name.startsWith(".")) name = name.substring(1).trim();
        if (name.length() > LONGEST_NAME) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 6 ? name.substring(dot) : "";
            name = name.substring(0, LONGEST_NAME - extension.length()).trim() + extension;
        }
        if (name.isEmpty() || name.endsWith(".tmp")) name = "font";
        return name;
    }

    /** [name] in [directory], or with " (2)", " (3)" and on before its extension when that's taken. */
    static String freeName(File directory, String name) {
        if (!new File(directory, name).exists()) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            String next = String.format(Locale.ROOT, "%s (%d)%s", stem, n, extension);
            if (!new File(directory, next).exists()) return next;
        }
    }

    /** Takes back what this process made and listed, for a test. */
    static void resetForTests() {
        FORMATS.clear();
        paths = null;
        formatFor = path -> importedFont(path, label(path));
        premiumOf = StoryFonts::nativePremium;
    }
}

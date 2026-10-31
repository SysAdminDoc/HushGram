/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Change the font" patch: Instagram's text draws in the phone's font or a font
 * file picked in HushGram settings instead of Instagram's own (#166, #5).
 *
 * <p>Instagram's newer design draws its text in Prism Sans, which its font helper loads once per
 * weight (regular, semibold and bold) and keeps in static fields. The patch hands each Typeface
 * the helper loads to {@link #prismTypeface} before it's kept, and every IgTextView to
 * {@link #textView} once Instagram has set it up. Each answers the chosen font at the same weight
 * and slant as the one it replaces, so bold stays bold and italic stays italic. Letters the chosen
 * font lacks still draw from the phone's fonts, emoji included.
 *
 * <p>The fonts are kept for the life of the process, so a change shows after a restart. Every hook
 * fails open: with Instagram's font chosen, HushGram paused, the settings not read yet, a font
 * file that won't load (one toast says so) or anything thrown, Instagram's own font goes through.
 */
public final class Fonts {
    /** The folder in Instagram's own files where the picked font is kept, and its name there. */
    static final String DIRECTORY = "hushgram-font";
    static final String FILE = "font";

    /** The largest font file kept: 64 MiB, room for a font covering Chinese, Japanese and Korean. */
    static final long MOST_BYTES = 64L * 1024 * 1024;

    /** What's counted for each font swapped. */
    static final String PHONE = "Instagram's font swapped for the phone's";
    static final String PICKED = "Instagram's font swapped for the picked file";

    /** What's counted for each emoji picture drawn in the picked file, and each one named for it. */
    static final String EMOJI = "Instagram's emoji pictures drawn in the picked file";
    static final String EMOJI_NAMED = "Instagram's emoji pictures named for the picked file";
    static final String EMOJI_CHOSEN = "Instagram's emoji pictures drawn in the chosen emoji";

    /** The weight a TextView with no Typeface of its own draws in. */
    static final int REGULAR = 400;

    /** Loads a font file, or answers null when it isn't one. Tests put their own in. */
    interface Loader {
        @Nullable
        Typeface load(File file);
    }

    static volatile Loader loader = file -> new Typeface.Builder(file).build();

    /** The chosen font for this process, null for Instagram's own, or {@link #UNREAD} until read. */
    private static final Object UNREAD = new Object();
    private static volatile Object chosen = UNREAD;
    private static volatile boolean fileRestartPending;
    private static final class ImportProcess {
        static final String ID = UUID.randomUUID().toString();
    }
    private static long importSequence, importing;
    private static final List<Runnable> importWatchers = new ArrayList<>();
    private static final Map<File, Object> stagingLocks = new ConcurrentHashMap<>();
    private static final Set<File> recoveredDirectories = ConcurrentHashMap.newKeySet();
    private static final String TRANSACTION = ".font-transaction.tmp", PREVIOUS = ".font-previous.tmp";
    private static final String WRITING = ".font-transaction-writing.tmp";
    private static final int TRANSACTION_MAGIC = 0x48474631;

    /**
     * The picked file's size and time when it was read for this process, null before, without one, or
     * when the file has no color emoji (see {@link #drawsInColor}).
     */
    @Nullable
    private static volatile String fileMark;

    /** Color glyph tables Android draws: Google's bitmap emoji (CBDT) and layered color glyphs (COLR). */
    private static final int CBDT = 0x43424454;
    private static final int COLR = 0x434f4c52;
    private static final int COLLECTION = 0x74746366;

    /** The Typefaces this class answered, so a view already in the chosen font is left alone. */
    private static final Set<Typeface> OURS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private Fonts() {
    }

    /**
     * Injected before Instagram's font helper keeps each Prism Sans Typeface it loads. Answers the
     * Typeface to keep in place of [loaded], which may be null when Instagram's font loader isn't
     * up yet. Never throws.
     */
    public static Typeface prismTypeface(Typeface loaded) {
        try {
            HookStatus.invoked(FamilyNames.FONT);
            if (loaded == null || !Utils.settingsReady() || OURS.contains(loaded)) return loaded;
            Typeface base = chosen();
            if (base == null) return loaded;
            Typeface swapped = styled(base, loaded);
            HookStatus.counted(FamilyNames.FONT, counted());
            return swapped;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FONT, "Prism Sans", failure);
            return loaded;
        }
    }

    /**
     * Injected first in AndroidX's ResourcesCompat.getFont(Context, int), which Compose text (a
     * profile's bio on 451, #166) and Instagram's own font loader read Prism Sans through. Answers
     * the chosen font at the weight of the Prism Sans resource [id] names, or null for any other
     * font, with Instagram's font chosen, or before the settings are read, and the resource then
     * loads as before. Never throws.
     */
    @Nullable
    public static Typeface resourceFont(Context context, int id) {
        try {
            HookStatus.invoked(FamilyNames.FONT);
            if (context == null || !Utils.settingsReady()) return null;
            Typeface base = chosen();
            if (base == null) return null;
            int weight = prismWeight(context, id);
            if (weight <= 0) return null;
            Typeface swapped = Typeface.create(base, weight, false);
            OURS.add(swapped);
            HookStatus.counted(FamilyNames.FONT, counted());
            return swapped;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FONT, "font resource", failure);
            return null;
        }
    }

    /**
     * Injected after each call to Instagram's emoji lookup, with its answer [instagrams]: the Typeface
     * Instagram draws its own emoji pictures in, the row of emoji above the comment box (#166),
     * reactions and the rest it draws as images rather than text. Null there means the phone's emoji.
     * Answers the picked font file when it has color emoji, so an emoji font picked as the Font
     * reaches them as it reaches text, or [instagrams] otherwise. Instagram draws those pictures in
     * black, so a file without colors (a text font with a few emoji, or a black and white emoji font)
     * would leave them black, unseen on a dark screen. Emoji the file lacks still draw from the
     * phone's. Always the same Typeface, since Instagram compares them. An emoji pack or file
     * chosen under Emoji style ({@link EmojiFonts}) comes first. Never throws.
     */
    @Nullable
    public static Typeface emojiTypeface(@Nullable Typeface instagrams) {
        try {
            HookStatus.invoked(FamilyNames.FONT);
            if (!Utils.settingsReady()) return instagrams;
            Typeface emoji = EmojiFonts.active();
            if (emoji != null) {
                HookStatus.counted(FamilyNames.FONT, EMOJI_CHOSEN);
                return emoji;
            }
            if (Settings.FONT.get() != FontChoice.FILE) return instagrams;
            Typeface picked = chosen();
            if (picked == null || fileMark == null) return instagrams;
            HookStatus.counted(FamilyNames.FONT, EMOJI);
            return picked;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FONT, "emoji", failure);
            return instagrams;
        }
    }

    /**
     * Injected where Instagram names an emoji picture or a sheet of them, with the [name] it made:
     * {@code emoji:/<version>-api<sdk>//<emoji>} or {@code emoji-sprite-sheet:/<...>//<count>//<emoji,...>}.
     * Instagram keeps the pictures it draws, even across restarts, by the part of the name before the
     * first "//", which says nothing of the font. With a font file that has color emoji picked, that
     * part also carries the file's size and time, so emoji drawn before it was picked aren't shown in
     * its place and picking another file draws them again. The rest, which Instagram reads to draw,
     * stays. An emoji pack or file chosen under Emoji style marks it the same way, with its own
     * prefix. Answers [name] as it is with any other choice. Never throws.
     */
    public static String emojiName(String name) {
        try {
            HookStatus.invoked(FamilyNames.FONT);
            if (name == null || !Utils.settingsReady()) return name;
            String mark = EmojiFonts.mark();
            String prefix = "-hge";
            if (mark == null) {
                if (Settings.FONT.get() != FontChoice.FILE || chosen() == null) return name;
                mark = fileMark;
                prefix = "-hg";
            }
            if (mark == null) return name;
            HookStatus.counted(FamilyNames.FONT, EMOJI_NAMED);
            int end = name.indexOf("//");
            if (end < 0) return name + prefix + mark;
            return name.substring(0, end) + prefix + mark + name.substring(end);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FONT, "emoji name", failure);
            return name;
        }
    }

    /** Each font resource looked at, by id: its Prism Sans weight, or 0 for a font that isn't Prism Sans. */
    static final Map<Integer, Integer> WEIGHTS = new ConcurrentHashMap<>();

    /** The weight of the Prism Sans file or family [id] names, or 0 when it names another font. */
    static int prismWeight(Context context, int id) {
        Integer known = WEIGHTS.get(id);
        if (known != null) return known;
        String name;
        try {
            name = context.getResources().getResourceEntryName(id);
        } catch (Exception notFound) {
            name = null;
        }
        int weight = prismWeight(name);
        WEIGHTS.put(id, weight);
        return weight;
    }

    /**
     * The weight a font resource named [name] draws Prism Sans in: Instagram keeps its files as
     * IGUIBetav9_Regular, _SemiBold and _Bold, and the three as the family prism_sans. 0 for any other.
     */
    static int prismWeight(@Nullable String name) {
        if (name == null) return 0;
        if (name.equals(PRISM_FAMILY)) return REGULAR;
        if (!name.startsWith(PRISM_FILE)) return 0;
        switch (name.substring(PRISM_FILE.length()).toLowerCase(Locale.ROOT)) {
            case "thin": return 100;
            case "extralight": return 200;
            case "light": return 300;
            case "regular": return REGULAR;
            case "medium": return 500;
            case "semibold": return 600;
            case "bold": return 700;
            case "extrabold": return 800;
            case "black": return 900;
            default: return 0;
        }
    }

    /** The names Instagram gives Prism Sans' font files (with the weight after) and its family. */
    static final String PRISM_FILE = "IGUIBetav9_";
    static final String PRISM_FAMILY = "prism_sans";

    /**
     * Injected right after IgTextView's set-up. Sets [view]'s Typeface to the chosen font at the
     * weight and slant it already has. Never throws.
     */
    public static void textView(TextView view) {
        try {
            HookStatus.invoked(FamilyNames.FONT);
            if (view == null || !Utils.settingsReady()) return;
            Typeface base = chosen();
            if (base == null) return;
            Typeface current = view.getTypeface();
            if (current != null && OURS.contains(current)) return;
            view.setTypeface(styled(base, current));
            HookStatus.counted(FamilyNames.FONT, counted());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FONT, "text view", failure);
        }
    }

    /** [base] at the weight and slant of [like], or regular and upright without one. */
    static Typeface styled(Typeface base, @Nullable Typeface like) {
        int weight = like == null ? REGULAR : like.getWeight();
        boolean italic = like != null && like.isItalic();
        Typeface styled = Typeface.create(base, weight, italic);
        OURS.add(styled);
        return styled;
    }

    private static String counted() {
        return Settings.FONT.get() == FontChoice.FILE ? PICKED : PHONE;
    }

    /**
     * The font chosen for this process, read once: Typeface.DEFAULT for the phone's, the kept file
     * for a file, null for Instagram's. A file that won't load answers null and says so once.
     */
    @Nullable
    static Typeface chosen() {
        Object known = chosen;
        if (known != UNREAD) return (Typeface) known;
        synchronized (Fonts.class) {
            if (chosen != UNREAD) return (Typeface) chosen;
            FontChoice choice = Settings.FONT.get();
            Typeface base = null;
            if (choice == FontChoice.SYSTEM) {
                base = Typeface.DEFAULT;
            } else if (choice == FontChoice.FILE) {
                base = loadKept();
                if (base == null) Utils.showToastLong(L10n.t(
                        "Couldn't load your font file, so Instagram's font shows. Pick it again in HushGram settings > Layout."));
            }
            chosen = base;
            return base;
        }
    }

    @Nullable
    private static Typeface loadKept() {
        Context context = Utils.getContext();
        if (context == null) return null;
        File file = kept(context);
        if (!readyForRead(file.getParentFile()) || !file.isFile()) return null;
        try {
            String mark = Long.toString(file.length(), 36) + "x" + Long.toString(file.lastModified(), 36);
            Typeface loaded = loader.load(file);
            if (loaded != null && drawsInColor(file)) fileMark = mark;
            return loaded;
        } catch (Exception failure) {
            Logger.printInfo(() -> "Font: the kept file didn't load", failure);
            return null;
        }
    }

    /**
     * Whether a font file, or the first font of a collection, has a CBDT or COLR table: color emoji
     * Android draws in their own colors. Reads only the table list at the start of the file.
     */
    static boolean drawsInColor(File file) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long start = 0;
            if (in.readInt() == COLLECTION) {
                in.readInt();
                if (in.readInt() < 1) return false;
                start = in.readInt() & 0xffffffffL;
                in.seek(start + 4);
            }
            int tables = in.readUnsignedShort();
            in.seek(start + 12);
            for (int i = 0; i < tables; i++) {
                int tag = in.readInt();
                if (tag == CBDT || tag == COLR) return true;
                in.skipBytes(12);
            }
        } catch (IOException failure) {
            Logger.printInfo(() -> "Font: couldn't read the kept file's tables", failure);
        }
        return false;
    }

    /** Loads [file] the way the picked font is loaded, or answers null when it isn't a font. */
    @Nullable
    public static Typeface load(File file) {
        return loader.load(file);
    }

    /** Where the picked font is kept. */
    static File kept(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY), FILE);
    }

    /**
     * Copies a picked file into Instagram's own storage if it is a font that loads, in place of the
     * one kept before. Answers false, keeping what was there, for a file too big, one that doesn't
     * start like a TrueType or OpenType font or collection, or one Android won't load. Off the
     * main thread.
     */
    public static boolean store(Context context, InputStream input) {
        return keep(input, kept(context));
    }

    /** The process that owns a pending picker; revisions from another process are not comparable. */
    public static String importProcess() { return ImportProcess.ID; }

    /** One font copy owns the process until its staged file has either landed or been removed. */
    public static synchronized long beginImport() {
        if (!Utils.isMainProcess() || importing != 0 || importSequence == Long.MAX_VALUE) return 0;
        importing = ++importSequence;
        notifyImports();
        return importing;
    }

    public static synchronized boolean ownsImport(long token) { return token != 0 && importing == token; }

    public static synchronized boolean importActive() { return importing != 0; }

    public static synchronized void finishImport(long token) {
        if (!ownsImport(token)) return;
        importing = 0;
        notifyImports();
    }

    public static synchronized void watchImports(Runnable watcher) {
        if (!importWatchers.contains(watcher)) importWatchers.add(watcher);
    }

    public static synchronized void unwatchImports(Runnable watcher) { importWatchers.remove(watcher); }

    private static void notifyImports() {
        for (Runnable watcher : new ArrayList<>(importWatchers)) Utils.runOnMainThread(watcher);
    }

    /** A replacement file needs a restart even when the selected enum remains FILE. */
    public static void fileInstalled() { fileRestartPending = true; }

    public static boolean restartPending() {
        return fileRestartPending && Settings.FONT.savedValue() == FontChoice.FILE;
    }

    /** Validates a font without touching the installed file. The caller still owns [input]. */
    @Nullable
    public static Prepared prepare(Context context, InputStream input) {
        return prepare(input, kept(context).getParentFile());
    }

    /** Prepares the rollback copy without changing the installed font. Off the main thread. */
    public static boolean prepareInstall(Context context, Prepared prepared) {
        return prepared.prepareTarget(kept(context));
    }

    /**
     * Copies [input] to [target] if it's a font that loads, the way {@link #store} keeps the picked
     * font, in place of any file there. Answers false, keeping what was there, for a file too big,
     * one that doesn't start like a font or one Android won't load. Story text fonts keep their
     * files this way too. Off the main thread.
     */
    public static boolean keep(InputStream input, File target) {
        try (Prepared prepared = prepare(input, target.getParentFile())) {
            if (prepared == null || !prepared.prepareTarget(target) || !prepared.install()) return false;
            return prepared.accept();
        }
    }

    /** The stage is unique, so even independent callers cannot share a partial file. */
    @Nullable
    public static Prepared prepare(InputStream input, File directory) {
        if (!Utils.isMainProcess()) return null;
        File temporary = null;
        boolean ready = false;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Couldn't make " + directory.getName());
            synchronized (stagingLocks.computeIfAbsent(directory, ignored -> new Object())) {
                readyForRead(directory);
                temporary = File.createTempFile("font-import-", ".tmp", directory);
            }
            byte[] start = new byte[4];
            int read = 0;
            long total = 0;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) throw new IOException("The font provider returned no bytes");
                    total += count;
                    if (total > MOST_BYTES) return null;
                    for (int i = 0; i < count && read < start.length; i++) start[read++] = buffer[i];
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (read < start.length || !startsLikeAFont(start)) return null;
            Typeface loaded = loader.load(temporary);
            if (loaded == null) return null;
            ready = true;
            return new Prepared(temporary);
        } catch (Exception failure) {
            Logger.printInfo(() -> "Font: the picked file wasn't kept", failure);
            return null;
        } finally {
            if (!ready) discard(temporary);
        }
    }

    /** A validated private stage. Closing an unaccepted installation restores its previous file. */
    public static final class Prepared implements AutoCloseable {
        private final File temporary;
        @Nullable private File target, backup, journal;
        private boolean installed, accepted;

        private Prepared(File temporary) { this.temporary = temporary; }

        /** The staged copy, for a check of its own before it's installed. */
        File staged() { return temporary; }

        /** Copies the previous file before final authorization, without moving it out of the way. */
        public boolean prepareTarget(File destination) {
            if (target != null || !temporary.getParentFile().equals(destination.getParentFile())) return false;
            try {
                File previous = new File(destination.getParentFile(), destination.getName() + PREVIOUS);
                File transaction = new File(destination.getParentFile(), destination.getName() + TRANSACTION);
                if (transaction.exists() || previous.exists()) return false;
                if (destination.exists()) {
                    backup = previous;
                    Files.copy(destination.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                target = destination;
                return true;
            } catch (Exception failure) {
                Logger.printInfo(() -> "Font: couldn't prepare the previous file", failure);
                return false;
            }
        }

        /** A durable marker connects a live-file rename to the setting that makes it usable. */
        public boolean recordSetting(Setting<?> setting, Object expected) {
            if (target == null || installed || accepted) return false;
            File writing = new File(target.getParentFile(), target.getName() + WRITING);
            journal = new File(target.getParentFile(), target.getName() + TRANSACTION);
            try {
                try (FileOutputStream output = new FileOutputStream(writing);
                     DataOutputStream data = new DataOutputStream(output)) {
                    data.writeInt(TRANSACTION_MAGIC);
                    data.writeUTF(target.getName());
                    data.writeUTF(setting.key);
                    data.writeUTF(expected.toString());
                    data.writeBoolean(backup != null);
                    data.flush();
                    output.getFD().sync();
                }
                Files.move(writing.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception failure) {
                Logger.printInfo(() -> "Font: couldn't record the prepared installation", failure);
                discard(writing);
                discard(journal);
                journal = null;
                return false;
            }
        }

        /** The only live-file mutation. Call after the provider closes and final authorization passes. */
        public boolean install() {
            if (target == null || installed || accepted) return false;
            installed = true;
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception failure) {
                Logger.printInfo(() -> "Font: couldn't install the prepared file", failure);
                return false;
            }
        }

        /** Removing the pending marker is the commit point. A refused delete must still roll back. */
        public boolean accept() {
            try {
                if (journal != null) Files.deleteIfExists(journal.toPath());
                accepted = true;
                return true;
            } catch (Exception failure) {
                Logger.printInfo(() -> "Font: couldn't commit the prepared file", failure);
                return false;
            }
        }

        public boolean rollback() {
            if (!installed || accepted) return true;
            File destination = target;
            if (destination == null) return false;
            try {
                if (backup == null) Files.deleteIfExists(destination.toPath());
                else restorePrevious(backup, destination);
                installed = false;
                return true;
            } catch (Exception failure) {
                Logger.printException(() -> "Font: couldn't restore the previous file", failure);
                return false;
            }
        }

        @Override public void close() {
            if (!rollback()) return;
            discard(temporary);
            discard(journal);
            if (journal == null || !journal.exists()) discard(backup);
        }
    }

    /**
     * Recover before the first read as well as the first import. Never recover a transaction owned
     * by this process, or mutate files from a secondary process. An unresolved marker hides its
     * directory until the main process can recover it.
     */
    public static boolean readyForRead(File directory) {
        synchronized (stagingLocks.computeIfAbsent(directory, ignored -> new Object())) {
            if (!recoveredDirectories.contains(directory)) {
                if (Utils.isMainProcess()) recoverStages(directory);
                recoveredDirectories.add(directory);
            }
            File[] pending = directory.listFiles((parent, name) -> name.endsWith(TRANSACTION));
            return pending == null ? !directory.exists() : pending.length == 0;
        }
    }

    private static void recoverStages(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) if (file.getName().endsWith(TRANSACTION)) recoverTransaction(file);
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName();
            if (name.startsWith("font-import-") && name.endsWith(".tmp") || name.endsWith(WRITING)) discard(file);
            else if (name.endsWith(PREVIOUS)) {
                String target = name.substring(0, name.length() - PREVIOUS.length());
                if (!new File(directory, target + TRANSACTION).exists()) discard(file);
            }
        }
    }

    /** An unknown marker is retained with its backup. It must never authorize deleting a live font. */
    private static void recoverTransaction(File journal) {
        String name, key, expected;
        boolean hadPrevious;
        try (DataInputStream input = new DataInputStream(new FileInputStream(journal))) {
            if (input.readInt() != TRANSACTION_MAGIC) return;
            name = input.readUTF();
            key = input.readUTF();
            expected = input.readUTF();
            hadPrevious = input.readBoolean();
            if (input.read() != -1 || name.isEmpty() || name.startsWith(".") || name.endsWith(".tmp")
                    || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                    || !new File(name).getName().equals(name)
                    || !journal.getName().equals(name + TRANSACTION)) return;
        } catch (Exception failure) {
            Logger.printInfo(() -> "Font: couldn't read a previous installation", failure);
            return;
        }
        try {
            Setting<?> setting = key.equals(Settings.FONT.key) ? Settings.FONT
                    : key.equals(Settings.STORY_FONTS.key) ? Settings.STORY_FONTS
                    : key.equals(Settings.STORY_EMOJI.key) ? Settings.STORY_EMOJI
                    : key.equals(Settings.EMOJI_FONT.key) ? Settings.EMOJI_FONT : null;
            if (setting == null) return;
            boolean known = setting == Settings.FONT ? FontChoice.FILE.name().equals(expected)
                    : setting == Settings.EMOJI_FONT ? EmojiChoice.kept(expected) : "true".equals(expected);
            if (!known) return;
            File target = new File(journal.getParentFile(), name);
            File previous = new File(journal.getParentFile(), name + PREVIOUS);
            // FILE/true may predate the import, so the option cannot prove that this file committed.
            // A crash before accepted cleanup can retain the old file even after the option saved.
            // Preferences may have changed since then; recovery must never restore their old values.
            if (previous.isFile()) restorePrevious(previous, target);
            else if (!hadPrevious) Files.deleteIfExists(target.toPath());
            else return;
            discard(journal);
            if (!journal.exists()) discard(previous);
        } catch (Exception failure) {
            Logger.printInfo(() -> "Font: couldn't recover a previous installation", failure);
        }
    }

    private static void restorePrevious(File previous, File target) throws IOException {
        File restored = File.createTempFile("font-import-", ".tmp", target.getParentFile());
        try {
            Files.copy(previous.toPath(), restored.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(restored.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            discard(restored);
        }
    }

    private static void discard(@Nullable File file) {
        if (file != null && file.exists() && !file.delete()) Logger.printInfo(() -> "Font: couldn't remove the partial copy");
    }

    /** TrueType (0x00010000 or 'true'), OpenType with CFF outlines ('OTTO') or a collection ('ttcf'). */
    static boolean startsLikeAFont(byte[] start) {
        int tag = ((start[0] & 0xff) << 24) | ((start[1] & 0xff) << 16) | ((start[2] & 0xff) << 8) | (start[3] & 0xff);
        return tag == 0x00010000 || tag == 0x4f54544f || tag == 0x74727565 || tag == 0x74746366;
    }

    /** Takes back what this process read, for a test. */
    static void resetForTests() {
        chosen = UNREAD;
        fileRestartPending = false;
        synchronized (Fonts.class) {
            importing = 0;
            importWatchers.clear();
        }
        recoveredDirectories.clear();
        stagingLocks.clear();
        fileMark = null;
        OURS.clear();
        WEIGHTS.clear();
        loader = file -> new Typeface.Builder(file).build();
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Hidden sounds, for "Hide reels by sound" (#191): the sounds picked with the sound icon beside a
 * reel, whose reels the Reels tab then leaves out.
 *
 * <p>A sound is Instagram's {@code audio_asset_id}, which every reel using it shares: a track's, or
 * for a reel with its own sound, that sound's, which the reels reusing it share too. The list is
 * {@link Settings#HIDDEN_SOUNDS}, a line for each sound: its id, a tab, its title, a tab and who made
 * it, so settings can show what each one is.
 *
 * <p>It filters the same pages as {@link CaptionWordReels}: each page the Reels viewer's network
 * callback takes, with the viewer's module name, and only the Reels tab's, so a reel opened from
 * the sound's own page, a profile, search or a message still plays. A page where every reel would go
 * keeps every item, so the viewer always has a reel to show. Off, paused, with nothing hidden,
 * before the settings are read, or when anything throws, the page comes back as it came.
 */
public final class HiddenSounds {
    /** The diagnostic counter route: the pages seen, and the items taken out of them. */
    static final String ROUTE = "Hidden sounds";

    /** The reason, and kind, of an item whose sound is hidden. */
    static final String HIDDEN = "hidden sound";

    /** The kind counted for a page left whole because every reel in it matched. */
    static final String PAGE_KEPT = "page kept whole";

    /** The longest title or name kept for a sound, so one odd title can't make the list huge. */
    static final int MAX_NAME = 100;

    /** Held while the saved list is read, changed and saved back, so two edits never lose one another. */
    private static final Object EDIT = new Object();

    /** The ids of the last list read and what it was parsed from. */
    private static volatile Parsed parsed;

    private HiddenSounds() {
    }

    /** A sound: its id, its title and who made it, either of the last two empty when Instagram didn't say. */
    public static final class Sound {
        public final String id;
        public final String title;
        public final String artist;

        public Sound(String id, String title, String artist) {
            this.id = id;
            this.title = title;
            this.artist = artist;
        }
    }

    private static final class Parsed {
        final String text;
        final Set<String> ids;

        Parsed(String text, Set<String> ids) {
            this.text = text;
            this.ids = ids;
        }
    }

    /** Reads what decides an item; the patch-filled stub and the sound bridges outside tests. */
    interface Reader {
        /** The item's post, or null for anything that isn't a Reels item carrying one. */
        @Nullable
        Object media(Object item);

        /** The id of the post's sound, or null when it has none. */
        @Nullable
        String soundId(Object media);
    }

    private static final Reader PATCHED = new Reader() {
        @Override
        public Object media(Object item) {
            return HiddenSounds.media(item);
        }

        @Override
        public String soundId(Object media) {
            Sound sound = soundOf(media, null);
            return sound == null ? null : sound.id;
        }
    };

    /**
     * Injected where the Reels viewer's network callback takes a page, with the viewer's module
     * name. Answers a new list without the reels whose sound is hidden while the switch is on and the
     * page is for the Reels tab, and [items] itself otherwise. Never throws.
     */
    public static List<?> page(List<?> items, String module) {
        return page(items, module, PATCHED, HiddenSounds::hiddenWhenOn);
    }

    static List<?> page(List<?> items, String module, Reader reader, Supplier<Set<String>> hidden) {
        if (items == null) return null;
        try {
            HookStatus.invoked(FamilyNames.REEL_DOWNLOAD);
            if (!PaidPartnershipReels.pickedByInstagram(module)) return items;
            int size = items.size();
            FeedFilterCounters.sawList(ROUTE, size);
            if (size == 0) return items;
            Set<String> ids = hidden.get();
            if (ids.isEmpty()) return items;

            boolean[] goes = new boolean[size];
            int matched = 0;
            int reels = 0;
            int at = 0;
            for (Object item : items) {
                if (at >= size) return items;
                Object media = item == null ? null : reader.media(item);
                if (media != null) reels++;
                String id = media == null ? null : reader.soundId(media);
                boolean hit = id != null && ids.contains(id);
                if (hit) {
                    matched++;
                    FeedFilterCounters.sawKind(ROUTE, HIDDEN);
                }
                goes[at++] = hit;
            }
            // A list that changed size while it was read isn't one this can rebuild.
            if (at != size) return items;
            if (matched == 0) return items;
            if (matched == reels) {
                FeedFilterCounters.sawKind(ROUTE, PAGE_KEPT);
                Logger.printDebug(() -> "Hidden sounds: kept a page of " + size + " where every reel matched");
                return items;
            }

            List<Object> kept = new ArrayList<>(size - matched);
            at = 0;
            for (Object item : items) {
                if (!goes[at++]) kept.add(item);
            }
            FeedFilterCounters.removed(ROUTE, matched, HIDDEN);
            int left = kept.size();
            int removed = matched;
            Logger.printDebug(() -> "Hidden sounds: took " + removed + " of " + size + " reels out, " + left + " left");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "hidden sounds page", failure);
            return items;
        }
    }

    /**
     * The sound [media] uses: its track, else its own sound, or null when it has neither or they
     * can't be read. A reel's own sound is credited to [poster], the account the reel is from, when
     * known. Never throws.
     */
    @Nullable
    public static Sound soundOf(@Nullable Object media, @Nullable String poster) {
        if (media == null) return null;
        try {
            Object clips = InstagramMedia.clipsMetadata(media);
            if (clips == null) return null;
            Object music = InstagramMedia.clipsMusic(clips);
            Object track = music == null ? null : InstagramMedia.musicTrack(music);
            String id = track == null ? null : id(InstagramMedia.trackAudioId(track));
            if (id != null) return new Sound(id, name(InstagramMedia.trackTitle(track)), name(InstagramMedia.trackArtist(track)));
            Object own = InstagramMedia.originalSound(clips);
            id = own == null ? null : id(InstagramMedia.originalSoundAudioId(own));
            if (id == null) return null;
            String by = name(poster);
            return new Sound(id, name(InstagramMedia.originalSoundTitle(own)), by.isEmpty() ? "" : "@" + by);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel sound", failure);
            return null;
        }
    }

    /** The hidden sounds' ids while the switch is on, read only once the settings are; none while paused or off. */
    static Set<String> hiddenWhenOn() {
        if (!Utils.settingsReady() || !Settings.HIDE_SOUND_REELS.get()) return Collections.emptySet();
        String text = Settings.HIDDEN_SOUNDS.get();
        if (text == null || text.isEmpty()) return Collections.emptySet();
        Parsed known = parsed;
        if (known != null && known.text.equals(text)) return known.ids;
        Set<String> ids = Collections.unmodifiableSet(sounds(text).keySet());
        parsed = new Parsed(text, ids);
        return ids;
    }

    /** The hidden sounds as you chose them, paused or not, in the order added. Never throws. */
    public static List<Sound> saved() {
        try {
            if (!Utils.settingsReady()) return Collections.emptyList();
            return new ArrayList<>(sounds(Settings.HIDDEN_SOUNDS.savedValue()).values());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "hidden sounds", failure);
            return Collections.emptyList();
        }
    }

    /** Whether [id] is on the list, paused or not. */
    public static boolean hides(@Nullable String id) {
        if (id == null || !Utils.settingsReady()) return false;
        return sounds(Settings.HIDDEN_SOUNDS.savedValue()).containsKey(id);
    }

    /** Hides the reels using [sound]. Answers whether the list holds it now; one already there isn't added twice. */
    public static boolean add(@Nullable Sound sound) {
        if (sound == null || id(sound.id) == null || !Utils.settingsReady()) return false;
        synchronized (EDIT) {
            String text = Settings.HIDDEN_SOUNDS.savedValue();
            if (sounds(text).containsKey(sound.id)) return true;
            String line = sound.id + "\t" + name(sound.title) + "\t" + name(sound.artist);
            Settings.HIDDEN_SOUNDS.save(text == null || text.isEmpty() ? line : text + "\n" + line);
        }
        Logger.printDebug(() -> "Hidden sounds: added a sound");
        return true;
    }

    /** Shows the reels using the sound [id] again. */
    public static void remove(@Nullable String id) {
        if (id == null || !Utils.settingsReady()) return;
        synchronized (EDIT) {
            StringBuilder kept = new StringBuilder();
            for (String line : lines(Settings.HIDDEN_SOUNDS.savedValue())) {
                Sound sound = entry(line);
                if (sound != null && sound.id.equals(id)) continue;
                if (kept.length() > 0) kept.append('\n');
                kept.append(line);
            }
            Settings.HIDDEN_SOUNDS.save(kept.toString());
        }
        Logger.printDebug(() -> "Hidden sounds: removed a sound");
    }

    /** The sounds [text] keeps, by id, each once, in the order added. */
    static Map<String, Sound> sounds(@Nullable String text) {
        Map<String, Sound> sounds = new LinkedHashMap<>();
        for (String line : lines(text)) {
            Sound sound = entry(line);
            if (sound != null && !sounds.containsKey(sound.id)) sounds.put(sound.id, sound);
        }
        return sounds;
    }

    private static List<String> lines(@Nullable String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) lines.add(line);
        }
        return lines;
    }

    /** A line's sound, or null for a line that isn't one. */
    @Nullable
    private static Sound entry(String line) {
        String[] parts = line.split("\t", -1);
        String id = id(parts[0]);
        if (id == null) return null;
        return new Sound(id, parts.length > 1 ? name(parts[1]) : "", parts.length > 2 ? name(parts[2]) : "");
    }

    /** [raw] as a sound id: trimmed digits, or null for anything else. */
    @Nullable
    static String id(@Nullable String raw) {
        if (raw == null) return null;
        String id = raw.trim();
        if (id.isEmpty() || id.length() > 30) return null;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        return id;
    }

    /** [raw] as a title or name kept on one line: tabs and line breaks as spaces, trimmed, cut short. Never null. */
    static String name(@Nullable String raw) {
        if (raw == null) return "";
        String name = raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME).trim() : name;
    }

    /**
     * The post a Reels item carries, or null for anything else. The patch writes the body, which
     * checks the item is Instagram's clips item and reads its one post field. Null as built.
     */
    public static Object media(Object item) {
        return null;
    }

    /** Forgets the parsed list. Tests only. */
    static void resetForTests() {
        parsed = null;
    }
}

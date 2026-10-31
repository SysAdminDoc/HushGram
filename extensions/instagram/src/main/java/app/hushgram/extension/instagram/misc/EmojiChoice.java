/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import androidx.annotation.Nullable;

/**
 * Where Instagram's emoji draw from ({@link EmojiFonts}): the phone's own, one of the free emoji
 * packs HushGram downloads from its maker's own release, or an emoji font file picked in settings
 * (#166). Each pack names the exact file, its size and its SHA-256, so nothing else is kept.
 */
public enum EmojiChoice {
    /** The phone's emoji, or Google's with Emoji style's switch on. Changes nothing. */
    PHONE(null, 0, null),
    /** Twitter's emoji as Mozilla's color font, CC BY 4.0. */
    TWEMOJI("https://github.com/mozilla/twemoji-colr/releases/download/v0.7.0/Twemoji.Mozilla.ttf", 1474284,
            "6d90152ee0d29e82fe2a87793af5aa4b7ad13e6538360889e141e81ed299ee8e"),
    /** OpenMoji 17.0.0's color font, CC BY-SA 4.0. */
    OPENMOJI("https://raw.githubusercontent.com/hfg-gmuend/openmoji/17.0.0/font/OpenMoji-color-cbdt/OpenMoji-color-cbdt.ttf",
            6696312, "a541ac344a05bf1aa12585790c86bb2a40649940866d317d492f944bbf139795"),
    /** Blobmoji 15.0, Android's old blob emoji brought up to date, Apache 2.0. */
    BLOBMOJI("https://github.com/C1710/blobmoji/releases/download/v15.0/Blobmoji.ttf", 12877044,
            "dcc3d6675036ba9b35ef574d2221532770b5bd9886cba23c645ef46947eb78f9"),
    /** A color emoji font file picked in HushGram settings. */
    FILE(null, 0, null);

    /** Where the pack's font file is downloaded from, or null for a choice with nothing to download. */
    @Nullable
    public final String url;
    /** The pack file's exact size and SHA-256 in lowercase hex. */
    public final long bytes;
    @Nullable
    public final String sha256;

    EmojiChoice(@Nullable String url, long bytes, @Nullable String sha256) {
        this.url = url;
        this.bytes = bytes;
        this.sha256 = sha256;
    }

    /** Whether choosing it downloads a pack. */
    public boolean pack() {
        return url != null;
    }

    /** Whether [name] is a choice that keeps a font file: any but the phone's. */
    static boolean kept(String name) {
        for (EmojiChoice choice : values()) if (choice != PHONE && choice.name().equals(name)) return true;
        return false;
    }
}

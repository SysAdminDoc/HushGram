/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

/** The font Instagram's text draws in ({@link Fonts}). Instagram's own is the one that changes nothing. */
public enum FontChoice {
    /** Instagram's own fonts, untouched. */
    INSTAGRAM,
    /** The phone's own font, the one other apps draw in. */
    SYSTEM,
    /** A font file picked in HushGram settings and kept in Instagram's own storage. */
    FILE
}

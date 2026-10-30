/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import androidx.annotation.Nullable;

/**
 * The icon Instagram shows on the home screen ({@link AppIcon}, #284). Each but Instagram's own is an
 * alias the patch adds, named after Instagram's launcher alias and {@link #key}.
 */
public enum AppIconChoice {
    /** Instagram's own icon, on Instagram's own alias. */
    INSTAGRAM(null),
    /** HushGram's H and ribbon on its pink to plum tile. */
    HUSHGRAM("hushgram"),
    /** Instagram's white glyph on black. */
    DARK("dark"),
    /** Instagram's white glyph on plum. */
    PLUM("plum"),
    /** Instagram's glyph in black on white (#353). */
    LIGHT("light");

    /** The end of this icon's alias, as the patch names it, or null for Instagram's own. */
    @Nullable public final String key;

    AppIconChoice(@Nullable String key) {
        this.key = key;
    }
}

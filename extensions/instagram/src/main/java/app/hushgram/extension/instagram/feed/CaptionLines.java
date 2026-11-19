/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

/**
 * How much of a feed post's caption shows before Instagram's "more" (#771): the whole caption, or a
 * number of lines. Instagram's own count is one or two lines, depending on the feed.
 */
public enum CaptionLines {
    WHOLE(Integer.MAX_VALUE),
    ONE(1),
    TWO(2),
    THREE(3),
    FOUR(4),
    FIVE(5),
    SIX(6),
    EIGHT(8),
    TEN(10);

    /** The line count Instagram cuts the caption at, where the whole caption never reaches it. */
    public final int lines;

    CaptionLines(int lines) {
        this.lines = lines;
    }
}

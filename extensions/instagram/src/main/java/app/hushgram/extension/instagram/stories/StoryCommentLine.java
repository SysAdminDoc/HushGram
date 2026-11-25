/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * How high the line a story shows for writing a comment sits (#826). On 457 Instagram puts it a few
 * dp over the bottom of the story's page, {@link #INSTAGRAM}, the default. {@link #HIGHER} and
 * {@link #HIGHEST} lift it by that much ({@link StoryReplyBox}), without moving anything around it.
 */
public enum StoryCommentLine {
    INSTAGRAM(0),
    HIGHER(24),
    HIGHEST(48);

    /** How much higher the line sits, in dp. */
    public final int dp;

    StoryCommentLine(int dp) {
        this.dp = dp;
    }

    /** How far up the line goes on a screen of [density], in pixels. */
    public int px(float density) {
        return Math.round(dp * density);
    }
}

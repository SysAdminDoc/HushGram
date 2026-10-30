/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

/**
 * How round the glass tab bar's pill is (#144). {@link #STANDARD}, the default, is the pill the bar has always
 * had. {@link #ROUNDER} rounds its corners more and {@link #ROUND} makes its ends half circles. The selected
 * tab's highlight follows, and {@link GlassTabBar#cornerPx} keeps either from passing half the height. The
 * liquid look is fully round whatever is picked here.
 */
public enum GlassCorners {
    STANDARD(12, 8),
    ROUNDER(20, 14),
    ROUND(Integer.MAX_VALUE, Integer.MAX_VALUE);

    /** The pill's corner radius, in dp. */
    public final int dp;
    /** The selected tab highlight's corner radius, in dp. */
    public final int highlightDp;

    GlassCorners(int dp, int highlightDp) {
        this.dp = dp;
        this.highlightDp = highlightDp;
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

/**
 * How tall the glass tab bar's pill is. {@link #STANDARD}, the default, is the pill the bar has always had.
 * {@link #COMPACT} draws a slimmer pill inside the same bar, so every tab keeps its full touch target, and
 * {@link #TALL} makes the bar itself a little taller. Both are held to limits in
 * {@link GlassTabBar#pillInsetPx} and {@link GlassTabBar#barHeightPx} so the icons and badges always fit.
 */
public enum GlassHeight {
    COMPACT,
    STANDARD,
    TALL
}

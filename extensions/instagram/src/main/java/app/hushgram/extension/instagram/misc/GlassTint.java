/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

/**
 * The color the glass tab bar's pill is tinted (#875). {@link #INSTAGRAM}, the default, is the bar's own color,
 * dark or light with Instagram's theme, as the glass has always been. {@link #LIGHT} and {@link #DARK} pick one
 * shade whatever the theme, {@link #AMOLED} is solid black, {@link #WALLPAPER} takes the phone's Material You
 * accent on Android 12 and newer, and {@link #CUSTOM} the color typed under it. The tab icons are recolored
 * where a tint leaves them on glass of the other shade ({@link GlassTabBar#glassColor}).
 */
public enum GlassTint {
    INSTAGRAM,
    LIGHT,
    DARK,
    AMOLED,
    WALLPAPER,
    CUSTOM
}

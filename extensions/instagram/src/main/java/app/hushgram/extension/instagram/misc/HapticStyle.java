/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

/**
 * Which tick the glass tab bar plays as its highlight passes a tab. A motor can't be heard about from a
 * description, so there are four to pick between: a short pulse of the motor's own (the default, the closest to
 * the quick tap of an iPhone), the phone's own click effect, a light tick that rings a little longer, and
 * the phone's strongest tick.
 */
public enum HapticStyle {
    SHORT,
    SYSTEM,
    SOFT,
    FULL
}

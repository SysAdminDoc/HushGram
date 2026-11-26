/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

/**
 * How one of HushGram's overlays on a photo or video looks (#471): solid, as it always was, see-through,
 * or frosted glass that blurs what's under it ({@link FrostedBackdrop}). Frosted glass needs Android 12,
 * and below that, or while a blur can't be recorded, it's see-through. Page dots on a pill
 * ({@link CarouselArrows}) and the seek bars on feed videos and stories ({@link GlassSeekBar}) each have
 * their own choice.
 *
 * <p>{@link #DOTS_ONLY} is the pill's alone (#187): its dots with nothing behind them. The seek bars don't
 * offer it, and one given it anyway draws solid.
 */
public enum OverlayLook {
    SOLID,
    SEE_THROUGH,
    FROSTED,
    DOTS_ONLY;

    /** The looks a seek bar offers, in their order. */
    public static OverlayLook[] seekBarLooks() {
        return new OverlayLook[] {SOLID, SEE_THROUGH, FROSTED};
    }
}

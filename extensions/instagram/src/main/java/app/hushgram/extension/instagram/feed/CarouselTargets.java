/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

/**
 * What Carousel arrows asks of Instagram's carousel pager, as a bridge the "Carousel arrows" patch
 * writes. It sits in a class of its own, apart from {@link CarouselArrows}, so a body the runtime ever
 * refused would take down only this call, never the arrows' own code. Unpatched, it does nothing.
 */
public final class CarouselTargets {
    private CarouselTargets() {
    }

    /**
     * Slides [pager] to its page [page], counted the way the pager counts its pages, at [speed], the
     * way Instagram's own buttons move a pager. The patch writes the body.
     */
    public static void turn(Object pager, int page, float speed) {
    }
}

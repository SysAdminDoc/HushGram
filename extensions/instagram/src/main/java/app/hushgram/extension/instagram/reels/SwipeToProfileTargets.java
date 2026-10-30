/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.view.View;

/**
 * Stubs the "Swipe left to open the profile" patch writes, so {@link SwipeToProfile} can reach the
 * Reels tab's profile panel without naming Instagram's renamed classes. Unpatched, each one answers
 * as if the piece were missing, and turning a panel does nothing.
 */
public final class SwipeToProfileTargets {
    private SwipeToProfileTargets() {
    }

    /** The tab host's swipe controller, or null. */
    public static Object controller(Object host) {
        return null;
    }

    /** The controller's side panel container, or null. */
    public static Object container(Object controller) {
        return null;
    }

    /** The tab the host is on. */
    public static Object tab(Object host) {
        return null;
    }

    /** The Reels tab. */
    public static Object reelsTab() {
        return null;
    }

    /** The container's profile panel, its right one, or null. */
    public static Object panel(Object container) {
        return null;
    }

    /** The view the right panel is built on, from the controller, or null when the layout has none. */
    public static Object rightView(Object controller) {
        return null;
    }

    /** Whether the panel is on. */
    public static boolean panelOn(Object panel) {
        return false;
    }

    /** Turns the panel on or off, by the panel's own position in the container. */
    public static void turn(Object container, Object panel, boolean on) {
    }

    /** Instagram's own walk: whether a view under the point, or the view itself, can scroll sideways against dx (negative for a swipe left). */
    public static boolean childScrolls(View view, int dx, int x, int y, boolean checkView, boolean skipOff) {
        return false;
    }

    /** Whether the view is a pager. */
    public static boolean pager(Object view) {
        return false;
    }

    /** Whether the pager takes swipes. */
    public static boolean inputOn(Object pager) {
        return false;
    }

    /** Turns the pager's swipes on or off. */
    public static void input(Object pager, boolean on) {
    }
}

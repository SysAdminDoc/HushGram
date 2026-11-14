/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import android.app.Activity;
import android.content.Context;
import java.io.IOException;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * A comment GIF saved with Save comment photo and picked again from the gallery in the comment box
 * (#402). Instagram would upload the file as a still, so it goes up through Instagram's own GIF send
 * instead, the same GIF, animated, like one picked in Instagram's GIF search. Save keeps the GIF's
 * send model with the file's size and SHA-256 ({@link CommentGifStore}); any other picture, or a
 * GIF Save didn't keep, goes up as before.
 */
public final class CommentGifPick {
    private CommentGifPick() {}

    /** The steps a pick takes, Instagram's own last. */
    interface Picks {
        /** What Save kept for the file at [path], or null. */
        byte[] kept(Context context, String path) throws IOException;
        /** [kept] read back as Instagram's send model through [screen]'s classes, or null. */
        Object info(byte[] kept, Object screen);
        Activity activity();
        void onMain(Runnable step);
        void clear(Object screen);
        void send(Object screen, Object activity, Object info);
    }

    private static final Picks NATIVE = new Picks() {
        public byte[] kept(Context context, String path) throws IOException { return CommentGifStore.find(context, path); }
        public Object info(byte[] kept, Object screen) {
            return CommentGifStore.unmarshal(kept, screen.getClass().getClassLoader());
        }
        public Activity activity() { return Utils.getActivity(); }
        public void onMain(Runnable step) { Utils.runOnMainThread(step); }
        public void clear(Object screen) { CommentGifReplyNative.clearPick(screen); }
        public void send(Object screen, Object activity, Object info) { CommentGifReplyNative.send(screen, activity, info); }
    };

    // What the diagnostic report counts, fixed text only.
    static final String KEPT = "saved GIF kept";
    static final String SENT = "saved GIF sent";
    static final String UNREADABLE = "saved GIF unreadable";

    /**
     * Called by the comment box's gallery pick with the picked file's [path] on [screen], before
     * Instagram reads it as a still. 1 when it's a GIF Save kept: it goes up through Instagram's GIF
     * send, and the stock pick ends there with nothing attached. 0 leaves the pick to Instagram.
     */
    public static int picked(String path, Object screen) {
        return picked(path, screen, NATIVE);
    }

    static int picked(String path, Object screen, Picks picks) {
        try {
            if (path == null || screen == null || !enabled() || !animated(path)) return 0;
            byte[] kept = picks.kept(Utils.getContext(), path);
            if (kept == null) return 0;
            Object info = picks.info(kept, screen);
            if (info == null) {
                HookStatus.counted(FamilyNames.COMMENT_PHOTO, UNREADABLE);
                return 0;
            }
            Activity activity = picks.activity();
            if (activity == null) return 0;
            picks.onMain(() -> send(screen, activity, info, picks));
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "saved gif pick", failure);
            return 0;
        }
    }

    /** Closes the gallery with nothing attached, then sends the GIF where the comment box points. */
    private static void send(Object screen, Activity activity, Object info, Picks picks) {
        try {
            picks.clear(screen);
            picks.send(screen, activity, info);
            HookStatus.counted(FamilyNames.COMMENT_PHOTO, SENT);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "saved gif send", failure);
            try {
                Utils.showToastShort(L10n.t(activity, "Couldn't send this GIF"));
            } catch (Throwable feedbackFailure) {
                HookStatus.threw(FamilyNames.COMMENT_PHOTO, "saved gif feedback", feedbackFailure);
            }
        }
    }

    /**
     * What Save comment photo hands its save for [gif], the comment's own GIF: once the file is in
     * the gallery, the GIF's send model is kept for it. Null when the switch is off, the build can't
     * send it, or the GIF has no send model, and the save goes ahead as it always did.
     */
    static Consumer<String> keeping(Context context, Object gif) {
        return keeping(context, gif, PatchFamily::commentGifPickInBuild, CommentGifReplyNative::info);
    }

    static Consumer<String> keeping(Context context, Object gif, BooleanSupplier inBuild, Function<Object, Object> info) {
        try {
            if (context == null || gif == null || !enabled() || !inBuild.getAsBoolean()) return null;
            byte[] parcel = CommentGifStore.marshal(info.apply(gif));
            if (parcel == null) return null;
            Context application = context.getApplicationContext();
            return item -> {
                try {
                    CommentGifStore.remember(application, item, parcel);
                    HookStatus.counted(FamilyNames.COMMENT_PHOTO, KEPT);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.COMMENT_PHOTO, "saved gif keep", failure);
                }
            };
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "saved gif model", failure);
            return null;
        }
    }

    /** Only a GIF or WebP is a GIF Save kept: anything else is never read through. */
    static boolean animated(String path) {
        String name = path.toLowerCase(Locale.US);
        return name.endsWith(".gif") || name.endsWith(".webp");
    }

    private static boolean enabled() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.SEND_SAVED_GIFS.get();
    }
}

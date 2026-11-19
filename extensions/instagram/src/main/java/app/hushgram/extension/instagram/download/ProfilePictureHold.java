/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Long press a profile picture to view it (#737): a long press on the picture at the top of a
 * profile opens {@link ProfilePictureViewer} on that account's largest picture, the whole square,
 * with Save, in place of Instagram's own preview.
 *
 * <p>The patch calls {@link #hold} in place of each call that gives the profile header's picture
 * its long press listener, so the picture gets Instagram's listener wrapped in {@link Held}. The
 * switch is read at the press, so turning it on or off counts on the next long press: off, paused,
 * or with no account or picture found, the press goes to Instagram's listener as before. A tap is
 * never touched. The account is the one the profile's top bar shows ({@link ProfileExtras#accountAround}).
 *
 * <p>An account with an avatar gets a picture that turns between the photo and the avatar, and its
 * long press opens Instagram's card from a gesture listener. The patch asks {@link #flipped} there
 * first, and the card stays shut when the viewer opened (#758).
 */
public final class ProfilePictureHold {
    private ProfilePictureHold() {
    }

    static final String ROUTE = FamilyNames.PROFILE_PICTURE;
    // What the diagnostic report counts. Fixed text: nothing read from the account goes in.
    static final String OPENED = "picture long press opened the viewer";
    static final String NO_ACCOUNT = "picture long press found no account";
    static final String NO_PICTURE = "picture long press found no picture";
    static final String NOT_OPENED = "picture long press couldn't open the viewer";

    /** Finds the account a long-pressed picture belongs to. A test swaps it. */
    interface Account {
        @Nullable Object around(View view);
    }

    /** Opens the viewer, Save allowed while [allowed] says yes. Answers whether it opened. A test swaps it. */
    interface Viewer {
        boolean open(Context context, List<MediaSave.Rendition> sizes, String owner, ProfilePicture.Save save,
                BooleanSupplier allowed);
    }

    static Account account = ProfileExtras::accountAround;
    static ProfilePicture.Native reads = ProfilePicture.NATIVE;
    static ProfilePicture.Save save = ProfilePicture.SAVE;
    static Viewer viewer = ProfilePictureViewer::open;

    /**
     * Injected in place of View.setOnLongClickListener on the profile header's picture: sets
     * [listener], Instagram's, wrapped so the switch can take the long press over. A null listener
     * is set as it is. Throws only as the call it replaces would, on a null [view].
     */
    public static void hold(View view, View.OnLongClickListener listener) {
        View.OnLongClickListener set = listener;
        try {
            HookStatus.invoked(ROUTE);
            if (listener != null && !(listener instanceof Held)) set = new Held(listener);
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "picture long press listener", failure);
            set = listener;
        }
        view.setOnLongClickListener(set);
    }

    /**
     * Injected in the long press of the picture that turns between the photo and the account's
     * avatar (#758), right before it opens Instagram's card: answers whether the viewer opened on
     * [picture], so the card stays shut. That picture takes its long press through a gesture
     * listener, never a long press listener, so {@link #hold} doesn't reach it. Never throws.
     */
    public static boolean flipped(View picture) {
        HookStatus.invoked(ROUTE);
        return view(picture);
    }

    /** Whether the switch is on, which a pause answers off. */
    static boolean wanted() {
        try {
            return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.PROFILE_PICTURE_LONG_PRESS.get();
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "picture long press switch", failure);
            return false;
        }
    }

    /**
     * Opens the viewer for the account [view] is on when the switch is on and the account has a
     * picture. Answers whether it did, so the caller leaves the press to Instagram when not. Never throws.
     */
    static boolean view(View view) {
        try {
            if (!wanted()) return false;
            Object user = account.around(view);
            if (user == null) {
                HookStatus.counted(ROUTE, NO_ACCOUNT);
                return false;
            }
            List<MediaSave.Rendition> sizes = ProfilePicture.sizes(user, reads);
            if (sizes.isEmpty()) {
                HookStatus.counted(ROUTE, NO_PICTURE);
                return false;
            }
            Context context = ProfileExtras.activityOf(view);
            if (!viewer.open(context, sizes, reads.username(user), save, ProfilePictureHold::wanted)) {
                HookStatus.counted(ROUTE, NOT_OPENED);
                return false;
            }
            HookStatus.counted(ROUTE, OPENED);
            Logger.printDebug(() -> "Profile picture long press opened the viewer");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "picture long press", failure);
            return false;
        }
    }

    /** Instagram's long press listener on the picture, which the switch takes over. */
    static final class Held implements View.OnLongClickListener {
        final View.OnLongClickListener original;

        Held(View.OnLongClickListener original) {
            this.original = original;
        }

        @Override public boolean onLongClick(View view) {
            return view(view) || original.onLongClick(view);
        }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Part of "Run without Play services": where Instagram's copy of Firebase Instance ID asks Google's
 * services for a push token.
 *
 * <p>Each request carries a PendingIntent that tells the services which app is asking. The copy
 * Instagram bundles builds it with no mutability flag, and on Android 12 and newer a target of
 * Android 12 or newer may not do that: {@code PendingIntent.getBroadcast} throws
 * IllegalArgumentException on the Firebase thread and Instagram closes (#767). Phones with Google
 * Play services never reach this request; a phone with microG or another stand-in for Play services
 * does, right after sign-in.
 *
 * <p>The request goes out immutable instead, the same as newer Firebase builds it. The services only
 * read who made it, so nothing about the request changes. Reads no setting, so Pause doesn't change it.
 */
public final class FirebaseRpc {

    private FirebaseRpc() {}

    /** {@code PendingIntent.FLAG_MUTABLE}, added in Android 12; the extension builds against older APIs too. */
    static final int FLAG_MUTABLE = 0x02000000;

    /** The flags Firebase asked for, marked immutable unless it already chose either way. */
    static int immutable(int flags) {
        if ((flags & (PendingIntent.FLAG_IMMUTABLE | FLAG_MUTABLE)) != 0) return flags;
        return flags | PendingIntent.FLAG_IMMUTABLE;
    }

    /**
     * Stands in for {@code PendingIntent.getBroadcast(context, requestCode, intent, flags)} where
     * Firebase Instance ID names the app in a token request.
     */
    public static PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags) {
        HookStatus.invoked(FamilyNames.WITHOUT_PLAY_SERVICES);
        return PendingIntent.getBroadcast(context, requestCode, intent, immutable(flags));
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * #767: Firebase Instance ID's token request names the app with a PendingIntent built with no
 * mutability flag. Android 12 and newer refuse that for an app targeting 12 or newer, so on a phone
 * without Play services Instagram closed right after sign-in. Through the extension it goes out
 * immutable and Android takes it. Robolectric doesn't make that refusal, so these read the flags
 * the request goes out with.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FirebaseRpcTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        HookStatus.clear();
    }

    @After
    public void tearDown() {
        HookStatus.clear();
    }

    /** What Firebase builds: an intent naming a package nobody installs, so only its sender is read. */
    private static Intent identity() {
        return new Intent().setPackage("com.google.example.invalidpackage");
    }

    @Test
    public void theRequestGoesOutImmutable() {
        PendingIntent sent = FirebaseRpc.getBroadcast(context, 0, identity(), 0);
        assertNotNull(sent);
        int flags = shadowOf(sent).getFlags();
        assertNotEquals("immutable", 0, flags & PendingIntent.FLAG_IMMUTABLE);
        assertEquals("not mutable", 0, flags & FirebaseRpc.FLAG_MUTABLE);
        assertEquals("the same intent", "com.google.example.invalidpackage", shadowOf(sent).getSavedIntent().getPackage());
        assertTrue("a broadcast", shadowOf(sent).isBroadcast());
    }

    @Test
    public void flagsFirebaseAskedForStay() {
        assertEquals(PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT,
                FirebaseRpc.immutable(PendingIntent.FLAG_UPDATE_CURRENT));
        assertEquals(PendingIntent.FLAG_IMMUTABLE, FirebaseRpc.immutable(PendingIntent.FLAG_IMMUTABLE));
        // A request that already chose mutable keeps its choice: two choices would be refused too.
        assertEquals(FirebaseRpc.FLAG_MUTABLE | PendingIntent.FLAG_ONE_SHOT,
                FirebaseRpc.immutable(FirebaseRpc.FLAG_MUTABLE | PendingIntent.FLAG_ONE_SHOT));
    }

    @Test
    public void eachRequestIsCounted() {
        FirebaseRpc.getBroadcast(context, 0, identity(), 0);
        String line = "";
        for (String reported : HookStatus.report()) {
            if (reported.startsWith(FamilyNames.WITHOUT_PLAY_SERVICES + ":")) line = reported;
        }
        assertTrue(line, line.startsWith(FamilyNames.WITHOUT_PLAY_SERVICES + ": invoked 1,"));
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSigningInfo;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * #760: where Instagram's Facebook login looks for the Facebook app. A re-signed build is told no
 * app is there, so Instagram logs in through the browser instead of a Facebook app that refuses
 * it. A Meta-signed build, and one whose key can't be read, get the system's answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FacebookAppLoginTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final Signature OUR_KEY = new Signature("308201a0b1c2d3");
    private static final String FACEBOOK = "com.facebook.katana";
    private static final String PROXY_AUTH = "com.facebook.katana.ProxyAuth";

    private PackageManager packages;
    private ShadowPackageManager shadowPackages;
    private Intent login;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        packages = context.getPackageManager();
        shadowPackages = shadowOf(packages);
        InstagramSignature.ownSigners = null;
        HookStatus.clear();

        // The Facebook app is installed and its login screen answers Instagram's intent.
        login = new Intent().setClassName(FACEBOOK, PROXY_AUTH).putExtra("client_id", "124024574287414");
        ResolveInfo resolved = new ResolveInfo();
        resolved.activityInfo = new ActivityInfo();
        resolved.activityInfo.packageName = FACEBOOK;
        resolved.activityInfo.name = PROXY_AUTH;
        shadowPackages.addResolveInfoForIntent(login, resolved);
    }

    @After
    public void tearDown() {
        InstagramSignature.ownSigners = null;
        HookStatus.clear();
    }

    /** Installs Instagram under its own package, currently signed with [current]. */
    private void installInstagram(Signature... current) {
        PackageInfo info = new PackageInfo();
        info.packageName = OwnPackage.name();
        SigningInfo signing = new SigningInfo();
        ShadowSigningInfo shadow = Shadow.extract(signing);
        shadow.setSignatures(current);
        info.signingInfo = signing;
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = OwnPackage.name();
        info.applicationInfo = app;
        shadowPackages.installPackage(info);
    }

    /** Instagram's Meta certificates, as the signer reader answers them for this app. */
    private static List<Signature> metaCertificates() {
        PackageInfo self = new PackageInfo();
        self.packageName = OwnPackage.name();
        List<Signature> meta = InstagramSignature.originalSigners(self);
        assertNotNull(meta);
        return meta;
    }

    private static String trustLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.RESTORE_TRUST + ":")) return line;
        }
        return "";
    }

    @Test
    public void aReSignedBuildFindsNoFacebookAppToLogInWith() {
        installInstagram(OUR_KEY);
        assertNull(FacebookAppLogin.resolveActivity(packages, login, 0));
        assertTrue(trustLine(), trustLine().contains(FacebookAppLogin.SKIPPED + " 1"));
    }

    /** The signers the reader kept for this app decide it before PackageManager is asked. */
    @Test
    public void keptSignersDecideToo() {
        InstagramSignature.ownSigners = new Signature[]{OUR_KEY};
        assertNull(FacebookAppLogin.resolveActivity(packages, login, 0));
    }

    @Test
    public void aMetaSignedBuildStillHandsOffToTheFacebookApp() {
        for (Signature meta : metaCertificates()) {
            InstagramSignature.ownSigners = new Signature[]{meta};
            ResolveInfo resolved = FacebookAppLogin.resolveActivity(packages, login, 0);
            assertNotNull(resolved);
            assertEquals(PROXY_AUTH, resolved.activityInfo.name);
        }
        assertTrue(trustLine(), !trustLine().contains(FacebookAppLogin.SKIPPED));
    }

    /** Nothing readable about this build's key: Instagram's own lookup, and no throw into it. */
    @Test
    public void anUnreadableKeyKeepsInstagramsLookup() {
        ResolveInfo resolved = FacebookAppLogin.resolveActivity(packages, login, 0);
        assertNotNull(resolved);
        assertEquals(PROXY_AUTH, resolved.activityInfo.name);
        assertTrue(trustLine(), !trustLine().contains(FacebookAppLogin.SKIPPED));
    }
}

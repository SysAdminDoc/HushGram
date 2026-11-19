/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/InstagramSignature.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Restore trust on re-signed builds" patch.
 *
 * <p>Instagram's security code compares the signing certificates of a package with a table of
 * Meta certificates, and it does this for its own package too, for example before it trusts one of
 * its own content providers. A re-signed build has a different certificate, so Instagram doesn't
 * trust itself there. The patch gives the check Instagram's original certificates when the package
 * is this app.
 *
 * <p>The name alone doesn't say that it's this app; the uid does. Android gives every installed app
 * its own (Instagram's manifest shares it with no other), and the process has it before any code
 * runs, so the check needs no context even while content providers start.
 *
 * <p>The same reader gives Instagram the signers of other Meta apps. Before it opens Threads,
 * Facebook or Messenger (the Threads buttons on a profile go this way), before it takes a share
 * from one of them and before it answers one of them on its family content providers, it builds
 * that app's identity from the first signer and looks it up in the same table. A Threads, Facebook
 * or Messenger the user patched with the same key as this build would fail that, so Instagram
 * showed "Sorry, we weren't able to load that website" instead of opening Threads (#22). For one
 * of those three packages, by its exact name, the reader answers that app's own Meta certificate
 * when this build is re-signed and the app's current signers are exactly this build's current
 * signers. Instagram's own rules then judge it as they'd judge Meta's signed app. Any other
 * package, any other signer, and anything that can't be read keep Instagram's own path.
 *
 * <p>Current means the newest signer. When Instagram asks only for the old signatures array,
 * Android fills it with the oldest certificate of an app's signing history, so a Threads that
 * rotated away from this build's key would still show that key there. A family app's record
 * without SigningInfo is read again from PackageManager with its signing certificates, and if
 * that can't be done it isn't trusted. This build's own signers come only from a record the uid
 * proved is this app, or from PackageManager, never from one that only carries its name.
 *
 * <p>Only an app signed with the same key as this build is trusted, and that key is the user's
 * own: nobody else can sign an app to it. A build that still carries Meta's key, like a Root
 * Mount install, steps aside, and a Meta-signed install of these apps is left to Instagram's own
 * check, which its real signer already passes. Like the answer for this app, this reads no
 * setting, so Pause doesn't change it.
 */
public final class InstagramSignature {

    private InstagramSignature() {}

    /** Threads, Facebook and Messenger, by their exact package names. */
    static final String THREADS = "com.instagram.barcelona";
    static final String FACEBOOK = "com.facebook.katana";
    static final String MESSENGER = "com.facebook.orca";

    /** The name Hook status reports a throw from the family app answer under. */
    static final String FAMILY_HOOK = "family app signers";

    /**
     * Instagram's first signing certificate (CN=Kevin Systrom, O=Instagram Inc), DER in Base64.
     * Its SHA-256 is 5f3e50f4…a993e305. Android 7 to 12 see it as the signer.
     */
    private static final String ORIGINAL_CERTIFICATE =
        "MIICTTCCAbagAwIBAgIETzHSyzANBgkqhkiG9w0BAQUFADBqMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZv"
        + "cm5pYTEWMBQGA1UEBxMNU2FuIEZyYW5jaXNjbzEWMBQGA1UEChMNSW5zdGFncmFtIEluYzEWMBQGA1UEAxMNS2V2"
        + "aW4gU3lzdHJvbTAgFw0xMjAyMDgwMTQxMzFaGA8yMTEyMDExNTAxNDEzMVowajELMAkGA1UEBhMCVVMxEzARBgNV"
        + "BAgTCkNhbGlmb3JuaWExFjAUBgNVBAcTDVNhbiBGcmFuY2lzY28xFjAUBgNVBAoTDUluc3RhZ3JhbSBJbmMxFjAU"
        + "BgNVBAMTDUtldmluIFN5c3Ryb20wgZ8wDQYJKoZIhvcNAQEBBQADgY0AMIGJAoGBAInrysAVZgtCpcCAv2lMUuKe"
        + "nfg6TJSWSwIso40rohV9jkZQlVx4eQasNEvbi30gKpIjFAPUjp4vDfPLkXz6m5dBMUyFBSZz1CrQDywlG+SmsBL7"
        + "nVszExsOXKC5GThW3DEdxl3EX5fSYy5yvsK0lkrf1dMGddXTcvuvETWaevtVAgMBAAEwDQYJKoZIhvcNAQEFBQAD"
        + "gYEAKu/YRSa1cBkpZ7Z5poW83BLPQAMFiVlNBNiFz6ijETcvuT8sHIumNvBhrrhyB/WhrSb+WHR8MHFPHpuRirLg"
        + "kNUlAwdlXuq1/t4eZAkxbF0pd5wDe1UPKbytQPpwyUe2FswF2qVTLA7MPs53OnHzcoekrDLyvX/u3oR8usVnGWk=";

    /**
     * The certificate Instagram rotated to (CN=Meta Platforms Inc.), DER in Base64. Its SHA-256 is
     * 3a10c50c…fcb4c014. Android 13 and newer see it as the signer, with the first one before it in
     * the signing history.
     */
    private static final String ROTATED_CERTIFICATE =
        "MIIFxTCCA62gAwIBAgIUb4xy1XKIl+SvqggzYlIIznmgYyUwDQYJKoZIhvcNAQELBQAwgYkxHDAaBgNVBAMME01l"
        + "dGEgUGxhdGZvcm1zIEluYy4xFDASBgNVBAsMC01ldGEgTW9iaWxlMRwwGgYDVQQKDBNNZXRhIFBsYXRmb3JtcyBJ"
        + "bmMuMRMwEQYDVQQHDApNZW5sbyBQYXJrMRMwEQYDVQQIDApDYWxpZm9ybmlhMQswCQYDVQQGEwJVUzAgFw0yNTAz"
        + "MDYyMjI2NTdaGA8yMDU1MDMwNjIyMjY1N1owgYkxHDAaBgNVBAMME01ldGEgUGxhdGZvcm1zIEluYy4xFDASBgNV"
        + "BAsMC01ldGEgTW9iaWxlMRwwGgYDVQQKDBNNZXRhIFBsYXRmb3JtcyBJbmMuMRMwEQYDVQQHDApNZW5sbyBQYXJr"
        + "MRMwEQYDVQQIDApDYWxpZm9ybmlhMQswCQYDVQQGEwJVUzCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIB"
        + "AMBQkySH5FNHvQNDuIMAzNCjI3cfnCUj21mdAzu4unF4ktUNggOMq1Edkh9rJqmPSCs4nftEMEP6OMy913pjFzku"
        + "KSet7k8owQVc/8nw3v6LApRvu4TKRGa5OM33H2YVoeUpXa+N519F4AfVhlFj14zhQf2lqGWLAmcLLVIXiLVu7TfK"
        + "tFda4vgVks2MM/t0B2+UNuXdQbvBDnMODlDHJcgyyUHwDrQ14C6Iokrd4HbFvPzRJ4fwMybiUZhX7VU5Rpq0HDMD"
        + "nL0s8++NTV8nkfkrNcrJNHbB8j+aOYX4U3JG2WNQMh0XMYj54Rv8RiTs0xnDDO/jT7rxQwPaDgCh28+A0LR1o17j"
        + "IsBDGNGZIVRxbps1L5UTzLIeqmxBsTWIF73rQW1J4ta0oGXF/WikHpNU5P9L1VyD4mo0FKKhc2Wevw/aFR0lyhFH"
        + "RAIbqNZdRUPeN/ik7SkMrYSPzFbBQ0j2hcRvjP1szugsucllAFaXWkyAvsdFgcycekfTMqexsXWPsxsVNwHps18B"
        + "9wVQJg9yo+CUwvhDWffLRw+2+Jxj0dqj5HndXTwJ0WIkHrqftEGbcmN6KS0OjMpKYZsMrVTAaonu9Err5Afwva2m"
        + "uppB7H8Yus3Uf5xFBO3HN7zKef6twUM5dxvYfU/+eA7E7pcaKtufPRuKc7/x/nAA/sQBAgMBAAGjITAfMB0GA1Ud"
        + "DgQWBBTmfUsLBke1fodbwdp0IlCEIFuNqzANBgkqhkiG9w0BAQsFAAOCAgEAMR0sxIrV6pdCKsEeOk53UfmFIFsS"
        + "wiufWSiNDQaHM3DEV4XyVFKz3dBDWofgKCDCSCMbzMoBFQAGyGpoAaOaqtkAG3aP9AMr3q6Gr31Yj2h193HivOlF"
        + "zRfx0UHuaV4FZ8iyGiMR68ctgVtgS42RRNdwn/cJy+BgDS+rc+t56BMLOSNSiJTo0M7QBVY1XzjwHWKA4cTC+cbg"
        + "qYJEPo+AYlf7iWTmniLe9e5oUVeld2XSLBoddW4P1oiZNU3jCGbm3/ei+8OWmGfFDEwO1HebVKcFMfA2kXDikb2I"
        + "VA/MhDV06yrpk5FCymQoWR9YrRiV8gE3sAcpqJVPTAHlljPc14QPrCzOF2ZckCCNLgS8E/16h6rsg+vNHbt5j0hk"
        + "zr8xT841Y6sDqNHLfFwOl5huf+KPi5c6lyp88whlz2YWfjPMwCD9cxjgE9qEaUoLvgHUW78TZ1ePpHdw2buOr3im"
        + "oSy0bZPG03d7y4nnvKqO/YiL3aL1MZZ39eG/Mz7WzND4gv9pAFq50gLSt87HyNImICem35KOF5ZPDRI5pWQB6FKQ"
        + "4YBbXJ3FBCv+VH/LeDYBKda/GknQH5ZCu0vf6bPFuXjYsMfZlQMY4TkwpfSHwfTcnA2hWNvc/p4KN2i+BDQhZamc"
        + "4QrP9dH86JW/n5UoPMHP4B9uJzBkffMtqjUmHpg=";

    /**
     * Threads' original signing certificate (CN=Meta Platforms Inc.), DER in Base64: its APK v3.0
     * signer and the first of its v3.1 lineage. Its SHA-256 is 5367570b…58b432, which Instagram
     * 449's trust table holds as U2dXC61IjY2moPq3jZdmoaTCPDxw-sCtLpHI8L1YtDI.
     */
    private static final String THREADS_CERTIFICATE =
        "MIIFnTCCA4UCFDFg4fUMGFm6i3t6zwZ/B4y/z5WcMA0GCSqGSIb3DQEBCwUAMIGJMRwwGgYDVQQDDBNNZXRhIFBs"
        + "YXRmb3JtcyBJbmMuMRQwEgYDVQQLDAtNZXRhIE1vYmlsZTEcMBoGA1UECgwTTWV0YSBQbGF0Zm9ybXMgSW5jLjET"
        + "MBEGA1UEBwwKTWVubG8gUGFyazETMBEGA1UECAwKQ2FsaWZvcm5pYTELMAkGA1UEBhMCVVMwIBcNMjMwMTI2MTA1"
        + "NzM2WhgPMjA1MzAxMjUxMDU3MzZaMIGJMRwwGgYDVQQDDBNNZXRhIFBsYXRmb3JtcyBJbmMuMRQwEgYDVQQLDAtN"
        + "ZXRhIE1vYmlsZTEcMBoGA1UECgwTTWV0YSBQbGF0Zm9ybXMgSW5jLjETMBEGA1UEBwwKTWVubG8gUGFyazETMBEG"
        + "A1UECAwKQ2FsaWZvcm5pYTELMAkGA1UEBhMCVVMwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQCsoLaD"
        + "gbhdqVNCjsfpVafcT/rEigcJiNPBcPEqJ+vbPgmSMJCTk2v3GH/fPgmncPmPGEY+DVSmv/O/MR0h0Ss1VlugaUnY"
        + "oLbiAUx+ySPD/SbSyJm6vuynrP1a+2a7PgWHYFEoZFYS7rRYjfGX2Un08wX9e4nv3xRu3xsrzmDsBp8To9AcpKMJ"
        + "qzUNeg/PlDEJ3yLb3mCKVbHcu3p8XP0tOI3ean/b46KS2/yt1Fg9jJdO0/KDPjqf6m6sKZ0Tl341tIowZhezd77s"
        + "miHagpAdX2eKjkvg1WNscc0ndd0jNpAkE3laQe398dC1cdzl+EHuKc9WoyWrMzQ+mpIi27GO1MWky042Dkx4Gqcs"
        + "sgJ5+vLqXGGnYuy28Sfdf61tM7SfwjhHVtvPVdHAWaqu9HzOczepLZ/7AI1zR03HwITfrTN4zwDAn1gf2L/f1l/h"
        + "KsjiFBK6o+R6Bh421FhkSbThP/DDr8cAYCDGYeEKPEuc/Qv+3xHVkPqt2txm/QxNhTNjFID5rIoy48R0foPn/pqz"
        + "tN/vuXSV4Ylofs4m/ehGoUGSrIO0X4Nw8/YqDQWiIw33MGYQYoPvgGT9hLApMqbWsDzhXuVtNyl9m+03B/17/t4F"
        + "i9W1Nd4aKwL3xiEXU746AOwkF3ZGQt61Kq3UwfM8CFBV+0TRA5eozStwYUmeNQIDAQABMA0GCSqGSIb3DQEBCwUA"
        + "A4ICAQBL8A9YqfUoYASGERvK9BSpLpa1/Cu0rwWaxxUUHVZQsuSKcTbpfMcfmebfKi6bz+pNL8L8THMCYocIT6yT"
        + "2wpcba4LOybGmZ0mt4f6reA0paQg/REUy7qR7tNZnHgqAliSRY7CjvVejthrIh98v0maYAY70ypr0nHJdBLb3B1I"
        + "LGLl9tewQJjUo8QGpp9bkWcTTVsEEC93ynk30mC2HX9vno/dRief2JYrVfAnkBexNVgYWr92860HFp5P2mo36S9c"
        + "GflgfJkmDHmKknH3mY/9ppIiIzApCxBHPO5r1R/89y+TxaASSr1/s5RCOBtif0eG6GGRYcSQWPinCMjh8tORlhzn"
        + "dauxrI3Dm931rKqSfg8vtgcCH/TpUPYe0oX1p4ZcxIn6EsbjaQNzfpq4/OgOFpZLid6EaUBxd1/NyPnYNWEKgk7M"
        + "pEkBgDRQWCIOKJgoGG2GwlIX8S84uR0gNUILBe/M+bQjtzm6sHREl/X5JIHid6XPVNBi5zvnqa7286IyEoBjGVFF"
        + "VVC2pGA+4Z8PN+RNBqCM9oJmZIkXCdK4pw7nxxTgx/pty1KPCPVAbNCFMn/NiLOHWdIKBTRX4br0nfjsttWWtGxQ"
        + "ji7UP0D0TfXqlh48Y3K+Ej28GvV6h5zsMFJMgkCWG/ZuDafwHigzu+1fnQbMCcecA1WEtI7eKw==";

    /**
     * Facebook's original signing certificate (CN=Facebook Corporation), DER in Base64. Meta signs
     * Messenger with it too. Its SHA-256 is e3f9e1e0…7ed0fdc1, which Instagram 449's trust table
     * holds as 4_nh4M-Z0OVqBVumXiQbM5n3zqUkMmsM3W7BMn7Q_cE.
     */
    private static final String FACEBOOK_CERTIFICATE =
        "MIICaDCCAdECBEqcRhAwDQYJKoZIhvcNAQEEBQAwejELMAkGA1UEBhMCVVMxCzAJBgNVBAgTAkNBMRIwEAYDVQQH"
        + "EwlQYWxvIEFsdG8xGDAWBgNVBAoTD0ZhY2Vib29rIE1vYmlsZTERMA8GA1UECxMIRmFjZWJvb2sxHTAbBgNVBAMT"
        + "FEZhY2Vib29rIENvcnBvcmF0aW9uMCAXDTA5MDgzMTIxNTIxNloYDzIwNTAwOTI1MjE1MjE2WjB6MQswCQYDVQQG"
        + "EwJVUzELMAkGA1UECBMCQ0ExEjAQBgNVBAcTCVBhbG8gQWx0bzEYMBYGA1UEChMPRmFjZWJvb2sgTW9iaWxlMREw"
        + "DwYDVQQLEwhGYWNlYm9vazEdMBsGA1UEAxMURmFjZWJvb2sgQ29ycG9yYXRpb24wgZ8wDQYJKoZIhvcNAQEBBQAD"
        + "gY0AMIGJAoGBAMIH1R3464yX2TugyMEALJKPqwDcG0L8peZumcwwI+0tIU2CK8WejjXdz19Ex66K3lDX4MQ09QDm"
        + "wTH0ooNPmH/EZAYRXeIBjruw1aPCYb2XWBzP73avxxNabVnohV7NfqzI+HN+eUxgp2HFNrcrEfrI5gP12hotVKoQ"
        + "O4oTwNvBAgMBAAEwDQYJKoZIhvcNAQEEBQADgYEAXum+i8uyUGSNO3QSkKgqHJ3C52oK8vIijx2fnEAHUpxEanAX"
        + "XFqQDVFBgShm20a+ZVniFBYWSDmYIR9KZzFJ+yIyoQ0kdmOyapAx4V+EvBx00UH/mKAtdvhbLIqyVxtkabIy2Odo"
        + "p/fKBPer5Kd1YVkWwHlAZWtYcXRXtCvZKKI=";

    private static volatile List<Signature> original;

    private static volatile List<Signature> threads;

    private static volatile List<Signature> facebook;

    /**
     * This build's real current signers, kept the first time they're seen. Android can't change a
     * running app's certificate without starting a new process. Package-visible for the tests.
     */
    static volatile Signature[] ownSigners;

    /**
     * Instagram's signing history, oldest first, if [info] is this app; the app's own Meta
     * certificate if [info] is a Threads, Facebook or Messenger signed with this build's key; or
     * {@code null} to keep the signers the system reports. The history is what Android 13 and newer
     * report for the Play build, and Instagram's trust table holds both certificates.
     */
    public static List<Signature> originalSigners(PackageInfo info) {
        if (isThisApp(info)) {
            // The name is a compile-time constant: this can run while content providers start,
            // before HushGram has a context, and Hook status reads no setting.
            HookStatus.invoked(FamilyNames.RESTORE_TRUST);
            // Only a record the uid proved is this app vouches for its signers. One that only
            // carries the name could be built by anyone, so the family check reads them from
            // PackageManager instead.
            if (isThisAppByUid(info)) rememberOwnSigners(info);
            return instagram();
        }
        return familyApp(info);
    }

    /**
     * A named family app calling a SameKey provider with this build's real current key. The patch
     * asks only after that provider's native policy returned false. Other provider policies never
     * reach this helper, and a refusal keeps the original decision.
     *
     * <p>The family signer answer above must stay specific to each app for deep links. Those
     * certificates differ from Instagram's original one, so they cannot satisfy a provider whose
     * policy requires the same key. Read the real current certificates here instead. The provider
     * already has its context before the application's onCreate, so this needs no setting or saved
     * signer and works during a cold provider start too.
     *
     * <p>Binder's uid is the principal. It must belong to exactly one named family package in this
     * Android user, and PackageManager must give that package the same uid. A shared or unreadable
     * uid, another current key, a Meta-signed build or any failure leaves the native decision alone.
     */
    public static boolean isSameKeyFamilyProviderCaller(@Nullable Context context) {
        try {
            String ownPackage = OwnPackage.name();
            if (context == null || Process.isIsolated() || !ownPackage.equals(context.getPackageName())) {
                return false;
            }
            int callerUid = Binder.getCallingUid();
            if (callerUid < 0 || !UserHandle.getUserHandleForUid(callerUid).equals(Process.myUserHandle())) {
                return false;
            }
            PackageManager packages = context.getPackageManager();
            if (packages == null) return false;
            String[] names = packages.getPackagesForUid(callerUid);
            if (names == null || names.length != 1 || metaSignersFor(names[0]) == null) return false;

            PackageInfo self = packages.getPackageInfo(ownPackage, PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo caller = packages.getPackageInfo(names[0], PackageManager.GET_SIGNING_CERTIFICATES);
            if (!packageUid(self, ownPackage, Process.myUid()) || !packageUid(caller, names[0], callerUid)) {
                return false;
            }
            Set<ByteBuffer> own = certificates(currentSigners(self));
            if (own.isEmpty()) return false;
            for (List<Signature> meta : Arrays.asList(instagram(), metaSignersFor(THREADS), metaSignersFor(FACEBOOK))) {
                for (Signature signer : meta) {
                    if (own.contains(ByteBuffer.wrap(signer.toByteArray()))) return false;
                }
            }
            Set<ByteBuffer> other = certificates(currentSigners(caller));
            if (other.isEmpty() || !own.equals(other)) return false;
            HookStatus.counted(FamilyNames.RESTORE_TRUST, "same-key provider caller");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RESTORE_TRUST, "same-key provider caller", failure);
            return false;
        }
    }

    /**
     * Whether this build carries a key of its own rather than one of Instagram's two Meta
     * certificates, going by its current signers: the ones kept from a record the uid proved is
     * this app, or else the ones [packages] reports, which the patch doesn't touch. False when
     * neither can be read, so the caller keeps Instagram's own path. A Root Mount install still
     * has Meta's key and gets false.
     */
    static boolean isReSigned(@Nullable PackageManager packages) throws PackageManager.NameNotFoundException {
        Signature[] ours = ownSigners;
        if (ours == null && packages != null) {
            ours = copyOf(currentSigners(packages.getPackageInfo(OwnPackage.name(), PackageManager.GET_SIGNING_CERTIFICATES)));
            if (ours != null) ownSigners = ours;
        }
        Set<ByteBuffer> own = certificates(ours);
        if (own.isEmpty()) return false;
        for (Signature instagram : instagram()) {
            if (own.contains(ByteBuffer.wrap(instagram.toByteArray()))) return false;
        }
        return true;
    }

    /** The system's package name and uid must agree with the principal being checked. */
    private static boolean packageUid(@Nullable PackageInfo info, String name, int uid) {
        ApplicationInfo app = info == null ? null : info.applicationInfo;
        return info != null && name.equals(info.packageName) && app != null
                && name.equals(app.packageName) && app.uid == uid;
    }

    /**
     * Keeps this build's real current signers from [info], the system's PackageInfo for this app,
     * before the answer replaces them, for the family app check. A record without SigningInfo
     * keeps nothing, so the check reads them from PackageManager. Never throws, so the answer for
     * this app stays what it always was.
     */
    private static void rememberOwnSigners(PackageInfo info) {
        if (ownSigners != null) return;
        try {
            ownSigners = copyOf(currentSigners(info));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RESTORE_TRUST, FAMILY_HOOK, failure);
        }
    }

    /**
     * The family app answer for [info], or {@code null} for Instagram's own path. Never throws: a
     * failure is reported under the patch and keeps Instagram's own answer.
     */
    private static List<Signature> familyApp(PackageInfo info) {
        try {
            if (info == null || metaSignersFor(info.packageName) == null) return null;
            Signature[] ours = ownSigners;
            if (ours == null) {
                ours = copyOf(signersFromPackageManager(null));
                ownSigners = ours;
            }
            Signature[] theirs = currentSigners(info);
            // Without SigningInfo the system filled only the signatures array, which holds the
            // oldest signer of a rotated app, so the package is read again for its current one.
            if (theirs == null) theirs = signersFromPackageManager(info.packageName);
            return familySigners(info.packageName, theirs, ours);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RESTORE_TRUST, FAMILY_HOOK, failure);
            return null;
        }
    }

    /**
     * The Meta certificate to answer for family app [packageName], whose current signers are
     * [theirs], on a build whose current signers are [ours], or {@code null} to keep Instagram's
     * own answer. It answers only when [packageName] is Threads, Facebook or Messenger, [ours] is
     * known and holds neither of Instagram's Meta certificates, and [theirs] is exactly [ours],
     * certificate for certificate. With several signers, every one has to match.
     */
    static List<Signature> familySigners(
            @Nullable String packageName, @Nullable Signature[] theirs, @Nullable Signature[] ours) {
        List<Signature> meta = metaSignersFor(packageName);
        if (meta == null) return null;

        Set<ByteBuffer> own = certificates(ours);
        if (own.isEmpty()) return refused("family app, own signers unknown");
        // Not re-signed: Instagram's own check already trusts the Meta-signed apps.
        for (Signature instagram : instagram()) {
            if (own.contains(ByteBuffer.wrap(instagram.toByteArray()))) {
                return refused("family app, Meta-signed build");
            }
        }
        Set<ByteBuffer> other = certificates(theirs);
        if (other.isEmpty() || !other.equals(own)) return refused("family app with another key");

        HookStatus.counted(FamilyNames.RESTORE_TRUST, "same-key family app");
        return meta;
    }

    private static List<Signature> refused(String reason) {
        HookStatus.counted(FamilyNames.RESTORE_TRUST, reason);
        return null;
    }

    /** The Meta certificate of family app [packageName], trusted one first, or null for any other. */
    @Nullable
    private static List<Signature> metaSignersFor(@Nullable String packageName) {
        if (THREADS.equals(packageName)) {
            List<Signature> signers = threads;
            if (signers == null) threads = signers = single(THREADS_CERTIFICATE);
            return signers;
        }
        if (FACEBOOK.equals(packageName) || MESSENGER.equals(packageName)) {
            List<Signature> signers = facebook;
            if (signers == null) facebook = signers = single(FACEBOOK_CERTIFICATE);
            return signers;
        }
        return null;
    }

    private static List<Signature> single(String certificate) {
        return Collections.singletonList(new Signature(Base64.decode(certificate, Base64.DEFAULT)));
    }

    /**
     * The current signers in [info]: the APK's signers from its SigningInfo, which are the newest
     * after a rotation and every signer when there are several. Null without SigningInfo. The old
     * signatures array never stands in: after a rotation it holds the oldest signer.
     */
    @Nullable
    static Signature[] currentSigners(@Nullable PackageInfo info) {
        if (info == null) return null;
        SigningInfo signing = info.signingInfo;
        if (signing == null) return null;
        Signature[] current = signing.getApkContentsSigners();
        return current == null || current.length == 0 ? null : current;
    }

    /**
     * The current signers of [packageName], or of this build when it's null, read straight from
     * PackageManager with their signing certificates, which the patch doesn't touch. Null without
     * a context. Utils.getContext logs when there's none yet, so a hook that runs before
     * HushGram's start doesn't ask it.
     */
    @Nullable
    private static Signature[] signersFromPackageManager(@Nullable String packageName)
            throws PackageManager.NameNotFoundException {
        if (!Utils.settingsReady()) return null;
        Context context = Utils.getContext();
        if (context == null) return null;
        PackageManager packages = context.getPackageManager();
        if (packages == null) return null;
        String name = packageName == null ? context.getPackageName() : packageName;
        return currentSigners(packages.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES));
    }

    /** A copy of [signers], or null when there are none, so an empty read is tried again later. */
    @Nullable
    private static Signature[] copyOf(@Nullable Signature[] signers) {
        return signers == null || signers.length == 0 ? null : signers.clone();
    }

    /** The certificate bytes of [signers], or an empty set when any of them is missing. */
    private static Set<ByteBuffer> certificates(@Nullable Signature[] signers) {
        if (signers == null || signers.length == 0) return Collections.emptySet();
        Set<ByteBuffer> certificates = new HashSet<>();
        for (Signature signer : signers) {
            if (signer == null) return Collections.emptySet();
            certificates.add(ByteBuffer.wrap(signer.toByteArray()));
        }
        return certificates;
    }

    /** Instagram's two certificates, read once. */
    private static List<Signature> instagram() {
        List<Signature> signers = original;
        if (signers == null) {
            signers = Collections.unmodifiableList(Arrays.asList(
                new Signature(Base64.decode(ORIGINAL_CERTIFICATE, Base64.DEFAULT)),
                new Signature(Base64.decode(ROTATED_CERTIFICATE, Base64.DEFAULT))));
            original = signers;
        }
        return signers;
    }

    /**
     * Whether [info] describes the running app. A package with another uid is another app,
     * whatever its name. PackageManager always fills in the ApplicationInfo; without one only the
     * name is known. The same goes for an isolated process: it runs under a uid of its own, not the
     * app's.
     */
    static boolean isThisApp(PackageInfo info) {
        if (info == null || info.packageName == null) return false;
        if (info.applicationInfo == null || Process.isIsolated()) return OwnPackage.name().equals(info.packageName);
        return isThisAppByUid(info);
    }

    /**
     * Whether the uid proves that [info] is the running app. It can't without an ApplicationInfo,
     * or in an isolated process, where [isThisApp] goes by the name alone.
     */
    private static boolean isThisAppByUid(PackageInfo info) {
        if (info == null || info.packageName == null || Process.isIsolated()) return false;
        ApplicationInfo app = info.applicationInfo;
        return app != null && app.uid == Process.myUid() && info.packageName.equals(app.packageName);
    }
}

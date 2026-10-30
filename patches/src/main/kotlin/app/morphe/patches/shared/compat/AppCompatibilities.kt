/*
 * Forked from:
 * https://github.com/icysymmetra/tiktok-patches-for-morphe/blob/main/patches/src/main/kotlin/app/morphe/patches/shared/compat/AppCompatibilities.kt
 *
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 *
 * Central Morphe `Compatibility` metadata so Morphe Manager shows human-readable app names.
 */
package app.morphe.patches.shared.compat

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

internal object AppCompatibilities {
    /** Instagram's magenta, the middle of its icon gradient. */
    private const val INSTAGRAM_COLOR = 0xE1306C

    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    /**
     * SHA-256 of Instagram's original signing certificate (CN=Kevin Systrom, O=Instagram Inc): the
     * "certificate SHA-256 digest" `apksigner verify --print-certs` prints for the v3.0 signer of a
     * genuine build, which Android 7 to 12L check. Instagram's own trust table holds it as
     * `Xz5Q9DVYPJrmJjAqcfc0AEQIen4sYK2s_CVCBamT4wU`. Morphe Manager holds an APK picked from storage
     * to these and warns before patching one another key signed.
     */
    const val INSTAGRAM_SIGNER_SHA256 = "5f3e50f435583c9ae626302a71f7340044087a7e2c60adacfc254205a993e305"

    /**
     * SHA-256 of Meta's newer certificate (CN=Meta Platforms Inc.), the APK Signature Scheme v3.1
     * signer Android 13 and newer check. Its lineage is authorized by the original key, and
     * Instagram's trust table holds it as `OhDFDBi6k3UGwodekEe-dPj9i4b64v-f6qUKgfy0wBQ`.
     */
    const val META_ROTATED_SIGNER_SHA256 = "3a10c50c18ba937506c2875e9047be74f8fd8b86fae2ff9feaa50a81fcb4c014"

    /** The Instagram build every patch here was applied to and read against. */
    const val INSTAGRAM_TARGET_VERSION = "453.0.0.57.79"

    /**
     * The version code of the arm64-v8a build of [INSTAGRAM_TARGET_VERSION]: APKMirror's
     * (arm64-v8a) (480-640dpi) (Android 9.0+) bundle of that release, the one the fixtures hold.
     */
    const val INSTAGRAM_TARGET_VERSION_CODE = 385914557

    /**
     * The other declared builds of [INSTAGRAM_TARGET_VERSION], one per ABI, each with its own
     * version code. Manager is told about every one, so a 32-bit phone is pointed at the
     * armeabi-v7a build rather than the arm64 one, which it can't install (#346).
     *
     * Instagram ships 453.0.0.57.79 as eight builds (arm64 385914517, 385914546 and 385914557;
     * armeabi-v7a 385914503, 385914510 and 385914556; x86 385914518 and 385914558). Manager takes
     * one code per ABI, so each ABI's highest code is declared, the one Play hands that ABI, and
     * the fixture tests read every build. No x86_64 build was uploaded for this release, so none
     * is declared; an x86_64 device with an arm translator runs the arm64 build.
     */
    const val INSTAGRAM_TARGET_VERSION_CODE_ARMEABI_V7A = 385914556
    const val INSTAGRAM_TARGET_VERSION_CODE_X86 = 385914558

    /** Each declared ABI's build of [INSTAGRAM_TARGET_VERSION]. */
    val INSTAGRAM_TARGET_VERSION_CODES = mapOf(
        SupportedAbi.ARM64_V8A to INSTAGRAM_TARGET_VERSION_CODE,
        SupportedAbi.ARMEABI_V7A to INSTAGRAM_TARGET_VERSION_CODE_ARMEABI_V7A,
        SupportedAbi.X86 to INSTAGRAM_TARGET_VERSION_CODE_X86,
    )

    /** Instagram's own floor, Android 9. */
    const val INSTAGRAM_TARGET_MIN_SDK = 28

    /** Every ABI loading HushGram's extensions needs Android 9 APIs. */
    const val INSTAGRAM_RUNTIME_MIN_SDK = 28

    fun instagram(): Array<Compatibility> = arrayOf(
        Compatibility(
            name = "Instagram",
            packageName = INSTAGRAM_PACKAGE,
            apkFileType = ApkFileType.APK,
            appIconColor = INSTAGRAM_COLOR,
            signatures = setOf(INSTAGRAM_SIGNER_SHA256, META_ROTATED_SIGNER_SHA256),
            targets = listOf(
                AppTarget(
                    version = INSTAGRAM_TARGET_VERSION,
                    versionCodes = INSTAGRAM_TARGET_VERSION_CODES,
                    minSdk = INSTAGRAM_TARGET_MIN_SDK,
                ),
            ),
        ),
    )
}

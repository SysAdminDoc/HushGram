/*
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.shared.compat

import app.morphe.Fixtures
import app.morphe.patcher.patch.SupportedAbi
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signing certificates and version code the bundle declares have to be the ones on Instagram's
 * own builds. Morphe Manager warns about a picked APK whose certificate differs and treats a
 * version code it was not told about as unsupported, so a wrong value here would warn every user
 * who downloaded the genuine release.
 */
class AppCompatibilitiesMatchFixturesTest {

    @Test
    fun `every retained vendor build is signed by a declared certificate`() {
        val declared = AppCompatibilities.instagram().single().signatures.orEmpty()
        assertEquals(
            "declared signatures",
            setOf(AppCompatibilities.INSTAGRAM_SIGNER_SHA256, AppCompatibilities.META_ROTATED_SIGNER_SHA256),
            declared,
        )
        val undeclared = mutableMapOf<String, Set<String>>()
        var checked = 0
        for (fixture in Fixtures.files { it.extension == "apk" || it.extension == "apks" || it.extension == "xapk" }) {
            apksIn(fixture) { name, apk ->
                val result = ApkVerifier.Builder(apk).build().verify()
                assertTrue("$name does not verify: ${result.errors}", result.isVerified)
                // v3.1 rotation puts the newer key in its own block, for Android 13 and newer, so
                // the certificates of every v3 and v3.1 signer count beside the headline ones.
                val signers = (result.signerCertificates +
                    result.v3SchemeSigners.flatMap { it.certificates } +
                    result.v31SchemeSigners.flatMap { it.certificates })
                    .map { sha256(it.encoded) }.toSet()
                assertTrue("$name carries no signer", signers.isNotEmpty())
                val other = signers - declared
                if (other.isNotEmpty()) undeclared[name] = other
                checked++
            }
        }
        assertTrue("no fixture APK was checked", checked > 0)
        assertEquals("fixtures a certificate the bundle does not declare signed",
            emptyMap<String, Set<String>>(), undeclared)
    }

    @Test
    fun `every declared target carries the version code and floor of its vendor build`() {
        val targets = AppCompatibilities.instagram().single().targets
        assertEquals(
            "declared versions, newest first",
            listOf(AppCompatibilities.INSTAGRAM_TARGET_VERSION),
            targets.map { it.version },
        )
        var checked = 0
        for (target in targets) {
            val version = checkNotNull(target.version)
            val codes = checkNotNull(target.versionCodes) { "the $version target declares no version codes" }
            val fixtures = Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }
            for (fixture in fixtures) {
                ZipFile(fixture).use { zip ->
                    val base = checkNotNull(zip.getEntry("base.apk")) { "${fixture.name} holds no base.apk" }
                    val copy = File.createTempFile("fixture-base", ".apk")
                    try {
                        zip.getInputStream(base).use { input -> copy.outputStream().use { input.copyTo(it) } }
                        // Each read walks the buffer to its end, so each one gets its own view.
                        val manifest = RandomAccessFile(copy, "r").use { file ->
                            ApkUtils.getAndroidManifest(DataSources.asDataSource(file))
                        }
                        assertEquals(
                            "${fixture.name} version code",
                            codes[SupportedAbi.ARM64_V8A],
                            ApkUtils.getVersionCodeFromBinaryAndroidManifest(manifest.duplicate()),
                        )
                        assertEquals(
                            "${fixture.name} minSdk",
                            target.minSdk,
                            ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(manifest.duplicate()),
                        )
                        checked++
                    } finally {
                        copy.delete()
                    }
                }
            }
        }
        assertEquals("one retained fixture for each declared target", targets.size, checked)
    }

    @Test
    fun `every ABI is declared with the version code of its own vendor build`() {
        // A 32-bit phone told only about the arm64 build gets an APK with no library it can load
        // and fails with INSTALL_FAILED_NO_MATCHING_ABIS (#346), so each ABI Instagram ships needs
        // its own code, and each code has to be the one on that ABI's genuine build.
        // No x86_64 build of 453.0.0.57.79 was uploaded, so none is declared.
        val libraryFolders = mapOf(
            SupportedAbi.ARM64_V8A to "arm64-v8a",
            SupportedAbi.ARMEABI_V7A to "armeabi-v7a",
            SupportedAbi.X86 to "x86",
        )
        for (target in AppCompatibilities.instagram().single().targets) {
            val version = checkNotNull(target.version)
            val codes = target.versionCodes.orEmpty()
            assertEquals("ABIs declared for $version", libraryFolders.keys, codes.keys)
            for ((abi, code) in codes) {
                val base = File(Fixtures.folder(), "instagram-$version-$code/base.apk")
                assertTrue("no fixture build for $abi at $base", base.isFile)
                val manifest = RandomAccessFile(base, "r").use { file ->
                    ApkUtils.getAndroidManifest(DataSources.asDataSource(file))
                }
                assertEquals("$abi build version code", code, ApkUtils.getVersionCodeFromBinaryAndroidManifest(manifest))
                val libraries = ZipFile(base).use { zip ->
                    zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }
                        .map { it.split('/')[1] }.toSet()
                }
                assertEquals("$abi build native library folders", setOf(libraryFolders.getValue(abi)), libraries)
            }
        }
    }

    /** Every APK a fixture holds: itself, or each split inside an .apks or .xapk, copied out to a temp file. */
    private fun apksIn(fixture: File, check: (String, File) -> Unit) {
        if (fixture.extension == "apk") return check(fixture.name, fixture)
        ZipFile(fixture).use { zip ->
            val apks = zip.entries().asSequence().filter { it.name.endsWith(".apk") }.toList()
            assertTrue("${fixture.name} holds no APK", apks.isNotEmpty())
            for (entry in apks) {
                val copy = File.createTempFile("fixture-split", ".apk")
                try {
                    zip.getInputStream(entry).use { input -> copy.outputStream().use { input.copyTo(it) } }
                    check("${fixture.name}!${entry.name}", copy)
                } finally {
                    copy.delete()
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

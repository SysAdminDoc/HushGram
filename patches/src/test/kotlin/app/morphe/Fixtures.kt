/*
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe

import app.morphe.patches.shared.compat.AppCompatibilities
import java.io.File
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue

/**
 * The vendor Instagram builds the fixture tests read, from the folder `HUSHGRAM_FIXTURE_DIR`
 * names.
 *
 * Unset, every fixture test skips and says which variable to set: the APKs are hundreds of
 * megabytes each and are not in the repository. Set, the folder has to hold what the test reads.
 * A variable pointing at a missing or empty folder is a broken setup, and skipping there would
 * read as a pass. There is no default folder: one used to be a path on the maintainer's machine,
 * which skipped quietly everywhere else and published that machine's layout.
 */
internal object Fixtures {
    const val VARIABLE = "HUSHGRAM_FIXTURE_DIR"

    /** The files in the fixture folder that [accept] takes, sorted by name. */
    fun files(accept: (File) -> Boolean): List<File> {
        val directory = folder()
        val found = directory.listFiles()?.filter { it.isFile && accept(it) }?.sortedBy { it.name }.orEmpty()
        if (found.isEmpty()) fail("$VARIABLE names $directory, which holds none of the Instagram files this test reads.")
        return found
    }

    /**
     * The base APKs of the other builds of each declared Instagram version, sorted by build. Each
     * is in a folder named `instagram-<version>-<version code>` in the fixture folder, and the
     * declared build's own folder is left out. Instagram ships one version as several builds
     * (450.0.0.50.77 has five for arm64), each compiled on its own, and the patches find what
     * they change by its shape, so these are read too (#77).
     */
    fun otherBuilds(): List<File> {
        val directory = folder()
        val folders = directory.listFiles()?.filter { it.isDirectory }.orEmpty()
        val found = AppCompatibilities.instagram().flatMap { it.targets }.flatMap { target ->
            val version = target.version ?: return@flatMap emptyList()
            val declared = target.versionCodes.orEmpty().values.mapTo(HashSet()) { "instagram-$version-$it" }
            folders.filter { it.name.startsWith("instagram-$version-") && it.name !in declared }.map { File(it, "base.apk") }.filter { it.isFile }
        }.sortedBy { it.parentFile.name }
        if (found.isEmpty()) {
            fail("$VARIABLE names $directory, which holds no instagram-<version>-<version code> folder with another build's base.apk.")
        }
        return found
    }

    private fun folder(): File {
        val configured = System.getenv(VARIABLE)
        assumeTrue(
            "$VARIABLE is not set, so the Instagram fixture tests skip. Point it at the folder " +
                "that holds the vendor Instagram APKs to run them.",
            !configured.isNullOrBlank(),
        )
        val directory = File(configured!!)
        if (!directory.isDirectory) fail("$VARIABLE names $directory, which is not a folder.")
        return directory
    }

    /** Every Instagram APK or bundle in the fixture folder. */
    fun apks(): List<File> = files { it.extension == "apk" }
}

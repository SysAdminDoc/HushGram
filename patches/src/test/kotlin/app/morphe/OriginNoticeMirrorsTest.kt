/*
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every mirror link in a source notice names a file GitLab has.
 *
 * <p>The notices that cite `github.com/ReVanced/revanced-patches` point at a repository GitHub
 * has blocked under a DMCA notice since 2026-03-24, and CONTRIBUTING keeps those lines as the
 * record of which revision the code came from. The line beside each is the GitLab mirror, which
 * is the one a reader can open. This reads the mirror's file list once per revision, so the next
 * takedown is found by the suite rather than by a reader, and it is the only test here that needs
 * the network: a machine with none fails it by name rather than passing over it.
 *
 * <p>Asking for each link wouldn't settle it. GitLab sends a request for a file its branch doesn't
 * have to the repository's front page, which answers 200, and two notices inherited from the
 * TikTok source named ReVanced paths that never existed that way (found 2026-09-30).
 *
 * <p>The read goes over git because GitLab's web addresses allow an address about 60 requests an
 * hour with every other check on the machine in the same count, and a 429 says nothing about the
 * file (release gate 2026-10-31: this test failed 35 minutes in with 20 minutes left on the limit).
 * Git isn't in that count. The clone brings one commit and its file lists, no file's contents.
 */
class OriginNoticeMirrorsTest {
    @Test
    fun `every mirror cited by a source notice names a file GitLab has`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "extensions").isDirectory && File(it, "CONTRIBUTING.md").isFile }
        val mirrors = listOf(File(root, "extensions"), File(root, "patches/src")).asSequence()
            .flatMap { tree -> tree.walkTopDown().onEnter { it.name != "build" } }
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .flatMap { file -> MIRROR.findAll(file.readText()).map { it.value } }
            .toSortedSet()
        assertTrue("no source notice cites a mirror, so the block has nothing beside it", mirrors.isNotEmpty())

        val unreadable = mirrors.filter { BLOB.matchEntire(it) == null }
        assertTrue("mirrors that name no file at a revision:\n" + unreadable.joinToString("\n"), unreadable.isEmpty())

        val missing = mirrors.groupBy { BLOB.matchEntire(it)!!.groupValues[1] }.flatMap { (revision, links) ->
            val files = filesAt(REMOTE, revision)
            links.filter { BLOB.matchEntire(it)!!.groupValues[2] !in files }
        }
        assertTrue("mirrors GitLab has no such file for:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun `a file counts only at the revision that has it`() {
        val repository = Files.createTempDirectory("mirror-repository").toFile()
        try {
            git(repository, "init", "-q", "-b", "main")
            addFile(repository, "extensions/old.kt")
            git(repository, "checkout", "-q", "-b", "later")
            addFile(repository, "extensions/new.kt")

            assertEquals(setOf("extensions/old.kt"), filesAt(repository.path, "main"))
            assertEquals(setOf("extensions/old.kt", "extensions/new.kt"), filesAt(repository.path, "later"))
            val failure = runCatching { filesAt(repository.path, "gone") }.exceptionOrNull()
            assertTrue("a revision the mirror lacks passed: $failure", failure is AssertionError && "gone" in failure.message.orEmpty())
        } finally {
            repository.deleteRecursively()
        }
    }

    private fun addFile(repository: File, path: String) {
        File(repository, path).apply { parentFile.mkdirs() }.writeText("fixture")
        git(repository, "add", "--", path)
        git(repository, "-c", "user.name=fixture", "-c", "user.email=fixture@example.test", "-c", "commit.gpgsign=false",
            "commit", "-q", "--no-verify", "-m", path)
    }

    /** Every path at a branch or tag, read from a clone that holds no file's contents. */
    private fun filesAt(remote: String, revision: String): Set<String> {
        val clone = Files.createTempDirectory("mirror-tree").toFile()
        try {
            git(clone, "clone", "-q", "--bare", "--depth=1", "--filter=blob:none", "--single-branch", "--branch", revision, remote, ".")
            return git(clone, "ls-tree", "-r", "--name-only", "-z", "HEAD").split(Char(0)).filter { it.isNotEmpty() }.toSet()
        } finally {
            clone.deleteRecursively()
        }
    }

    /**
     * What git printed. A failure throws with what it said. The hook that runs a release gate leaves
     * GIT_ variables behind that would point this at the repository being pushed, and a credential
     * helper or a prompt would stop a run nobody is watching, so all of those are cleared.
     */
    private fun git(directory: File, vararg arguments: String): String {
        val printed = File.createTempFile("mirror-git", ".out")
        val said = File.createTempFile("mirror-git", ".err")
        try {
            val builder = ProcessBuilder(listOf("git", "-c", "credential.helper=") + arguments)
                .directory(directory)
                .redirectOutput(printed)
                .redirectError(said)
            builder.environment().keys.removeAll { it.startsWith("GIT_") }
            builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
            val process = builder.start()
            process.outputStream.close()
            if (!process.waitFor(GIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw AssertionError("git ${arguments.joinToString(" ")} took over $GIT_TIMEOUT_MINUTES minutes")
            }
            if (process.exitValue() != 0) throw AssertionError("git ${arguments.joinToString(" ")} failed:\n${said.readText()}")
            return printed.readText()
        } finally {
            printed.delete()
            said.delete()
        }
    }

    private companion object {
        val MIRROR = Regex("""https://gitlab\.com/ReVanced/revanced-patches/[^\s"]+""")
        val BLOB = Regex("""https://gitlab\.com/ReVanced/revanced-patches/-/blob/([^/]+)/([^#?]+)(?:[#?].*)?""")
        const val REMOTE = "https://gitlab.com/ReVanced/revanced-patches.git"
        const val GIT_TIMEOUT_MINUTES = 2L
    }
}

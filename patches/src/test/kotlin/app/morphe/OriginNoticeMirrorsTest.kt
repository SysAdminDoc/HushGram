/*
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every mirror URL in a source notice answers.
 *
 * <p>The notices that cite `github.com/ReVanced/revanced-patches` point at a repository GitHub
 * has blocked under a DMCA notice since 2026-03-24, and CONTRIBUTING keeps those lines as the
 * record of which revision the code came from. The line beside each is the GitLab mirror, which
 * is the one a reader can open. This fetches each distinct mirror once, so the next takedown is
 * found by the suite rather than by a reader, and it is the only test here that needs the
 * network: a machine with none fails it by name rather than passing over it.
 *
 * <p>A mirror has to answer at its own address. GitLab sends a request for a file its branch
 * doesn't have to the repository's front page, which answers 200, and two notices inherited from
 * the TikTok source named ReVanced paths that never existed that way (found 2026-09-30).
 *
 * <p>GitLab answers a burst of these with 429 Too Many Requests, which says nothing about the
 * file (preflight 2026-10-18, with the other Hush repos checking the same mirrors). The requests
 * are spaced, and a 429 is asked again after the wait GitLab names, a few times at most; a mirror
 * still turning the check away after that fails like any other.
 */
class OriginNoticeMirrorsTest {
    @Test
    fun `every mirror cited by a source notice answers 200`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "extensions").isDirectory && File(it, "CONTRIBUTING.md").isFile }
        val mirrors = listOf(File(root, "extensions"), File(root, "patches/src")).asSequence()
            .flatMap { tree -> tree.walkTopDown().onEnter { it.name != "build" } }
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .flatMap { file -> MIRROR.findAll(file.readText()).map { it.value } }
            .toSortedSet()
        assertTrue("no source notice cites a mirror, so the block has nothing beside it", mirrors.isNotEmpty())

        val failures = mirrors.mapNotNull { mirror ->
            Thread.sleep(SPACING_MS)
            val status = runCatching { statusAfterThrottling(mirror) }.getOrElse { error -> "no answer: ${error.message}" }
            if (status == "200") null else "$status  $mirror"
        }
        assertTrue("mirrors that do not answer 200:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `a wait GitLab names is kept within the cap`() {
        assertEquals(7_000L, waitMillis("7"))
        assertEquals(MAX_WAIT_MS, waitMillis("600"))
        assertEquals(DEFAULT_WAIT_MS, waitMillis(null))
        assertEquals(DEFAULT_WAIT_MS, waitMillis("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    private fun statusAfterThrottling(url: String): String {
        var answer = status(url)
        repeat(THROTTLED_TRIES - 1) {
            if (answer.first != "429") return answer.first
            Thread.sleep(waitMillis(answer.second))
            answer = status(url)
        }
        return answer.first
    }

    private fun waitMillis(retryAfter: String?): Long {
        val seconds = retryAfter?.trim()?.toLongOrNull() ?: return DEFAULT_WAIT_MS
        return (seconds * 1_000).coerceIn(0, MAX_WAIT_MS)
    }

    /** The status, or where a redirect sent it, and the Retry-After header that came with it. */
    private fun status(url: String): Pair<String, String?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "hushgram-origin-notice-check")
        return try {
            val status = connection.responseCode.toString()
            val retryAfter = connection.getHeaderField("Retry-After")
            (if (connection.url.toString() != url) "sent on to ${connection.url}" else status) to retryAfter
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val MIRROR = Regex("""https://gitlab\.com/ReVanced/revanced-patches/[^\s"]+""")
        const val SPACING_MS = 400L
        const val THROTTLED_TRIES = 4
        const val DEFAULT_WAIT_MS = 10_000L
        const val MAX_WAIT_MS = 60_000L
    }
}

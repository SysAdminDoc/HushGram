/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.extension

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.sharelinks.SHARE_LINK_TARGETS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetCoverageTest {
    @Test
    fun partialCoverageKeepsOnlyFixedLabelsAndActualCounts() {
        val visited = mutableListOf<String>()
        var recorded: TargetCoverage? = null
        val matched = handleTargets("Example", "targets", listOf("alpha", "beta", "gamma"), coverage = { recorded = it }) {
            visited += it
            if (it == "beta") "a dynamic method or address was not found" else null
        }
        assertEquals(listOf("alpha", "beta", "gamma"), visited)
        assertEquals(2, matched)
        assertEquals("1|2|3|alpha,beta,gamma|beta", recorded!!.encode())
        assertFalse(recorded.encode().contains("address"))
    }

    @Test
    fun noCoverageFailsAndDoesNotPublishMetadata() {
        var recorded = false
        assertThrows(PatchException::class.java) {
            handleTargets("Example", "targets", listOf("alpha", "beta", "gamma"), coverage = { recorded = true }) {
                "$it is absent"
            }
        }
        assertFalse(recorded)
    }

    @Test
    fun invalidLabelsFailBeforeAnyTargetIsMutated() {
        for (labels in listOf(listOf("alpha", "alpha"), listOf("https://host/account"), emptyList())) {
            var visited = false
            assertThrows(IllegalArgumentException::class.java) {
                handleTargets("Example", "targets", labels) { visited = true; null }
            }
            assertFalse(visited)
        }
    }

    /** Audit A03: each link target left out on its own still applies the rest and says so. */
    @Test
    fun everyRealTargetCanBeMissingOnItsOwn() {
        for (left in SHARE_LINK_TARGETS) {
            var recorded: TargetCoverage? = null
            val matched = handleTargets("Example", "targets", SHARE_LINK_TARGETS,
                coverage = { recorded = it }) { if (it == left) "$it is absent" else null }
            assertEquals(SHARE_LINK_TARGETS.size - 1, matched)
            assertEquals(listOf(left), recorded!!.missing)
        }
    }

    @Test
    fun completeCoverageStillRecordsItsInputCensus() {
        var recorded: TargetCoverage? = null
        assertEquals(2, handleTargets("Example", "targets", listOf("alpha", "beta"), coverage = { recorded = it }) { null })
        assertTrue(recorded!!.missing.isEmpty())
        assertEquals("1|2|2|alpha,beta|", recorded.encode())
    }
}

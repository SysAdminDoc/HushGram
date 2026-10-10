/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.extension

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.analytics.ANALYTICS_SUPPORTING
import app.morphe.patches.instagram.misc.analytics.ANALYTICS_TARGETS
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

    /** Audit A03: setup screens alone used to pass Disable analytics with every event route missing. */
    @Test
    fun supportingTargetsAloneStopThePatchBeforeTheyAreTouched() {
        val visited = mutableListOf<String>()
        var recorded = false
        val failure = assertThrows(PatchException::class.java) {
            handleTargets("Example", "targets", listOf("alpha", "beta", "setup"), supporting = setOf("setup"),
                coverage = { recorded = true }) {
                visited += it
                if (it == "setup") null else "$it is absent"
            }
        }
        assertEquals(listOf("alpha", "beta"), visited)
        assertFalse(recorded)
        assertTrue(failure.message!!.contains("setup only works alongside them"))
    }

    @Test
    fun oneWorkingTargetLetsTheSupportingOnesApply() {
        var recorded: TargetCoverage? = null
        val matched = handleTargets("Example", "targets", listOf("alpha", "beta", "setup"), supporting = setOf("setup"),
            coverage = { recorded = it }) { if (it == "alpha") "alpha is absent" else null }
        assertEquals(2, matched)
        assertEquals("1|2|3|alpha,beta,setup|alpha", recorded!!.encode())
    }

    @Test
    fun supportingTargetsHaveToBeLabelsListedLast() {
        for (supporting in listOf(setOf("alpha"), setOf("gamma"), setOf("alpha", "beta", "setup"))) {
            var visited = false
            assertThrows(IllegalArgumentException::class.java) {
                handleTargets("Example", "targets", listOf("alpha", "beta", "setup"), supporting = supporting) {
                    visited = true; null
                }
            }
            assertFalse(visited)
        }
    }

    /** Audit A03: each analytics and link target left out on its own still applies the rest and says so. */
    @Test
    fun everyRealTargetCanBeMissingOnItsOwn() {
        for ((targets, supporting) in listOf(ANALYTICS_TARGETS to ANALYTICS_SUPPORTING, SHARE_LINK_TARGETS to emptySet<String>())) {
            for (left in targets) {
                var recorded: TargetCoverage? = null
                val matched = handleTargets("Example", "targets", targets, supporting = supporting,
                    coverage = { recorded = it }) { if (it == left) "$it is absent" else null }
                assertEquals(targets.size - 1, matched)
                assertEquals(listOf(left), recorded!!.missing)
            }
        }
    }

    @Test
    fun analyticsWithOnlyTheSetupScreensDoesNotApply() {
        assertEquals(setOf("setup"), ANALYTICS_SUPPORTING)
        assertEquals("setup", ANALYTICS_TARGETS.last())
        assertThrows(PatchException::class.java) {
            handleTargets("Disable analytics", "event upload addresses", ANALYTICS_TARGETS,
                supporting = ANALYTICS_SUPPORTING) { if (it == "setup") null else "$it is absent" }
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

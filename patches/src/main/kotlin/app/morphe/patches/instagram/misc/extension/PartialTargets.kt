/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe.patches.instagram.misc.extension

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import java.util.logging.Logger

/**
 * Where a patch writes at patch time. Morphe's desktop CLI prints every logger named under
 * `app.morphe`, a warning as a `WARNING:` line, and Morphe Manager shows them in its patch log.
 */
internal val patchLog: Logger = Logger.getLogger("app.morphe.patches.instagram")

/** Fixed source labels only. The diagnostic metadata never holds a method name, URL or user data. */
internal data class TargetCoverage(val targets: List<String>, val missing: List<String>) {
    val expected get() = targets.size
    val matched get() = expected - missing.size

    fun encode(): String = "1|$matched|$expected|${targets.joinToString(",")}|${missing.joinToString(",")}"
}

/**
 * What a patch that works down a list of separate targets does when a build lacks some of them.
 *
 * [handle] is asked about each target once, in order, and does the patch's work on it. It answers
 * null when the target was there and has been dealt with, or why it wasn't, naming the target. A
 * build with some of the list gets the protection that's left, and the patch log names each target
 * the patch went without, one warning apiece, so a partly renamed build no longer passes quietly.
 * A build with none of them stops the patch, naming every one: applying then would claim a
 * protection the app doesn't get.
 *
 * [supporting] names the targets, last in the list, that only work alongside the others, such as
 * Disable analytics' setup screens, which are skipped because the events saying they were seen are
 * refused, and its event stream switch, which sends events to the upload the other targets guard.
 * A build with none of the targets before them stops the patch before they're touched, so they
 * can't stand in for the protection on their own.
 *
 * @param what the targets' kind in the plural, as the messages say it ("ad prefetch schedulers")
 * @return how many targets were dealt with
 */
internal fun <T> handleTargets(
    patch: String,
    what: String,
    targets: List<T>,
    label: (T) -> String = { it.toString() },
    coverage: (TargetCoverage) -> Unit = {},
    supporting: Set<String> = emptySet(),
    handle: (T) -> String?,
): Int {
    require(targets.isNotEmpty()) { "$patch has no $what to look for" }
    val labels = targets.map(label)
    require(labels.size <= 256 && labels.distinct().size == labels.size &&
        labels.all { it.matches(Regex("[a-z][a-z0-9 -]{0,63}")) }) { "$patch has invalid coverage labels" }
    val firstSupporting = labels.size - supporting.size
    require(firstSupporting > 0 && labels.drop(firstSupporting).toSet() == supporting) {
        "$patch's supporting targets have to be some of its labels, listed last"
    }
    val alongside = labels.drop(firstSupporting)
    val missing = mutableListOf<String>()
    val reasons = mutableListOf<String>()
    targets.forEachIndexed { index, target ->
        if (index == firstSupporting && reasons.size == index) {
            throw PatchException(
                "$patch: this Instagram build has none of the $index $what that work on their own, and " +
                    "${alongside.joinToString(" and ")} only work${if (alongside.size == 1) "s" else ""} alongside them. " +
                    reasons.joinToString("; ", postfix = "."),
            )
        }
        handle(target)?.let { reason ->
            reasons += reason
            missing += labels[index]
        }
    }
    if (reasons.size == targets.size) {
        throw PatchException(
            "$patch: this Instagram build has none of the ${targets.size} $what the patch works on. " +
                reasons.joinToString("; ", postfix = "."),
        )
    }
    val result = TargetCoverage(labels.toList(), missing.toList())
    val handled = result.matched
    reasons.forEach { reason ->
        patchLog.warning("$patch: $reason. The patch goes on with the $handled of ${targets.size} $what it found.")
    }
    coverage(result)
    return handled
}

/** A class descriptor as Java writes the name: `Lcom/instagram/Foo;` is `com.instagram.Foo`. */
internal fun javaName(descriptor: String): String =
    descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

/**
 * The one method [fingerprint] matches in this build. None or more than one stops [patch] with a
 * message naming [what], so a build where the anchor moved or was copied fails at patch time rather
 * than hooking the wrong method.
 */
internal fun BytecodePatchContext.uniqueMethod(patch: String, what: String, fingerprint: Fingerprint): MutableMethod {
    val matches = fingerprint.matchAllOrNull().orEmpty()
    if (matches.size != 1) {
        val found = if (matches.isEmpty()) "none" else matches.joinToString { "${it.originalMethod.definingClass}->${it.originalMethod.name}" }
        throw PatchException("$patch: expected exactly one $what in this Instagram build, found $found")
    }
    return matches.single().method
}

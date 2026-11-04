/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesReadingWide
import app.morphe.patches.instagram.misc.extension.classesTouching
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.BitSet

internal const val NEW_ROW_TINT = "$EXTENSION_PACKAGE/misc/NewRowTint;->color(J)J"

/** Instagram's kept Prism palette, and its white at 15 percent, the dark theme's tint for a new row. */
internal const val NEW_ROW_PALETTE = "Lcom/instagram/compose/core/theme/BasePrismColors;"
internal const val NEW_ROW_PRISM_TINT = "WHITE_15_TRANSPARENT"

/** A Compose theme class has at least this many colors as long instance fields, 87 on 453. */
internal const val NEW_ROW_THEME_COLORS = 80

/** Every composable on the Notifications page logs a trace label starting with this. */
private const val NOTIFICATIONS_LABEL = "com.instagram.newsfeed."

/** The avatar reads only the tint from the theme, which is how a build's Redex name for that field is found. */
private const val AVATAR_LABEL = "${NOTIFICATIONS_LABEL}compose.ui.StartAvatar."

private const val NEW_ROW_PATCH = "Pure black dark mode"

/** One read of the theme's new row tint on the Notifications page: the method and the iget-wide's index. */
internal class NewRowTintRead(val type: String, val method: Method, val index: Int)

/**
 * Every read of the new row tint on the Notifications page. A new notification's row, its swipe
 * background and its avatar's ring all take this color from the Compose theme, gray over black in
 * dark mode, so a black palette never reaches them (#374). Other screens read the same color for
 * their own highlights, like a comment's flash, and keep it.
 */
internal fun BytecodePatchContext.findNewRowTintReads(): List<NewRowTintRead> {
    val theme = newRowTheme()
    val pages = classesReadingWide(theme).flatMap { classDef ->
        classDef.methods.mapNotNull { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            val labels = notificationLabels(code).map { it.second }.ifEmpty {
                if (code.any { it.wideFieldOf(theme) != null }) outlinedLabels(classDef, method) else emptyList()
            }
            if (labels.isEmpty()) null else NewRowPage(classDef.type, method, code, labels)
        }
    }
    // A page is a method of a class reading the theme, so a merged lambda's invoke holding an avatar
    // label that reads no color is one when another arm's body reads the theme (`LX/0C5a;->invoke`,
    // whose `A04` reads `LX/05Ak;->A0K`, on 386014714). Only an avatar reading the theme names the tint.
    val avatars = pages.filter { page ->
        page.labels.any { it.startsWith(AVATAR_LABEL) } && page.code.any { it.wideFieldOf(theme) != null }
    }
    if (avatars.size != 1) {
        throw PatchException(
            "$NEW_ROW_PATCH: expected one Notifications avatar reading $theme, found ${avatars.size}: " +
                avatars.joinToString { "${it.type}->${it.method.name}" },
        )
    }
    val colors = avatars.single().code.mapNotNull { it.wideFieldOf(theme) }.distinct()
    if (colors.size != 1) {
        throw PatchException("$NEW_ROW_PATCH: the Notifications avatar reads $colors, expected one color of $theme")
    }
    return pages.flatMap { page ->
        page.code.indices.filter { page.code[it].wideFieldOf(theme) == colors.single() }
            .map { NewRowTintRead(page.type, page.method, it) }
    }
}

/** Sends each read through the hook. Light mode's blue comes back as it went in. */
internal fun BytecodePatchContext.blackenNewRowTints(reads: List<NewRowTintRead>) {
    for (read in reads.sortedByDescending { it.index }) {
        val method = mutableClassDefBy(read.type).methods.single {
            it.name == read.method.name && it.returnType == read.method.returnType &&
                it.parameterTypes.map(Any::toString) == read.method.parameterTypes.map(Any::toString)
        }
        val register = (method.implementation!!.instructions[read.index] as OneRegisterInstruction).registerA
        method.addInstructions(
            read.index + 1,
            """
                invoke-static/range { v$register .. v${register + 1} }, $NEW_ROW_TINT
                move-result-wide v$register
            """,
        )
    }
}

private class NewRowPage(val type: String, val method: Method, val code: List<Instruction>, val labels: List<String>)

/** Each Notifications label [code] loads, with the index of its load. */
private fun BytecodePatchContext.notificationLabels(code: List<Instruction>): List<Pair<Int, String>> =
    code.indices.mapNotNull { at -> stringLoadedAt(code, at)?.takeIf { it.startsWith(NOTIFICATIONS_LABEL) }?.let { at to it } }

/**
 * The Notifications labels of a lambda body Redex moved out of its invoke. The body becomes a static
 * method of the lambda's class and the trace label stays in invoke, which calls the body on the
 * Notifications arm of its switch (`LX/0X9f;->A01` from `LX/0X9f;->invoke` on 386014758). The body
 * takes the labels only when nothing outside its class calls it and a Notifications label reaches
 * every call, so a body the switch's other arms call keeps its colors.
 */
private fun BytecodePatchContext.outlinedLabels(classDef: ClassDef, body: Method): List<String> {
    if (!AccessFlags.STATIC.isSet(body.accessFlags)) return emptyList()
    val parameters = body.parameterTypes.map(Any::toString)
    val labels = mutableListOf<String>()
    for (caller in classDef.methods) {
        val code = caller.implementation?.instructions?.toList().orEmpty()
        val sites = code.indices.filter { code[it].callsStatic(classDef.type, body.name, parameters, body.returnType) }
        if (sites.isEmpty()) continue
        val flow = runCatching { ControlFlow.of(caller) }.getOrNull() ?: return emptyList()
        val loads = notificationLabels(code)
        for (site in sites) {
            val reaching = loads.filter { (at, _) -> flow.reaches(at, site) }
            if (reaching.isEmpty()) return emptyList()
            labels += reaching.map { it.second }
        }
    }
    if (labels.isEmpty()) return emptyList()
    if (classesCalling(classDef.type, body.name, parameters, body.returnType).any { it.type != classDef.type }) return emptyList()
    return labels.distinct()
}

private fun Instruction.callsStatic(type: String, name: String, parameters: List<String>, returnType: String): Boolean {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val called = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return called.definingClass == type && called.name == name && called.returnType == returnType &&
        called.parameterTypes.map(Any::toString) == parameters
}

/** Whether a path of fall throughs, branches and switch arms leads from [from] to [to]. */
private fun ControlFlow.reaches(from: Int, to: Int): Boolean {
    val seen = BitSet(instructions.size)
    val queue = ArrayDeque(listOf(from))
    while (queue.isNotEmpty()) {
        val at = queue.removeFirst()
        if (at == to) return true
        if (seen.get(at)) continue
        seen.set(at)
        normal[at].filterNot(seen::get).forEach(queue::addLast)
    }
    return false
}

/** The class of the Compose themes: a class made by those reading Prism's tint, holding a theme's worth of colors. */
private fun BytecodePatchContext.newRowTheme(): String {
    val themes = classesTouching(NEW_ROW_PALETTE, NEW_ROW_PRISM_TINT).flatMap { classDef ->
        classDef.methods.flatMap { method ->
            method.implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.NEW_INSTANCE }
                .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        }
    }.distinct().filter { type ->
        (classDefByOrNull(type)?.instanceFields?.count { it.type == "J" } ?: 0) >= NEW_ROW_THEME_COLORS
    }
    return themes.singleOrNull()
        ?: throw PatchException("$NEW_ROW_PATCH: expected one Compose theme class made with $NEW_ROW_PRISM_TINT, found $themes")
}

private fun Instruction.wideFieldOf(theme: String): String? {
    if (opcode != Opcode.IGET_WIDE) return null
    val field = (this as ReferenceInstruction).reference as FieldReference
    return if (field.definingClass == theme) field.name else null
}

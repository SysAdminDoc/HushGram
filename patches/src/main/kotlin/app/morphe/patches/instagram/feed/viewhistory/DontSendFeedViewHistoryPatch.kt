/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.viewhistory

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Don't send feed view history"

/** The parameter a Home request (feed/timeline/) carries the posts you looked at in. */
internal const val FEED_VIEW_INFO = "feed_view_info"

/** Hands the record of what you looked at to the extension, which answers null to leave it out. */
internal const val HELD = "$EXTENSION_PACKAGE/feed/FeedViewInfo;->held(Ljava/lang/Object;)Ljava/lang/Object;"

private const val STRING = "Ljava/lang/String;"

@Suppress("unused")
val dontSendFeedViewHistoryPatch = bytecodePatch(
    name = "Don't send feed view history",
    description = "Stops Instagram from learning which posts in Home you looked at and for how long. Posts " +
        "you've already seen may come back. Ghost mode, at the top of Ads and privacy, turns it on with the " +
        "others. Starts off. Turn it on in HushGram settings > Feed.",
) {
    // Ghost mode turns its switch, so it sits with the other switches Ghost mode turns.
    category("Ghost mode")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("feedViewHistory")
        holdBackFeedViewInfo()
        enableStatus("feedViewHistory")
    }
}

/**
 * One place a Home request adds [FEED_VIEW_INFO]: [method], the index of the null test right after
 * the read of the record ([test]), the register the record was read into, and the record's type.
 */
internal data class FeedViewInfoSite(val method: Method, val test: Int, val register: Int, val record: String)

/**
 * Every place a Home request adds [FEED_VIEW_INFO], by its shape rather than any class or field
 * name: the name loaded (as a constant or, on a Redex build, asked of a string pool), then a read
 * of an object field (the record), a null test on it, a read of the record's own string field, and
 * a `(String, String)V` call taking the name and that string. On 455 both builders of the
 * feed/timeline/ request have it, the cold start's and the one for each page after.
 */
internal fun BytecodePatchContext.feedViewInfoSites(): List<FeedViewInfoSite> =
    classesLoadingString(FEED_VIEW_INFO).flatMap { it.methods }.flatMap { method ->
        val code = method.implementation?.instructions?.toList() ?: emptyList()
        code.indices.mapNotNull { at -> siteAt(method, code, at) }
    }

private fun BytecodePatchContext.siteAt(method: Method, code: List<Instruction>, at: Int): FeedViewInfoSite? {
    if (stringLoadedAt(code, at) != FEED_VIEW_INFO) return null
    // A constant holds the name in its own register; a string pool hands it over in the move-result after the call.
    val (name, read) = if (code[at].opcode == Opcode.INVOKE_STATIC) {
        val result = code.getOrNull(at + 1) ?: return null
        if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return null
        (result as OneRegisterInstruction).registerA to at + 1 + 1
    } else {
        (code[at] as OneRegisterInstruction).registerA to at + 1
    }
    val record = code.getOrNull(read) ?: return null
    if (record.opcode != Opcode.IGET_OBJECT) return null
    val recordField = (record as ReferenceInstruction).reference as FieldReference
    val held = (record as TwoRegisterInstruction).registerA
    if (!recordField.type.startsWith("L") || recordField.type == STRING || held == name) return null
    val test = code.getOrNull(read + 1) ?: return null
    if (test.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != held) return null
    val value = code.getOrNull(read + 2) ?: return null
    if (value.opcode != Opcode.IGET_OBJECT || (value as TwoRegisterInstruction).registerB != held) return null
    val valueField = (value as ReferenceInstruction).reference as FieldReference
    if (valueField.definingClass != recordField.type || valueField.type != STRING) return null
    val add = code.getOrNull(read + 3) ?: return null
    if (add.opcode != Opcode.INVOKE_VIRTUAL) return null
    val added = (add as ReferenceInstruction).reference as MethodReference
    if (added.parameterTypes.map(Any::toString) != listOf(STRING, STRING) || added.returnType != "V") return null
    val args = add as FiveRegisterInstruction
    if (args.registerCount != 3 || args.registerD != name || args.registerE != (value as TwoRegisterInstruction).registerA) {
        return null
    }
    return FeedViewInfoSite(method, read + 1, held, recordField.type)
}

/**
 * Hands the record of what you looked at to [HELD] right after each Home request reads it, and
 * casts the answer back to the record's type. Instagram's own null test then leaves
 * [FEED_VIEW_INFO] out of the request when the extension answers null. The record was read into
 * a 4-bit register, so the call takes it as is. Fails before anything changes when no site is found,
 * or when something jumps straight to a null test and would go around the hook.
 */
internal fun BytecodePatchContext.holdBackFeedViewInfo() {
    val sites = feedViewInfoSites()
    if (sites.isEmpty()) {
        throw PatchException(
            "$PATCH: found no Home request adding \"$FEED_VIEW_INFO\" from a record it tests for null",
        )
    }
    val targets = sites.groupBy { it.method }.map { (found, here) ->
        val method = mutableClassDefBy(found.definingClass).methods.single {
            it.name == found.name && it.returnType == found.returnType &&
                it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString)
        }
        val code = method.implementation!!.instructions
        here.forEach { site ->
            if ((code[site.test] as BuilderInstruction).location.labels.isNotEmpty()) {
                throw PatchException(
                    "$PATCH: something jumps to the null test of \"$FEED_VIEW_INFO\" in " +
                        "${found.definingClass}->${found.name}, past where the hook would go",
                )
            }
        }
        method to here
    }
    targets.forEach { (method, here) ->
        // Later sites first, so an insert doesn't move the index of one still to come.
        here.sortedByDescending { it.test }.forEach { site ->
            method.addInstructions(
                site.test,
                """
                    invoke-static { v${site.register} }, $HELD
                    move-result-object v${site.register}
                    check-cast v${site.register}, ${site.record}
                """,
            )
        }
    }
}

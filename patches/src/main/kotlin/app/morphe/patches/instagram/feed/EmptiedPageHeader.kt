/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The title of the header Instagram adds to a page of Home, right after the page's first post. */
internal const val NEW_POSTS_HEADER = "New posts for you"

internal const val ADD_PAGE_HEADER = "$EXTENSION_PACKAGE/feed/FeedPageHeader;->add(Ljava/util/List;ILjava/lang/Object;)V"

private val LIST_CALLS = setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
)
private val LIST_TYPES = setOf("Ljava/util/AbstractList;", "Ljava/util/ArrayList;", "Ljava/util/List;")

/**
 * For every patch that can take posts out of Home: a page can come down to nothing, and Instagram
 * then adds its header at index 1 of the empty list and crashes.
 */
internal val emptiedPageHeaderPatch = bytecodePatch {
    dependsOn(instagramExtensionPatch)

    execute {
        guardNewPostsHeader()
    }
}

/**
 * Hands the insert that follows the header's title to the extension, on the same registers. Only the
 * call changes, so whatever jumps onto it lands on the hook. A build without one still patches: the
 * filters it serves matter more than this guard.
 */
internal fun BytecodePatchContext.guardNewPostsHeader() {
    var guarded = 0
    for (owner in classesLoadingString(NEW_POSTS_HEADER).distinctBy { it.type }) {
        for (found in owner.methods.filter { loadsString(it, NEW_POSTS_HEADER) }) {
            val code = found.implementation?.instructions?.toList() ?: continue
            val sites = code.indices.filter { stringLoadedAt(code, it) == NEW_POSTS_HEADER }
                .mapNotNull { title -> (title + 1 until code.size).firstOrNull { code[it].isIndexedInsert() } }
                .distinct()
            if (sites.isEmpty()) continue
            val parameters = found.parameterTypes.map(Any::toString)
            val method = mutableClassDefBy(owner.type).methods.single {
                it.name == found.name && it.returnType == found.returnType &&
                    it.parameterTypes.map(Any::toString) == parameters
            }
            for (at in sites) method.replaceInstruction(at, guardedInsert(code[at]))
            guarded += sites.size
        }
    }
    if (guarded == 0) patchLog.warning("Home's page header insert was not found, so it stays unguarded")
}

private fun guardedInsert(insert: Instruction): String = if (insert is RegisterRangeInstruction) {
    "invoke-static/range { v${insert.startRegister} .. v${insert.startRegister + 2} }, $ADD_PAGE_HEADER"
} else {
    val call = insert as FiveRegisterInstruction
    "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE} }, $ADD_PAGE_HEADER"
}

private fun Instruction.isIndexedInsert(): Boolean {
    if (opcode !in LIST_CALLS) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.name == "add" && call.returnType == "V" && call.definingClass in LIST_TYPES &&
        call.parameterTypes.map(Any::toString) == listOf("I", "Ljava/lang/Object;")
}

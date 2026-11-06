/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Clean up Reels"

/** What Instagram logs as the name of the method that decides a viewer's way to its friends' reels. */
internal const val FRIEND_LANE_BUTTON = "android_purge_26_q3_ClipsOverlayActionBarUseCase_shouldShowFriendLaneButton"

/** What Instagram logs in the method that builds a viewer's top bar state, which asks [FRIEND_LANE_BUTTON]. */
internal const val TOP_BAR_STATE = "android_purge_26_q2_ClipsViewerOverlayActionBarDelegateProvider_provide"

internal const val FRIENDS_ROW_HOOK = "$EXTENSION_PACKAGE/reels/ReelsTopBar;->friendsTabAnyway(Ljava/lang/Object;)Z"

private const val VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"
private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/**
 * Where a viewer's tabs at the top are decided (#499): in [method], at [noAt], register [flag] gets
 * no, the last step of a chain of checks. The next thing it does is jump to where yes goes on, which
 * reads the viewer's source into [source] off [config] with [sourceField] before anything else.
 */
internal class FriendsRowSite(
    val method: Method,
    val noAt: Int,
    val flag: Int,
    val source: Int,
    val config: Int,
    val sourceField: String,
)

/**
 * Finds the one place the tabs are decided, failing before anything changes when it isn't there.
 * The builder of the top bar's state asks [FRIEND_LANE_BUTTON]. The first branch on that answer goes
 * to `flag = 1`, and the check right after it goes to `flag = 0` when it fails too. Whichever is set
 * ends up in the same read of the viewer's source. The tab builder itself adds Reels and Friends
 * whenever it runs, so this flag is the whole of what decides them on the client.
 */
internal fun BytecodePatchContext.findFriendsRow(): FriendsRowSite {
    val button = classesLoadingString(FRIEND_LANE_BUTTON).flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
                method.parameterTypes.firstOrNull()?.toString() == VIEWER_CONFIG && loadsString(method, FRIEND_LANE_BUTTON)
        }
    }.singleOrNull() ?: throw PatchException("$PATCH: expected one method deciding the friends button ($FRIEND_LANE_BUTTON)")
    val sites = classesLoadingString(TOP_BAR_STATE).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, TOP_BAR_STATE) }.mapNotNull { friendsRowIn(it, button) }
    }
    return sites.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one place deciding the tabs at the top of Reels from the friends button, found ${sites.size}",
    )
}

private fun friendsRowIn(method: Method, button: Method): FriendsRowSite? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val call = code.indexOfFirst { it.calls(button) }
    if (call < 0) return null
    val result = code.getOrNull(call + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } ?: return null
    val answer = (result as OneRegisterInstruction).registerA
    val branch = (call + 2 until code.size).firstOrNull {
        code[it].opcode == Opcode.IF_NEZ && (code[it] as OneRegisterInstruction).registerA == answer
    } ?: return null
    val flow = ControlFlow.of(method)
    val yesAt = flow.normal[branch].singleOrNull { it != branch + 1 } ?: return null
    if (code.getOrNull(branch + 1)?.opcode != Opcode.IF_EQZ) return null
    val noAt = flow.normal[branch + 1].singleOrNull { it != branch + 2 } ?: return null
    val yes = code[yesAt].constant() ?: return null
    val no = code[noAt].constant() ?: return null
    if (yes.first != 1 || no.first != 0 || yes.second != no.second) return null
    // The no jumps straight on to the instruction after the yes, which reads the viewer's source.
    if (code.getOrNull(noAt + 1)?.opcode !in GOTOS || flow.normal[noAt + 1].singleOrNull() != yesAt + 1) return null
    val read = code.getOrNull(yesAt + 1)?.takeIf { it.opcode == Opcode.IGET_OBJECT } ?: return null
    val field = (read as ReferenceInstruction).reference as? FieldReference ?: return null
    if (field.definingClass != VIEWER_CONFIG || field.type != CLIPS_VIEWER_SOURCE) return null
    read as TwoRegisterInstruction
    if (read.registerA == read.registerB) return null
    return FriendsRowSite(method, noAt, yes.second, read.registerA, read.registerB, field.toString())
}

private fun Instruction.calls(target: Method): Boolean {
    val called = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return called.definingClass == target.definingClass && called.name == target.name && called.returnType == target.returnType &&
        called.parameterTypes.map(Any::toString) == target.parameterTypes.map(Any::toString)
}

/** A small constant load as its value and register, or null. */
private fun Instruction.constant(): Pair<Int, Int>? =
    if (opcode == Opcode.CONST_4 || opcode == Opcode.CONST_16) {
        (this as NarrowLiteralInstruction).narrowLiteral to (this as OneRegisterInstruction).registerA
    } else {
        null
    }

/**
 * Asks the extension in place of the no, in the jump's own stretch of code, which only Instagram's
 * no reaches: a viewer it already said yes to never comes this way. [FriendsRowSite.source] is free
 * here, since the instruction the jump lands on writes it first.
 */
internal fun BytecodePatchContext.keepFriendsRow(site: FriendsRowSite) {
    val found = site.method
    val method = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString) &&
            it.returnType == found.returnType
    }
    if (site.noAt + 1 in method.jumpTargets()) {
        throw PatchException("$PATCH: something jumps into ${found.definingClass}->${found.name} where the Friends tab hook goes")
    }
    method.addInstructions(
        site.noAt + 1,
        """
            iget-object v${site.source}, v${site.config}, ${site.sourceField}
            invoke-static/range { v${site.source} .. v${site.source} }, $FRIENDS_ROW_HOOK
            move-result v${site.flag}
        """,
    )
}

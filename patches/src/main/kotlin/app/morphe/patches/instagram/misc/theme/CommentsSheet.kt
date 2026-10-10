/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method

/** Instagram's one bottom sheet host. Its name is kept, and every sheet's screen sits inside it. */
internal const val BOTTOM_SHEET_HOST = "Lcom/instagram/igds/components/bottomsheet/BottomSheetFragment;"

internal const val PAINT_COMMENTS_SHEET = "$EXTENSION_PACKAGE/misc/CommentsSheet;->paint(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val REPAINT_COMMENTS_SHEET = "$EXTENSION_PACKAGE/misc/CommentsSheet;->repaint(Ljava/lang/Object;)V"

private const val CONTEXT = "Landroid/content/Context;"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"

/** The host's two methods the comments sheet's background is put on and put back by. */
internal class BottomSheetHooks(val setUp: Method, val dragged: Method)

private fun Method.parameters() = parameterTypes.map(CharSequence::toString)

/**
 * Finds, in [BOTTOM_SHEET_HOST], the one non-static method of (Context, Fragment, int) answering
 * nothing, which paints the container and receives the screen to show, and the one public
 * non-static (int, int) method answering nothing, which the sheet calls as it is dragged and which
 * can paint the container again. Fails when either isn't exactly one, since that's an update this
 * patch hasn't seen.
 */
internal fun BytecodePatchContext.findBottomSheetHooks(): BottomSheetHooks {
    val host = classDefByOrNull(BOTTOM_SHEET_HOST) ?: throw PatchException("$PATCH_NAME: $BOTTOM_SHEET_HOST is gone")
    val live = host.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" }
    val setUp = live.filter { it.parameters() == listOf(CONTEXT, FRAGMENT, "I") }
    val dragged = live.filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && it.parameters() == listOf("I", "I") }
    if (setUp.size != 1) throw PatchException("$PATCH_NAME: expected one (Context, Fragment, int) method in the bottom sheet, found ${setUp.size}")
    if (dragged.size != 1) throw PatchException("$PATCH_NAME: expected one public (int, int) method in the bottom sheet, found ${dragged.size}")
    return BottomSheetHooks(setUp.single(), dragged.single())
}

private const val PATCH_NAME = "Pure black dark mode"

/**
 * Before each return of the host's set-up method, hands the host and the screen it shows to
 * [PAINT_COMMENTS_SHEET], and before each return of its drag method hands it the host to
 * [REPAINT_COMMENTS_SHEET]. The comments sheet's gray belongs to the host's container, not to a
 * theme attribute, so the colors the patch changes don't reach it (#108).
 */
internal fun BytecodePatchContext.blackenCommentsSheet(hooks: BottomSheetHooks) {
    val host = mutableClassDefBy(BOTTOM_SHEET_HOST)
    fun Method.same(other: Method) = name == other.name && parameters() == other.parameters() && returnType == other.returnType
    host.methods.single { it.same(hooks.setUp) }.hookReturns("invoke-static { p0, p2 }, $PAINT_COMMENTS_SHEET")
    host.methods.single { it.same(hooks.dragged) }.hookReturns("invoke-static { p0 }, $REPAINT_COMMENTS_SHEET")
}

private fun app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.hookReturns(call: String) {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    if (returns.isEmpty()) throw PatchException("$PATCH_NAME: $name returns nowhere")
    returns.asReversed().forEach { addInstructions(it, call) }
}

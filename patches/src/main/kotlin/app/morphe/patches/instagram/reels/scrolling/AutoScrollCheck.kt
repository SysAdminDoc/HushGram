/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.scrolling

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.reels.autoscroll.SCROLLER_SHOULD_SCROLL
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Asked first thing in the Reels auto scroller's check of whether to move on, with the source of the
 * viewer it scrolls. A 0 keeps the reel on screen, which then plays again; anything else runs
 * Instagram's check as it was.
 */
internal const val AUTO_SCROLL_CHECK_HOOK = "$REEL_SCROLLING->autoScroll(Ljava/lang/Object;)I"

/** The auto scroller's check of whether to move on, [scroller]->[name], and the [source] field it reads the viewer's source from. */
internal class AutoScrollCheck(val scroller: String, val name: String, val source: String)

private fun refuseCheck(detail: String): Nothing = throw PatchException("Stop Reels scrolling: $detail")

/**
 * Where Play only the reel you opened stops auto scroll (#656).
 *
 * Every move the Reels auto scroller makes on its own, once a reel ends, a little before it ends or
 * when its timer runs out, asks one check first, the method marked [SCROLLER_SHOULD_SCROLL]: a static
 * taking the scroller and answering whether to move on. Move on when a reel ends uses the same
 * scroller, so it asks the same check. The scroller is built for one viewer, with that viewer's
 * source, and keeps it in its one instance field of [VIEWER_SOURCE] type, stored by its constructor.
 *
 * The hook goes in front of the check's first instruction, which nothing jumps back to, borrowing
 * one local, which holds nothing before the method's own code runs.
 */
internal fun BytecodePatchContext.findAutoScrollCheck(): AutoScrollCheck {
    val holders = typesMarked(SCROLLER_SHOULD_SCROLL)
    val marked = mutableListOf<Method>()
    classDefForEach { type ->
        if (type.type !in holders) return@classDefForEach
        type.methods.filterTo(marked) { SCROLLER_SHOULD_SCROLL in it.markers() }
    }
    val check = marked.singleOrNull()
        ?: refuseCheck("expected one method marked $SCROLLER_SHOULD_SCROLL, found ${marked.size}")
    val scroller = check.definingClass
    val text = "$scroller->${check.name}(${check.checkParameters().joinToString("")})${check.returnType}"
    if (!AccessFlags.STATIC.isSet(check.accessFlags) || check.returnType != "Z" || check.checkParameters() != listOf(scroller)) {
        refuseCheck("$text, marked $SCROLLER_SHOULD_SCROLL, isn't a static method taking its own class and answering a boolean")
    }
    if (0 in check.jumpTargets()) refuseCheck("something in $text jumps back to its start")
    if (check.localRegisterCount() < 1) refuseCheck("$text has no local register to borrow")

    val type = classDefBy(scroller)
    val sources = type.fields.filter { it.type == VIEWER_SOURCE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val source = sources.singleOrNull()
        ?: refuseCheck("expected one $VIEWER_SOURCE field in $scroller, found ${sources.size}")
    val field = "$scroller->${source.name}:$VIEWER_SOURCE"
    val kept = type.methods.any { method ->
        method.name == "<init>" && method.checkCode().any { it.opcode == Opcode.IPUT_OBJECT && it.checkField()?.toString() == field }
    }
    if (!kept) refuseCheck("no constructor of $scroller keeps the viewer's source in $field")
    val named = type.methods.count { it.name == check.name && it.checkParameters() == listOf(scroller) }
    if (named != 1) refuseCheck("$scroller has $named methods named ${check.name} taking only itself")
    return AutoScrollCheck(scroller, check.name, field)
}

/**
 * Puts [AUTO_SCROLL_CHECK_HOOK] first in the check, with the source the scroller keeps. On a 0 the
 * check answers false, don't move on, and on anything else it runs as Instagram wrote it.
 */
internal fun BytecodePatchContext.holdAutoScroll(check: AutoScrollCheck) {
    val method = mutableClassDefBy(check.scroller).methods.single {
        it.name == check.name && it.checkParameters() == listOf(check.scroller)
    }
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${check.source}
            invoke-static { v0 }, $AUTO_SCROLL_CHECK_HOOK
            move-result v0
            if-nez v0, :stock
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("stock", method.getInstruction(0)),
    )
}

private fun Method.checkParameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun Method.checkCode() = implementation?.instructions?.toList().orEmpty()

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.checkField(): FieldReference? =
    (this as? ReferenceInstruction)?.reference as? FieldReference

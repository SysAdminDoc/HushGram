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
import app.morphe.patches.instagram.direct.reels.ViewerConfigPath
import app.morphe.patches.instagram.direct.reels.viewerConfigPath
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.reels.autoscroll.SCROLLER_SHOULD_SCROLL_FLAG
import app.morphe.patches.instagram.reels.autoscroll.methodsLoadingScrollFlag
import app.morphe.patches.instagram.reels.partnership.fillStub
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Asked first thing in the Reels auto scroller's check of whether to move on, with the source of the
 * viewer it scrolls and the scroller. A 0 keeps the reel on screen, which then plays again; anything
 * else runs Instagram's check as it was.
 */
internal const val AUTO_SCROLL_CHECK_HOOK = "$REEL_SCROLLING->autoScroll(Ljava/lang/Object;Ljava/lang/Object;)I"

/**
 * The auto scroller's check of whether to move on, [scroller]->[name], the [source] field it reads the
 * viewer's source from, and the way to the viewer's config the scroller keeps ([config]), or null on a
 * build without one public way to it.
 */
internal class AutoScrollCheck(val scroller: String, val name: String, val source: String, val config: ViewerConfigPath?)

private fun refuseCheck(detail: String): Nothing = throw PatchException("Stop Reels scrolling: $detail")

/**
 * Where Play only the reel you opened stops auto scroll (#656).
 *
 * Every move the Reels auto scroller makes on its own, once a reel ends, a little before it ends or
 * when its timer runs out, asks one check first, the one method loading [SCROLLER_SHOULD_SCROLL_FLAG]:
 * a static taking the scroller and answering whether to move on. Move on when a reel ends uses the
 * same scroller, so it asks the same check. The scroller is built for one viewer, with that viewer's
 * source, and keeps it in its one instance field of [VIEWER_SOURCE] type, stored by its constructor.
 *
 * The hook goes in front of the check's first instruction, which nothing jumps back to, borrowing
 * one local, which holds nothing before the method's own code runs, and a second for the scroller
 * when its parameter register is past v15. The scroller keeps the viewer's config one object away
 * ([viewerConfigPath]), which the extension reads off it, so a chat's reel opened as its Blend's is
 * told (#804); a build without that one public way gets a warning, and the check goes by the source.
 */
internal fun BytecodePatchContext.findAutoScrollCheck(): AutoScrollCheck {
    val flag = "0x${SCROLLER_SHOULD_SCROLL_FLAG.toString(16)}"
    val loading = methodsLoadingScrollFlag()
    val check = loading.singleOrNull()
        ?: refuseCheck("expected one method loading the scroll check's flag $flag, found ${loading.size}")
    val scroller = check.definingClass
    val text = "$scroller->${check.name}(${check.checkParameters().joinToString("")})${check.returnType}"
    if (!AccessFlags.STATIC.isSet(check.accessFlags) || check.returnType != "Z" || check.checkParameters() != listOf(scroller)) {
        refuseCheck("$text, loading $flag, isn't a static method taking its own class and answering a boolean")
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
    val config = try {
        if (!AccessFlags.PUBLIC.isSet(type.accessFlags)) throw PatchException("Stop Reels scrolling: $scroller isn't public")
        viewerConfigPath("Stop Reels scrolling: the auto scroller $scroller", scroller)
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. A chat's reel opened as its Blend's auto scrolls by its source.")
        null
    }
    return AutoScrollCheck(scroller, check.name, field, config)
}

/**
 * Puts [AUTO_SCROLL_CHECK_HOOK] first in the check, with the source the scroller keeps and the
 * scroller, and writes the [SCROLLER_CONFIG] bridge reading the viewer's config off it. On a 0 the
 * check answers false, don't move on, and on anything else it runs as Instagram wrote it.
 */
internal fun BytecodePatchContext.holdAutoScroll(check: AutoScrollCheck) {
    val method = mutableClassDefBy(check.scroller).methods.single {
        it.name == check.name && it.checkParameters() == listOf(check.scroller)
    }
    check.config?.let { path ->
        // A scroller without its state answers no config. No register reaches a label holding two types.
        fillStub(
            REEL_SCROLLING, SCROLLER_CONFIG, "Ljava/lang/Object;", 1,
            """
                check-cast p0, ${check.scroller}
                iget-object p0, p0, ${path.holder}
                if-nez p0, :read
                const/4 p0, 0x0
                return-object p0
                :read
                iget-object p0, p0, ${path.config}
                return-object p0
            """,
        )
    }
    // The scroller's own register when an invoke can name it, else a copy in a second local, which a
    // method with parameters past v15 has.
    val self = method.localRegisterCount()
    val scroller = if (self <= 15) "p0" else "v1"
    val copy = if (self <= 15) "" else "move-object/from16 v1, p0"
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            $copy
            iget-object v0, v0, ${check.source}
            invoke-static { v0, $scroller }, $AUTO_SCROLL_CHECK_HOOK
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

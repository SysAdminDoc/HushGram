/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Hide suggested posts"

/** The marker of the Reels footer's method that picks the row's control (452.1's was ClipsFullWidthFooterUseCase_getOnImpressionControl). */
internal const val REELS_CONTROL_MARKER = "ClipsFullWidthFooterComponent_getRankingControls"

internal const val REELS_CONTROL = "$FEED_INTEREST->reelsControl(Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * Where the Reels footer hands its "Are you interested?" row its control, found: the use case
 * method, the index of the one store of the control into the row's component, the register it
 * stores, and the control's type. [write] passes the control through FeedInterest.reelsControl()
 * right before the store, and a null leaves the component with nothing to draw (#131).
 */
internal class ReelsInterestControl(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val storeAt: Int,
    val register: Int,
    val controlType: String,
) {
    fun write(context: BytecodePatchContext) {
        val method = context.mutableClassDefBy(type).methods.single {
            it.name == name && it.returnType == returnType && it.parameterTypes.map(CharSequence::toString) == parameters
        }
        // At the store's own label, so the server's control and the method's own fallback both pass it.
        method.addInstructionsAtControlFlowLabel(
            storeAt,
            """
                invoke-static {v$register}, $REELS_CONTROL
                move-result-object v$register
                check-cast v$register, $controlType
            """,
        )
    }
}

/**
 * Finds the Reels footer's method holding [REELS_CONTROL_MARKER]: the one method with that marker,
 * an instance method taking the reel's state and a fallback control and answering the ranking
 * controls component. It reads the reel's own on-impression control (the wrapped
 * clips_on_impression_control model), or keeps the fallback when there is none, and stores what it
 * ends up with in the one field of the component of the fallback's type. The component
 * (ClipsRankingControlsComponent) draws an empty box when that field is null. On 453's 385914557
 * that's LX/08YW;->A02(LX/09Wo;LX/06CH;)LX/0D5R; storing v13 into LX/0D5R;->A0A at instruction 57,
 * and 385914556 stores the same register at the same instruction. Anything else is an update this patch
 * hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findReelsInterestControl(): ReelsInterestControl {
    val found = mutableListOf<Method>()
    typesMarked(REELS_CONTROL_MARKER).forEach { type ->
        classDefByOrNull(type)?.methods?.forEach { method -> if (REELS_CONTROL_MARKER in method.markers()) found += method }
    }
    val method = found.distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}${it.returnType}" }.singleOrNull()
        ?: refuse("expected one method holding the $REELS_CONTROL_MARKER marker, found ${found.size}")
    val where = "${method.definingClass}->${method.name}"
    val parameters = method.parameterTypes.map(CharSequence::toString)
    val component = method.returnType
    if (AccessFlags.STATIC.isSet(method.accessFlags) || parameters.size != 2 || parameters.any { !it.startsWith("L") } ||
        !component.startsWith("L")
    ) {
        refuse("$where isn't an instance method taking two objects and answering a component")
    }
    val controlType = parameters[1]
    val code = method.implementation?.instructions?.toList() ?: refuse("$where has no code")
    val stores = code.indices.filter { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.IPUT_OBJECT && field?.definingClass == component && field.type == controlType
    }
    val storeAt = stores.singleOrNull()
        ?: refuse("expected one store of a $controlType into $component in $where, found ${stores.size}")
    val register = (code[storeAt] as TwoRegisterInstruction).registerA
    return ReelsInterestControl(method.definingClass, method.name, parameters, component, storeAt, register, controlType)
}

/** [findReelsInterestControl], or null with a warning: Reels keeps its question and Home is still hooked. */
internal fun BytecodePatchContext.reelsInterestControlOrWarn(): ReelsInterestControl? = try {
    findReelsInterestControl()
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. Reels keep their 'Are you interested?' row in this build.")
    null
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the Reels interest question: $detail")

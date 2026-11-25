/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.sound

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.jumpTargets
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val RESET_PATCH = "Start feed videos with sound (reset)"
internal const val FEED_AUDIO_STATE_CHANGING = "$FEED_SOUND->audioStateChanging(Ljava/lang/Object;)V"

private const val BOOLEAN = "Ljava/lang/Boolean;"

private fun refuseReset(detail: String): Nothing = throw PatchException("$RESET_PATCH: $detail")

/**
 * The account audio state's own static setter, the one every write of the state goes through (#762): the
 * static method of the state class returning void that takes (the state, Boolean) and stores that Boolean
 * in a Boolean field of the state class. Instagram's taps and toggles hand it true or false; after ten
 * minutes in the background, or on a foreground the server asks for, Instagram hands it null, which
 * forgets the state. 457's is `LX/03id;->A01(LX/03id;Ljava/lang/Boolean;)V` on 386314341. Found
 * from [factory], the state's factory the Reels audio toggle calls, and checked before anything changes.
 */
internal fun BytecodePatchContext.findAudioStateReset(factory: MethodReference): Method {
    val stateType = factory.returnType
    val state = classDefByOrNull(stateType) ?: refuseReset("the audio state $stateType isn't in the app")
    val setters = state.methods.filter { method ->
        val registers = method.implementation?.registerCount ?: return@filter false
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(Any::toString) == listOf(stateType, BOOLEAN) &&
            method.implementation!!.instructions.any { instruction ->
                val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                instruction.opcode == Opcode.IPUT_OBJECT && field != null && field.definingClass == stateType &&
                    field.type == BOOLEAN && (instruction as TwoRegisterInstruction).registerA == registers - 1
            }
    }
    val setter = setters.singleOrNull()
        ?: refuseReset("expected one static $stateType setter storing its Boolean in the state, found ${setters.size}")
    if (0 in setter.jumpTargets()) refuseReset("something in $stateType->${setter.name} jumps back to its first instruction")
    return setter
}

/**
 * At the setter's first instruction: hand the value it was given, its second parameter, to
 * [FEED_AUDIO_STATE_CHANGING]. A null there gives every feed its turn again, the way a cold start does, so
 * Home's next video starts with sound after Instagram forgot the state. Nothing else changes; the
 * extension never throws.
 */
internal fun BytecodePatchContext.applyAudioStateReset(setter: Method) {
    val method = mutableClassDefBy(setter.definingClass).methods.single {
        it.name == setter.name && it.parameterTypes.map(Any::toString) == setter.parameterTypes.map(Any::toString) &&
            it.returnType == setter.returnType
    }
    method.addInstruction(0, "invoke-static/range { p1 .. p1 }, $FEED_AUDIO_STATE_CHANGING")
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

internal const val BLEND_BUTTON = "$EXTENSION_PACKAGE/metaai/MetaAi;->blendButton()Z"

/**
 * The sharing surface the Blend check asks the reel's share eligibility for, as a narrow constant
 * (`const/16 v0, 0x45` ahead of the eligibility call, on 455 and on every 456 build).
 */
internal const val BLEND_SURFACE = 0x45

private const val SESSION = "Lcom/instagram/common/session/UserSession;"
private const val STRING = "Ljava/lang/String;"

/** Where the Blend visibility check is: its class, name, parameters and return type (the answer's class). */
internal class BlendVisibilityCheck(val type: String, val name: String, val parameters: List<String>, val returnType: String)

/**
 * The Blend invite button on a reel is shown or hidden from the answer of one static method: it takes
 * the reel, the session, the owner's id and three flags, and answers a small value of two flags that
 * the Reels viewer's reel binder and the clips viewer both read. 456 dropped the purge marker it
 * logged its name with (android_purge_26_q2_BlendButtonVisibilityUtils_computeBlendInviteButtonVisibility),
 * so it's the one static method of that shape asking the reel's share eligibility for
 * [BLEND_SURFACE] (`LX/0BvH;->A00` on 386214574). An instance method of the same parameters sits
 * on every build; it isn't static. Fails when there isn't exactly one, or when its answer's class
 * has no public constructor of two booleans, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findBlendVisibilityCheck(): BlendVisibilityCheck {
    val checks = classesLoading(BLEND_SURFACE.toLong()).flatMap { classDef ->
        classDef.methods.filter { it.isBlendVisibilityCheck() }
            .map { BlendVisibilityCheck(classDef.type, it.name, it.parameterTypes.map(CharSequence::toString), it.returnType) }
    }
    val check = checks.singleOrNull()
        ?: throw PatchException("Hide Meta AI: ${checks.size} methods decide the Blend invite button (static, loading $BLEND_SURFACE), not one")
    val answer = classDefByOrNull(check.returnType)
        ?: throw PatchException("Hide Meta AI: the Blend answer ${check.returnType} is missing")
    val constructor = answer.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Z", "Z")
    } ?: throw PatchException("Hide Meta AI: ${check.returnType} has no constructor of two booleans")
    if (!AccessFlags.PUBLIC.isSet(constructor.accessFlags)) {
        throw PatchException("Hide Meta AI: the constructor of two booleans in ${check.returnType} isn't public")
    }
    return check
}

/** Static, (reel, session, owner id, three flags) answering an object, and loading [BLEND_SURFACE] as a narrow constant. */
internal fun Method.isBlendVisibilityCheck(): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType.startsWith("L") &&
        parameterTypes.map(CharSequence::toString).let { parameters ->
            parameters.size == 6 && parameters[0].startsWith("L") && parameters[1] == SESSION && parameters[2] == STRING &&
                parameters.drop(3).all { it == "Z" }
        } &&
        implementation?.instructions?.any {
            !it.opcode.setsWideRegister() && (it as? NarrowLiteralInstruction)?.narrowLiteral == BLEND_SURFACE
        } == true

/**
 * Makes the check answer "no Blend" from its first instruction while Hide Blend on reels is on: the
 * answer with both flags false, the one Instagram builds itself when the reel can't be shared at all.
 * The switch is read on every call, so off and Pause take Instagram's own path. The method's first
 * two locals are free at its start, and nothing jumps back there.
 */
internal fun BytecodePatchContext.holdBlendButton(check: BlendVisibilityCheck) {
    val method = mutableClassDefBy(check.type).methods.single {
        it.name == check.name && it.returnType == check.returnType &&
            it.parameterTypes.map(CharSequence::toString) == check.parameters
    }
    method.requireLocals("Hide Meta AI", 2)
    if (0 in method.jumpTargets()) throw PatchException("Hide Meta AI: something jumps back to the Blend check's first instruction")
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $BLEND_BUTTON
            move-result v0
            if-eqz v0, :build
            new-instance v0, ${check.returnType}
            const/4 v1, 0x0
            invoke-direct { v0, v1, v1 }, ${check.returnType}-><init>(ZZ)V
            return-object v0
        """,
        ExternalLabel("build", method.getInstruction(0)),
    )
}

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
import app.morphe.patches.instagram.misc.analytics.loadsString
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags

internal const val BLEND_BUTTON = "$EXTENSION_PACKAGE/metaai/MetaAi;->blendButton()Z"

/** What Instagram logs as the name of the method that decides the Blend invite button, its redex original name. */
internal const val BLEND_VISIBILITY = "android_purge_26_q2_BlendButtonVisibilityUtils_computeBlendInviteButtonVisibility"

private const val SESSION = "Lcom/instagram/common/session/UserSession;"
private const val STRING = "Ljava/lang/String;"

/** Where the Blend visibility check is: its class, name, parameters and return type (the answer's class). */
internal class BlendVisibilityCheck(val type: String, val name: String, val parameters: List<String>, val returnType: String)

/**
 * The Blend invite button on a reel is shown or hidden from the answer of one static method: it takes
 * the reel, the session, the owner's id and three flags, and answers a small value of two flags that
 * the Reels viewer's reel binder and the clips viewer both read. It's the one method naming
 * [BLEND_VISIBILITY], each itself or from a pool of shared strings. Fails when there isn't exactly
 * one, or when its answer's class has no public constructor of two booleans, since that's an update
 * this patch hasn't seen.
 */
internal fun BytecodePatchContext.findBlendVisibilityCheck(): BlendVisibilityCheck {
    val checks = classesLoadingString(BLEND_VISIBILITY).flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") &&
                method.parameterTypes.map(CharSequence::toString).let { parameters ->
                    parameters.size == 6 && parameters[1] == SESSION && parameters[2] == STRING &&
                        parameters.drop(3).all { it == "Z" }
                } && loadsString(method, BLEND_VISIBILITY)
        }.map { BlendVisibilityCheck(classDef.type, it.name, it.parameterTypes.map(CharSequence::toString), it.returnType) }
    }
    val check = checks.singleOrNull()
        ?: throw PatchException("Hide Meta AI: ${checks.size} methods decide the Blend invite button ($BLEND_VISIBILITY), not one")
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

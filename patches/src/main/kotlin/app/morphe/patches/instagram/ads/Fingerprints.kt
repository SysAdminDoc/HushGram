/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.ads

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesHolding
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * The strings only the ad-insert method holds: it writes the "Is ad pod" key into its debug map and
 * flags a "cross_surface_duplicate_ad". No other method in Instagram 449 or 450 carries both. They're
 * matched exactly, where the fingerprint this replaced matched any string containing them; each
 * is an exact constant in the insert on 449 and 450 (395, 438), so both find the same method.
 */
internal val AD_INJECTOR_STRINGS = listOf("cross_surface_duplicate_ad", "Is ad pod")

/** The ad-insert method, by the names it has in this build. Its class and name are Redex names. */
internal data class AdInjector(val type: String, val name: String, val parameters: List<String>)

/**
 * The method that puts a sponsored item into a feed and answers whether it went in: static, three
 * arguments, a boolean back, and both [AD_INJECTOR_STRINGS] in its own code. None or more than one
 * stops the patch before anything changes, so a build where the anchor moved or was copied fails
 * at patch time rather than guarding the wrong method.
 */
internal fun BytecodePatchContext.findAdInjector(): AdInjector {
    val found = classesHolding(*AD_INJECTOR_STRINGS.toTypedArray()).flatMap { classDef ->
        classDef.methods.filter { it.isAdInjector() }
            .map { AdInjector(classDef.type, it.name, it.parameterTypes.map(CharSequence::toString)) }
    }
    return found.singleOrNull()
        ?: throw PatchException("Hide ads: ${found.size} methods that put an ad into a feed in this Instagram build, not one")
}

private fun Method.isAdInjector(): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == "Z" && parameterTypes.size == 3 &&
        AD_INJECTOR_STRINGS.all { string ->
            implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
            } == true
        }

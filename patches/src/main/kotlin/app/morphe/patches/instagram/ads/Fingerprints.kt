/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.ads

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/**
 * The strings only the ad-insert method holds: it writes the "Is ad pod" key into its debug map and
 * flags a "cross_surface_duplicate_ad". No other method in Instagram 449 or 450 carries both. They're
 * matched exactly, where the fingerprint this replaced matched any string containing them. Some
 * 454 builds ask Redex's string pool for them, so a pooled load counts the same as a constant.
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
    val loading = AD_INJECTOR_STRINGS.drop(1).map { string -> classesLoadingString(string).mapTo(HashSet()) { it.type } }
    val found = classesLoadingString(AD_INJECTOR_STRINGS.first()).distinctBy { it.type }
        .filter { classDef -> loading.all { classDef.type in it } }
        .flatMap { classDef ->
            classDef.methods.filter { isAdInjector(it) }
                .map { AdInjector(classDef.type, it.name, it.parameterTypes.map(CharSequence::toString)) }
        }
    return found.singleOrNull()
        ?: throw PatchException("Hide ads: ${found.size} methods that put an ad into a feed in this Instagram build, not one")
}

private fun BytecodePatchContext.isAdInjector(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.parameterTypes.size == 3 &&
        AD_INJECTOR_STRINGS.all { string -> loadsString(method, string) }

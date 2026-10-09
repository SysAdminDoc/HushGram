/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore.recent

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Don't save recent searches"
internal const val RECENT_SEARCHES = "$EXTENSION_PACKAGE/explore/RecentSearches;"
internal const val KEEP = "$RECENT_SEARCHES->keep()Z"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** The endpoint search tells about each result you open, which keeps Recent on Instagram's side. */
internal const val REGISTER_RECENT = "fbsearch/register_recent_search_click/"

/** Two lines the recent searches cache logs when it can't write its list, which find its class. */
internal const val RECENT_CACHE = "RecentSearchCache"
internal const val RECENT_CACHE_FAILED = "Error saving recent searches. Clearing results."

/** The cache's add puts the new entry at the top of its list, which its remove doesn't. */
internal const val LIST_ADD_AT = "Ljava/util/List;->add(ILjava/lang/Object;)V"

/** The method that tells Instagram's servers about a search result you opened. */
internal object RegisterRecentSearchFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(USER_SESSION, "Ljava/lang/String;", "Ljava/lang/String;", "I"),
    strings = listOf(REGISTER_RECENT),
)

@Suppress("unused")
val dontSaveRecentSearchesPatch = bytecodePatch(
    name = "Don't save recent searches",
    description = "Keeps the accounts and tags you open from search out of your Recent list. Searches already in " +
        "Recent stay until you clear them. Starts off. Turn it on in HushGram settings > Explore.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("recentSearches")
        val register = uniqueMethod(PATCH, "method holding \"$REGISTER_RECENT\"", RegisterRecentSearchFingerprint)
        val add = findRecentAdd()
        listOf(register, add).forEach { it.askFirst() }
        enableStatus("recentSearches")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The recent searches cache's add: in the one class holding [RECENT_CACHE] and
 * [RECENT_CACHE_FAILED], the one instance method taking an object and returning nothing that
 * calls [LIST_ADD_AT].
 */
internal fun BytecodePatchContext.findRecentAdd(): MutableMethod {
    val caches = classesHolding(RECENT_CACHE, RECENT_CACHE_FAILED)
    val cache = caches.singleOrNull() ?: refuse("expected one class holding \"$RECENT_CACHE\", found ${caches.size}")
    val adds = cache.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") && method.callsListAddAt()
    }
    val add = adds.singleOrNull() ?: refuse("expected one add in ${cache.type}, found ${adds.size}")
    return mutableClassDefBy(cache.type).methods.single {
        it.name == add.name && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
    }
}

/**
 * Puts [KEEP] first: on a false the method returns before doing anything, so nothing is saved or
 * sent. Both methods return nothing, so leaving early needs no answer.
 */
internal fun MutableMethod.askFirst() {
    val free = freeLocalsAt(PATCH, 0, 1).single()
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEP
            move-result v$free
            if-nez v$free, :keep
            return-void
        """,
        ExternalLabel("keep", getInstruction(0)),
    )
}

private fun Method.callsListAddAt(): Boolean = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == LIST_ADD_AT
} == true

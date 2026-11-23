/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide suggested accounts in DMs"
internal const val INBOX_UNITS = "$EXTENSION_PACKAGE/direct/InboxSuggestions;->units(Ljava/util/List;)Ljava/util/List;"

/** The event only the method loading your messages' suggested accounts logs, when it shows ones it fetched earlier. */
internal const val PREFETCHED = "recommended_users_prefetched"

/** The name of the follow requests unit, which the class building your messages' sections of accounts reads it by. */
internal const val FOLLOW_REQUESTS = "pending_follow_requests"

/** The parameters of the method building the section: the units and a string. */
private val UNITS_SET = listOf("Ljava/util/List;", "Ljava/lang/String;")

internal const val SEARCH_SUGGESTIONS = "$EXTENSION_PACKAGE/direct/InboxSuggestions;->searchSuggestions(Ljava/util/List;)Ljava/util/List;"

/**
 * The key of the accounts you took out of More suggestions in Messages search, which only the
 * method sorting them out of that list and the one taking an account out read.
 */
internal const val REMOVED_SUGGESTIONS = "more_suggestions_removed_users"

/** The parameters of the method sorting More suggestions: the session and the accounts. */
private val SUGGESTIONS_SORT = listOf("Lcom/instagram/common/session/UserSession;", "Ljava/util/List;")

/**
 * Leaves the section of accounts to follow out of the bottom of your messages. Included in the
 * default selection with its switch initially off, so hiding it remains the user's pick.
 *
 * Only the list the section is built from changes. Your chats, follow requests and the notes row
 * aren't touched, and the accounts are still fetched.
 */
@Suppress("unused")
val hideInboxSuggestionsPatch = bytecodePatch(
    name = "Hide suggested accounts in DMs",
    description = "Takes the Accounts to follow section off the bottom of your messages, and More suggestions out " +
        "of Messages search. Your chats and follow requests stay. Starts off. Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("inboxSuggestions")
        hookInboxUnits(findInboxUnitsSet())
        findSearchSuggestionsOrWarn()?.let { hookSearchSuggestions(it) }
        enableStatus("inboxSuggestions")
    }
}

/** A method by its class, name and parameter types. */
internal class UnitsSite(val type: String, val name: String, val parameters: List<String>)

/**
 * Finds the method building your messages' section of accounts, failing before anything changes
 * when an update moved it, since that's a build this patch hasn't seen: the one instance method
 * taking [UNITS_SET] and answering nothing that the one method holding [PREFETCHED] calls, in a
 * class that holds [FOLLOW_REQUESTS], with code of its own. It has to read a unit's name through
 * an app class's getName(), which InboxSuggestions asks too, and leave follow requests to another
 * method: it mustn't hold [FOLLOW_REQUESTS] or call a method of its class that does, since the
 * hook empties every unit it's given. Only its own class's methods are looked at, so a helper
 * elsewhere reading follow requests for it would get through; on every 450 build follow requests
 * come from the loader's call to that class's own reader (A0D, A0E on 449). A method loads either
 * string itself or asks a Redex pool for it, as the reader does on 456's armeabi-v7a build
 * (`LX/01w6;->A0C` on 386214562).
 */
internal fun BytecodePatchContext.findInboxUnitsSet(): UnitsSite {
    val found = mutableListOf<Pair<String, Method>>()
    val holders = classesLoadingString(PREFETCHED).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in holders) return@classDefForEach
        classDef.methods.forEach { method -> if (loadsString(method, PREFETCHED)) found += classDef.type to method }
    }
    val (type, loader) = found.singleOrNull() ?: refuse("expected one method holding $PREFETCHED, found ${found.size}")
    val where = "$type->${loader.name}"
    val sets = loader.implementation!!.instructions.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return@mapNotNull null
        instruction.methodReference()?.takeIf {
            it.parameterTypes.map(CharSequence::toString) == UNITS_SET && it.returnType == "V"
        }?.toString()
    }.distinct()
    val set = sets.singleOrNull() ?: refuse("expected $where to hand its units to one method, found ${sets.size}")
    val owner = set.substringBefore("->")
    val name = set.substringAfter("->").substringBefore("(")
    val ownerClass = classDefByOrNull(owner) ?: refuse("$owner isn't in this build")
    val requests = ownerClass.methods.filter { loadsString(it, FOLLOW_REQUESTS) }
    if (requests.isEmpty()) refuse("$owner doesn't read $FOLLOW_REQUESTS")
    val method = ownerClass.methods.singleOrNull {
        it.name == name && it.parameterTypes.map(CharSequence::toString) == UNITS_SET && it.returnType == "V"
    } ?: refuse("$set isn't declared in $owner")
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.implementation == null) {
        refuse("$set isn't an instance method with code")
    }
    val calls = method.implementation!!.instructions.mapNotNull { it.methodReference() }
    // Instagram's own unit model, not Class.getName or Thread.getName.
    val readsName = calls.any {
        it.name == "getName" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" &&
            !it.definingClass.startsWith("Ljava/") && !it.definingClass.startsWith("Landroid/")
    }
    if (!readsName) {
        refuse("$set doesn't read a unit's name through getName()")
    }
    val readsRequests = method in requests || calls.any { call ->
        call.definingClass == owner && requests.any {
            it.name == call.name && it.returnType == call.returnType &&
                it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
        }
    }
    if (readsRequests) refuse("$set reads $FOLLOW_REQUESTS itself, so emptying its units would hide follow requests too")
    return UnitsSite(owner, name, UNITS_SET)
}

/**
 * Hands the units to [INBOX_UNITS] first thing in the method at [site] and builds the section from
 * its answer, so every caller of that method asks too.
 */
internal fun BytecodePatchContext.hookInboxUnits(site: UnitsSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters && it.returnType == "V"
    }
    // The units are p1: after this and before the string, the last of the method's registers.
    val units = method.implementation!!.registerCount - 2
    method.addInstructions(
        0,
        """
            invoke-static/range { v$units .. v$units }, $INBOX_UNITS
            move-result-object v$units
        """,
    )
}

/**
 * Finds the method More suggestions in Messages search goes through before it shows: the one static
 * `(UserSession, List)List` method loading [REMOVED_SUGGESTIONS], which takes out the accounts you
 * removed from it. The search's own list, its See all page and the other lists of suggested people
 * Instagram builds from the same answer all call it. Fails unless it's there once, with code, and
 * nothing branches back to its start.
 */
internal fun BytecodePatchContext.findSearchSuggestions(): UnitsSite {
    val found = classesLoadingString(REMOVED_SUGGESTIONS).flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/util/List;" &&
                method.parameterTypes.map(CharSequence::toString) == SUGGESTIONS_SORT && loadsString(method, REMOVED_SUGGESTIONS)
        }.map { classDef.type to it }
    }
    val (type, method) = found.singleOrNull() ?: refuse("expected one static method sorting More suggestions by $REMOVED_SUGGESTIONS, found ${found.size}")
    if (method.implementation == null) refuse("$type->${method.name} has no code")
    if (0 in method.jumpTargets()) refuse("a branch goes back to the start of $type->${method.name}")
    return UnitsSite(type, method.name, SUGGESTIONS_SORT)
}

/** More suggestions in Messages search, or null with a warning, so the inbox's section still goes. */
internal fun BytecodePatchContext.findSearchSuggestionsOrWarn(): UnitsSite? = try {
    findSearchSuggestions()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without hiding More suggestions in Messages search.")
    null
}

/**
 * Hands the accounts to [SEARCH_SUGGESTIONS] first thing in the method at [site] and sorts its
 * answer, an empty list while the switch is on, so every list of More suggestions comes out empty.
 */
internal fun BytecodePatchContext.hookSearchSuggestions(site: UnitsSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters && it.returnType == "Ljava/util/List;"
    }
    // A static method's parameters are its last registers, and the accounts are the last of them.
    val accounts = method.implementation!!.registerCount - 1
    method.addInstructions(
        0,
        """
            invoke-static/range { v$accounts .. v$accounts }, $SEARCH_SUGGESTIONS
            move-result-object v$accounts
        """,
    )
}

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

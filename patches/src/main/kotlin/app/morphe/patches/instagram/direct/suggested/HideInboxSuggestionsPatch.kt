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
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Hide suggested accounts in DMs"
internal const val INBOX_UNITS = "$EXTENSION_PACKAGE/direct/InboxSuggestions;->units(Ljava/util/List;)Ljava/util/List;"

/** The event only the method loading your messages' suggested accounts logs, when it shows ones it fetched earlier. */
internal const val PREFETCHED = "recommended_users_prefetched"

/** The name of the follow requests unit, which the class building your messages' sections of accounts reads it by. */
internal const val FOLLOW_REQUESTS = "pending_follow_requests"

/** The parameters of the method building the section: the units and a string. */
private val UNITS_SET = listOf("Ljava/util/List;", "Ljava/lang/String;")

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
    description = "Takes the Accounts to follow section off the bottom of your messages. Your chats and follow " +
        "requests stay. Starts off. Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("inboxSuggestions")
        hookInboxUnits(findInboxUnitsSet())
        enableStatus("inboxSuggestions")
    }
}

/** A method by its class, name and parameter types. */
internal class UnitsSite(val type: String, val name: String, val parameters: List<String>)

/**
 * Finds the method building your messages' section of accounts, failing before anything changes
 * when an update moved it, since that's a build this patch hasn't seen: the one instance method
 * taking [UNITS_SET] and answering nothing that the one method holding [PREFETCHED] calls, in a
 * class that holds [FOLLOW_REQUESTS], with code of its own.
 */
internal fun BytecodePatchContext.findInboxUnitsSet(): UnitsSite {
    val found = mutableListOf<Pair<String, Method>>()
    val holders = classesHolding(PREFETCHED).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in holders) return@classDefForEach
        classDef.methods.forEach { method -> if (PREFETCHED in method.strings()) found += classDef.type to method }
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
    if (classesHolding(FOLLOW_REQUESTS).none { it.type == owner }) refuse("$owner doesn't read $FOLLOW_REQUESTS")
    val method = ownerClass.methods.singleOrNull {
        it.name == name && it.parameterTypes.map(CharSequence::toString) == UNITS_SET && it.returnType == "V"
    } ?: refuse("$set isn't declared in $owner")
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.implementation == null) {
        refuse("$set isn't an instance method with code")
    }
    return UnitsSite(owner, name, UNITS_SET)
}

/**
 * Hands the units to [INBOX_UNITS] first thing in the method at [site] and builds the section from
 * its answer, so every caller of that method asks too.
 */
internal fun BytecodePatchContext.hookInboxUnits(site: UnitsSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
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

private fun Method.strings(): Set<String> = implementation?.instructions?.mapNotNull { it.string() }?.toSet() ?: emptySet()

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

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
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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

internal const val SEARCH_SECTIONS = "$EXTENSION_PACKAGE/direct/InboxSuggestions;->searchSections(Ljava/util/List;)V"

/** More suggestions' name in the list of sections Messages search builds one after another. */
internal const val SUGGESTED_SECTION = "SUGGESTED"

/** Recent searches' name in the same list, which keeps another list naming [SUGGESTED_SECTION] from being taken for it. */
internal const val RECENT_QUERIES = "RECENT_QUERIES"

private const val STRING_EQUALS = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"

/** The constants that set the walk's counters, between the last section going in and the walk's first size(). */
private val COUNTER_SETS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)

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
        findSearchSuggestionsOrWarn()?.let { sort ->
            hookSearchSuggestions(sort)
            findSearchSectionsOrWarn(sort)?.let { hookSearchSections(it) }
        }
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

/** Where Messages search builds its sections: the method, the register holding its list of section names, and where the hook goes. */
internal class SectionsSite(val type: String, val name: String, val parameters: List<String>, val returnType: String, val list: Int, val at: Int)

/**
 * Finds where Messages search builds the sections it opens on, so More suggestions goes whole,
 * its header with its rows (#826): the one method loading [SUGGESTED_SECTION] and [RECENT_QUERIES]
 * that calls the method at [sort]. It walks a list of section names, comparing each with equals,
 * and the More suggestions branch calls [sort] right after the comparison picking it. The list is
 * the one the compared name came from, a get(I) answering into the register equals was called
 * on, and the hook goes at the start of the run of constants setting the walk's counters, right
 * before the walk's first size() of the list, once every section is in. Fails unless that run is reached
 * only from above, and the instructions after its start only from the one before or from the walk
 * jumping back, so the hook runs once, ahead of the walk.
 */
internal fun BytecodePatchContext.findSearchSections(sort: UnitsSite): SectionsSite {
    val sortCall = "${sort.type}->${sort.name}(${sort.parameters.joinToString("")})Ljava/util/List;"
    val recent = classesLoadingString(RECENT_QUERIES).mapTo(HashSet()) { it.type }
    val found = classesLoadingString(SUGGESTED_SECTION).filter { it.type in recent }.flatMap { classDef ->
        classDef.methods.filter { method ->
            method.implementation?.instructions?.any { it.methodReference()?.toString() == sortCall } == true &&
                loadsString(method, SUGGESTED_SECTION) && loadsString(method, RECENT_QUERIES)
        }.map { classDef.type to it }
    }
    val (type, method) = found.singleOrNull()
        ?: refuse("expected one method naming the $SUGGESTED_SECTION and $RECENT_QUERIES sections that calls $sortCall, found ${found.size}")
    val where = "$type->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val calls = code.indices.filter { code[it].methodReference()?.toString() == sortCall }
    val call = calls.singleOrNull() ?: refuse("expected $where to call $sortCall once, found ${calls.size}")
    val equals = (call - 1 downTo 0).firstOrNull { code[it].methodReference()?.toString() == STRING_EQUALS }
        ?: refuse("$where compares no section's name before calling $sortCall")
    val name = code[equals].firstRegister() ?: refuse("$where calls equals on no register")
    val gets = (0 until equals).filter { at ->
        code[at].isListCall("get", "(I)Ljava/lang/Object;") &&
            (code.getOrNull(at + 1) as? OneRegisterInstruction)?.let { it.opcode == Opcode.MOVE_RESULT_OBJECT && it.registerA == name } == true
    }
    val get = gets.singleOrNull() ?: refuse("expected $where to take the section's name v$name out of a list once, found ${gets.size}")
    val list = code[get].firstRegister()!!
    // The walk's first size(): the nearest one of the list before the get that follows its counters.
    // The register may hold something else further up, the method's own size() of another list.
    val size = (get - 1 downTo 1).firstOrNull {
        code[it].isListCall("size", "()I") && code[it].firstRegister() == list && code[it - 1].opcode in COUNTER_SETS
    } ?: refuse("$where sets no counter right before asking how many sections v$list holds")
    var at = size
    while (at > 0 && code[at - 1].opcode in COUNTER_SETS && (code[at - 1] as OneRegisterInstruction).registerA != list) at--
    if (at == size) refuse("$where sets its counters in the sections' register v$list")
    val flow = ControlFlow.of(method)
    val into = Array(code.size) { mutableListOf<Int>() }
    code.indices.forEach { from -> (flow.normal[from] + flow.exceptional[from]).forEach { into[it] += from } }
    if (into[at].any { it >= at }) refuse("$where jumps back to instruction $at, where the hook would go")
    for (next in at + 1..size) {
        val stray = into[next].filter { it != next - 1 && it <= size }
        if (stray.isNotEmpty()) refuse("$where jumps from $stray to instruction $next, past where the hook would go")
    }
    return SectionsSite(type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType, list, at)
}

/** Messages search's sections, or null with a warning, so More suggestions still comes up empty. */
internal fun BytecodePatchContext.findSearchSectionsOrWarn(sort: UnitsSite): SectionsSite? = try {
    findSearchSections(sort)
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH empties More suggestions in Messages search but leaves its header.")
    null
}

/**
 * Hands the list of section names to [SEARCH_SECTIONS] where [site] says, keeping the label there
 * on the hook, so whatever reached the walk's counters reaches the hook first.
 */
internal fun BytecodePatchContext.hookSearchSections(site: SectionsSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters && it.returnType == site.returnType
    }
    method.addInstructionsAtControlFlowLabel(
        site.at,
        """
            invoke-static/range { v${site.list} .. v${site.list} }, $SEARCH_SECTIONS
        """,
    )
}

/** A call on java.util's lists or collections, by name and descriptor. */
private fun Instruction.isListCall(name: String, descriptor: String): Boolean {
    val call = methodReference() ?: return false
    return call.definingClass.startsWith("Ljava/util/") && call.name == name &&
        "(${call.parameterTypes.joinToString("")})${call.returnType}" == descriptor
}

/** An invoke's first register, the receiver of an instance call. */
private fun Instruction.firstRegister(): Int? = when (this) {
    is FiveRegisterInstruction -> registerC.takeIf { registerCount > 0 }
    is RegisterRangeInstruction -> startRegister.takeIf { registerCount > 0 }
    else -> null
}

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

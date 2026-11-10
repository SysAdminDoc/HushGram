/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.BitSet

/** Where the parameters of a request for a page of Home are built: what feed the page is for. */
internal const val HOME_REQUEST = "$HIDDEN_ACCOUNTS->homeRequest(Ljava/util/Map;)V"

/** Where the same builder reads the request's extra parameters, Favorites' paging source on 454. */
internal const val HOME_REQUEST_EXTRAS = "$HIDDEN_ACCOUNTS->homeRequestExtras(Ljava/util/Map;)V"

/** The request parameter naming the feed a page of Home is asked for: favorites, following or none. */
internal const val PAGINATION_SOURCE = "pagination_source"

/** Another parameter of the same builder, which no other method loads beside [PAGINATION_SOURCE]. */
internal const val EAGER_REFRESH = "is_feed_eager_refresh"

private const val LINKED_HASH_MAP = "Ljava/util/LinkedHashMap;"
private const val JAVA_MAP = "Ljava/util/Map;"
private val MAP_TYPES = setOf("Ljava/util/AbstractMap;", "Ljava/util/HashMap;", LINKED_HASH_MAP, JAVA_MAP)
private val MAP_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)

/**
 * Keep hidden accounts in Favorites' hook (#601), found: the method building every request for a
 * page of Home (For you, Following and Favorites go through it alike), the map it gathers the
 * paging source in, the instruction where it starts copying that map into the request, and where
 * it later reads the request's extra parameters ([extrasAt], into [extras]). [write] hands the map
 * to HiddenAccounts.homeRequest right there, every way in to it included, and the extras to
 * HiddenAccounts.homeRequestExtras right after they're read.
 */
internal class HomeRequestSource(
    private val method: MutableMethod,
    private val at: Int,
    private val map: Int,
    private val extrasAt: Int,
    private val extras: Int,
) {
    fun write() {
        // The later place first, so the earlier one is still where it was found.
        method.addInstructions(extrasAt + 1, "invoke-static/range { v$extras .. v$extras }, $HOME_REQUEST_EXTRAS")
        method.addInstructionsAtControlFlowLabel(at, "invoke-static/range { v$map .. v$map }, $HOME_REQUEST")
    }
}

/**
 * Finds the request hook, or answers null after the patch log says why. Hidden accounts then leaves
 * a hidden account's posts out of Favorites too, and Keep hidden accounts in Favorites isn't offered.
 *
 * The builder is the one method loading both [PAGINATION_SOURCE] and [EAGER_REFRESH] that makes a
 * LinkedHashMap, loads [PAGINATION_SOURCE] after it and then reads the map's entrySet(). The map has
 * to be there on every way in to that read: nothing reaches it without passing where the map is
 * made, and nothing writes over the map's register on the way.
 *
 * The extras are the one Map field the builder reads that other methods read their paging source
 * from right away (read the field, load [PAGINATION_SOURCE], get()). The builder has to read it
 * once, after the copy above on every way in, so a request's extras always come after its
 * parameters, and nothing may jump in right after the read.
 */
internal fun BytecodePatchContext.homeRequestSourceOrWarn(): HomeRequestSource? = try {
    val sourceMethods = classesLoadingString(PAGINATION_SOURCE).flatMap { it.methods }
    val builders = sourceMethods.filter {
        it.implementation != null && loadsString(it, PAGINATION_SOURCE) && loadsString(it, EAGER_REFRESH)
    }
    val sites = builders.flatMap { builder -> paramsCopies(builder).map { builder to it } }
    val (builder, site) = sites.singleOrNull()
        ?: throw PatchException("Keep hidden accounts in Favorites: expected one request builder copying its paging source, found ${sites.size} in ${builders.size} method(s)")
    val extrasFields = sourceMethods.flatMap { sourceReads(it) }.toSet()
    val code = builder.implementation!!.instructions.toList()
    val extrasReads = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT && code[it].referenceOrNull()?.toString() in extrasFields }
    val extrasAt = extrasReads.singleOrNull()
        ?: throw PatchException("Keep hidden accounts in Favorites: expected the request builder to read its extra parameters once, found ${extrasReads.size} read(s) of ${extrasFields.size} field(s)")
    if (extrasAt < site.first || extrasAt + 1 >= code.size || extrasAt + 1 in builder.jumpTargets() || !alwaysBefore(ControlFlow.of(builder), site.first, extrasAt)) {
        throw PatchException("Keep hidden accounts in Favorites: the request builder's extra parameters aren't surely read after its parameters")
    }
    val method = mutableClassDefBy(builder.definingClass).methods.single {
        it.name == builder.name && it.returnType == builder.returnType &&
            it.parameterTypes.map(Any::toString) == builder.parameterTypes.map(Any::toString)
    }
    HomeRequestSource(method, site.first, site.second, extrasAt, (code[extrasAt] as TwoRegisterInstruction).registerA)
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. Hidden accounts leaves those posts out of Favorites too.")
    null
}

/** Each (entrySet() read, map register) of [method] that copies a map holding the paging source. */
private fun BytecodePatchContext.paramsCopies(method: Method): List<Pair<Int, Int>> {
    val code = method.implementation?.instructions?.toList().orEmpty()
    val flow = try {
        ControlFlow.of(method)
    } catch (odd: IllegalArgumentException) {
        return emptyList()
    }
    val found = mutableListOf<Pair<Int, Int>>()
    for (made in code.indices) {
        val instruction = code[made]
        if (instruction.opcode != Opcode.NEW_INSTANCE || (instruction.referenceOrNull() as? TypeReference)?.type != LINKED_HASH_MAP) continue
        val map = (instruction as OneRegisterInstruction).registerA
        val source = (made + 1 until code.size).firstOrNull { stringLoadedAt(code, it) == PAGINATION_SOURCE } ?: continue
        val read = (source + 1 until code.size).firstOrNull { code[it].callsMap("entrySet", map) } ?: continue
        if (holdsMapAt(flow, made, map, read)) found += read to map
    }
    return found
}

/**
 * The Map fields [method] reads its paging source from right away: the field read, the key
 * loaded, and get() on what was read, within a few instructions and with nothing written over it.
 */
private fun BytecodePatchContext.sourceReads(method: Method): List<String> {
    val code = method.implementation?.instructions?.toList().orEmpty()
    return code.indices.mapNotNull { at ->
        val field = (code[at].referenceOrNull() as? FieldReference)?.takeIf { code[at].opcode == Opcode.IGET_OBJECT && it.type == JAVA_MAP }
            ?: return@mapNotNull null
        val map = (code[at] as TwoRegisterInstruction).registerA
        val get = (at + 1..minOf(at + 4, code.lastIndex)).firstOrNull { code[it].callsMap("get", map) } ?: return@mapNotNull null
        val between = at + 1 until get
        field.toString().takeIf { between.any { stringLoadedAt(code, it) == PAGINATION_SOURCE } && between.none { code[it].writesRegister(map) } }
    }
}

/** Whether every way from the method's start to [later] goes through [first]. */
private fun alwaysBefore(flow: ControlFlow, first: Int, later: Int): Boolean {
    val reached = BitSet()
    val pending = ArrayDeque<Int>()
    reached.set(0)
    pending += 0
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (at == later) return false
        if (at == first) continue
        (flow.normal[at] + flow.exceptional[at]).forEach { if (!reached[it]) { reached.set(it); pending += it } }
    }
    return true
}

/**
 * Whether [map] surely holds what [made] put in it when [read] runs: no way from the method's start,
 * or from another write of [map], gets to [read] without going through [made].
 */
private fun holdsMapAt(flow: ControlFlow, made: Int, map: Int, read: Int): Boolean {
    val reached = BitSet()
    val pending = ArrayDeque<Int>()
    fun visit(next: List<Int>) = next.forEach { if (!reached[it]) { reached.set(it); pending += it } }
    visit(listOf(0))
    flow.instructions.indices.filter { it != made && flow.instructions[it].writesRegister(map) }.forEach { visit(flow.normal[it]) }
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (at == read) return false
        // Past the map's making, the map is there; a throw from it still leaves the old value.
        if (at != made) visit(flow.normal[at])
        visit(flow.exceptional[at])
    }
    return true
}

private fun Instruction.referenceOrNull() = (this as? ReferenceInstruction)?.reference

/** Whether this calls [name] (entrySet() or get()) on [map] as a map. */
private fun Instruction.callsMap(name: String, map: Int): Boolean {
    if (opcode !in MAP_CALLS) return false
    val called = referenceOrNull() as? MethodReference ?: return false
    if (called.name != name || called.parameterTypes.size != (if (name == "get") 1 else 0) || called.definingClass !in MAP_TYPES) return false
    val receiver = when (this) {
        is FiveRegisterInstruction -> registerC
        is RegisterRangeInstruction -> startRegister
        else -> return false
    }
    return receiver == map
}

/** Whether this instruction writes [register], as the low or the high half of a wide value too. */
private fun Instruction.writesRegister(register: Int): Boolean {
    if (this is RegisterRangeInstruction || !opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesLoadingString
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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.BitSet

/** Where the parameters of a request for a page of Home are built: what feed the page is for. */
internal const val HOME_REQUEST = "$HIDDEN_ACCOUNTS->homeRequest(Ljava/util/Map;)V"

/** The request parameter naming the feed a page of Home is asked for: favorites, following or none. */
internal const val PAGINATION_SOURCE = "pagination_source"

/** Another parameter of the same builder, which no other method loads beside [PAGINATION_SOURCE]. */
internal const val EAGER_REFRESH = "is_feed_eager_refresh"

private const val LINKED_HASH_MAP = "Ljava/util/LinkedHashMap;"
private val MAP_TYPES = setOf("Ljava/util/AbstractMap;", "Ljava/util/HashMap;", LINKED_HASH_MAP, "Ljava/util/Map;")
private val ENTRY_SET_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)

/**
 * Keep hidden accounts in Favorites' hook (#601), found: the method building every request for a
 * page of Home (For you, Following and Favorites go through it alike), the map it gathers the
 * paging source in, and the instruction where it starts copying that map into the request.
 * [write] hands the map to HiddenAccounts.homeRequest right there, every way in to it included.
 */
internal class HomeRequestSource(private val method: MutableMethod, private val at: Int, private val map: Int) {
    fun write() {
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
 */
internal fun BytecodePatchContext.homeRequestSourceOrWarn(): HomeRequestSource? = try {
    val builders = classesLoadingString(PAGINATION_SOURCE).flatMap { it.methods }.filter {
        it.implementation != null && loadsString(it, PAGINATION_SOURCE) && loadsString(it, EAGER_REFRESH)
    }
    val sites = builders.flatMap { builder -> paramsCopies(builder).map { builder to it } }
    val (builder, site) = sites.singleOrNull()
        ?: throw PatchException("Keep hidden accounts in Favorites: expected one request builder copying its paging source, found ${sites.size} in ${builders.size} method(s)")
    val method = mutableClassDefBy(builder.definingClass).methods.single {
        it.name == builder.name && it.returnType == builder.returnType &&
            it.parameterTypes.map(Any::toString) == builder.parameterTypes.map(Any::toString)
    }
    HomeRequestSource(method, site.first, site.second)
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
        val read = (source + 1 until code.size).firstOrNull { code[it].readsEntrySetOf(map) } ?: continue
        if (holdsMapAt(flow, made, map, read)) found += read to map
    }
    return found
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

/** Whether this calls entrySet() on [map] as a map. */
private fun Instruction.readsEntrySetOf(map: Int): Boolean {
    if (opcode !in ENTRY_SET_CALLS) return false
    val called = referenceOrNull() as? MethodReference ?: return false
    if (called.name != "entrySet" || called.parameterTypes.isNotEmpty() || called.definingClass !in MAP_TYPES) return false
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

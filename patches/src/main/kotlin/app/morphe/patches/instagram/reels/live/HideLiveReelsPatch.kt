/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.live

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.reels.partnership.filterReelsPage
import app.morphe.patches.instagram.reels.partnership.findReelsPage
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide lives in Reels"
internal const val LIVE_REELS = "$EXTENSION_PACKAGE/reels/LiveReels;"
internal const val LIVE_PAGE_FILTER = "$LIVE_REELS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

/**
 * The marker of the method that works out what a live preview in Reels shows. It asks the Reels
 * item for its live preview part first, by that part's key, and stops when there isn't one.
 */
internal const val LIVE_PREVIEW_STATE = "ClipsLivePreviewUseCase_getUiState"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * How a Reels item is asked for its live preview part: [key], the static field holding that part's
 * key, [lookup], the item's method answering the part for a key, and [check], how a part that holds
 * a broadcast is told from one that doesn't.
 */
internal class LivePreviewKey(val key: FieldReference, val lookup: MethodReference, val check: LiveCheck)

/**
 * How the live preview code tells a live part from an empty one. Instagram hands back a part for
 * every reel, so a part alone means nothing (#566). The code casts it to [part], reads its [tree],
 * and asks the tree through [read] for the value under [id], passing a [fallback] built from the part
 * and [fallbackCase] that works the value out when the tree doesn't hold it. A live part answers a
 * String there, and the code stops on anything else.
 */
internal class LiveCheck(
    val part: String,
    val tree: FieldReference,
    val read: MethodReference,
    val id: Int,
    val fallback: MethodReference,
    val fallbackCase: Int,
)

/** How far past the lookup the check is looked for. In 454 it ends 14 instructions on. */
private const val LIVE_CHECK_REACH = 32

private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

private val LITERALS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)

/**
 * Finds [LivePreviewKey] in the one method marked [LIVE_PREVIEW_STATE]: a static field read straight
 * into a call of the Reels item's ([itemType]) one-argument method answering an Object, with that
 * field as the argument, and the [LiveCheck] that follows it. Every such read there has to agree. The
 * field, its class, the method, the item and everything the check touches all have to be public so the
 * extension's stub can reach them, and the field's type has to be the key type the method takes.
 */
internal fun BytecodePatchContext.findLivePreviewKey(itemType: String): LivePreviewKey {
    val marked = typesMarked(LIVE_PREVIEW_STATE).sorted()
        .flatMap { type -> classDefBy(type).methods.filter { LIVE_PREVIEW_STATE in it.markers() } }
    val state = marked.singleOrNull()
        ?: refuse("expected one method marked $LIVE_PREVIEW_STATE, found ${marked.size}")
    val code = state.implementation?.instructions?.toList().orEmpty()
    val targets = state.jumpTargets()
    val found = LinkedHashMap<String, Pair<FieldReference, MethodReference>>()
    val calls = mutableListOf<Int>()
    for (at in 0 until code.size - 1) {
        if (code[at].opcode != Opcode.SGET_OBJECT) continue
        val key = (code[at] as ReferenceInstruction).reference as? FieldReference ?: continue
        val held = (code[at] as OneRegisterInstruction).registerA
        val call = code[at + 1]
        if (call.opcode != Opcode.INVOKE_VIRTUAL && call.opcode != Opcode.INVOKE_VIRTUAL_RANGE) continue
        val lookup = (call as ReferenceInstruction).reference as? MethodReference ?: continue
        if (lookup.definingClass != itemType || lookup.returnType != OBJECT || lookup.parameterTypes.size != 1) continue
        if (call.registers().getOrNull(1) != held) continue
        found["$key $lookup"] = key to lookup
        calls += at + 1
    }
    val (liveKey, liveLookup) = found.values.singleOrNull() ?: refuse(
        if (found.isEmpty()) "${state.describe()} doesn't ask a Reels item ($itemType) for a part by a key"
        else "${state.describe()} asks a Reels item for more than one part: ${found.keys}",
    )
    val checks = calls.mapNotNull { liveCheck(code, targets, it) }.distinctBy { it.describe() }
    if (checks.isEmpty()) {
        refuse("${state.describe()} doesn't check the part ${liveLookup.name} answers for a String it reads with a fallback")
    }
    val check = checks.singleOrNull() ?: refuse("${state.describe()} checks the live part in more than one way")
    val live = LivePreviewKey(liveKey, liveLookup, check)

    val holder = classDefByOrNull(live.key.definingClass) ?: refuse("${live.key.definingClass} isn't a class of the app")
    val field = holder.fields.firstOrNull { it.name == live.key.name && it.type == live.key.type }
        ?: refuse("${live.key} isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(holder.accessFlags) || !AccessFlags.PUBLIC.isSet(field.accessFlags) ||
        !AccessFlags.STATIC.isSet(field.accessFlags)
    ) refuse("${live.key} isn't a public static field the extension can read")
    val taken = live.lookup.parameterTypes.single().toString()
    val keyClass = classDefByOrNull(live.key.type)
    if (live.key.type != taken && keyClass?.interfaces?.contains(taken) != true) {
        refuse("${live.key} is a ${live.key.type}, not the $taken ${live.lookup.name} takes")
    }
    val item = classDefBy(itemType)
    val method = item.methods.firstOrNull {
        it.name == live.lookup.name && it.returnType == OBJECT && it.parameterTypes.map(Any::toString) == listOf(taken)
    } ?: refuse("${live.lookup} isn't declared by $itemType")
    if (!AccessFlags.PUBLIC.isSet(item.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags) ||
        AccessFlags.STATIC.isSet(method.accessFlags)
    ) refuse("${live.lookup} isn't a public method the extension can call")
    requireReachable(check)

    val extension = classDefByOrNull(LIVE_REELS) ?: refuse("the extension has no $LIVE_REELS")
    if (extension.methods.none { it.isStaticStub("page", listOf(LIST, STRING), LIST) }) {
        refuse("the extension has no static page(List, String)List")
    }
    if (extension.methods.none { it.isStaticStub("livePreview", listOf(OBJECT), OBJECT) }) {
        refuse("the extension has no static livePreview(Object)Object to fill")
    }
    return live
}

/**
 * Reads the [LiveCheck] that follows the part lookup at [call]: the part's cast, its tree read, the
 * fallback built from the part and a literal case, and the tree's read of a literal id with that
 * fallback, answered into a String cast. Null when the code after the lookup isn't that shape.
 *
 * A literal counts only on straight code from where it's loaded to where it's used: some builds load
 * the fallback's case at the top of the method, well before the lookup (386014759), and a register
 * written again, or reached by a jump, holds nothing this can know. [targets] are the jumped-to indexes.
 */
private fun liveCheck(code: List<Instruction>, targets: Set<Int>, call: Int): LiveCheck? {
    val result = code.getOrNull(call + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: return null
    val part = (result as OneRegisterInstruction).registerA
    val cast = code.getOrNull(call + 2)?.takeIf { it.opcode == Opcode.CHECK_CAST } ?: return null
    if ((cast as OneRegisterInstruction).registerA != part) return null
    val partType = ((cast as ReferenceInstruction).reference as? TypeReference)?.type ?: return null

    val literals = HashMap<Int, Int>()
    var tree: FieldReference? = null
    var treeAt = -1
    var made: String? = null
    var madeAt = -1
    var fallback: MethodReference? = null
    var fallbackCase: Int? = null
    for (at in 0 until minOf(code.size, call + 3 + LIVE_CHECK_REACH)) {
        val instruction = code[at]
        val opcode = instruction.opcode
        if (at in targets) literals.clear()
        trackLiterals(literals, instruction)
        if (at < call + 3) continue
        when (opcode) {
            Opcode.IGET_OBJECT -> {
                val read = instruction as TwoRegisterInstruction
                val field = (instruction as ReferenceInstruction).reference as? FieldReference
                if (tree == null && read.registerB == part && field?.definingClass == partType) {
                    tree = field
                    treeAt = read.registerA
                }
            }
            Opcode.NEW_INSTANCE -> {
                madeAt = (instruction as OneRegisterInstruction).registerA
                made = ((instruction as ReferenceInstruction).reference as? TypeReference)?.type
            }
            Opcode.INVOKE_DIRECT -> {
                val method = (instruction as ReferenceInstruction).reference as? MethodReference ?: continue
                val passed = instruction.registers()
                if (method.definingClass == made && method.name == "<init>" &&
                    method.parameterTypes.map(Any::toString) == listOf(OBJECT, "I") &&
                    passed.size == 3 && passed[0] == madeAt && passed[1] == part
                ) {
                    fallback = method
                    fallbackCase = literals[passed[2]]
                }
            }
            Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> {
                val method = (instruction as ReferenceInstruction).reference as? MethodReference ?: continue
                val passed = instruction.registers()
                val treeRead = tree ?: continue
                val made0 = fallback ?: continue
                if (passed.size != 3 || passed[0] != treeAt || passed[2] != madeAt) continue
                if (method.definingClass != treeRead.type || method.returnType != OBJECT ||
                    method.parameterTypes.map(Any::toString) != listOf("I", FUNCTION0)
                ) continue
                val id = literals[passed[1]] ?: return null
                val answer = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: return null
                val string = code.getOrNull(at + 2)?.takeIf { it.opcode == Opcode.CHECK_CAST } ?: return null
                if ((string as OneRegisterInstruction).registerA != (answer as OneRegisterInstruction).registerA) return null
                if (((string as ReferenceInstruction).reference as? TypeReference)?.type != STRING) return null
                return LiveCheck(partType, treeRead, method, id, made0, fallbackCase ?: return null)
            }
            else -> {}
        }
    }
    return null
}

/**
 * Brings [literals], the narrow literal each register is known to hold, past [instruction]: a register
 * it writes loses its value (both halves of a wide write), a const sets it, and nothing is known after
 * code that doesn't fall through.
 */
private fun trackLiterals(literals: MutableMap<Int, Int>, instruction: Instruction) {
    val opcode = instruction.opcode
    if (opcode.setsRegister() && instruction is OneRegisterInstruction) {
        literals.remove(instruction.registerA)
        if (opcode.setsWideRegister()) literals.remove(instruction.registerA + 1)
    }
    if (opcode in LITERALS) {
        literals[(instruction as OneRegisterInstruction).registerA] = (instruction as NarrowLiteralInstruction).narrowLiteral
    }
    if (!opcode.canContinue()) literals.clear()
}

/** Refuses a [check] whose part, tree field, tree read or fallback the extension's stub couldn't reach. */
private fun BytecodePatchContext.requireReachable(check: LiveCheck) {
    val part = classDefByOrNull(check.part) ?: refuse("${check.part} isn't a class of the app")
    val tree = part.fields.firstOrNull { it.name == check.tree.name && it.type == check.tree.type }
        ?: refuse("${check.tree} isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(part.accessFlags) || !AccessFlags.PUBLIC.isSet(tree.accessFlags) ||
        AccessFlags.STATIC.isSet(tree.accessFlags)
    ) refuse("${check.tree} isn't a public field the extension can read")
    val treeType = classDefByOrNull(check.tree.type) ?: refuse("${check.tree.type} isn't a class of the app")
    val read = treeType.methods.firstOrNull {
        it.name == check.read.name && it.returnType == OBJECT && it.parameterTypes.map(Any::toString) == listOf("I", FUNCTION0)
    } ?: refuse("${check.read} isn't declared by ${check.tree.type}")
    if (!AccessFlags.PUBLIC.isSet(treeType.accessFlags) || !AccessFlags.INTERFACE.isSet(treeType.accessFlags) ||
        !AccessFlags.PUBLIC.isSet(read.accessFlags)
    ) refuse("${check.read} isn't a public interface method the extension can call")
    val made = classDefByOrNull(check.fallback.definingClass) ?: refuse("${check.fallback.definingClass} isn't a class of the app")
    val made0 = made.methods.firstOrNull {
        it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf(OBJECT, "I")
    } ?: refuse("${check.fallback} isn't declared by ${made.type}")
    if (!AccessFlags.PUBLIC.isSet(made.accessFlags) || !AccessFlags.PUBLIC.isSet(made0.accessFlags) ||
        AccessFlags.ABSTRACT.isSet(made.accessFlags)
    ) refuse("${check.fallback} isn't a public constructor the extension can call")
    if (FUNCTION0 !in made.interfaces) refuse("${made.type} isn't a $FUNCTION0")
}

private fun LiveCheck.describe() = "$part $tree $read $id $fallback $fallbackCase"

/**
 * Fills the extension's livePreview stub, which answers a Reels item's live preview part when it holds
 * a broadcast and null for anything else, then hands each page the Reels viewer takes to [LIVE_PAGE_FILTER] with the viewer's
 * module name, where the other Reels filters read it.
 */
internal fun BytecodePatchContext.hideLiveReels(page: ReelsPageSites, live: LivePreviewKey) {
    fillLivePreview(page.itemType, live)
    filterReelsPage(page, LIVE_PAGE_FILTER)
}

/**
 * Fills the extension's livePreview(Object)Object stub for Reels items of [itemType]. It asks the item
 * for its live preview part and answers it only when the part passes Instagram's own [LiveCheck], the
 * same read with the same fallback, so a reel's empty part answers null.
 */
internal fun BytecodePatchContext.fillLivePreview(itemType: String, live: LivePreviewKey) {
    val check = live.check
    fillStub(
        LIVE_REELS, "livePreview", OBJECT, 5,
        """
            instance-of v0, p0, $itemType
            if-nez v0, :item
            :none
            const/4 v0, 0x0
            return-object v0
            :item
            check-cast p0, $itemType
            sget-object v0, ${live.key}
            invoke-virtual { p0, v0 }, ${live.lookup}
            move-result-object v0
            instance-of v1, v0, ${check.part}
            if-eqz v1, :none
            check-cast v0, ${check.part}
            iget-object v1, v0, ${check.tree}
            if-eqz v1, :none
            new-instance v2, ${check.fallback.definingClass}
            const v3, ${check.fallbackCase}
            invoke-direct { v2, v0, v3 }, ${check.fallback}
            const v3, ${check.id}
            invoke-interface { v1, v3, v2 }, ${check.read}
            move-result-object v1
            instance-of v1, v1, $STRING
            if-eqz v1, :none
            return-object v0
        """,
    )
}

private fun Method.describe() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** The registers an invoke passes, the receiver first. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/**
 * Leaves the previews of live broadcasts Instagram puts between reels out of the Reels tab and out
 * of the reels Explore plays when a tile is tapped (#566). A viewer opened on something the user
 * chose keeps them. In the default selection with its switch off.
 */
@Suppress("unused")
val hideLiveReelsPatch = bytecodePatch(
    name = "Hide lives in Reels",
    description = "Leaves out the live broadcasts Instagram puts between reels in the Reels tab and when you swipe " +
        "through reels from Explore. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("liveReels")
        val page = findReelsPage(PATCH)
        val live = findLivePreviewKey(page.itemType)
        hideLiveReels(page, live)
        enableStatus("liveReels")
    }
}

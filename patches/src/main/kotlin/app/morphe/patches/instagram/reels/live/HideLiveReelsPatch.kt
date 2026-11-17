/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.live

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
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
internal const val LIVE_ADDED_FILTER = "$LIVE_REELS->added(Ljava/util/List;Ljava/lang/String;I)Ljava/util/List;"
internal const val LIVE_SINGLE_FILTER = "$LIVE_REELS->single(Ljava/lang/Object;Ljava/lang/String;I)I"

/**
 * The trace names of Instagram's Reels adapter's two inserts of one item at a position, which put
 * the item straight into the data source's items without its list add or insert. A live Instagram
 * slips in this way after the Reels have run a while (#566).
 */
internal val REELS_SINGLE_INSERT_TRACES = listOf(
    "ClipsStandaloneAdapterDataSource:insertItem",
    "ClipsStandaloneAdapterDataSource:insertItemGranular",
)

/**
 * The Reels viewer's data source has two ways a list joins its items: added at the end (a page, an
 * ad fetch, a refresh) or inserted at a position (a chain, items the server places). The insert loads
 * this trace name, and the add runs the helper loading [REELS_ADD_ITEM] for each item. 456 dropped
 * the purge markers 455 named both methods by.
 */
internal const val REELS_INSERT_TRACE = "ClipsStandaloneAdapterDataSource:insertItemsGranular"

/** The log label of the data source's helper that adds one item at the end. */
internal const val REELS_ADD_ITEM = "addItemInternal()"

private const val GET_MODULE_NAME = "getModuleName"

/**
 * The trace name of Instagram's Reels adapter's add, which may put a placeholder at the end of a list
 * it adds, for the next page to show in once it's loaded, and checks whether its last item already is
 * one. 455 marked that check ClipsViewerAdapter_maybeReturnGhostClipsItem; 456 dropped the marker.
 */
internal const val REELS_PLACEHOLDER = "ClipsViewerAdapter.addClipsItems"

/**
 * The image a live preview in Reels shows, which the method working out what the preview shows
 * makes from the reel's broadcast ([BROADCAST]). That method asks the Reels item for its live preview
 * part first, by that part's key, and stops when there isn't one. 455 marked it
 * ClipsLivePreviewUseCase_getUiState; 456 dropped the marker, and it loads no string of its own.
 */
internal const val LIVE_PREVIEW_IMAGE = "Lcom/instagram/common/typedurl/SimpleImageUrl;"

/** The broadcast a live reel carries, which the live preview's code reads off the reel's media. */
internal const val BROADCAST = "Lcom/instagram/api/schemas/BroadcastIntf;"

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
 * and asks the tree through [read] for the value under [id], passing a [fallback] built from
 * [fallbackCase] that works the value out when the tree doesn't hold it. A live part answers a
 * String there, and the code stops on anything else. Up to 455 [read] is the tree's own (int,
 * Function0) read and the fallback a Function0 holding the part too; from 456 it's a static helper
 * handed the tree, a Function1 fallback holding the case alone, and the id.
 */
internal class LiveCheck(
    val part: String,
    val tree: FieldReference,
    val read: MethodReference,
    val id: Int,
    val fallback: MethodReference,
    val fallbackCase: Int,
) {
    /** Whether [read] is 456's static helper rather than the tree's own read. */
    val throughHelper: Boolean get() = read.parameterTypes.size == 3
}

/** How far past the lookup the check is looked for. In 454 it ends 14 instructions on. */
private const val LIVE_CHECK_REACH = 32

private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"

private val LITERALS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)

/**
 * Finds [LivePreviewKey] in the live preview's state, the one method taking only a Reels item
 * ([itemType]) that makes a [LIVE_PREVIEW_IMAGE] and asks for a [BROADCAST]: a static field read
 * straight into a call of the item's one-argument method answering an Object, with that field as the
 * argument, and the [LiveCheck] that follows it. Every such read there has to agree. The field, its
 * class, the method, the item and everything the check touches all have to be public so the
 * extension's stub can reach them, and the field's type has to be the key type the method takes.
 */
internal fun BytecodePatchContext.findLivePreviewKey(itemType: String): LivePreviewKey {
    val states = classesCreating(LIVE_PREVIEW_IMAGE).sortedBy { it.type }.flatMap { it.methods }.filter { method ->
        val code = method.implementation?.instructions ?: return@filter false
        method.parameterTypes.map(Any::toString) == listOf(itemType) &&
            code.any { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == LIVE_PREVIEW_IMAGE } &&
            code.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.returnType == BROADCAST }
    }
    val state = states.singleOrNull() ?: refuse(
        "expected one method taking a Reels item ($itemType) that makes a $LIVE_PREVIEW_IMAGE from a $BROADCAST, " +
            "found ${states.map { it.describe() }}",
    )
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
                if (method.definingClass != made || method.name != "<init>" || passed.firstOrNull() != madeAt) continue
                val parameters = method.parameterTypes.map(Any::toString)
                if (parameters == listOf(OBJECT, "I") && passed.size == 3 && passed[1] == part) {
                    fallback = method
                    fallbackCase = literals[passed[2]]
                } else if (parameters == listOf("I") && passed.size == 2) {
                    // 456's fallback holds the case alone.
                    fallback = method
                    fallbackCase = literals[passed[1]]
                }
            }
            Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> {
                val method = (instruction as ReferenceInstruction).reference as? MethodReference ?: continue
                val passed = instruction.registers()
                val treeRead = tree ?: continue
                val made0 = fallback ?: continue
                val parameters = method.parameterTypes.map(Any::toString)
                val held = made0.parameterTypes.map(Any::toString)
                val idAt = if (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) {
                    // 456 reads the tree through a static helper handed the tree, the fallback and the id.
                    if (passed.size != 3 || passed[0] != treeAt || passed[1] != madeAt || held != listOf("I")) continue
                    if (method.returnType != OBJECT || parameters != listOf(treeRead.type, FUNCTION1, "I")) continue
                    passed[2]
                } else {
                    if (passed.size != 3 || passed[0] != treeAt || passed[2] != madeAt || held != listOf(OBJECT, "I")) continue
                    if (method.definingClass != treeRead.type || method.returnType != OBJECT || parameters != listOf("I", FUNCTION0)) continue
                    passed[1]
                }
                val id = literals[idAt] ?: return null
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
    val readParameters = check.read.parameterTypes.map(Any::toString)
    if (check.throughHelper) {
        val helper = classDefByOrNull(check.read.definingClass) ?: refuse("${check.read.definingClass} isn't a class of the app")
        val read = helper.methods.firstOrNull {
            it.name == check.read.name && it.returnType == OBJECT && it.parameterTypes.map(Any::toString) == readParameters
        } ?: refuse("${check.read} isn't declared by ${helper.type}")
        if (!AccessFlags.PUBLIC.isSet(helper.accessFlags) || !AccessFlags.PUBLIC.isSet(read.accessFlags) ||
            !AccessFlags.STATIC.isSet(read.accessFlags)
        ) refuse("${check.read} isn't a public static method the extension can call")
    } else {
        val treeType = classDefByOrNull(check.tree.type) ?: refuse("${check.tree.type} isn't a class of the app")
        val read = treeType.methods.firstOrNull {
            it.name == check.read.name && it.returnType == OBJECT && it.parameterTypes.map(Any::toString) == listOf("I", FUNCTION0)
        } ?: refuse("${check.read} isn't declared by ${check.tree.type}")
        if (!AccessFlags.PUBLIC.isSet(treeType.accessFlags) || !AccessFlags.INTERFACE.isSet(treeType.accessFlags) ||
            !AccessFlags.PUBLIC.isSet(read.accessFlags)
        ) refuse("${check.read} isn't a public interface method the extension can call")
    }
    val fallbackParameters = check.fallback.parameterTypes.map(Any::toString)
    val made = classDefByOrNull(check.fallback.definingClass) ?: refuse("${check.fallback.definingClass} isn't a class of the app")
    val made0 = made.methods.firstOrNull {
        it.name == "<init>" && it.parameterTypes.map(Any::toString) == fallbackParameters
    } ?: refuse("${check.fallback} isn't declared by ${made.type}")
    if (!AccessFlags.PUBLIC.isSet(made.accessFlags) || !AccessFlags.PUBLIC.isSet(made0.accessFlags) ||
        AccessFlags.ABSTRACT.isSet(made.accessFlags)
    ) refuse("${check.fallback} isn't a public constructor the extension can call")
    val function = if (check.throughHelper) FUNCTION1 else FUNCTION0
    if (function !in made.interfaces) refuse("${made.type} isn't a $function")
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
            ${if (check.throughHelper) "invoke-direct { v2, v3 }, ${check.fallback}" else "invoke-direct { v2, v0, v3 }, ${check.fallback}"}
            const v3, ${check.id}
            ${if (check.throughHelper) "invoke-static { v1, v2, v3 }, ${check.read}" else "invoke-interface { v1, v3, v2 }, ${check.read}"}
            move-result-object v1
            instance-of v1, v1, $STRING
            if-eqz v1, :none
            return-object v0
        """,
    )
}

/**
 * How a Reels item is told for the placeholder Instagram's adapter puts at the end of a list it adds
 * while the next page loads: its [kind], read off the item, is the one the static [placeholder] holds.
 */
internal class ReelsPlaceholder(val kind: FieldReference, val placeholder: FieldReference)

/**
 * Finds [ReelsPlaceholder] in the methods loading [REELS_PLACEHOLDER], where the adapter checks
 * whether its last item is the placeholder already: a field of the Reels item ([itemType]) read,
 * straight followed by a static of that field's type read off that type, the two compared at once,
 * with nothing jumping in between. Every such compare there has to agree, and the fields and the
 * classes holding them have to be public so the extension's stub can reach them.
 */
internal fun BytecodePatchContext.findReelsPlaceholder(itemType: String): ReelsPlaceholder {
    val marked = classesLoadingString(REELS_PLACEHOLDER).sortedBy { it.type }
        .flatMap { it.methods }.filter { loadsString(it, REELS_PLACEHOLDER) }
    if (marked.isEmpty()) refuse("expected a method loading $REELS_PLACEHOLDER, found none")
    val found = LinkedHashMap<String, ReelsPlaceholder>()
    for (method in marked) {
        val code = method.implementation?.instructions?.toList().orEmpty()
        val targets = method.jumpTargets()
        for (at in 0 until code.size - 2) {
            if (code[at].opcode != Opcode.IGET_OBJECT || code[at + 1].opcode != Opcode.SGET_OBJECT) continue
            val compare = code[at + 2]
            if (compare.opcode != Opcode.IF_EQ && compare.opcode != Opcode.IF_NE) continue
            if (at + 1 in targets || at + 2 in targets) continue
            val kind = (code[at] as ReferenceInstruction).reference as? FieldReference ?: continue
            val placeholder = (code[at + 1] as ReferenceInstruction).reference as? FieldReference ?: continue
            if (kind.definingClass != itemType || placeholder.definingClass != kind.type || placeholder.type != kind.type) continue
            val read = (code[at] as TwoRegisterInstruction).registerA
            val held = (code[at + 1] as OneRegisterInstruction).registerA
            val compared = compare as TwoRegisterInstruction
            if (read == held || setOf(compared.registerA, compared.registerB) != setOf(read, held)) continue
            found["$kind $placeholder"] = ReelsPlaceholder(kind, placeholder)
        }
    }
    val check = found.values.singleOrNull() ?: refuse(
        if (found.isEmpty()) "${marked.map { it.describe() }} don't compare a Reels item's ($itemType) kind with a placeholder's"
        else "the methods loading $REELS_PLACEHOLDER tell the placeholder in more than one way: ${found.keys}",
    )

    val item = classDefBy(itemType)
    val kind = item.fields.firstOrNull { it.name == check.kind.name && it.type == check.kind.type }
        ?: refuse("${check.kind} isn't declared by $itemType")
    if (!AccessFlags.PUBLIC.isSet(item.accessFlags) || !AccessFlags.PUBLIC.isSet(kind.accessFlags) ||
        AccessFlags.STATIC.isSet(kind.accessFlags)
    ) refuse("${check.kind} isn't a public field the extension can read")
    val holder = classDefByOrNull(check.placeholder.definingClass) ?: refuse("${check.placeholder.definingClass} isn't a class of the app")
    val placeholder = holder.fields.firstOrNull { it.name == check.placeholder.name && it.type == check.placeholder.type }
        ?: refuse("${check.placeholder} isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(holder.accessFlags) || !AccessFlags.PUBLIC.isSet(placeholder.accessFlags) ||
        !AccessFlags.STATIC.isSet(placeholder.accessFlags)
    ) refuse("${check.placeholder} isn't a public static field the extension can read")
    if (classDefByOrNull(LIVE_REELS)?.methods?.none { it.isStaticStub("placeholder", listOf(OBJECT), "Z") } != false) {
        refuse("the extension has no static placeholder(Object)boolean to fill")
    }
    return check
}

/**
 * Fills the extension's placeholder(Object)boolean stub for Reels items of [itemType]: true when the
 * item's kind is the placeholder's, the compare Instagram's adapter makes, and false for anything else.
 */
internal fun BytecodePatchContext.fillReelsPlaceholder(itemType: String, check: ReelsPlaceholder) {
    fillStub(
        LIVE_REELS, "placeholder", "Z", 3,
        """
            instance-of v0, p0, $itemType
            if-eqz v0, :no
            check-cast p0, $itemType
            iget-object v0, p0, ${check.kind}
            sget-object v1, ${check.placeholder}
            if-ne v0, v1, :no
            const/4 v0, 0x1
            return v0
            :no
            const/4 v0, 0x0
            return v0
        """,
    )
}

/**
 * Where the Reels viewer's data source, [dataSource], takes a list into its items: [inserts], each
 * method's descriptor with the index of its List parameter, its [moduleField], the viewer's module
 * name, and [size], how many items it holds.
 */
internal class ReelsItemInserts(
    val dataSource: String,
    val moduleField: FieldReference,
    val size: MethodReference,
    val inserts: List<Pair<String, Int>>,
)

/**
 * Finds [ReelsItemInserts]: the one method loading [REELS_INSERT_TRACE], an instance (int, List)Z, and
 * on its class the instance (List)V that runs the one helper loading [REELS_ADD_ITEM]. The add may hand
 * its list to a sibling (List)V running the helper too, so it's the one no other such sibling calls.
 * That class keeps what its viewer's getModuleName answered in one String field in its constructor,
 * which nothing else writes, and answers its own size()I. Each method needs three locals for the hook
 * and nothing jumping to its first instruction, and [extension] a static added(List, String, int)List.
 * A failure names [patch].
 */
internal fun BytecodePatchContext.findReelsItemInserts(patch: String = PATCH, extension: String = LIVE_REELS): ReelsItemInserts {
    fun refuse(detail: String): Nothing = throw PatchException("$patch: $detail")
    val traced = classesLoadingString(REELS_INSERT_TRACE).flatMap { it.methods }.filter { loadsString(it, REELS_INSERT_TRACE) }
    val insert = traced.singleOrNull() ?: refuse("expected one method loading $REELS_INSERT_TRACE, found ${traced.size}")
    val dataSource = insert.definingClass
    val source = classDefBy(dataSource)
    val helpers = source.methods.filter { loadsString(it, REELS_ADD_ITEM) }
    val helper = helpers.singleOrNull()
        ?: refuse("expected one method of $dataSource loading $REELS_ADD_ITEM, found ${helpers.map { it.describe() }}")
    val adding = source.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.map(Any::toString) == listOf(LIST) &&
            method.returnType == "V" && method.calls(helper)
    }
    val outer = adding.filter { method -> adding.none { other -> other !== method && other.calls(method) } }
    val add = outer.singleOrNull()
        ?: refuse("expected one (List)V of $dataSource adding its items through ${helper.describe()}, found ${outer.map { it.describe() }}")
    val shapes = listOf(add to (listOf(LIST) to "V"), insert to (listOf("I", LIST) to "Z"))
    for ((method, shape) in shapes) {
        val (parameters, returns) = shape
        if (AccessFlags.STATIC.isSet(method.accessFlags) || method.implementation == null ||
            method.parameterTypes.map(Any::toString) != parameters || method.returnType != returns
        ) refuse("expected ${method.describe()} to be an instance method taking $parameters and answering $returns")
    }

    val kept = source.methods.filter { it.name == "<init>" }.flatMap { constructor ->
        val code = constructor.implementation?.instructions?.toList().orEmpty()
        val self = constructor.localRegisterCount()
        val targets = constructor.jumpTargets()
        code.indices.mapNotNull { at ->
            val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference ?: return@mapNotNull null
            if (code[at].opcode != Opcode.IPUT_OBJECT || field.definingClass != dataSource || field.type != STRING) return@mapNotNull null
            val write = code[at] as TwoRegisterInstruction
            if (write.registerB != self || at < 2 || at in targets || at - 1 in targets) return@mapNotNull null
            val result = code[at - 1].takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction
            val called = (code[at - 2] as? ReferenceInstruction)?.reference as? MethodReference
            if (result?.registerA != write.registerA || called == null || called.name != GET_MODULE_NAME ||
                called.parameterTypes.isNotEmpty() || called.returnType != STRING
            ) return@mapNotNull null
            field
        }
    }.distinctBy { it.toString() }
    val moduleField = kept.singleOrNull()
        ?: refuse("expected $dataSource's constructor to keep one module name from $GET_MODULE_NAME, found ${kept.map { it.name }}")
    val writers = source.methods.filter { method ->
        method.implementation?.instructions?.any { instruction ->
            val written = (instruction as? ReferenceInstruction)?.reference as? FieldReference
            instruction.opcode == Opcode.IPUT_OBJECT && written?.definingClass == dataSource && written.name == moduleField.name
        } == true
    }
    val outside = classesAccessing(dataSource, moduleField.name, Opcode.IPUT_OBJECT).map { it.type }.filter { it != dataSource }
    if (writers.any { it.name != "<init>" } || outside.isNotEmpty()) {
        refuse("$moduleField is written by ${writers.filter { it.name != "<init>" }.map { it.describe() } + outside}, not only $dataSource's constructor")
    }

    val size = source.methods.firstOrNull {
        it.name == "size" && it.parameterTypes.isEmpty() && it.returnType == "I" &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: refuse("$dataSource doesn't answer its own size()I")

    val inserts = shapes.map { (method, _) ->
        if (method.localRegisterCount() < 3) refuse("${method.describe()} has ${method.localRegisterCount()} local register(s), needs 3")
        if (0 in method.jumpTargets()) refuse("${method.describe()} jumps back to its first instruction")
        if ((method.implementation?.registerCount ?: 0) > 256) refuse("${method.describe()} has more registers than the hook can name")
        "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}" to method.parameterTypes.map(Any::toString).indexOf(LIST)
    }
    if (classDefByOrNull(extension)?.methods?.none { it.isStaticStub("added", listOf(LIST, STRING, "I"), LIST) } != false) {
        refuse("the extension has no static added(List, String, int)List")
    }
    return ReelsItemInserts(dataSource, moduleField, size, inserts)
}

/**
 * Hands the list each of [sites]' inserts takes to [filter] first thing, with the data source's
 * module name and size, and puts the list it answers in the parameter's place. Nothing jumps to the
 * first instruction and the locals hold nothing yet, so the hook borrows v0 to v2. A second patch's
 * hook goes in ahead of the first, and both read the list from the parameter.
 */
internal fun BytecodePatchContext.filterReelsItemInserts(sites: ReelsItemInserts, filter: String = LIVE_ADDED_FILTER) {
    val methods = mutableClassDefBy(sites.dataSource).methods
    for ((descriptor, listIndex) in sites.inserts) {
        val method = methods.single { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == descriptor }
        val list = method.parameterRegister(listIndex)
        method.addInstructions(
            0,
            """
                move-object/from16 v0, p0
                invoke-virtual { v0 }, ${sites.size}
                move-result v1
                iget-object v0, v0, ${sites.moduleField}
                move-object/from16 v2, $list
                invoke-static { v2, v0, v1 }, $filter
                move-result-object v2
                move-object/from16 $list, v2
            """,
        )
    }
}

/**
 * One of the Reels adapter's inserts of a single item: the method's [type] and [descriptor], and
 * [dataSource], the adapter's field holding the data source the item goes into.
 */
internal class ReelsSingleInsert(val type: String, val descriptor: String, val dataSource: FieldReference)

/**
 * Finds the [ReelsSingleInsert]s: for each of [REELS_SINGLE_INSERT_TRACES], the one method loading
 * it, an instance (item, int)Z with no switch (a Redex merge of several methods dispatches on one),
 * three locals for the hook and nothing jumping to its first instruction. Its field is the one it
 * reads off this and casts straight to [sites]' data source, read from Instagram's own code. The
 * extension needs a static single(Object, String, int)int.
 */
internal fun BytecodePatchContext.findReelsSingleInserts(sites: ReelsItemInserts): List<ReelsSingleInsert> {
    val found = REELS_SINGLE_INSERT_TRACES.map { trace ->
        val traced = classesLoadingString(trace).flatMap { it.methods }.filter { loadsString(it, trace) }
        val insert = traced.singleOrNull() ?: refuse("expected one method loading $trace, found ${traced.size}")
        val parameters = insert.parameterTypes.map(Any::toString)
        if (AccessFlags.STATIC.isSet(insert.accessFlags) || insert.implementation == null || parameters.size != 2 ||
            !parameters[0].startsWith("L") || parameters[1] != "I" || insert.returnType != "Z"
        ) refuse("expected ${insert.describe()} to be an instance method taking an item and an int and answering Z")
        val code = insert.implementation?.instructions?.toList().orEmpty()
        if (code.any { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }) {
            refuse("${insert.describe()} switches, so it may be several methods in one")
        }
        if (insert.localRegisterCount() < 3) refuse("${insert.describe()} has ${insert.localRegisterCount()} local register(s), needs 3")
        if (0 in insert.jumpTargets()) refuse("${insert.describe()} jumps back to its first instruction")
        val self = insert.localRegisterCount()
        val fields = code.indices.mapNotNull { at ->
            val read = code[at]
            val field = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return@mapNotNull null
            if (read.opcode != Opcode.IGET_OBJECT || (read as TwoRegisterInstruction).registerB != self ||
                field.definingClass != insert.definingClass
            ) return@mapNotNull null
            val cast = code.getOrNull(at + 1)
            val type = (cast as? ReferenceInstruction)?.reference as? TypeReference
            if (cast?.opcode != Opcode.CHECK_CAST || (cast as OneRegisterInstruction).registerA != read.registerA ||
                type?.type != sites.dataSource
            ) return@mapNotNull null
            field
        }.distinctBy { it.toString() }
        val field = fields.singleOrNull()
            ?: refuse("expected ${insert.describe()} to read one field of its own as ${sites.dataSource}, found ${fields.map { it.name }}")
        ReelsSingleInsert(insert.definingClass, "${insert.name}(${parameters.joinToString("")})Z", field)
    }
    if (classDefByOrNull(LIVE_REELS)?.methods?.none { it.isStaticStub("single", listOf(OBJECT, STRING, "I"), "I") } != false) {
        refuse("the extension has no static single(Object, String, int)int")
    }
    return found
}

/**
 * Hands the item each of [inserts] takes to [LIVE_SINGLE_FILTER] first thing, with the module name
 * and size of the data source it goes into, and answers false, as Instagram's insert does for a
 * position it can't take, when the filter says the item is a live to leave out. An adapter whose
 * field holds something else inserts as it would. The return is gated on the item, so the rest of
 * the insert runs for anything that isn't a live. Nothing jumps to the first instruction and the
 * locals hold nothing yet, so the hook borrows v0 to v2.
 */
internal fun BytecodePatchContext.filterReelsSingleInserts(sites: ReelsItemInserts, inserts: List<ReelsSingleInsert>) {
    for (insert in inserts) {
        val method = mutableClassDefBy(insert.type).methods.single {
            "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == insert.descriptor
        }
        val item = method.parameterRegister(0)
        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                iget-object v0, v0, ${insert.dataSource}
                instance-of v1, v0, ${sites.dataSource}
                if-eqz v1, :insert
                check-cast v0, ${sites.dataSource}
                invoke-virtual { v0 }, ${sites.size}
                move-result v1
                iget-object v0, v0, ${sites.moduleField}
                move-object/from16 v2, $item
                invoke-static { v2, v0, v1 }, $LIVE_SINGLE_FILTER
                move-result v0
                if-eqz v0, :insert
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("insert", method.getInstruction(0)),
        )
    }
}

private fun Method.describe() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** Whether this method invokes [target]. */
private fun Method.calls(target: Method): Boolean = implementation?.instructions?.any { instruction ->
    val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
    called.definingClass == target.definingClass && called.name == target.name &&
        called.returnType == target.returnType &&
        called.parameterTypes.map(Any::toString) == target.parameterTypes.map(Any::toString)
} == true

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
        val inserts = findReelsItemInserts()
        val singles = findReelsSingleInserts(inserts)
        val placeholder = findReelsPlaceholder(page.itemType)
        hideLiveReels(page, live)
        fillReelsPlaceholder(page.itemType, placeholder)
        filterReelsItemInserts(inserts)
        filterReelsSingleInserts(inserts, singles)
        enableStatus("liveReels")
    }
}

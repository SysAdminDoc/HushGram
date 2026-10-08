/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.analytics

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The only string the Bloks screen opener's full-screen method holds that no other method does. */
internal const val SCREEN_FETCH = "BKDataFetcher.fetch"
internal val DIRECT_SCREEN_ACTION = listOf("null_param_openScreenOptions", "null_param_appId", "cds_push_invocation_start", "cds_push_invocation_end")
internal val DIRECT_SCREEN_PRESENTER = listOf("FragmentActivity is required to open CDS bottom sheet", "foa_bottom_sheet_config", "cds_bloks")

private const val SCREEN_CONFIG = "Lcom/instagram/bloks/hosting/IgBloksScreenConfig;"

/** What the opener's constructor takes: the screen's app id, then two maps of its parameters. */
private val OPENER_CONSTRUCTOR = listOf("Ljava/lang/String;", "Ljava/util/Map;", "Ljava/util/Map;")

/** An instance method opening a Bloks screen: full screen, bottom sheet, or pushed. */
private fun Method.opensScreen() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
    parameterTypes.map(Any::toString) == listOf("Landroid/content/Context;", SCREEN_CONFIG)

/**
 * Asks [hook] with the screen's app id first thing in each of the Bloks screen opener's ways of
 * showing a screen, and on a yes returns before it shows anything. The server sends the "Set up on
 * new device" screens, which ask for contacts and location, as Bloks screens by app id, so the app
 * id is the one thing that tells them apart. They come back on every start while the events saying
 * they were seen are refused, which is why Disable analytics skips them.
 *
 * The opener keeps the app id in the String field its (String, Map, Map) constructor stores its
 * first argument in; the hook reads that field off `this` itself, so it doesn't matter which of the
 * opener's methods the server's screen goes through. A second route presents immutable screen data
 * directly; its checked app id is the constructor's second String. Null screen data keeps the native
 * path. Both routes are discovered before either is changed. Answers null on success, or why not.
 */
internal fun BytecodePatchContext.skipSetupScreens(hook: String): String? {
    // Every search here goes through the patcher's string index (#60); the checks on each method stay as they were.
    val openers = classesHolding(SCREEN_FETCH).filter { classDef ->
        classDef.methods.any { it.opensScreen() && SCREEN_FETCH in it.strings() }
    }.map { it.type }
    val opener = openers.distinct().singleOrNull()
        ?: return "expected one class opening a Bloks screen with $SCREEN_FETCH, found ${openers.distinct().size}"
    val mutable = mutableClassDefBy(opener)

    val constructor = mutable.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(Any::toString) == OPENER_CONSTRUCTOR
    } ?: return "$opener has no (String, Map, Map) constructor to read the screen's app id from"
    val appId = constructor.argumentField(0)
        ?: return "$opener's constructor doesn't keep its app id in exactly one unchanged String field"

    val methods = mutable.methods.filter { it.opensScreen() }
    // `this` sits right under the two arguments; v0 must be a local below it to hold the answer.
    methods.firstOrNull { it.implementation?.let { body -> body.registerCount - 3 < 1 || !body.instructions.any() } != false }?.let {
        return "$opener->${it.name} has no body or spare register"
    }
    val direct = try {
        findDirectSetupPresenter()
    } catch (failure: SetupDiscoveryFailure) {
        return failure.message
    }
    methods.forEach { method ->
        val self = method.implementation!!.registerCount - 3
        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, v$self
                iget-object v0, v0, $appId
                invoke-static { v0 }, $hook
                move-result v0
                if-eqz v0, :open
                return-void
            """,
            ExternalLabel("open", method.getInstruction(0)),
        )
    }
    val presenter = mutableClassDefBy(direct.type).methods.single {
        it.name == direct.name && it.returnType == "V" && it.parameterTypes.map(Any::toString) == direct.parameters
    }
    presenter.addInstructionsWithLabels(0, """
        move-object/from16 v0, v${direct.modelRegister}
        if-eqz v0, :open
        iget-object v0, v0, ${direct.appId}
        invoke-static { v0 }, $hook
        move-result v0
        if-eqz v0, :open
        return-void
    """, ExternalLabel("open", presenter.getInstruction(0)))
    return null
}

private class DirectSetupPresenter(val type: String, val name: String, val parameters: List<String>,
                                   val modelRegister: Int, val appId: FieldReference)
private class SetupDiscoveryFailure(message: String) : RuntimeException(message)
private fun refuseSetup(detail: String): Nothing = throw SetupDiscoveryFailure("alternate setup route: $detail")

/** Join the action's actual app-id argument to its public immutable field and a void presenter. */
private fun BytecodePatchContext.findDirectSetupPresenter(): DirectSetupPresenter {
    val actions = classesHolding(*DIRECT_SCREEN_ACTION.toTypedArray()).asSequence().flatMap { it.methods.asSequence() }.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 2 && method.returnType == "Ljava/lang/Object;" &&
            method.strings().containsAll(DIRECT_SCREEN_ACTION)
    }.toList()
    val action = actions.singleOrNull() ?: refuseSetup("${actions.size} openScreenOptions actions, not one")
    val code = action.implementation!!.instructions.toList()
    val calls = code.indices.filter { index ->
        val ref = code[index].screenMethod()
        code[index].opcode in listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) && ref?.returnType == "V" &&
            ref.parameterTypes.size == 6 && ref.parameterTypes[0].toString() == "Landroid/content/Context;" &&
            ref.parameterTypes[2].toString() == SCREEN_CONFIG && ref.parameterTypes[5].toString() == "I" &&
            listOf(1, 3, 4).all { ref.parameterTypes[it].toString().startsWith("L") }
    }
    val call = calls.singleOrNull() ?: refuseSetup("${calls.size} void direct screen calls, not one")
    val reference = code[call].screenMethod()!!
    val presenters = classesHolding(DIRECT_SCREEN_PRESENTER.first()).asSequence().flatMap { it.methods.asSequence() }.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.size == 6 &&
            it.parameterTypes[0].toString() == "Landroid/content/Context;" && it.parameterTypes[2].toString() == SCREEN_CONFIG &&
            it.parameterTypes[5].toString() == "I" && listOf(1, 3, 4).all { index -> it.parameterTypes[index].toString().startsWith("L") } &&
            // 450 asks a pool of shared strings for some of them.
            DIRECT_SCREEN_PRESENTER.first() in it.strings() && DIRECT_SCREEN_PRESENTER.all { value -> loadsString(it, value) }
    }.toList()
    val presenter = presenters.singleOrNull() ?: refuseSetup("${presenters.size} marked void presenters, not one")
    if (presenter.toString() != reference.toString()) refuseSetup("action calls another presenter")
    val firstParameter = (presenter.implementation?.registerCount ?: refuseSetup("presenter has no body")) - 6
    if (firstParameter < 1) refuseSetup("presenter has no spare local")
    val modelType = reference.parameterTypes[1].toString()
    val model = classDefByOrNull(modelType) ?: refuseSetup("screen data class is absent")
    if (!AccessFlags.PUBLIC.isSet(model.accessFlags)) refuseSetup("screen data class isn't public")
    val constructor = model.methods.singleOrNull { it.name == "<init>" && it.returnType == "V" &&
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.directScreenConstructor() }
        ?: refuseSetup("screen data constructor is absent or ambiguous")
    val appId = constructor.argumentField(5) ?: refuseSetup("second String argument has no unique unchanged field")
    if (model.fields.singleOrNull { it.toString() == appId.toString() }?.let {
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.FINAL.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
        } != true) {
        refuseSetup("screen app-id field isn't public and immutable")
    }
    val constructors = code.indices.filter { index -> code[index].opcode in listOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE) &&
        code[index].screenMethod()?.toString() == constructor.toString() }
    val construction = constructors.singleOrNull() ?: refuseSetup("${constructors.size} screen data constructions, not one")
    if (construction >= call) refuseSetup("screen data isn't constructed before presentation")
    val appMarker = code.indices.singleOrNull { code[it].screenString() == "null_param_appId" }
        ?: refuseSetup("app-id null check marker is absent or duplicated")
    val checked = code.getOrNull(appMarker - 1) as? OneRegisterInstruction
    if (checked == null || code[appMarker - 1].opcode != Opcode.IF_NEZ ||
        code.getOrNull(appMarker - 2)?.opcode != Opcode.MOVE_RESULT_OBJECT ||
        (code[appMarker - 2] as OneRegisterInstruction).registerA != checked.registerA ||
        code.getOrNull(appMarker - 3)?.screenMethod()?.returnType != "Ljava/lang/String;" ||
        code[construction].screenArguments().getOrNull(6) != checked.registerA) refuseSetup("the checked app id isn't the second String passed to the screen data")
    val checkedPath = code.screenTarget(appMarker - 1)
    if (checkedPath == null || checkedPath !in (appMarker + 1)..construction ||
        !action.screenValueReaches(appMarker - 2, checked.registerA, construction, checked.registerA)) {
        refuseSetup("the checked app id doesn't reach screen data unchanged")
    }
    val source = code[construction].screenArguments().firstOrNull() ?: refuseSetup("screen data construction has no receiver")
    val allocation = code.take(construction).indexOfLast { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == source }
    if (allocation < 0 || code[allocation].opcode != Opcode.NEW_INSTANCE || code[allocation].screenReference() != modelType ||
        !action.screenValueReaches(allocation, source, construction, source)) refuseSetup("screen data isn't newly initialized")
    val received = code[call].screenArguments().getOrNull(1) ?: refuseSetup("presenter has no screen data argument")
    if (!action.screenValueReaches(construction, source, call, received)) refuseSetup("presenter doesn't always receive the constructed screen data")
    return DirectSetupPresenter(reference.definingClass, reference.name, reference.parameterTypes.map(Any::toString), firstParameter + 1, appId)
}

/** A value must survive every path, including handler entries, rather than one linear copy chain. */
private fun Method.screenValueReaches(seed: Int, source: Int, target: Int, register: Int): Boolean {
    val flow = try { ControlFlow.of(this) } catch (_: IllegalArgumentException) { return false }
    val code = flow.instructions
    val roots = setOf(0) + flow.exceptional.flatMap { it }
    val before = arrayOfNulls<Set<Int>>(code.size)
    val queue = ArrayDeque<Int>()
    roots.forEach { before[it] = emptySet(); queue += it }
    while (queue.isNotEmpty()) {
        val index = queue.removeFirst()
        val instruction = code[index]
        val aliases = before[index]!!.toMutableSet()
        if (instruction.opcode.setsRegister()) {
            val destination = (instruction as? OneRegisterInstruction)?.registerA ?: return false
            val copied = instruction.opcode in OBJECT_MOVES && (instruction as TwoRegisterInstruction).registerB in aliases
            aliases -= destination
            if (copied) aliases += destination
            if (instruction.opcode.setsWideRegister()) aliases -= destination + 1
        }
        if (index == seed) aliases += source
        for (next in flow.normal[index]) {
            val old = before[next]
            val joined = old?.intersect(aliases) ?: aliases.toSet()
            if (old != joined) { before[next] = joined; queue += next }
        }
    }
    return before[target]?.contains(register) == true
}

private fun List<Instruction>.screenTarget(index: Int): Int? {
    val offset = this[index] as? OffsetInstruction ?: return null
    val address = take(index).sumOf { it.codeUnits } + offset.codeOffset
    var current = 0
    for (candidate in indices) {
        if (current == address) return candidate
        current += this[candidate].codeUnits
    }
    return null
}

/** Resolve a constructor argument through plain copies before its first branch, never a guessed field. */
private fun Method.argumentField(argument: Int): FieldReference? {
    val code = implementation?.instructions?.toList() ?: return null
    val firstParameter = implementation!!.registerCount - parameterTypes.sumOf { if (it.toString() in listOf("J", "D")) 2 else 1 }
    val source = firstParameter + parameterTypes.take(argument).sumOf { if (it.toString() in listOf("J", "D")) 2 else 1 }
    val self = firstParameter - 1
    val origins = mutableMapOf(source to source, self to self)
    val fields = mutableListOf<FieldReference>()
    for (instruction in code) {
        if (instruction is OffsetInstruction || instruction.opcode in listOf(Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE, Opcode.RETURN_VOID, Opcode.THROW)) break
        val moved = instruction as? TwoRegisterInstruction
        if (instruction.opcode == Opcode.IPUT_OBJECT && moved != null && origins[moved.registerA] == source && origins[moved.registerB] == self) {
            ((instruction as ReferenceInstruction).reference as? FieldReference)?.takeIf { it.definingClass == definingClass && it.type == "Ljava/lang/String;" }?.let { fields += it }
        }
        if (instruction.opcode in OBJECT_MOVES && moved != null) {
            val origin = origins[moved.registerB]
            if (origin == null) origins.remove(moved.registerA) else origins[moved.registerA] = origin
        } else if (instruction.opcode.setsRegister()) {
            (instruction as? OneRegisterInstruction)?.let { origins.remove(it.registerA) }
        }
    }
    val field = fields.singleOrNull() ?: return null
    return field.takeIf { code.count { instruction -> instruction.opcode == Opcode.IPUT_OBJECT && instruction.screenReference() == field.toString() } == 1 }
}

private fun Method.directScreenConstructor(): Boolean {
    val p = parameterTypes.map(Any::toString)
    return p.size == 19 && p[0] == "Landroid/util/SparseArray;" && p[1].startsWith("L") && p[2].startsWith("L") &&
        p[3] == "Ljava/lang/Object;" && p.subList(4, 8) == List(4) { "Ljava/lang/String;" } &&
        p.subList(8, 13) == listOf("Ljava/util/HashMap;", "Ljava/util/List;", "Ljava/util/Map;", "Ljava/util/Map;", "Ljava/util/Map;") &&
        p.subList(13, 19) == listOf("I", "I", "J", "J", "Z", "Z")
}

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private fun Instruction.screenReference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
private fun Instruction.screenString(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Instruction.screenMethod(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.screenArguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun Method.strings(): Set<String> = implementation?.instructions?.mapNotNull { instruction ->
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
    else ((instruction as ReferenceInstruction).reference as StringReference).string
}?.toSet().orEmpty()

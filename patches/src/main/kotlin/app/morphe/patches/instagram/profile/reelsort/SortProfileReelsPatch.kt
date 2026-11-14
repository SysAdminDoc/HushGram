/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.reelsort

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
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
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val REEL_SORT_PATCH = "Sort reels on profiles"
internal const val REEL_SORT = "$EXTENSION_PACKAGE/profile/ReelSort;"
internal const val SORTABLE_HOOK = "$REEL_SORT->sortable(I)Z"

/** The purge markers of the profile Reels tab's provider: the tab coming up, and a tap on its title. */
internal const val TAB_SELECTED = "ClipsProfileTabProvider_onTabSelected"
internal const val TAB_CLICKED = "ClipsProfileTabProvider_onTabClicked"

private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val BOOLEAN_VALUE = "Ljava/lang/Boolean;->booleanValue()Z"
private const val OBJECT = "Ljava/lang/Object;"

/** One read of the sort gate: in [method], the unboxed answer sits in [register], and the hook goes in at [after]. */
internal class ReelSortRead(val method: Method, val call: Int, val after: Int, val register: Int)

/** The Reels tab's provider, the lazy field holding whether this profile's reels can be sorted, and each read of it. */
internal class ReelSortSite(val provider: String, val gate: FieldReference, val reads: List<ReelSortRead>)

private fun refuse(why: String): Nothing = throw PatchException("$REEL_SORT_PATCH: $why")

/**
 * Keeps the Latest and Most viewed sort on every profile's Reels tab (#679).
 *
 * Instagram shows the arrow next to Reels, and the menu it opens, only for a profile the server
 * marks as sortable, which it decides per profile and can take back. The tab's provider reads that
 * answer from one lazy field whenever the tab comes up or its title is tapped. The patch passes each
 * of those reads through the extension, which says yes while the switch is on. Picking a sort is
 * still Instagram's own request with its own sort_by_views flag, and what comes back is the server's
 * answer. In the default selection with its switch off.
 */
@Suppress("unused")
val sortProfileReelsPatch = bytecodePatch(
    name = "Sort reels on profiles",
    description = "Brings back the arrow next to Reels on a profile, with Latest and Most viewed, on profiles " +
        "where Instagram took it away. Starts off. Turn it on in HushGram settings > Profiles.",
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("profileReelSort")
        applyReelSort(findReelSort())
        enableStatus("profileReelSort")
    }
}

/**
 * Finds the provider by its two markers, and in both methods every `getValue()` of the class's one
 * field of the lazy's type, unboxed at once with `booleanValue`, or every call of the provider's
 * static method that does only that, on builds where Redex moved it there. Fails before anything changes when
 * the extension lacks its hook, a marker is missing or held twice, the reads come off more than one
 * field, a read of that field isn't unboxed where it's made (it would keep Instagram's answer), or
 * something jumps to where a hook would go.
 */
internal fun BytecodePatchContext.findReelSort(): ReelSortSite {
    val extension = classDefByOrNull(REEL_SORT) ?: refuse("the extension has no $REEL_SORT")
    if (extension.methods.none {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == SORTABLE_HOOK &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
        }
    ) refuse("the extension has no public static $SORTABLE_HOOK")

    val marked = mutableMapOf<String, MutableList<Method>>()
    for (type in typesMarked(TAB_SELECTED, TAB_CLICKED).sorted()) {
        val classDef = classDefByOrNull(type) ?: continue
        for (method in classDef.methods) {
            method.markers().filter { it == TAB_SELECTED || it == TAB_CLICKED }.distinct().forEach { marked.getOrPut(it) { mutableListOf() } += method }
        }
    }
    val selected = marked[TAB_SELECTED].orEmpty().singleOrNull()
        ?: refuse("expected one method marked $TAB_SELECTED, found ${marked[TAB_SELECTED].orEmpty().size}")
    val clicked = marked[TAB_CLICKED].orEmpty().singleOrNull()
        ?: refuse("expected one method marked $TAB_CLICKED, found ${marked[TAB_CLICKED].orEmpty().size}")
    if (selected.definingClass != clicked.definingClass) refuse("the two markers are in ${selected.definingClass} and ${clicked.definingClass}")
    val provider = classDefByOrNull(selected.definingClass) ?: refuse("this Instagram build has no ${selected.definingClass}")

    val found = listOf(selected, clicked).map { method -> method to gateReads(method, provider) }
    val gates = found.flatMap { (_, reads) -> reads.map { it.second } }.distinctBy { it.toString() }
    val gate = gates.singleOrNull() ?: refuse("expected the sort gate to be read off one field, found ${gates.size}")
    if (gate.definingClass != provider.type) refuse("the sort gate $gate isn't a field of ${provider.type}")
    val sameType = provider.fields.filter { it.type == gate.type && !AccessFlags.STATIC.isSet(it.accessFlags) }
    if (sameType.size != 1) refuse("expected ${provider.type} to hold one ${gate.type}, found ${sameType.size}")

    val reads = found.flatMap { (method, reads) ->
        if (reads.isEmpty()) refuse("${method.name} never reads the sort gate")
        val code = method.implementation!!.instructions.toList()
        val calls = code.indices.filter { at ->
            code[at].calledOn(gate.type) || helperCalled(code[at], provider)?.let(::helperGate)?.toString() == gate.toString()
        }
        val read = reads.map { it.first }
        if (calls != read.map { it.call }) refuse("${method.name} reads the sort gate somewhere it isn't unboxed at once")
        val targets = method.jumpTargets()
        read.forEach { if (it.after in targets) refuse("something in ${method.name} jumps to just past a read of the sort gate") }
        read
    }
    return ReelSortSite(provider.type, gate, reads)
}

/**
 * Each read of the sort gate in [method], with its field: unboxed where it's made, or, where Redex
 * moved that read into a static method of [provider] (some builds do), each call of that method
 * with its answer taken at once.
 */
private fun gateReads(method: Method, provider: ClassDef): List<Pair<ReelSortRead, FieldReference>> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val outlined = code.indices.mapNotNull { at ->
        val field = helperCalled(code[at], provider)?.let(::helperGate) ?: return@mapNotNull null
        val result = code.getOrNull(at + 1)
        if (result?.opcode != Opcode.MOVE_RESULT) return@mapNotNull null
        ReelSortRead(method, at, at + 2, (result as OneRegisterInstruction).registerA) to field
    }
    return (inlineReads(method, code) + outlined).sortedBy { it.first.call }
}

/** The static method of [provider] that [step] calls, when it takes one [provider] and answers a boolean. */
private fun helperCalled(step: Instruction, provider: ClassDef): Method? {
    if (step.opcode != Opcode.INVOKE_STATIC && step.opcode != Opcode.INVOKE_STATIC_RANGE) return null
    val call = step.methodCalled() ?: return null
    val takes = listOf(provider.type)
    if (call.definingClass != provider.type || call.returnType != "Z" || call.parameterTypes.map(CharSequence::toString) != takes) return null
    return provider.methods.singleOrNull {
        it.name == call.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == takes && AccessFlags.STATIC.isSet(it.accessFlags)
    }
}

/** The field [helper] reads when its whole body is one read of it off its parameter, unboxed and returned, else null. */
private fun helperGate(helper: Method): FieldReference? {
    val implementation = helper.implementation ?: return null
    val code = implementation.instructions.toList()
    val (read, field) = inlineReads(helper, code).singleOrNull() ?: return null
    if (read.call != 1 || code.size != read.after + 1) return null
    val load = code[0] as? TwoRegisterInstruction ?: return null
    if (load.registerB != implementation.registerCount - 1) return null
    val returned = code[read.after]
    if (returned.opcode != Opcode.RETURN || (returned as OneRegisterInstruction).registerA != read.register) return null
    return field
}

/** Each no-argument call answering an Object on a field of [method]'s own class, checked to a Boolean and unboxed at once, with that field. */
private fun inlineReads(method: Method, code: List<Instruction>): List<Pair<ReelSortRead, FieldReference>> {
    return code.indices.mapNotNull { at ->
        val call = code[at].methodCalled() ?: return@mapNotNull null
        if (call.parameterTypes.isNotEmpty() || call.returnType != OBJECT) return@mapNotNull null
        if (code[at].opcode != Opcode.INVOKE_INTERFACE && code[at].opcode != Opcode.INVOKE_VIRTUAL) return@mapNotNull null
        val boxed = code.getOrNull(at + 1)
        val cast = code.getOrNull(at + 2)
        val unboxed = code.getOrNull(at + 3)
        val result = code.getOrNull(at + 4)
        if (boxed?.opcode != Opcode.MOVE_RESULT_OBJECT || cast?.opcode != Opcode.CHECK_CAST) return@mapNotNull null
        if (((cast as ReferenceInstruction).reference as? TypeReference)?.type != BOOLEAN) return@mapNotNull null
        if (unboxed?.opcode != Opcode.INVOKE_VIRTUAL || unboxed.methodCalled()?.toString() != BOOLEAN_VALUE) return@mapNotNull null
        if (result?.opcode != Opcode.MOVE_RESULT) return@mapNotNull null
        val field = fieldIn(code, at, (code[at] as FiveRegisterInstruction).registerC) ?: return@mapNotNull null
        if (field.definingClass != method.definingClass || field.type != call.definingClass) return@mapNotNull null
        ReelSortRead(method, at, at + 5, (result as OneRegisterInstruction).registerA) to field
    }
}

/** The field [register] was last read from before [at], walking back through the code as written, or null when something else set it last. */
private fun fieldIn(code: List<Instruction>, at: Int, register: Int): FieldReference? {
    for (i in at - 1 downTo 0) {
        val step = code[i]
        if (step.opcode.setsWideRegister() && (step as? OneRegisterInstruction)?.registerA?.let { it == register || it + 1 == register } == true) return null
        if (!step.opcode.setsRegister() || step !is OneRegisterInstruction || step.registerA != register) continue
        if (step.opcode != Opcode.IGET_OBJECT) return null
        return (step as ReferenceInstruction).reference as? FieldReference
    }
    return null
}

/** Only called once [findReelSort] found everything: each read's answer goes through [SORTABLE_HOOK] right after its move-result. */
internal fun BytecodePatchContext.applyReelSort(site: ReelSortSite) {
    for ((method, reads) in site.reads.groupBy { it.method }) {
        val mutable = mutableClassDefBy(site.provider).methods.single {
            it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }
        for (read in reads.sortedByDescending { it.after }) {
            val v = read.register
            mutable.addInstructions(
                read.after,
                """
                    invoke-static/range { v$v .. v$v }, $SORTABLE_HOOK
                    move-result v$v
                """,
            )
        }
    }
}

private fun Instruction.methodCalled(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** Whether this is any call of a no-argument method of [type] answering an Object, range forms included. */
private fun Instruction.calledOn(type: String): Boolean {
    val call = methodCalled() ?: return false
    return call.definingClass == type && call.parameterTypes.isEmpty() && call.returnType == OBJECT
}

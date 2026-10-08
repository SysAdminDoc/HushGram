/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Clean up Reels"

/**
 * Takes four things off the Reels viewer, each behind its own switch: the Follow button beside a
 * reel's author, the pills that prompt you to make something or promote something, and friends'
 * activity with the comment preview, all on once the patch is in, and the Add comment bar under a
 * reel opened from a profile's reposts, which starts off. Friends' activity covers the floating
 * bubbles and the Liked by or Followed by line with its faces. See ReelParts.kt for each part.
 *
 * Every part is required: a build where one can't be found once, in the shape its kind has, stops
 * the patch with what's wrong, rather than shipping a switch that quietly does nothing.
 */
@Suppress("unused")
val cleanUpReelsPatch = bytecodePatch(
    name = "Clean up Reels",
    description = "Hides the Follow button on reels, the pills that push Edits, templates, Meta AI and " +
        "Ray-Ban Meta glasses, friends' activity with the comment preview, and the comment bar under a reposted reel. " +
        "Each part has its own switch.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        hideReelParts()
        enableStatus("reelDeclutter")
    }
}

/**
 * Finds the method behind each of [REEL_PARTS] by its marker and asks the part's hook first thing
 * in it. A render returns null when the hook says to hide, which Instagram's own renders answer
 * whenever they have nothing to draw. The check returns false. The floating bubbles and the social
 * context line are then taken out where Instagram decides on them, see [hideFloatingBubbles] and
 * [hideFriendsSocialContext].
 */
internal fun BytecodePatchContext.hideReelParts() {
    val wanted = CLEANUP_MARKERS.toSet()
    val found = mutableMapOf<String, MutableList<Method>>()
    val marked = typesMarked(*CLEANUP_MARKERS.toTypedArray())
    classDefForEach { classDef ->
        if (classDef.type !in marked) return@classDefForEach
        classDef.methods.forEach { method ->
            method.markers().filter { it in wanted }.distinct().forEach { found.getOrPut(it) { mutableListOf() } += method }
        }
    }
    fun holding(marker: String): Method {
        val holders = found[marker].orEmpty()
        return holders.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one method holding the $marker marker, found " +
                if (holders.isEmpty()) "none" else holders.joinToString { "${it.definingClass}->${it.name}" },
        )
    }

    val methods = REEL_PARTS.associateWith { holding(it.marker) }
    val bubbles = holding(FLOATING_BUBBLES)
    val socialContext = holding(SOCIAL_CONTEXT_CHECK)
    methods.forEach { (part, method) -> requireShape(part, method) }
    val renders = methods.filterKeys { !it.check }.values
    val shapes = renders.map { it.parameterTypes.single().toString() to it.returnType }.toSet()
    if (shapes.size != 1) throw PatchException("$PATCH: the renders don't share one shape: $shapes")
    val none = noBubbles(bubbles)
    val line = socialContextType(socialContext)
    val bar = commentBar(holding(COMMENT_BAR_SHOW), holding(COMMENT_BAR_HIDE), holding(COMMENT_BAR_CREATED), holding(COMMENT_BAR_UNVANISH))

    methods.forEach { (part, found) ->
        val method = mutable(found)
        method.requireLocals(PATCH, 1)
        val answer = if (part.check) "return v0" else "return-object v0"
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, ${part.hook}
                move-result v0
                if-eqz v0, :draw
                const/4 v0, 0x0
                $answer
            """,
            ExternalLabel("draw", method.getInstruction(0)),
        )
    }
    hideFloatingBubbles(bubbles, none)
    hideFriendsSocialContext(socialContext, line)
    hideCommentBar(bar)
}

/**
 * The comment bar's controller: [show], its showCommentBar, [hide], its hideCommentBar, and
 * [created], its onViewCreated, which keeps the bar it inflates at instruction [stored] - 1. The
 * viewer's source is in [source], and [free] is a local nothing reads at [stored]. [unVanish] is
 * where Instagram puts the bar back without asking the show.
 */
internal class CommentBar(
    val show: Method,
    val hide: Method,
    val created: Method,
    val stored: Int,
    val free: Int,
    val source: String,
    val unVanish: UnVanish,
)

/**
 * The comment bar's unVanish, [method]: the hook goes in at instruction [at], right after the
 * marker's trace call, reads the source off the controller in v[controller], borrows v[free], and
 * jumps to the call of Instagram's own hide at instruction [hidden] when it says to hide.
 */
internal class UnVanish(val method: Method, val at: Int, val controller: Int, val hidden: Int, val free: Int)

/**
 * Checks the comment bar's controller before anything changes. Its show and hide are instance
 * methods of one class taking nothing; the hide reads the bar from one View field, which its
 * onViewCreated, in the same class, stores once on `this`; and the class holds the viewer's source
 * in one field. A hook put right after that store has to find `this` intact and a local free, and
 * nothing may jump there past it.
 */
private fun BytecodePatchContext.commentBar(show: Method, hide: Method, created: Method, unVanish: Method): CommentBar {
    val type = show.definingClass
    val what = "$PATCH: the comment bar's controller $type"
    if (hide.definingClass != type || created.definingClass != type) {
        throw PatchException("$PATCH: the comment bar's show, hide and onViewCreated are in $type, ${hide.definingClass} and ${created.definingClass}, not one class")
    }
    for (method in listOf(show, hide)) {
        if (AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.isNotEmpty() || method.returnType != "V" ||
            method.implementation == null
        ) throw PatchException("$what: ${method.name} isn't an instance method taking nothing")
    }
    if (created.name != "onViewCreated" || AccessFlags.STATIC.isSet(created.accessFlags) || created.implementation == null) {
        throw PatchException("$what: ${created.name}, holding the $COMMENT_BAR_CREATED marker, isn't its onViewCreated")
    }
    val sources = classDefBy(type).fields.filter { it.type == CLIPS_VIEWER_SOURCE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val source = sources.singleOrNull() ?: throw PatchException("$what: expected one field holding the viewer's source, found ${sources.size}")
    if (unVanish.definingClass != type && !AccessFlags.PUBLIC.isSet(source.accessFlags)) {
        throw PatchException("$what: its unVanish, in ${unVanish.definingClass}, can't read the viewer's source, which isn't public")
    }
    val bars = hide.implementation!!.instructions.filter { it.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
        .filter { it.definingClass == type && it.type == VIEW }.map { "${it.definingClass}->${it.name}:${it.type}" }.distinct()
    val bar = bars.singleOrNull() ?: throw PatchException("$what: expected its hide to read one View field, found ${bars.size}")
    val code = created.implementation!!.instructions.toList()
    val stores = code.indices.filter { at ->
        code[at].opcode == Opcode.IPUT_OBJECT &&
            ((code[at] as ReferenceInstruction).reference as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" } == bar
    }
    val store = stores.singleOrNull() ?: throw PatchException("$what: expected onViewCreated to store the bar once, found ${stores.size}")
    if ((code[store] as TwoRegisterInstruction).registerB != created.localRegisterCount()) {
        throw PatchException("$what: onViewCreated stores the bar on something other than the controller")
    }
    val after = store + 1
    if (after >= code.size || after in created.jumpTargets()) {
        throw PatchException("$what: onViewCreated jumps to just after it stores the bar, so the hook would be skipped")
    }
    created.requireThisIntact(what, listOf(after))
    val free = created.freeLocalsAt(what, after, 1).single()
    return CommentBar(show, hide, created, after, free, "${source.definingClass}->${source.name}:${source.type}", unVanished(unVanish, hide))
}

/**
 * Checks the comment bar's unVanish before anything changes. It loads its marker once and hands
 * it straight to a trace call, and calls the controller's [hide] once, on its hidden branch. The
 * hook goes in right after the trace, where the method's own next instruction reads a field of the
 * controller off the register the hide is later called on, so the controller is known to be there.
 * Nothing may jump past the hook, and it needs a local that neither its own code nor the hide reads.
 */
private fun unVanished(method: Method, hide: Method): UnVanish {
    val type = hide.definingClass
    val what = "$PATCH: the comment bar's unVanish ${method.definingClass}->${method.name}"
    val code = method.implementation?.instructions?.toList() ?: throw PatchException("$what has no body")
    val loads = code.indices.filter { at ->
        (code[at].opcode == Opcode.CONST_STRING || code[at].opcode == Opcode.CONST_STRING_JUMBO) &&
            ((code[at] as ReferenceInstruction).reference as StringReference).string
                .let { PURGE_MARKER.find(it)?.groupValues?.get(1) } == COMMENT_BAR_UNVANISH
    }
    val loaded = loads.singleOrNull() ?: throw PatchException("$what: expected it to load its marker once, found ${loads.size}")
    val trace = code.getOrNull(loaded + 1) as? FiveRegisterInstruction
    if (trace == null || trace.opcode != Opcode.INVOKE_STATIC || trace.registerCount != 1 ||
        trace.registerC != (code[loaded] as OneRegisterInstruction).registerA
    ) throw PatchException("$what doesn't hand its marker to a trace call right after loading it")
    val at = loaded + 2
    val hides = code.indices.filter { index ->
        code[index].opcode == Opcode.INVOKE_VIRTUAL && ((code[index] as ReferenceInstruction).reference as MethodReference).let {
            it.definingClass == type && it.name == hide.name && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
    }
    val hidden = hides.singleOrNull() ?: throw PatchException("$what: expected it to call the hide once, found ${hides.size}")
    val controller = (code[hidden] as FiveRegisterInstruction).registerC
    val next = code.getOrNull(at)
    val field = (next as? ReferenceInstruction)?.reference as? FieldReference
    if (next == null || next.opcode !in INSTANCE_READS || field?.definingClass != type ||
        (next as TwoRegisterInstruction).registerB != controller
    ) throw PatchException("$what doesn't read the controller in v$controller right after its trace, so the hook can't tell it's there")
    if (at in method.jumpTargets()) throw PatchException("$what jumps to just after its trace, so the hook would be skipped")
    // The hook's jump reaches the hide with each register as it is at the hook. What the hide's
    // path reads before writing has to hold the same there as on Instagram's own way to the hide,
    // so nothing on a path from the hook to the hide may write it. An instruction is on one when the
    // hook reaches it before the hide and it reaches the hide: the shown branch's writes don't count.
    val flow = ControlFlow.of(method)
    val predecessors = List(code.size) { mutableListOf<Int>() }
    for (index in code.indices) (flow.normal[index] + flow.exceptional[index]).forEach { predecessors[it] += index }
    val fromHook = reachable(at) { index -> if (index == hidden) emptyList() else flow.normal[index] + flow.exceptional[index] }
    val between = (fromHook intersect reachable(hidden) { predecessors[it] }) - hidden
    val rewritten = RegisterLiveness.of(method).liveInto(hidden).filter { register -> between.any { code[it].writes(register) } }
    if (rewritten.isNotEmpty()) {
        throw PatchException(
            "$what writes ${rewritten.sorted().joinToString { "v$it" }} between its trace and the hide, and the hide's path reads it, " +
                "so the hook can't jump to the hide",
        )
    }
    val free = method.freeLocalsAt(what, at, 1, listOf(hidden)).single()
    return UnVanish(method, at, controller, hidden, free)
}

/** Every instruction index reached from [start] by following [next], [start] included. */
private fun reachable(start: Int, next: (Int) -> Collection<Int>): Set<Int> {
    val seen = mutableSetOf<Int>()
    val pending = ArrayDeque(listOf(start))
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        if (seen.add(index)) pending += next(index)
    }
    return seen
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

private val INSTANCE_READS = setOf(
    Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN, Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT,
)

/**
 * Has the comment bar hidden, with Instagram's own hide, when the hook says so for the viewer's
 * source: first thing whenever the controller would show it, right after onViewCreated keeps the
 * bar it inflated, which is drawn until the controller first decides, and in unVanish, which would
 * otherwise put the bar back after a vanish, by taking its own hidden branch.
 */
private fun BytecodePatchContext.hideCommentBar(bar: CommentBar) {
    val hide = "${bar.hide.definingClass}->${bar.hide.name}()V"
    val show = mutable(bar.show)
    show.requireLocals(PATCH, 1)
    show.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, ${bar.source}
            invoke-static { v0 }, $HIDE_COMMENT_BAR
            move-result v0
            if-eqz v0, :show
            invoke-virtual { p0 }, $hide
            return-void
        """,
        ExternalLabel("show", show.getInstruction(0)),
    )
    val created = mutable(bar.created)
    created.addInstructionsWithLabels(
        bar.stored,
        """
            iget-object v${bar.free}, p0, ${bar.source}
            invoke-static { v${bar.free} }, $HIDE_COMMENT_BAR
            move-result v${bar.free}
            if-eqz v${bar.free}, :keep
            invoke-virtual { p0 }, $hide
        """,
        ExternalLabel("keep", created.getInstruction(bar.stored)),
    )
    val back = bar.unVanish
    val unVanish = mutable(back.method)
    unVanish.addInstructionsWithLabels(
        back.at,
        """
            iget-object v${back.free}, v${back.controller}, ${bar.source}
            invoke-static { v${back.free} }, $HIDE_COMMENT_BAR
            move-result v${back.free}
            if-nez v${back.free}, :hide
        """,
        ExternalLabel("hide", unVanish.getInstruction(back.hidden)),
    )
}

private const val VIEW = "Landroid/view/View;"

private fun BytecodePatchContext.mutable(found: Method) = mutableClassDefBy(found.definingClass).methods.single {
    it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString) &&
        it.returnType == found.returnType
}

/**
 * The state the floating bubbles use case answers when there are no bubbles: the one field it reads
 * and answers straight away, a single instance of its own class.
 */
private fun noBubbles(method: Method): String {
    val code = method.implementation?.instructions?.toList().orEmpty()
    val nones = code.zipWithNext().mapNotNull { (read, answer) ->
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return@mapNotNull null
        val returned = read.opcode == Opcode.SGET_OBJECT && answer.opcode == Opcode.RETURN_OBJECT &&
            (read as OneRegisterInstruction).registerA == (answer as OneRegisterInstruction).registerA
        if (returned && field.type == field.definingClass) "${field.definingClass}->${field.name}:${field.type}" else null
    }.distinct()
    return nones.singleOrNull() ?: throw PatchException(
        "$PATCH: expected ${method.definingClass}->${method.name}, holding the $FLOATING_BUBBLES marker, to answer " +
            "one state of its own as it is, found ${nones.ifEmpty { listOf("none") }.joinToString()}",
    )
}

/** Has the floating bubbles use case answer its no-bubbles state when friends' activity is hidden. */
private fun BytecodePatchContext.hideFloatingBubbles(found: Method, none: String) {
    val method = mutable(found)
    method.requireLocals(PATCH, 1)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_SOCIAL_FOOTER
            move-result v0
            if-eqz v0, :build
            sget-object v0, $none
            return-object v0
        """,
        ExternalLabel("build", method.getInstruction(0)),
    )
}

/** Where the social context check reads its line's type: the parameter holding the line, and the field holding the type. */
internal data class SocialContextType(val parameter: Int, val field: String)

/**
 * The line the social context check is handed, and its type: the field the check reads off a
 * parameter and asks for its ordinal, on an enum naming [SOCIAL_CONTEXT_TYPES].
 */
private fun BytecodePatchContext.socialContextType(method: Method): SocialContextType {
    val what = "$PATCH: ${method.definingClass}->${method.name}, holding the $SOCIAL_CONTEXT_CHECK marker,"
    if (method.implementation == null || method.returnType != "Z") throw PatchException("$what is not a check answering a boolean")
    val code = method.implementation!!.instructions.toList()
    val parameters = method.parameterTypes.indices.associateBy { method.parameterRegisterNumber(it) }
    val reads = code.zipWithNext().withIndex().mapNotNull { (at, pair) ->
        val (read, asked) = pair
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return@mapNotNull null
        val call = (asked as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        if (read.opcode != Opcode.IGET_OBJECT || asked.opcode != Opcode.INVOKE_VIRTUAL) return@mapNotNull null
        if (call.definingClass != "Ljava/lang/Enum;" || call.name != "ordinal") return@mapNotNull null
        val registers = read as TwoRegisterInstruction
        if ((asked as FiveRegisterInstruction).registerC != registers.registerA) return@mapNotNull null
        val parameter = parameters[registers.registerB] ?: return@mapNotNull null
        Triple(at, parameter, field)
    }
    val (at, parameter, field) = reads.singleOrNull()
        ?: throw PatchException("$what reads its line's type off a parameter ${reads.size} time(s), not once")
    if (method.parameterTypes[parameter].toString() != field.definingClass) {
        throw PatchException("$what reads ${field.definingClass}->${field.name} off a parameter of another type")
    }
    method.requireParameterIntact(what, parameter, listOf(at))
    val named = classDefBy(field.type).methods.filter { it.name == "<clinit>" }.flatMap { initializer ->
        initializer.implementation?.instructions?.toList().orEmpty().mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
    }.toSet()
    val missing = SOCIAL_CONTEXT_TYPES.filter { it !in named }
    if (missing.isNotEmpty()) throw PatchException("$what reads a type, ${field.type}, that doesn't name $missing")
    return SocialContextType(parameter, "${field.definingClass}->${field.name}:${field.type}")
}

/**
 * Has the social context check answer yes, leave it out, for a line about friends' activity. On 449
 * the check reads the line on every way through, and the line comes from a factory that never
 * answers null, so reading it first adds no failure of its own.
 */
private fun BytecodePatchContext.hideFriendsSocialContext(found: Method, line: SocialContextType) {
    val method = mutable(found)
    method.requireLocals(PATCH, 1)
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${method.parameterRegister(line.parameter)}
            iget-object v0, v0, ${line.field}
            invoke-static { v0 }, $HIDE_SOCIAL_CONTEXT
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x1
            return v0
        """,
        ExternalLabel("check", method.getInstruction(0)),
    )
}

/** A render takes one component scope and answers an object; the check is static and answers a boolean. */
private fun requireShape(part: ReelPart, method: Method) {
    val static = AccessFlags.STATIC.isSet(method.accessFlags)
    val problem = when {
        method.implementation == null -> "has no body"
        part.check && (!static || method.returnType != "Z") -> "is not a static check answering a boolean"
        !part.check && (static || method.parameterTypes.size != 1 || !method.returnType.startsWith("L")) ->
            "is not a render taking one scope and answering a component"
        else -> null
    } ?: return
    throw PatchException("$PATCH: ${method.definingClass}->${method.name}, holding the ${part.marker} marker, $problem")
}

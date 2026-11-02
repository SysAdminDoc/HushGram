/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.autoscroll

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.classesTouching
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val AUTO_SCROLL_INSTALL = "$REEL_AUTO_SCROLL->install(I)Z"
internal const val AUTO_SCROLL_LEAD = "$REEL_AUTO_SCROLL->lead(I)Z"
internal const val AUTO_SCROLL_LEAD_TIME = "$REEL_AUTO_SCROLL->leadTime(I)I"

/** The marker, after Instagram's release prefix, of the check of whether this account is offered auto scroll in Reels at all. */
internal const val OPT_IN_AUTOSCROLL = "ClipsViewerExperimentUtil_isOptInAutoscrollEnabled"

/**
 * The markers, after Instagram's release prefix, of the Reels auto scroller's own three steps: what
 * it runs when a reel reaches its end, its check of whether to move on from that reel, and its move
 * to the next one. The last is matched whole, since "UpNextNavigator_scrollToNextReel" is another class's.
 */
internal const val SCROLLER_ON_LOOP = "ClipsAutoScroller_onVideoLoop"
internal const val SCROLLER_SHOULD_SCROLL = "ClipsAutoScroller_shouldScrollToNextReel"
internal const val SCROLLER_SCROLL = "ClipsAutoScroller_scrollToNextReel"

/**
 * The marker, after Instagram's release prefix, of the scroller's look at each progress report of
 * the playing reel, where it may move on a little before the reel ends.
 */
internal const val SCROLLER_ON_PROGRESS = "ClipsAutoScroller_onProgressUpdate"

/** The marker, in the scroller's constructor, of its read of how long before a reel's end it may move on. */
internal const val SCROLLER_LEAD_TIME = "ClipsViewerExperimentUtil_getAutoscrollScrollBufferMs"

/** The section the Reels viewer names in its trace while it builds its auto scroller. */
internal const val SCROLLER_TRACE = "ClipsViewerFragment.onCreate:clipsAutoScroller"

private const val SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * In the scroller's [constructor], the instruction at [switchAt] that keeps, from [switchRegister],
 * whether it moves on a little before a reel ends, and the one at [timeAt] that keeps, from
 * [timeRegister], how long before.
 */
internal class ScrollerLead(val constructor: MethodSite, val switchAt: Int, val switchRegister: Int, val timeAt: Int, val timeRegister: Int)

/**
 * In the Reels viewer's [builder], the index right [after] it reads whether this account is offered
 * auto scroll, and the [register] it read that into, which the branch at that index goes by. And
 * where the scroller it builds keeps when to move on, [lead].
 */
internal class ScrollerInstall(val builder: MethodSite, val after: Int, val register: Int, val lead: ScrollerLead)

private fun refuseInstall(detail: String): Nothing = throw PatchException("Keep Reels auto scroll on: $detail")

/**
 * Where the Reels viewer decides whether to build Instagram's auto scroller. Asked for in #175.
 *
 * On 450 one check, marked [OPT_IN_AUTOSCROLL], a static (session, boolean) answering a boolean,
 * decides two things: whether a reel's menu gets the Auto scroll row, and whether the viewer builds
 * the scroller and has it listen for reels ending. An account that isn't offered the row has no
 * scroller either, so answering the plugin's check alone would move nothing. The hook goes where
 * the viewer reads that check, and nowhere else: the check itself and its readers in the menus stay
 * Instagram's own, so no row appears that Instagram didn't offer.
 *
 * The scroller is the class of the one method marked [SCROLLER_SHOULD_SCROLL], a static taking the
 * scroller and answering a boolean. The same class holds the one method marked [SCROLLER_SCROLL], a
 * static (scroller, boolean) returning nothing, and the one marked [SCROLLER_ON_LOOP], an instance
 * method returning nothing that calls both. The check of whether to move on has to ask [isActive],
 * the plugin's check the extension already answers, itself or through a static (scroller) boolean
 * method of the scroller, so a scroller the hook had built moves only while the extension says so.
 * None of the three is changed: which reel may be moved on from, and the move, stay Instagram's.
 * When it moves on is found too, see [findScrollerLead].
 *
 * The builder is the one method anywhere that makes a new scroller. It's static, loads
 * [SCROLLER_TRACE], makes the scroller once and reads the check once. The read's result goes
 * straight into an if-eqz that skips past the place the scroller is made, and nothing jumps to the
 * result or to the branch, so code put between them always runs between them. No exception handler
 * covering either one reads the register the answer is in.
 */
internal fun BytecodePatchContext.findScrollerInstall(isActive: Method): ScrollerInstall {
    val wanted = listOf(OPT_IN_AUTOSCROLL, SCROLLER_ON_LOOP, SCROLLER_SHOULD_SCROLL, SCROLLER_SCROLL, SCROLLER_ON_PROGRESS, SCROLLER_LEAD_TIME)
    val marked = wanted.associateWith { mutableListOf<Method>() }
    val holders = typesMarked(*wanted.toTypedArray())
    classDefForEach { type ->
        if (type.type !in holders) return@classDefForEach
        type.methods.forEach { method -> method.markers().distinct().forEach { marked[it]?.add(method) } }
    }
    fun single(marker: String): Method {
        val found = marked.getValue(marker)
        return found.singleOrNull() ?: refuseInstall("expected one method marked $marker, found ${found.size}")
    }

    val offered = single(OPT_IN_AUTOSCROLL)
    if (!offered.installStatic() || offered.returnType != "Z" || offered.installParameters() != listOf(SESSION, "Z")) {
        refuseInstall("${offered.installText()}, marked $OPT_IN_AUTOSCROLL, isn't a static ($SESSION, boolean) method answering a boolean")
    }
    val should = single(SCROLLER_SHOULD_SCROLL)
    val scroller = should.definingClass
    if (!should.installStatic() || should.returnType != "Z" || should.installParameters() != listOf(scroller)) {
        refuseInstall("${should.installText()}, marked $SCROLLER_SHOULD_SCROLL, isn't a static method taking its own class and answering a boolean")
    }
    val move = single(SCROLLER_SCROLL)
    if (move.definingClass != scroller || !move.installStatic() || move.returnType != "V" || move.installParameters() != listOf(scroller, "Z")) {
        refuseInstall("${move.installText()}, marked $SCROLLER_SCROLL, isn't a static ($scroller, boolean) method returning nothing")
    }
    val loop = single(SCROLLER_ON_LOOP)
    if (loop.definingClass != scroller || loop.installStatic() || loop.returnType != "V") {
        refuseInstall("${loop.installText()}, marked $SCROLLER_ON_LOOP, isn't an instance method of $scroller returning nothing")
    }
    if (loop.installCode().none { it.installCalls(should) } || loop.installCode().none { it.installCalls(move) }) {
        refuseInstall("${loop.installText()} doesn't call both ${should.installText()} and ${move.installText()}")
    }
    val asks = should.installCode().any { it.installCalls(isActive) } || classDefBy(scroller).methods.any { helper ->
        helper.installStatic() && helper.returnType == "Z" && helper.installParameters() == listOf(scroller) &&
            should.installCode().any { it.installCalls(helper) } && helper.installCode().any { it.installCalls(isActive) }
    }
    if (!asks) refuseInstall("${should.installText()} doesn't ask ${isActive.installText()} whether auto scroll is on")
    val lead = findScrollerLead(scroller, loop, single(SCROLLER_ON_PROGRESS), single(SCROLLER_LEAD_TIME), should, move)

    val builders = classesCreating(scroller).flatMap { type -> type.methods.filter { method -> method.installCode().any { it.creates(scroller) } } }
    val builder = builders.singleOrNull() ?: refuseInstall("expected one method building $scroller, found ${builders.size}")
    val code = builder.installCode()
    if (!builder.installStatic() || code.none { it.installString() == SCROLLER_TRACE }) {
        refuseInstall("${builder.installText()}, which builds $scroller, isn't the Reels viewer's static method tracing \"$SCROLLER_TRACE\"")
    }
    val made = code.indices.filter { code[it].creates(scroller) }
    val create = made.singleOrNull() ?: refuseInstall("${builder.installText()} builds $scroller ${made.size} times, expected once")
    val reads = code.indices.filter { code[it].installCalls(offered) }
    val read = reads.singleOrNull() ?: refuseInstall("${builder.installText()} reads ${offered.installText()} ${reads.size} times, expected once")
    val result = code.getOrNull(read + 1) as? OneRegisterInstruction
    val branch = code.getOrNull(read + 2) as? OneRegisterInstruction
    if (result == null || branch == null || result.opcode != Opcode.MOVE_RESULT ||
        (branch.opcode != Opcode.IF_EQZ && branch.opcode != Opcode.IF_NEZ) || branch.registerA != result.registerA
    ) {
        refuseInstall("${builder.installText()} doesn't branch on ${offered.installText()}'s answer right after reading it at instruction $read")
    }
    val flow = ControlFlow.of(builder)
    val skipsTo = flow.normal[read + 2].first()
    val buildsWhenTrue = branch.opcode == Opcode.IF_NEZ
    // 453 jumps ahead to the build on a yes instead of skipping it on a no.
    if (buildsWhenTrue && skipsTo !in read + 3..create) {
        refuseInstall(
            "the branch at instruction ${read + 2} of ${builder.installText()} goes to $skipsTo, not on to the place $scroller is built, instruction $create",
        )
    }
    if (!buildsWhenTrue && create !in read + 3 until skipsTo) {
        refuseInstall(
            "the branch at instruction ${read + 2} of ${builder.installText()} goes to $skipsTo and doesn't skip the place $scroller is built, instruction $create",
        )
    }
    val targets = builder.jumpTargets()
    (read + 1..read + 2).firstOrNull { it in targets }?.let {
        refuseInstall("something in ${builder.installText()} jumps to instruction $it, between its read of ${offered.installText()} and the branch on it")
    }
    // The verifier takes the hook's call for one that can throw, so no handler around it may read the answer.
    val liveness = RegisterLiveness.of(builder)
    (flow.exceptional[read + 1] + flow.exceptional[read + 2]).distinct().firstOrNull { result.registerA in liveness.liveInto(it) }?.let {
        refuseInstall("the handler at instruction $it of ${builder.installText()} reads v${result.registerA}, which holds ${offered.installText()}'s answer")
    }
    // The builder is found again by its name and what it takes, which has to pick one method.
    val named = classDefBy(builder.definingClass).methods.count { it.name == builder.name && it.installParameters() == builder.installParameters() }
    if (named != 1) refuseInstall("${builder.definingClass} has $named methods named ${builder.name} taking what ${builder.installText()} takes")
    return ScrollerInstall(MethodSite(builder.definingClass, builder.name, builder.installParameters()), read + 2, result.registerA, lead)
}

/**
 * Where the scroller keeps when it moves on from a reel. Reported in #473.
 *
 * On 453 the scroller moves on either from [progress], marked [SCROLLER_ON_PROGRESS], once the
 * playing reel is within a set time of its end, or from [loop] once the reel has started over. A
 * server setting decides whether the first may happen, and with it off the reel plays its start
 * again while the next one slides in. The scroller's [constructor] reads that setting into a boolean
 * of its own and then, after loading [SCROLLER_LEAD_TIME], the time into an int of its own, which it
 * has already held between 10 and 400 ms. [progress] reads both and [loop] reads the boolean, to
 * skip a second move, and both call [should] and [move]. The hooks go right before the two writes,
 * at their labels, so a branch to a write passes through its hook too.
 *
 * The boolean is the instruction just before the marker loads, an iput-boolean on the constructor's
 * own `this`; the time is the one iput of an int on `this` after it. Nothing else anywhere writes
 * either field, and nothing after either write, exception handlers included, reads the register
 * written, so the answer put there goes to that field alone.
 */
private fun BytecodePatchContext.findScrollerLead(
    scroller: String, loop: Method, progress: Method, constructor: Method, should: Method, move: Method,
): ScrollerLead {
    if (progress.definingClass != scroller || progress.installStatic() || progress.returnType != "V") {
        refuseInstall("${progress.installText()}, marked $SCROLLER_ON_PROGRESS, isn't an instance method of $scroller returning nothing")
    }
    if (progress.installCode().none { it.installCalls(should) } || progress.installCode().none { it.installCalls(move) }) {
        refuseInstall("${progress.installText()} doesn't call both ${should.installText()} and ${move.installText()}")
    }
    if (constructor.definingClass != scroller || constructor.name != "<init>") {
        refuseInstall("${constructor.installText()}, marked $SCROLLER_LEAD_TIME, isn't a constructor of $scroller")
    }
    val code = constructor.installCode()
    val marks = code.indices.filter { code[it].installString()?.let(PURGE_MARKER::matchEntire)?.groupValues?.get(1) == SCROLLER_LEAD_TIME }
    val mark = marks.singleOrNull() ?: refuseInstall("${constructor.installText()} loads $SCROLLER_LEAD_TIME ${marks.size} times, expected once")
    val self = constructor.localRegisterCount()
    val switchAt = mark - 1
    val switch = code.getOrNull(switchAt)?.installKept(Opcode.IPUT_BOOLEAN, scroller, self)
        ?: refuseInstall("${constructor.installText()} doesn't keep a boolean of its own right before it loads $SCROLLER_LEAD_TIME")
    val times = (mark + 1 until code.size).filter { code[it].installKept(Opcode.IPUT, scroller, self)?.type == "I" }
    val timeAt = times.singleOrNull()
        ?: refuseInstall("${constructor.installText()} keeps ${times.size} ints of its own after it loads $SCROLLER_LEAD_TIME, expected one")
    val time = code[timeAt].installKept(Opcode.IPUT, scroller, self)!!

    if (progress.installCode().none { it.installReads(Opcode.IGET_BOOLEAN, switch) } || progress.installCode().none { it.installReads(Opcode.IGET, time) }) {
        refuseInstall("${progress.installText()} doesn't read both ${switch.installText()} and ${time.installText()}")
    }
    if (loop.installCode().none { it.installReads(Opcode.IGET_BOOLEAN, switch) }) {
        refuseInstall("${loop.installText()} doesn't read ${switch.installText()}")
    }
    for (field in listOf(switch, time)) {
        val writers = classesTouching(scroller, field.name).flatMap { type ->
            type.methods.flatMap { method -> method.installCode().filter { it.installWrites(field) }.map { method.installText() } }
        }
        if (writers != listOf(constructor.installText())) {
            refuseInstall("${field.installText()} is written by ${writers.distinct().joinToString()}, expected once, by ${constructor.installText()}")
        }
    }
    val switchRegister = (code[switchAt] as TwoRegisterInstruction).registerA
    val timeRegister = (code[timeAt] as TwoRegisterInstruction).registerA
    listOf(switchAt to switchRegister, timeAt to timeRegister).forEach { (at, register) ->
        constructor.readsAfter(at, register).firstOrNull()?.let {
            refuseInstall("instruction $it of ${constructor.installText()} reads v$register after instruction $at keeps it")
        }
    }
    val named = classDefBy(scroller).methods.count { it.name == constructor.name && it.installParameters() == constructor.installParameters() }
    if (named != 1) refuseInstall("$scroller has $named constructors taking what ${constructor.installText()} takes")
    return ScrollerLead(MethodSite(scroller, constructor.name, constructor.installParameters()), switchAt, switchRegister, timeAt, timeRegister)
}

private fun Method.installStatic() = AccessFlags.STATIC.isSet(accessFlags)

private fun Method.installParameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun Method.installText() = "$definingClass->$name(${installParameters().joinToString("")})$returnType"

private fun Method.installCode(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.installString() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun FieldReference.installText() = "$definingClass->$name:$type"

/** The field of [owner] this instruction writes with [opcode] on the object in [self], if it does. */
private fun Instruction.installKept(opcode: Opcode, owner: String, self: Int): FieldReference? {
    if (this.opcode != opcode || (this as TwoRegisterInstruction).registerB != self) return null
    return ((this as ReferenceInstruction).reference as FieldReference).takeIf { it.definingClass == owner }
}

private fun Instruction.installField(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.installSameField(field: FieldReference) =
    installField()?.let { it.definingClass == field.definingClass && it.name == field.name && it.type == field.type } == true

private fun Instruction.installReads(opcode: Opcode, field: FieldReference) = this.opcode == opcode && installSameField(field)

private fun Instruction.installWrites(field: FieldReference) = installSameField(field) && !opcode.setsRegister()

private fun Instruction.creates(type: String) =
    opcode == Opcode.NEW_INSTANCE && ((this as ReferenceInstruction).reference as TypeReference).type == type

/** Whether this instruction calls [method]. */
private fun Instruction.installCalls(method: Method): Boolean {
    val called = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return called.definingClass == method.definingClass && called.name == method.name &&
        called.returnType == method.returnType && called.parameterTypes.map(CharSequence::toString) == method.installParameters()
}

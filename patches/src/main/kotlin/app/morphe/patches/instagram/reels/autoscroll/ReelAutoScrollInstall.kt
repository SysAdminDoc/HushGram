/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.autoscroll

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.classesTouching
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
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
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val AUTO_SCROLL_INSTALL = "$REEL_AUTO_SCROLL->install(I)Z"
internal const val AUTO_SCROLL_LEAD = "$REEL_AUTO_SCROLL->lead(I)Z"
internal const val AUTO_SCROLL_LEAD_TIME = "$REEL_AUTO_SCROLL->leadTime(I)I"

/**
 * The MobileConfig flag deciding whether this account is offered auto scroll in Reels at all, read
 * as a wide constant by the one check of it (076m.A09 on 456's 386214574). No other method loads it.
 */
internal const val OPT_IN_AUTOSCROLL_FLAG = 0x8108f000003316L

/**
 * The MobileConfig flag the scroller's check of whether to move on reads as it decides (0AMZ.A07 on
 * 456's 386214574), loaded as a wide constant. No other method in the app loads it.
 */
internal const val SCROLLER_SHOULD_SCROLL_FLAG = 0x810d8c00a24cb9L

/** What the scroller's move to the next reel logs (0AMZ.A04 on 456's 386214574). No other method loads it. */
internal const val SCROLLER_SCROLL_EVENT = "instagram_clips_viewer_autoplay_scroll"

/**
 * The MobileConfig flag for how long before a reel's end the scroller may move on, read as a wide
 * constant by the scroller's constructor alone (0AMZ.<init> on 456's 386214574).
 */
internal const val SCROLLER_LEAD_FLAG = 0x8208f0000c13ebL

/** Every method loading [flag] as a wide constant. */
internal fun BytecodePatchContext.methodsLoadingWide(flag: Long): List<Method> =
    classesLoading(flag).flatMap { it.methods }.filter { method -> method.installCode().any { it.loadsWide(flag) } }

/** Every method loading [SCROLLER_SHOULD_SCROLL_FLAG] as a wide constant, the scroller's check of whether to move on. */
internal fun BytecodePatchContext.methodsLoadingScrollFlag(): List<Method> = methodsLoadingWide(SCROLLER_SHOULD_SCROLL_FLAG)

private fun Instruction.loadsWide(flag: Long) = opcode == Opcode.CONST_WIDE && (this as WideLiteralInstruction).wideLiteral == flag

private fun hex(flag: Long) = "0x${flag.toString(16)}"

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
 * On 450 one check, the one method loading [OPT_IN_AUTOSCROLL_FLAG], a static (session, boolean)
 * answering a boolean, decides two things: whether a reel's menu gets the Auto scroll row, and
 * whether the viewer builds the scroller and has it listen for reels ending. An account that isn't
 * offered the row has no scroller either, so answering the plugin's check alone would move nothing.
 * The hook goes where the viewer reads that check, and nowhere else: the check itself and its
 * readers in the menus stay Instagram's own, so no row appears that Instagram didn't offer.
 *
 * The scroller is the class of the one method loading [SCROLLER_SHOULD_SCROLL_FLAG], a static taking
 * the scroller and answering a boolean. The same class holds the one method loading
 * [SCROLLER_SCROLL_EVENT], a static (scroller, boolean) returning nothing, and two instance methods
 * returning nothing that call both: what it runs when a reel reaches its end, and its look at each
 * progress report. The check of whether to move on has to ask [isActive], the plugin's check the
 * extension already answers, itself or through a static (scroller) boolean method of the scroller,
 * so a scroller the hook had built moves only while the extension says so. None of them is changed:
 * which reel may be moved on from, and the move, stay Instagram's. When it moves on is found too,
 * see [findScrollerLead].
 *
 * The builder is the one method anywhere that makes a new scroller: the Reels viewer's on 456's
 * 386214574, a factory in the scroller's own class on the armeabi-v7a 386214562. It's static, loads
 * [SCROLLER_TRACE], makes the scroller once and reads the check once. The read's result goes
 * straight into an if-eqz that skips past the place the scroller is made, and nothing jumps to the
 * result or to the branch, so code put between them always runs between them. No exception handler
 * covering either one reads the register the answer is in.
 */
internal fun BytecodePatchContext.findScrollerInstall(isActive: Method): ScrollerInstall {
    fun single(flag: Long): Method {
        val found = methodsLoadingWide(flag)
        return found.singleOrNull() ?: refuseInstall("expected one method loading flag ${hex(flag)}, found ${found.size}")
    }

    val offered = single(OPT_IN_AUTOSCROLL_FLAG)
    if (!offered.installStatic() || offered.returnType != "Z" || offered.installParameters() != listOf(SESSION, "Z")) {
        refuseInstall("${offered.installText()}, which loads flag ${hex(OPT_IN_AUTOSCROLL_FLAG)}, isn't a static ($SESSION, boolean) method answering a boolean")
    }
    val should = single(SCROLLER_SHOULD_SCROLL_FLAG)
    val scroller = should.definingClass
    if (!should.installStatic() || should.returnType != "Z" || should.installParameters() != listOf(scroller)) {
        refuseInstall("${should.installText()}, which loads flag ${hex(SCROLLER_SHOULD_SCROLL_FLAG)}, isn't a static method taking its own class and answering a boolean")
    }
    val moves = classesLoadingString(SCROLLER_SCROLL_EVENT).flatMap { type -> type.methods.filter { loadsString(it, SCROLLER_SCROLL_EVENT) } }
    val move = moves.singleOrNull() ?: refuseInstall("expected one method loading \"$SCROLLER_SCROLL_EVENT\", found ${moves.size}")
    if (move.definingClass != scroller || !move.installStatic() || move.returnType != "V" || move.installParameters() != listOf(scroller, "Z")) {
        refuseInstall("${move.installText()}, which loads \"$SCROLLER_SCROLL_EVENT\", isn't a static ($scroller, boolean) method returning nothing")
    }
    val steps = classDefBy(scroller).methods.filter { step ->
        !step.installStatic() && step.returnType == "V" && step.installCode().any { it.installCalls(should) } && step.installCode().any { it.installCalls(move) }
    }
    if (steps.size != 2) {
        refuseInstall("expected two instance methods of $scroller returning nothing to call both ${should.installText()} and ${move.installText()}, found ${steps.size}")
    }
    val asks = should.installCode().any { it.installCalls(isActive) } || classDefBy(scroller).methods.any { helper ->
        helper.installStatic() && helper.returnType == "Z" && helper.installParameters() == listOf(scroller) &&
            should.installCode().any { it.installCalls(helper) } && helper.installCode().any { it.installCalls(isActive) }
    }
    if (!asks) refuseInstall("${should.installText()} doesn't ask ${isActive.installText()} whether auto scroll is on")
    val lead = findScrollerLead(scroller, steps, single(SCROLLER_LEAD_FLAG))

    val builders = classesCreating(scroller).flatMap { type -> type.methods.filter { method -> method.installCode().any { it.creates(scroller) } } }
    val builder = builders.singleOrNull() ?: refuseInstall("expected one method building $scroller, found ${builders.size}")
    val code = builder.installCode()
    if (!builder.installStatic() || !loadsString(builder, SCROLLER_TRACE)) {
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
 * On 453 the scroller moves on either from its look at each progress report, once the playing reel
 * is within a set time of its end, or from what it runs once the reel has started over. Both are in
 * [steps]. A server setting decides whether the first may happen, and with it off the reel plays its
 * start again while the next one slides in. The scroller's [constructor] reads that setting into a
 * boolean of its own and then, after loading [SCROLLER_LEAD_FLAG], the time into an int of its own,
 * which it has already held between 10 and 400 ms. The progress step reads both and the other step
 * reads the boolean, to skip a second move. The hooks go right before the two writes, at their
 * labels, so a branch to a write passes through its hook too.
 *
 * The boolean is the last iput-boolean on the constructor's own `this` before the flag loads (on 456
 * the read of the setting sits between the two); the time is the one iput of an int on `this` after
 * it. Nothing else anywhere writes either field, and nothing after either write, exception handlers
 * included, reads the register written, so the answer put there goes to that field alone.
 */
private fun BytecodePatchContext.findScrollerLead(scroller: String, steps: List<Method>, constructor: Method): ScrollerLead {
    if (constructor.definingClass != scroller || constructor.name != "<init>") {
        refuseInstall("${constructor.installText()}, which loads flag ${hex(SCROLLER_LEAD_FLAG)}, isn't a constructor of $scroller")
    }
    val code = constructor.installCode()
    val marks = code.indices.filter { code[it].loadsWide(SCROLLER_LEAD_FLAG) }
    val mark = marks.singleOrNull() ?: refuseInstall("${constructor.installText()} loads flag ${hex(SCROLLER_LEAD_FLAG)} ${marks.size} times, expected once")
    val self = constructor.localRegisterCount()
    val switchAt = (mark - 1 downTo 0).firstOrNull { code[it].installKept(Opcode.IPUT_BOOLEAN, scroller, self) != null }
        ?: refuseInstall("${constructor.installText()} doesn't keep a boolean of its own before it loads flag ${hex(SCROLLER_LEAD_FLAG)}")
    val switch = code[switchAt].installKept(Opcode.IPUT_BOOLEAN, scroller, self)!!
    val times = (mark + 1 until code.size).filter { code[it].installKept(Opcode.IPUT, scroller, self)?.type == "I" }
    val timeAt = times.singleOrNull()
        ?: refuseInstall("${constructor.installText()} keeps ${times.size} ints of its own after it loads flag ${hex(SCROLLER_LEAD_FLAG)}, expected one")
    val time = code[timeAt].installKept(Opcode.IPUT, scroller, self)!!

    val progresses = steps.filter { step -> step.installCode().any { it.installReads(Opcode.IGET, time) } }
    val progress = progresses.singleOrNull()
        ?: refuseInstall("expected one of ${steps.joinToString { it.installText() }} to read ${time.installText()}, found ${progresses.size}")
    val loop = steps.single { it !== progress }
    if (progress.installCode().none { it.installReads(Opcode.IGET_BOOLEAN, switch) }) {
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

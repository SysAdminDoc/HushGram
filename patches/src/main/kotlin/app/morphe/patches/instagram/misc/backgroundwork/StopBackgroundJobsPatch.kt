/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.backgroundwork

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.icon.pickAppIconPatch
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.privacy.code
import app.morphe.patches.instagram.privacy.describe
import app.morphe.patches.instagram.privacy.exactlyOne
import app.morphe.patches.instagram.privacy.methodsHolding
import app.morphe.patches.instagram.privacy.mutable
import app.morphe.patches.instagram.privacy.refuse
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Stop background jobs"
private const val WORK = "$EXTENSION_PACKAGE/misc/BackgroundWork;"
internal const val SKIP_WORK = "$WORK->skipWork(Ljava/lang/String;)Z"

internal const val SCHEDULE = "Landroid/app/job/JobScheduler;->schedule(Landroid/app/job/JobInfo;)I"
internal const val ENQUEUE =
    "Landroid/app/job/JobScheduler;->enqueue(Landroid/app/job/JobInfo;Landroid/app/job/JobWorkItem;)I"

/**
 * Each Android call the extension stands in for, and its stand-in, which takes the call's object
 * first and then its arguments, so the call keeps its registers. Instagram's component calls aren't
 * here: Pick the app icon already sends each to AppIcon, which asks BackgroundWork first.
 */
internal val BACKGROUND_STAND_INS = mapOf(
    SCHEDULE to "$WORK->schedule(Landroid/app/job/JobScheduler;Landroid/app/job/JobInfo;)I",
    ENQUEUE to "$WORK->enqueue(Landroid/app/job/JobScheduler;Landroid/app/job/JobInfo;Landroid/app/job/JobWorkItem;)I",
)

/** The call the patch can't do without: Instagram schedules the held services' jobs with it. */
internal val REQUIRED_CALLS = setOf(SCHEDULE)

/**
 * A method that enqueues periodic work: the unique name it hands the extension, the strings that
 * pick it out, and the worker class it loads. WorkManager builds a worker by its class name, so
 * those names stay, and so do the unique names Instagram enqueues the work under.
 */
internal class Enqueuer(val name: String, val strings: List<String>, val worker: String)

internal val ENQUEUERS = listOf(
    Enqueuer(
        "ig_emergency_push_periodic_fetch_work",
        listOf("ig_emergency_push_periodic_fetch_work"),
        "Lcom/instagram/emergencypush/EmergencyPushPeriodicFetchWorker;",
    ),
    Enqueuer(
        "FCMPushabilityCheckJob",
        listOf("FCMPushabilityCheckJob", "FBNSPushabilityCheckJob"),
        "Lcom/facebook/pushlite/pushability_healthcheck/PushabilityCheckerWorker;",
    ),
    Enqueuer(
        "ig_family_app_last_used_states_logging_background_work",
        listOf("ig_family_app_last_used_states_logging_background_work"),
        "Lcom/instagram/partneranalytics/igfamilyapplastusedstates/IGFamilyAppLastUsedStatesLogWorker;",
    ),
)

/**
 * Turns off Meta's push service while Instagram's notifications are off, the background feed
 * prefetch, and the periodic usage logs, each behind its own switch. See BackgroundWork in the
 * extension, which does the turning off as Instagram starts. In the default selection with every
 * switch off.
 */
@Suppress("unused")
val stopBackgroundJobsPatch = bytecodePatch(
    name = "Stop background jobs",
    description = "Turns off Meta's push service while Instagram's notifications are off in your phone's settings, " +
        "stops Instagram refreshing your feed in the background, and stops its periodic log of the other Meta " +
        "apps you use, so it wakes your phone less. Each has its own switch. Starts off. Turn them on in " +
        "HushGram settings > Battery.",
) {
    category("Fixes")
    // Pick the app icon sends every one of Instagram's component calls through AppIcon, which keeps a
    // component held off from being turned on again.
    dependsOn(settingsPatch, instagramExtensionPatch, pickAppIconPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("backgroundWork")
        // Everything is found before anything is changed.
        val enqueuers = ENQUEUERS.map { it to findEnqueuer(it) }
        val calls = findStandInCalls()
        // Calls move in place first, by the indices found; the checks then go in at each start.
        val routed = routeCalls(calls)
        for ((enqueuer, method) in enqueuers) askFirst(method, enqueuer.name)
        patchLog.info("$PATCH: routed ${routed.entries.joinToString { (call, count) -> "$count ${call.substringAfter("->").substringBefore("(")}" }}")
        enableStatus("backgroundWork")
    }
}

/** The one void method holding [enqueuer]'s strings and loading its worker's class. */
internal fun BytecodePatchContext.findEnqueuer(enqueuer: Enqueuer): Method {
    val method = methodsHolding(*enqueuer.strings.toTypedArray()).filter { method ->
        method.returnType == "V" && method.code().any { it.loadsClass(enqueuer.worker) }
    }.exactlyOne(PATCH, "method enqueueing ${enqueuer.name}")
    if (0 in method.jumpTargets()) refuse(PATCH, "something jumps to the start of ${method.describe()}")
    return method
}

private fun Instruction.loadsClass(type: String): Boolean =
    opcode == Opcode.CONST_CLASS && ((this as ReferenceInstruction).reference as TypeReference).type == type

/** The enqueue method returns before it enqueues anything while the extension says to skip [name]. */
private fun BytecodePatchContext.askFirst(method: Method, name: String) {
    val mutable = mutable(method)
    mutable.requireLocals(PATCH, 1)
    mutable.addInstructionsWithLabels(
        0,
        """
            const-string v0, "$name"
            invoke-static { v0 }, $SKIP_WORK
            move-result v0
            if-eqz v0, :enqueue
            return-void
        """,
        ExternalLabel("enqueue", mutable.getInstruction(0)),
    )
}

/** Where each call [BACKGROUND_STAND_INS] names is made outside the extension: method, then instruction indices. */
internal fun BytecodePatchContext.findStandInCalls(): Map<Method, List<Int>> {
    val found = LinkedHashMap<Method, List<Int>>()
    val seen = HashSet<String>()
    for (call in BACKGROUND_STAND_INS.keys) {
        val owner = call.substringBefore("->")
        val name = call.substringAfter("->").substringBefore("(")
        for (classDef in classesCalling(owner, name)) {
            if (classDef.type.startsWith(EXTENSION_ROOT) || !seen.add(classDef.type)) continue
            for (method in classDef.methods) {
                val indices = method.code().indices.filter { method.code()[it].callsStandIn() }
                if (indices.isNotEmpty()) found[method] = indices
            }
        }
    }
    val made = found.entries.flatMap { (method, indices) -> indices.map { method.code()[it].calledReference() } }.toSet()
    for (call in REQUIRED_CALLS) {
        if (call !in made) refuse(PATCH, "found no ${call.substringAfter("->").substringBefore("(")} call to route")
    }
    for ((method, indices) in found) {
        for (index in indices) {
            val call = method.code()[index]
            if (call !is FiveRegisterInstruction && call !is RegisterRangeInstruction) {
                refuse(PATCH, "${method.describe()} makes a ${call.calledReference()} call in a form it can't move")
            }
        }
    }
    return found
}

private fun Instruction.calledReference(): String = (this as ReferenceInstruction).reference.toString()

private fun Instruction.callsStandIn(): Boolean =
    (opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
        (this as ReferenceInstruction).reference.toString() in BACKGROUND_STAND_INS

/** Every call [findStandInCalls] found goes to its stand-in, on the same registers. Answers how many of each moved. */
private fun BytecodePatchContext.routeCalls(calls: Map<Method, List<Int>>): Map<String, Int> {
    val routed = LinkedHashMap<String, Int>()
    for ((method, indices) in calls) {
        val code = method.code()
        val mutable = mutable(method)
        for (index in indices) {
            val call = code[index]
            val reference = call.calledReference()
            val standIn = BACKGROUND_STAND_INS.getValue(reference)
            val smali = when (call) {
                is FiveRegisterInstruction ->
                    listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)
                        .take(call.registerCount).joinToString(prefix = "invoke-static { ", postfix = " }, $standIn") { "v$it" }
                is RegisterRangeInstruction ->
                    "invoke-static/range { v${call.startRegister} .. v${call.startRegister + call.registerCount - 1} }, $standIn"
                else -> refuse(PATCH, "${method.describe()} makes a $reference call in a form it can't move")
            }
            mutable.replaceInstruction(index, smali)
            routed[reference] = (routed[reference] ?: 0) + 1
        }
    }
    return routed
}

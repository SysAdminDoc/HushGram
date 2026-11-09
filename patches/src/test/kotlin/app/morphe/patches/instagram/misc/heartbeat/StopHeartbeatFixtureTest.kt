/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.heartbeat

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stop background wake-ups on every build of each declared Instagram version: one heartbeat method with
 * a register free for the check, and one upload scheduler with one AlarmManager.set to move, each in the
 * shape its patch contract names.
 */
class StopHeartbeatFixtureTest {
    private fun builds(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = Fixtures.files { file -> file.extension == "apks" && versions.any { file.name.contains("-$it-") } }
        return declared + Fixtures.otherBuilds()
    }

    private fun Method.holds(value: String) = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    } == true

    private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

    private fun heartbeatsIn(build: File) = FixtureDex.methodsWhere(build, { dex -> dex.stringSection.any { it == HEARTBEAT_TAG } }) { method ->
        method.returnType == "V" && method.parameterTypes.size == 1 && method.parameterTypes[0].startsWith("L") &&
            method.holds(HEARTBEAT_TAG) && method.holds(NO_ALARM_MANAGER)
    }

    private fun uploadsIn(build: File) = FixtureDex.methodsWhere(build, { dex -> dex.stringSection.any { it == UPLOAD_TAG } }) { method ->
        method.returnType == "V" && method.holds(UPLOAD_TAG) && method.holds(UPLOAD_ACTION)
    }

    private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    /** A body's opcodes with alignment nops dropped and a goto of any width read as one. */
    private fun List<Instruction>.kinds() = filter { it.opcode != Opcode.NOP }.map {
        if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode
    }

    @Test
    fun eachBuildHasOneHeartbeatAndOneUploadAlarmToChange() {
        for (build in builds()) {
            val heartbeats = heartbeatsIn(build)
            assertEquals("${build.parentFile.name}/${build.name}: heartbeat alarm methods", 1, heartbeats.size)
            val heartbeat = heartbeats.single()
            val inputs = heartbeat.parameterTypes.size + if (heartbeat.isStatic()) 0 else 1
            assertTrue("${build.name}: no register free in the heartbeat method", heartbeat.implementation!!.registerCount - inputs >= 1)

            val uploads = uploadsIn(build)
            assertEquals("${build.parentFile.name}/${build.name}: upload schedulers", 1, uploads.size)
            val upload = uploads.single()
            assertTrue("${build.name}: the upload scheduler isn't static", upload.isStatic())
            assertEquals(3, upload.parameterTypes.size)
            assertEquals("I", upload.parameterTypes[2].toString())
            val sets = upload.implementation!!.instructions.filter {
                (it as? ReferenceInstruction)?.reference?.toString() == ALARM_SET
            }
            assertEquals("${build.name}: AlarmManager.set calls in the upload scheduler", 1, sets.size)
            assertTrue("${build.name}: AlarmManager.set is a range call", sets.single() is FiveRegisterInstruction)
        }
    }

    /**
     * The patch's own fingerprints, run over every class that loads one of their exact anchor strings,
     * each pick the one method the shape check above finds.
     */
    @Test
    fun eachBuildsFingerprintsPickThoseMethods() {
        for (build in builds()) {
            val label = "${build.parentFile.name}/${build.name}"
            val classes = (FixtureDex.classesHolding(build, NO_ALARM_MANAGER) + FixtureDex.classesHolding(build, UPLOAD_ACTION))
                .distinctBy { it.type }
            val context = PatchContexts.of(classes)
            val heartbeat = context.uniqueMethod("Stop background wake-ups", "heartbeat alarm", HeartbeatAlarmFingerprint)
            assertEquals("$label: the heartbeat alarm", heartbeatsIn(build).single().signature(), heartbeat.signature())
            val upload = context.uniqueMethod("Stop background wake-ups", "analytics upload alarm", UploadAlarmFingerprint)
            assertEquals("$label: the upload scheduler", uploadsIn(build).single().signature(), upload.signature())
        }
    }

    /**
     * #341: every build has one prefetch scheduler, static, handed the job service class second, with a
     * register free for the check, and the job service's onStartJob has one too. The patch's own finders
     * pick them, and patched, each asks the extension first: the scheduler with the class it's handed,
     * returning when told, and onStartJob answering false, done, when told.
     */
    @Test
    fun eachBuildsPrefetchSchedulerAndJobStartAskTheExtensionFirst() {
        var checked = 0
        for (build in builds()) {
            val label = "${build.parentFile.name}/${build.name}"
            val classes = FixtureDex.classesLoading(build, BG_FETCH_JOB_FAILED) +
                FixtureDex.classes(build, setOf(BG_FETCH_SERVICE)).values
            val context = PatchContexts.of(classes.distinctBy { it.type })

            val scheduler = context.findBgFetchScheduler()
            assertTrue("$label: the scheduler isn't static", scheduler.isStatic())
            val schedulerInputs = scheduler.parameterTypes.size
            assertTrue("$label: no register free in the scheduler", scheduler.implementation!!.registerCount - schedulerInputs >= 1)
            val start = context.uniqueMethod("Stop background wake-ups", "background prefetch job", BgFetchJobStartFingerprint)
            assertTrue("$label: no register free in onStartJob", start.implementation!!.registerCount - 2 >= 1)
            val service = scheduler.implementation!!.registerCount - 2
            val stockScheduler = scheduler.implementation!!.instructions.toList()
            val stockStart = start.implementation!!.instructions.toList()

            stopBackgroundFetch(scheduler, start)

            val patched = scheduler.implementation!!.instructions.toList()
            val ask = patched[0] as RegisterRangeInstruction
            assertEquals("$label: the scheduler asks first", "$EXTENSION/misc/Heartbeat;->stopBackgroundFetch(Ljava/lang/Class;)Z",
                (patched[0] as ReferenceInstruction).reference.toString())
            assertEquals("$label: handing it the job service class", service, ask.startRegister)
            assertEquals(1, ask.registerCount)
            assertEquals("Ljava/lang/Class;", scheduler.parameterTypes[1].toString())
            assertEquals(Opcode.MOVE_RESULT, patched[1].opcode)
            assertEquals(Opcode.IF_EQZ, patched[2].opcode)
            assertEquals(Opcode.RETURN_VOID, patched[3].opcode)
            assertEquals("$label: the scheduler's own code follows", stockScheduler.kinds(), patched.drop(4).kinds())

            val started = start.implementation!!.instructions.toList()
            assertEquals("$label: the job asks first", "$EXTENSION/misc/Heartbeat;->skipBackgroundFetch()Z",
                (started[0] as ReferenceInstruction).reference.toString())
            assertEquals(Opcode.IF_EQZ, started[2].opcode)
            assertEquals(Opcode.CONST_4, started[3].opcode)
            assertEquals(0, (started[3] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.RETURN, started[4].opcode)
            assertEquals("$label: onStartJob's own code follows", stockStart.kinds(), started.drop(5).kinds())
            checked++
        }
        assertEquals("every build is read", Fixtures.BUILDS, checked)
    }

    private companion object {
        const val EXTENSION = "Lapp/hushgram/extension/instagram"
        const val HEARTBEAT_TAG = "WarmHeartbeat"
        const val NO_ALARM_MANAGER = "AlarmManager not available, cannot schedule heartbeat"
        const val UPLOAD_TAG = "AnalyticsUploadAlarm"
        const val UPLOAD_ACTION = "action_batch_upload"
        const val ALARM_SET = "Landroid/app/AlarmManager;->set(IJLandroid/app/PendingIntent;)V"
    }
}

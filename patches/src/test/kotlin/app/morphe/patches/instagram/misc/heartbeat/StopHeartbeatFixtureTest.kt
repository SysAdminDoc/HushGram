/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.heartbeat

import app.morphe.Fixtures
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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

    @Test
    fun eachBuildHasOneHeartbeatAndOneUploadAlarmToChange() {
        for (build in builds()) {
            val heartbeats = FixtureDex.methodsWhere(build, { dex -> dex.stringSection.any { it == HEARTBEAT_TAG } }) { method ->
                method.returnType == "V" && method.parameterTypes.size == 1 && method.parameterTypes[0].startsWith("L") &&
                    method.holds(HEARTBEAT_TAG) && method.holds(NO_ALARM_MANAGER)
            }
            assertEquals("${build.parentFile.name}/${build.name}: heartbeat alarm methods", 1, heartbeats.size)
            val heartbeat = heartbeats.single()
            val inputs = heartbeat.parameterTypes.size + if (heartbeat.isStatic()) 0 else 1
            assertTrue("${build.name}: no register free in the heartbeat method", heartbeat.implementation!!.registerCount - inputs >= 1)

            val uploads = FixtureDex.methodsWhere(build, { dex -> dex.stringSection.any { it == UPLOAD_TAG } }) { method ->
                method.returnType == "V" && method.holds(UPLOAD_TAG) && method.holds(UPLOAD_ACTION)
            }
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

    private companion object {
        const val HEARTBEAT_TAG = "WarmHeartbeat"
        const val NO_ALARM_MANAGER = "AlarmManager not available, cannot schedule heartbeat"
        const val UPLOAD_TAG = "AnalyticsUploadAlarm"
        const val UPLOAD_ACTION = "action_batch_upload"
        const val ALARM_SET = "Landroid/app/AlarmManager;->set(IJLandroid/app/PendingIntent;)V"
    }
}

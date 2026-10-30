/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.backgroundwork

import app.morphe.Fixtures
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stop background jobs on every build of each declared Instagram version: one enqueue method for
 * each periodic work it stops, with a register free and nothing jumping to its start, and
 * setComponentEnabledSetting and JobScheduler.schedule calls outside the extension, each in a form
 * that moves to a static stand-in on the same registers.
 */
class StopBackgroundJobsFixtureTest {
    private fun builds(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = Fixtures.files { file -> file.extension == "apks" && versions.any { file.name.contains("-$it-") } }
        return declared + Fixtures.otherBuilds()
    }

    private fun Method.holds(value: String) = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    } == true

    private fun Method.loads(type: String) = implementation?.instructions?.any {
        it.opcode == Opcode.CONST_CLASS && ((it as ReferenceInstruction).reference as TypeReference).type == type
    } == true

    @Test
    fun eachBuildHasOneEnqueuerPerWorkAndCallsToRoute() {
        for (build in builds()) {
            for (enqueuer in ENQUEUERS) {
                val found = FixtureDex.methodsWhere(build, { dex -> dex.stringSection.any { it == enqueuer.strings.first() } }) {
                    it.returnType == "V" && enqueuer.strings.all { string -> it.holds(string) } && it.loads(enqueuer.worker)
                }
                assertEquals("${build.parentFile.name}/${build.name}: methods enqueueing ${enqueuer.name}", 1, found.size)
                val method = found.single()
                val inputs = method.parameterTypes.size + if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
                assertTrue("${build.name}: no register free in ${method.name}", method.implementation!!.registerCount - inputs >= 1)
            }

            val calls = FixtureDex.methodsWhere(build, { true }) { method ->
                !method.definingClass.startsWith("Lapp/hushgram/extension/") &&
                    method.implementation?.instructions?.any { it.reference() in BACKGROUND_STAND_INS } == true
            }.flatMap { method -> method.implementation!!.instructions.filter { it.reference() in BACKGROUND_STAND_INS } }
            for (required in REQUIRED_CALLS) {
                assertTrue("${build.name}: no $required call to route", calls.any { it.reference() == required })
            }
            assertFalse("${build.name}: a call in a form that can't move",
                calls.any { it !is FiveRegisterInstruction && it !is RegisterRangeInstruction })
            assertTrue("${build.name}: a call that isn't invoke-virtual",
                calls.all { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE })
        }
    }

    private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.reference(): String? =
        (this as? ReferenceInstruction)?.reference?.toString()
}

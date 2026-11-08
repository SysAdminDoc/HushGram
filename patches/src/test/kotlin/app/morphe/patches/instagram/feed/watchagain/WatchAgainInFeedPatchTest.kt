/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.watchagain

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watch again in the feed (#568): the hook is in the extension, and in every build of 454 the end
 * card controller's constructor hands the in-place replay flag to the extension before storing it.
 */
class WatchAgainInFeedPatchTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(WATCH_AGAIN_IN_FEED.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$WATCH_AGAIN_IN_FEED is not in the extension: $declared", WATCH_AGAIN_IN_FEED.substringAfter("->") in declared)
    }

    @Test
    fun eachBuildHandsTheReplayFlagToTheExtension() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            val context = PatchContexts.of(FixtureDex.classesLoading(bundle, CONTINUE_WATCHING))

            val site = context.findWatchAgain()
            context.replayWatchAgainInFeed(site)

            var stores = 0
            for (constructor in context.mutableClassDefBy(site.controller).methods.filter { it.name == "<init>" }) {
                val code = constructor.code()
                for (at in code.indices) {
                    if (code[at].opcode != Opcode.IPUT_BOOLEAN || code[at].reference().toString() != site.flag) continue
                    val value = (code[at] as TwoRegisterInstruction).registerA
                    val call = code[at - 2]
                    assertEquals("${bundle.name}: the hook before the store", WATCH_AGAIN_IN_FEED, call.reference().toString())
                    assertEquals("${bundle.name}: the hook takes the flag", value, (call as RegisterRangeInstruction).startRegister)
                    assertEquals("${bundle.name}: the answer is stored", Opcode.MOVE_RESULT, code[at - 1].opcode)
                    assertEquals("${bundle.name}: into the same register", value, (code[at - 1] as OneRegisterInstruction).registerA)
                    stores++
                }
            }
            assertTrue("${bundle.name}: no constructor stores ${site.flag}", stores > 0)
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
}

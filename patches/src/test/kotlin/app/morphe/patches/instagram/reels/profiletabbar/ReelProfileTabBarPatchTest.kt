/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.profiletabbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep the tab bar on a reel's profile (#385): in every build of 454 both routes that open a
 * reel author's profile ask the extension first, and skip the launch when it says yes.
 */
class ReelProfileTabBarPatchTest {
    private val markers = listOf(AUTHOR_PROFILE, VIEWER_PROFILE)

    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
    }

    private fun context(bundle: File, extra: List<ClassDef> = emptyList()): BytecodePatchContext =
        PatchContexts.of((markers + FOLLOW_PROMPT).flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type } + extra)

    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val hooks = ExtensionDex.classDef(REEL_PROFILE_HOOK.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$REEL_PROFILE_HOOK is not in the extension: $hooks", REEL_PROFILE_HOOK.substringAfter("->") in hooks)
        assertEquals(1, ExtensionDex.classDef(REEL_PROFILE_TARGETS).methods.count { it.name == "push" })
    }

    @Test
    fun eachBuildAsksTheExtensionBeforeLaunchingTheProfilesOwnScreen() {
        var checked = 0
        for (bundle in bundles()) {
            val context = context(bundle)
            for (marker in markers) {
                val route = context.findProfileRoute(marker)
                context.keepProfileOnTab(route)

                val owner = context.mutableClassDefBy(route.method.definingClass)
                val parameters = route.method.parameterTypes.map(CharSequence::toString)
                val method = owner.methods.first {
                    it.name == route.method.name && it.parameterTypes.map(CharSequence::toString) == parameters
                }
                val code = method.code()
                val call = code[route.hookAt]
                assertEquals("${bundle.name}: the hook goes before the launcher", REEL_PROFILE_HOOK, call.reference().toString())
                assertEquals(
                    "${bundle.name}: the hook is handed the activity, factory, session and config",
                    listOf(route.activity, route.factory, route.session, route.config),
                    (call as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE, it.registerF) },
                )
                assertEquals("${bundle.name}: the answer is read", route.answer, (code[route.hookAt + 1] as OneRegisterInstruction).registerA)
                assertEquals("${bundle.name}: an opened profile skips", Opcode.IF_NEZ, code[route.hookAt + 2].opcode)
                assertEquals("${bundle.name}: stock code follows", Opcode.NEW_INSTANCE, code[route.hookAt + 3].opcode)

                val target = indexAfterBranch(code, route.hookAt + 2)
                val resumed = route.resumeAt + 3
                assertEquals("${bundle.name}: the branch lands just past the launch", resumed, target)
                assertEquals("${bundle.name}: the launch is what's skipped", "V", (code[resumed - 1].reference() as MethodReference).returnType)
                assertFalse("${bundle.name}: v${route.answer} isn't read past the skip", readsBeforeWrite(code, resumed, route.answer))
                checked++
            }
        }
        assertEquals("both routes in every build of 454", Fixtures.BUILDS * markers.size, checked)
    }

    @Test
    fun eachBuildPushesWithInstagramsOwnNavigatorAndItIsPublic() {
        var checked = 0
        for (bundle in bundles()) {
            val first = context(bundle)
            val factory = first.findProfileRoute(AUTHOR_PROFILE).factoryType
            assertEquals(factory, first.findProfileRoute(VIEWER_PROFILE).factoryType)
            val push = first.findProfilePush(factory)
            assertEquals("${bundle.name}: the profile is built", FRAGMENT, push.build.returnType)

            val owners = FixtureDex.classes(bundle, (listOf(push.build, push.navigator) + push.steps).map { it.definingClass }.toSet()).values.toList()
            val full = context(bundle, owners + ExtensionDex.classDef(REEL_PROFILE_TARGETS))
            full.requirePushIsPublic(push)
            full.writeProfilePush(push)

            val stub = full.mutableClassDefBy(REEL_PROFILE_TARGETS).methods.single { it.name == "push" }
            val code = stub.code()
            assertEquals("${bundle.name}: the factory builds the screen", push.build.toString(), code.first { it.opcode == Opcode.INVOKE_VIRTUAL }.reference().toString())
            assertEquals("${bundle.name}: the navigator is made", push.navigator.toString(), code.first { it.opcode == Opcode.INVOKE_DIRECT }.reference().toString())
            val steps = code.filter { it.opcode == Opcode.INVOKE_VIRTUAL }.drop(1).map { it.reference().toString() }
            assertEquals("${bundle.name}: every step runs, in order", push.steps.map { it.toString() }, steps)
            assertEquals("${bundle.name}: the stub ends after the steps", Opcode.RETURN_VOID, code[8 + steps.size].opcode)
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    private fun indexAfterBranch(code: List<Instruction>, branch: Int): Int {
        var units = (code[branch] as OffsetInstruction).codeOffset
        var at = branch
        while (units > 0) {
            units -= code[at].codeUnits
            at++
        }
        return at
    }

    /** Whether [register] is read on the straight path from [start] before anything writes it. */
    private fun readsBeforeWrite(code: List<Instruction>, start: Int, register: Int): Boolean {
        for (at in start until code.size) {
            val instruction = code[at]
            if (register in instruction.reads()) return true
            if (instruction.opcode.setsRegister() && (instruction as OneRegisterInstruction).registerA == register) return false
        }
        return false
    }

    private fun Instruction.reads(): List<Int> = when {
        this is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        this is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        this is ThreeRegisterInstruction -> listOf(registerB, registerC) + if (opcode.setsRegister()) emptyList() else listOf(registerA)
        this is TwoRegisterInstruction -> listOf(registerB) + if (opcode.setsRegister()) emptyList() else listOf(registerA)
        this is OneRegisterInstruction && !opcode.setsRegister() -> listOf(registerA)
        else -> emptyList()
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
}

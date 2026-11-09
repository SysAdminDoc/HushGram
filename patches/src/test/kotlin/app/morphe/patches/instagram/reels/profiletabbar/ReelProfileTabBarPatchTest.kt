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
 * reel author's profile ask the extension first, and skip the launch when it says yes. Both tab
 * hosts let the extension answer whether a tab is at its root.
 */
class ReelProfileTabBarPatchTest {
    private val PROFILE_SCREEN = "Lcom/instagram/profile/fragment/UserDetailFragment;"
    private val markers = listOf(AUTHOR_PROFILE, VIEWER_PROFILE)

    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
    }

    private fun context(bundle: File, extra: List<ClassDef> = emptyList()): BytecodePatchContext =
        PatchContexts.of((markers + FOLLOW_PROMPT + FEED_TAG).flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type } + extra)

    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val hooks = ExtensionDex.classDef(REEL_PROFILE_HOOK.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$REEL_PROFILE_HOOK is not in the extension: $hooks", REEL_PROFILE_HOOK.substringAfter("->") in hooks)
        assertTrue("$TAB_ROOT_HOOK is not in the extension: $hooks", TAB_ROOT_HOOK.substringAfter("->") in hooks)
        assertEquals(1, ExtensionDex.classDef(REEL_PROFILE_TARGETS).methods.count { it.name == "push" })
        assertEquals(1, ExtensionDex.classDef(REEL_PROFILE_TARGETS).methods.count { it.name == "fragments" })
    }

    /** Both tab hosts let the extension answer the root check, right after they read it. */
    @Test
    fun eachBuildLetsBothTabHostsAskTheExtension() {
        var checked = 0
        for (bundle in bundles()) {
            val context = context(bundle)
            val check = context.findTabRootCheck()
            val hosts = context.findTabHosts(check.method)
            assertEquals("${bundle.name}: two hosts decide on the bar", 2, hosts.size)
            assertEquals("${bundle.name}: two different hosts", 2, hosts.map { it.method.definingClass }.distinct().size)
            for (host in hosts) {
                assertEquals("${bundle.name}: the answer is what the check returned", Opcode.MOVE_RESULT, host.method.code()[host.hookAt - 1].opcode)
                context.keepProfileAsTabRoot(host)

                val method = context.mutableClassDefBy(host.method.definingClass).methods.first {
                    it.name == host.method.name && it.parameterTypes.map(CharSequence::toString) == host.method.parameterTypes.map(CharSequence::toString)
                }
                val code = method.code()
                assertEquals("${bundle.name}: the hook follows the read", TAB_ROOT_HOOK, code[host.hookAt].reference().toString())
                assertEquals(
                    "${bundle.name}: the hook is handed the answer and the host",
                    listOf(host.answer, method.implementation!!.registerCount - 1),
                    (code[host.hookAt] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) },
                )
                assertEquals("${bundle.name}: the hook's answer replaces it", host.answer, (code[host.hookAt + 1] as OneRegisterInstruction).registerA)
                assertEquals("${bundle.name}: the read is the hook's", Opcode.MOVE_RESULT, code[host.hookAt + 1].opcode)
                checked++
            }
        }
        assertEquals("both hosts in every build of 454", Fixtures.BUILDS * 2, checked)
    }

    /** The stub reads what the root check reads, so the extension sees the same screens. */
    @Test
    fun eachBuildListsAHostsScreensThroughPublicCalls() {
        var checked = 0
        for (bundle in bundles()) {
            val first = context(bundle)
            val check = first.findTabRootCheck()
            val manager = first.findTabHosts(check.method).map { it.manager }.distinctBy { it.toString() }.single()
            val owners = FixtureDex.classes(bundle, setOfNotNull(manager.definingClass, check.fragments.definingClass, check.store?.definingClass)).values.toList()
            val full = context(bundle, owners + ExtensionDex.classDef(REEL_PROFILE_TARGETS))
            full.requirePublic(listOf(manager, check.fragments))
            check.store?.let { full.requirePublic(it) }
            full.writeFragmentList(manager, check)

            val code = full.mutableClassDefBy(REEL_PROFILE_TARGETS).methods.single { it.name == "fragments" }.code()
            assertEquals(
                "${bundle.name}: the host's manager, then its screens",
                listOf(manager.toString(), check.fragments.toString()),
                code.filter { it.opcode == Opcode.INVOKE_VIRTUAL }.map { it.reference().toString() },
            )
            check.store?.let { store -> assertEquals("${bundle.name}: the store is read", store.toString(), code.first { it.opcode == Opcode.IGET_OBJECT }.reference().toString()) }
            assertEquals("${bundle.name}: the list is returned", Opcode.RETURN_OBJECT, code[if (check.store == null) 5 else 6].opcode)
            assertEquals("${bundle.name}: Instagram keeps the profile screen's name", 1, FixtureDex.classes(bundle, setOf(PROFILE_SCREEN)).size)
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
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

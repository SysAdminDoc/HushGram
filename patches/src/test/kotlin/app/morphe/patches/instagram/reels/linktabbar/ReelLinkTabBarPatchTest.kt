/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.linktabbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.reels.profiletabbar.FEED_TAG
import app.morphe.patches.instagram.reels.profiletabbar.TAB_ROOT_HOOK
import app.morphe.patches.instagram.reels.profiletabbar.findTabHosts
import app.morphe.patches.instagram.reels.profiletabbar.findTabRootCheck
import app.morphe.patches.instagram.reels.profiletabbar.keepProfileAsTabRoot
import app.morphe.patches.instagram.reels.profiletabbar.requirePublic
import app.morphe.patches.instagram.reels.profiletabbar.writeFragmentList
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep the tab bar on reel links (#385): in every declared build the route that puts a reel link's
 * viewer in an activity asks the extension first and returns when the reel went onto the tab, and
 * the extension's stub calls that same route, which is public.
 */
class ReelLinkTabBarPatchTest {
    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
    }

    /**
     * The route's class, the viewer config, the tab hosts, and the classes loading [BACK_STACK]: on a
     * build that pools it (386214575), the pool.
     */
    private fun context(bundle: File, extra: List<ClassDef> = emptyList()): BytecodePatchContext =
        PatchContexts.of(FixtureDex.classesLoadingAny(bundle, setOf(LINK_HANDLER, BACK_STACK, TAB_BAR_HIDDEN, FEED_TAG)).distinctBy { it.type } + extra)

    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val hooks = ExtensionDex.classDef(REEL_LINK_HOOK.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$REEL_LINK_HOOK is not in the extension: $hooks", REEL_LINK_HOOK.substringAfter("->") in hooks)
        assertEquals(1, ExtensionDex.classDef(REEL_LINK_TARGETS).methods.count { it.name == "show" })
        assertEquals(1, ExtensionDex.classDef(REEL_LINK_TARGETS).methods.count { it.name == "tabBarHiddenFlag" && it.returnType == "Ljava/lang/String;" })
        assertTrue("$LINK_TAB_ROOT_HOOK is not in the extension: $hooks", LINK_TAB_ROOT_HOOK.substringAfter("->") in hooks)
        assertEquals(1, ExtensionDex.classDef(REEL_LINK_TARGETS).methods.count { it.name == "fragments" })
    }

    /**
     * Both tab hosts let the extension say the link's reel is the tab's root, right after they read
     * the root check, chained with the reel profile's answer when that patch is in too.
     */
    @Test
    fun eachBuildLetsBothTabHostsAskAboutTheLinksReel() {
        var checked = 0
        for (bundle in bundles()) {
            val context = context(bundle)
            val check = context.findTabRootCheck()
            val hosts = context.findTabHosts(check.method)
            assertEquals("${bundle.name}: two hosts decide on the bar", 2, hosts.size)
            for (host in hosts) {
                context.keepProfileAsTabRoot(host)
                context.keepProfileAsTabRoot(host, LINK_TAB_ROOT_HOOK)

                val method = context.mutableClassDefBy(host.method.definingClass).methods.first {
                    it.name == host.method.name && it.parameterTypes.map(CharSequence::toString) == host.method.parameterTypes.map(CharSequence::toString)
                }
                val code = method.code()
                assertEquals("${bundle.name}: the root check's answer is read", Opcode.MOVE_RESULT, code[host.hookAt - 1].opcode)
                assertEquals("${bundle.name}: the link's hook follows the read", LINK_TAB_ROOT_HOOK, code[host.hookAt].reference().toString())
                assertEquals("${bundle.name}: then the profile's", TAB_ROOT_HOOK, code[host.hookAt + 2].reference().toString())
                for (at in listOf(host.hookAt, host.hookAt + 2)) {
                    assertEquals(
                        "${bundle.name}: each hook is handed the answer and the host",
                        listOf(host.answer, method.implementation!!.registerCount - 1),
                        (code[at] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) },
                    )
                    assertEquals("${bundle.name}: each hook's answer replaces it", host.answer, (code[at + 1] as OneRegisterInstruction).registerA)
                    assertEquals("${bundle.name}: and is read", Opcode.MOVE_RESULT, code[at + 1].opcode)
                }
                checked++
            }
        }
        assertEquals("both hosts in every build of 456", Fixtures.BUILDS * 2, checked)
    }

    /** The link's stub lists a host's screens the way the root check does. */
    @Test
    fun eachBuildListsAHostsScreensForTheLinksReel() {
        var checked = 0
        for (bundle in bundles()) {
            val first = context(bundle)
            val check = first.findTabRootCheck()
            val manager = first.findTabHosts(check.method).map { it.manager }.distinctBy { it.toString() }.single()
            val owners = FixtureDex.classes(bundle, setOfNotNull(manager.definingClass, check.fragments.definingClass, check.store?.definingClass)).values.toList()
            val full = context(bundle, owners + ExtensionDex.classDef(REEL_LINK_TARGETS))
            full.requirePublic(listOf(manager, check.fragments))
            check.store?.let { full.requirePublic(it) }
            full.writeFragmentList(manager, check, REEL_LINK_TARGETS)

            val code = full.mutableClassDefBy(REEL_LINK_TARGETS).methods.single { it.name == "fragments" }.code()
            assertEquals(
                "${bundle.name}: the host's manager, then its screens",
                listOf(manager.toString(), check.fragments.toString()),
                code.filter { it.opcode == Opcode.INVOKE_VIRTUAL }.map { it.reference().toString() },
            )
            assertEquals("${bundle.name}: the list is returned", Opcode.RETURN_OBJECT, code.last().opcode)
            checked++
        }
        assertEquals("every build of 456 is read", Fixtures.BUILDS, checked)
    }

    @Test
    fun eachBuildAsksTheExtensionFirstInTheReelLinkRoute() {
        var checked = 0
        for (bundle in bundles()) {
            val stock = context(bundle).findLinkViewerRoute().code()
            val context = context(bundle)
            val route = context.findLinkViewerRoute()
            context.askBeforeTheLinkViewer(route)

            val method = context.mutableClassDefBy(route.definingClass).methods.first {
                it.name == route.name && it.parameterTypes.map(CharSequence::toString) == LINK_VIEWER_PARAMETERS
            }
            val code = method.code()
            val registers = method.implementation!!.registerCount
            val parameters = (registers - 3 until registers).toList()
            val call = code[0]
            assertEquals("${bundle.name}: the hook comes first", REEL_LINK_HOOK, call.reference().toString())
            assertEquals(
                "${bundle.name}: the hook is handed the arguments, the activity and the session",
                parameters,
                (call as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE) },
            )
            assertEquals("${bundle.name}: the answer is read", Opcode.MOVE_RESULT, code[1].opcode)
            assertTrue("${bundle.name}: the answer goes in a register of its own", (code[1] as OneRegisterInstruction).registerA !in parameters)
            assertEquals("${bundle.name}: a no runs stock code", Opcode.IF_EQZ, code[2].opcode)
            assertEquals("${bundle.name}: a yes returns", Opcode.RETURN_VOID, code[3].opcode)
            assertEquals("${bundle.name}: the branch lands on stock code", 4, indexAfterBranch(code, 2))
            assertEquals("${bundle.name}: stock code is kept as it was", stock.map { it.kind() }, code.drop(4).map { it.kind() })
            assertTrue("${bundle.name}: the route reads the back stack flag the extension sets", context.loadsString(method, BACK_STACK))
            checked++
        }
        assertEquals("every build of 456 is read", Fixtures.BUILDS, checked)
    }

    @Test
    fun eachBuildOpensTheReelThroughThePublicRoute() {
        var checked = 0
        for (bundle in bundles()) {
            val route = context(bundle).findLinkViewerRoute()
            val context = context(bundle, listOf(ExtensionDex.classDef(REEL_LINK_TARGETS)))
            context.requirePublic(listOf(route))
            context.writeLinkViewerCall(route)

            val code = context.mutableClassDefBy(REEL_LINK_TARGETS).methods.single { it.name == "show" }.code()
            assertEquals(
                "${bundle.name}: the stub casts each argument to what the route takes",
                LINK_VIEWER_PARAMETERS,
                code.take(3).map { ((it as ReferenceInstruction).reference as TypeReference).type },
            )
            assertEquals("${bundle.name}: the stub calls the route", Opcode.INVOKE_STATIC, code[3].opcode)
            assertEquals(
                "${bundle.name}: the route itself is called",
                "${route.definingClass}->${route.name}(${LINK_VIEWER_PARAMETERS.joinToString("")})V",
                code[3].reference().toString(),
            )
            assertEquals("${bundle.name}: the stub ends there", Opcode.RETURN_VOID, code[4].opcode)
            checked++
        }
        assertEquals("every build of 456 is read", Fixtures.BUILDS, checked)
    }

    /** The viewer config's flag a link sets to hide the tab bar is named for the extension, which clears it. */
    @Test
    fun eachBuildNamesTheViewerFlagThatHidesTheTabBar() {
        var checked = 0
        for (bundle in bundles()) {
            val context = context(bundle, listOf(ExtensionDex.classDef(REEL_LINK_TARGETS)))
            val flag = context.findTabBarFlag()
            val declared = context.classDefBy(VIEWER_CONFIG).fields.single { it.name == flag.name }
            assertEquals("${bundle.name}: a boolean of the config's own", "Z", declared.type)
            assertFalse("${bundle.name}: an instance flag", AccessFlags.STATIC.isSet(declared.accessFlags))
            // 386214574's toString loads shouldHideTabBar from A3V (dex probe, 2026-11-21).
            if (bundle.name.contains("-386314341")) assertEquals("A3V", flag.name)
            context.writeTabBarFlag(flag)

            val code = context.mutableClassDefBy(REEL_LINK_TARGETS).methods.single { it.name == "tabBarHiddenFlag" }.code()
            assertEquals("${bundle.name}: the stub hands over the name", Opcode.CONST_STRING, code[0].opcode)
            assertEquals("${bundle.name}: the flag's own name", flag.name, (code[0].reference() as StringReference).string)
            assertEquals("${bundle.name}: and returns it", Opcode.RETURN_OBJECT, code[1].opcode)
            checked++
        }
        assertEquals("every build of 456 is read", Fixtures.BUILDS, checked)
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

    /** A goto an insert widened reads as the goto it was. */
    private fun Instruction.kind(): Opcode = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    }

    private fun Method.code(): List<Instruction> =
        implementation?.instructions?.toList().orEmpty().filter { it.opcode != Opcode.NOP }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
}

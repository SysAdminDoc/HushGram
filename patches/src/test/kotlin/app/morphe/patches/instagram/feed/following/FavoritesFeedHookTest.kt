/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.following

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.flags.findFlagReads
import app.morphe.patches.instagram.reels.profiletabbar.FEED_TAG
import app.morphe.patches.instagram.reels.profiletabbar.REEL_PROFILE_TARGETS
import app.morphe.patches.instagram.reels.profiletabbar.TAB_ROOT_HOOK
import app.morphe.patches.instagram.reels.profiletabbar.findTabHosts
import app.morphe.patches.instagram.reels.profiletabbar.findTabRootCheck
import app.morphe.patches.instagram.reels.profiletabbar.keepProfileAsTabRoot
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Favorites from the tab bar (#643): in every build of 454 the feed switch built for an activity
 * hands itself to the extension as each constructor returns, the stubs reach its feed type and
 * method, and both tab hosts let the extension answer the root check, chained with the reel
 * profile's hook on the same read.
 */
class FavoritesFeedHookTest {
    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
    }

    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(FAVORITES_SWITCH_READY.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(FAVORITES_SWITCH_READY, FAVORITES_TAB_ROOT)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
        val stubs = ExtensionDex.classDef(FAVORITES_TARGETS).methods.map { it.name }
        for (stub in listOf("feedTypes", "show", "fragments")) assertEquals("$stub in $stubs", 1, stubs.count { it == stub })
    }

    /** A build without Home's feed switch as the patch knows it warns and keeps the other switches. */
    @Test
    fun aBuildWithoutTheSwitchOnlyWarns() {
        val none = PatchContexts.of(listOf(ExtensionDex.classDef(FAVORITES_TARGETS)))
        val failure = assertThrows(PatchException::class.java) { none.findFavoritesSwitch(emptyList()) }
        assertTrue(failure.message, failure.message!!.contains("expected one feed switch built for an activity, found 0"))
        assertFalse(none.favoritesOrWarn(emptyList()))
    }

    @Test
    fun eachBuildOpensFavoritesFromATabAndKeepsTheBar() {
        var checked = 0
        for (bundle in bundles()) {
            builds(bundle, "${bundle.parentFile?.name}/${bundle.name}")
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    private fun builds(bundle: File, label: String) {
        val holders = LinkedHashMap<String, ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { it.loadsTheFlag() || it.holds(SAVED_FEED_KEY) || it.holds(FOR_YOU_FEED) }) {
                    holders.putIfAbsent(classDef.type, ImmutableClassDef.of(classDef))
                }
            }
        }
        FixtureDex.classesLoading(bundle, FEED_TAG).forEach { holders.putIfAbsent(it.type, it) }

        val first = PatchContexts.of(holders.values.toList())
        val switch = first.findFavoritesSwitch(first.findFeedSwitches(first.findFlagReads("test", FEED_PICKER_FLAGS)))
        val check = first.findTabRootCheck()
        val hosts = first.findTabHosts(check.method)
        val manager = hosts.map { it.manager }.distinctBy { it.toString() }.single()
        val owners = FixtureDex.classes(
            bundle,
            setOfNotNull(manager.definingClass, check.fragments.definingClass, check.store?.definingClass, switch.feedType),
        )
        owners.forEach { (type, classDef) -> holders.putIfAbsent(type, classDef) }
        for (type in listOf(FAVORITES_SWITCH_READY.substringBefore("->"), FAVORITES_TARGETS, REEL_PROFILE_TARGETS)) {
            holders[type] = ExtensionDex.classDef(type)
        }

        val context = PatchContexts.of(holders.values.toList())
        val switches = context.findFeedSwitches(context.findFlagReads("test", FEED_PICKER_FLAGS))
        // The reel profile's patch goes first here, so Favorites' hook has to find the read past it.
        context.findTabHosts(context.findTabRootCheck().method).forEach { context.keepProfileAsTabRoot(it) }
        assertTrue("$label: Favorites is in", context.favoritesOrWarn(switches))

        val built = context.mutableClassDefBy(switch.type).methods.filter {
            it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == "Landroidx/fragment/app/FragmentActivity;"
        }
        assertTrue("$label: the switch is built for an activity", built.isNotEmpty())
        for (constructor in built) {
            val code = constructor.code()
            val self = constructor.implementation!!.registerCount - constructor.parameterTypes.sumOf { if (it.toString() in WIDE) 2 else 1 } - 1
            val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
            assertTrue("$label: the constructor returns", returns.isNotEmpty())
            for (at in returns) {
                val ready = code[at - 1]
                assertEquals("$label: the switch is handed over before it returns", FAVORITES_SWITCH_READY, ready.referenceText())
                val range = ready as RegisterRangeInstruction
                assertEquals("$label: it hands over itself", listOf(self, 1), listOf(range.startRegister, range.registerCount))
            }
            assertEquals("$label: one hand-over per return", returns.size, code.count { it.referenceText() == FAVORITES_SWITCH_READY })
        }

        for (host in hosts) {
            val method = context.mutableClassDefBy(host.method.definingClass).methods.single {
                it.name == host.method.name && it.parameterTypes.map(CharSequence::toString) == host.method.parameterTypes.map(CharSequence::toString)
            }
            val code = method.code()
            val favorites = code.indices.single { code[it].referenceText() == FAVORITES_TAB_ROOT }
            assertEquals("$label: Favorites' hook follows the root check's read", Opcode.MOVE_RESULT, code[favorites - 1].opcode)
            assertEquals("$label: the root check is what was read", check.method.name, code[favorites - 2].calledName())
            assertEquals(
                "$label: the hook is handed the answer and the host",
                listOf(host.answer, host.host),
                (code[favorites] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) },
            )
            assertEquals("$label: its answer replaces Instagram's", listOf(Opcode.MOVE_RESULT, host.answer),
                listOf(code[favorites + 1].opcode, (code[favorites + 1] as OneRegisterInstruction).registerA))
            assertEquals("$label: the reel profile's hook still reads it next", TAB_ROOT_HOOK, code[favorites + 2].referenceText())
            assertEquals("$label: one reel profile hook", 1, code.count { it.referenceText() == TAB_ROOT_HOOK })
        }

        val stubs = context.mutableClassDefBy(FAVORITES_TARGETS).methods
        val types = stubs.single { it.name == "feedTypes" }.code()
        assertEquals("$label: the feed types", listOf(Opcode.CONST_CLASS, Opcode.RETURN_OBJECT), types.take(2).map { it.opcode })
        assertEquals("$label: the feed type is the switch's", switch.feedType, types[0].referenceText())
        val show = stubs.single { it.name == "show" }.code()
        assertEquals(
            "$label: the switch is handed the feed",
            "${switch.type}->${switch.name}(${switch.feedType})V",
            show.single { it.opcode == Opcode.INVOKE_VIRTUAL }.referenceText(),
        )
        val fragments = stubs.single { it.name == "fragments" }.code()
        assertEquals(
            "$label: the host's manager, then its screens",
            listOf(manager.toString(), check.fragments.toString()),
            fragments.filter { it.opcode == Opcode.INVOKE_VIRTUAL }.map { it.referenceText() },
        )
    }

    private fun Method.loadsTheFlag(): Boolean = implementation?.instructions?.any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral in FEED_PICKER_FLAGS
    } == true

    private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.calledName(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.name

    private companion object {
        val WIDE = setOf("J", "D")
    }
}

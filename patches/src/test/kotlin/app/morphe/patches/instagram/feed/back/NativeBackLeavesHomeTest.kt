/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.back

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hook on each of 450's seven builds: Home's feed fragment is the one class tracing its scroll
 * to the top, its Back handler scrolls once with the reason built as BACK_BUTTON_PRESS, and the
 * hook goes in front of that scroll without moving anything else.
 */
class NativeBackLeavesHomeTest {
    @Test fun everyBuildHandsHomesBackScrollToTheHook() {
        val checked = mutableListOf<String>()
        for (bundle in bundles()) {
            val name = bundle.absolutePath
            val holders = FixtureDex.classesHolding(bundle, SCROLL_TITLE)
            assertEquals("$name: one class traces the scroll", 1, holders.size)
            val reasonTypes = holders.single().methods.flatMap { it.parameterTypes }.map(Any::toString).toSet()
            val classes = FixtureDex.withStringPools(bundle, holders + FixtureDex.classes(bundle, reasonTypes).values) +
                ExtensionDex.classDef(BACK_LEAVES_HOME)
            val context = PatchContexts.of(classes)
            val site = context.findBackOnHome()

            val handler = site.handler
            assertEquals(name, "onBackPressed", handler.name)
            assertEquals(name, holders.single().type, handler.definingClass)
            val code = handler.code()
            val call = code[site.scroll]
            assertEquals("$name: the call is a virtual call", Opcode.INVOKE_VIRTUAL, call.opcode)
            val scroll = (call as ReferenceInstruction).reference as MethodReference
            assertEquals("$name: the scroll takes one reason and answers nothing", listOf(1, "V"),
                listOf(scroll.parameterTypes.size, scroll.returnType))
            val field = (code[site.scroll - 1] as ReferenceInstruction).reference as FieldReference
            assertEquals("$name: the reason is a constant of the scroll's own enum", scroll.parameterTypes.single().toString(), field.definingClass)
            assertEquals("$name: the constant is built as $BACK_PRESS", BACK_PRESS,
                enumConstantName(classes.single { it.type == field.definingClass }, field))
            assertTrue("$name: a local the hook can name, v${site.free}", site.free in 0..15)

            val original = NeutralNativePath(context.mutableClassDefBy(handler.definingClass).methods.single { it.name == "onBackPressed" })
            val others = classes.filter { it.type != handler.definingClass }
                .associate { it.type to it.methods.map { method -> method.name + method.parameterTypes to method.code().map { i -> i.opcode } } }
            context.backLeavesHome(site)

            val hooked = context.mutableClassDefBy(handler.definingClass).methods.single { it.name == "onBackPressed" }
            val after = hooked.code()
            assertEquals("$name: one hook", 1, after.count { (it as? ReferenceInstruction)?.reference.toString() == LEAVE })
            assertEquals("$name: the hook is where the scroll was", LEAVE, (after[site.scroll] as ReferenceInstruction).reference.toString())
            assertEquals("$name: the answer is read into the free local", site.free, (after[site.scroll + 1] as OneRegisterInstruction).registerA)
            assertEquals("$name: false is returned from it", Opcode.RETURN, after[site.scroll + 4].opcode)
            assertEquals("$name: the scroll follows", scroll.toString(), (after[site.scroll + 5] as ReferenceInstruction).reference.toString())
            original.assertPreserved(name, hooked, (site.scroll until site.scroll + 5).toSet())
            for ((type, methods) in others) {
                val now = context.mutableClassDefBy(type).methods.map { method -> method.name + method.parameterTypes to method.code().map { i -> i.opcode } }
                assertEquals("$name: $type is left alone", methods, now)
            }
            checked += name
        }
        for (code in BUILDS) assertTrue("no fixture of 450 build $code among $checked", checked.any { it.contains("-$code") })
    }

    @Test fun dexBackedInstructionReReadsFindTheSameAnchor() {
        for (bundle in bundles().take(1)) {
            val holders = FixtureDex.classesHolding(bundle, SCROLL_TITLE)
            val reasonTypes = holders.single().methods.flatMap { it.parameterTypes }.map(Any::toString).toSet()
            val copied = PatchContexts.of(
                FixtureDex.withStringPools(bundle, holders + FixtureDex.classes(bundle, reasonTypes).values) + ExtensionDex.classDef(BACK_LEAVES_HOME),
            ).findBackOnHome()
            val read = FixtureDex.classesAsRead(bundle, holders.map { it.type }.toSet() + reasonTypes)
            val context = PatchContexts.of(read.values + ExtensionDex.classDef(BACK_LEAVES_HOME))
            val site = context.findBackOnHome()
            assertEquals(copied.scroll, site.scroll)
            assertEquals(copied.free, site.free)
            context.backLeavesHome(site)
            val hooked = context.mutableClassDefBy(site.handler.definingClass).methods.single { it.name == "onBackPressed" }
            assertEquals(LEAVE, (hooked.code()[site.scroll] as ReferenceInstruction).reference.toString())
        }
    }

    private companion object {
        val BUILDS = listOf(385611395, 385611400, 385611404, 385611431, 385611438, 385611439, 385611440)

        fun bundles(): List<File> {
            val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
            return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
                Fixtures.otherBuilds()
        }

        fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    }
}

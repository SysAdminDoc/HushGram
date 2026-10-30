/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.messagebox

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.metaai.COMPOSER_BUTTON
import app.morphe.patches.instagram.metaai.COMPOSER_BUTTON_IDS
import app.morphe.patches.instagram.metaai.COMPOSER_BUTTON_NAMES
import app.morphe.patches.instagram.metaai.findComposerButtonVisibility
import app.morphe.patches.instagram.metaai.holdComposerButtons
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The message box buttons switch (#217): the composer's show-or-hide method hands the button and the
 * flag to the extension first, and its constructor hands the gallery, sticker and voice views over
 * as it returns. Checked on every declared build and every other build of the version, alone and
 * together with Hide Meta AI's own hook on the same method in both orders: Meta AI's call ends up first
 * either way, then this one, then the method's own code.
 */
class MessageBoxHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(MESSAGE_BOX).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(BUTTON_HOOK, CREATED_HOOK)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun withoutTheComposerThePatchRefuses() {
        val refusal = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(ExtensionDex.classDef(MESSAGE_BOX))).findMessageBox()
        }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("message box button enums"))
    }

    @Test
    fun eachDeclaredBuildHidesMessageBoxButtons() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildHidesMessageBoxButtonsToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun holders(bundle: File): List<ClassDef> {
        val kept = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (candidate in dex.classes) {
                val keep = candidate.methods.any { method ->
                    val code = method.code()
                    (method.name == "<clinit>" && code.mapNotNull { it.string() }.let {
                        it.containsAll(BOX_BUTTON_NAMES) || it.containsAll(COMPOSER_BUTTON_NAMES)
                    }) ||
                        code.any { (it as? NarrowLiteralInstruction)?.narrowLiteral.let { id -> id in BOX_BUTTON_IDS || id in COMPOSER_BUTTON_IDS } } ||
                        (method.parameterTypes.size == 3 && method.parameterTypes.last().toString() == "Z" &&
                            method.returnType == "V" && (code.firstOrNull() as? ReferenceInstruction)?.reference.let {
                                it is MethodReference && it.returnType == "Landroid/view/View;"
                            })
                }
                if (keep) kept += ImmutableClassDef.of(candidate)
            }
        }
        return kept
    }

    private fun builds(bundle: File, label: String) {
        val kept = holders(bundle)
        for (order in listOf("alone", "after Meta AI", "before Meta AI")) {
            val context = PatchContexts.of(kept + ExtensionDex.classDef(MESSAGE_BOX))
            if (order == "after Meta AI") context.holdComposerButtons(context.findComposerButtonVisibility())
            val site = context.findMessageBox()
            val holder = kept.single { it.type == site.type }
            val before = holder.methods.single { it.name == "<init>" && it.code().any { i -> (i as? NarrowLiteralInstruction)?.narrowLiteral == BOX_BUTTON_IDS[0] } }
            val beforeCode = before.code()
            val ownCode = holder.methods.single {
                it.name == site.dispatcher && it.parameterTypes.map(CharSequence::toString) == site.dispatcherParameters
            }.code()
            context.hideMessageBoxButtons(site)
            // Morphe applies patches by name, so Hide Meta AI goes first, but neither may depend on it.
            if (order == "before Meta AI") context.holdComposerButtons(context.findComposerButtonVisibility())

            val patched = context.mutableClassDefBy(site.type)
            val constructor = patched.methods.single { it.name == "<init>" && it.code().any { i -> (i as? NarrowLiteralInstruction)?.narrowLiteral == BOX_BUTTON_IDS[0] } }
            val code = constructor.code()
            assertEquals("$label: four more instructions in the constructor", beforeCode.size + 4, code.size)
            val hook = code.indexOfFirst { it.reference() == CREATED_HOOK }
            assertEquals("$label: the hook is right before the return", code.size - 2, hook)
            assertEquals("$label: the return", Opcode.RETURN_VOID, code.last().opcode)
            assertEquals("$label: three reads", 3, code.subList(hook - 3, hook).count { it.opcode == Opcode.IGET_OBJECT })
            assertEquals(
                "$label: the fields read are the ones the lookups stored",
                listOf(site.gallery, site.sticker, site.voice).map { it.toString() },
                code.subList(hook - 3, hook).map { (it as ReferenceInstruction).reference.toString() },
            )
            assertEquals("$label: one hook", 1, code.count { it.reference() == CREATED_HOOK })
            assertEquals(
                "$label: nothing before the new code moved",
                beforeCode.dropLast(1).map { it.opcode }, code.take(beforeCode.size - 1).map { it.opcode },
            )

            val dispatcher = patched.methods.single {
                it.name == site.dispatcher && it.parameterTypes.map(CharSequence::toString) == site.dispatcherParameters
            }
            val body = dispatcher.code()
            val first = dispatcher.implementation!!.registerCount - 2
            val guard = body.indexOfFirst { it.reference() == BUTTON_HOOK }
            val metaAi = order != "alone"
            assertEquals("$label $order: the button hook's place", if (metaAi) 2 else 0, guard)
            assertEquals("$label $order: Meta AI's hook", if (metaAi) 0 else -1, body.indexOfFirst { it.reference() == COMPOSER_BUTTON })
            assertEquals("$label $order: one Meta AI hook", if (metaAi) 1 else 0, body.count { it.reference() == COMPOSER_BUTTON })
            if (metaAi) assertEquals("$label $order: Meta AI's answer replaces the flag", first + 1, (body[1] as OneRegisterInstruction).registerA)
            assertEquals(
                "$label $order: the method's own code follows the hooks unchanged",
                ownCode.map { it.opcode }, body.drop(guard + 2).map { it.opcode },
            )
            assertEquals("$label: range call", Opcode.INVOKE_STATIC_RANGE, body[guard].opcode)
            assertEquals("$label: button then flag", first, (body[guard] as RegisterRangeInstruction).startRegister)
            assertEquals("$label: two registers", 2, (body[guard] as RegisterRangeInstruction).registerCount)
            assertEquals("$label: the answer replaces the flag", Opcode.MOVE_RESULT, body[guard + 1].opcode)
            assertEquals("$label: into the flag", first + 1, (body[guard + 1] as OneRegisterInstruction).registerA)
            assertEquals("$label: one button hook", 1, body.count { it.reference() == BUTTON_HOOK })
        }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}

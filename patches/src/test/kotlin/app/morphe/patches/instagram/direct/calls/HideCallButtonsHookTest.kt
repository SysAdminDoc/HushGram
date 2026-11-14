/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.calls

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The call buttons switch (#217): both binders of the chat header's buttons hand their view and
 * model to the extension first, and the kind stub reads the model's kind name. Checked on every
 * declared build and every other build of the version.
 */
class HideCallButtonsHookTest {
    @Test
    fun theHookAndStubAreInTheExtension() {
        val declared = ExtensionDex.classDef(CALL_BUTTONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (method in listOf(BOUND.substringAfter("->"), LEFT_BUTTON.substringAfter("->"), "$KIND_OF_STUB(Ljava/lang/Object;)Ljava/lang/String;")) {
            assertTrue("$method is not in the extension: $declared", method in declared)
        }
    }

    @Test
    fun withoutTheHeaderBindersThePatchRefuses() {
        val refusal = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(ExtensionDex.classDef(CALL_BUTTONS))).findCallButtons()
        }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("expected one pill button binder"))
    }

    @Test
    fun eachDeclaredBuildHidesCallButtons() {
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
    fun eachOtherBuildHidesCallButtonsToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val binds = classDef.methods.any { method ->
                    method.parameterTypes.size == 2 && method.parameterTypes[0] == "Landroid/view/View;" &&
                        method.implementation?.instructions?.any { instruction ->
                            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            reference?.definingClass == PILL && reference.name == "setButtonText"
                        } == true
                }
                if (binds) holders += ImmutableClassDef.of(classDef)
            }
        }
        assertTrue("$label: no class binds a pill button", holders.isNotEmpty())
        val models = holders.flatMap { holder -> holder.methods.mapNotNull { it.parameterTypes.getOrNull(1)?.toString() } }.toSet()
        // The header models: the classes, other than the button models, whose button model fields the binders read.
        val headers = holders.flatMap { holder ->
            holder.methods.flatMap { method ->
                method.implementation?.instructions?.mapNotNull { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? FieldReference)
                        ?.takeIf { instruction.opcode == Opcode.IGET_OBJECT && it.type in models && it.definingClass != holder.type }
                        ?.definingClass
                } ?: emptyList()
            }
        }.toSet() - models
        val kept = holders + FixtureDex.classes(bundle, models + headers).values +
            FixtureDex.classesLoading(bundle, "VIDEO_CALL") + FixtureDex.classesLoading(bundle, "AUDIO_CALL")
        val context = PatchContexts.of(kept.distinctBy { it.type } + ExtensionDex.classDef(CALL_BUTTONS))

        val found = context.findCallButtons()
        val stockHeader = found.header.implementation!!.instructions.toList()
        context.hideCallButtons()

        // The header model's constructor hands its left button to the extension first and keeps the answer in its place.
        val made = found.header.implementation!!.instructions.toList()
        val left = found.header.implementation!!.registerCount -
            (1 + found.header.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }) + found.leftRegister
        assertEquals("$label header: the call", Opcode.INVOKE_STATIC_RANGE, made[0].opcode)
        assertEquals("$label header: the hook", LEFT_BUTTON, (made[0] as ReferenceInstruction).reference.toString())
        assertEquals("$label header: the left button alone", 1, (made[0] as RegisterRangeInstruction).registerCount)
        assertEquals("$label header: the left button", left, (made[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$label header: the answer", Opcode.MOVE_RESULT_OBJECT, made[1].opcode)
        assertEquals("$label header: kept in place", left, (made[1] as OneRegisterInstruction).registerA)
        assertEquals("$label header: cast back", Opcode.CHECK_CAST, made[2].opcode)
        assertEquals("$label header: to the button model", found.model, (made[2] as ReferenceInstruction).reference.toString())
        assertEquals("$label header: on the same register", left, (made[2] as OneRegisterInstruction).registerA)
        assertEquals("$label header: the stock body follows", stockHeader.map { it.opcode }, made.drop(3).map { it.opcode })
        // The register handed over is the one the constructor keeps in the field the binder reads.
        val keeps = made.filter { it.opcode == Opcode.IPUT_OBJECT && (it as ReferenceInstruction).reference.toString().endsWith(":${found.model}") }
        assertEquals("$label header: one button model kept", 1, keeps.size)
        assertEquals("$label header: kept from the left button", left, (keeps.single() as TwoRegisterInstruction).registerA)
        assertEquals("$label header: hooks", 1, made.count { (it as? ReferenceInstruction)?.reference?.toString() == LEFT_BUTTON })

        for ((what, binder) in listOf("pill" to found.pill, "icon" to found.icon)) {
            val code = binder.implementation!!.instructions.toList()
            val first = code[0]
            assertEquals("$label $what: the call", Opcode.INVOKE_STATIC_RANGE, first.opcode)
            assertEquals("$label $what: the hook", BOUND, (first as ReferenceInstruction).reference.toString())
            // The view and the model are the two registers after this.
            assertEquals("$label $what: view and model", 2, (first as RegisterRangeInstruction).registerCount)
            assertEquals(
                "$label $what: view first",
                binder.implementation!!.registerCount - binder.parameterTypes.size, first.startRegister,
            )
            assertEquals("$label $what: hooks", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == BOUND })
        }
        val stub = context.mutableClassDefBy(CALL_BUTTONS).methods.single { it.name == KIND_OF_STUB }.implementation!!.instructions.toList()
        assertEquals("$label: the kind field", found.kind.toString(), stub.first { it.opcode == Opcode.IGET_OBJECT }.let { (it as ReferenceInstruction).reference.toString() })
        assertTrue("$label: the kind's name", stub.any { (it as? ReferenceInstruction)?.reference?.toString() == "Ljava/lang/Enum;->name()Ljava/lang/String;" })
        // One return after a merge would hand back the enum or the String as an Object, and ART rejects the class for it.
        // The stub's own return null stays after both, never reached.
        val returns = stub.indices.filter { stub[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals("$label: the name, then null", listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.CONST_4), returns.take(2).map { stub[it - 1].opcode })
    }

    private companion object {
        const val PILL = "Lcom/instagram/ui/widget/pulsingbutton/PulsingPillButton;"
    }
}

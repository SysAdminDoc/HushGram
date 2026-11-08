/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.edits

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.feedfollow.FEED_HEADER_STATE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide Made with Edits (#570): the hook is in the extension, and in every build of 454 each
 * constructor of the feed header's label state hands its kinds list to the extension before storing it.
 */
class HideEditsLabelPatchTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(EDITS_LABELS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$EDITS_LABELS is not in the extension: $declared", EDITS_LABELS.substringAfter("->") in declared)
    }

    @Test
    fun eachBuildHandsTheLabelKindsToTheExtension() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            val headers = FixtureDex.classesLoading(bundle, FEED_HEADER_STATE)
            val kinds = FixtureDex.classesLoading(bundle, EDITS_KIND)
            val states = headers.flatMap { it.methods }.flatMap { it.code() }
                .filter { it.opcode == Opcode.IGET_OBJECT }.map { it.reference() as FieldReference }
                .filter { it.type == "Ljava/util/List;" }.map { it.definingClass }.toSet()
            val kept = (headers + kinds + FixtureDex.classes(bundle, states).values).distinctBy { it.type }
            val context = PatchContexts.of(kept)

            val site = context.findEditsLabel()
            context.hideEditsLabel(site)

            var stores = 0
            for (constructor in context.mutableClassDefBy(site.state).methods.filter { it.name == "<init>" }) {
                val code = constructor.code()
                for (at in code.indices) {
                    if (code[at].opcode != Opcode.IPUT_OBJECT || code[at].reference().toString() != site.field) continue
                    val value = (code[at] as TwoRegisterInstruction).registerA
                    val call = code[at - 2]
                    assertEquals("${bundle.name}: the hook before the store", EDITS_LABELS, call.reference().toString())
                    assertEquals("${bundle.name}: the hook takes the list", value, (call as RegisterRangeInstruction).startRegister)
                    assertEquals("${bundle.name}: the answer is stored", Opcode.MOVE_RESULT_OBJECT, code[at - 1].opcode)
                    assertEquals("${bundle.name}: into the same register", value, (code[at - 1] as OneRegisterInstruction).registerA)
                    stores++
                }
            }
            assertTrue("${bundle.name}: no constructor stores ${site.field}", stores > 0)
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
}

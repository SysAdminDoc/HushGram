/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.hdr

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep HDR on photo uploads (#532): Instagram's check for keeping Ultra HDR on photos hands its
 * answer to HdrPhotos where it returns, the final renderer's flag for gain maps in color goes
 * through HdrPhotos before its branch, and the photo save step tells HdrPhotos of each JPEG it
 * writes with Android's encoder.
 */
class KeepHdrOnPhotoUploadsHookTest {
    /** The opcodes of [code] as a comparison of stock and patched code reads them: every goto as GOTO, no NOPs or payloads. */
    private fun opcodes(code: List<Instruction>) = code
        .filter { it.opcode != Opcode.NOP && !it.opcode.format.isPayloadFormat }
        .map { if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode }

    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(HDR_PHOTOS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$HDR_PHOTOS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(HDR_PHOTOS_ULTRA_HDR, HDR_PHOTOS_COLOR_GAINMAP, HDR_PHOTOS_SAVED)) {
            assertTrue("$hook is not in the extension: $declared", hook in declared)
        }
    }

    /** On each declared build all three go in, on 457's check `0RjC.A00`, renderer `0UGd.H39` and save step `0D3E.A05`. */
    @Test
    fun eachDeclaredBuildHooksAllThree() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val sites = hooks(bundle, bundle.name)
                if (version == "457.0.0.55.73") {
                    assertEquals("${bundle.name}: the check", "LX/0RjC;->A00", "${sites.gate.type}->${sites.gate.name}")
                    assertEquals("${bundle.name}: its return", 17 to 3, sites.gateReturn to sites.gateRegister)
                    assertEquals("${bundle.name}: the renderer", "LX/0UGd;->H39", "${sites.renderer.type}->${sites.renderer.name}")
                    assertEquals("${bundle.name}: the color flag's branch", 177 to 9, sites.colorBranch to sites.colorRegister)
                    assertEquals("${bundle.name}: the save step", "LX/0D3E;->A05", "${sites.save.type}->${sites.save.name}")
                    assertEquals("${bundle.name}: after the JPEG write", 102, sites.savedAt)
                    assertEquals("${bundle.name}: picture, path, answer", listOf(2, 5, 0), listOf(sites.picture, sites.path, sites.written))
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksThemToo() {
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    /** A second run finds its own calls and refuses instead of asking twice. */
    @Test
    fun aSecondRunRefuses() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val context = PatchContexts.of(classes(bundle) + ExtensionDex.classDef(HDR_PHOTOS))
        context.keepHdrOnPhotoUploads(context.findHdrPhotos())
        val failure = assertThrows(PatchException::class.java) { context.findHdrPhotos() }
        assertTrue(failure.message, failure.message!!.contains("already calls $HDR_PHOTOS"))
    }

    /** Without the extension, the preview or the renderer, the patch fails before anything changes. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val all = classes(bundle)
        val sites = PatchContexts.of(all + ExtensionDex.classDef(HDR_PHOTOS)).findHdrPhotos()

        val withoutExtension = PatchContexts.of(all)
        val missing = assertThrows(PatchException::class.java) { withoutExtension.findHdrPhotos() }
        assertTrue(missing.message, missing.message!!.contains("the extension has no $HDR_PHOTOS"))

        val withoutPreview = PatchContexts.of(all.filter { it.type != THUMBNAIL_PREVIEW } + ExtensionDex.classDef(HDR_PHOTOS))
        val noPreview = assertThrows(PatchException::class.java) { withoutPreview.findHdrPhotos() }
        assertTrue(noPreview.message, noPreview.message!!.contains("there's no $THUMBNAIL_PREVIEW"))

        val stockGate = all.single { it.type == sites.gate.type }.methods.single { it.name == sites.gate.name }.implementation!!.instructions.map { it.opcode }
        val withoutRenderer = PatchContexts.of(all.filter { it.type != sites.renderer.type } + ExtensionDex.classDef(HDR_PHOTOS))
        val noRenderer = assertThrows(PatchException::class.java) { withoutRenderer.findHdrPhotos() }
        assertTrue(noRenderer.message, noRenderer.message!!.contains("expected one method loading \"$FINAL_RENDERER_MARKER\""))
        assertEquals("the check is left as it was", stockGate, withoutRenderer.code(sites.gate).map { it.opcode })
    }

    private fun hooks(bundle: File, label: String): PhotoHdrSites {
        val context = PatchContexts.of(classes(bundle) + ExtensionDex.classDef(HDR_PHOTOS))
        val sites = context.findHdrPhotos()
        val gate = context.code(sites.gate)
        val renderer = context.code(sites.renderer)
        val save = context.code(sites.save)
        context.keepHdrOnPhotoUploads(sites)

        // The check: the answer goes through the extension and back into its register right before the return, and every branch to the return lands on the hook.
        val hookedGate = context.code(sites.gate)
        assertEquals("$label: two instructions go into the check", opcodes(gate).size + 2, opcodes(hookedGate).size)
        assertEquals("$label: the check up to its return", opcodes(gate.take(sites.gateReturn)), opcodes(hookedGate.take(sites.gateReturn)))
        assertEquals(label, listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.RETURN), opcodes(hookedGate.drop(sites.gateReturn)))
        hookedGate[sites.gateReturn].assertCalls(label, HDR_PHOTOS_ULTRA_HDR, listOf(sites.gateRegister))
        assertEquals("$label: the answer goes back", sites.gateRegister, (hookedGate[sites.gateReturn + 1] as OneRegisterInstruction).registerA)
        assertEquals("$label: and is returned", sites.gateRegister, (hookedGate[sites.gateReturn + 2] as OneRegisterInstruction).registerA)
        val method = context.mutableClassDefBy(sites.gate.type).methods.single { it.name == sites.gate.name }
        val all = method.implementation!!.instructions.toList()
        val landings = all.filterIsInstance<BuilderOffsetInstruction>().map { all[it.target.location.index] }
        assertTrue("$label: a branch lands on the hook", landings.any { it === all[sites.gateReturn] })
        assertTrue("$label: no branch skips the hook", landings.none { it === all[sites.gateReturn + 2] })

        // The renderer: the color flag goes through the extension between its move-result and its branch.
        val hookedRenderer = context.code(sites.renderer)
        assertEquals("$label: two instructions go into the renderer", opcodes(renderer).size + 2, opcodes(hookedRenderer).size)
        assertEquals("$label: the renderer up to the branch", opcodes(renderer.take(sites.colorBranch)), opcodes(hookedRenderer.take(sites.colorBranch)))
        assertEquals("$label: the renderer from the branch on", opcodes(renderer.drop(sites.colorBranch)), opcodes(hookedRenderer.drop(sites.colorBranch + 2)))
        assertEquals(label, Opcode.MOVE_RESULT, renderer[sites.colorBranch - 1].opcode)
        hookedRenderer[sites.colorBranch].assertCalls(label, HDR_PHOTOS_COLOR_GAINMAP, listOf(sites.colorRegister))
        assertEquals("$label: the flag goes back", sites.colorRegister, (hookedRenderer[sites.colorBranch + 1] as OneRegisterInstruction).registerA)
        assertEquals("$label: before the branch on it", Opcode.IF_NEZ, hookedRenderer[sites.colorBranch + 2].opcode)
        assertEquals(label, sites.colorRegister, (hookedRenderer[sites.colorBranch + 2] as OneRegisterInstruction).registerA)

        // The save step: one call right after the JPEG write's answer, with the picture, the path and the answer.
        val hookedSave = context.code(sites.save)
        assertEquals("$label: one instruction goes into the save step", opcodes(save).size + 1, opcodes(hookedSave).size)
        assertEquals("$label: the save step up to the hook", opcodes(save.take(sites.savedAt)), opcodes(hookedSave.take(sites.savedAt)))
        assertEquals("$label: the save step after it", opcodes(save.drop(sites.savedAt)), opcodes(hookedSave.drop(sites.savedAt + 1)))
        assertEquals("$label: the hook follows the write's answer", Opcode.MOVE_RESULT, save[sites.savedAt - 1].opcode)
        hookedSave[sites.savedAt].assertCalls(label, HDR_PHOTOS_SAVED, listOf(sites.picture, sites.path, sites.written))
        return sites
    }

    private fun Instruction.assertCalls(label: String, method: String, registers: List<Int>) {
        assertEquals("$label: calls the extension", method, ((this as ReferenceInstruction).reference as MethodReference).toString())
        val passed = when (this) {
            is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
            is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
            else -> emptyList()
        }
        assertEquals("$label: with the registers", registers, passed)
    }

    /**
     * The preview and the classes loading the renderer's and the save step's strings, with the
     * string pools they ask, and the class the preview's check is in.
     */
    private fun classes(bundle: File): List<ClassDef> {
        val found = FixtureDex.withStringPools(
            bundle,
            FixtureDex.classesLoadingAny(bundle, setOf(FINAL_RENDERER_MARKER, PHOTO_SAVE_MARKER)) { it.type == THUMBNAIL_PREVIEW },
        )
        val preview = found.single { it.type == THUMBNAIL_PREVIEW }
        val checks = preview.methods.single { it.name == "onResume" }.implementation!!.instructions
            .mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference) }
            .filter { it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION) }
            .map { it.definingClass }.toSet()
        return found + FixtureDex.classes(bundle, checks - found.map { it.type }.toSet()).values
    }

    private fun BytecodePatchContext.code(site: PhotoMethod): List<Instruction> =
        mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
            .implementation!!.instructions.toList()
}

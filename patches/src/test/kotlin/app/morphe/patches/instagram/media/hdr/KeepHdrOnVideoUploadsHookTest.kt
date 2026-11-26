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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep HDR on video uploads (#532): where Instagram's encoder search marks a candidate SDR, the
 * mark goes through HdrVideos with whether HLG was asked for, and the HLG mark after the P010 test
 * and every branch onto the SDR mark stay as they were.
 */
class KeepHdrOnVideoUploadsHookTest {
    /** The opcodes of [code] as a comparison of stock and patched code reads them: every goto as GOTO, no NOPs or payloads. */
    private fun opcodes(code: List<Instruction>) = code
        .filter { it.opcode != Opcode.NOP && !it.opcode.format.isPayloadFormat }
        .map { if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode }

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HDR_VIDEOS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$HDR_VIDEOS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HDR_VIDEOS_HLG is not in the extension: $declared", HDR_VIDEOS_HLG in declared)
    }

    /** On each declared build the hook goes in, on 457 arm64's search `0KCB.A00` after its `const/16 v21 #0x0`, reading p4 (v26). */
    @Test
    fun eachDeclaredBuildHooksTheSearch() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val site = hooks(bundle, bundle.name)
                if (version == "457.0.0.55.73") {
                    assertEquals("${bundle.name}: the search", "LX/0KCB;->A00", "${site.search.type}->${site.search.name}")
                    assertEquals("${bundle.name}: its parameters", listOf("LX/0KEg;", "I", "I", "Z", "Z", "Z"), site.search.parameters)
                    assertEquals("${bundle.name}: after the SDR mark, the mark, whether HLG is asked for", listOf(97, 21, 26), listOf(site.markedAt, site.flag, site.wanted))
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The other builds hook the same way; 457's x86_64 build names the search `0KEq.A00`. */
    @Test
    fun eachOtherBuildHooksItToo() {
        for (base in Fixtures.otherBuilds()) {
            val site = hooks(base, base.parentFile.name)
            if (base.parentFile.name == "instagram-457.0.0.55.73-386314286") {
                assertEquals("${base.parentFile.name}: the search", "LX/0KEq;->A00", "${site.search.type}->${site.search.name}")
                assertEquals("${base.parentFile.name}: after the SDR mark, the mark, whether HLG is asked for", listOf(97, 21, 26), listOf(site.markedAt, site.flag, site.wanted))
            }
        }
    }

    /** A second run finds its own call and refuses instead of asking twice. */
    @Test
    fun aSecondRunRefuses() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val context = PatchContexts.of(classes(bundle) + ExtensionDex.classDef(HDR_VIDEOS))
        context.keepHdrOnVideoUploads(context.findHdrVideos())
        val failure = assertThrows(PatchException::class.java) { context.findHdrVideos() }
        assertTrue(failure.message, failure.message!!.contains("already calls $HDR_VIDEOS"))
    }

    /** Without the extension or the search, the patch fails before anything changes. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val all = classes(bundle)
        val site = PatchContexts.of(all + ExtensionDex.classDef(HDR_VIDEOS)).findHdrVideos()

        val withoutExtension = PatchContexts.of(all)
        val missing = assertThrows(PatchException::class.java) { withoutExtension.findHdrVideos() }
        assertTrue(missing.message, missing.message!!.contains("the extension has no $HDR_VIDEOS"))
        val stock = all.single { it.type == site.search.type }.methods.single { it.name == site.search.name && it.parameterTypes.map(CharSequence::toString) == site.search.parameters }
            .implementation!!.instructions.map { it.opcode }
        assertEquals("the search is left as it was", stock, withoutExtension.code(site.search).map { it.opcode })

        val withoutSearch = PatchContexts.of(all.filter { it.type != site.search.type } + ExtensionDex.classDef(HDR_VIDEOS))
        val noSearch = assertThrows(PatchException::class.java) { withoutSearch.findHdrVideos() }
        assertTrue(noSearch.message, noSearch.message!!.contains("expected one static method returning a HashSet that loads \"$ENCODER_SEARCH_MARKER\" and reads $COLOR_FORMATS, found 0"))
    }

    private fun hooks(bundle: File, label: String): VideoHdrSite {
        val stock = classes(bundle)
        val context = PatchContexts.of(stock + ExtensionDex.classDef(HDR_VIDEOS))
        val site = context.findHdrVideos()
        val search = context.code(site.search)

        // What the finder read, checked against the stock code: the P010 test, the HLG mark after it, the SDR mark and its goto, and the parameter asked.
        val test = search.indices.single { at -> search[at].literal() == P010 && search.getOrNull(at + 1)?.opcode == Opcode.IF_NE }
        assertEquals("$label: the HLG mark follows the P010 test", 1, search[test + 2].literal())
        assertEquals("$label: on the mark's register", site.flag, (search[test + 2] as OneRegisterInstruction).registerA)
        assertEquals("$label: the SDR mark", 0, search[site.markedAt - 1].literal())
        assertEquals("$label: on the same register", site.flag, (search[site.markedAt - 1] as OneRegisterInstruction).registerA)
        assertEquals("$label: then a goto", Opcode.GOTO, opcodes(listOf(search[site.markedAt])).single())
        val sdk = search.indices.single { (search[it].reference() as? FieldReference)?.toString() == "Landroid/os/Build\$VERSION;->SDK_INT:I" }
        assertEquals("$label: whether HLG is asked for is tested right before the Android version read", Opcode.IF_EQZ, search[sdk - 1].opcode)
        assertEquals(label, site.wanted, (search[sdk - 1] as OneRegisterInstruction).registerA)

        context.keepHdrOnVideoUploads(site)
        val hooked = context.code(site.search)

        // Two instructions go in between the SDR mark and its goto, and the rest is as it was.
        assertEquals("$label: two instructions go into the search", opcodes(search).size + 2, opcodes(hooked).size)
        assertEquals("$label: the search up to the hook", opcodes(search.take(site.markedAt)), opcodes(hooked.take(site.markedAt)))
        assertEquals("$label: the search after it", opcodes(search.drop(site.markedAt)), opcodes(hooked.drop(site.markedAt + 2)))
        hooked[site.markedAt].assertCalls(label, HDR_VIDEOS_HLG, listOf(site.wanted))
        assertEquals("$label: the answer is the mark", Opcode.MOVE_RESULT, hooked[site.markedAt + 1].opcode)
        assertEquals(label, site.flag, (hooked[site.markedAt + 1] as OneRegisterInstruction).registerA)

        // Every branch that landed on the SDR mark still does, nothing lands on the hook, and the goto still goes where the HLG mark goes on.
        val method = context.mutableClassDefBy(site.search.type).methods.single { it.name == site.search.name && it.parameterTypes.map(CharSequence::toString) == site.search.parameters }
        val all = method.implementation!!.instructions.toList()
        val landings = all.filterIsInstance<BuilderOffsetInstruction>().map { all[it.target.location.index] }
        assertEquals("$label: the four tests that end on the SDR mark", 4, landings.count { it === all[site.markedAt - 1] })
        assertTrue("$label: no branch lands on the hook", landings.none { it === all[site.markedAt] || it === all[site.markedAt + 1] })
        val join = all[site.markedAt + 2] as BuilderOffsetInstruction
        assertTrue("$label: the goto goes on after the HLG mark", all[join.target.location.index] === all[test + 3])

        // Every other method of these classes reading the encoders' color formats, the camera's HDR capability check among them, is left as it was.
        val readers = stock.flatMap { type ->
            type.methods.filter { reader -> reader.implementation?.instructions?.any { (it.reference() as? FieldReference)?.toString() == COLOR_FORMATS } == true }
                .map { VideoMethod(type.type, it.name, it.parameterTypes.map(CharSequence::toString)) to it.implementation!!.instructions.map { instruction -> instruction.opcode } }
        }.filter { it.first != site.search }
        for ((reader, opcodes) in readers) assertEquals("$label: ${reader.type}->${reader.name} is left as it was", opcodes, context.code(reader).map { it.opcode })
        if (bundle.name.endsWith("-386314341.apks")) {
            assertTrue("$label: the capability check is one of them: $readers", readers.any { (reader, _) -> "${reader.type}->${reader.name}" == "LX/0bHZ;->A03" })
        }
        return site
    }

    private fun Instruction.literal(): Int? = if (opcode == Opcode.CONST_4 || opcode == Opcode.CONST_16 || opcode == Opcode.CONST) (this as NarrowLiteralInstruction).narrowLiteral else null
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.assertCalls(label: String, method: String, registers: List<Int>) {
        assertEquals("$label: calls the extension", method, ((this as ReferenceInstruction).reference as MethodReference).toString())
        assertEquals("$label: as a range", Opcode.INVOKE_STATIC_RANGE, opcode)
        val passed = (this as RegisterRangeInstruction).let { (it.startRegister until it.startRegister + it.registerCount).toList() }
        assertEquals("$label: with the registers", registers, passed)
    }

    /** The classes loading the encoder search's string, with the string pools they ask. */
    private fun classes(bundle: File): List<ClassDef> =
        FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, ENCODER_SEARCH_MARKER))

    private fun BytecodePatchContext.code(site: VideoMethod): List<Instruction> =
        mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
            .implementation!!.instructions.toList()
}

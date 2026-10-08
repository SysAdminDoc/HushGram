/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.screenshots

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Don't report screenshots: both of Instagram's screenshot detectors ask the extension first where
 * they hand a screenshot on. Anything the patch can't tell apart fails it before an instruction changes.
 */
class DontReportScreenshotsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SCREENSHOT_REPORTS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("hold is not in the extension: $declared", HOLD_SCREENSHOT.substringAfter("->") in declared)
    }

    @Test
    fun bothDetectorsAskFirst() {
        val context = PatchContexts.of(listOf(media(), folder()))
        val reports = context.findScreenshotReports()
        reports.forEach(::holdScreenshotReport)
        assertHooked("stand-ins", context, reports)
    }

    @Test
    fun aMissingOrDoubledHandOffFailsThePatch() {
        refuses("found 0", listOf(media(handOff = false), folder()))
        refuses("found 2", listOf(media(second = true), folder()))
        refuses("media screenshot detector", listOf(media(), media("Lfixture/OtherMedia;"), folder()))
        refuses("folder screenshot report", listOf(media()))
        refuses("folder screenshot report", listOf(media(), folder(static = false)))
    }

    @Test
    fun aHandOffWithoutALocalOrWithAJumpBackFailsThePatch() {
        refuses("needs 1", listOf(media(registers = 5), folder()))
        refuses("jumps back to the media detector's", listOf(media(loop = true), folder()))
        refuses("jumps back to the folder screenshot report's", listOf(media(), folder(loop = true)))
    }

    /** In each declared build: each detector's one hand-off, asking the extension first. */
    @Test
    fun eachDeclaredBuildHoldsBothDetectors() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = (FixtureDex.classesHolding(bundle, DETECTOR_SESSION) + FixtureDex.classesHolding(bundle, FOLDER_REPORT))
                    .distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val reports = context.findScreenshotReports()
                assertTrue("${bundle.name}: the two hand-offs are in two detectors", reports[0].definingClass != reports[1].definingClass)
                reports.forEach(::holdScreenshotReport)
                assertHooked(bundle.name, context, reports)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.instructions().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { context.findScreenshotReports().forEach(::holdScreenshotReport) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.instructions().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    private fun assertHooked(what: String, context: BytecodePatchContext, reports: List<MutableMethod>) {
        for (report in reports) {
            val code = report.instructions()
            assertEquals("$what: the call", HOLD_SCREENSHOT, (code[0] as ReferenceInstruction).reference.toString())
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
            assertEquals("$what: the branch", Opcode.IF_EQZ, code[2].opcode)
            assertEquals("$what: the branch's target", 4, (report.implementation!!.instructions.toList()[2] as BuilderOffsetInstruction).target.location.index)
            assertEquals("$what: the early return", Opcode.RETURN_VOID, code[3].opcode)
            val calls = context.mutableClassDefBy(report.definingClass).methods.sumOf { method ->
                method.instructions().count { (it as? ReferenceInstruction)?.reference?.toString() == HOLD_SCREENSHOT }
            }
            assertEquals("$what: hold calls in ${report.definingClass}", 1, calls)
        }
    }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference"
    }

    private companion object {
        const val MEDIA = "Lfixture/MediaDetector;"
        const val FOLDER = "Lfixture/FolderDetector;"

        /** Loads [strings] into the first registers, then a nop, a nop (or a jump back to the start) and a return. */
        fun code(registers: Int, loop: Boolean, vararg strings: String) = ImmutableMethodImplementation(
            registers,
            strings.mapIndexed { i, string -> ImmutableInstruction21c(Opcode.CONST_STRING, i, ImmutableStringReference(string)) } +
                listOf(
                    ImmutableInstruction10x(Opcode.NOP),
                    if (loop) ImmutableInstruction10t(Opcode.GOTO, -(2 * strings.size + 1)) else ImmutableInstruction10x(Opcode.NOP),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            null, null,
        )

        fun method(type: String, name: String, parameters: List<String>, access: Int, implementation: ImmutableMethodImplementation) =
            ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", access, null, null, implementation)

        /** Shaped like the media detector: a session starter holding both log lines, and the hand-off. */
        fun media(type: String = MEDIA, handOff: Boolean = true, second: Boolean = false, registers: Int = 6, loop: Boolean = false): ClassDef {
            val access = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            val methods = mutableListOf(method(type, "start", emptyList(), access, code(3, false, CONTENT_DETECTOR, DETECTOR_SESSION)))
            if (handOff) methods += method(type, "found", FOUND_PARAMETERS, access, code(registers, loop))
            if (second) methods += method(type, "foundAgain", FOUND_PARAMETERS, access, code(registers, loop))
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
        }

        /** Shaped like the folder detector's static report, (detector, path, list), logging each listener it tells. */
        fun folder(static: Boolean = true, loop: Boolean = false): ClassDef {
            val access = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
            val parameters = listOf(FOLDER, "Ljava/lang/String;", "Ljava/util/List;")
            val report = method(FOLDER, "report", parameters, access, code(5, loop, FOLDER_REPORT))
            return ImmutableClassDef(FOLDER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(report))
        }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.photos

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LargerPhotosHookTest {
    private val pickerType = "Lfixture/SizePicker;"
    private val reportType = "Lfixture/UserAgent;"
    private val pickerParameters = listOf("Ljava/lang/Integer;", "Ljava/util/List;", "I")

    /** Both hooks the patch writes are in the LargerPhotos the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(LARGER_PHOTOS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(WANTED, SCREEN)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * The picker's width goes through the hook first thing and comes back as the width. The user
     * agent's screen parts go through the other right after they're kept, and nothing else moves.
     */
    @Test
    fun bothHooksLandWhereTheSizesAreDecided() {
        val classes = listOf(classDef(pickerType, listOf(picker())), classDef(reportType, listOf(report())))
        val reportBefore = classes[1].methods.single().code().size
        val context = PatchContexts.of(classes)

        askForLarger(context.findLargerSizes())

        assertPickerHooked("stand-in", context.mutableClassDefBy(pickerType).methods.single())
        val report = context.mutableClassDefBy(reportType).methods.single()
        assertReportHooked("stand-in", report)
        assertEquals("the user agent grew by more than the hook", reportBefore + 1, report.code().size)
        assertEquals("the parts' register", 4, (report.code().single { it.referenceText() == SCREEN } as RegisterRangeInstruction).startRegister)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val where = "$reportType->report"
        val cases = listOf(
            listOf(classDef(reportType, listOf(report()))) to "expected exactly one size picker",
            listOf(classDef(pickerType, listOf(picker(), picker("pick2"))), classDef(reportType, listOf(report()))) to
                "expected exactly one size picker",
            listOf(classDef(pickerType, listOf(picker())), classDef(reportType, listOf(report(formats = 2)))) to
                "expected $where to load the screen's format once, found 2",
            listOf(classDef(pickerType, listOf(picker())), classDef(reportType, listOf(report(parts = 2)))) to
                "expected $where to make one three-part array beside the screen's format, found 0",
            listOf(classDef(pickerType, listOf(picker())), classDef(reportType, listOf(report(kept = false)))) to
                "$where doesn't keep the screen's parts",
            listOf(classDef(pickerType, listOf(picker())), classDef(reportType, listOf(report(jumpIn = true)))) to
                "something in $where jumps in right after the screen's parts",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { askForLarger(context.findLargerSizes()) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == WANTED || it.referenceText() == SCREEN } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build both lookups find their one method and each hook lands once, where it should. */
    @Test
    fun eachDeclaredBuildAsksForTheLargerSize() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        val holds = classDef.methods.any { method ->
                            method.code().any { it.string() == SCREEN_FORMAT } ||
                                (method.returnType == EXTENDED_IMAGE_URL && method.parameterTypes.map(CharSequence::toString) == pickerParameters)
                        }
                        if (holds) holders += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(holders)

                val sites = context.findLargerSizes()
                askForLarger(sites)

                assertPickerHooked(bundle.name, sites.picker)
                assertReportHooked(bundle.name, sites.report)
                val written = holders.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
                assertEquals("${bundle.name}: methods asking", 1, written.count { m -> m.code().any { it.referenceText() == WANTED } })
                assertEquals("${bundle.name}: methods reporting", 1, written.count { m -> m.code().any { it.referenceText() == SCREEN } })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun assertPickerHooked(what: String, method: MutableMethod) {
        val code = method.code()
        val width = method.implementation!!.registerCount - 1
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == WANTED })
        assertEquals("$what: the hook comes first", WANTED, code[0].referenceText())
        val call = code[0] as RegisterRangeInstruction
        assertEquals("$what: the width handed over", listOf(width, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: kept as the width", width, (code[1] as OneRegisterInstruction).registerA)
    }

    private fun assertReportHooked(what: String, method: MutableMethod) {
        val code = method.code()
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == SCREEN })
        val hook = code.indexOfFirst { it.referenceText() == SCREEN }
        val array = code[hook - 2]
        assertEquals("$what: the parts", Opcode.FILLED_NEW_ARRAY, array.opcode)
        assertEquals("$what: three parts", 3, (array as FiveRegisterInstruction).registerCount)
        assertEquals("$what: kept", Opcode.MOVE_RESULT_OBJECT, code[hook - 1].opcode)
        val kept = (code[hook - 1] as OneRegisterInstruction).registerA
        val call = code[hook] as RegisterRangeInstruction
        assertEquals("$what: the parts handed over", listOf(kept, 1), listOf(call.startRegister, call.registerCount))
        val format = code.indexOfFirst { it.string() == SCREEN_FORMAT }
        assertTrue("$what: the format is beside the parts", format in hook - 6..hook + 5)
    }

    /** A size picker shaped like Instagram 450's: it reads the first size's width and answers that size. */
    private fun picker(name: String = "pick"): Method = method(
        pickerType, name, pickerParameters, EXTENDED_IMAGE_URL, locals = 3,
        """
            const/4 v0, 0x0
            invoke-interface { v4, v0 }, Ljava/util/List;->get(I)Ljava/lang/Object;
            move-result-object v1
            check-cast v1, $EXTENDED_IMAGE_URL
            invoke-virtual { v1 }, $EXTENDED_IMAGE_URL->getWidth()I
            move-result v2
            return-object v1
        """,
    )

    /**
     * A user agent's device part shaped like Instagram 450's: the density, width and height made
     * into Integers in v1 to v3, put in a three-part array kept in v4, and formatted.
     */
    private fun report(formats: Int = 1, parts: Int = 3, kept: Boolean = true, jumpIn: Boolean = false): Method {
        val array = if (parts == 3) "filled-new-array { v1, v2, v3 }, [Ljava/lang/Object;" else "filled-new-array { v1, v2 }, [Ljava/lang/Object;"
        val body = buildString {
            appendLine("const/16 v0, 0x1a4")
            appendLine("invoke-static { v0 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;")
            appendLine("move-result-object v1")
            appendLine("const/16 v0, 0x438")
            appendLine("invoke-static { v0 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;")
            appendLine("move-result-object v2")
            appendLine("const/16 v0, 0x924")
            appendLine("invoke-static { v0 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;")
            appendLine("move-result-object v3")
            if (jumpIn) appendLine("if-eqz v5, :after")
            appendLine(array)
            if (kept) appendLine("move-result-object v4") else appendLine("const/4 v4, 0x0")
            if (jumpIn) appendLine(":after")
            appendLine("const-string v0, \"$SCREEN_FORMAT\"")
            appendLine("invoke-static { v0, v4 }, Ljava/lang/String;->format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;")
            appendLine("move-result-object v0")
            if (formats == 2) appendLine("const-string v0, \"$SCREEN_FORMAT\"")
            appendLine("return-object v0")
        }
        return method(reportType, "report", listOf("Landroid/content/Context;"), "Ljava/lang/String;", locals = 5, body)
    }

    private fun method(type: String, name: String, parameters: List<String>, returns: String, locals: Int, body: String): Method {
        val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
        val mutable = MutableMethod(
            ImmutableMethod(
                type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(locals + parameters.size, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = (reference() as? MethodReference)?.toString()

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string
}

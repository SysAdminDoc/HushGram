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
import app.morphe.patches.instagram.download.IMAGE_URL
import app.morphe.patches.instagram.misc.extension.INSTAGRAM_APPLICATION
import app.morphe.patches.instagram.misc.extension.jumpTargets
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        for (hook in listOf(WANTED, SCREEN, AT_START, SAMPLE_SIZE)) {
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

    /**
     * The kept replies are dropped right after the extension gets its context, first thing in the
     * application's onCreate and before Instagram's own startup, handed the application itself.
     */
    @Test
    fun theKeptRepliesAreDroppedRightAfterTheContext() {
        val context = PatchContexts.of(listOf(application(onCreate())))
        val before = context.mutableClassDefBy(INSTAGRAM_APPLICATION).methods.single().code().size

        forgetAtStart(context.findStart())

        val onCreate = context.mutableClassDefBy(INSTAGRAM_APPLICATION).methods.single()
        val code = onCreate.code()
        assertEquals("onCreate grew by more than the hook", before + 1, code.size)
        assertEquals("the context comes first", SET_CONTEXT, code[0].referenceText())
        assertEquals("then the hook", AT_START, code[1].referenceText())
        assertEquals("hooks", 1, code.count { it.referenceText() == AT_START })
        val call = code[1] as RegisterRangeInstruction
        val application = onCreate.implementation!!.registerCount - 1
        assertEquals("the application handed over", listOf(application, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("Instagram's own startup follows", Opcode.INVOKE_SUPER, code[2].opcode)
    }

    /** An onCreate the extension doesn't get its context in once fails at patch time, with nothing written. */
    @Test
    fun anOnCreateWithoutTheContextOnceFailsBeforeAnythingChanges() {
        for (calls in listOf(0, 2)) {
            val context = PatchContexts.of(listOf(application(onCreate(calls))))
            val failure = assertThrows(PatchException::class.java) { forgetAtStart(context.findStart()) }
            assertTrue("$calls: ${failure.message}", failure.message!!.contains("to hand the extension its context once, found $calls"))
            val code = context.mutableClassDefBy(INSTAGRAM_APPLICATION).methods.single().code()
            assertTrue("$calls: something was written", code.none { it.referenceText() == AT_START })
        }
    }

    /**
     * The image view hands the extension the view, the picture and the size it may decode at right
     * after reading the size, keeping the answer as the size: through a spare low register for a
     * picture past v15, and straight from the parameter otherwise. The getter is left alone.
     */
    @Test
    fun theImageViewAsksTheExtensionHowSmallToDecode() {
        val context = PatchContexts.of(listOf(imageView(getter(), reader(), quickReader())))

        decodeToFit(context.findDecodes())

        val methods = context.mutableClassDefBy(IG_IMAGE_VIEW).methods
        val reader = methods.single { it.name == "A03" }
        assertDecodeHooked("stand-in A03", reader)
        assertDecodeHooked("stand-in A04", methods.single { it.name == "A04" })
        assertTrue("the getter changed", methods.single { it.name.startsWith(MAX_SAMPLE_SIZE_GETTER) }.code().none { it.referenceText() == SAMPLE_SIZE })
        val code = reader.code()
        assertEquals(
            "stand-in A03",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IGET, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.CONST_4),
            code.take(6).map { it.opcode },
        )
        assertEquals("the spare and the picture", listOf(1, 21), (code[2] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals("A03 grew by more than the hook", 9, code.size)
        assertEquals("A04 grew by more than the hook", 7, methods.single { it.name == "A04" }.code().size)
    }

    /** A register a try's handler reads after the size is read isn't borrowed for the picture. */
    @Test
    fun aRegisterAHandlerReadsIsLeftAlone() {
        val context = PatchContexts.of(listOf(imageView(getter(), reader(handled = true))))

        decodeToFit(context.findDecodes())

        val reader = context.mutableClassDefBy(IG_IMAGE_VIEW).methods.single { it.name == "A03" }
        assertDecodeHooked("stand-in A03 in a try", reader)
        assertEquals("the spare", 2, (reader.code()[2] as TwoRegisterInstruction).registerA)
    }

    /** An image view the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun anImageViewThePatchCantReadFailsBeforeAnythingChanges() {
        val where = "$IG_IMAGE_VIEW->A03"
        val cases = listOf(
            emptyList<ClassDef>() to "$IG_IMAGE_VIEW isn't there",
            listOf(imageView(reader(), quickReader())) to "to keep one getter of its largest sample size, found 0",
            listOf(imageView(getter(), getter("getMaxSampleSize\$other"), reader())) to "to keep one getter of its largest sample size, found 2",
            listOf(imageView(getter())) to "nothing in $IG_IMAGE_VIEW handed a picture reads its largest sample size",
            listOf(imageView(getter(), quickReader(), reader(reads = 2))) to "expected $where to read the largest sample size once, found 2",
            listOf(imageView(getter(), quickReader(), reader(overView = true))) to "$where reads the largest sample size over the view's own register v13",
            listOf(imageView(getter(), quickReader(), reader(jumpIn = true))) to "something in $where jumps in right after it reads the largest sample size",
            listOf(imageView(getter(), quickReader(), reader(overPicture = true))) to "$where writes over the picture it's handed in v21",
            listOf(imageView(getter(), quickReader(), reader(starved = true))) to
                "$where has 0 local register(s) up to v15 that nothing reads after instruction 2, needs 1",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { decodeToFit(context.findDecodes()) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.flatMap { context.mutableClassDefBy(it.type).methods }
                .filter { method -> method.code().any { it.referenceText() == SAMPLE_SIZE } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build every lookup finds its method and each hook lands once, where it should. */
    @Test
    fun eachDeclaredBuildAsksForTheLargerSize() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildAsksForTheLargerSizeToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val holds = classDef.type == IG_IMAGE_VIEW || classDef.methods.any { method ->
                    method.code().any { it.string() == SCREEN_FORMAT } ||
                        (method.returnType == EXTENDED_IMAGE_URL && method.parameterTypes.map(CharSequence::toString) == pickerParameters)
                }
                if (holds) holders += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(holders)

        val sites = context.findLargerSizes()
        val decodes = context.findDecodes()
        askForLarger(sites)
        decodeToFit(decodes)

        assertPickerHooked(label, sites.picker)
        assertReportHooked(label, sites.report)
        val written = holders.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
        assertEquals("$label: methods asking", 1, written.count { m -> m.code().any { it.referenceText() == WANTED } })
        assertEquals("$label: methods reporting", 1, written.count { m -> m.code().any { it.referenceText() == SCREEN } })
        val decoding = written.filter { m -> m.code().any { it.referenceText() == SAMPLE_SIZE } }
        assertEquals("$label: methods decoding to fit", decodes.size, decoding.size)
        assertTrue("$label: setUrlInternal doesn't decode to fit", decoding.any { m -> m.code().any { it.string() == "IgImageView.setUrlInternal" } })
        for (method in decoding) assertDecodeHooked("$label ${method.name}", method)
    }

    /**
     * The hook comes right after the view reads its largest sample size, with only moves to low
     * registers between, handed the view the size was read from, the picture parameter and the
     * size, and its answer is kept as the size. Nothing jumps in between the read and the answer.
     */
    private fun assertDecodeHooked(what: String, method: MutableMethod) {
        val code = method.code()
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == SAMPLE_SIZE })
        val hook = code.indexOfFirst { it.referenceText() == SAMPLE_SIZE }
        val read = code.subList(0, hook).indexOfLast { it.opcode == Opcode.IGET && (it.reference() as? FieldReference)?.definingClass == IG_IMAGE_VIEW }
        assertTrue("$what: no read of the size before the hook", read >= 0)
        val size = code[read] as TwoRegisterInstruction
        val moves = code.subList(read + 1, hook).map { it as TwoRegisterInstruction }
        assertTrue("$what: more than moves between the read and the hook", moves.all { it.opcode == Opcode.MOVE_OBJECT_FROM16 || it.opcode == Opcode.MOVE_FROM16 })
        fun from(register: Int) = moves.lastOrNull { it.registerA == register }?.registerB ?: register
        val call = code[hook] as FiveRegisterInstruction
        assertEquals("$what: arguments", 3, call.registerCount)
        assertEquals("$what: the view", size.registerB, from(call.registerC))
        assertEquals("$what: the picture", pictureRegister(method), from(call.registerD))
        assertEquals("$what: the size", size.registerA, from(call.registerE))
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[hook + 1].opcode)
        assertEquals("$what: kept as the size", size.registerA, (code[hook + 1] as OneRegisterInstruction).registerA)
        val jumpedOnto = method.jumpTargets().filter { it in read + 1..hook + 1 }
        assertTrue("$what: something jumps in at $jumpedOnto", jumpedOnto.isEmpty())
    }

    private fun pictureRegister(method: Method): Int {
        val widths = method.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
        val at = method.parameterTypes.indexOfFirst { it.toString() == IMAGE_URL }
        return method.implementation!!.registerCount - widths.sum() + widths.take(at).sum()
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

    /** The application's onCreate as the extension patch leaves it: the context handed over [calls] times, then Instagram's own startup. */
    private fun onCreate(calls: Int = 1): Method {
        val body = buildString {
            repeat(calls) { appendLine("invoke-static/range { p0 .. p0 }, $SET_CONTEXT") }
            appendLine("invoke-super { p0 }, Landroid/app/Application;->onCreate()V")
            appendLine("return-void")
        }
        val mutable = MutableMethod(
            ImmutableMethod(
                INSTAGRAM_APPLICATION, "onCreate", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun application(onCreate: Method): ClassDef =
        ImmutableClassDef(INSTAGRAM_APPLICATION, AccessFlags.PUBLIC.value, "Landroid/app/Application;", null, null, null, null, listOf(onCreate))

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

    private val sizeField = "$IG_IMAGE_VIEW->A03:I"

    private fun imageView(vararg methods: Method): ClassDef =
        ImmutableClassDef(IG_IMAGE_VIEW, AccessFlags.PUBLIC.value, "Landroid/widget/ImageView;", null, null, null, null, methods.toList())

    /** The kept getter of the largest sample size, shaped like 454's. */
    private fun getter(name: String = "getMaxSampleSize\$fbandroid_fixture"): Method = instance(
        name, emptyList(), "I", locals = 1,
        """
            iget v0, p0, $sizeField
            return v0
        """,
    )

    /**
     * A picture setter shaped like 454's A03: the view copied to v13, the size read from it into
     * v0, then v2 written. The picture arrives in v21, past what a plain invoke names. [reads],
     * [overView], [jumpIn] and [overPicture] each break one thing the patch needs, [starved]
     * leaves no low register unread after the size, and [handled] puts the read in a try whose
     * handler reads v1.
     */
    private fun reader(
        reads: Int = 1,
        overView: Boolean = false,
        jumpIn: Boolean = false,
        overPicture: Boolean = false,
        starved: Boolean = false,
        handled: Boolean = false,
    ): Method {
        val body = buildString {
            appendLine("move-object/from16 v13, p0")
            if (jumpIn) appendLine("if-eqz v13, :after")
            appendLine(if (overView) "iget v13, v13, $sizeField" else "iget v0, v13, $sizeField")
            if (starved) appendLine("filled-new-array/range { v0 .. v15 }, [I")
            if (jumpIn) appendLine(":after")
            appendLine("const/4 v2, 0x0")
            if (reads == 2) appendLine("iget v3, v13, $sizeField")
            if (overPicture) appendLine("move-object/from16 v21, v13")
            appendLine("invoke-static { v0 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;")
            appendLine("move-result-object v1")
            appendLine("return-void")
            if (handled) {
                appendLine("invoke-static { v1 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;")
                appendLine("return-void")
            }
        }
        return instance("A03", listOf(IMAGE_URL, "Z"), "V", locals = 20, body, tried = if (handled) 1 to 6 else null)
    }

    /** A picture setter shaped like 454's A04, with every register it needs low: the view in v3, the picture in v4. */
    private fun quickReader(): Method = instance(
        "A04", listOf(IMAGE_URL), "V", locals = 3,
        """
            iget v0, p0, $sizeField
            const/4 v1, 0x1
            invoke-static { v0 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
            move-result-object v2
            return-void
        """,
    )

    /** A private instance method of the image view, with the instruction at [tried]'s first in a try handled at its second. */
    private fun instance(name: String, parameters: List<String>, returns: String, locals: Int, body: String, tried: Pair<Int, Int>? = null): Method {
        val flags = AccessFlags.PRIVATE.value or AccessFlags.FINAL.value
        val registers = locals + 1 + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                IG_IMAGE_VIEW, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        val built = ImmutableMethod.of(mutable)
        if (tried == null) return built
        val code = built.code()
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val (covered, handler) = tried
        val block = ImmutableTryBlock(addresses[covered], code[covered].codeUnits, listOf(ImmutableExceptionHandler(null, addresses[handler])))
        return ImmutableMethod(
            IG_IMAGE_VIEW, name, built.parameters, returns, flags, null, null,
            ImmutableMethodImplementation(registers, code, listOf(block), null),
        )
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = (reference() as? MethodReference)?.toString()

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string
}

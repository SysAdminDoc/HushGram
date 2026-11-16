/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.ring

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21ih
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ring thickness (#528): every width the ring view reads goes through the hook, once on each path,
 * and its own two width fields are left as Instagram wrote them.
 */
class StoryRingThicknessHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(RING_THICKNESS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$RING_THICKNESS is not in the extension: $declared", RING_THICKNESS.substringAfter("->") in declared)
    }

    /**
     * The constructor's read after its writes, both of the measure's reads and both of the paint
     * picker's are found. The getter, which hands its read straight back, isn't, and neither are
     * the setters' writes.
     */
    @Test
    fun everyReadButTheGettersIsFound() {
        val reads = PatchContexts.of(listOf(spinner())).findRingWidthReads()
        assertEquals(
            listOf("<init>" to 3, "measure" to 0, "measure" to 1, "paint" to 2, "paint" to 5),
            reads.map { it.name to it.read }.sortedWith(compareBy({ it.first }, { it.second })),
        )
        assertEquals(listOf(1, 0, 1, 0, 0), reads.sortedWith(compareBy({ it.name }, { it.read })).map { it.register })
    }

    /**
     * Each read is followed by the hook, which takes the width and answers into the same register,
     * and the rest of each method is as it was.
     */
    @Test
    fun eachReadGoesThroughTheHook() {
        val context = PatchContexts.of(listOf(spinner()))
        val reads = context.findRingWidthReads()
        val originals = reads.map { it.name to it.parameters }.distinct().associateWith { NeutralNativePath(context.method(it)) }
        context.thickenRings(reads)

        for ((key, original) in originals) {
            val inMethod = reads.filter { it.name to it.parameters == key }.sortedBy { it.read }
            val method = context.method(key)
            assertThickened(key.first, method, inMethod)
            original.assertPreserved(key.first, method, added(inMethod))
        }
    }

    /**
     * The paint picker reads the inactive width and falls into the stroke, or reads the active width
     * and jumps back to the stroke, as 454's `getCurrentPaint` does. Each hook goes ahead of the
     * stroke's label, so the jump still lands on the stroke and each width is scaled once.
     */
    @Test
    fun aJumpOntoTheStrokeSkipsTheOtherWidthsHook() {
        val context = PatchContexts.of(listOf(spinner()))
        context.thickenRings(context.findRingWidthReads())

        val code = context.method("paint" to emptyList()).implementation!!.instructions.toList()
        assertEquals(
            listOf(Opcode.CONST_4, Opcode.IF_EQZ, Opcode.IGET, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC,
                Opcode.RETURN_VOID, Opcode.IGET, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.GOTO),
            code.map { it.opcode },
        )
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        fun lands(at: Int) = addresses.indexOf(addresses[at] + (code[at] as OffsetInstruction).codeOffset)
        assertEquals("the branch to the active width", 7, lands(1))
        assertEquals("the jump back lands on the stroke, past the inactive width's hook", 5, lands(10))
    }

    /** Nothing in the view writes the widths after the patch, so the fields stay Instagram's. */
    @Test
    fun theWidthFieldsAreLeftAlone() {
        val context = PatchContexts.of(listOf(spinner()))
        context.thickenRings(context.findRingWidthReads())
        for (setter in WIDTH_SETTERS) {
            val code = context.method(setter to listOf("F")).implementation!!.instructions.toList()
            assertEquals(setter, listOf(Opcode.IPUT, Opcode.RETURN_VOID), code.map { it.opcode })
        }
        val getter = context.method("getActiveStrokeWidth" to emptyList()).implementation!!.instructions.toList()
        assertEquals("the getter", listOf(Opcode.IGET, Opcode.RETURN), getter.map { it.opcode })
    }

    @Test
    fun aBuildWithoutTheRingViewFailsThePatch() {
        refuses("has no $GRADIENT_SPINNER", emptyList())
    }

    @Test
    fun aMissingSetterFailsThePatch() {
        refuses("expected one $GRADIENT_SPINNER->setInactiveStrokeWidth(F)V, found 0", listOf(spinner(inactiveWrites = null)))
    }

    @Test
    fun aSetterWritingTwoFieldsFailsThePatch() {
        refuses("setActiveStrokeWidth writes 2 fields", listOf(spinner(activeWrites = listOf(ACTIVE, INACTIVE))))
    }

    @Test
    fun bothSettersWritingOneFieldFailThePatch() {
        refuses("width setters write one field, $ACTIVE", listOf(spinner(inactiveWrites = listOf(ACTIVE))))
    }

    @Test
    fun aSetterWritingAnythingButAFloatOfTheViewFailsThePatch() {
        val elsewhere = ImmutableFieldReference("Lfixture/Other;", "active", "F")
        refuses("writes $elsewhere, not a float field", listOf(spinner(activeWrites = listOf(elsewhere))))
        val whole = ImmutableFieldReference(GRADIENT_SPINNER, "active", "I")
        refuses("writes $whole, not a float field", listOf(spinner(activeWrites = listOf(whole))))
    }

    /** A width written back after a read would be scaled again each time the method runs. */
    @Test
    fun aWriteAfterAReadFailsThePatch() {
        refuses("$GRADIENT_SPINNER->rescale writes a ring's width after reading one", listOf(spinner(writesAfterReading = true)))
    }

    @Test
    fun aViewThatNeverReadsItsWidthsFailsThePatch() {
        refuses("no method of $GRADIENT_SPINNER reads its rings' widths", listOf(spinner(reads = false)))
    }

    /** A refusal leaves the view as it was: everything is found before anything changes. */
    @Test
    fun aRefusalLeavesTheViewAlone() {
        val context = PatchContexts.of(listOf(spinner(writesAfterReading = true)))
        assertThrows(PatchException::class.java) { context.findRingWidthReads() }
        val hooked = context.mutableClassDefBy(GRADIENT_SPINNER).methods.filter { method ->
            method.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == RING_THICKNESS } == true
        }
        assertEquals(emptyList<String>(), hooked.map { it.name })
    }

    /**
     * In every build each width the ring view reads, but for the getters', gets the hook, its draw
     * and its measure among them, and each method is otherwise as it was.
     */
    @Test
    fun eachDeclaredBuildThickensTheRings() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                thickens(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildThickensTheRingsToo() {
        for (base in Fixtures.otherBuilds()) thickens(base, base.parentFile.name)
    }

    private fun thickens(bundle: File, label: String) {
        val spinner = FixtureDex.classes(bundle, setOf(GRADIENT_SPINNER)).values
        assertEquals("$label: the ring view", 1, spinner.size)
        val context = PatchContexts.of(spinner)
        val stock = spinner.single()
        val fields = WIDTH_SETTERS.map { setter ->
            stock.methods.single { it.name == setter && it.parameterTypes.map(CharSequence::toString) == listOf("F") }.implementation!!.instructions
                .single { it.opcode == Opcode.IPUT }.let { ((it as ReferenceInstruction).reference as FieldReference).toString() }
        }
        val expected = stock.methods.flatMap { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            code.indices.filter { at ->
                val read = code[at]
                read.opcode == Opcode.IGET && ((read as ReferenceInstruction).reference as FieldReference).toString() in fields &&
                    !(code.getOrNull(at + 1)?.opcode == Opcode.RETURN &&
                        (code[at + 1] as OneRegisterInstruction).registerA == (read as TwoRegisterInstruction).registerA)
            }.map { "${method.name}(${method.parameterTypes.joinToString("")}) at $it" }
        }.sorted()

        val reads = context.findRingWidthReads()
        assertEquals("$label: the ring widths the view reads", expected,
            reads.map { "${it.name}(${it.parameters.joinToString("")}) at ${it.read}" }.sorted())
        val names = reads.map { it.name }.toSet()
        assertTrue("$label: the draw reads no width: $names", "onDraw" in names)
        assertTrue("$label: the measure reads no width: $names", "onMeasure" in names)

        val originals = reads.map { it.name to it.parameters }.distinct().associateWith { NeutralNativePath(context.method(it)) }
        context.thickenRings(reads)
        for ((key, original) in originals) {
            val inMethod = reads.filter { it.name to it.parameters == key }.sortedBy { it.read }
            val method = context.method(key)
            assertThickened("$label ${key.first}", method, inMethod)
            original.assertPreserved("$label ${key.first}", method, added(inMethod))
        }
    }

    private fun refuses(why: String, classes: List<ClassDef>) {
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findRingWidthReads() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(why))
        assertTrue(refusal.message, refusal.message.orEmpty().startsWith("Story ring size: "))
    }

    private fun BytecodePatchContext.method(key: Pair<String, List<String>>): Method =
        mutableClassDefBy(GRADIENT_SPINNER).methods.single {
            it.name == key.first && it.parameterTypes.map(CharSequence::toString) == key.second
        }

    /** Where the hooks sit once [reads], one method's in order, have each been followed by two instructions. */
    private fun added(reads: List<RingWidthRead>): Set<Int> =
        reads.withIndex().flatMap { (shift, read) -> listOf(read.read + 2 * shift + 1, read.read + 2 * shift + 2) }.toSet()

    /**
     * Right after each read, a range call passing the width's register alone and its answer moved
     * back, and nothing jumping onto either; no other call to the hook.
     */
    private fun assertThickened(what: String, method: Method, reads: List<RingWidthRead>) {
        val code = method.implementation!!.instructions.toList()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == RING_THICKNESS }
        assertEquals("$what: hooks", reads.withIndex().map { (shift, read) -> read.read + 2 * shift + 1 }, hooks)
        val targets = method.jumpTargets()
        for ((shift, read) in reads.withIndex()) {
            val at = read.read + 2 * shift
            assertEquals("$what: the read at ${read.read}", Opcode.IGET, code[at].opcode)
            assertEquals("$what: the hook's call", Opcode.INVOKE_STATIC_RANGE, code[at + 1].opcode)
            val call = code[at + 1] as RegisterRangeInstruction
            assertEquals("$what: what the hook is passed", read.register to 1, call.startRegister to call.registerCount)
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at + 2].opcode)
            assertEquals("$what: back into the width's register", read.register, (code[at + 2] as OneRegisterInstruction).registerA)
            assertTrue("$what: something jumps onto the hook after ${read.read}", at + 1 !in targets && at + 2 !in targets)
        }
    }

    private companion object {
        val ACTIVE = ImmutableFieldReference(GRADIENT_SPINNER, "active", "F")
        val INACTIVE = ImmutableFieldReference(GRADIENT_SPINNER, "inactive", "F")
        val STROKE = ImmutableMethodReference("Lfixture/Paint;", "stroke", listOf("F"), "V")
        val LARGER = ImmutableMethodReference("Ljava/lang/Math;", "max", listOf("F", "F"), "F")

        /**
         * The ring view's width code, shaped like 454's:
         *
         * - the two setters, writing [activeWrites] and [inactiveWrites] (no setter for null);
         * - `getActiveStrokeWidth()F`, reading the active width and returning it;
         * - `<init>(Context)`, writing both widths, then reading the active one for a stroke;
         * - `measure(II)V`, reading both into v0 and v1 and taking the larger;
         * - `paint()V`, the paint picker:
         *
         *       a0  const/4 v1, 0
         *       a1  if-eqz v1, a9
         *       a3  iget v0, v2, inactive
         *       a5  invoke-static {v0}, stroke(F)V
         *       a8  return-void
         *       a9  iget v0, v2, active
         *       a11 goto a5
         *
         * Without [reads] it has only the setters and the getter. With [writesAfterReading] it gets
         * `rescale()V`, which reads the active width and writes it to the inactive one.
         */
        fun spinner(
            activeWrites: List<FieldReference>? = listOf(ACTIVE),
            inactiveWrites: List<FieldReference>? = listOf(INACTIVE),
            reads: Boolean = true,
            writesAfterReading: Boolean = false,
        ): ClassDef {
            fun method(name: String, parameters: List<String>, returns: String, registers: Int, code: List<Instruction>, flags: Int = AccessFlags.PUBLIC.value) =
                ImmutableMethod(
                    GRADIENT_SPINNER, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                    ImmutableMethodImplementation(registers, code, null, null),
                )
            val methods = mutableListOf<ImmutableMethod>()
            for ((setter, writes) in WIDTH_SETTERS.zip(listOf(activeWrites, inactiveWrites))) {
                if (writes == null) continue
                methods += method(setter, listOf("F"), "V", 2, writes.map { ImmutableInstruction22c(Opcode.IPUT, 1, 0, it) } + ImmutableInstruction10x(Opcode.RETURN_VOID))
            }
            methods += method("getActiveStrokeWidth", emptyList(), "F", 2, listOf(
                ImmutableInstruction22c(Opcode.IGET, 0, 1, ACTIVE),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ))
            if (reads) {
                methods += method("<init>", listOf("Landroid/content/Context;"), "V", 4, listOf(
                    ImmutableInstruction21ih(Opcode.CONST_HIGH16, 0, 2f.toRawBits()),
                    ImmutableInstruction22c(Opcode.IPUT, 0, 2, ACTIVE),
                    ImmutableInstruction22c(Opcode.IPUT, 0, 2, INACTIVE),
                    ImmutableInstruction22c(Opcode.IGET, 1, 2, ACTIVE),
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, STROKE),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ), AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value)
                methods += method("measure", listOf("I", "I"), "V", 5, listOf(
                    ImmutableInstruction22c(Opcode.IGET, 0, 2, ACTIVE),
                    ImmutableInstruction22c(Opcode.IGET, 1, 2, INACTIVE),
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0, LARGER),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ))
                methods += method("paint", emptyList(), "V", 3, listOf(
                    ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                    ImmutableInstruction21t(Opcode.IF_EQZ, 1, 8),
                    ImmutableInstruction22c(Opcode.IGET, 0, 2, INACTIVE),
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, STROKE),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ImmutableInstruction22c(Opcode.IGET, 0, 2, ACTIVE),
                    ImmutableInstruction10t(Opcode.GOTO, -6),
                ))
            }
            if (writesAfterReading) {
                methods += method("rescale", emptyList(), "V", 2, listOf(
                    ImmutableInstruction22c(Opcode.IGET, 0, 1, ACTIVE),
                    ImmutableInstruction22c(Opcode.IPUT, 0, 1, INACTIVE),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ))
            }
            return ImmutableClassDef(
                GRADIENT_SPINNER, AccessFlags.PUBLIC.value, "Landroid/view/View;",
                null, null, null, null, methods,
            )
        }
    }
}

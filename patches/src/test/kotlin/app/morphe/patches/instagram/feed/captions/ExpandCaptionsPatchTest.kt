/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.captions

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expand captions (#771) switches its status method on and asks the extension for the line count
 * right before Instagram cuts a caption, handing it the surface the post shows on, so the feeds get
 * the count picked in settings and Reels and stories keep Instagram's.
 */
class ExpandCaptionsPatchTest {
    /** The hook the patch writes is in the CaptionLength the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(CAPTION_LENGTH).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$CAPTION_MAX_LINES is not in the extension: $declared", CAPTION_MAX_LINES.substringAfter("->") in declared)
    }

    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + classes())
        expandCaptionsPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, EXPAND_CAPTIONS_STATUS).code()
        assertEquals(1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val other = context.method(SETTINGS_STATUS, "hideCaptions").code()
        assertEquals("another patch's status is still off", 0, (other.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /** The builder is the static Context-and-props caller; the measurer returning text is passed over. */
    @Test
    fun theCutIsFound() {
        val found = PatchContexts.of(classes()).findCaptionTrim()

        assertEquals(BUILDER, found.type)
        assertEquals("A03", found.name)
        assertEquals(listOf(CONTEXT, PROPS, STRING), found.parameters)
        assertEquals(SPANNABLE, found.returnType)
        assertEquals("the trim call", 9, found.call)
        assertEquals("the line count's register in the range", 28, found.lines)
        assertEquals("$PROPS->surface:$SURFACE", found.surface)
    }

    /** The hook reads the surface out of the props and writes the extension's answer over the count. */
    @Test
    fun theHookAsksBeforeTheCut() {
        val context = PatchContexts.of(classes())
        val found = context.findCaptionTrim()
        val stock = context.builder(found).code()
        val props = context.builder(found).parameterRegisterNumber(1)

        found.write(context)

        assertEquals("parameter 1 of a static method with 30 locals", 31, props)
        assertHooked("stand-in", context.builder(found).code(), stock, found, props)
        val measurer = context.mutableClassDefBy(MEASURER).methods.single().code()
        assertTrue("the measurer is left alone", measurer.none { it.method()?.toString() == CAPTION_MAX_LINES })
    }

    /**
     * #821: the Reels tab's own caption renders settle their line count where the open caption's
     * Integer.MAX_VALUE meets the cut ones, after a goto in one look and by falling through in the other.
     */
    @Test
    fun theReelCaptionCountsAreFound() {
        val found = PatchContexts.of(classes()).findReelCaptionLines().associateBy { it.type }

        assertEquals(setOf(REEL_GOTO, REEL_FALLTHROUGH), found.keys)
        assertEquals("the goto look meets past its own count", 8, found.getValue(REEL_GOTO).merge)
        assertEquals("the goto look counts in v3", 3, found.getValue(REEL_GOTO).lines)
        assertEquals("the falling look meets after the const", 10, found.getValue(REEL_FALLTHROUGH).merge)
        assertEquals("the falling look counts in v5", 5, found.getValue(REEL_FALLTHROUGH).lines)
    }

    /** The hook is where every path to the count meets, and the branches that went there now run it first. */
    @Test
    fun theReelCaptionCountAsksWhereThePathsMeet() {
        val context = PatchContexts.of(classes())
        for (found in context.findReelCaptionLines()) {
            val stock = context.render(found.type).code()
            found.write(context)
            assertReelHooked(found.type, context.render(found.type).code(), stock, found.merge, found.lines)
        }
    }

    /**
     * Hide captions writes its question at the top of the same render first in a real patch run, which moves
     * everything down; the count's hook still lands where the paths meet.
     */
    @Test
    fun theReelCaptionCountFollowsCodeWrittenAboveIt() {
        val context = PatchContexts.of(classes())
        val found = context.findReelCaptionLines().single { it.type == REEL_GOTO }
        val method = context.mutableClassDefBy(REEL_GOTO).methods.single { it.name == "A0p" }
        method.addInstructionsWithLabels(0, "const/4 v0, 0x0\nconst/4 v1, 0x0\nconst/4 v2, 0x0")
        val stock = method.code()

        found.write(context)

        assertReelHooked("moved down", context.render(REEL_GOTO).code(), stock, found.merge + 3, found.lines)
    }

    @Test
    fun theWholePatchAsksInBothReelLooks() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + classes())
        val stock = listOf(REEL_GOTO, REEL_FALLTHROUGH).associateWith { type ->
            context.mutableClassDefBy(type).methods.associate { it.name to it.code() }
        }
        val counts = context.findReelCaptionLines().associateBy { it.type }
        expandCaptionsPatch.execute(context)
        for (type in listOf(REEL_GOTO, REEL_FALLTHROUGH)) {
            val asks = context.render(type).code().count { it.method()?.toString() == CAPTION_REEL_LINES }
            assertEquals("$type asks once", 1, asks)
            assertReelHooked(type, context.render(type).code(), stock.getValue(type).getValue("A0p"),
                counts.getValue(type).merge, counts.getValue(type).lines)
            // #821: and wherever its class reads the open flag, after the count's hook is in.
            val reads = context.mutableClassDefBy(type).methods.sumOf { method ->
                assertOpenHooked("$type->${method.name}", method.code(), stock.getValue(type).getValue(method.name), "$type->open:Z")
            }
            assertEquals("$type: the render's two reads and the helper's", 3, reads)
        }
    }

    /** #821: each reel caption render's class, once, with the flag it hands to String.valueOf before the key. */
    @Test
    fun theReelCaptionFlagsAreFound() {
        val found = PatchContexts.of(classes()).findReelCaptionOpens().associate { it.type to it.flag }
        assertEquals(mapOf(REEL_GOTO to "$REEL_GOTO->open:Z", REEL_FALLTHROUGH to "$REEL_FALLTHROUGH->open:Z"), found)
    }

    /**
     * #821: every read of the open flag in the render's class, its helpers' included, asks whether to draw the
     * caption open, the answer back in the read's register. A read right before a label branches land on keeps
     * the branches on Instagram's instruction, past the question.
     */
    @Test
    fun everyReadOfTheOpenFlagAsks() {
        for (shapes in listOf(listOf("goto", "fallthrough"), listOf("landing"))) {
            val context = PatchContexts.of(classes(reels = shapes))
            for (found in context.findReelCaptionOpens()) {
                val stock = context.mutableClassDefBy(found.type).methods.associate { it.name to it.code() }
                val hooked = found.write(context)
                val reads = context.mutableClassDefBy(found.type).methods.sumOf { method ->
                    assertOpenHooked("${found.type}->${method.name}", method.code(), stock.getValue(method.name), found.flag)
                }
                assertEquals("${found.type}: every read asks", reads, hooked)
                assertTrue("${found.type}: the helper asks too",
                    context.mutableClassDefBy(found.type).methods.single { it.name == "A0A" }.code()
                        .any { it.method()?.toString() == CAPTION_REEL_OPEN })
            }
        }
        val landing = PatchContexts.of(classes(reels = listOf("landing")))
        val code = landing.render(REEL_GOTO).code()
        val read = code.indices.last { code[it].opcode == Opcode.IGET_BOOLEAN && (code[it] as TwoRegisterInstruction).registerA == 6 }
        assertTrue("the fixture's last read sits right before the merge", read + 1 in branchTargets(code))
    }

    @Test
    fun theOpenHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(CAPTION_LENGTH).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$CAPTION_REEL_OPEN is not in the extension: $declared", CAPTION_REEL_OPEN.substringAfter("->") in declared)
    }

    @Test
    fun theReelHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(CAPTION_LENGTH).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$CAPTION_REEL_LINES is not in the extension: $declared", CAPTION_REEL_LINES.substringAfter("->") in declared)
    }

    @Test
    fun noReelCaptionFailsThePatch() = refusedLines("found 0", classes(reels = emptyList()))

    @Test
    fun aReelCaptionThatNeverOpensFailsThePatch() = refusedLines("found 0", classes(reels = listOf("never")))

    @Test
    fun aReelCaptionOpeningTwiceFailsThePatch() = refusedLines("found 2", classes(reels = listOf("twice")))

    @Test
    fun aReelCaptionWithoutItsFlagFailsThePatch() = refusedLines("doesn't hand a boolean field", classes(reels = listOf("unkeyed")))

    @Test
    fun noCaptionViewFailsThePatch() = refused("isn't in the app", classes(view = false))

    @Test
    fun aViewWithTwoTrimHelpersFailsThePatch() = refused("found 2", classes(secondHelper = true))

    @Test
    fun noBuilderFailsThePatch() = refused("found 0", classes(builders = 0))

    @Test
    fun twoBuildersFailThePatch() = refused("found 2", classes(builders = 2))

    @Test
    fun aBuilderCuttingTwiceFailsThePatch() = refused("2 times, not once", classes(variant = "twice"))

    @Test
    fun aJumpToTheCutFailsThePatch() = refused("jumps straight to the trim call", classes(variant = "jump"))

    @Test
    fun aBuilderReusingThePropsRegisterFailsThePatch() = refused("writes over parameter 1", classes(variant = "spoiled"))

    @Test
    fun propsWithoutASurfaceFailThePatch() = refused("found 0", classes(surfaces = 0))

    @Test
    fun propsWithTwoSurfacesFailThePatch() = refused("found 2", classes(surfaces = 2))

    /** On each declared build the patch finds the one caption builder and asks before its cut. */
    @Test
    fun eachDeclaredBuildAsksBeforeTheCut() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                asksBeforeTheCut(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildAsksBeforeTheCutToo() {
        var checked = 0
        for (base in Fixtures.otherBuilds()) {
            asksBeforeTheCut(base, base.parentFile.name)
            checked++
        }
        assertEquals("the builds besides the declared one", Fixtures.OTHER_BUILDS, checked)
    }

    /**
     * Slices the fixture the way the finder reads it: the caption view, every class calling the
     * helper its onMeasure cuts with, the props those callers take and the enums the props hold,
     * with their string pools. The whole patch then runs, and only the one builder is hooked.
     */
    private fun asksBeforeTheCut(bundle: File, label: String) {
        val view = FixtureDex.classes(bundle, setOf(EXPANDING_TEXT_VIEW)).values.singleOrNull()
            ?: throw AssertionError("$label: no $EXPANDING_TEXT_VIEW")
        val measure = view.methods.single { it.name == "onMeasure" && it.parameterTypes.map(CharSequence::toString) == listOf("I", "I") }
        val helper = measure.code().mapNotNull { it.method() }.filter { it.isTrim() }
            .map { it.toString() }.distinct().singleOrNull()
            ?: throw AssertionError("$label: onMeasure calls no single trim helper")
        val callers = LinkedHashMap<String, ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.methodSection.none { it.toString() == helper }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.type in callers) continue
                if (classDef.methods.any { method -> method.code().any { it.method()?.toString() == helper } }) {
                    callers[classDef.type] = ImmutableClassDef.of(classDef)
                }
            }
        }
        assertTrue("$label: more than the view and the builder cut with it: ${callers.keys}", callers.size >= 3)
        val props = callers.values.flatMap { it.methods }
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == SPANNABLE && it.parameterTypes.size >= 2 }
            .mapTo(HashSet()) { it.parameterTypes[1].toString() }
        val propsClasses = FixtureDex.classes(bundle, props).values
        val fieldTypes = propsClasses.flatMap { it.fields }.map { it.type }.filter { it.startsWith("L") }.toSet()
        val enums = FixtureDex.classes(bundle, fieldTypes).values.filter { it.superclass == ENUM }
        val reelHolders = FixtureDex.classesLoading(bundle, REEL_CAPTION_EXPANDED)
        val sliced = (callers.values + propsClasses + enums + reelHolders).distinctBy { it.type }
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + FixtureDex.withStringPools(bundle, sliced))
        val found = context.findCaptionTrim()
        val stock = context.builder(found).code()
        val propsRegister = context.builder(found).parameterRegisterNumber(1)
        // #821: both looks of the Reels tab's own caption, on every build.
        val reels = context.findReelCaptionLines()
        assertEquals("$label: the reel caption's two looks", 2, reels.size)
        val reelStock = reels.associate { it.type to context.render(it.type, it.name).code() }
        val opens = context.findReelCaptionOpens()
        assertEquals("$label: the two looks' classes", reels.map { it.type }.toSet(), opens.map { it.type }.toSet())
        val openStock = opens.associate { open -> open.type to context.mutableClassDefBy(open.type).methods.associate { it.key() to it.code() } }

        expandCaptionsPatch.execute(context)

        for (reel in reels) {
            assertReelHooked(
                "$label ${reel.type}", context.render(reel.type, reel.name).code(), reelStock.getValue(reel.type), reel.merge, reel.lines,
            )
        }
        // #821: every read of each look's open flag, in the render and in its class's helpers, asks.
        for (open in opens) {
            val methods = context.mutableClassDefBy(open.type).methods
            val reads = methods.sumOf { method ->
                assertOpenHooked("$label ${open.type}->${method.name}", method.code(), openStock.getValue(open.type).getValue(method.key()), open.flag)
            }
            assertTrue("$label ${open.type}: its render reads the flag more than once", reads > 1)
            assertTrue("$label ${open.type}: a helper reads it too",
                methods.count { method -> method.code().any { it.method()?.toString() == CAPTION_REEL_OPEN } } > 1)
        }

        val status = context.method(SETTINGS_STATUS, EXPAND_CAPTIONS_STATUS).code()
        assertEquals("$label: status", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        assertHooked("$label ${found.type}->${found.name}", context.builder(found).code(), stock, found, propsRegister)
        val hooked = sliced.flatMap { holder ->
            context.mutableClassDefBy(holder.type).methods.filter { method ->
                method.code().any { it.method()?.toString() == CAPTION_MAX_LINES }
            }.map { "${it.definingClass}->${it.name}" }
        }
        assertEquals("$label: only the caption builder asks", listOf("${found.type}->${found.name}"), hooked)
    }

    /**
     * Six instructions in front of the cut: the props into a borrowed local, its surface field, the
     * count beside it, the call, its answer, and the answer back in the count's register. Instagram's
     * own code is all still there, and the cut comes right after.
     */
    private fun assertHooked(what: String, code: List<Instruction>, stock: List<Instruction>, found: CaptionTrim, props: Int) {
        val calls = code.indices.filter { code[it].method()?.toString() == CAPTION_MAX_LINES }
        assertEquals("$what: one hook", 1, calls.size)
        val start = calls.single() - 3
        val load = code[start] as TwoRegisterInstruction
        val holder = load.registerA
        assertEquals("$what: the props", Opcode.MOVE_OBJECT_FROM16, code[start].opcode)
        assertEquals("$what: from parameter 1", props, load.registerB)
        val surface = code[start + 1]
        assertEquals("$what: the surface", Opcode.IGET_OBJECT, surface.opcode)
        assertEquals("$what: the surface field", found.surface, (surface as ReferenceInstruction).reference.toString())
        assertEquals("$what: read from the props", holder, (surface as TwoRegisterInstruction).registerB)
        assertEquals("$what: into the same local", holder, surface.registerA)
        val copy = code[start + 2] as TwoRegisterInstruction
        assertEquals("$what: the count", Opcode.MOVE_FROM16, code[start + 2].opcode)
        val count = copy.registerA
        assertEquals("$what: from the range", found.lines, copy.registerB)
        val call = code[start + 3] as FiveRegisterInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC, code[start + 3].opcode)
        assertEquals("$what: two arguments", 2, call.registerCount)
        assertEquals("$what: the surface and the count", listOf(holder, count), listOf(call.registerC, call.registerD))
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[start + 4].opcode)
        assertEquals("$what: in the count's local", count, (code[start + 4] as OneRegisterInstruction).registerA)
        val back = code[start + 5] as TwoRegisterInstruction
        assertEquals("$what: written back", Opcode.MOVE_FROM16, code[start + 5].opcode)
        assertEquals("$what: over the range's count", found.lines, back.registerA)
        assertEquals("$what: from the answer", count, back.registerB)
        assertEquals("$what: the cut follows", Opcode.INVOKE_STATIC_RANGE, code[start + 6].opcode)
        assertTrue("$what: the cut follows", code[start + 6].method()?.isTrim() == true)
        assertTrue("$what: low locals, as the invoke and the iget need", holder <= 15 && count <= 15 && holder != count)
        assertStockKept(what, stock, code.filterIndexed { index, _ -> index !in start..start + 5 })
    }

    private fun assertStockKept(what: String, stock: List<Instruction>, kept: List<Instruction>) {
        fun kinds(code: List<Instruction>) = code.map { it.opcode.kind() }.filter { it != Opcode.NOP }
        assertEquals("$what: Instagram's own code is kept", kinds(stock), kinds(kept))
    }

    private fun Opcode.kind() = if (this == Opcode.GOTO_16 || this == Opcode.GOTO_32) Opcode.GOTO else this

    /**
     * Two instructions at [merge]: the count into the extension as a one-register range, and its answer back
     * in the same register. Whatever branched onto the merge now lands on the hook, nothing lands past it, and
     * Instagram's own code is all still there.
     */
    private fun assertReelHooked(what: String, code: List<Instruction>, stock: List<Instruction>, merge: Int, lines: Int) {
        val calls = code.indices.filter { code[it].method()?.toString() == CAPTION_REEL_LINES }
        assertEquals("$what: one hook", 1, calls.size)
        val at = calls.single()
        // The open flag's questions (#821), two instructions after each of its reads, move the merge down by theirs above it.
        val opensAbove = (0 until at).count { code[it].method()?.toString() == CAPTION_REEL_OPEN }
        assertEquals("$what: at the merge", merge + 2 * opensAbove, at)
        val call = code[at] as RegisterRangeInstruction
        assertEquals("$what: a range call", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
        assertEquals("$what: the count alone", listOf(lines, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("$what: back in the count", lines, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: Instagram's merge follows", stock[merge].opcode, code[at + 2].opcode)
        val targets = branchTargets(code)
        assertTrue("$what: a branch lands on the hook", at in targets)
        assertTrue("$what: no branch skips the hook", at + 2 !in targets)
        assertStockKept(what, stock, code.filterIndexed { index, _ -> index != at && index != at + 1 && !isOpenHook(code, index) })
    }

    /** Whether [index] is one of the two instructions of an open flag's question: the call or its answer. */
    private fun isOpenHook(code: List<Instruction>, index: Int): Boolean =
        code[index].method()?.toString() == CAPTION_REEL_OPEN ||
            index > 0 && code[index - 1].method()?.toString() == CAPTION_REEL_OPEN

    /**
     * Each read of [flag] in [code] goes straight on to the question, a one-register range over the read's
     * register, and its answer back in that register; no branch lands on either, and Instagram's own code is
     * all still there. Returns how many reads it saw.
     */
    private fun assertOpenHooked(what: String, code: List<Instruction>, stock: List<Instruction>, flag: String): Int {
        val reads = code.indices.filter {
            code[it].opcode == Opcode.IGET_BOOLEAN && (code[it] as ReferenceInstruction).reference.toString() == flag
        }
        val targets = branchTargets(code)
        for (read in reads) {
            val register = (code[read] as OneRegisterInstruction).registerA
            val call = code[read + 1]
            assertEquals("$what: the question right after the read at $read", CAPTION_REEL_OPEN, call.method()?.toString())
            assertEquals("$what: a range call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("$what: the read's register alone", listOf(register, 1),
                listOf((call as RegisterRangeInstruction).startRegister, call.registerCount))
            assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[read + 2].opcode)
            assertEquals("$what: back in the read's register", register, (code[read + 2] as OneRegisterInstruction).registerA)
            assertTrue("$what: no branch lands on the question", read + 1 !in targets && read + 2 !in targets)
        }
        assertEquals("$what: a question for each read and no other",
            reads.size, code.count { it.method()?.toString() == CAPTION_REEL_OPEN })
        // The line count's question, when the whole patch ran, is held to its place by assertReelHooked.
        assertStockKept(what, stock, code.filterIndexed { index, _ ->
            !isOpenHook(code, index) && code[index].method()?.toString() != CAPTION_REEL_LINES &&
                (index == 0 || code[index - 1].method()?.toString() != CAPTION_REEL_LINES)
        })
        return reads.size
    }

    private fun branchTargets(code: List<Instruction>): Set<Int> {
        val addresses = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> addresses[index + 1] = addresses[index] + instruction.codeUnits }
        val byAddress = code.indices.associateBy { addresses[it] }
        return code.indices.mapNotNullTo(HashSet()) { at ->
            (code[at] as? OffsetInstruction)?.let { byAddress[addresses[at] + it.codeOffset] }
        }
    }

    private fun refusedLines(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findReelCaptionLines() }
        assertTrue(failure.message!!, failure.message!!.startsWith("Expand captions: the reel caption's lines: ") && failure.message!!.contains(why))
    }

    /** A render by its type and name: the fixtures' renders are A0p, a real build's are whatever its Redex named them. */
    private fun BytecodePatchContext.render(type: String, name: String = "A0p"): Method =
        mutableClassDefBy(type).methods.single { it.name == name }

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findCaptionTrim() }
        assertTrue(failure.message!!, failure.message!!.startsWith("Expand captions: ") && failure.message!!.contains(why))
    }

    private fun BytecodePatchContext.builder(found: CaptionTrim): Method =
        mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    /** A method within its class, overloads told apart. */
    private fun Method.key(): String = "$name(${parameterTypes.joinToString("")})$returnType"

    private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun MethodReference.isTrim(): Boolean = returnType == CHAR_SEQUENCE && parameterTypes.size == 6

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val ENUM = "Ljava/lang/Enum;"
        const val CONTEXT = "Landroid/content/Context;"
        const val STRING = "Ljava/lang/String;"
        const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
        const val SPANNABLE = "Landroid/text/SpannableStringBuilder;"
        const val LAYOUT = "Lfixture/Layout;"
        const val PROPS = "Lfixture/CaptionProps;"
        const val SURFACE = "Lfixture/Surface;"
        const val KIND = "Lfixture/Kind;"
        const val BUILDER = "Lfixture/CaptionBuilder;"
        const val MEASURER = "Lfixture/Measurer;"
        const val TRIM_ARGUMENTS = "($LAYOUT$CHAR_SEQUENCE$CHAR_SEQUENCE${CHAR_SEQUENCE}IZ)$CHAR_SEQUENCE"

        /** Shaped like 456's LX/01Gc->A00, the helper ExpandingTextView's onMeasure cuts with. */
        const val HELPER = "Lfixture/Trim;->A00$TRIM_ARGUMENTS"
        const val OTHER_HELPER = "Lfixture/OtherTrim;->A00$TRIM_ARGUMENTS"

        const val PUBLIC = 0x1
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val PUBLIC_STATIC = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value

        fun method(owner: String, name: String, parameters: List<String>, returnType: String, flags: Int, registers: Int, smali: String): Method {
            val method = MutableMethod(
                ImmutableMethod(
                    owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null),
                ),
            )
            method.addInstructionsWithLabels(0, smali)
            return ImmutableMethod.of(method)
        }

        /** The caption view: onMeasure loads six arguments and cuts with the helper, twice over with [second]. */
        fun view(second: Boolean): ClassDef {
            val other = if (second) "invoke-static/range {v0 .. v5}, $OTHER_HELPER" else ""
            val measure = method(EXPANDING_TEXT_VIEW, "onMeasure", listOf("I", "I"), "V", PUBLIC, 9, """
                const/4 v0, 0x0
                const/4 v1, 0x0
                const/4 v2, 0x0
                const/4 v3, 0x0
                const/4 v4, 0x1
                const/4 v5, 0x0
                invoke-static/range {v0 .. v5}, $HELPER
                $other
                return-void
            """.trimIndent())
            return ImmutableClassDef(EXPANDING_TEXT_VIEW, PUBLIC, "Landroid/widget/TextView;", null, null, null, null, listOf(measure))
        }

        /**
         * Shaped like 456's LX/02pL->A03: static (Context, props, String), 30 locals, the props read
         * once at the start, and the cut a range call over v24..v29 with the count in v28. "twice"
         * cuts again after, "jump" branches straight onto the cut, "spoiled" reuses the props'
         * register first.
         */
        fun builder(owner: String, variant: String): Method {
            val jump = if (variant == "jump") "if-eqz v6, :cut" else ""
            val spoil = if (variant == "spoiled") "move-object/from16 v31, v0" else ""
            val again = if (variant == "twice") "invoke-static/range {v24 .. v29}, $HELPER\nmove-result-object v7" else ""
            return method(owner, "A03", listOf(CONTEXT, PROPS, STRING), SPANNABLE, PUBLIC_STATIC, 33, """
                move-object/from16 v6, v31
                const/4 v7, 0x2
                const/4 v0, 0x0
                $spoil
                $jump
                move-object/from16 v24, v0
                move-object/from16 v25, v32
                move-object/from16 v26, v32
                move-object/from16 v27, v32
                move/from16 v28, v7
                const/16 v29, 0x0
                :cut
                invoke-static/range {v24 .. v29}, $HELPER
                move-result-object v7
                $again
                new-instance v0, $SPANNABLE
                invoke-direct {v0, v7}, $SPANNABLE-><init>($CHAR_SEQUENCE)V
                return-object v0
            """.trimIndent())
        }

        /** An enum whose static initializer loads [names], the way Instagram's surface enum keeps its names. */
        fun enum(type: String, names: List<String>): ClassDef {
            val loads = names.joinToString("\n") { "const-string v0, \"$it\"" }
            val initializer = method(type, "<clinit>", emptyList(), "V", AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 1,
                "$loads\nreturn-void")
            return ImmutableClassDef(type, PUBLIC_FINAL or AccessFlags.ENUM.value, ENUM, null, null, null, null, listOf(initializer))
        }

        const val REEL_GOTO = "Lfixture/ReelCaption;"
        const val REEL_FALLTHROUGH = "Lfixture/OtherReelCaption;"
        const val SCOPE = "Lfixture/Scope;"
        const val COMPONENT = "Lfixture/Component;"

        /**
         * A Reels caption render like 456's LX/0aex->A0p ("goto": the open count's const goes on to a goto
         * back to the merge) or LX/0Ee1->A0p ("fallthrough": the const falls into it), as an instance
         * (scope) -> component method with this in v8. Its open flag goes to String.valueOf before the key.
         * "never" opens at 5 lines instead of MAX_VALUE, "twice" settles a count twice, "unkeyed" doesn't
         * hand the flag to String.valueOf, "landing" reads the flag again right before the merge, where
         * branches land. Its static A0A reads the flag too, as 456's render helpers do.
         */
        fun reelRender(owner: String, shape: String): ClassDef {
            val flag = "$owner->open:Z"
            val whole = if (shape == "never") "0x5" else "0x7fffffff"
            val keyed = if (shape == "unkeyed") "const/4 v1, 0x0" else
                "iget-boolean v0, v8, $flag\ninvoke-static {v0}, Ljava/lang/String;->valueOf(Z)Ljava/lang/String;\nmove-result-object v1"
            val body = if (shape == "fallthrough") """
                $keyed
                const-string v0, "$REEL_CAPTION_EXPANDED"
                iget v5, v8, $owner->lines:I
                iget-boolean v7, v8, $flag
                if-nez v7, :whole
                const/4 v7, 0x1
                if-ge v5, v7, :merge
                :whole
                const v5, $whole
                :merge
                iget-boolean v7, v8, $owner->other:Z
                invoke-static {v5}, Lfixture/Text;->lines(I)V
                const/4 v0, 0x0
                return-object v0
            """ else {
                val again = if (shape == "twice") """
                    iget-boolean v3, v8, $flag
                    if-nez v3, :whole2
                    const/4 v3, 0x2
                    :merge2
                    invoke-static {v3}, Lfixture/Text;->lines(I)V
                    const/4 v0, 0x0
                    return-object v0
                    :whole2
                    const v3, 0x7fffffff
                    goto :merge2
                """ else ""
                val landing = if (shape == "landing") "iget-boolean v6, v8, $flag" else ""
                """
                $keyed
                const-string v0, "$REEL_CAPTION_EXPANDED"
                iget-boolean v3, v8, $flag
                if-nez v3, :whole
                if-eqz v2, :props
                const/4 v3, 0x1
                $landing
                :merge
                const/16 v7, 0xe
                invoke-static {v3}, Lfixture/Text;->lines(I)V
                $again
                const/4 v0, 0x0
                return-object v0
                :props
                iget v3, v8, $owner->lines:I
                const/4 v4, 0x1
                if-ge v3, v4, :merge
                :whole
                const v3, $whole
                goto :merge
                """
            }
            val render = method(owner, "A0p", listOf(SCOPE), COMPONENT, PUBLIC_FINAL, 10, body.trimIndent())
            // Like 456's LX/0Egs->A0A: a static helper of the render's class drawing the date under an open caption.
            val date = method(owner, "A0A", listOf(SCOPE, owner), COMPONENT, PUBLIC_STATIC, 3, """
                iget-boolean v0, v2, $flag
                if-eqz v0, :closed
                const/4 v0, 0x0
                return-object v0
                :closed
                const/4 v0, 0x0
                return-object v0
            """.trimIndent())
            val fields = listOf(
                ImmutableField(owner, "open", "Z", PUBLIC_FINAL, null, null, null),
                ImmutableField(owner, "other", "Z", PUBLIC_FINAL, null, null, null),
                ImmutableField(owner, "lines", "I", PUBLIC_FINAL, null, null, null),
            )
            return ImmutableClassDef(owner, PUBLIC_FINAL, "Lfixture/Render;", null, null, null, fields, listOf(render, date))
        }

        fun classes(
            view: Boolean = true,
            secondHelper: Boolean = false,
            builders: Int = 1,
            variant: String = "ok",
            surfaces: Int = 1,
            reels: List<String> = listOf("goto", "fallthrough"),
        ): List<ClassDef> {
            val reelClasses = reels.mapIndexed { index, shape ->
                reelRender(if (shape == "fallthrough" || index > 0) REEL_FALLTHROUGH else REEL_GOTO, shape)
            }
            val surfaceFields = listOf("surface", "previous").take(surfaces).map { ImmutableField(PROPS, it, SURFACE, PUBLIC_FINAL, null, null, null) }
            val fields = surfaceFields + listOf(
                ImmutableField(PROPS, "kind", KIND, PUBLIC_FINAL, null, null, null),
                ImmutableField(PROPS, "fallback", SURFACE, PUBLIC_STATIC, null, null, null),
                ImmutableField(PROPS, "lines", "Ljava/lang/Integer;", PUBLIC_FINAL, null, null, null),
            )
            val props = ImmutableClassDef(PROPS, PUBLIC_FINAL, OBJECT, null, null, null, fields, emptyList())
            val builderClasses = listOf(BUILDER, "Lfixture/OtherBuilder;").take(builders).map { type ->
                ImmutableClassDef(type, PUBLIC_FINAL, OBJECT, null, null, null, null, listOf(builder(type, variant)))
            }
            // Calls the helper too, static with a Context first, but hands back plain text, like 456's LX/0AIr->A00.
            val measurer = method(MEASURER, "A00", listOf(CONTEXT, PROPS), CHAR_SEQUENCE, PUBLIC_STATIC, 8, """
                const/4 v0, 0x0
                const/4 v1, 0x0
                const/4 v2, 0x0
                const/4 v3, 0x0
                const/4 v4, 0x1
                const/4 v5, 0x0
                invoke-static/range {v0 .. v5}, $HELPER
                move-result-object v0
                return-object v0
            """.trimIndent())
            return (if (view) listOf(view(secondHelper)) else emptyList()) + builderClasses + props +
                ImmutableClassDef(MEASURER, PUBLIC_FINAL, OBJECT, null, null, null, null, listOf(measurer)) +
                enum(SURFACE, listOf("NOT_SET", "MAIN_FEED", "CLIPS_VIEWER", "STORY_VIEWER")) +
                enum(KIND, listOf("PHOTO", "VIDEO", "CLIPS_VIEWER")) + reelClasses +
                ImmutableClassDef("Lfixture/Unrelated;", PUBLIC, OBJECT, null, null, null, null, emptyList())
        }
    }
}

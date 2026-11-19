/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.headerline

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide the line under names (#771): the hook is in the extension, and on every build of 456 the feed
 * header builder hands its label kinds to the extension right before it compares their count with 2,
 * then counts the answer, so the sliding line, the line that stays and the name-only header all follow
 * the filtered list.
 */
class HideHeaderLinePatchTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HEADER_LINE_KINDS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HEADER_LINE_KINDS is not in the extension: $declared", HEADER_LINE_KINDS.substringAfter("->") in declared)
        assertTrue("$REEL_LINE is not in the extension: $declared", REEL_LINE.substringAfter("->") in declared)
    }

    /**
     * On every build the reel author row's props constructor first lets Sponsored and Paid partnership
     * lines through, then hands any other line to the extension with Instagram's empty line, and takes
     * the answer, cast back to a line, in the line's own register before Instagram's code runs.
     */
    @Test
    fun eachBuildHandsTheReelLineOverFirstThingInTheRowsProps() {
        var checked = 0
        for (bundle in bundles()) {
            val context = PatchContexts.of(reelClasses(bundle))
            val site = context.findReelLine()
            val stock = context.constructor(site).code()

            // The register is the line's own: Instagram stores it in a field of the line's type.
            val stored = stock.filter { it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == site.line }
                .map { (it.reference() as FieldReference).type }
            assertEquals("${bundle.name}: v${site.line} is the line", listOf(site.lineType), stored)
            assertEquals("${bundle.name}: Sponsored and Paid partnership", 2, site.kept.size)
            assertTrue("${bundle.name}: nothing branches onto the first instruction", branchesOnto(stock, 0).isEmpty())

            context.hideReelLine(site)
            val code = context.constructor(site).code()
            val first = 2 * site.kept.size + 4
            site.kept.forEachIndexed { index, kept ->
                val check = code[2 * index]
                assertEquals("${bundle.name}: asks whether it's a $kept", Opcode.INSTANCE_OF, check.opcode)
                assertEquals("${bundle.name}: of the line", site.line, (check as TwoRegisterInstruction).registerB)
                assertEquals("${bundle.name}: the kind", kept, (check.reference() as TypeReference).type)
                val pass = code[2 * index + 1]
                assertEquals("${bundle.name}: and lets it through", Opcode.IF_NEZ, pass.opcode)
                assertEquals("${bundle.name}: on the answer", check.registerA, (pass as OneRegisterInstruction).registerA)
                assertEquals("${bundle.name}: to Instagram's first instruction", unit(code, first) - unit(code, 2 * index + 1), (pass as OffsetInstruction).codeOffset)
            }
            val none = code[first - 4]
            assertEquals("${bundle.name}: the empty line", site.none, (none.reference() as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" })
            val hook = code[first - 3]
            assertEquals("${bundle.name}: reaches the extension", REEL_LINE, hook.reference().toString())
            val passed = (hook as Instruction35c).let { listOf(it.registerC, it.registerD).take(it.registerCount) }
            assertEquals("${bundle.name}: with the line and the empty line", listOf(site.line, (none as OneRegisterInstruction).registerA), passed)
            assertEquals("${bundle.name}: the answer", site.line, (code[first - 2] as OneRegisterInstruction).registerA)
            assertEquals("${bundle.name}: takes the line's place", Opcode.MOVE_RESULT_OBJECT, code[first - 2].opcode)
            assertEquals("${bundle.name}: as a line", site.lineType, (code[first - 1].reference() as TypeReference).type)
            assertEquals("${bundle.name}: the hook is there once", 1, code.count { it.reference()?.toString() == REEL_LINE })
            assertStockKept(bundle.name, stock, code.subList(first, code.size))
            checked++
        }
        assertEquals("every build of ${Fixtures.VERSION} is read", Fixtures.BUILDS, checked)
    }

    /** The reel line's shapes and the row builder's classes, then every class the builder reads or makes. */
    private fun reelClasses(bundle: File): Collection<ClassDef> {
        val found = FixtureDex.classesLoadingAny(bundle, setOf(REEL_LINE_BUILDER, CYCLIC_LINE, SPONSORED_LINE, PAID_LINE))
            .associateByTo(LinkedHashMap()) { it.type }
        val first = PatchContexts.of(found.values)
        val touched = first.classesLoadingString(REEL_LINE_BUILDER).flatMap { it.methods }.filter { method ->
            first.loadsString(method, REEL_LINE_BUILDER)
        }.flatMap { method ->
            method.code().mapNotNull { instruction ->
                when (val reference = instruction.reference()) {
                    is FieldReference -> reference.definingClass
                    is MethodReference -> reference.definingClass
                    else -> null
                }
            }
        }.toSet()
        for ((type, classDef) in FixtureDex.classes(bundle, touched - found.keys)) found[type] = classDef
        return found.values
    }

    private fun unit(code: List<Instruction>, at: Int) = code.take(at).sumOf { it.codeUnits }

    private fun app.morphe.patcher.patch.BytecodePatchContext.constructor(site: ReelLineSite): Method =
        mutableClassDefBy(site.owner).methods.single { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == site.constructor }

    @Test
    fun eachBuildHandsTheKindsOverBeforeTheyAreCounted() {
        var checked = 0
        for (bundle in bundles()) {
            val context = PatchContexts.of(FixtureDex.classesLoading(bundle, FEED_HEADER_BUILDER))
            val site = context.findHeaderLine()
            val stock = context.method(site).code()

            // The site is Instagram's own count against 2, read from the stock code.
            val size = stock[site.at - 2]
            assertEquals("${bundle.name}: the count", "Ljava/util/List;->size()I", (size.reference() as MethodReference).let { "${it.definingClass}->${it.name}()${it.returnType}" })
            assertEquals("${bundle.name}: of the kinds list", site.list, (size as Instruction35c).registerC)
            assertEquals("${bundle.name}: compared", Opcode.IF_LT, stock[site.at].opcode)
            assertEquals("${bundle.name}: the count is what's compared", site.count, (stock[site.at] as TwoRegisterInstruction).registerA)
            val limit = (stock[site.at] as TwoRegisterInstruction).registerB
            val set = (site.at - 1 downTo 0).first { stock[it].opcode.setsRegister() && (stock[it] as? OneRegisterInstruction)?.registerA == limit }
            assertEquals("${bundle.name}: against 2", 2, (stock[set] as NarrowLiteralInstruction).narrowLiteral)
            assertTrue("${bundle.name}: nothing branches onto the count's compare", branchesOnto(stock, site.at).isEmpty())

            context.hideHeaderLine(site)
            val code = context.method(site).code()
            val hook = code[site.at]
            assertEquals("${bundle.name}: the kinds reach the extension", HEADER_LINE_KINDS, hook.reference().toString())
            assertEquals("${bundle.name}: the kinds list is what's handed over", site.list, (hook as RegisterRangeInstruction).startRegister)
            assertEquals("${bundle.name}: the answer replaces the list", Opcode.MOVE_RESULT_OBJECT, code[site.at + 1].opcode)
            assertEquals("${bundle.name}: in its register", site.list, (code[site.at + 1] as OneRegisterInstruction).registerA)
            assertEquals("${bundle.name}: and is counted again", "Ljava/util/List;->size()I", (code[site.at + 2].reference() as MethodReference).let { "${it.definingClass}->${it.name}()${it.returnType}" })
            assertEquals("${bundle.name}: the new list's count", site.list, (code[site.at + 2] as RegisterRangeInstruction).startRegister)
            assertEquals("${bundle.name}: into the compared register", site.count, (code[site.at + 3] as OneRegisterInstruction).registerA)
            assertEquals("${bundle.name}: then Instagram's compare", Opcode.IF_LT, code[site.at + 4].opcode)
            assertEquals("${bundle.name}: the hook is there once", 1, code.count { it.reference()?.toString() == HEADER_LINE_KINDS })
            assertStockKept(bundle.name, stock, code.subList(0, site.at) + code.subList(site.at + 4, code.size))
            checked++
        }
        assertEquals("every build of ${Fixtures.VERSION} is read", Fixtures.BUILDS, checked)
    }

    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.method(site: HeaderLineSite): Method =
        mutableClassDefBy(site.owner).methods.single { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == site.method }

    private fun assertStockKept(what: String, stock: List<Instruction>, kept: List<Instruction>) {
        fun kinds(code: List<Instruction>) = code.map { it.opcode.kind() }.filter { it != Opcode.NOP }
        assertEquals("$what: Instagram's own code is kept", kinds(stock), kinds(kept))
    }

    private fun Opcode.kind(): Opcode = if (this == Opcode.GOTO_16 || this == Opcode.GOTO_32) Opcode.GOTO else this
    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
}

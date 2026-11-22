/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.homereels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reels on Home below the name (#788): the hook is in the extension, and on every build of 456 the
 * row picker's two tests each ask it with the feed's module name before they can answer yes, the
 * feed test right where its live video branch ends and the tall test first thing.
 */
class HomeReelsBelowNamePatchTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(BELOW_NAME.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$BELOW_NAME is not in the extension: $declared", BELOW_NAME.substringAfter("->") in declared)
    }

    @Test
    fun eachBuildAsksBothTestsWithTheModuleName() {
        var checked = 0
        for (bundle in bundles()) {
            val context = PatchContexts.of(homeClasses(bundle))
            val sites = context.findHomeReels()
            val name = bundle.name
            val feedStock = context.method(sites.feed, sites.feedTest).code()
            val tallStock = context.method(sites.row, sites.tallTest).code()
            val picker = context.picker(sites)
            val pickerCode = picker.code()

            // The picker hands the feed test the module name its row maker's host answers, and asks
            // the tall test on that same row maker: read from the stock code, not from the finder.
            val feedCall = pickerCode.indexOfFirst { it.reference()?.toString() == "${sites.feed}->${sites.feedTest}" }
            assertTrue("$name: the picker asks the feed test", feedCall >= 0)
            val moduleArgument = arguments(pickerCode[feedCall]).last()
            val moduleSet = (feedCall - 1 downTo 0).first { writes(pickerCode[it], moduleArgument) }
            assertEquals("$name: the feed test gets the module name", Opcode.MOVE_RESULT_OBJECT, pickerCode[moduleSet].opcode)
            assertEquals("$name: from the host", sites.moduleName, pickerCode[moduleSet - 1].reference().toString())
            val hostArgument = arguments(pickerCode[moduleSet - 1]).single()
            val hostRead = pickerCode[moduleSet - 2]
            assertEquals("$name: the host is a field of the row maker", sites.host,
                (hostRead.reference() as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" })
            assertEquals("$name: read into the register asked", hostArgument, (hostRead as TwoRegisterInstruction).registerA)
            val tallCall = pickerCode.indexOfFirst { it.reference()?.toString() == "${sites.row}->${sites.tallTest}" }
            assertTrue("$name: the picker asks the tall test", tallCall >= 0)
            assertEquals("$name: on the row maker whose host it read", hostRead.registerB, arguments(pickerCode[tallCall]).first())

            // The feed test's live branch: it loads the live kind, compares, and on no lands on the site.
            val live = feedStock.indices.first { context.stringLoadedAt(feedStock, it) == LIVE_IN_FEED }
            val branch = (live until sites.afterLive).first { feedStock[it].opcode == Opcode.IF_EQZ }
            assertEquals("$name: the live branch ends at the site", sites.afterLive, targetOf(feedStock, branch))
            assertTrue("$name: the live branch answers yes", (branch until sites.afterLive).any { feedStock[it].opcode == Opcode.RETURN })
            val stockOnto = feedStock.indices.filter { targetOf(feedStock, it) == sites.afterLive }
            assertTrue("$name: Instagram's own jumps reach the site", stockOnto.isNotEmpty())

            context.keepHomeReelsBelowName(sites)

            val feed = context.method(sites.feed, sites.feedTest).code()
            val at = sites.afterLive
            assertEquals("$name: the feed test reaches the extension", BELOW_NAME, feed[at].reference().toString())
            assertEquals("$name: with the module name", sites.module, (feed[at] as RegisterRangeInstruction).startRegister)
            assertEquals("$name: one register", 1, (feed[at] as RegisterRangeInstruction).registerCount)
            assertAnswersNoOnYes(name, feed, at + 1, sites.feedScratch)
            for (from in stockOnto) assertEquals("$name: Instagram's jump at $from runs the hook", at, targetOf(feed, from))
            assertEquals("$name: the feed test's hook is there once", 1, feed.count { it.reference()?.toString() == BELOW_NAME })
            assertStockKept("$name feed test", feedStock, feed.subList(0, at) + feed.subList(at + 5, feed.size))

            val tall = context.method(sites.row, sites.tallTest).code()
            val t = sites.tallScratch
            assertEquals("$name: the row maker", Opcode.MOVE_OBJECT_FROM16, tall[0].opcode)
            assertEquals("$name: is this", sites.self, (tall[0] as TwoRegisterInstruction).registerB)
            assertEquals("$name: its host", sites.host, (tall[1].reference() as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" })
            assertEquals("$name: read from it", listOf(t, t), (tall[1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
            assertEquals("$name: a missing host", Opcode.IF_EQZ, tall[2].opcode)
            assertEquals("$name: keeps Instagram's answer", 10, targetOf(tall, 2))
            assertEquals("$name: the module name", sites.moduleName, tall[3].reference().toString())
            assertEquals("$name: of the host", t, (tall[3] as RegisterRangeInstruction).startRegister)
            assertEquals("$name: comes back", t, (tall[4] as OneRegisterInstruction).registerA)
            assertEquals("$name: the tall test reaches the extension", BELOW_NAME, tall[5].reference().toString())
            assertEquals("$name: with it", t, (tall[5] as RegisterRangeInstruction).startRegister)
            assertAnswersNoOnYes(name, tall, 6, t)
            assertEquals("$name: the tall test's hook is there once", 1, tall.count { it.reference()?.toString() == BELOW_NAME })
            assertStockKept("$name tall test", tallStock, tall.subList(10, tall.size))
            checked++
        }
        assertEquals("every build of ${Fixtures.VERSION} is read", Fixtures.BUILDS, checked)
    }

    /** At [at]: move-result vS, if-eqz vS to a nop right before Instagram's code, const/4 vS 0, return vS. */
    private fun assertAnswersNoOnYes(what: String, code: List<Instruction>, at: Int, scratch: Int) {
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at].opcode)
        assertEquals("$what: in the borrowed register", scratch, (code[at] as OneRegisterInstruction).registerA)
        assertEquals("$what: on no", Opcode.IF_EQZ, code[at + 1].opcode)
        assertEquals("$what: of it", scratch, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: Instagram decides", at + 4, targetOf(code, at + 1))
        assertEquals("$what: past the hook", Opcode.NOP, code[at + 4].opcode)
        assertEquals("$what: on yes, no", Opcode.CONST_4, code[at + 2].opcode)
        assertEquals("$what: zero", 0, (code[at + 2] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$what: returned", Opcode.RETURN, code[at + 3].opcode)
        assertEquals("$what: from that register", scratch, (code[at + 3] as OneRegisterInstruction).registerA)
    }

    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
    }

    /** The classes holding the two strings, then every class calling a feed test, the picker's among them. */
    private fun homeClasses(bundle: File): Collection<ClassDef> {
        val found = FixtureDex.classesLoadingAny(bundle, setOf(LIVE_IN_FEED, FULL_HEIGHT_ROW)).associateByTo(LinkedHashMap()) { it.type }
        val first = PatchContexts.of(found.values)
        val tests = first.classesLoadingString(LIVE_IN_FEED).flatMap { it.methods }.filter { method ->
            method.returnType == "Z" && method.parameterTypes.size == 2 && first.loadsString(method, LIVE_IN_FEED)
        }.map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }.toSet()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.methodSection.none { it.toString() in tests }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.type in found) continue
                if (classDef.methods.any { method -> method.code().any { it.reference()?.toString() in tests } }) {
                    found[classDef.type] = ImmutableClassDef.of(classDef)
                }
            }
        }
        return found.values
    }

    private fun BytecodePatchContext.method(owner: String, signature: String): Method =
        mutableClassDefBy(owner).methods.single { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == signature }

    private fun BytecodePatchContext.picker(sites: HomeReelSites): Method =
        classDefBy(sites.row).methods.single { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.code().any { it.reference()?.toString() == "${sites.feed}->${sites.feedTest}" } &&
                method.code().any { it.reference()?.toString() == "${sites.row}->${sites.tallTest}" }
        }

    private fun arguments(call: Instruction): List<Int> = when (call) {
        is Instruction35c -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount)
        is Instruction3rc -> (call.startRegister until call.startRegister + call.registerCount).toList()
        else -> emptyList()
    }

    private fun writes(instruction: Instruction, register: Int): Boolean =
        instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA == register

    private fun assertStockKept(what: String, stock: List<Instruction>, kept: List<Instruction>) {
        fun kinds(code: List<Instruction>) = code.map { it.opcode.kind() }.filter { it != Opcode.NOP }
        assertEquals("$what: Instagram's own code is kept", kinds(stock), kinds(kept))
    }

    private fun Opcode.kind(): Opcode = if (this == Opcode.GOTO_16 || this == Opcode.GOTO_32) Opcode.GOTO else this
    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
}

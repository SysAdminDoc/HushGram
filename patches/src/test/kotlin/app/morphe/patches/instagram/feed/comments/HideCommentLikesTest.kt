/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.comments

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide likes on comments (#762): in every declared build the comment row a comments sheet builds
 * as components asks CommentLikes.hidden right before it adds the heart-and-count component to
 * its column, skips the add on a yes, and keeps the rest of its code.
 */
class HideCommentLikesTest {
    @Test
    fun theHookIsInTheExtension() {
        val type = COMMENT_LIKES_HIDDEN.substringBefore("->")
        val declared = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$type->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$COMMENT_LIKES_HIDDEN is not in the extension: $declared", COMMENT_LIKES_HIDDEN in declared)
    }

    @Test
    fun eachDeclaredBuildTakesTheHeartOffTheRow() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                takesTheHeartOff(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** With nothing making a heart there is nothing to hook: the find stops, and the warning route answers null. */
    @Test
    fun withoutAMakerTheFindStops() {
        val bundle = firstBundle()
        val classes = rowClasses(bundle)
        val makers = PatchContexts.of(classes).findCommentRowLikes().map { it.type }.toSet()
        val without = classes.filter { it.type !in makers }
        val failure = runCatching { PatchContexts.of(without).findCommentRowLikes() }.exceptionOrNull()
        assertTrue("the find went on: $failure", failure is PatchException)
        assertTrue(failure!!.message!!, failure.message!!.startsWith("Hide comments: ") && failure.message!!.contains("found 0") &&
            failure.message!!.contains(COMMENT_LIKE_TAG))
        assertNull(PatchContexts.of(without).findCommentRowLikesOrWarn())
    }

    /** Without the heart components there is nothing to look for: the find stops before any row is read. */
    @Test
    fun withoutTheHeartTheFindStops() {
        val bundle = firstBundle()
        val classes = rowClasses(bundle)
        val components = PatchContexts.of(classes).findCommentRowLikes().map { it.component }.toSet()
        val without = classes.filter { it.type !in components }
        val failure = runCatching { PatchContexts.of(without).findCommentRowLikes() }.exceptionOrNull()
        assertTrue("the find went on: $failure", failure is PatchException)
        assertTrue(failure!!.message!!, failure.message!!.contains(COMMENT_LIKE_TAG) && failure.message!!.contains("found 0"))
        assertNull(PatchContexts.of(without).findCommentRowLikesOrWarn())
    }

    /**
     * Every method making a heart gets a plan, and in front of each heart add goes the no-argument
     * hook, its answer in a register the add doesn't read, and an if-nez on it landing right after
     * the add. Nothing jumps onto an add, and nothing else changes.
     */
    private fun takesTheHeartOff(bundle: File, label: String) {
        val classes = rowClasses(bundle)
        val context = PatchContexts.of(classes)
        val plans = context.findCommentRowLikes()
        val components = plans.map { it.component }.toSet()
        val makers = classes.flatMap { classDef -> classDef.methods.filter { method -> method.code().any { it.madeBy(components) } }.map { classDef.type to it.name } }
        assertEquals("$label: every method making a heart has a plan", makers.toSet(), plans.map { it.type to it.name }.toSet())
        val rows = plans.groupBy { Triple(it.type, it.name, it.parameters) }.map { (key, planned) ->
            val row = context.mutableClassDefBy(key.first).methods.single {
                it.name == key.second && it.parameterTypes.map(CharSequence::toString) == key.third
            }
            Triple(row, row.code(), planned.sortedBy { it.addAt })
        }
        for ((_, stock, planned) in rows) for (plan in planned) {
            assertEquals("$label: the add", plan.add, stock[plan.addAt].referenceText())
            val add = stock[plan.addAt].reference() as MethodReference
            assertEquals("$label: a collection add of one object", "add(Ljava/lang/Object;)Z",
                "${add.name}(${add.parameterTypes.joinToString("")})${add.returnType}")
            val made = stock.indices.single { stock[it].madeBy(setOf(plan.component)) }
            val component = (stock[made] as OneRegisterInstruction).registerA
            assertEquals("$label: the add takes the new heart column", component, stock[plan.addAt].arguments()[1])
            assertTrue("$label: the add's answer is dropped", stock[plan.addAt + 1].opcode != Opcode.MOVE_RESULT)
            assertEquals("$label: nothing jumps onto the add", 0, stock.jumpsInto(plan.addAt))
        }

        context.hideCommentRowLikes(plans)

        for ((row, stock, planned) in rows) {
            val code = row.code()
            val hooks = code.indices.filter { code[it].referenceText() == COMMENT_LIKES_HIDDEN }
            assertEquals("$label: one hook right before each add in ${row.name}", planned.mapIndexed { n, plan -> plan.addAt + 3 * n }, hooks)
            for ((n, at) in hooks.withIndex()) {
                assertEquals("$label: the hook", Opcode.INVOKE_STATIC to 0, code[at].opcode to (code[at] as FiveRegisterInstruction).registerCount)
                assertEquals("$label: its answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
                val answer = (code[at + 1] as OneRegisterInstruction).registerA
                assertEquals("$label: the branch reads the answer", Opcode.IF_NEZ to answer, code[at + 2].opcode to (code[at + 2] as OneRegisterInstruction).registerA)
                assertEquals("$label: a yes goes on past the add", at + 4, (code[at + 2] as BuilderOffsetInstruction).target.location.index)
                assertEquals("$label: the add follows", planned[n].add, code[at + 3].referenceText())
                assertTrue("$label: the add doesn't read v$answer", answer !in code[at + 3].arguments())
                assertEquals("$label: nothing jumps onto the hook", 0, code.jumpsInto(at))
            }
            val inserted = hooks.flatMap { it..it + 2 }.toSet()
            val kept = code.filterIndexed { index, _ -> index !in inserted }
            assertEquals("$label: the row's own code is kept", stock.shape(), kept.shape())
        }
    }

    private fun firstBundle(): File {
        val version = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.first()
        return Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.first()
    }

    /** The classes loading a heart tag, the pools they ask included, and every class making a heart. */
    private fun rowClasses(bundle: File): List<ClassDef> {
        val likes = FixtureDex.classesLoading(bundle, COMMENT_LIKE_TAG)
        val counts = FixtureDex.classesLoading(bundle, COMMENT_LIKE_COUNT_TAG)
        val hearts = likes.map { it.type }.toSet().intersect(counts.map { it.type }.toSet())
        return (likes + counts + FixtureDex.classesCreating(bundle, hearts)).distinctBy { it.type }
    }

    private fun Instruction.madeBy(types: Collection<String>) =
        opcode == Opcode.NEW_INSTANCE && (reference() as TypeReference).type in types

    private fun List<Instruction>.jumpsInto(at: Int) = count { (it as? BuilderOffsetInstruction)?.target?.location?.index == at }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    /** Opcodes and references without padding, with a widened goto read as a goto. */
    private fun List<Instruction>.shape() = filter { it.opcode != Opcode.NOP && !it.opcode.format.isPayloadFormat }
        .map { kind(it.opcode) to it.referenceText() }

    private fun kind(opcode: Opcode) = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.savemenu

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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide the Like button (D#877): the builder of a feed post's row of buttons hands the Like button,
 * the one it wraps with the LIKE_LINK click area, through FeedSaveRow right before its own null
 * check, so a null answer leaves the heart out and the Comment button moves into its place.
 */
class LikeButtonHookTest {
    @Test
    fun theLikeButtonGoesThroughTheHookRightBeforeItsNullCheck() {
        val context = PatchContexts.of(fixture())

        val found = context.findFeedLikeButton()
        assertEquals("$ROW->render", "${found.method.definingClass}->${found.method.name}")
        assertEquals("the like's null check", LIKE_CHECK, found.at)
        assertEquals("the like's register", 1, found.register)
        assertEquals("the wrapper's answer", COMPONENT, found.type)
        assertTrue("before the write, nothing is changed", found.method.code().none { it.calls(LIKE_BUTTON) })

        found.write()

        val code = found.method.code()
        assertEquals("one call", 1, code.count { it.calls(LIKE_BUTTON) })
        assertFilteredBeforeCheck("fixture", code, found.register, COMPONENT)
        val check = code.indexOfFirst { it.calls(LIKE_BUTTON) } + 3
        assertEquals("the null check still skips only the add", code[check + 2], code.branchTarget(check))
        assertTrue("the add still follows", code[check + 1].calls(ADD))
    }

    @Test
    fun withoutOneLikeAddNothingIsFound() {
        for ((case, classes) in listOf(
            "no builder" to fixture().filter { it.type != ROW },
            "two builders" to fixture() + row(type = "Lfixture/OtherRow;"),
            "the builder loading only one of its strings" to fixture().filter { it.type != ROW } + row(share = false),
            "no Like click area" to fixture().filter { it.type != CLICK_AREA } + clickArea(like = "SHARE_LINK"),
            "the Like button added twice" to fixture().filter { it.type != ROW } + row(twice = true),
            "a jump onto the null check" to fixture().filter { it.type != ROW } + row(jump = true),
            "the answer checked but not added" to fixture().filter { it.type != ROW } + row(added = false),
        )) {
            val context = PatchContexts.of(classes)
            assertThrows(case, PatchException::class.java) { context.findFeedLikeButton() }
            assertNull(case, context.findFeedLikeButtonOrWarn())
        }
    }

    /** On each build the row builder adds one Like button, and the hook lands right before its null check. */
    @Test
    fun eachBuildFindsTheLikeButton() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            builds(bundle, "${bundle.parentFile.name}/${bundle.name}")
            checked++
        }
        assertEquals("every build is read", Fixtures.BUILDS, checked)
    }

    private fun builds(bundle: File, label: String) {
        val loading = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, COMMENT_COMPONENT))
        val have = loading.mapTo(HashSet()) { it.type }
        val enums = loading.asSequence().flatMap { it.methods.asSequence() }.flatMap { it.code().asSequence() }
            .filter { it.opcode == Opcode.SGET_OBJECT }
            .map { (it as ReferenceInstruction).reference as FieldReference }
            .filter { it.type == it.definingClass && it.definingClass !in have }
            .map { it.definingClass }.toSet()
        val context = PatchContexts.of(loading + FixtureDex.classes(bundle, enums).values)

        val found = context.findFeedLikeButton()
        val stock = found.method.code()
        val wrap = stock[found.at - 2]
        assertTrue("$label: the wrapper is a static call", wrap.opcode == Opcode.INVOKE_STATIC || wrap.opcode == Opcode.INVOKE_STATIC_RANGE)
        val skipped = stock.branchTarget(found.at)

        found.write()

        val code = found.method.code()
        assertEquals("$label: one call", 1, code.count { it.calls(LIKE_BUTTON) })
        assertFilteredBeforeCheck(label, code, found.register, found.type)
        val check = code.indexOfFirst { it.calls(LIKE_BUTTON) } + 3
        assertEquals("$label: the null check still lands where it did", skipped, code.branchTarget(check))
        assertEquals("$label: the null check skips only the add", code[check + 2], code.branchTarget(check))
        val add = (code[check + 1] as ReferenceInstruction).reference as MethodReference
        assertEquals("$label: the add", "add", add.name)
    }

    /** The hook's call on [register], its answer and the cast back to [type], then Instagram's null check of it. */
    private fun assertFilteredBeforeCheck(what: String, code: List<Instruction>, register: Int, type: String) {
        val call = code.indexOfFirst { it.calls(LIKE_BUTTON) }
        assertEquals("$what: the wrapper's answer before the call", Opcode.MOVE_RESULT_OBJECT, code[call - 1].opcode)
        assertEquals("$what: the answer's register", register, (code[call - 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the call's register", register, (code[call] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the call takes one register", 1, (code[call] as RegisterRangeInstruction).registerCount)
        assertEquals("$what: the hook's answer", Opcode.MOVE_RESULT_OBJECT, code[call + 1].opcode)
        assertEquals("$what: the hook's answer register", register, (code[call + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[call + 2].opcode)
        assertEquals("$what: the cast's type", type, ((code[call + 2] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$what: the cast's register", register, (code[call + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the null check", Opcode.IF_EQZ, code[call + 3].opcode)
        assertEquals("$what: the null check's register", register, (code[call + 3] as OneRegisterInstruction).registerA)
    }

    /** The instruction the branch at [from] lands on. */
    private fun List<Instruction>.branchTarget(from: Int): Instruction {
        var unit = 0
        val units = map { instruction -> unit.also { unit += instruction.codeUnits } }
        val target = units[from] + (this[from] as OffsetInstruction).codeOffset
        return this[units.indexOf(target)]
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private companion object {
        const val CLICK_AREA = "Lfixture/ClickArea;"
        const val COMPONENT = "Lfixture/Component;"
        const val WRAP = "Lfixture/Wrap;->wrap(Lfixture/ClickArea;Lfixture/Component;)Lfixture/Component;"
        const val ROW = "Lfixture/ButtonRow;"
        const val CONTEXT = "Lfixture/Context;"
        const val ADD = "Ljava/util/AbstractCollection;->add(Ljava/lang/Object;)Z"
        /** Where [row]'s Like null check sits. */
        const val LIKE_CHECK = 7
        val FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        fun fixture(): List<ClassDef> = listOf(clickArea(), row())

        /** Instagram's click areas, as an enum building each constant with its name in its static initializer. */
        fun clickArea(like: String = "LIKE_LINK"): ClassDef {
            val build = """
                new-instance v0, $CLICK_AREA
                const-string v1, "$like"
                const/4 v2, 0x0
                invoke-direct { v0, v1, v2 }, $CLICK_AREA-><init>(Ljava/lang/String;I)V
                sput-object v0, $CLICK_AREA->a:$CLICK_AREA
                new-instance v0, $CLICK_AREA
                const-string v1, "SAVE_BUTTON"
                const/4 v2, 0x1
                invoke-direct { v0, v1, v2 }, $CLICK_AREA-><init>(Ljava/lang/String;I)V
                sput-object v0, $CLICK_AREA->b:$CLICK_AREA
                return-void
            """
            val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
            val fields = listOf(
                ImmutableField(CLICK_AREA, "a", CLICK_AREA, flags, null, null, null),
                ImmutableField(CLICK_AREA, "b", CLICK_AREA, flags, null, null, null),
            )
            val clinit = method(CLICK_AREA, "<clinit>", emptyList(), "V", 3, build,
                AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value)
            return classDef(CLICK_AREA, "Ljava/lang/Enum;", fields, listOf(clinit))
        }

        /**
         * The row's builder: the Like button wrapped with its click area, the answer checked and added,
         * then the comment and share lambdas' names and the Save button wrapped and added the same way.
         */
        fun row(
            type: String = ROW,
            share: Boolean = true,
            twice: Boolean = false,
            jump: Boolean = false,
            added: Boolean = true,
        ): ClassDef {
            val like = """
                sget-object v3, $CLICK_AREA->a:$CLICK_AREA
                move-object v4, v2
                invoke-static { v3, v4 }, $WRAP
                move-result-object v1
                if-eqz v1, :%s
                ${if (added) "invoke-virtual { v0, v1 }, $ADD" else "invoke-virtual { v0 }, Ljava/lang/Object;->hashCode()I"}
                :%s
            """
            val render = """
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V
                const/4 v2, 0x0
                ${like.format("noLike", "noLike")}
                ${if (twice) like.format("noLike2", "noLike2") else ""}
                const-string v5, "$COMMENT_COMPONENT"
                ${if (share) "const-string v5, \"$SHARE_COMPONENT\"" else ""}
                ${if (jump) "if-nez v5, :check" else ""}
                sget-object v3, $CLICK_AREA->b:$CLICK_AREA
                invoke-static { v3, v2 }, $WRAP
                move-result-object v1
                if-eqz v1, :noSave
                invoke-virtual { v0, v1 }, $ADD
                :noSave
                const/4 v1, 0x0
                return-object v1
            """
            val body = if (jump) render.replaceFirst("if-eqz v1, :noLike", ":check\nif-eqz v1, :noLike") else render
            return classDef(type, "Ljava/lang/Object;", emptyList(), listOf(method(type, "render", listOf(CONTEXT), COMPONENT, 6, body)))
        }

        fun classDef(type: String, superclass: String, fields: List<ImmutableField>, methods: List<Method>): ClassDef =
            ImmutableClassDef(type, FINAL, superclass, null, null, null, fields, methods)

        fun method(
            owner: String,
            name: String,
            parameters: List<String>,
            result: String,
            registers: Int,
            body: String,
            flags: Int = AccessFlags.PUBLIC.value,
        ): Method =
            MutableMethod(ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, result,
                flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, body.trimIndent().lines().map(String::trim).filter(String::isNotEmpty).joinToString("\n"))
            }.let(ImmutableMethod::of)
    }
}

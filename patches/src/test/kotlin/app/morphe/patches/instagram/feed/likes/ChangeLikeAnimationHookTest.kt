/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.likes

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The double-tap heart's set-up for a post hands Instagram's animation to LikeAnimation.pick first
 * and its check before playing one of Instagram's own to LikeAnimation.allow, as an int, and the
 * extension's stub answers the plain heart's value. Nothing else of the extension's is filled in.
 */
class ChangeLikeAnimationHookTest {
    @Test
    fun theSetUpTakesThePickedAnimationAndItsCheckIsOpened() {
        val context = PatchContexts.of(listOf(animation(), view(), extension()))

        val anchors = context.findLikeAnimation()
        assertEquals(ANIMATION, anchors.animation)
        assertEquals("NONE", anchors.none.name)
        assertEquals("the check's answer", 4, anchors.gate)
        context.applyLikeAnimation(anchors)

        val code = context.configure()
        assertEquals(
            listOf(PICK_LIKE_ANIMATION, "move-result-object", "check-cast", "if-eqz", "sget-object", "if-eq", GATE,
                "move-result", ALLOW_LIKE_ANIMATION, "move-result", "if-eqz", SET_UP, "const/4", "return-void", OTHER, "goto"),
            code.map(::shape),
        )
        assertEquals("pick reads the animation", 4, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals("and writes it back", 4, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(ANIMATION, (code[2] as ReferenceInstruction).reference.toString())
        assertEquals("allow reads the check's answer", 0, (code[8] as FiveRegisterInstruction).registerC)
        assertEquals("and answers into it", 0, (code[9] as OneRegisterInstruction).registerA)
        assertEquals("the test reads that", 0, (code[10] as OneRegisterInstruction).registerA)
        assertTrue("once each", code.count { it.calls(PICK_LIKE_ANIMATION) } == 1 && code.count { it.calls(ALLOW_LIKE_ANIMATION) } == 1)

        assertEquals(listOf("sget-object", "return-object"), context.stub(NO_ANIMATION_STUB).take(2).map { it.opcode.name })
        assertEquals("$ANIMATION->NONE:$ANIMATION", (context.stub(NO_ANIMATION_STUB)[0] as ReferenceInstruction).reference.toString())
        assertTrue("the type is read off that value, not written in", context.stub("animationType").none { it.opcode == Opcode.CONST_CLASS })
    }

    @Test
    fun aViewOrAnimationThatIsntTheOneFailsThePatch() {
        refuses("no view", listOf(animation(), extension()))
        refuses("no set-up", listOf(animation(), view(setUp = "setUpSomethingElse"), extension()))
        refuses("an animation that isn't an enum", listOf(animation(superclass = "Ljava/lang/Object;"), view(), extension()))
        refuses("an animation that isn't public", listOf(animation(flags = AccessFlags.FINAL.value or AccessFlags.ENUM.value), view(), extension()))
        refuses("an animation that isn't in the app", listOf(view(), extension()))
        refuses("two set-ups for a post", listOf(animation(), view(twice = true), extension()))
        refuses("no plain heart compared", listOf(animation(), view(compare = ""), extension()))
        refuses("a plain heart of another type", listOf(animation(), view(compare = OTHER_NONE), extension()))
        refuses("two checks", listOf(animation(), view(check = "$CHECK\n$CHECK"), extension()))
        refuses("no check", listOf(animation(), view(check = ""), extension()))
        refuses("no extension", listOf(animation(), view()))
    }

    /** A branch landing on the check's test would skip allow, so the patch fails before changing anything. */
    @Test
    fun aBranchOntoTheCheckTestFailsThePatchUnchanged() {
        val context = PatchContexts.of(listOf(animation(), view(entry = "if-nez p2, :test"), extension()))
        val anchors = context.findLikeAnimation()
        val before = context.configure().map(::shape)
        val failure = assertThrows(PatchException::class.java) { context.applyLikeAnimation(anchors) }
        assertTrue(failure.message, failure.message!!.contains("lands right after its check"))
        assertEquals(before, context.configure().map(::shape))
    }

    /**
     * On every declared build's fixture: the view's one set-up for a post takes the session and the
     * animation enum, compares it with the plain heart's value, checks the session once, and gets
     * both hooks and the stub.
     */
    @Test
    fun eachDeclaredBuildPlaysThePickedAnimation() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val view = FixtureDex.classes(bundle, setOf(LIKE_ACTION_VIEW)).values.single()
                val animation = view.methods.single { it.name == SET_UP_CUSTOM_LIKES }.parameterTypes.single().toString()
                val classes = listOf(view) + FixtureDex.classes(bundle, setOf(animation)).values + extension()
                val context = PatchContexts.of(classes)

                val anchors = context.findLikeAnimation()
                assertEquals(bundle.name, animation, anchors.animation)
                assertEquals("${bundle.name}: the plain heart is the animation's own", animation, anchors.none.definingClass)
                context.applyLikeAnimation(anchors)

                val code = context.mutableClassDefBy(LIKE_ACTION_VIEW).methods.single {
                    it.name == anchors.configure.name && it.parameterTypes.map(Any::toString) == anchors.configure.parameterTypes.map(Any::toString)
                }.implementation!!.instructions.toList()
                assertTrue("${bundle.name}: pick first", code[0].calls(PICK_LIKE_ANIMATION))
                assertEquals(bundle.name, Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
                assertEquals(bundle.name, Opcode.CHECK_CAST, code[2].opcode)
                val register = (code[0] as RegisterRangeInstruction).startRegister
                assertEquals("${bundle.name}: the animation's register", anchors.configure.implementation!!.registerCount - 1, register)
                assertEquals(bundle.name, register, (code[1] as OneRegisterInstruction).registerA)
                assertEquals(bundle.name, register, (code[2] as OneRegisterInstruction).registerA)
                val allow = code.indexOfFirst { it.calls(ALLOW_LIKE_ANIMATION) }
                assertEquals("${bundle.name}: allow right after the check's answer", Opcode.MOVE_RESULT, code[allow - 1].opcode)
                assertTrue("${bundle.name}: and the check before that", (code[allow - 2] as ReferenceInstruction).reference.toString().endsWith("(Lcom/instagram/common/session/UserSession;)Z"))
                assertEquals(bundle.name, Opcode.MOVE_RESULT, code[allow + 1].opcode)
                assertEquals("${bundle.name}: the test after", Opcode.IF_EQZ, code[allow + 2].opcode)
                assertTrue("${bundle.name}: the set-up after", code.drop(allow).any { it.calls("$LIKE_ACTION_VIEW->$SET_UP_CUSTOM_LIKES($animation)V") })
                assertEquals(bundle.name, 1, code.count { it.calls(PICK_LIKE_ANIMATION) })
                assertEquals(bundle.name, 1, code.count { it.calls(ALLOW_LIKE_ANIMATION) })
                assertEquals(bundle.name, anchors.none.toString(), (context.stub(NO_ANIMATION_STUB)[0] as ReferenceInstruction).reference.toString())
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun refuses(case: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        assertThrows(case, PatchException::class.java) { context.findLikeAnimation() }
    }

    private fun shape(instruction: Instruction): String = when {
        instruction.calls(PICK_LIKE_ANIMATION) -> PICK_LIKE_ANIMATION
        instruction.calls(ALLOW_LIKE_ANIMATION) -> ALLOW_LIKE_ANIMATION
        instruction.calls(GATE_REFERENCE) -> GATE
        instruction.calls(OTHER_REFERENCE) -> OTHER
        instruction.calls("$LIKE_ACTION_VIEW->$SET_UP_CUSTOM_LIKES($ANIMATION)V") -> SET_UP
        else -> instruction.opcode.name
    }

    private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun BytecodePatchContext.configure(): List<Instruction> =
        mutableClassDefBy(LIKE_ACTION_VIEW).methods.single { it.name == "set" }.implementation!!.instructions.toList()

    private fun BytecodePatchContext.stub(name: String): List<Instruction> =
        mutableClassDefBy(LIKE_ANIMATION).methods.single { it.name == name }.implementation!!.instructions.toList()

    private companion object {
        const val ANIMATION = "Lfixture/LikeAnimationKind;"
        const val OTHER_ANIMATION = "Lfixture/OtherKind;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        const val GATE_REFERENCE = "Lfixture/Gate;->on($SESSION)Z"
        const val OTHER_REFERENCE = "Lfixture/Gate;->plain($SESSION)Z"
        const val GATE = "GATE"
        const val OTHER = "OTHER"
        const val SET_UP = "SET_UP"
        const val CHECK = "invoke-static { p1 }, $GATE_REFERENCE\nmove-result v0"
        const val OTHER_NONE = "$OTHER_ANIMATION->NONE:$OTHER_ANIMATION"
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        fun extension(): ClassDef = ExtensionDex.classDef(LIKE_ANIMATION)

        /** The animation enum: the plain heart's value and one Rings animation, as static fields of its own type. */
        fun animation(
            superclass: String = "Ljava/lang/Enum;",
            flags: Int = PUBLIC_FINAL or AccessFlags.ENUM.value,
        ): ClassDef {
            val fieldFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value
            val fields = listOf("NONE", "RINGS").map { ImmutableField(ANIMATION, it, ANIMATION, fieldFlags, null, null, null) }
            return ImmutableClassDef(ANIMATION, flags, superclass, null, null, null, fields, emptyList())
        }

        /**
         * The heart view as 450 has it: the set-up for a post (here "set") plays the plain heart when
         * the animation is missing or the plain heart's value, or when the session's check says no,
         * and hands it to the set-up for one of Instagram's own otherwise. v0 and v1 are its locals,
         * then the view, the session and the animation.
         */
        fun view(
            setUp: String = SET_UP_CUSTOM_LIKES,
            twice: Boolean = false,
            compare: String = "$ANIMATION->NONE:$ANIMATION",
            check: String = CHECK,
            entry: String = "",
        ): ClassDef {
            val comparison = if (compare.isEmpty()) "" else "sget-object v0, $compare\nif-eq p2, v0, :plain"
            val body = """
                $entry
                if-eqz p2, :plain
                $comparison
                $check
                ${if (":test" in entry) ":test" else ""}
                if-eqz v0, :plain
                invoke-virtual { p0, p2 }, $LIKE_ACTION_VIEW->$setUp($ANIMATION)V
                :reset
                const/4 v1, 0x0
                return-void
                :plain
                invoke-static { p1 }, $OTHER_REFERENCE
                goto :reset
            """.lines().filter { it.isNotBlank() }.joinToString("\n")
            val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            val methods = mutableListOf(
                method(setUp, listOf(ANIMATION), 2, AccessFlags.PUBLIC.value, "return-void"),
                method("set", listOf(SESSION, ANIMATION), 5, instance, body),
            )
            if (twice) methods += method("setAgain", listOf(SESSION, ANIMATION), 5, instance, body)
            return ImmutableClassDef(LIKE_ACTION_VIEW, AccessFlags.PUBLIC.value, "Landroid/widget/ImageView;", null, null, null, null, methods)
        }

        fun method(name: String, parameters: List<String>, registers: Int, flags: Int, body: String): Method =
            MutableMethod(ImmutableMethod(LIKE_ACTION_VIEW, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
                flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, body.trimIndent())
            }.let(ImmutableMethod::of)
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.opening

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The story viewer's animator passes its spring's stiffness and damping ratio through the
 * extension right before it sets them, and a build where either can't be placed fails the patch
 * unchanged.
 */
class SmoothStoryOpenHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(SMOOTH_STORY_OPEN).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$SMOOTH_STORY_OPEN->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("SmoothStoryOpen declares no stiffness(F)F: $declared", EASE_STIFFNESS in declared)
        assertTrue("SmoothStoryOpen declares no damping(F)F: $declared", EASE_DAMPING in declared)
    }

    @Test
    fun bothSettersAreEasedRightBeforeTheirCalls() {
        val classes = listOf(spring(), animator())
        val context = PatchContexts.of(classes)

        val found = context.findStorySpring()
        assertEquals(ANIMATOR, found.type)
        assertEquals(1, found.stiffness.register)
        assertEquals(2, found.damping.register)
        context.easeStorySpring(found)

        assertEased("fixture", context, found, classes.single { it.type == ANIMATOR })
        assertUnchanged(context, spring())
    }

    /** A stiffness set through invoke-virtual/range hands its float in the register after the spring. */
    @Test
    fun aRangeCallIsEasedToo() {
        val classes = listOf(spring(), animator(range = true))
        val context = PatchContexts.of(classes)

        val found = context.findStorySpring()
        assertEquals(1, found.stiffness.register)
        context.easeStorySpring(found)

        assertEased("range", context, found, classes.single { it.type == ANIMATOR })
    }

    @Test
    fun aBuildWithoutTheAnimatorFailsThePatch() =
        refuses("one class loading \"$ANIMATE_REVEAL\"", listOf(spring(), animator(reveal = false)))

    @Test
    fun aBuildWithoutTheSpringsChecksFailsThePatch() =
        refuses("no spring stiffness setter", listOf(spring(stiffnessCheck = "Stiffness"), animator()))

    @Test
    fun aSecondStiffnessCallFailsThePatch() =
        refuses("set its spring's stiffness once, found 2", listOf(spring(), animator(stiffnessCalls = 2)))

    @Test
    fun aJumpOntoACallFailsThePatch() =
        refuses("jumps onto the stiffness call", listOf(spring(), animator(jumpOntoStiffness = true)))

    @Test
    fun eachDeclaredBuildEasesTheSpring() {
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

    @Test
    fun eachOtherBuildEasesTheSpringToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val classes = listOf(ANIMATE_REVEAL, STIFFNESS_CHECK, DAMPING_CHECK)
            .flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, classes))

        val found = context.findStorySpring()
        val before = classes.single { it.type == found.type }
        context.easeStorySpring(found)

        assertEased(label, context, found, before)
    }

    /**
     * Each setter call has the hook and its move-result right before it, on the register it hands
     * the spring, no branch lands on a hook, the rest of the animator is as it was, and no other
     * method of the class changed.
     */
    private fun assertEased(label: String, context: BytecodePatchContext, found: StorySpring, before: ClassDef) {
        val methods = context.mutableClassDefBy(found.type).methods
        for (original in before.methods) {
            val now = methods.single { it.sameSignatureAs(original) }
            val calls = listOf(found.stiffness to EASE_STIFFNESS, found.damping to EASE_DAMPING).filter { (call, _) ->
                call.name == original.name && call.returnType == original.returnType &&
                    call.parameters == original.parameterTypes.map(CharSequence::toString)
            }
            if (calls.isEmpty()) {
                assertEquals("$label: ${original.name} changed", original.code().map(::text), now.code().map(::text))
                continue
            }
            val code = now.implementation!!.instructions.toList()
            val added = mutableSetOf<Int>()
            for ((call, hook) in calls) {
                val at = code.indexOfFirst { text(it) == "${Opcode.INVOKE_STATIC_RANGE} $hook" }
                assertTrue("$label: no $hook in ${original.name}", at >= 0)
                val easing = code[at] as RegisterRangeInstruction
                assertEquals("$label: $hook takes one register", 1, easing.registerCount)
                assertEquals("$label: $hook's register", call.register, easing.startRegister)
                assertEquals("$label: $hook's answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
                assertEquals("$label: $hook's answer register", call.register, (code[at + 1] as OneRegisterInstruction).registerA)
                val setter = code[at + 2]
                val called = (setter as ReferenceInstruction).reference as MethodReference
                assertEquals("$label: after $hook", listOf("F"), called.parameterTypes.map(CharSequence::toString))
                assertEquals("$label: the float the setter gets after $hook", call.register, floatRegister(setter))
                added += setOf(at, at + 1)
            }
            assertEquals("$label: hooks in ${original.name}", calls.size,
                code.count { text(it).endsWith(EASE_STIFFNESS) || text(it).endsWith(EASE_DAMPING) })
            NeutralNativePath(original).assertPreserved("$label ${original.name}", now, added)

            val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
            val hookAddresses = added.filter { code[it].opcode == Opcode.INVOKE_STATIC_RANGE }.map { addresses[it] }.toSet()
            for ((at, instruction) in code.withIndex()) {
                if (instruction !is OffsetInstruction) continue
                val target = addresses[at] + instruction.codeOffset
                assertTrue("$label: the branch at $at lands on a hook", target !in hookAddresses)
            }
        }
    }

    private fun floatRegister(call: Instruction): Int = when (call) {
        is FiveRegisterInstruction -> call.registerD
        is RegisterRangeInstruction -> call.startRegister + 1
        else -> -1
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.easeStorySpring(context.findStorySpring()) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) assertUnchanged(context, classDef)
    }

    private fun assertUnchanged(context: BytecodePatchContext, before: ClassDef) {
        val after = context.mutableClassDefBy(before.type).methods.map { method -> method.code().map(::text) }
        assertEquals("${before.type} changed", before.methods.map { method -> method.code().map(::text) }, after)
    }

    /** The code without the alignment nops dexlib2 adds or drops around a payload. */
    private fun Method.code(): List<Instruction> = implementation?.instructions?.filter { it.opcode != Opcode.NOP } ?: emptyList()

    /** Parameters compared as text: dexlib2's lists of two kinds don't equal each other. */
    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.toString()
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode} $reference"
    }

    private companion object {
        const val SPRING = "Lfixture/Spring;"
        const val ANIMATOR = "Lfixture/StoryAnimator;"
        val SET_STIFFNESS = ImmutableMethodReference(SPRING, "stiffness", listOf("F"), "V")
        val SET_DAMPING = ImmutableMethodReference(SPRING, "damping", listOf("F"), "V")

        /** A SpringForce stand-in: two (F)V setters, each loading the check AndroidX throws with. */
        fun spring(stiffnessCheck: String = STIFFNESS_CHECK): ClassDef {
            fun setter(name: String, check: String) = ImmutableMethod(
                SPRING, name, listOf(ImmutableMethodParameter("F", null, null)), "V",
                AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(
                    3,
                    listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(check)),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    null, null,
                ),
            )
            return ImmutableClassDef(
                SPRING, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(setter("stiffness", stiffnessCheck), setter("damping", DAMPING_CHECK)),
            )
        }

        /**
         * The story viewer's animator: a reveal that logs [ANIMATE_REVEAL], and a build() that
         * makes the spring and sets its damping ratio from v2 and its stiffness from v1, the way
         * 453's constructor does.
         *
         *     new-instance v0, Spring
         *     const v1, 1500f
         *     const v2, 0.5f
         *     invoke-virtual {v0, v2}, damping(F)V
         *     [goto :stiffness]  (jumpOntoStiffness)
         *     :stiffness invoke-virtual {v0, v1}, stiffness(F)V  (or invoke-virtual/range {v0 .. v1})
         *     return-void
         */
        fun animator(reveal: Boolean = true, range: Boolean = false, stiffnessCalls: Int = 1, jumpOntoStiffness: Boolean = false): ClassDef {
            val stiffness = if (range) ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 0, 2, SET_STIFFNESS)
            else ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, SET_STIFFNESS)
            val code = buildList<Instruction> {
                add(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(SPRING)))
                add(ImmutableInstruction31i(Opcode.CONST, 1, 1500f.toRawBits()))
                add(ImmutableInstruction31i(Opcode.CONST, 2, 0.5f.toRawBits()))
                add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 2, 0, 0, 0, SET_DAMPING))
                if (jumpOntoStiffness) add(ImmutableInstruction10t(Opcode.GOTO, 1))
                repeat(stiffnessCalls) { add(stiffness) }
                add(ImmutableInstruction10x(Opcode.RETURN_VOID))
            }
            val build = ImmutableMethod(
                ANIMATOR, "build", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(4, code, null, null),
            )
            val revealMethod = ImmutableMethod(
                ANIMATOR, "reveal", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(
                    2,
                    listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(if (reveal) ANIMATE_REVEAL else "ReelViewer")),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    null, null,
                ),
            )
            return ImmutableClassDef(
                ANIMATOR, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(build, revealMethod),
            )
        }
    }
}

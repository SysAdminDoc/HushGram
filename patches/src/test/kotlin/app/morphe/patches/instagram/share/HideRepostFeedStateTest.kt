/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

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
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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
 * Feed's action-row state (#69): the flag its toString prints after "isRepostButtonEnabled=", and
 * the count's when it's a field of its own set once, go through the extension right before the
 * state's constructor stores them, so a row drawn again from the same state still has no Repost.
 */
class HideRepostFeedStateTest {
    @Test
    fun theButtonAndItsCountAreHookedWhereTheConstructorStoresThem() {
        val context = PatchContexts.of(listOf(state()))

        val found = context.findFeedRepostState()
        assertEquals(STATE, found.type)
        assertEquals("the animation is printed from a constant", listOf("enabled", "count"), found.flags.map { it.name })
        assertEquals(listOf(2 to 0, 4 to 0), found.writes.map { it.at to it.value })
        assertTrue("nothing reads the value after its write", found.writes.all { it.answer == it.value })
        context.hideFeedState(found)

        val code = context.constructor()
        assertEquals(2, code.count { it.calls(REPOSTS_FEED_STATE) })
        assertHooked("enabled", code, "enabled", value = 0, answer = 0)
        assertHooked("count", code, "count", value = 0, answer = 0)
    }

    /** An animation kept as a flag of its own, set once, goes the same way as the count. */
    @Test
    fun anAnimationKeptAsAFlagIsHookedToo() {
        val context = PatchContexts.of(listOf(state(animate = "iget-boolean v4, p0, $STATE->animate:Z", constructor = WITH_ANIMATE)))
        val found = context.findFeedRepostState()
        assertEquals(listOf("enabled", "count", "animate"), found.flags.map { it.name })
        context.hideFeedState(found)
        val code = context.constructor()
        assertEquals(3, code.count { it.calls(REPOSTS_FEED_STATE) })
        assertHooked("animate", code, "animate", value = 0, answer = 0)
    }

    /** A count set twice, or one that isn't final, isn't proved to be the row's, so the button goes alone. */
    @Test
    fun aCountSetTwiceOrNotFinalIsLeftAlone() {
        for ((case, classDef) in listOf(
            "set twice" to state(constructor = COUNT_TWICE),
            "not final" to state(countFlags = AccessFlags.PUBLIC.value),
        )) {
            val context = PatchContexts.of(listOf(classDef))
            val found = context.findFeedRepostState()
            assertEquals(case, listOf("enabled"), found.flags.map { it.name })
            context.hideFeedState(found)
            val code = context.constructor()
            assertEquals(case, 1, code.count { it.calls(REPOSTS_FEED_STATE) })
            assertHooked(case, code, "enabled", value = 0, answer = 0)
        }
    }

    /**
     * When the value's register is read again after its write, the answer goes into a spare one
     * and the write stores that, so the other flag set from the same value keeps Instagram's.
     */
    @Test
    fun aValueReadAfterItsWriteIsAnsweredIntoASpareRegister() {
        val context = PatchContexts.of(listOf(state(constructor = SHARED_VALUE)))
        val found = context.findFeedRepostState()
        val enabled = found.writes.single { it.field.name == "enabled" }
        assertEquals("p1", 2, enabled.value)
        assertEquals("the one spare local", 0, enabled.answer)
        context.hideFeedState(found)

        val code = context.constructor()
        assertHooked("enabled", code, "enabled", value = 2, answer = 0)
        val clickable = code[code.indexOfStore("clickable")] as TwoRegisterInstruction
        assertEquals("the other flag still stores p1", 2, clickable.registerA)
        assertHooked("count", code, "count", value = 3, answer = 3)
    }

    /** A branch that went to the write now goes to the call, so no path stores the flag unasked. */
    @Test
    fun aBranchToTheWriteRunsTheHook() {
        val context = PatchContexts.of(listOf(state(constructor = BRANCH_TO_STORE)))
        context.hideFeedState(context.findFeedRepostState())

        val code = context.constructor()
        val call = code.indexOfStore("enabled") - 2
        assertTrue(code[call].calls(REPOSTS_FEED_STATE))
        val branch = code.filterIsInstance<BuilderOffsetInstruction>().single()
        assertEquals("the branch lands on the call", call, branch.target.location.index)
    }

    @Test
    fun noStateOrTwoFailThePatch() {
        assertRefused("no state", PatchContexts.of(listOf(state(enabledLabel = ", isRepostEnabled="))))
        assertRefused("two states", PatchContexts.of(listOf(state(), state(type = OTHER_STATE))))
    }

    @Test
    fun aLabelNotFollowedByOneOfItsOwnBooleanFieldsFailsThePatch() {
        for ((case, classDef) in listOf(
            "an int" to state(appended = "I"),
            "a constant" to state(enabled = "const/4 v1, 0x1"),
            "another class's field" to state(enabled = "iget-boolean v1, v3, $OTHER_STATE->enabled:Z"),
            "another object's field" to state(enabled = "sget-object v3, $STATE->EMPTY:$STATE\n    iget-boolean v1, v3, $STATE->enabled:Z"),
            "a field read on one path only" to state(enabled = "const/4 v1, 0x0\n    if-eqz v1, :read\n    iget-boolean v1, v3, $STATE->enabled:Z\n    :read"),
        )) assertRefused(case, PatchContexts.of(listOf(classDef)))
    }

    @Test
    fun aFlagSetOtherThanByItsConstructorFailsThePatch() {
        val setter = method(STATE, "withEnabled", listOf("Z"), "V", 2, AccessFlags.PUBLIC.value,
            "iput-boolean p1, p0, $STATE->enabled:Z\nreturn-void")
        for ((case, classDef) in listOf(
            "not final" to state(enabledFlags = AccessFlags.PUBLIC.value),
            "set by another method" to state(extra = listOf(setter)),
            "never set" to state(constructor = NO_ENABLED),
            "no spare register" to state(constructor = NO_SPARE, constructorRegisters = 4),
        )) assertRefused(case, PatchContexts.of(listOf(classDef)))
    }

    /**
     * On each declared build the state is found by its label, and both its writes, the button's and
     * the count's, are hooked right before they store, with the call reading the stored register and
     * its answer going back into the register the write stores. On 450 each value is moved out of
     * its parameter right before, and nothing reads it afterwards.
     */
    @Test
    fun eachDeclaredBuildKeepsTheFeedRowsRepostOff() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val context = PatchContexts.of(FixtureDex.classesHolding(bundle, REPOST_ENABLED_LABEL))
                val found = context.findFeedRepostState()
                assertEquals("${bundle.name}: the button and its count", 2, found.flags.size)
                assertEquals("${bundle.name}: each set once", 2, found.writes.size)
                assertEquals("${bundle.name}: one constructor", 1, found.writes.map { it.parameters }.toSet().size)
                assertTrue("${bundle.name}: in place", found.writes.all { it.answer == it.value })
                context.hideFeedState(found)

                val code = context.mutableClassDefBy(found.type).methods.single { it.name == "<init>" }
                    .implementation!!.instructions.toList()
                assertEquals(bundle.name, 2, code.count { it.calls(REPOSTS_FEED_STATE) })
                for (write in found.writes) {
                    val at = assertHooked(bundle.name, code, write.field.name, write.value, write.answer, write.field)
                    val before = code[at - 3]
                    assertTrue("${bundle.name}: the value is moved in right before",
                        before.opcode.setsRegister() && (before as OneRegisterInstruction).registerA == write.value)
                }
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /**
     * The write of [name] stores [answer], right after the call takes [value] and moves its answer
     * into [answer]. Answers the write's index.
     */
    private fun assertHooked(
        what: String,
        code: List<Instruction>,
        name: String,
        value: Int,
        answer: Int,
        field: FieldReference? = null,
    ): Int {
        val at = code.indexOfStore(name, field)
        val call = code[at - 2]
        assertTrue("$what: the call before the write", call.calls(REPOSTS_FEED_STATE))
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, call.opcode)
        call as FiveRegisterInstruction
        assertEquals("$what: the call's one argument", 1, call.registerCount)
        assertEquals("$what: the call reads the value", value, call.registerC)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at - 1].opcode)
        assertEquals("$what: the answer's register", answer, (code[at - 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the write stores the answer", answer, (code[at] as TwoRegisterInstruction).registerA)
        return at
    }

    private fun assertRefused(case: String, context: BytecodePatchContext) {
        assertThrows(case, PatchException::class.java) { context.findFeedRepostState() }
        for (type in listOf(STATE, OTHER_STATE)) {
            val methods = runCatching { context.classDefBy(type).methods }.getOrDefault(emptyList())
            for (method in methods) {
                assertTrue("$case: $type->${method.name} changed",
                    method.implementation!!.instructions.none { it.calls(REPOSTS_FEED_STATE) })
            }
        }
    }

    private fun List<Instruction>.indexOfStore(name: String, field: FieldReference? = null): Int {
        val stores = indices.filter { at ->
            val stored = ((this[at] as? ReferenceInstruction)?.reference as? FieldReference)
            this[at].opcode == Opcode.IPUT_BOOLEAN && stored != null && stored.name == name &&
                (field == null || stored.toString() == field.toString())
        }
        assertEquals("one write of $name", 1, stores.size)
        return stores.single()
    }

    private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun BytecodePatchContext.constructor(): List<Instruction> =
        mutableClassDefBy(STATE).methods.single { it.name == "<init>" }.implementation!!.instructions.toList()

    private companion object {
        const val STATE = "Lfixture/FeedRowState;"
        const val OTHER_STATE = "Lfixture/OtherRowState;"
        const val APPEND_STRING = "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;"
        const val APPEND_BOOLEAN = "Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;"
        val FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        /** As 450 writes it: each flag moved out of its parameter, stored, and the register used again. */
        val STORES = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            move v0, p1
            iput-boolean v0, p0, $STATE->enabled:Z
            move v0, p2
            iput-boolean v0, p0, $STATE->count:Z
            iput-boolean p3, p0, $STATE->clickable:Z
            return-void
        """
        val WITH_ANIMATE = STORES.replace("return-void", "move v0, p1\n    iput-boolean v0, p0, $STATE->animate:Z\n    return-void")
        val COUNT_TWICE = STORES.replace("return-void", "iput-boolean p3, p0, $STATE->count:Z\n    return-void")
        val NO_ENABLED = STORES.replace("iput-boolean v0, p0, $STATE->enabled:Z", "nop")

        /** p1 sets both the button and the clickable flag, so its register is read after the first write. */
        val SHARED_VALUE = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            iput-boolean p1, p0, $STATE->enabled:Z
            iput-boolean p1, p0, $STATE->clickable:Z
            iput-boolean p2, p0, $STATE->count:Z
            return-void
        """

        /** As [SHARED_VALUE] with no local to spare: the constructor has its parameters and nothing else. */
        val NO_SPARE = SHARED_VALUE

        val BRANCH_TO_STORE = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            move v0, p1
            if-nez p2, :store
            const/4 v0, 0x0
            :store
            iput-boolean v0, p0, $STATE->enabled:Z
            iput-boolean p2, p0, $STATE->count:Z
            return-void
        """

        /**
         * The state: three flags, an optional animation flag, a constructor storing them, and a
         * toString that loads the button's, the count's and the animation's first, the button's
         * through a copy of this as 450's does, then prints each right after its label.
         */
        fun state(
            type: String = STATE,
            enabledLabel: String = REPOST_ENABLED_LABEL,
            enabled: String = "iget-boolean v1, v3, $type->enabled:Z",
            appended: String = "Z",
            animate: String = "const/4 v4, 0x0",
            enabledFlags: Int = FINAL,
            countFlags: Int = FINAL,
            constructor: String = STORES,
            constructorRegisters: Int = 5,
            extra: List<Method> = emptyList(),
        ): ClassDef {
            val printer = """
                move-object v3, p0
                $enabled
                iget-boolean v2, p0, $type->count:Z
                $animate
                new-instance v0, Ljava/lang/StringBuilder;
                invoke-direct { v0 }, Ljava/lang/StringBuilder;-><init>()V
                const-string v3, "$enabledLabel"
                invoke-virtual { v0, v3 }, $APPEND_STRING
                invoke-virtual { v0, v1 }, Ljava/lang/StringBuilder;->append($appended)Ljava/lang/StringBuilder;
                const-string v3, "$REPOST_COUNT_LABEL"
                invoke-virtual { v0, v3 }, $APPEND_STRING
                invoke-virtual { v0, v2 }, $APPEND_BOOLEAN
                const-string v3, "$REPOST_ANIMATE_LABEL"
                invoke-virtual { v0, v3 }, $APPEND_STRING
                invoke-virtual { v0, v4 }, $APPEND_BOOLEAN
                invoke-virtual { v0 }, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v0
                return-object v0
            """
            val methods = listOf(
                method(type, "<init>", listOf("Z", "Z", "Z"), "V", constructorRegisters,
                    AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, constructor.replace(STATE, type)),
                method(type, "toString", emptyList(), "Ljava/lang/String;", 6, AccessFlags.PUBLIC.value, printer),
            ) + extra
            val fields = listOf(
                ImmutableField(type, "enabled", "Z", enabledFlags, null, null, null),
                ImmutableField(type, "count", "Z", countFlags, null, null, null),
                ImmutableField(type, "clickable", "Z", FINAL, null, null, null),
                ImmutableField(type, "animate", "Z", FINAL, null, null, null),
                ImmutableField(type, "EMPTY", type, FINAL or AccessFlags.STATIC.value, null, null, null),
            )
            return ImmutableClassDef(type, FINAL, "Ljava/lang/Object;", null, null, null, fields, methods)
        }

        fun method(owner: String, name: String, parameters: List<String>, result: String, registers: Int, flags: Int,
                   body: String): Method = MutableMethod(ImmutableMethod(owner, name,
            parameters.map { ImmutableMethodParameter(it, null, null) }, result, flags, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, body.trimIndent())
        }.let(ImmutableMethod::of)
    }
}

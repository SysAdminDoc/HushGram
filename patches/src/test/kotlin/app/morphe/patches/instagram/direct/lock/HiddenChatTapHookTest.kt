/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.instagram.media.taptoplay.TOUCH as TAP_CLOCK
import app.morphe.patches.instagram.stories.mentions.PILL_TOUCH
import app.morphe.patches.instagram.stories.mentions.findPillTouch
import app.morphe.patches.instagram.stories.mentions.hookPillTouch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Double-tap Messages for hidden chats (#358): the touch hook in IgFragmentActivity.dispatchTouchEvent. */
class HiddenChatTapHookTest {
    /**
     * IgFragmentActivity with its own dispatchTouchEvent: [locals] locals, then the activity and the
     * event. It loads its trace name and answers false, after Tap to play's call when [tapClock].
     */
    private fun activity(locals: Int = 7, tapClock: Boolean = false): ClassDef {
        val self = locals
        val answer = if (locals > 0) 0 else self
        val code = listOfNotNull(
            ImmutableInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE, self, 2,
                ImmutableMethodReference(TAP_CLOCK.substringBefore("->"), "touch", listOf("Landroid/app/Activity;", "Landroid/view/MotionEvent;"), "V"),
            ).takeIf { tapClock },
            ImmutableInstruction21c(Opcode.CONST_STRING, answer, ImmutableStringReference("dispatchTouchEvent")),
            ImmutableInstruction11n(Opcode.CONST_4, answer, 0),
            ImmutableInstruction11x(Opcode.RETURN, answer),
        )
        val touch = ImmutableMethod(
            FRAGMENT_ACTIVITY, "dispatchTouchEvent", listOf(ImmutableMethodParameter("Landroid/view/MotionEvent;", null, null)), "Z",
            AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(locals + 2, code, null, null),
        )
        return ImmutableClassDef(
            FRAGMENT_ACTIVITY, AccessFlags.PUBLIC.value, "Landroidx/appcompat/app/AppCompatActivity;", null, null, null, null, listOf(touch),
        )
    }

    /**
     * The activity hands each touch to the double tap's hook first thing, answers true when it says
     * so, and otherwise runs on from its own first instruction, with the answer in its first local.
     */
    @Test
    fun everyTouchReachesTheDoubleTapFirstThingInTheActivity() {
        val touch = PatchContexts.of(listOf(activity())).findHiddenChatsTouch()
        hookHiddenChatsTouch(touch)

        val code = touch.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals(HIDDEN_CHATS_TAP, code[0].referenceText())
        assertEquals("this and the event", listOf(7, 2), (code[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals("the branch goes to Instagram's own first instruction", 4, (code[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals(Opcode.RETURN, code[3].opcode)
        assertEquals(0, (code[3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.CONST_STRING, code[4].opcode)
    }

    /** Tap to play's call stays first, and with See who a story mentions' pill hook in too, each falls through to the next. */
    @Test
    fun withTapToPlayAndThePillTheHooksChain() {
        val context = PatchContexts.of(listOf(activity(tapClock = true)))
        hookPillTouch(context.findPillTouch())
        val touch = context.findHiddenChatsTouch()
        hookHiddenChatsTouch(touch)

        val code = touch.implementation!!.instructions.toList()
        assertEquals(TAP_CLOCK, code[0].referenceText())
        assertEquals(HIDDEN_CHATS_TAP, code[1].referenceText())
        assertEquals("the double tap falls through to the pill", 5, (code[3] as BuilderOffsetInstruction).target.location.index)
        assertEquals(PILL_TOUCH, code[5].referenceText())
        assertEquals("the pill falls through to Instagram", 9, (code[7] as BuilderOffsetInstruction).target.location.index)
        assertEquals(Opcode.CONST_STRING, code[9].opcode)
    }

    @Test
    fun anActivityWithNoLocalFailsThePatch() = refuses("no local register") {
        PatchContexts.of(listOf(activity(locals = 0))).findHiddenChatsTouch()
    }

    @Test
    fun noActivityFailsThePatch() = refuses("this build has no") {
        PatchContexts.of(emptyList()).findHiddenChatsTouch()
    }

    private fun refuses(detail: String, run: () -> Unit) {
        val failure = assertThrows(PatchException::class.java) { run() }
        assertTrue(failure.message, failure.message!!.startsWith("$LOCK_PATCH: "))
        assertTrue(failure.message, failure.message!!.contains(detail))
    }

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }
}

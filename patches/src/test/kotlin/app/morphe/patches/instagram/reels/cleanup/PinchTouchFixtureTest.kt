/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinch for clear mode (#744) hands each of a reel's touches to the extension first, so a second
 * finger landing keeps the list around the reel out of the gesture and fingers spreading apart reach
 * Instagram's pinch, which only fingers closing in did before. The touch is the pinch listener's
 * method taking the reel's view and the event and handing the event to its scale detector, the
 * listener being the one whose scale end logs pinch_to_zoom.
 */
class PinchTouchFixtureTest {
    @Test
    fun theTouchHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_PINCH_CLEAR_MODE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$PINCH_CLEAR_MODE_TOUCH is not in the extension: $declared", PINCH_CLEAR_MODE_TOUCH.substringAfter("->") in declared)
    }

    @Test
    fun theTouchHandsItsViewAndEventOverFirstThing() {
        val context = PatchContexts.of(listOf(listener(LISTENER)))
        val found = context.findPinchTouch() ?: throw AssertionError("the fixture's touch wasn't found")
        assertEquals(LISTENER, found.owner)
        assertEquals("A04", found.name)
        val stock = touchOf(context.classDefBy(LISTENER))
        context.pinchTouch(found)
        val now = touchOf(context.mutableClassDefBy(LISTENER))
        assertHooked("fixture", now, stock)
    }

    @Test
    fun noListenerOrTwoLeaveTheTouchAlone() {
        assertNull("no listener", PatchContexts.of(listOf(listener(LISTENER, logs = false))).findPinchTouch())
        assertNull("two listeners", PatchContexts.of(listOf(listener(LISTENER), listener(OTHER_LISTENER))).findPinchTouch())
        assertNull("a listener that isn't a gesture listener", PatchContexts.of(listOf(listener(LISTENER, gesture = false))).findPinchTouch())
        assertNull("no touch feeding the detector", PatchContexts.of(listOf(listener(LISTENER, feeds = false))).findPinchTouch())
    }

    @Test
    fun eachDeclaredBuildHandsItsTouchOver() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                handsItsTouchOver(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHandsItsTouchOverToo() {
        var checked = 0
        for (base in Fixtures.otherBuilds()) {
            handsItsTouchOver(base, base.parentFile.name)
            checked++
        }
        assertEquals("the builds besides the declared one", Fixtures.OTHER_BUILDS, checked)
    }

    private fun handsItsTouchOver(bundle: File, label: String) {
        val sliced = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, PINCH_TO_ZOOM))
        val context = PatchContexts.of(sliced)
        val found = context.findPinchTouch() ?: throw AssertionError("$label: no reel touch found")
        val stock = touchOf(context.classDefBy(found.owner))
        assertEquals("$label: the touch's view register", stock.parameterRegisterNumber(0), found.first)
        context.pinchTouch(found)
        assertHooked(label, touchOf(context.mutableClassDefBy(found.owner)), stock)
    }

    private fun touchOf(owner: ClassDef): Method = owner.methods.single {
        it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(VIEW, EVENT) &&
            it.code().any { instruction -> instruction.reference() == SCALE_TOUCH }
    }

    /** The hook first, on the view and the event as Instagram passed them, and the stock body right after it, untouched. */
    private fun assertHooked(label: String, now: Method, stock: Method) {
        val code = now.code()
        val hook = code.first()
        assertEquals("$label: the hook goes first", PINCH_CLEAR_MODE_TOUCH, hook.reference())
        hook as RegisterRangeInstruction
        assertEquals("$label: the hook takes the view", now.parameterRegisterNumber(0), hook.startRegister)
        assertEquals("$label: and the event", 2, hook.registerCount)
        assertEquals("$label: the event follows the view", now.parameterRegisterNumber(1), hook.startRegister + 1)
        val rest = code.drop(1).filter { it.opcode != Opcode.NOP }
        val before = stock.code().filter { it.opcode != Opcode.NOP }
        assertEquals("$label: the stock body follows", before.map { it.opcode }, rest.map { it.opcode })
        assertEquals("$label: with the same references", before.map { it.reference() }, rest.map { it.reference() })
        assertEquals("$label: the hook goes in once", 1, code.count { it.reference() == PINCH_CLEAR_MODE_TOUCH })
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        const val LISTENER = "Lfixture/PinchListener;"
        const val OTHER_LISTENER = "Lfixture/OtherPinchListener;"
        const val VIEW = "Landroid/view/View;"
        const val EVENT = "Landroid/view/MotionEvent;"
        const val DETECTOR = "Landroid/view/ScaleGestureDetector;"
        val PUBLIC = AccessFlags.PUBLIC.value
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        fun method(owner: String, name: String, parameters: List<String>, returnType: String, registers: Int, smali: String): Method {
            val method = MutableMethod(
                ImmutableMethod(
                    owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, PUBLIC_FINAL, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null),
                ),
            )
            method.addInstructionsWithLabels(0, smali)
            return ImmutableMethod.of(method)
        }

        /**
         * A pinch listener shaped like Instagram 456's: a gesture listener and scale listener whose scale end
         * logs pinch_to_zoom ([logs]), and whose touch hands the event to the detector ([feeds]).
         */
        fun listener(owner: String, logs: Boolean = true, gesture: Boolean = true, feeds: Boolean = true): ClassDef {
            val end = method(owner, "onScaleEnd", listOf(DETECTOR), "V", 3, """
                const-string v0, "${if (logs) PINCH_TO_ZOOM else "something_else"}"
                return-void
            """.trimIndent())
            val feed = if (feeds) "invoke-virtual {v1, p2}, $SCALE_TOUCH\nmove-result v0" else "const/4 v0, 0x0"
            val touch = method(owner, "A04", listOf(VIEW, EVENT), "Z", 5, """
                iget-object v1, p0, $owner->detector:$DETECTOR
                if-eqz v1, :none
                $feed
                return v0
                :none
                const/4 v0, 0x0
                return v0
            """.trimIndent())
            val superclass = if (gesture) SIMPLE_GESTURE_LISTENER else "Ljava/lang/Object;"
            val field = ImmutableField(owner, "detector", DETECTOR, PUBLIC_FINAL, null, null, null)
            return ImmutableClassDef(owner, PUBLIC, superclass, listOf(SCALE_LISTENER), null, null, listOf(field), listOf(end, touch))
        }
    }
}

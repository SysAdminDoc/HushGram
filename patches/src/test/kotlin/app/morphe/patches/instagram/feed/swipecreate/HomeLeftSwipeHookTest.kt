/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.swipecreate

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.lock.HIDDEN_CHATS_TAP
import app.morphe.patches.instagram.direct.lock.findHiddenChatsTouch
import app.morphe.patches.instagram.direct.lock.hookHiddenChatsTouch
import app.morphe.patches.instagram.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.instagram.media.taptoplay.TOUCH as TAP_CLOCK
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.instagram.reels.swipeprofile.SWIPE_TO_PROFILE
import app.morphe.patches.shared.compat.AppCompatibilities
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

/** Swipe left for Messages (#639): the touch hook in IgFragmentActivity.dispatchTouchEvent. */
class HomeLeftSwipeHookTest {
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

    /** The hook the patch writes is in the HomeLeftSwipe the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HOME_LEFT_SWIPE.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HOME_LEFT_SWIPE is not in the extension: $declared", HOME_LEFT_SWIPE.substringAfter("->") in declared)
    }

    /**
     * The activity hands each touch to the swipe's hook first thing, answers true when it says so,
     * and otherwise runs on from its own first instruction, with the answer in its first local.
     */
    @Test
    fun everyTouchReachesTheSwipeFirstThingInTheActivity() {
        val touch = PatchContexts.of(listOf(activity())).findHomeLeftSwipeTouch()
        hookHomeLeftSwipe(touch)

        val code = touch.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals(HOME_LEFT_SWIPE, code[0].referenceText())
        assertEquals("this and the event", listOf(7, 2), (code[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals(0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("the branch goes to Instagram's own first instruction", 4, (code[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals(Opcode.RETURN, code[3].opcode)
        assertEquals(0, (code[3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.CONST_STRING, code[4].opcode)
        assertEquals("Instagram's own code follows unchanged", listOf(Opcode.CONST_STRING, Opcode.CONST_4, Opcode.RETURN), code.drop(4).map { it.opcode })
    }

    /** Tap to play's call stays first, and with the hidden chats' double tap in too, each falls through to the next. */
    @Test
    fun withTapToPlayAndTheDoubleTapTheHooksChain() {
        val context = PatchContexts.of(listOf(activity(tapClock = true)))
        hookHiddenChatsTouch(context.findHiddenChatsTouch())
        val touch = context.findHomeLeftSwipeTouch()
        hookHomeLeftSwipe(touch)

        val code = touch.implementation!!.instructions.toList()
        assertEquals(TAP_CLOCK, code[0].referenceText())
        assertEquals(HOME_LEFT_SWIPE, code[1].referenceText())
        assertEquals("the swipe falls through to the double tap", 5, (code[3] as BuilderOffsetInstruction).target.location.index)
        assertEquals(HIDDEN_CHATS_TAP, code[5].referenceText())
        assertEquals("the double tap falls through to Instagram", 9, (code[7] as BuilderOffsetInstruction).target.location.index)
        assertEquals(Opcode.CONST_STRING, code[9].opcode)
        assertEquals("one swipe hook", 1, code.count { it.referenceText() == HOME_LEFT_SWIPE })
    }

    /** Without Tap to play the swipe's hook goes first, in front of Instagram's own code. */
    @Test
    fun withTheDoubleTapAlreadyInTheSwipeGoesFirst() {
        val context = PatchContexts.of(listOf(activity()))
        hookHiddenChatsTouch(context.findHiddenChatsTouch())
        val touch = context.findHomeLeftSwipeTouch()
        hookHomeLeftSwipe(touch)

        val code = touch.implementation!!.instructions.toList()
        assertEquals(HOME_LEFT_SWIPE, code[0].referenceText())
        assertEquals("the swipe falls through to the double tap", 4, (code[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals(HIDDEN_CHATS_TAP, code[4].referenceText())
        assertEquals(Opcode.CONST_STRING, code[8].opcode)
    }

    /**
     * The tabs' screen the swipe arms on, and the one Swipe left to open the profile leaves to the
     * Reels tab's panel, is named as a class every declared build has. The manifest's MainTabActivity
     * is a launcher alias with no class behind it, so no screen ever comes by that name.
     */
    @Test
    fun theTabsScreenIsNamedAsAClassInEveryBuild() {
        val names = setOf(
            ExtensionDex.stringConstant(HOME_LEFT_SWIPE.substringBefore("->"), "MAIN_TABS"),
            ExtensionDex.stringConstant(SWIPE_TO_PROFILE, "MAIN_TABS"),
        )
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val builds = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        assertTrue("no fixture of a declared build", builds.isNotEmpty())
        for (build in builds) {
            val found = mutableSetOf<String>()
            FixtureDex.forEach(build) { dex ->
                for (classDef in dex.classes) {
                    val simple = classDef.type.substringAfterLast('/').removeSuffix(";")
                    if (simple in names) found += simple
                }
            }
            assertEquals(build.name, names, found)
        }
    }

    @Test
    fun anActivityWithNoLocalFailsThePatch() = refuses("no local register") {
        PatchContexts.of(listOf(activity(locals = 0))).findHomeLeftSwipeTouch()
    }

    @Test
    fun noActivityFailsThePatch() = refuses("this build has no") {
        PatchContexts.of(emptyList()).findHomeLeftSwipeTouch()
    }

    private fun refuses(detail: String, run: () -> Unit) {
        val failure = assertThrows(PatchException::class.java) { run() }
        assertTrue(failure.message, failure.message!!.startsWith("Stop swipe to create: "))
        assertTrue(failure.message, failure.message!!.contains(detail))
    }

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }
}

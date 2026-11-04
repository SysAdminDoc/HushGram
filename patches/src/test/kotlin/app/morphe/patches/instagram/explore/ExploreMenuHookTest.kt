/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreMenuHookTest {
    /** Every hook the patch writes is in the ExploreMenu the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(EXPLORE_MENU).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(UNPERSONALIZE, RELOADED, KEEP_UNPERSONALIZED, MENU_BUTTON, OLD_MENU_BUTTON)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * In each declared build the timer, onResume's shape and both menu buttons are found, and after
     * the patch each place goes past the extension. On 453.0.0.57.79's 385914557 a branch lands on
     * the personalized flag read, so the hook has to take that branch's label.
     */
    @Test
    fun eachDeclaredBuildUnpersonalizesExplore() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        var branchedOnto = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                if (unpersonalizes(bundle, bundle.name)) branchedOnto++
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
        assertTrue("no declared build branches onto the personalized flag read", branchedOnto > 0)
    }

    /** The same in the other builds of each declared version, which Redex compiled on their own. */
    @Test
    fun eachOtherBuildUnpersonalizesExplore() {
        val builds = Fixtures.otherBuilds()
        for (apk in builds) unpersonalizes(apk, apk.parentFile.name)
        assertEquals("other builds of ${Fixtures.VERSION}", Fixtures.OTHER_BUILDS, builds.size)
    }

    @Test
    fun noTimerFailsThePatch() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findExploreTimer() }
        assertTrue(failure.message!!, failure.message!!.contains("expected one timer loading $DSA_EXPIRED, found 0"))
    }

    /** Without the older top bar the menu's icon can't be told, and nothing is written. */
    @Test
    fun noOlderTopBarFailsThePatch() {
        val bundle = declaredBundle()
        val context = PatchContexts.of(slice(bundle).filter { it.type != EXPLORE_ACTION_BAR })

        val resume = context.findExploreResume(context.findExploreTimer())
        val failure = assertThrows(PatchException::class.java) { context.findExploreMenuButtons(resume) }
        assertTrue(failure.message!!, failure.message!!.contains("$EXPLORE_ACTION_BAR is gone"))
    }

    /**
     * An onResume that no longer reads the personalized flag in front of the menu flag is a shape
     * the patch hasn't seen. The read is swapped for two nops, the same size, so every branch and
     * try block still lands where it did.
     */
    @Test
    fun anUnseenResumeFailsThePatch() {
        val bundle = declaredBundle()
        val classes = slice(bundle)
        val timer = PatchContexts.of(classes).findExploreTimer()
        val resume = PatchContexts.of(classes).findExploreResume(timer)
        val changed = classes.map {
            if (it.type == resume.holder) withoutInstruction(it, resume.method, resume.parameters, resume.hookAt) else it
        }

        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(changed).findExploreResume(timer) }
        assertTrue(failure.message!!, failure.message!!.contains("doesn't read the personalized flag first"))
    }

    /**
     * 454's 386014704 asks the timer in the controller's LX/0D2f;->A05, which onResume calls with the
     * controller as `this`, and ends two instructions past the flag write. The hook goes there.
     */
    @Test
    fun aControllerAskingTheTimerTakesTheHook() {
        val context = PatchContexts.of(listOf(fragment(callsController()), controller(CONTROLLER)))
        val resume = context.findExploreResume(STAND_IN_TIMER)
        assertEquals(CONTROLLER, resume.holder)
        assertEquals("resume", resume.method)
        assertEquals(0, resume.hookAt)
        assertEquals("this", 4, resume.controller)

        context.unpersonalizeExploreResume(STAND_IN_TIMER, resume)
        assertReloadedOnResume("$CONTROLLER->resume", context, STAND_IN_TIMER, resume, branchedOnto = false)
        val onResume = onResume(context, FRAGMENT).implementation!!.instructions
        assertTrue("onResume itself is left alone", onResume.none { referenceText(it) == UNPERSONALIZE })
    }

    @Test
    fun twoMethodsAskingTheTimerFailThePatch() {
        val context = PatchContexts.of(listOf(fragment(callsController(), callsController(OTHER)), controller(CONTROLLER), controller(OTHER)))
        val failure = assertThrows(PatchException::class.java) { context.findExploreResume(STAND_IN_TIMER) }
        assertTrue(failure.message!!, failure.message!!.contains("$FRAGMENT->onResume asks the timer through 2 methods it calls, expected one"))
    }

    @Test
    fun aControllerAskingTheTimerTwiceFailsThePatch() {
        val context = PatchContexts.of(listOf(fragment(callsController()), controller(CONTROLLER, twice = true)))
        val failure = assertThrows(PatchException::class.java) { context.findExploreResume(STAND_IN_TIMER) }
        assertTrue(failure.message!!, failure.message!!.contains("$CONTROLLER->resume asks the timer 2 times, expected once"))
    }

    /** The relaxed end still wants the flag written, and reads no further than the method goes. */
    @Test
    fun aControllerThatNeverWritesTheFlagFailsThePatch() {
        val context = PatchContexts.of(listOf(fragment(callsController()), controller(CONTROLLER, writes = false)))
        val failure = assertThrows(PatchException::class.java) { context.findExploreResume(STAND_IN_TIMER) }
        assertTrue(failure.message!!, failure.message!!.contains("$CONTROLLER->resume doesn't write the personalized flag after the timer"))
    }

    /** Only a virtual call is followed, the way 454 makes it; a static one leaves onResume asking nothing. */
    @Test
    fun aStaticMethodAskingTheTimerIsNotFollowed() {
        val call = "invoke-static { v0 }, $CONTROLLER->resume($CONTROLLER)V"
        val context = PatchContexts.of(listOf(fragment(call), controller(CONTROLLER, static = true)))
        val failure = assertThrows(PatchException::class.java) { context.findExploreResume(STAND_IN_TIMER) }
        assertTrue(failure.message!!, failure.message!!.contains("$FRAGMENT->onResume asks the timer 0 times, expected once"))
    }

    /** Finds and patches one build, and answers whether a branch landed on the hook's spot. */
    private fun unpersonalizes(bundle: File, label: String): Boolean {
        val context = PatchContexts.of(slice(bundle))

        val timer = context.findExploreTimer()
        val resume = context.findExploreResume(timer)
        val buttons = context.findExploreMenuButtons(resume)
        val branchedOnto = resume.hookAt in asker(context, resume).jumpTargets()
        context.keepExploreUnpersonalized(timer)
        context.unpersonalizeExploreResume(timer, resume)
        context.hideExploreMenuButtons(buttons)

        assertTimerKept("$label ${timer.type}->${timer.method}", context, timer)
        assertReloadedOnResume("$label ${resume.holder}->${resume.method}", context, timer, resume, branchedOnto)
        assertMenuButtonsHidden(label, context, buttons)
        return branchedOnto
    }

    /** The timer starts by asking the extension, and a yes answers false. */
    private fun assertTimerKept(what: String, context: BytecodePatchContext, timer: ExploreTimer) {
        val method = context.mutableClassDefBy(timer.type).methods.single {
            it.name == timer.method && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }
        val code = method.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
        assertEquals("$what: the first call", KEEP_UNPERSONALIZED, referenceText(code[0]))
        assertEquals(
            "$what: the answer",
            listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
            code.subList(1, 5).map { it.opcode },
        )
        assertEquals("$what: one hook", 1, code.count { referenceText(it) == KEEP_UNPERSONALIZED })
        assertTrue("$what: the timer's own reload is still there", code.any { referenceText(it) == timer.reload.toString() })
    }

    /**
     * In front of the personalized flag read: the flag, the switch, the timer, its fragment, the grid
     * and the state are each checked, with no menu flag asked, then the reload is counted, the flag
     * cleared and the same static reload the menu's row makes is called with false. Every check
     * jumps to the original read, and a branch that landed there lands on the hook now.
     */
    private fun assertReloadedOnResume(
        what: String,
        context: BytecodePatchContext,
        timer: ExploreTimer,
        resume: ExploreResume,
        branchedOnto: Boolean,
    ) {
        val method = asker(context, resume)
        val code = method.implementation!!.instructions.toList()
        val asks = code.indices.filter { referenceText(code[it]) == UNPERSONALIZE }
        assertEquals("$what: one ask", 1, asks.size)
        val start = asks.single() - 2
        assertEquals("$what: the hook starts where the flag was read", resume.hookAt, start)
        assertEquals("$what: the hook reads the flag first", Opcode.IGET_BOOLEAN, code[start].opcode)
        assertEquals("$what: the personalized flag", resume.personalized.toString(), referenceText(code[start]))
        assertEquals("$what: one count", 1, code.count { referenceText(it) == RELOADED })

        val counted = code.indexOfFirst { referenceText(it) == RELOADED }
        val clear = code[counted + 2]
        assertEquals("$what: the flag is cleared", Opcode.IPUT_BOOLEAN, clear.opcode)
        assertEquals("$what: the cleared flag", resume.personalized.toString(), referenceText(clear))
        assertEquals("$what: on the controller", resume.controller, (clear as TwoRegisterInstruction).registerB)
        val reload = code[counted + 3]
        assertEquals("$what: the reload", timer.reload.toString(), referenceText(reload))
        assertEquals("$what: with false", (clear as TwoRegisterInstruction).registerA, (reload as FiveRegisterInstruction).registerE)
        val asked = (start until counted).filter { code[it].opcode == Opcode.CONST_WIDE }
        assertTrue("$what: doesn't ask the menu flag, which is EU-only (#311)", asked.isEmpty())

        val keep = counted + 4
        assertEquals("$what: the label's nop", Opcode.NOP, code[keep].opcode)
        val original = code[keep + 1]
        assertEquals("$what: Instagram's own read follows", Opcode.IGET_BOOLEAN, original.opcode)
        assertEquals("$what: of the personalized flag", resume.personalized.toString(), referenceText(original))
        assertEquals("$what: on the controller", resume.controller, (original as TwoRegisterInstruction).registerB)

        val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        fun target(at: Int) = addresses.indexOf(addresses[at] + (code[at] as OffsetInstruction).codeOffset)
        val checks = (start until counted).filter { code[it].opcode == Opcode.IF_EQZ }
        assertEquals("$what: the checks", 6, checks.size)
        for (at in checks) assertEquals("$what: the check at $at leaves Explore alone", keep, target(at))
        if (branchedOnto) assertTrue("$what: the branch onto the read lands on the hook", start in method.jumpTargets())
    }

    /** The newer bar hands its menu button to the extension before keeping it; the older bar's maker asks first. */
    private fun assertMenuButtonsHidden(label: String, context: BytecodePatchContext, buttons: ExploreMenuButtons) {
        val holder = context.mutableClassDefBy(buttons.fragment).methods.single {
            it.name == buttons.method && it.parameterTypes.map(CharSequence::toString) == buttons.parameters
        }
        val code = holder.implementation!!.instructions.toList()
        val hands = code.indices.filter { referenceText(code[it]) == MENU_BUTTON }
        val what = "$label ${buttons.fragment}->${buttons.method}"
        assertEquals("$what: one hand-over", 1, hands.size)
        val hand = code[hands.single()] as FiveRegisterInstruction
        assertEquals("$what: the button", buttons.view, hand.registerC)
        val kept = code[hands.single() + 1]
        assertEquals("$what: then kept", Opcode.IPUT_OBJECT, kept.opcode)
        assertEquals("$what: the same button", buttons.view, (kept as TwoRegisterInstruction).registerA)

        val maker = context.mutableClassDefBy(buttons.bar).methods.single {
            it.name == buttons.maker && it.parameterTypes.map(CharSequence::toString) == MAKER_PARAMETERS
        }
        val made = maker.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
        val bar = "$label ${buttons.bar}->${buttons.maker}"
        assertEquals("$bar: the menu's icon", buttons.menuIcon, (made[0] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$bar: then asks", OLD_MENU_BUTTON, referenceText(made[1]))
        val ask = made[1] as FiveRegisterInstruction
        assertEquals("$bar: with the icon asked for", buttons.icon, ask.registerC)
        assertEquals("$bar: and the menu's", (made[0] as OneRegisterInstruction).registerA, ask.registerD)
        assertEquals("$bar: a yes skips the button", listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), made.subList(2, 5).map { it.opcode })
    }

    private fun onResume(context: BytecodePatchContext, fragment: String): Method =
        context.mutableClassDefBy(fragment).methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() }

    /** The method that asks the timer, onResume or the controller's method it calls. */
    private fun asker(context: BytecodePatchContext, resume: ExploreResume): Method =
        context.mutableClassDefBy(resume.holder).methods.single {
            it.name == resume.method && it.parameterTypes.map(CharSequence::toString) == resume.parameters
        }

    /**
     * The timer, the fragment and the older bar, and the classes the fragment's onResume calls into
     * (454's 386014704 asks the timer in LX/0D2f;->A05), with the string pools they ask.
     */
    private fun slice(bundle: File): List<ClassDef> {
        val found = (
            FixtureDex.classesLoading(bundle, DSA_EXPIRED) +
                FixtureDex.classesLoading(bundle, EXPLORE_FRAGMENT_TAG) +
                FixtureDex.classes(bundle, setOf(EXPLORE_ACTION_BAR)).values
            ).distinctBy { it.type }
        val called = found.flatMap { it.methods }.filter { it.name == "onResume" && it.parameterTypes.isEmpty() }
            .flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .filter { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
            .mapNotNull { ((it as ReferenceInstruction).reference as? MethodReference)?.definingClass }
            .toSet() - found.map { it.type }.toSet()
        val classes = found + FixtureDex.classes(bundle, called).values
        return FixtureDex.withStringPools(bundle, classes).distinctBy { it.type }
    }

    private fun callsController(type: String = CONTROLLER) = "invoke-virtual { v0 }, $type->resume()V"

    /**
     * A stand-in Explore fragment: an onResume reading its controller and making [calls] with it, and
     * the static reload, which reads the grid before anything else.
     */
    private fun fragment(vararg calls: String): ClassDef {
        val onResume = MutableMethod(
            ImmutableMethod(FRAGMENT, "onResume", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(2, emptyList(), null, null)),
        )
        onResume.addInstructions(0, (listOf("iget-object v0, v1, $FRAGMENT->controller:$CONTROLLER") + calls + "return-void").joinToString("\n"))
        val reload = MutableMethod(
            ImmutableMethod(
                FRAGMENT, "reload", listOf(STATE, FRAGMENT, "Z").map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        )
        reload.addInstructions(
            0,
            """
                iget-object v0, v2, $FRAGMENT->grid:Ljava/lang/Object;
                if-eqz v0, :end
                :end
                return-void
            """,
        )
        return ImmutableClassDef(
            FRAGMENT, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod.of(onResume), ImmutableMethod.of(reload)),
        )
    }

    /**
     * A stand-in controller whose resume(), with `this` in v4, reads the personalized flag, asks the
     * menu flag and the timer, and sets the flag, as 386014704's LX/0D2f;->A05 does from its 37th
     * instruction on.
     */
    private fun controller(type: String, twice: Boolean = false, writes: Boolean = true, static: Boolean = false): ClassDef {
        val flags = AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0
        val parameters = if (static) listOf(ImmutableMethodParameter(type, null, null)) else emptyList()
        val method = MutableMethod(
            ImmutableMethod(type, "resume", parameters, "V", flags, null, null, ImmutableMethodImplementation(5, emptyList(), null, null)),
        )
        method.addInstructions(
            0,
            listOfNotNull(
                "iget-boolean v0, v4, $type->personalized:Z",
                "if-nez v0, :done",
                "invoke-static { v4 }, $FLAGS->source(Ljava/lang/Object;)Ljava/lang/Object;",
                "move-result-object v1",
                "const-wide v2, 0x1234L",
                "invoke-static { v1, v2, v3 }, $FLAGS->check(Ljava/lang/Object;J)Z",
                "move-result v0",
                "if-eqz v0, :done",
                "iget-object v0, v4, $type->timer:$TIMER",
                "if-eqz v0, :set",
                "invoke-virtual { v0 }, $TIMER->expired()Z",
                "move-result v0",
                "if-eqz v0, :done",
                ":set",
                "const/4 v0, 0x1",
                if (writes) "iput-boolean v0, v4, $type->personalized:Z" else null,
                if (twice) "iget-object v1, v4, $type->timer:$TIMER" else null,
                if (twice) "invoke-virtual { v1 }, $TIMER->expired()Z" else null,
                ":done",
                "return-void",
            ).joinToString("\n"),
        )
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod.of(method)))
    }

    private fun declaredBundle(): File =
        Fixtures.files { it.extension == "apks" && it.name.contains("-${Fixtures.VERSION}-") }.first()

    /** [classDef] with the instruction at [index] of [name] taking [parameters] swapped for nops of the same size. */
    private fun withoutInstruction(classDef: ClassDef, name: String, parameters: List<String>, index: Int): ClassDef {
        val methods = classDef.methods.map { method ->
            val implementation = method.implementation
            val other = method.name != name || method.parameterTypes.map(CharSequence::toString) != parameters
            if (other || implementation == null) return@map method
            val code = implementation.instructions.toList()
            val nops = List(code[index].codeUnits) { ImmutableInstruction10x(Opcode.NOP) }
            val changed = code.subList(0, index) + nops + code.subList(index + 1, code.size)
            ImmutableMethod(
                method.definingClass, method.name, method.parameters, method.returnType, method.accessFlags,
                method.annotations, method.hiddenApiRestrictions,
                ImmutableMethodImplementation(implementation.registerCount, changed, implementation.tryBlocks, null),
            )
        }
        return ImmutableClassDef(
            classDef.type, classDef.accessFlags, classDef.superclass, classDef.interfaces, classDef.sourceFile,
            classDef.annotations, classDef.fields, methods,
        )
    }

    private fun referenceText(instruction: Instruction) = (instruction as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        val MAKER_PARAMETERS = listOf("Landroid/view/View\$OnClickListener;", "I", "I", "I", "I")
        const val FRAGMENT = "Lfixture/Fragment;"
        const val CONTROLLER = "Lfixture/Controller;"
        const val OTHER = "Lfixture/Other;"
        const val TIMER = "Lfixture/Timer;"
        const val STATE = "Lfixture/State;"
        const val FLAGS = "Lfixture/Flags;"

        /** A timer as [findExploreTimer] would describe one on the stand-in classes. */
        val STAND_IN_TIMER = ExploreTimer(
            TIMER, "expired", FRAGMENT, ImmutableMethodReference(FRAGMENT, "reload", listOf(STATE, FRAGMENT, "Z"), "V"),
            ImmutableFieldReference(TIMER, "state", STATE), ImmutableFieldReference(TIMER, "fragment", FRAGMENT),
        )
    }
}

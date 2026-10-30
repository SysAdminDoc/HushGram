/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.topbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep Home's top bar switches the status method on and puts Home's list of header views through
 * the extension right before the scroll controller gets it (#408).
 */
class KeepHomeTopBarPatchTest {
    /** The hook the patch writes is in the HomeTopBar the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HOME_TOP_BAR).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SLIDING_VIEWS is not in the extension: $declared", SLIDING_VIEWS.substringAfter("->") in declared)
    }

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + classes())
        keepHomeTopBarPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, HOME_TOP_BAR_STATUS).code()
        assertEquals(1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val other = context.method(SETTINGS_STATUS, "hideCaptions").code()
        assertEquals("another patch's status is still off", 0, (other.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    @Test
    fun theHandOffIsFound() {
        val code = PatchContexts.of(classes()).classDefBy(HOME).methods.single().code()
        val found = PatchContexts.of(classes()).findHeaderHandOff()

        assertEquals(HOME, found.type)
        assertEquals("A0g", found.name)
        assertEquals("(Z)V", found.shape)
        assertEquals(code.indexOfFirst { it.opcode == Opcode.INVOKE_VIRTUAL && it.reference().toString().startsWith(CONTROLLER) }, found.call)
        assertEquals(0, found.list)
    }

    /** The list goes through the hook right before the call, and the call gets the answer in the same register. */
    @Test
    fun theListGoesThroughTheHookBeforeTheCall() {
        val context = PatchContexts.of(classes())
        val found = context.findHeaderHandOff()
        val before = context.home(found).size

        found.write(context)

        val code = context.home(found)
        assertEquals("two instructions in front of the call", before + 2, code.size)
        assertHooked("stand-in", code, 0)
    }

    /** A range call keeps the list third, after the controller and the listener. */
    @Test
    fun aRangeCallIsHookedOnItsThirdRegister() {
        val context = PatchContexts.of(classes(variant = "range"))
        val found = context.findHeaderHandOff()
        assertEquals(4, found.list)

        found.write(context)

        assertHooked("range", context.home(found), 4)
    }

    @Test
    fun noHandOffFailsThePatch() = refused("found 0", classes(leaveOut = true))

    @Test
    fun twoHandOffsFailThePatch() = refused("found 2", classes(second = true))

    @Test
    fun aJumpToTheCallFailsThePatch() = refused("jumps to the hand-off", classes(variant = "jump"))

    @Test
    fun aListMadeEarlierFailsThePatch() = refused("doesn't make the header's list right before", classes(variant = "late"))

    /** The same call shape in a class without the argument's name isn't the header's hand-off. */
    @Test
    fun aSetupWithoutTheArgumentIsLeftAlone() {
        val context = PatchContexts.of(classes())
        val elsewhere = context.classDefBy(ELSEWHERE).methods.single().code().map { it.opcode }

        context.findHeaderHandOff().write(context)

        assertEquals(elsewhere, context.mutableClassDefBy(ELSEWHERE).methods.single().code().map { it.opcode })
    }

    /** On each declared build the patch applies to Home's fragment. */
    @Test
    fun eachDeclaredBuildKeepsItsTopBar() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                keepsTopBar(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildKeepsItsTopBarToo() {
        var checked = 0
        for (base in Fixtures.otherBuilds()) {
            keepsTopBar(base, base.parentFile.name)
            checked++
        }
        assertEquals("the builds besides the declared one", Fixtures.OTHER_BUILDS, checked)
    }

    /**
     * Slices the fixture the way the finder reads it, every class loading the argument's name with
     * the string pools they call, and runs the whole patch over it: one hand-off, hooked once.
     */
    private fun keepsTopBar(bundle: File, label: String) {
        val holders = FixtureDex.classesHolding(bundle, NAVBAR_HIDDEN_ARGUMENT)
        assertTrue("$label: nothing holds $NAVBAR_HIDDEN_ARGUMENT", holders.isNotEmpty())
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + FixtureDex.withStringPools(bundle, holders))
        val found = context.findHeaderHandOff()

        keepHomeTopBarPatch.execute(context)

        val status = context.method(SETTINGS_STATUS, HOME_TOP_BAR_STATUS).code()
        assertEquals("$label: status", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        assertHooked("$label ${found.type}->${found.name}", context.home(found), found.list)
        val hooked = holders.flatMap { holder ->
            context.mutableClassDefBy(holder.type).methods.filter { method ->
                method.code().any { it.reference()?.toString() == SLIDING_VIEWS }
            }.map { "${it.definingClass}->${it.name}" }
        }
        assertEquals("$label: only the hand-off is hooked", listOf("${found.type}->${found.name}"), hooked)
    }

    /** One hook, its answer back in the list's register, then the controller's setup reading that register. */
    private fun assertHooked(what: String, code: List<Instruction>, list: Int) {
        val calls = code.indices.filter { code[it].reference()?.toString() == SLIDING_VIEWS }
        assertEquals("$what: one hook", 1, calls.size)
        val at = calls.single()
        val hook = code[at] as RegisterRangeInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
        assertEquals("$what: with the list alone", list to 1, hook.startRegister to hook.registerCount)
        assertEquals("$what: its answer", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
        assertEquals("$what: back in the list's register", list, (code[at + 1] as OneRegisterInstruction).registerA)
        val setup = code[at + 2]
        assertTrue("$what: the controller's setup is next", setup.opcode == Opcode.INVOKE_VIRTUAL || setup.opcode == Opcode.INVOKE_VIRTUAL_RANGE)
        val read = when (setup) {
            is FiveRegisterInstruction -> setup.registerE
            is RegisterRangeInstruction -> setup.startRegister + 2
            else -> -1
        }
        assertEquals("$what: reading the hook's answer", list, read)
    }

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findHeaderHandOff() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private fun BytecodePatchContext.home(found: HeaderHandOff): List<Instruction> =
        mutableClassDefBy(found.type).methods.single { it.name == found.name }.implementation!!.instructions.toList()

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val HOME = "Lfixture/HomeFragment;"
        const val SECOND = "Lfixture/OtherHome;"
        const val ELSEWHERE = "Lfixture/Profile;"
        const val CONTROLLER = "Lfixture/Controller;"
        const val SETUP = "$CONTROLLER->setUp(Lfixture/Listener;Ljava/util/List;FZ)V"

        /**
         * Shaped like 453's LX/04rY->A0g(Z): reads the argument by name, wraps the header in a list
         * and hands it straight to the controller. "range" makes the call a range with the list in
         * v4, "jump" branches to the call, "late" loads a constant between the list and the call,
         * "plain" leaves the argument out.
         */
        fun smali(variant: String): String {
            val argument = if (variant == "plain") "" else "const-string v0, \"$NAVBAR_HIDDEN_ARGUMENT\""
            val branch = if (variant == "jump") "if-eqz p1, :call" else ""
            val early = if (variant == "late") "" else "const/4 v5, 0x1"
            val late = if (variant == "late") "const/4 v5, 0x1" else ""
            val list = if (variant == "range") "v4" else "v0"
            val call = if (variant == "range") {
                "invoke-virtual/range {v2 .. v6}, $SETUP"
            } else {
                "invoke-virtual {v2, v3, v0, v4, v5}, $SETUP"
            }
            val rangeFloat = if (variant == "range") "const/4 v5, 0x0" else ""
            val rangeFlag = if (variant == "range") "const/4 v6, 0x1" else ""
            return """
                $argument
                iget-object v1, p0, $HOME->header:Landroid/view/View;
                iget-object v2, p0, $HOME->controller:$CONTROLLER
                iget-object v3, p0, $HOME->listener:Lfixture/Listener;
                const/4 v4, 0x0
                $early
                $rangeFloat
                $rangeFlag
                $branch
                invoke-static {v1}, Lfixture/Lists;->of($OBJECT)Ljava/util/List;
                move-result-object $list
                $late
                :call
                $call
                return-void
            """.trimIndent()
        }

        fun home(owner: String, variant: String): Method {
            val method = MutableMethod(
                ImmutableMethod(
                    owner, "A0g", listOf(ImmutableMethodParameter("Z", null, null)), "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(9, emptyList(), null, null),
                ),
            )
            method.addInstructionsWithLabels(0, smali(variant))
            return ImmutableMethod.of(method)
        }

        fun classes(leaveOut: Boolean = false, second: Boolean = false, variant: String = "ok"): List<ClassDef> {
            fun fragment(type: String, method: Method) = ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, null, listOf(method),
            )
            val home = if (leaveOut) emptyList() else listOf(fragment(HOME, home(HOME, variant)))
            val other = if (second) listOf(fragment(SECOND, home(SECOND, "ok"))) else emptyList()
            return home + other + fragment(ELSEWHERE, home(ELSEWHERE, "plain")) +
                ImmutableClassDef("Lfixture/Unrelated;", AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, emptyList())
        }
    }
}

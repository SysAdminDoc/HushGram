/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

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
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HideShareSheetGroupHookTest {
    /** Both hooks the patch writes are in the ShareSheet the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(HIDE_GROUP_BUTTON, HIDE_GROUP_ACTION, HIDE_GROUP_SEND)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** The build returns before inflating its stub on a yes, and the reveal turns the search box's action off after showing it. */
    @Test
    fun theBuildAndTheRevealAreGuarded() {
        val context = PatchContexts.of(sheetClasses())

        val sites = context.findShareSheet()
        assertEquals("A0N", sites.build.name)
        assertEquals(listOf("A0D"), sites.reveals.map { it.method.name })
        context.guardGroupButton(sites.build)
        context.guardGroupAction(sites.reveals)

        val methods = context.mutableClassDefBy(SHARE_SHEET).methods
        assertBuildGuarded("stand-in", methods.single { it.name == sites.build.name })
        assertRevealGuarded("stand-in", methods.single { it.name == "A0D" }, 1)
    }

    /**
     * 451's 385713073 copies the reveal into onActivityResult and onViewCreated instead of keeping a
     * method of its own: each copy is guarded after its own call.
     */
    @Test
    fun aRevealCopiedIntoTwoMethodsIsGuardedInBoth() {
        val context = PatchContexts.of(sheetClasses(inlined = true))

        val sites = context.findShareSheet()
        assertEquals(listOf("onActivityResult", "onViewCreated"), sites.reveals.map { it.method.name }.sorted())
        context.guardGroupAction(sites.reveals)

        val methods = context.mutableClassDefBy(SHARE_SHEET).methods
        assertRevealGuarded("onActivityResult", methods.single { it.name == "onActivityResult" }, 1)
        assertRevealGuarded("onViewCreated", methods.single { it.name == "onViewCreated" }, 1)
    }

    /** A box shown its action without the button being tested first isn't the reveal. */
    @Test
    fun aRevealThatNeverTestsTheButtonFailsThePatch() {
        val context = PatchContexts.of(sheetClasses(testsButton = false))
        assertThrows(PatchException::class.java) { context.findShareSheet() }
    }

    @Test
    fun aSecondMethodKeepingTheButtonFailsThePatch() {
        val context = PatchContexts.of(sheetClasses(secondBuild = true))
        assertThrows(PatchException::class.java) { context.findShareSheet() }
    }

    /** A build that no longer inflates a stub it takes is an update the patch hasn't seen. */
    @Test
    fun aBuildInflatingNoStubFailsThePatch() {
        val context = PatchContexts.of(sheetClasses(inflates = false))
        assertThrows(PatchException::class.java) { context.findShareSheet() }
    }

    /** With no reveal showing the search box's action, the button could still come back that way. */
    @Test
    fun noRevealFailsThePatch() {
        val context = PatchContexts.of(sheetClasses(reveals = false))
        assertThrows(PatchException::class.java) { context.findShareSheet() }
    }

    @Test
    fun aSearchBoxWithoutItsSettersFailsThePatch() {
        val context = PatchContexts.of(sheetClasses(setters = listOf(ACTION_VISIBLE)))
        assertThrows(PatchException::class.java) { context.findShareSheet() }
    }

    @Test
    fun aSheetWithoutTheSearchBoxFieldFailsThePatch() {
        val context = PatchContexts.of(sheetClasses(searchBoxField = false))
        assertThrows(PatchException::class.java) { context.findShareSheet() }
    }

    /** The build's guard needs three registers of its own for the search box, its layout and the width. */
    @Test
    fun aBuildWithTwoRegistersOfItsOwnFailsThePatch() {
        val context = PatchContexts.of(sheetClasses(buildRegisters = 6))
        assertThrows(PatchException::class.java) { context.findShareSheet() }
    }

    /** The reveal's guard needs a free register of its own besides the search box's, for its answer and its false. */
    @Test
    fun aRevealWithOneRegisterOfItsOwnFailsThePatch() {
        val context = PatchContexts.of(sheetClasses(revealRegisters = 2))
        assertThrows(PatchException::class.java) { context.findShareSheet() }
    }

    /** The show asks the extension first, and on a yes calls the hide with its session and returns. */
    @Test
    fun theGroupSendIsGuarded() {
        val context = PatchContexts.of(composerClasses())

        val send = context.findGroupSend()
        assertEquals("A02", send.show.name)
        assertEquals("A00", send.hide.name)
        assertEquals(3, send.session)
        context.guardGroupSend(send)

        assertGroupSendGuarded("stand-in", context.mutableClassDefBy(COMPOSER).methods.single { it.name == "A02" }, "A00")
    }

    @Test
    fun aSecondMethodSettingUpTheGroupSendFailsThePatch() {
        val context = PatchContexts.of(composerClasses(secondShow = true))
        assertThrows(PatchException::class.java) { context.findGroupSend() }
    }

    /** Without the hide, a yes would leave the button as it was rather than put it away. */
    @Test
    fun noGroupSendHideFailsThePatch() {
        val context = PatchContexts.of(composerClasses(hides = false))
        assertThrows(PatchException::class.java) { context.findGroupSend() }
    }

    @Test
    fun aShowWithoutTheSessionFailsThePatch() {
        val context = PatchContexts.of(composerClasses(showTakesSession = false))
        assertThrows(PatchException::class.java) { context.findGroupSend() }
    }

    @Test
    fun noShareSheetFailsThePatch() {
        val context = PatchContexts.of(sheetClasses().filter { it.type != SHARE_SHEET })
        assertThrows(PatchException::class.java) { context.findShareSheet() }
    }

    /**
     * In each declared build the share sheet's build and reveals, and the bar's send-as-group show,
     * are found and guarded. On 451's 385713073 they're A0L, the reveal copied into onActivityResult
     * and onViewCreated, and the bar's show.
     */
    @Test
    fun eachDeclaredBuildGuardsTheNewGroupButton() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildGuardsTheNewGroupButtonToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type in listOf(SHARE_SHEET, SEARCH_BOX, COMPOSER)) holders += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(holders)

        val sites = context.findShareSheet()
        val send = context.findGroupSend()
        context.guardGroupButton(sites.build)
        context.guardGroupAction(sites.reveals)
        context.guardGroupSend(send)

        fun method(site: SheetMethod) = context.mutableClassDefBy(site.type).methods
            .single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
        assertBuildGuarded("${label} ${sites.build.name}", method(sites.build))
        assertTrue("$label: no reveal", sites.reveals.isNotEmpty())
        sites.reveals.groupBy { it.method.name to it.method.parameters }.forEach { (_, inMethod) ->
            val reveal = inMethod.first().method
            assertRevealGuarded("${label} ${reveal.name}", method(reveal), inMethod.size)
        }
        val guarded = context.mutableClassDefBy(SHARE_SHEET).methods.sumOf { m -> m.implementation!!.instructions.count { it.names(HIDE_GROUP_ACTION) } }
        assertEquals("$label: one guard per reveal and none elsewhere", sites.reveals.size, guarded)
        assertGroupSendGuarded("${label} ${send.show.name}", method(send.show), send.hide.name)
    }

    /**
     * The method opens with the extension call and its test, then on a yes reads the search box,
     * gives it the full width and returns before the stub is inflated; one call in all.
     */
    private fun assertBuildGuarded(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: hooks", 1, code.count { it.names(HIDE_GROUP_BUTTON) })
        assertEquals("$what: the call", HIDE_GROUP_BUTTON, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the test", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the tested register", (code[1] as OneRegisterInstruction).registerA, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the search box", "$SHARE_SHEET->$STICKY_SEARCH_BOX:$SEARCH_BOX", (code[3] as ReferenceInstruction).reference.toString())
        assertEquals("$what: its params", "Landroid/view/View;->getLayoutParams()Landroid/view/ViewGroup\$LayoutParams;", (code[5] as ReferenceInstruction).reference.toString())
        assertEquals("$what: full width", Opcode.CONST_4, code[8].opcode)
        assertEquals("$what: the width", "Landroid/view/ViewGroup\$LayoutParams;->width:I", (code[9] as ReferenceInstruction).reference.toString())
        assertEquals("$what: set back", "Landroid/view/View;->setLayoutParams(Landroid/view/ViewGroup\$LayoutParams;)V", (code[10] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the early return", Opcode.RETURN_VOID, code[11].opcode)
        assertEquals("$what: no inflate before it", -1, code.take(12).indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString()?.contains("inflate") == true })
    }

    /**
     * Right after each of the method's [sites] calls showing the search box's action: the extension
     * call and its test, then on a yes false into the answer's register, and the same box's action
     * hidden and turned off with it; one guard per site. A no jumps to the instruction right after
     * the guard in this method, not to its place in the compiled snippet, which the dex would read
     * as an offset from the method's start.
     */
    private fun assertRevealGuarded(what: String, method: Method, sites: Int) {
        val code = method.implementation!!.instructions.toList()
        val hooks = code.indices.filter { code[it].names(HIDE_GROUP_ACTION) }
        assertEquals("$what: hooks", sites, hooks.size)
        for (hook in hooks) {
            val shown = code[hook - 1]
            assertTrue("$what: the guard follows the box's show", shown.names("$SEARCH_BOX->$ACTION_VISIBLE(Z)V"))
            val box = (shown as FiveRegisterInstruction).registerC
            val answer = (code[hook + 1] as OneRegisterInstruction).registerA
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[hook + 1].opcode)
            assertTrue("$what: the answer isn't the box", answer != box)
            assertEquals("$what: the test", Opcode.IF_EQZ, code[hook + 2].opcode)
            assertEquals("$what: the tested register", answer, (code[hook + 2] as OneRegisterInstruction).registerA)
            assertEquals("$what: false", Opcode.CONST_4, code[hook + 3].opcode)
            for ((offset, setter) in listOf(4 to ACTION_VISIBLE, 5 to ACTION_ENABLED)) {
                val call = code[hook + offset]
                assertTrue("$what: $setter", call.names("$SEARCH_BOX->$setter(Z)V"))
                assertEquals("$what: $setter's box and false", listOf(box, answer), (call as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
            }
            val skip = (code[hook + 2] as BuilderOffsetInstruction).target.location
            assertEquals("$what: a no skips to the guard's end", hook + 6, skip.index)
            assertTrue("$what: the guard's end is its nop", skip.instruction === code[hook + 6] && code[hook + 6].opcode == Opcode.NOP)
        }
    }

    /** The method opens with the extension call and its test, then on a yes calls the hide with this and the session and returns; one call in all. */
    private fun assertGroupSendGuarded(what: String, method: Method, hide: String) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: hooks", 1, code.count { it.names(HIDE_GROUP_SEND) })
        assertEquals("$what: the call", HIDE_GROUP_SEND, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the test", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: this", Opcode.MOVE_OBJECT_FROM16, code[3].opcode)
        assertEquals("$what: the session", Opcode.MOVE_OBJECT_FROM16, code[4].opcode)
        assertEquals("$what: the hide", "$COMPOSER->$hide($USER_SESSION)V", (code[5] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the early return", Opcode.RETURN_VOID, code[6].opcode)
    }

    private fun Instruction.names(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private companion object {
        const val VIEW = "Landroid/view/View;"
        const val VIEW_STUB = "Landroid/view/ViewStub;"

        val INSTANCE = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val PRIVATE = AccessFlags.PRIVATE.value or AccessFlags.FINAL.value

        val BUTTON = ImmutableFieldReference(SHARE_SHEET, GROUP_BUTTON, VIEW)
        val BOX = ImmutableFieldReference(SHARE_SHEET, STICKY_SEARCH_BOX, SEARCH_BOX)

        const val HOLDER = "Lfixture/ComposerViews;"

        fun parameters(vararg types: String) = types.map { ImmutableMethodParameter(it, null, null) }

        fun method(name: String, parameters: List<ImmutableMethodParameter>, registers: Int, code: List<Instruction>, type: String = SHARE_SHEET) =
            ImmutableMethod(type, name, parameters, "V", PRIVATE, null, null, ImmutableMethodImplementation(registers, code, null, null))

        /**
         * Shaped like 449's share sheet: a build inflating the stub it takes and keeping the button,
         * a reveal reading the button and showing the search box's action, and onViewCreated
         * reading the button too. Beside it the search box with its two public setters.
         */
        fun sheetClasses(
            secondBuild: Boolean = false,
            inflates: Boolean = true,
            reveals: Boolean = true,
            setters: List<String> = listOf(ACTION_VISIBLE, ACTION_ENABLED),
            searchBoxField: Boolean = true,
            revealRegisters: Int = 3,
            buildRegisters: Int = 9,
            inlined: Boolean = false,
            testsButton: Boolean = true,
        ): List<ClassDef> {
            // A0N(ViewStub, boolean, boolean), in nine registers on 449: this is v5, the stub v6.
            val build = listOfNotNull(
                if (inflates) {
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 6, 0, 0, 0, 0, ImmutableMethodReference(VIEW_STUB, "inflate", null, VIEW))
                } else {
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 6, 0, 0, 0, 0, ImmutableMethodReference(VIEW_STUB, "getParent", null, "Landroid/view/ViewParent;"))
                },
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),
                ImmutableInstruction22c(Opcode.IPUT_OBJECT, 4, 5, BUTTON),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            // A0D() with this in [self]: the button tested for null (a button of its own returns at
            // once), then the search box, true, and the setter showing its action.
            fun reveal(self: Int) = listOfNotNull(
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, self, BUTTON),
                if (testsButton) ImmutableInstruction21t(Opcode.IF_EQZ, 1, 3) else null,
                if (testsButton) ImmutableInstruction10x(Opcode.RETURN_VOID) else null,
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, self, BOX),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 1),
                if (reveals) {
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, ImmutableMethodReference(SEARCH_BOX, ACTION_VISIBLE, listOf("Z"), "V"))
                } else {
                    null
                },
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            val created = listOf(
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, BUTTON),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            // Inlined as 451's 385713073 has it: the same reveal in onActivityResult and onViewCreated, this in v2 of each.
            val revealing = if (inlined) {
                listOf(
                    method("onActivityResult", parameters("I", "I", "Landroid/content/Intent;"), 6, reveal(2)),
                    method("onViewCreated", parameters(VIEW, "Landroid/os/Bundle;"), 5, reveal(2)),
                )
            } else {
                listOf(
                    method("A0D", emptyList(), revealRegisters, reveal(revealRegisters - 1)),
                    method("onViewCreated", parameters(VIEW, "Landroid/os/Bundle;"), 3, created),
                )
            }
            val methods = listOfNotNull(
                method("A0N", parameters(VIEW_STUB, "Z", "Z"), buildRegisters, build),
                if (secondBuild) method("A0M", parameters(VIEW_STUB, "Z", "Z"), 9, build) else null,
            ) + revealing
            val fields = listOfNotNull(
                ImmutableField(SHARE_SHEET, GROUP_BUTTON, VIEW, AccessFlags.PUBLIC.value, null, null, null),
                if (searchBoxField) ImmutableField(SHARE_SHEET, STICKY_SEARCH_BOX, SEARCH_BOX, AccessFlags.PUBLIC.value, null, null, null) else null,
            )
            val setterMethods = setters.map { name ->
                ImmutableMethod(
                    SEARCH_BOX, name, parameters("Z"), "V", INSTANCE, null, null,
                    ImmutableMethodImplementation(2, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
                )
            }
            return listOf(
                ImmutableClassDef(SHARE_SHEET, INSTANCE, "Ljava/lang/Object;", null, null, null, fields, methods),
                ImmutableClassDef(SEARCH_BOX, INSTANCE, "Ljava/lang/Object;", null, null, null, null, setterMethods),
            )
        }

        /**
         * Shaped like 449's message bar binder: A02(List, boolean, UserSession) in 21 registers, setting
         * up the send-as-group button and showing it, and A00(UserSession) reading that button to hide it.
         */
        fun composerClasses(secondShow: Boolean = false, hides: Boolean = true, showTakesSession: Boolean = true): List<ClassDef> {
            val button = ImmutableFieldReference(HOLDER, "A04", GROUP_SEND_BUTTON)
            val show = listOf(
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, button),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(GROUP_SEND_BUTTON, "A00", null, "V")),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            val hide = listOf(
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 2, button),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            val showParameters = if (showTakesSession) parameters("Ljava/util/List;", "Z", USER_SESSION) else parameters("Ljava/util/List;", "Z", "Ljava/lang/Object;")
            val methods = listOfNotNull(
                method("A02", showParameters, 21, show, COMPOSER),
                if (secondShow) method("A03", showParameters, 21, show, COMPOSER) else null,
                if (hides) method("A00", parameters(USER_SESSION), 3, hide, COMPOSER) else null,
                method("A01", parameters(USER_SESSION, "Ljava/lang/String;"), 3, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), COMPOSER),
            )
            return listOf(ImmutableClassDef(COMPOSER, INSTANCE, "Ljava/lang/Object;", null, null, null, null, methods))
        }
    }
}
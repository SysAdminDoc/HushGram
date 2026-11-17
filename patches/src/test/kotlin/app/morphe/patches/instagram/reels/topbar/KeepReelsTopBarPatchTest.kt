/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.topbar

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
 * Keep the Reels top bar switches the status method on and puts the top bar's keep-in-place flag
 * through the extension as its scroll callback starts (#411).
 */
class KeepReelsTopBarPatchTest {
    /** The hook the patch writes is in the ReelsTopBarScroll the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REELS_TOP_BAR_SCROLL).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$KEEP_IN_PLACE is not in the extension: $declared", KEEP_IN_PLACE.substringAfter("->") in declared)
    }

    /** The patch switches its status method on and leaves the others as they were. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + classes())
        keepReelsTopBarPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, KEEP_REELS_TOP_BAR_STATUS).code()
        assertEquals(1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val other = context.method(SETTINGS_STATUS, "hideCaptions").code()
        assertEquals("another patch's status is still off", 0, (other.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /** With 30 registers and nine parameters, `this` is v20 and the second flag, p6, is v26. */
    @Test
    fun theScrollIsFound() {
        val found = PatchContexts.of(classes()).findReelScroll()

        assertEquals(ACTION_BAR, found.type)
        assertEquals("A07", found.name)
        assertEquals(SCROLL_PARAMETERS, found.parameters)
        assertEquals(26, found.flag)
    }

    /** A copy of a copy of the flag handed to update still counts. */
    @Test
    fun aCopyOfACopyIsHandedOverToo() {
        assertEquals(26, PatchContexts.of(classes(variant = "chain")).findReelScroll().flag)
    }

    /** The flag goes through the hook before anything else, its answer back in the same register. */
    @Test
    fun theFlagGoesThroughTheHookFirst() {
        val context = PatchContexts.of(classes())
        val found = context.findReelScroll()
        val before = context.scroll(found).map { it.opcode }

        found.write(context)

        val code = context.scroll(found)
        assertHooked("stand-in", code, found.flag)
        assertEquals("the rest as it was", before, code.drop(2).map { it.opcode })
    }

    @Test
    fun noReelScrollFailsThePatch() = refused("five flags, found 0", classes(leaveOut = true))

    /** A scroll shaped method outside the top bar doesn't count, and a second one in it is refused. */
    @Test
    fun twoReelScrollsFailThePatch() = refused("five flags, found 2", classes(second = true))

    @Test
    fun aChangedScrollFailsThePatch() = refused("five flags, found 0", classes(variant = "shape"))

    @Test
    fun anUpdateWithoutTheFlagFailsThePatch() = refused("calls 0 updates", classes(updateFlag = false))

    @Test
    fun twoUpdatesFailThePatch() = refused("calls 2 updates", classes(variant = "two"))

    @Test
    fun aFlagNotHandedOverFailsThePatch() = refused("doesn't hand its second flag", classes(variant = "lost"))

    @Test
    fun aJumpToTheStartFailsThePatch() = refused("jumps back to its start", classes(variant = "jump"))

    /** On each declared build the patch applies to the top bar's scroll callback. */
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
     * Slices the fixture the way the finder reads it, the top bar's class alone, and runs the whole
     * patch over it: one scroll callback, hooked once.
     */
    private fun keepsTopBar(bundle: File, label: String) {
        val marked = FixtureDex.classes(bundle, setOf(ACTION_BAR)).values.toList()
        assertTrue("$label: no $ACTION_BAR", marked.isNotEmpty())
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + marked)
        val found = context.findReelScroll()

        keepReelsTopBarPatch.execute(context)

        val status = context.method(SETTINGS_STATUS, KEEP_REELS_TOP_BAR_STATUS).code()
        assertEquals("$label: status", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        assertHooked("$label ${found.type}->${found.name}", context.scroll(found), found.flag)
        val hooked = marked.flatMap { classDef ->
            context.mutableClassDefBy(classDef.type).methods.filter { method ->
                method.code().any { it.reference()?.toString() == KEEP_IN_PLACE }
            }.map { "${it.definingClass}->${it.name}" }
        }
        assertEquals("$label: only the scroll callback is hooked", listOf("${found.type}->${found.name}"), hooked)
    }

    /** The hook first, on the flag alone, then its answer back in the flag's register. */
    private fun assertHooked(what: String, code: List<Instruction>, flag: Int) {
        assertEquals("$what: one hook", 1, code.count { it.reference()?.toString() == KEEP_IN_PLACE })
        assertEquals("$what: a static call first", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals("$what: to the hook", KEEP_IN_PLACE, code[0].reference().toString())
        val hook = code[0] as RegisterRangeInstruction
        assertEquals("$what: with the flag alone", flag to 1, hook.startRegister to hook.registerCount)
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: back in the flag's register", flag, (code[1] as OneRegisterInstruction).registerA)
    }

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findReelScroll() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private fun BytecodePatchContext.scroll(found: ReelScroll): List<Instruction> =
        mutableClassDefBy(found.type).methods.single { it.name == found.name }.implementation!!.instructions.toList()

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val HELPER = "Lfixture/ScrollHelper;"
        val SCROLL_PARAMETERS = listOf("Lcom/instagram/common/session/UserSession;", "Lfixture/State;", "I", "I", "Z", "Z", "Z", "Z", "Z")
        val UPDATE_PARAMETERS = listOf(
            "Landroid/view/View;", "Lfixture/State;", "Lfixture/Key;", "Lkotlin/jvm/functions/Function0;",
            "I", "I", "Z", "Z", "Z", "Z", "Z", "Z", "Z",
        )

        /** The helper's update, named [name], its third flag an int when it isn't [flagged]. */
        fun update(name: String = "A02", flagged: Boolean = true): String {
            val parameters = if (flagged) UPDATE_PARAMETERS else UPDATE_PARAMETERS.mapIndexed { i, type -> if (i == 8) "I" else type }
            return "$HELPER->$name(${parameters.joinToString("")})V"
        }

        /**
         * Shaped like 456's ClipsViewerActionBar->A0O: copies `this` down to a register a field read
         * takes, then hands the title to the helper's update [call] with its own second flag as update's
         * third. "chain" copies the flag twice on the way, "lost" hands a constant instead, "jump"
         * branches back to the start, "shape" drops the last flag, "two" calls a second update.
         */
        fun smali(variant: String, call: String): String {
            val copy = when (variant) {
                "chain" -> "move/from16 v15, p6\n                move v9, v15"
                "lost" -> "const/4 v9, 0x1"
                else -> "move/from16 v9, p6"
            }
            val jump = if (variant == "jump") "if-eqz p5, :start" else ""
            val second = if (variant == "two") "invoke-virtual/range {v0 .. v13}, ${update("A04")}" else ""
            return """
                :start
                move-object/from16 v14, p0
                iget-object v0, v14, $ACTION_BAR->helper:$HELPER
                iget-object v1, v14, $ACTION_BAR->title:Landroid/view/View;
                move-object/from16 v2, p2
                const/4 v3, 0x0
                const/4 v4, 0x0
                move/from16 v5, p3
                move/from16 v6, p4
                move/from16 v7, p5
                const/4 v8, 0x0
                $copy
                const/4 v10, 0x0
                const/4 v11, 0x0
                const/4 v12, 0x0
                const/4 v13, 0x0
                invoke-virtual/range {v0 .. v13}, $call
                $second
                $jump
                return-void
            """.trimIndent()
        }

        fun method(owner: String, name: String, parameters: List<String>, registers: Int, smali: String): Method {
            val method = MutableMethod(
                ImmutableMethod(
                    owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null),
                ),
            )
            method.addInstructionsWithLabels(0, smali)
            return ImmutableMethod.of(method)
        }

        fun scroll(owner: String, variant: String, name: String = "A07", call: String = update()): Method {
            val parameters = if (variant == "shape") SCROLL_PARAMETERS.dropLast(1) else SCROLL_PARAMETERS
            return method(owner, name, parameters, 30, smali(variant, call))
        }

        /**
         * The top bar with its scroll callback (left out, or with a [second] one), calling an update
         * with its keep-in-place flag or, without [updateFlag], an int there, then the helper and a
         * class outside the bar with a method of the scroll's shape, which the finder never reads.
         */
        fun classes(leaveOut: Boolean = false, second: Boolean = false, variant: String = "ok", updateFlag: Boolean = true): List<ClassDef> {
            fun type(type: String, vararg methods: Method) = ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, null, methods.toList(),
            )
            val call = update(flagged = updateFlag)
            val scrolls = listOfNotNull(scroll(ACTION_BAR, variant, call = call), if (second) scroll(ACTION_BAR, "ok", "A08") else null)
            val bar = if (leaveOut) emptyList() else listOf(type(ACTION_BAR, *scrolls.toTypedArray()))
            val helper = method(HELPER, "A02", call.substringAfter("(").substringBefore(")").let(::split), 15, "return-void")
            return bar + type(HELPER, helper) + type("Lfixture/Unrelated;", scroll("Lfixture/Unrelated;", "ok"))
        }

        /** A descriptor's parameter list, one type per entry. */
        fun split(descriptor: String): List<String> =
            Regex("\\[*(?:L[^;]+;|[ZBSCIJFD])").findAll(descriptor).map { it.value }.toList()
    }
}

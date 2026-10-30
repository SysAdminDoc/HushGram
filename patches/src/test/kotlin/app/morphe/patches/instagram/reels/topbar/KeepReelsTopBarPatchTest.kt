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
import app.morphe.patches.instagram.misc.extension.markers
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
    fun noReelScrollFailsThePatch() = refused("found 0", classes(leaveOut = true))

    @Test
    fun twoReelScrollsFailThePatch() = refused("found 2", classes(second = true))

    @Test
    fun aChangedScrollFailsThePatch() = refused("not a session, the scroll state", classes(variant = "shape"))

    @Test
    fun anUpdateWithoutTheFlagFailsThePatch() = refused("no flag at 8", classes(updateFlag = false))

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
     * Slices the fixture the way the finder reads it, every class holding either marker, and runs
     * the whole patch over it: one scroll callback, hooked once.
     */
    private fun keepsTopBar(bundle: File, label: String) {
        val marked = markedClasses(bundle)
        assertTrue("$label: nothing holds $ON_REEL_SCROLL", marked.isNotEmpty())
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

    /** Every class in [bundle] with a method carrying the scroll callback's or the update's marker. */
    private fun markedClasses(bundle: File): List<ClassDef> {
        val names = setOf(ON_REEL_SCROLL, SCROLL_UPDATE)
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.stringSection.none { string -> string.startsWith("android_purge_") && names.any { string.endsWith("_$it") } }) return@forEach
            dex.classes.filterTo(found) { classDef -> classDef.methods.any { method -> method.markers().any { it in names } } }
        }
        return found.map { ImmutableClassDef.of(it) }
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
        const val ACTION_BAR = "Lfixture/ActionBar;"
        const val SECOND = "Lfixture/OtherActionBar;"
        const val HELPER = "Lfixture/ScrollHelper;"
        val SCROLL_PARAMETERS = listOf("Lfixture/Session;", "Lfixture/State;", "I", "I", "Z", "Z", "Z", "Z", "Z")
        val UPDATE_PARAMETERS = listOf(
            "Landroid/view/View;", "Lfixture/State;", "Lfixture/Key;", "Lkotlin/jvm/functions/Function0;",
            "I", "I", "Z", "Z", "Z", "Z", "Z", "Z", "Z",
        )
        val UPDATE = "$HELPER->A02(${UPDATE_PARAMETERS.joinToString("")})V"

        /**
         * Shaped like 453's ClipsViewerActionBar->A07: loads its marker, copies `this` down to a
         * register a field read takes, then hands the title to the helper's update with its own
         * second flag as update's third. "chain" copies the flag twice
         * on the way, "lost" hands a constant instead, "jump" branches back to the start, "shape"
         * drops the last flag, "unmarked" leaves the marker out.
         */
        fun smali(variant: String): String {
            val marker = if (variant == "unmarked") "" else "const-string v14, \"android_purge_26_q3_$ON_REEL_SCROLL\""
            val copy = when (variant) {
                "chain" -> "move/from16 v15, p6\n                move v9, v15"
                "lost" -> "const/4 v9, 0x1"
                else -> "move/from16 v9, p6"
            }
            val jump = if (variant == "jump") "if-eqz p5, :start" else ""
            return """
                :start
                $marker
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
                invoke-virtual/range {v0 .. v13}, $UPDATE
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

        fun scroll(owner: String, variant: String): Method {
            val parameters = if (variant == "shape") SCROLL_PARAMETERS.dropLast(1) else SCROLL_PARAMETERS
            return method(owner, "A07", parameters, 30, smali(variant))
        }

        fun update(flagged: Boolean): Method {
            val parameters = if (flagged) UPDATE_PARAMETERS else UPDATE_PARAMETERS.mapIndexed { i, type -> if (i == 8) "I" else type }
            return method(HELPER, "A02", parameters, 15, "const-string v0, \"android_purge_26_q3_$SCROLL_UPDATE\"\nreturn-void")
        }

        fun classes(leaveOut: Boolean = false, second: Boolean = false, variant: String = "ok", updateFlag: Boolean = true): List<ClassDef> {
            fun type(type: String, vararg methods: Method) = ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, null, methods.toList(),
            )
            val bar = if (leaveOut) emptyList() else listOf(type(ACTION_BAR, scroll(ACTION_BAR, variant)))
            val other = if (second) listOf(type(SECOND, scroll(SECOND, "ok"))) else emptyList()
            return bar + other + type(HELPER, update(updateFlag)) +
                type("Lfixture/Unrelated;", scroll("Lfixture/Unrelated;", "unmarked"))
        }
    }
}

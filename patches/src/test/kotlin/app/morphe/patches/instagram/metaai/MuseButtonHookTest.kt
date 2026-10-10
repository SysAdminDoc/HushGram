/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.profile.threads.PROFILE_ACTION_BAR
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide the Muse button on your profile: the method filling the profile's top bar goes back to the
 * head of its walk of the items just inside the Muse item's branch, while the switch is on.
 */
class MuseButtonHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(MUSE_BUTTON.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$MUSE_BUTTON is not in the extension: $declared", MUSE_BUTTON.substringAfter("->") in declared)
    }

    /** The filler is found through the bar, the Muse item by its view's builder, and a yes goes back to hasNext. */
    @Test
    fun theMuseItemsBranchGoesBackToTheWalkWhileTheSwitchIsOn() {
        val patch = PatchContexts.of(classes())
        val found = patch.findMuseButtonTest()
        assertEquals(FILLER, found.type)
        assertEquals("fill", found.name)
        assertEquals(MUSE_ITEM, found.item)
        val before = classes().single { it.type == FILLER }.methods.single { it.name == "fill" }.code()
        assertEquals("the branch on the Muse test", Opcode.IF_EQZ, before[found.branch].opcode)
        assertEquals("the walk's head", HAS_NEXT, before[found.loop].referenceText())

        patch.holdMuseButton(found)

        val answer = assertAsked("synthetic", found, before, patch.filler(found))
        assertEquals("v0 is free there: the head writes it before reading it", 0, answer)
    }

    @Test
    fun aBuildWithoutTheProfileBarFailsThePatch() {
        val refusal = assertThrows(PatchException::class.java) {
            PatchContexts.of(classes().filter { it.type != PROFILE_ACTION_BAR }).findMuseButtonTest()
        }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("is missing"))
    }

    @Test
    fun aBarCallingTwoFillersFailsThePatch() {
        assertRefused(classes(fillers = 2), "calls 2 methods filling it, not one")
    }

    @Test
    fun aBarWithoutTheMuseViewFailsThePatch() {
        assertRefused(classes(museView = false), "for 0 kinds of item, not one")
    }

    @Test
    fun aMuseItemTestedTwiceFailsThePatch() {
        assertRefused(classes(museTests = 2), "2 times, not once")
    }

    @Test
    fun aMuseTestWithoutItsBranchRightAfterFailsThePatch() {
        assertRefused(classes(gap = true), "doesn't branch right on its test")
    }

    @Test
    fun aTestOfSomethingOtherThanTheNextItemFailsThePatch() {
        assertRefused(classes(tested = "v5"), "tests something other than the item its walk took next")
    }

    @Test
    fun aJumpIntoTheMuseBranchFailsThePatch() {
        assertRefused(classes(jumpIn = true), "jump into the Muse button's branch")
    }

    @Test
    fun aMuseTestThatMovedFailsTheHold() {
        val patch = PatchContexts.of(classes())
        val found = patch.findMuseButtonTest()
        patch.filler(found).addInstructions(0, "nop")
        val refusal = assertThrows(PatchException::class.java) { patch.holdMuseButton(found) }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("before it was hooked"))
    }

    /**
     * In each declared build, and in every other build of a declared version, the profile's top bar
     * calls one filler, which tests once for the one item class whose view holds the Muse button's
     * tag, and the ask goes in just inside that branch, going back to the walk's hasNext.
     */
    @Test
    fun eachBuildLeavesTheMuseButtonOutOfTheProfileBar() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val declared = versions.flatMap { version ->
            Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.map { version to it }
        }
        for ((version, bundle) in declared) {
            builds(bundle, bundle.name)
            checked += version
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val bar = FixtureDex.classes(bundle, setOf(PROFILE_ACTION_BAR)).values.single()
        val fillers = bar.methods.flatMap { it.code() }.mapNotNull { it.staticCall() }
            .filter { it.parameterTypes.size == 8 && it.parameterTypes[6].toString() == "Ljava/util/List;" }
            .mapTo(HashSet()) { it.definingClass }
        val filler = FixtureDex.classes(bundle, fillers).values.toList()
        val views = FixtureDex.classesHolding(bundle, MUSE_BUTTON_VIEW)
        val seed = (listOf(bar) + filler + FixtureDex.withStringPools(bundle, views)).distinctBy { it.type }
        val patch = PatchContexts.of(seed)
        val found = patch.findMuseButtonTest()
        assertEquals("$label: the filler is static and takes the bar's eight", 8, found.parameters.size)
        val before = filler.single { it.type == found.type }.methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.code()
        assertTrue("$label: the head is ahead of the test", found.loop < found.branch)

        patch.holdMuseButton(found)

        val after = patch.filler(found)
        assertAsked(label, found, before, after)
        val muse = views.map { it.type }.toSet()
        val builders = after.code().mapNotNull { it.staticCall() }.filter { it.returnType in muse }
        assertEquals("$label: one Muse view builder, taking the item", 1, builders.size)
        assertTrue("$label: the builder takes ${found.item}", builders.single().parameterTypes.any { it.toString() == found.item })
    }

    /**
     * The test and its branch, then the ask, its answer and a branch back to the walk's hasNext on
     * a yes, then the Muse item's own code. Everything else is as it was. Answers the register the
     * answer goes in.
     */
    private fun assertAsked(what: String, found: MuseButtonTest, before: List<Instruction>, method: Method): Int {
        val after = method.code()
        val start = found.branch + 1
        assertEquals("$what: the test is where it was", Opcode.INSTANCE_OF, after[found.branch - 1].opcode)
        assertEquals("$what: the ask comes just inside the branch", MUSE_BUTTON, after[start].referenceText())
        assertEquals("$what: answer", Opcode.MOVE_RESULT, after[start + 1].opcode)
        val answer = (after[start + 1] as OneRegisterInstruction).registerA
        assertEquals("$what: a yes", Opcode.IF_NEZ, after[start + 2].opcode)
        assertEquals("$what: tests the answer", answer, (after[start + 2] as OneRegisterInstruction).registerA)
        assertNotEquals("$what: the answer isn't the item", (after[found.branch - 1] as TwoRegisterInstruction).registerB, answer)
        assertEquals("$what: one ask", 1, after.count { it.referenceText() == MUSE_BUTTON })
        assertEquals("$what: the rest is as it was", before.map { it.opcode },
            (after.take(start) + after.drop(start + 3)).map { it.opcode })
        val back = ControlFlow.of(method).normal[start + 2].first()
        assertEquals("$what: a yes goes back to the walk's head", found.loop, back)
        assertEquals("$what: which asks hasNext", HAS_NEXT, after[back].referenceText())
        return answer
    }

    private fun assertRefused(classes: List<ClassDef>, message: String) {
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findMuseButtonTest() }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains(message))
    }

    private fun BytecodePatchContext.filler(found: MuseButtonTest): MutableMethod =
        mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }

    private fun classes(
        fillers: Int = 1,
        museView: Boolean = true,
        museTests: Int = 1,
        gap: Boolean = false,
        tested: String = "v2",
        jumpIn: Boolean = false,
    ): List<ClassDef> {
        val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val calls = (0 until fillers).joinToString("\n") { copy -> "invoke-static/range { v0 .. v7 }, ${fill(FILLER.withCopy(copy))}" }
        val bar = classDef(PROFILE_ACTION_BAR, listOf(method(PROFILE_ACTION_BAR, "bind", BAR, 8, "$calls\nreturn-void", flags)))
        // Six locals, then the eight parameters from v6: the context in v7, the second group in v10
        // and the list in v12.
        val body = listOfNotNull(
            "invoke-interface { v12 }, $LIST_ITERATOR",
            "move-result-object v1",
            ":loop",
            "invoke-interface { v1 }, $HAS_NEXT",
            "move-result v0",
            "if-eqz v0, :done",
            "invoke-interface { v1 }, $NEXT",
            "move-result-object v2",
            "move-object v5, v2".takeIf { tested == "v5" },
            "instance-of v0, $tested, $FOLLOW_ITEM",
            "if-eqz v0, :muse_test",
            "instance-of v4, v2, $MUSE_ITEM".takeIf { museTests == 2 },
            "invoke-static { v10 }, Lfixture/Views;->follow(Landroid/view/ViewGroup;)V",
            if (jumpIn) "goto :muse" else "goto :loop",
            ":muse_test",
            "instance-of v0, $tested, $MUSE_ITEM",
            "const/4 v4, 0x0".takeIf { gap },
            "if-eqz v0, :loop",
            ":muse",
            "check-cast v2, $MUSE_ITEM",
            "invoke-static { v7, v2 }, $FILLER->muse(Landroid/content/Context;$MUSE_ITEM)$MUSE_VIEW",
            "move-result-object v3",
            "invoke-virtual { v10, v3 }, Landroid/view/ViewGroup;->addView(Landroid/view/View;)V",
            "goto :loop",
            ":done",
            "return-void",
        ).joinToString("\n")
        val filling = (0 until fillers).map { copy ->
            val type = FILLER.withCopy(copy)
            classDef(type, listOf(method(type, "fill", BAR, 14, body, flags)))
        }
        val tag = if (museView) "const-string v0, \"$MUSE_BUTTON_VIEW\"" else "const-string v0, \"MuseView\""
        val view = classDef(MUSE_VIEW, listOf(method(MUSE_VIEW, "tag", emptyList(), "Ljava/lang/String;", 1, "$tag\nreturn-object v0", flags)))
        return listOf(bar) + filling + view
    }

    private fun String.withCopy(copy: Int) = if (copy == 0) this else removeSuffix(";") + "$copy;"

    private fun fill(owner: String) = "$owner->fill(${BAR.joinToString("")})V"

    private fun method(owner: String, name: String, parameters: List<String>, registers: Int, body: String, flags: Int): Method =
        method(owner, name, parameters, "V", registers, body, flags)

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.staticCall(): MethodReference? =
        if (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) (this as ReferenceInstruction).reference as? MethodReference else null

    private companion object {
        const val FILLER = "Lfixture/BarFiller;"
        const val FOLLOW_ITEM = "Lfixture/FollowItem;"
        const val MUSE_ITEM = "Lfixture/MuseItem;"
        const val MUSE_VIEW = "Lfixture/MuseView;"
        const val LIST_ITERATOR = "Ljava/util/List;->iterator()Ljava/util/Iterator;"
        const val HAS_NEXT = "Ljava/util/Iterator;->hasNext()Z"
        const val NEXT = "Ljava/util/Iterator;->next()Ljava/lang/Object;"
        val BAR = listOf(
            "Landroid/app/Activity;", "Landroid/content/Context;", "Lcom/instagram/common/session/UserSession;",
            "Lcom/instagram/common/ui/base/IgLinearLayout;", "Lcom/instagram/common/ui/base/IgLinearLayout;",
            "Lfixture/BarLogger;", "Ljava/util/List;", "Lkotlin/jvm/functions/Function1;",
        )
    }
}

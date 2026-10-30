/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestQuestionTest {
    /** The hook the patch writes is in the FeedInterest the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_INTEREST).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HIDE_INTEREST is not in the extension: $declared", HIDE_INTEREST.substringAfter("->") in declared)
    }

    @Test
    fun theAddIsFound() {
        val found = PatchContexts.of(classes()).findInterestQuestion()

        assertEquals(BUILDER_TYPE, found.type)
        assertEquals("buildRowViewTypes", found.name)
        assertEquals(listOf(ROWS, OBJECT, OBJECT), found.parameters)
        assertEquals("the sget of the row type", 21, found.rowAt)
        assertEquals("the register the module check left behind", 1, found.register)
        assertEquals("the builder's return", 4, found.endAt)
        assertEquals("the row type", "Lfixture/RowType;->A0E:Lfixture/RowType;", found.rowType)
    }

    /** The binder group's add (#131): the one other load of the row type, Home's own builder left out. */
    @Test
    fun theGroupedAddIsFound() {
        val context = PatchContexts.of(groupedClasses())
        val found = context.findGroupedInterestQuestion(context.findInterestQuestion())

        assertEquals(GROUP_TYPE, found.type)
        assertEquals("A05", found.name)
        assertEquals(listOf(ROWS, "Lfixture/Post;", "Lfixture/State;"), found.parameters)
        assertEquals("the sget of the row type", 14, found.rowAt)
        assertEquals("the register the load writes", 0, found.register)
        assertEquals("the instruction after the add", 18, found.endAt)
    }

    @Test
    fun theGroupedHookSkipsTheAdd() {
        val context = PatchContexts.of(groupedClasses())
        val home = context.findInterestQuestion()
        val found = context.findGroupedInterestQuestion(home)

        home.write(context)
        found.write(context)

        assertHooked("stand-in", context, home)
        assertGroupedHooked("stand-in", context, found)
    }

    @Test
    fun noGroupedAddFailsThePatch() = groupedRefused("found 0", groupedClasses(leaveOut = true))

    @Test
    fun twoGroupedAddsFailThePatch() = groupedRefused("found 2", groupedClasses(variant = "twice"))

    @Test
    fun aGroupedLoadWithoutItsOrdinalFailsThePatch() = groupedRefused("take the row type's ordinal", groupedClasses(variant = "noordinal"))

    @Test
    fun aGroupedAddOfSomethingElseFailsThePatch() = groupedRefused("add the row with the row type's ordinal", groupedClasses(variant = "otheradd"))

    @Test
    fun aStyleCheckThatGoesElsewhereFailsThePatch() = groupedRefused("check the question's style", groupedClasses(variant = "stylelsewhere"))

    @Test
    fun aGroupedNeitherThatDoesNotSkipFailsThePatch() = groupedRefused("when the module is neither", groupedClasses(variant = "neitherelsewhere"))

    /** The hook goes where both module checks meet, and a yes goes on to the builder's return. */
    @Test
    fun theHookGoesWhereTheModuleChecksMeet() {
        val context = PatchContexts.of(classes())
        val found = context.findInterestQuestion()

        found.write(context)

        assertHooked("stand-in", context, found)
    }

    @Test
    fun noBuilderFailsThePatch() = refused("found 0", classes(leaveOut = true))

    @Test
    fun twoBuildersFailThePatch() = refused("found 2", classes(second = true))

    @Test
    fun aStaticBuilderIsNotTheBuilder() = refused("found 0", classes(static = true))

    @Test
    fun aBuilderWithOtherParametersIsNotTheBuilder() = refused("found 0", classes(parameters = listOf(ROWS, "I", OBJECT)))

    @Test
    fun theHomeModuleLoadedTwiceFailsThePatch() = refused("loads $HOME_MODULE 2 times", classes(variant = "twice"))

    @Test
    fun aBuilderThatNeverChecksAQuestionFailsThePatch() = refused("doesn't check a post's question", classes(variant = "noquestion"))

    @Test
    fun aQuestionOfAnotherClassFailsThePatch() = refused("doesn't check a post's question", classes(variant = "otherclass"))

    @Test
    fun aNoQuestionThatGoesElsewhereFailsThePatch() = refused("doesn't return when a post has no question", classes(variant = "badend"))

    @Test
    fun aMissingOlderPostsCheckFailsThePatch() = refused("one after the other", classes(variant = "noolder"))

    @Test
    fun aHomeThatSkipsTheAddFailsThePatch() = refused("doesn't add its row when the module is $HOME_MODULE", classes(variant = "homeskips"))

    @Test
    fun anAddThatLoadsNoRowTypeFailsThePatch() = refused("doesn't load its row type", classes(variant = "noload"))

    @Test
    fun anAddWithoutAJumpFailsThePatch() = refused("doesn't jump to a shared add", classes(variant = "nojump"))

    @Test
    fun aSharedAddThatTakesNoOrdinalFailsThePatch() = refused("take the row type's ordinal", classes(variant = "noordinal"))

    @Test
    fun aNeitherThatDoesNotReturnFailsThePatch() = refused("when the module is neither", classes(variant = "neitherelsewhere"))

    /** Hook test over the declared build: the add is found and the hook sits right before it. */
    @Test
    fun eachDeclaredBuildHooksTheQuestionRow() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooksTheQuestionRow(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same in the other builds of each declared version, seven on 451. */
    @Test
    fun eachOtherBuildHooksTheQuestionRow() {
        var checked = 0
        for (apk in Fixtures.otherBuilds()) {
            hooksTheQuestionRow(apk, apk.parentFile.name)
            checked++
        }
        assertTrue("no other build", checked > 0)
        assertEquals("the builds besides the declared one", Fixtures.OTHER_BUILDS, checked)
    }

    /**
     * Slices the fixture the way the finder reads it: every class holding the Home module's name,
     * with the string pools they call, so a second builder holding both names anywhere in the app
     * would be there to find.
     */
    private fun hooksTheQuestionRow(bundle: File, label: String) {
        val holders = FixtureDex.classesHolding(bundle, HOME_MODULE)
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders))

        val found = context.findInterestQuestion()
        val grouped = context.findGroupedInterestQuestion(found)
        val before = context.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.implementation!!.instructions.toList()
        // 453's builds place the add differently, so its shape is checked, not its index.
        assertEquals("$label: the row type's load", Opcode.SGET_OBJECT, before[found.rowAt].opcode)
        assertEquals("$label: the row type", found.rowType, before[found.rowAt].reference().toString())
        assertTrue("$label: the builder's return", before[found.endAt].opcode in setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_OBJECT))
        // 453 builds the binder group's rows in its kept override, not a renamed method.
        assertEquals("$label: the binder group", "buildRowViewTypes", grouped.name)
        assertTrue("$label: the binder group isn't Home's builder", grouped.type != found.type)
        assertEquals("$label: after the binder group's add", grouped.rowAt + 4, grouped.endAt)
        found.write(context)
        grouped.write(context)

        assertHooked("$label ${found.type}->${found.name}", context, found)
        assertGroupedHooked("$label ${grouped.type}->${grouped.name}", context, grouped)
    }

    /**
     * Right before the binder group's load: the same question and answer, and a yes jumping to what
     * followed the add. The load, its ordinal and the add still follow, and the style check still
     * skips to what followed the add rather than past the hook.
     */
    private fun assertGroupedHooked(what: String, context: BytecodePatchContext, found: InterestQuestion) {
        val code = context.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.implementation!!.instructions.toList()
        val calls = code.indices.filter { code[it].reference()?.toString() == HIDE_INTEREST }
        assertEquals("$what: one hook, where the load was", listOf(found.rowAt), calls)
        val at = calls.single()
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("$what: in the register", found.register, (code[at + 1] as OneRegisterInstruction).registerA)
        val branch = code[at + 2]
        assertEquals("$what: the branch", Opcode.IF_NEZ, branch.opcode)
        assertEquals("$what: a no loads the row type", found.rowType, code[at + 3].reference()?.toString())
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        fun targetOf(index: Int) = addresses.indexOf(addresses[index] + (code[index] as OffsetInstruction).codeOffset)
        val after = found.endAt + 3
        assertEquals("$what: a yes skips the load, the ordinal and the add", after, targetOf(at + 2))
        assertEquals("$what: the style check still skips the add", after, targetOf(at - 1))
        for (index in code.indices) {
            if (index == at + 2 || code[index] !is OffsetInstruction) continue
            assertTrue("$what: instruction $index jumps past the hook", targetOf(index) != at + 3)
        }
    }

    /**
     * Right before the add: the extension's question, its answer into the register the add then
     * overwrites, and a branch on it to the builder's return, once in the method. The row type load
     * still follows, and the checks before it jump to the hook rather than past it.
     */
    private fun assertHooked(what: String, context: BytecodePatchContext, found: InterestQuestion) {
        val code = context.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.implementation!!.instructions.toList()
        val calls = code.indices.filter { code[it].reference()?.toString() == HIDE_INTEREST }
        assertEquals("$what: one hook, where the add was", listOf(found.rowAt), calls)
        val at = calls.single()
        val call = code[at] as FiveRegisterInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC, code[at].opcode)
        assertEquals("$what: with nothing", 0, call.registerCount)
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("$what: in the register", found.register, (code[at + 1] as OneRegisterInstruction).registerA)
        val branch = code[at + 2]
        assertEquals("$what: the branch", Opcode.IF_NEZ, branch.opcode)
        assertEquals("$what: on the answer", found.register, (branch as OneRegisterInstruction).registerA)
        assertEquals("$what: a no loads the row type", Opcode.SGET_OBJECT, code[at + 3].opcode)
        assertEquals("$what: into the same register", found.register, (code[at + 3] as OneRegisterInstruction).registerA)
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val target = addresses.indexOf(addresses[at + 2] + (branch as OffsetInstruction).codeOffset)
        assertEquals("$what: a yes returns", Opcode.RETURN_VOID, code[target].opcode)
        // The checks that used to reach the add now reach the hook: no branch lands on the row type load.
        for (index in code.indices) {
            val instruction = code[index]
            if (index == at + 2 || instruction !is OffsetInstruction) continue
            val jumpsTo = addresses.indexOf(addresses[index] + instruction.codeOffset)
            assertTrue("$what: instruction $index jumps past the hook", jumpsTo != at + 3)
        }
        val reachesHook = code.indices.count { index ->
            val instruction = code[index]
            index != at + 2 && instruction is OffsetInstruction && addresses.indexOf(addresses[index] + instruction.codeOffset) == at
        }
        assertTrue("$what: something jumps to the hook", reachesHook >= 1)
    }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findInterestQuestion() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private fun groupedRefused(why: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val home = context.findInterestQuestion()
        val failure = assertThrows(PatchException::class.java) { context.findGroupedInterestQuestion(home) }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val ROWS = "Lfixture/RowList;"
        const val BUILDER_TYPE = "Lfixture/FeedAdapter;"
        const val OTHER_TYPE = "Lfixture/OtherAdapter;"
        const val GROUP_TYPE = "Lfixture/GroupAdapter;"
        const val MEDIA_TYPE = "Lcom/instagram/feed/media/Media;"

        /**
         * Shaped like 451's builder: a post's question is looked up and a missing one returns, the
         * feed's module name is compared with the Home module and then the Older Posts module, and
         * either one loads the row type and jumps to the add near the top, which takes its ordinal
         * and falls into the return. [variant] breaks one thing at a time.
         *   0 goto start   1 add: invoke ordinal   4 return-void (end)   8 the post's question   13 the Home module
         *   17 the Older Posts module   21 the row type load   22 goto add
         */
        fun smali(variant: String): String {
            val question = when (variant) {
                "noquestion" -> "const/4 v1, 0x0"
                "otherclass" -> "invoke-virtual {p2}, Lfixture/NotAPost;->A2J()Lfixture/Question;"
                else -> "invoke-virtual {p2}, $MEDIA_TYPE->A2J()Lfixture/Question;"
            }
            val questionResult = if (variant == "noquestion") "" else "move-result-object v1\n"
            val noQuestion = if (variant == "badend") "if-eqz v1, :elsewhere" else "if-eqz v1, :end"
            val home = if (variant == "homeskips") "if-nez v1, :end" else "if-nez v1, :home"
            val olderName = if (variant == "noolder") "other_module" else "feed_timeline_older"
            val older = """
                const-string v1, "$olderName"
                invoke-virtual {v1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                ${if (variant == "neitherelsewhere") "if-eqz v1, :elsewhere" else "if-eqz v1, :end"}
            """
            val load = when (variant) {
                "noload" -> "const/4 v1, 0x0"
                else -> "sget-object v1, Lfixture/RowType;->A0E:Lfixture/RowType;"
            }
            val jump = if (variant == "nojump") "nop" else "goto :add"
            val ordinal = if (variant == "noordinal") "invoke-virtual {v1}, Ljava/lang/Object;->hashCode()I" else "invoke-virtual {v1}, Ljava/lang/Enum;->ordinal()I"
            val twice = if (variant == "twice") "\nconst-string v3, \"feed_timeline\"" else ""
            return """
                goto :start
                :add
                $ordinal
                move-result v1
                invoke-static {v1}, Lfixture/Rows;->add(I)V
                :end
                return-void
                :elsewhere
                nop
                ${if (variant == "noolder") "const-string v3, \"feed_timeline_older\"" else "nop"}
                return-void
                :start
                $question
                $questionResult$noQuestion
                invoke-interface {p1}, Lfixture/Module;->getModuleName()Ljava/lang/String;
                move-result-object v2
                const-string v1, "feed_timeline"$twice
                invoke-virtual {v1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                $home
                $older
                :home
                $load
                $jump
            """.trimIndent()
        }

        fun builder(owner: String, variant: String, static: Boolean, parameters: List<String>): Method {
            val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
            val method = MutableMethod(
                ImmutableMethod(
                    owner, "buildRowViewTypes", parameters.map { ImmutableMethodParameter(it, null, null) }, "V", flags, null, null,
                    ImmutableMethodImplementation(8, emptyList(), null, null),
                ),
            )
            method.addInstructionsWithLabels(0, smali(variant))
            return ImmutableMethod.of(method)
        }

        fun classes(
            leaveOut: Boolean = false,
            second: Boolean = false,
            static: Boolean = false,
            parameters: List<String> = listOf(ROWS, OBJECT, OBJECT),
            variant: String = "ok",
        ): List<ClassDef> {
            fun adapter(type: String, method: Method) = ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, null, listOf(method),
            )
            val found = if (leaveOut) emptyList() else listOf(adapter(BUILDER_TYPE, builder(BUILDER_TYPE, variant, static, parameters)))
            val other = if (second) listOf(adapter(OTHER_TYPE, builder(OTHER_TYPE, "ok", false, listOf(ROWS, OBJECT, OBJECT)))) else emptyList()
            // A method with the strings but another shape: the feed adapter's other builder.
            val sibling = adapter(
                "Lfixture/SiblingAdapter;",
                builder("Lfixture/SiblingAdapter;", "ok", false, listOf(ROWS, "Lfixture/A;", "Lfixture/B;")),
            )
            return found + other + sibling + listOf(
                ImmutableClassDef(
                    "Lfixture/Unrelated;", AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, emptyList(),
                ),
            )
        }

        /**
         * Shaped like 451's binder group (LX/05rL->A06 on 385713073): the module compared with the Home module and
         * then the Older Posts one, neither skipping the add; the question's style compared, another
         * style skipping it too; then the row type, its ordinal and the add.
         *   0 the Home module   6 the Older Posts module   9 neither skips   13 the style check
         *   14 the row type load   15 ordinal   17 the add   18 what follows the add
         */
        fun groupedSmali(variant: String): String {
            val neither = if (variant == "neitherelsewhere") "if-eqz v0, :elsewhere" else "if-eqz v0, :skip"
            val style = if (variant == "stylelsewhere") "if-ne v1, v0, :elsewhere" else "if-ne v1, v0, :skip"
            val ordinal = if (variant == "noordinal") "invoke-virtual {v0}, Ljava/lang/Object;->hashCode()I" else "invoke-virtual {v0}, Ljava/lang/Enum;->ordinal()I"
            val added = if (variant == "otheradd") "v1" else "v0"
            return """
                const-string v3, "feed_timeline"
                invoke-interface {p3}, Lfixture/Module;->getModuleName()Ljava/lang/String;
                move-result-object v1
                invoke-virtual {v3, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-nez v0, :home
                const-string v0, "feed_timeline_older"
                invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                $neither
                :home
                invoke-virtual {p2}, Lfixture/Post;->style()Lfixture/Style;
                move-result-object v1
                sget-object v0, Lfixture/Style;->A0F:Lfixture/Style;
                $style
                sget-object v0, Lfixture/RowType;->A0E:Lfixture/RowType;
                $ordinal
                move-result v0
                invoke-virtual {p0, p1, p2, p3, $added}, $GROUP_TYPE->add(${ROWS}Ljava/lang/Object;Ljava/lang/Object;I)V
                :skip
                const/4 v2, 0x0
                return-void
                :elsewhere
                return-void
            """.trimIndent()
        }

        /** Home's builder, the binder group (left out, or twice), and an unrelated class. */
        fun groupedClasses(leaveOut: Boolean = false, variant: String = "ok"): List<ClassDef> {
            fun adapter(type: String, method: Method) = ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, null, listOf(method),
            )
            fun group(type: String): ClassDef {
                val method = MutableMethod(
                    ImmutableMethod(
                        type, "A05", listOf(ROWS, "Lfixture/Post;", "Lfixture/State;").map { ImmutableMethodParameter(it, null, null) },
                        "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                        ImmutableMethodImplementation(8, emptyList(), null, null),
                    ),
                )
                method.addInstructionsWithLabels(0, groupedSmali(variant))
                return adapter(type, ImmutableMethod.of(method))
            }
            val groups = when {
                leaveOut -> emptyList()
                variant == "twice" -> listOf(group(GROUP_TYPE), group("Lfixture/SecondGroupAdapter;"))
                else -> listOf(group(GROUP_TYPE))
            }
            return listOf(adapter(BUILDER_TYPE, builder(BUILDER_TYPE, "ok", false, listOf(ROWS, OBJECT, OBJECT)))) + groups + listOf(
                ImmutableClassDef(
                    "Lfixture/Unrelated;", AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, emptyList(),
                ),
            )
        }
    }
}

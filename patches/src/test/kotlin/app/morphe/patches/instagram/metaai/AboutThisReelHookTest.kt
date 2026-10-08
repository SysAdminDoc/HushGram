/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction51l
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * About this reel (#42): every menu's ask for the summary answers through the extension, and the
 * summary row's one addView of the Ask Meta AI box goes through it instead. Anything else the
 * patch can't tell apart fails it before an instruction changes.
 */
class AboutThisReelHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(ABOUT_THIS_REEL, ASK_META_AI_BOX)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** The Reels menu's two asks and the feed menu's one each pass the answer through, high registers too. */
    @Test
    fun everyAskForTheSummaryAnswersThroughTheExtension() {
        val context = PatchContexts.of(listOf(factory(), menu(REELS_MENU, marked = true, results = listOf(5, 18)), menu(FEED_MENU, results = listOf(5))))

        val summary = context.findAboutSummaryCalls()
        context.dropAboutSummary(summary)

        assertEquals(FACTORY, summary.factory)
        assertEquals(3, summary.calls.size)
        assertPassedThrough(REELS_MENU, context.mutableClassDefBy(REELS_MENU).methods.single { it.name == "A0Y" }, 2)
        assertPassedThrough(FEED_MENU, context.mutableClassDefBy(FEED_MENU).methods.single { it.name == "A0Y" }, 1)
    }

    /** Without the Reels viewer's menu among the callers, the factory isn't the one the report is about. */
    @Test
    fun aFactoryTheReelsMenuNeverAsksFailsThePatch() {
        val context = PatchContexts.of(listOf(factory(), menu(FEED_MENU, results = listOf(5))))
        refuses("the Reels More menu never asks the summary factory") { context.findAboutSummaryCalls() }
    }

    @Test
    fun twoFactoriesFailThePatch() {
        val context = PatchContexts.of(listOf(factory(), factory("Lfixture/OtherSummary;"), menu(REELS_MENU, marked = true, results = listOf(5))))
        refuses("2 About this reel summary factories") { context.findAboutSummaryCalls() }
    }

    /** The flag in a method of another shape (here answering Object) is no factory, so there is none. */
    @Test
    fun aFactoryOfAnotherShapeFailsThePatch() {
        val context = PatchContexts.of(listOf(factory(returns = "Ljava/lang/Object;"), menu(REELS_MENU, marked = true, results = listOf(5))))
        refuses("0 About this reel summary factories") { context.findAboutSummaryCalls() }
    }

    @Test
    fun aCallThatDropsTheAnswerFailsThePatch() {
        val context = PatchContexts.of(listOf(factory(), menu(REELS_MENU, marked = true, results = listOf(null))))
        refuses("drops the summary factory's answer") { context.findAboutSummaryCalls() }
    }

    /** The box the composer lambda gets, copied through a move, is the view the row's addView hands the extension. */
    @Test
    fun theSummaryRowsAskBoxGoesThroughTheExtension() {
        val context = PatchContexts.of(listOf(summaryRow(SUMMARY_ROW)))

        val site = context.findAskMetaAiBox()
        context.holdAskMetaAiBox(site)

        assertEquals(SUMMARY_ROW, site.type)
        assertBoxHeld(SUMMARY_ROW, context.mutableClassDefBy(SUMMARY_ROW).methods.single { it.name == "A04" }, BOX)
    }

    /** A second addView means the patch can't tell which one adds the box. */
    @Test
    fun twoAddViewsFailThePatch() {
        val context = PatchContexts.of(listOf(summaryRow(SUMMARY_ROW, adds = listOf(BOX, BOX))))
        refuses("adds 2 views without layout params") { context.findAskMetaAiBox() }
    }

    @Test
    fun addingAnotherViewFailsThePatch() {
        val context = PatchContexts.of(listOf(summaryRow(SUMMARY_ROW, adds = listOf(BOX + 1))))
        refuses("adds some other view than the Ask Meta AI box") { context.findAskMetaAiBox() }
    }

    /** The same register number holding something else by the time it's added isn't the box. */
    @Test
    fun aBoxRegisterWrittenBeforeTheAddFailsThePatch() {
        val context = PatchContexts.of(listOf(summaryRow(SUMMARY_ROW, overwrite = true)))
        refuses("puts something else in the Ask Meta AI box's register") { context.findAskMetaAiBox() }
    }

    /** Without the composer lambda the method is no summary row, so there is none. */
    @Test
    fun aRowWithoutTheComposerLambdaFailsThePatch() {
        val context = PatchContexts.of(listOf(summaryRow(SUMMARY_ROW, lambda = false)))
        refuses("0 About this reel summary rows") { context.findAskMetaAiBox() }
    }

    @Test
    fun twoSummaryRowsFailThePatch() {
        val context = PatchContexts.of(listOf(summaryRow(SUMMARY_ROW), summaryRow("Lfixture/OtherRow;")))
        refuses("2 About this reel summary rows") { context.findAskMetaAiBox() }
    }

    /**
     * In each declared build: the one factory, every menu's ask including the Reels viewer's, and
     * the summary row's one addView of the Ask Meta AI box, all through the extension.
     */
    @Test
    fun eachDeclaredBuildAnswersAboutThisReelAndItsAskBox() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val kept = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        val code = classDef.methods.flatMap { it.instructions() }
                        val loadsFlag = code.any { (it as? WideLiteralInstruction)?.wideLiteral == SUMMARY_FLAG }
                        val holdsRowString = code.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string in SUMMARY_ROW_STRINGS }
                        val asksAFactory = code.any { instruction ->
                            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
                                call?.parameterTypes?.map(CharSequence::toString) == FACTORY_SHAPE && call.returnType == call.definingClass
                        }
                        if (loadsFlag || holdsRowString || asksAFactory) kept += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(kept)

                val summary = context.findAboutSummaryCalls()
                val site = context.findAskMetaAiBox()
                // Every call of the factory in the kept classes, counted apart from the patch's own search.
                val asks = kept.sumOf { classDef ->
                    classDef.methods.sumOf { method ->
                        method.instructions().count { instruction ->
                            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            call != null && call.definingClass == summary.factory && call.returnType == summary.factory &&
                                call.parameterTypes.map(CharSequence::toString) == FACTORY_SHAPE
                        }
                    }
                }
                assertTrue("${bundle.name}: the menus asking for the summary", asks >= 3)
                assertEquals("${bundle.name}: every ask found", asks, summary.calls.size)
                val callers = summary.calls.map { Triple(it.type, it.name, it.parameters) }.toSet()
                assertTrue("${bundle.name}: a menu that also adds the Ask box", Triple(site.type, site.name, site.parameters) !in callers)
                val row = context.mutableClassDefBy(site.type).methods.single {
                    it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
                }
                val box = (row.instructions()[site.addView] as FiveRegisterInstruction).registerD

                context.dropAboutSummary(summary)
                context.holdAskMetaAiBox(site)

                for (call in callers) {
                    val method = context.mutableClassDefBy(call.first).methods.single {
                        it.name == call.second && it.parameterTypes.map(CharSequence::toString) == call.third
                    }
                    val count = summary.calls.count { Triple(it.type, it.name, it.parameters) == call }
                    assertPassedThrough("${bundle.name} ${call.first}->${call.second}", method, count, summary.factory)
                }
                assertBoxHeld("${bundle.name} ${site.type}->${site.name}", row, box)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** Each ask's move-result is followed by the hook on its register, the answer back in it, and the cast to the factory's class. */
    private fun assertPassedThrough(what: String, method: Method, asks: Int, factory: String = FACTORY) {
        val code = method.instructions()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == ABOUT_THIS_REEL }
        assertEquals("$what: hooks", asks, hooks.size)
        for (hook in hooks) {
            assertEquals("$what: the factory's answer", Opcode.MOVE_RESULT_OBJECT, code[hook - 1].opcode)
            val register = (code[hook - 1] as OneRegisterInstruction).registerA
            val factoryCall = (code[hook - 2] as ReferenceInstruction).reference as MethodReference
            assertEquals("$what: the factory", factory, factoryCall.definingClass)
            assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
            assertEquals("$what: the register passed", register, (code[hook] as RegisterRangeInstruction).startRegister)
            assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
            assertEquals("$what: the answer's register", register, (code[hook + 1] as OneRegisterInstruction).registerA)
            assertEquals("$what: the cast", Opcode.CHECK_CAST, code[hook + 2].opcode)
            assertEquals("$what: the cast's register", register, (code[hook + 2] as OneRegisterInstruction).registerA)
            assertEquals("$what: the cast's class", factory, (code[hook + 2] as ReferenceInstruction).reference.toString())
        }
    }

    /** The patch refuses, for the reason given. */
    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    /** No addView of the box is left, and the one hook call in its place takes the same row and the same box. */
    private fun assertBoxHeld(what: String, method: Method, box: Int) {
        val code = method.instructions()
        val references = code.map { (it as? ReferenceInstruction)?.reference?.toString() }
        assertEquals("$what: addViews left", 0, references.count { it == "Landroid/view/ViewGroup;->addView(Landroid/view/View;)V" })
        val hooks = code.indices.filter { references[it] == ASK_META_AI_BOX }
        assertEquals("$what: hooks", 1, hooks.size)
        val hook = code[hooks.single()] as FiveRegisterInstruction
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, code[hooks.single()].opcode)
        assertEquals("$what: the arguments", 2, hook.registerCount)
        assertEquals("$what: the box", box, hook.registerD)
    }

    private companion object {
        const val FACTORY = "Lfixture/AiSummary;"
        const val REELS_MENU = "Lfixture/ClipsMoreOptions;"
        const val FEED_MENU = "Lfixture/FeedMoreOptions;"
        const val SUMMARY_ROW = "Lfixture/SummaryRow;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        const val LAMBDA = "Lcom/instagram/metaai/aidiscovery/AiDiscoveryMenuHelper\$createComposerView\$5;"
        const val BOX = 10
        val FACTORY_SHAPE = listOf(SESSION, "Ljava/lang/String;", "Z", "Z")
        val STATIC = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value

        /** Shaped like 450's: static, (UserSession, String, boolean, boolean), answering its own class, loading the summary flag. */
        fun factory(type: String = FACTORY, returns: String = type): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    type, "A02", FACTORY_SHAPE.map { ImmutableMethodParameter(it, null, null) }, returns, STATIC, null, null,
                    ImmutableMethodImplementation(
                        6,
                        listOf(
                            ImmutableInstruction51l(Opcode.CONST_WIDE, 0, SUMMARY_FLAG),
                            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ),
                        null, null,
                    ),
                ),
            ),
        )

        /**
         * A menu asking [FACTORY] once for each of [results]: the register taking the answer, or
         * null for an ask whose answer is dropped. [marked] puts the Reels viewer menu's purge marker in it.
         */
        fun menu(type: String, marked: Boolean = false, results: List<Int?>): ClassDef {
            val code = mutableListOf<Instruction>()
            if (marked) {
                code += ImmutableInstruction21c(
                    Opcode.CONST_STRING, 0,
                    ImmutableStringReference("android_purge_26_q3_ClipsOrganicMediaItemViewMoreOptionsController_$REELS_MENU_MARKER"),
                )
            }
            for (result in results) {
                code += ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, 4, 1, 2, 3, 4, 0, ImmutableMethodReference(FACTORY, "A02", FACTORY_SHAPE, FACTORY),
                )
                if (result != null) code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, result)
            }
            code += ImmutableInstruction10x(Opcode.RETURN_VOID)
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(ImmutableMethod(type, "A0Y", emptyList(), "V", STATIC, null, null, ImmutableMethodImplementation(20, code, null, null))),
            )
        }

        /**
         * Shaped like 450's summary row binder: its two strings, the box inflated into [BOX], copied
         * to v20 for the composer lambda (when [lambda]), then added to the row in v0 once for each of
         * [adds]. [overwrite] puts a zero in [BOX] between the lambda and the add.
         */
        fun summaryRow(type: String, adds: List<Int> = listOf(BOX), lambda: Boolean = true, overwrite: Boolean = false): ClassDef {
            val code = mutableListOf<Instruction>()
            for (string in SUMMARY_ROW_STRINGS) code += ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(string))
            code += ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, ImmutableMethodReference("Lfixture/Inflater;", "A00", emptyList(), "Landroid/view/View;"))
            code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, BOX)
            if (lambda) {
                code += ImmutableInstruction21c(Opcode.NEW_INSTANCE, 18, ImmutableTypeReference(LAMBDA))
                code += ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 20, BOX)
                code += ImmutableInstruction3rc(
                    Opcode.INVOKE_DIRECT_RANGE, 18, 3,
                    ImmutableMethodReference(LAMBDA, "<init>", listOf("Landroid/content/Context;", "Landroid/view/View;"), "V"),
                )
            }
            if (overwrite) code += ImmutableInstruction11n(Opcode.CONST_4, BOX, 0)
            for (view in adds) {
                code += ImmutableInstruction35c(
                    Opcode.INVOKE_VIRTUAL, 2, 0, view, 0, 0, 0,
                    ImmutableMethodReference("Landroid/view/ViewGroup;", "addView", listOf("Landroid/view/View;"), "V"),
                )
            }
            code += ImmutableInstruction10x(Opcode.RETURN_VOID)
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(ImmutableMethod(type, "A04", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(21, code, null, null))),
            )
        }
    }
}

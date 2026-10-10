/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns
import app.morphe.patches.instagram.feed.FeedItemStandIns.assertFilteredBeforeReturn
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.instagram.feed.filterParsedFeedItems
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
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction51l
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideMetaAiHookTest {
    /** Both hooks the patch writes are in the MetaAi the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(SEARCH_FLAG, META_AI_FILTER, FOLLOW_UP_BAR, FOLLOW_UP_STUB, HOME_BUTTON, COMPOSER_BUTTON, INBOX_ROW)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** Each read, through the config interface or a static wrapper, answers through the extension. */
    @Test
    fun everyReadOfASearchFlagAnswersThroughTheExtension() {
        val context = PatchContexts.of(
            listOf(gate(SEARCH, SEARCH_FLAGS[0]), wrapped(INBOX, SEARCH_FLAGS[1]), gate(RING, SEARCH_FLAGS[2]), gate(HOME, SEARCH_FLAGS[3])),
        )

        val reads = context.findSearchFlagReads()
        context.answerSearchFlagReads(reads)

        assertEquals(4, reads.size)
        for (type in listOf(SEARCH, INBOX, RING, HOME)) {
            assertAnsweredAfterEveryRead(type, context.mutableClassDefBy(type).methods.single { it.name == "A00" })
        }
    }

    @Test
    fun aFlagNobodyReadsFailsThePatch() {
        val context = PatchContexts.of(listOf(gate(SEARCH, SEARCH_FLAGS[0]), gate(RING, SEARCH_FLAGS[2]), gate(HOME, SEARCH_FLAGS[3])))
        assertThrows(PatchException::class.java) { context.findSearchFlagReads() }
    }

    /** A flag's id going anywhere but a boolean read is an update the patch hasn't seen. */
    @Test
    fun aFlagReadAsSomethingElseFailsThePatch() {
        val context = PatchContexts.of(
            listOf(
                gate(SEARCH, SEARCH_FLAGS[0]), gate(RING, SEARCH_FLAGS[2]), gate(HOME, SEARCH_FLAGS[3]),
                gate(INBOX, SEARCH_FLAGS[1], returns = "J", result = Opcode.MOVE_RESULT_WIDE),
            ),
        )
        assertThrows(PatchException::class.java) { context.findSearchFlagReads() }
    }

    @Test
    fun aFlagReadWithItsAnswerDroppedFailsThePatch() {
        val context = PatchContexts.of(
            listOf(
                gate(SEARCH, SEARCH_FLAGS[0]), gate(RING, SEARCH_FLAGS[2]), gate(HOME, SEARCH_FLAGS[3]),
                gate(INBOX, SEARCH_FLAGS[1], result = null),
            ),
        )
        assertThrows(PatchException::class.java) { context.findSearchFlagReads() }
    }

    /** The extension's own code may name the flags; only the app's reads are answered. */
    @Test
    fun theExtensionIsLeftAlone() {
        val own = gate("Lapp/hushgram/extension/instagram/metaai/Probe;", SEARCH_FLAGS[0])
        val context = PatchContexts.of(
            listOf(own, gate(SEARCH, SEARCH_FLAGS[0]), wrapped(INBOX, SEARCH_FLAGS[1]), gate(RING, SEARCH_FLAGS[2]), gate(HOME, SEARCH_FLAGS[3])),
        )

        val reads = context.findSearchFlagReads()

        assertEquals(setOf(SEARCH, INBOX, RING, HOME), reads.map { it.type }.toSet())
        assertEquals(4, reads.size)
    }

    /** The bar's stub check answers through the extension; the pills' stub inside the bar is left alone. */
    @Test
    fun theFollowUpBarsStubCheckAnswersThroughTheExtension() {
        val context = PatchContexts.of(listOf(barSetup(RESULTS)))

        context.dropFollowUpBar(context.findFollowUpBarCheck())

        assertDropped(RESULTS, context.mutableClassDefBy(RESULTS).methods.single { it.name == "A06" })
    }

    @Test
    fun twoBarSetupsFailThePatch() {
        val context = PatchContexts.of(listOf(barSetup(RESULTS), barSetup("Lfixture/OtherResults;")))
        assertThrows(PatchException::class.java) { context.findFollowUpBarCheck() }
    }

    @Test
    fun aBarSetupWithoutTheCheckFailsThePatch() {
        val context = PatchContexts.of(listOf(barSetup(RESULTS, checked = 1)))
        assertThrows(PatchException::class.java) { context.findFollowUpBarCheck() }
    }

    /** A check of one view before inflating another is an update the patch hasn't seen. */
    @Test
    fun aCheckOfAnotherViewFailsThePatch() {
        val context = PatchContexts.of(listOf(barSetup(RESULTS, checkedId = 6)))
        assertThrows(PatchException::class.java) { context.findFollowUpBarCheck() }
    }

    /** On 450 the lookup is the findViewById, so the page's view answers through the extension before its null test. */
    @Test
    fun aPageViewCheckAnswersThroughTheExtension() {
        val context = PatchContexts.of(listOf(viewCheckedBarSetup(RESULTS)))

        val check = context.findFollowUpBarCheck()
        context.dropFollowUpBar(check)

        assertEquals(2, check.moveResult)
        assertEquals(8, check.register)
        assertDropped(RESULTS, context.mutableClassDefBy(RESULTS).methods.single { it.name == "A06" })
    }

    /**
     * The x86_64 build 385611440 inlines the lookup, a findViewById cast to ViewStub, and the page's
     * view still answers through the extension before its null test.
     */
    @Test
    fun anInlinedLookupsPageViewCheckAnswersThroughTheExtension() {
        val context = PatchContexts.of(listOf(viewCheckedBarSetup(RESULTS, inlined = true)))

        val check = context.findFollowUpBarCheck()
        context.dropFollowUpBar(check)

        assertEquals(2, check.moveResult)
        assertEquals(8, check.register)
        assertDropped(RESULTS, context.mutableClassDefBy(RESULTS).methods.single { it.name == "A06" })
    }

    /**
     * With the bar's lookup inlined too, 449's setup casts two stubs, the bar's and the pills'
     * inside it. Neither is searched in a view read off a field, the bar's in one the method was
     * handed and the pills' in the inflated bar, so neither is taken.
     */
    @Test
    fun twoInlinedLookupsFailThePatch() {
        val context = PatchContexts.of(listOf(barSetup(RESULTS, inlined = true)))
        assertThrows(PatchException::class.java) { context.findFollowUpBarCheck() }
    }

    /**
     * A method making a static stub lookup isn't one that inlines it, even when that lookup's stub
     * isn't inflated where the finder looks: the pills' findViewById and cast, searched in the bar
     * read back off a field, would otherwise be taken for the bar's lookup.
     */
    @Test
    fun aStaticLookupWhoseInflateIsntMatchedFailsThePatch() {
        val context = PatchContexts.of(listOf(inflatedThroughACopy(RESULTS)))
        val failure = assertThrows(PatchException::class.java) { context.findFollowUpBarCheck() }
        assertTrue("$failure", failure.message!!.contains("looks up a stub to inflate 0 times"))
    }

    /** 385611440's inlined lookup searches the page's view read off a field; one searching a call's answer isn't taken. */
    @Test
    fun anInlinedLookupInAViewNotReadOffAFieldFailsThePatch() {
        val context = PatchContexts.of(listOf(viewCheckedBarSetup(RESULTS, inlined = true, viewFromCall = true)))
        val failure = assertThrows(PatchException::class.java) { context.findFollowUpBarCheck() }
        assertTrue("$failure", failure.message!!.contains("looks up a stub to inflate 0 times"))
    }

    @Test
    fun aLookupWithoutAnyCheckFailsThePatch() {
        val context = PatchContexts.of(listOf(viewCheckedBarSetup(RESULTS, tested = 2)))
        assertThrows(PatchException::class.java) { context.findFollowUpBarCheck() }
    }

    /** A jump in between the view being set and the lookup would skip the hook on that path. */
    @Test
    fun aJumpPastTheViewFailsThePatch() {
        val context = PatchContexts.of(listOf(viewCheckedBarSetup(RESULTS, jumpIn = true)))
        assertThrows(PatchException::class.java) { context.findFollowUpBarCheck() }
    }

    @Test
    fun theExtensionsOwnBarSetupIsLeftAlone() {
        val context = PatchContexts.of(listOf(barSetup("Lapp/hushgram/extension/instagram/metaai/Probe;"), barSetup(RESULTS)))
        assertEquals(RESULTS, context.findFollowUpBarCheck().type)
    }

    /**
     * In each declared build, and in each other build of a declared version, the bar setup is found
     * and its stub check answers through the extension. 385611440 inlines the lookup (#95).
     */
    @Test
    fun eachDeclaredBuildDropsTheFollowUpBar() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                dropsTheFollowUpBarIn(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
        for (base in Fixtures.otherBuilds()) dropsTheFollowUpBarIn(base, base.parentFile.name)
    }

    private fun dropsTheFollowUpBarIn(bundle: java.io.File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { it.holdsAll(FOLLOW_UP_SETUP) }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(holders)

        val check = context.findFollowUpBarCheck()
        context.dropFollowUpBar(check)

        val method = context.mutableClassDefBy(check.type).methods.single {
            it.name == check.name && it.parameterTypes.map(CharSequence::toString) == check.parameters
        }
        assertDropped("$label ${check.type}->${check.name}", method)
    }

    /** 450's bar stub: its findViewById's answer goes through the extension before its null test and the helper inflating it. */
    @Test
    fun theFollowUpBarsOwnStubAnswersThroughTheExtension() {
        val context = PatchContexts.of(listOf(inflatedBarSetup(RESULTS)))

        val stub = context.findFollowUpBarStub()
        context.dropFollowUpBarStub(stub)

        assertEquals(5, stub.moveResult)
        assertEquals(0, stub.register)
        assertStubDropped(RESULTS, context.mutableClassDefBy(RESULTS).methods.single { it.name == "A06" })
    }

    /** A stub the page finds but never hands to the helper isn't the bar's. */
    @Test
    fun aBarStubThatIsNeverInflatedFailsThePatch() {
        val context = PatchContexts.of(listOf(inflatedBarSetup(RESULTS, inflated = false)))
        val failure = assertThrows(PatchException::class.java) { context.findFollowUpBarStub() }
        assertTrue("$failure", failure.message!!.contains("0 times"))
    }

    @Test
    fun aHelperInflatingAnotherIdFailsThePatch() {
        val context = PatchContexts.of(listOf(inflatedBarSetup(RESULTS, inflatedId = 6)))
        assertThrows(PatchException::class.java) { context.findFollowUpBarStub() }
    }

    /** The id set again between the test and the helper means the helper inflates something else. */
    @Test
    fun anIdChangedBeforeTheHelperFailsThePatch() {
        val context = PatchContexts.of(listOf(inflatedBarSetup(RESULTS, holderInto = 7)))
        assertThrows(PatchException::class.java) { context.findFollowUpBarStub() }
    }

    @Test
    fun twoBarStubsFailThePatch() {
        val context = PatchContexts.of(listOf(inflatedBarSetup(RESULTS, copies = 2)))
        val failure = assertThrows(PatchException::class.java) { context.findFollowUpBarStub() }
        assertTrue("$failure", failure.message!!.contains("2 times"))
    }

    /** A jump straight to the stub's null test would skip the hook on that path. */
    @Test
    fun aJumpToTheBarStubsTestFailsThePatch() {
        val context = PatchContexts.of(listOf(inflatedBarSetup(RESULTS, jumpIn = true)))
        assertThrows(PatchException::class.java) { context.findFollowUpBarStub() }
    }

    /** A site that moved after it was found is refused, never hooked in the wrong place. */
    @Test
    fun aBarStubThatMovedFailsTheDrop() {
        val context = PatchContexts.of(listOf(inflatedBarSetup(RESULTS)))
        val stub = context.findFollowUpBarStub()
        context.mutableClassDefBy(RESULTS).methods.single { it.name == "A06" }.addInstructions(0, "nop")
        assertThrows(PatchException::class.java) { context.dropFollowUpBarStub(stub) }
    }

    /**
     * In each declared build, and in each other build of a declared version, the bar's own stub is
     * found in the setup that holds the pills' check, before it, and with both hooks in, the
     * pills' check and the bar's stub each answer through the extension where they should.
     */
    @Test
    fun eachDeclaredBuildLeavesTheFollowUpBarItselfOut() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                leavesTheBarOutIn(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
        for (base in Fixtures.otherBuilds()) leavesTheBarOutIn(base, base.parentFile.name)
    }

    private fun leavesTheBarOutIn(bundle: java.io.File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { it.holdsAll(FOLLOW_UP_SETUP) }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(holders)

        val check = context.findFollowUpBarCheck()
        val stub = context.findFollowUpBarStub()
        assertEquals("$label: one setup", "${check.type}->${check.name}", "${stub.type}->${stub.name}")
        assertTrue("$label: the bar's stub comes before the pills' check", stub.moveResult < check.moveResult)
        context.dropFollowUpBar(check)
        context.dropFollowUpBarStub(stub)

        val method = context.mutableClassDefBy(stub.type).methods.single {
            it.name == stub.name && it.parameterTypes.map(CharSequence::toString) == stub.parameters
        }
        assertDropped("$label ${check.type}->${check.name} pills", method)
        assertStubDropped("$label ${stub.type}->${stub.name} bar", method)
    }

    /**
     * The method has exactly one call to the bar's stub hook, on the findViewById's answer, with the
     * answer back in it, then the null test of it, and then the static helper answering a View
     * handed the view and id the findViewById searched.
     */
    private fun assertStubDropped(what: String, method: Method) {
        val code = method.instructions()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == FOLLOW_UP_STUB }
        assertEquals("$what: hooks", 1, hooks.size)
        val hook = hooks.single()
        assertEquals("$what: the lookup", "Landroid/view/View;->findViewById(I)Landroid/view/View;", (code[hook - 2] as ReferenceInstruction).reference.toString())
        val searched = code[hook - 2].registers()
        assertEquals("$what: the lookup's answer", Opcode.MOVE_RESULT_OBJECT, code[hook - 1].opcode)
        val register = (code[hook - 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("$what: the call's register", listOf(register), code[hook].registers())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the register", register, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the test", Opcode.IF_EQZ, code[hook + 2].opcode)
        assertEquals("$what: the tested register", register, (code[hook + 2] as OneRegisterInstruction).registerA)
        val inflate = (hook + 3 until code.size).first { code[it].opcode == Opcode.INVOKE_STATIC || code[it].opcode == Opcode.INVOKE_STATIC_RANGE }
        assertEquals("$what: the helper's answer", "Landroid/view/View;", ((code[inflate] as ReferenceInstruction).reference as MethodReference).returnType)
        assertEquals("$what: the helper's view and id", searched, code[inflate].registers())
    }

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    /** Each button name the bar takes from the server's list answers through the extension; the later loop is left alone. */
    @Test
    fun homesButtonNamesAnswerThroughTheExtension() {
        val context = PatchContexts.of(listOf(homeBar(HOME_BAR)))

        context.dropHomeButton(context.findHomeButtonNames())

        assertNamesPassedThrough(HOME_BAR, context.mutableClassDefBy(HOME_BAR).methods.single { it.name == "AdW" })
    }

    @Test
    fun twoHomeBarsFailThePatch() {
        val context = PatchContexts.of(listOf(homeBar(HOME_BAR), homeBar("Lfixture/OtherHomeBar;")))
        assertThrows(PatchException::class.java) { context.findHomeButtonNames() }
    }

    /** A "meta_ai" compared with some other register means the loop isn't the one the patch knows. */
    @Test
    fun aHomeBarComparingAnotherNameFailsThePatch() {
        val context = PatchContexts.of(listOf(homeBar(HOME_BAR, compared = 0)))
        assertThrows(PatchException::class.java) { context.findHomeButtonNames() }
    }

    @Test
    fun aHomeBarWithoutTheNullTestFailsThePatch() {
        val context = PatchContexts.of(listOf(homeBar(HOME_BAR, tested = 0)))
        assertThrows(PatchException::class.java) { context.findHomeButtonNames() }
    }

    @Test
    fun eachDeclaredBuildDropsHomesMetaAiButton() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { it.holdsAll(HOME_BAR_SETUP) }) holders += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(holders)

                val names = context.findHomeButtonNames()
                context.dropHomeButton(names)

                val method = context.mutableClassDefBy(names.type).methods.single {
                    it.name == names.name && it.parameterTypes.map(CharSequence::toString) == names.parameters
                }
                assertNamesPassedThrough("${bundle.name} ${names.type}->${names.name}", method)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun theFeedParseHelperAnswersThroughTheMetaAiFilter() {
        val context = PatchContexts.of(FeedItemStandIns.classes(listOf("MEDIA", "AD", "CLIPS_NETEGO") + META_AI_UNITS))

        context.filterParsedFeedItems("Hide Meta AI", META_AI_FILTER, META_AI_UNITS)

        val helper = context.mutableClassDefBy(FeedItemStandIns.ITEM).methods.single { it.name == "A02" }
        assertFilteredBeforeReturn("helper", helper, META_AI_FILTER)
    }

    @Test
    fun aKindEnumMissingAMetaAiUnitFailsThePatch() {
        val context = PatchContexts.of(FeedItemStandIns.classes(listOf("MEDIA", "AD") + META_AI_UNITS.drop(1)))
        assertThrows(PatchException::class.java) {
            context.filterParsedFeedItems("Hide Meta AI", META_AI_FILTER, META_AI_UNITS)
        }
    }

    /**
     * In each declared build every flag is read, every read is a boolean whose answer is kept, and
     * after the patch each answer goes through the extension. On 449 that's seven reads.
     */
    @Test
    fun eachDeclaredBuildAnswersEveryReadOfEveryFlag() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.loadsAFlag() }) holders += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(holders)

                val reads = context.findSearchFlagReads()
                context.answerSearchFlagReads(reads)

                assertEquals("${bundle.name}: flags read", SEARCH_FLAGS.toSet(), reads.map { it.flag }.toSet())
                assertEquals("${bundle.name}: reads", 7, reads.size)
                for (read in reads) {
                    val method = context.mutableClassDefBy(read.type).methods.single {
                        it.name == read.name && it.parameterTypes.map(CharSequence::toString) == read.parameters
                    }
                    assertAnsweredAfterEveryRead("${bundle.name} ${read.type}->${read.name}", method)
                }
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun Method.loadsAFlag(): Boolean = implementation?.instructions?.any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral in SEARCH_FLAGS
    } == true

    /** After each flag load, its read's move-result is followed by the extension call and a move-result into the same register. */
    private fun assertAnsweredAfterEveryRead(what: String, method: Method) {
        val code = method.instructions()
        val loads = code.indices.filter { code[it].opcode == Opcode.CONST_WIDE && (code[it] as WideLiteralInstruction).wideLiteral in SEARCH_FLAGS }
        assertTrue("$what loads no flag", loads.isNotEmpty())
        for (load in loads) {
            val result = (load + 1 until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
            val register = (code[result] as OneRegisterInstruction).registerA
            val call: Instruction = code[result + 1]
            assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("$what: the hook", SEARCH_FLAG, (call as ReferenceInstruction).reference.toString())
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
            assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
        }
    }

    private fun Method.holdsAll(strings: List<String>): Boolean {
        val held = implementation?.instructions
            ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string } ?: return false
        return held.containsAll(strings)
    }

    /**
     * The method has exactly one call to the extension, on the register the instruction before it
     * sets, with the answer back in it. On 449 that's the stub check's move-result, followed by the
     * null test; on 450 it's the page's view, tested for null and then searched for the stub.
     */
    private fun assertDropped(what: String, method: Method) {
        val code = method.instructions()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == FOLLOW_UP_BAR }
        assertEquals("$what: hooks", 1, hooks.size)
        val hook = hooks.single()
        val find = (code[hook - 2] as? ReferenceInstruction)?.reference?.toString()
        if (find != "Landroid/view/View;->findViewById(I)Landroid/view/View;") return assertDroppedBeforeTheViewTest(what, code, hook)
        val register = (code[hook - 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the check's result", Opcode.MOVE_RESULT_OBJECT, code[hook - 1].opcode)
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the register", register, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the test", Opcode.IF_EQZ, code[hook + 2].opcode)
        assertEquals("$what: the tested register", register, (code[hook + 2] as OneRegisterInstruction).registerA)
    }

    /**
     * The view's iget is followed by the extension call on its register and the answer back in it,
     * then its null test and the lookup searching it: a static call answering a ViewStub, or the
     * findViewById 385611440 inlines it into, cast to ViewStub.
     */
    private fun assertDroppedBeforeTheViewTest(what: String, code: List<Instruction>, hook: Int) {
        assertEquals("$what: the view", Opcode.IGET_OBJECT, code[hook - 1].opcode)
        val register = (code[hook - 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the register", register, (code[hook + 1] as OneRegisterInstruction).registerA)
        val test = (hook + 2 until code.size).first {
            code[it].opcode == Opcode.IF_EQZ && (code[it] as OneRegisterInstruction).registerA == register
        }
        val lookup = (test + 1 until code.size).first { code[it].opcode == Opcode.INVOKE_STATIC || code[it].opcode == Opcode.INVOKE_VIRTUAL }
        val call = (code[lookup] as ReferenceInstruction).reference as MethodReference
        if (code[lookup].opcode == Opcode.INVOKE_VIRTUAL) {
            assertEquals("$what: the inlined lookup", "Landroid/view/View;->findViewById(I)Landroid/view/View;", call.toString())
            assertEquals("$what: the stub's cast", "Landroid/view/ViewStub;", (code[lookup + 2] as ReferenceInstruction).reference.toString())
        } else {
            assertEquals("$what: the lookup's return", "Landroid/view/ViewStub;", call.returnType)
        }
        assertEquals("$what: the searched view", register, (code[lookup] as FiveRegisterInstruction).registerC)
    }

    /** The name's cast is followed by the extension call on its register, the answer back in it, and the null test; one call in all. */
    private fun assertNamesPassedThrough(what: String, method: Method) {
        val code = method.instructions()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == HOME_BUTTON }
        assertEquals("$what: hooks", 1, hooks.size)
        val hook = hooks.single()
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[hook - 1].opcode)
        val register = (code[hook - 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the register", register, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the test", Opcode.IF_EQZ, code[hook + 2].opcode)
        assertEquals("$what: the tested register", register, (code[hook + 2] as OneRegisterInstruction).registerA)
    }

    private companion object {
        const val HOME_BAR = "Lfixture/MainFeedActionBar;"
        const val RESULTS = "Lfixture/SearchResultsBar;"
        const val SEARCH = "Lfixture/SearchGate;"
        const val INBOX = "Lfixture/InboxScreen;"
        const val RING = "Lfixture/InboxRingGate;"
        const val HOME = "Lfixture/HomeBarGate;"
        const val CONFIG = "Lfixture/MobileConfig;"
        const val SESSION = "Lfixture/UserSession;"

        /** Shaped like 449's search gate: the id, a cast of the config, and the interface's boolean read. */
        fun gate(type: String, flag: Long, returns: String = "Z", result: Opcode? = Opcode.MOVE_RESULT) = holder(
            type,
            listOfNotNull(
                ImmutableInstruction51l(Opcode.CONST_WIDE, 0, flag),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 2, ImmutableTypeReference(CONFIG)),
                ImmutableInstruction35c(
                    Opcode.INVOKE_INTERFACE, 3, 2, 0, 1, 0, 0, ImmutableMethodReference(CONFIG, "BXd", listOf("J"), returns),
                ),
                result?.let { ImmutableInstruction11x(it, 0) },
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
        )

        /** Shaped like 449's inbox: the id passed to a static wrapper after the config. */
        fun wrapped(type: String, flag: Long) = holder(
            type,
            listOf(
                ImmutableInstruction51l(Opcode.CONST_WIDE, 0, flag),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, 3, 2, 0, 1, 0, 0,
                    ImmutableMethodReference("Lfixture/Config;", "A1A", listOf("Ljava/lang/Object;", "J"), "Z"),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
        )

        /**
         * Shaped like 449's search results bottom bar setup: its two strings, a findViewById of the
         * bar's stub whose answer is tested for null, a static lookup of the same id handing the stub
         * to inflate(), and then the pills' stub, found inside the bar and cast, inflated too.
         * [checked] is the register the null test reads and [checkedId] the id register the check
         * passes; the lookup always passes v7. [inlined] makes the bar's lookup a findViewById and
         * cast, the way the pills' is.
         */
        fun barSetup(type: String, checked: Int = 0, checkedId: Int = 7, inlined: Boolean = false): ClassDef {
            val view = "Landroid/view/View;"
            val stub = "Landroid/view/ViewStub;"
            val findView = ImmutableMethodReference(view, "findViewById", listOf("I"), view)
            val inflate = ImmutableMethodReference(stub, "inflate", emptyList(), view)
            val lookup = if (inlined) {
                listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 3, 7, 0, 0, 0, findView),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                    ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(stub)),
                )
            } else {
                listOf(
                    ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 2, 3, 7, 0, 0, 0,
                        ImmutableMethodReference("Lfixture/Views;", "A0C", listOf(view, "I"), stub),
                    ),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                )
            }
            val code = listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference("keyboardHeightChangeDetector")),
                ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference("bottomSearchSuggestionPillsHelper")),
                ImmutableInstruction31i(Opcode.CONST, 7, 0x7f0b22bd),
                ImmutableInstruction31i(Opcode.CONST, 6, 0x7f0b22be),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 3, checkedId, 0, 0, 0, findView),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction21t(Opcode.IF_EQZ, checked, if (inlined) 25 else 23),
            ) + lookup + listOf(
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, inflate),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction31i(Opcode.CONST, 5, 0x7f0b06a7),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 5, 0, 0, 0, findView),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference(stub)),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, inflate),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "A06", listOf(ImmutableMethodParameter("Lkotlin/jvm/functions/Function1;", null, null)), "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                        ImmutableMethodImplementation(10, code, null, null),
                    ),
                ),
            )
        }

        /**
         * Shaped like 450's search results bottom bar setup: its two strings, the page's view read
         * into v8 and tested for null, then a static lookup (450's findViewById and cast) of the
         * bar's stub in it, tested for null and for a parent before inflate(). [tested] is the
         * register the first null test reads; [jumpIn] adds a jump to the lookup's id after the end.
         * [inlined] makes the lookup 385611440's, the findViewById and the cast themselves.
         * [viewFromCall] has the page's view answered by a call instead of read off a field.
         */
        fun viewCheckedBarSetup(
            type: String,
            tested: Int = 8,
            jumpIn: Boolean = false,
            inlined: Boolean = false,
            viewFromCall: Boolean = false,
        ): ClassDef {
            val view = "Landroid/view/View;"
            val stub = "Landroid/view/ViewStub;"
            // The cast takes two code units more, between the first test and where it goes.
            val longer = if (inlined) 2 else 0
            // Both ways of setting the view come before every branch and its target, so no offset changes.
            val setView = if (viewFromCall) {
                listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 10, 0, 0, 0, 0, ImmutableMethodReference(type, "A04", emptyList(), view)),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 8),
                )
            } else {
                listOf(ImmutableInstruction22c(Opcode.IGET_OBJECT, 8, 10, ImmutableFieldReference(type, "A04", view)), ImmutableInstruction10x(Opcode.NOP))
            }
            val code = listOfNotNull(
                ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference("keyboardHeightChangeDetector")),
                ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference("bottomSearchSuggestionPillsHelper")),
                setView[0],
                setView[1],
                ImmutableInstruction21t(Opcode.IF_EQZ, tested, 21 + longer),
                ImmutableInstruction31i(Opcode.CONST, 0, 0x7f0b3fc8),
                if (inlined) {
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 8, 0, 0, 0, 0, ImmutableMethodReference(view, "findViewById", listOf("I"), view))
                } else {
                    ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 2, 8, 0, 0, 0, 0,
                        ImmutableMethodReference("Lfixture/Views;", "A06", listOf(view, "I"), stub),
                    )
                },
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                if (inlined) ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference(stub)) else null,
                ImmutableInstruction21t(Opcode.IF_EQZ, 1, 12),
                ImmutableInstruction35c(
                    Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0,
                    ImmutableMethodReference(view, "getParent", emptyList(), "Landroid/view/ViewParent;"),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction21t(Opcode.IF_EQZ, 0, 6),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, ImmutableMethodReference(stub, "inflate", emptyList(), view)),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
                if (jumpIn) ImmutableInstruction10t(Opcode.GOTO, -20 - longer) else null,
            )
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "A06", listOf(ImmutableMethodParameter("Lkotlin/jvm/functions/Function1;", null, null)), "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                        ImmutableMethodImplementation(12, code, null, null),
                    ),
                ),
            )
        }

        /**
         * Shaped like 450's search results bottom bar setup around the bar's own stub: its two
         * strings, the page's view read into v3 and the bar's id into v7, then a findViewById of
         * v7 in v3 whose answer is tested for null, the page's state holder asked for, and a
         * static (View, int) helper answering a View, handed v3 and v7, which inflates the stub:
         *
         *     0, 1 const-string | 2 iget-object v3, A03 | 3 const v7, id | 4 findViewById {v3, v7}
         *     5 move-result-object v0 | 6 if-eqz v0 -> 12 | 7 invoke-virtual {v10}, A05
         *     8 move-result-object v1 | 9 invoke-static {v3, v7}, A0C | 10 move-result-object v0
         *     11 iput-object v0, v1, A03 | 12 return-void
         *
         * [copies] repeats the lookup, from the findViewById on. [inflated] false leaves the helper
         * out, [inflatedId] is the id register the helper is handed, [holderInto] the register the
         * holder lands in, and [jumpIn] adds a jump to the first null test after the end.
         */
        fun inflatedBarSetup(
            type: String,
            copies: Int = 1,
            inflated: Boolean = true,
            inflatedId: Int = 7,
            holderInto: Int = 1,
            jumpIn: Boolean = false,
        ): ClassDef {
            val view = "Landroid/view/View;"
            val findView = ImmutableMethodReference(view, "findViewById", listOf("I"), view)
            // The lookup in code units: findViewById 3, move-result 1, the test 2, the holder 3 and its move-result 1,
            // the helper 3 and its move-result 1, the iput 2. The test jumps past the iput.
            val units = if (inflated) 16 else 12
            val lookup = listOfNotNull(
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 3, 7, 0, 0, 0, findView),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction21t(Opcode.IF_EQZ, 0, units - 4),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 10, 0, 0, 0, 0, ImmutableMethodReference(type, "A05", emptyList(), "Ljava/lang/Object;")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, holderInto),
                if (inflated) {
                    ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 2, 3, inflatedId, 0, 0, 0,
                        ImmutableMethodReference("Lfixture/Views;", "A0C", listOf(view, "I"), view),
                    )
                } else {
                    null
                },
                if (inflated) ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0) else null,
                ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, 1, ImmutableFieldReference("Lfixture/BarHolder;", "A03", view)),
            )
            // Two const-strings, the iget and the const take 9 code units before the first lookup.
            val code = listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference("keyboardHeightChangeDetector")),
                ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference("bottomSearchSuggestionPillsHelper")),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, 10, ImmutableFieldReference(type, "A03", view)),
                ImmutableInstruction31i(Opcode.CONST, 7, 0x7f0b22f5),
            ) + (1..copies).flatMap { lookup } + listOfNotNull(
                ImmutableInstruction10x(Opcode.RETURN_VOID),
                if (jumpIn) ImmutableInstruction10t(Opcode.GOTO, 9 + 4 - (9 + units * copies + 1)) else null,
            )
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "A06", listOf(ImmutableMethodParameter("Lkotlin/jvm/functions/Function1;", null, null)), "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                        ImmutableMethodImplementation(12, code, null, null),
                    ),
                ),
            )
        }

        /**
         * A bar setup making a static stub lookup and inflating its stub through a copy, then
         * finding the pills' stub in the inflated bar, kept in a field and read back, and inflating
         * that:
         *
         *     2 iget-object v8, A04 | 3 if-eqz v8 -> 19 | 4 const v0, id | 5 invoke-static {v8, v0}, A06
         *     6 move-result-object v1 | 7 move-object v3, v1 | 8 inflate {v3} | 9 move-result-object v4
         *     10 iput-object v4, A05 | 11 iget-object v5, A05 | 12 if-eqz v5 -> 19 | 13 const v6, id
         *     14 findViewById {v5, v6} | 15 move-result-object v7 | 16 check-cast v7, ViewStub
         *     17 inflate {v7} | 18 move-result-object v7 | 19 return-void
         */
        fun inflatedThroughACopy(type: String): ClassDef {
            val view = "Landroid/view/View;"
            val stub = "Landroid/view/ViewStub;"
            val inflate = ImmutableMethodReference(stub, "inflate", emptyList(), view)
            val bar = ImmutableFieldReference(type, "A05", view)
            val code = listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference("keyboardHeightChangeDetector")),
                ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference("bottomSearchSuggestionPillsHelper")),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 8, 10, ImmutableFieldReference(type, "A04", view)),
                ImmutableInstruction21t(Opcode.IF_EQZ, 8, 33),
                ImmutableInstruction31i(Opcode.CONST, 0, 0x7f0b3fc8),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, 2, 8, 0, 0, 0, 0,
                    ImmutableMethodReference("Lfixture/Views;", "A06", listOf(view, "I"), stub),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction12x(Opcode.MOVE_OBJECT, 3, 1),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 3, 0, 0, 0, 0, inflate),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),
                ImmutableInstruction22c(Opcode.IPUT_OBJECT, 4, 10, bar),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 5, 10, bar),
                ImmutableInstruction21t(Opcode.IF_EQZ, 5, 15),
                ImmutableInstruction31i(Opcode.CONST, 6, 0x7f0b06a7),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 5, 6, 0, 0, 0, ImmutableMethodReference(view, "findViewById", listOf("I"), view)),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 7),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 7, ImmutableTypeReference(stub)),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 7, 0, 0, 0, 0, inflate),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 7),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "A06", listOf(ImmutableMethodParameter("Lkotlin/jvm/functions/Function1;", null, null)), "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                        ImmutableMethodImplementation(12, code, null, null),
                    ),
                ),
            )
        }

        /**
         * Shaped like 449's Home top bar setup: its strings, a name taken from the server's list
         * (next(), cast, a null test, then length()), the "meta_ai" case comparing that name, and a
         * later loop over names cast without a null test. [tested] is the register the null test
         * reads and [compared] the one compared with "meta_ai"; the name is in v9.
         */
        fun homeBar(type: String, tested: Int = 9, compared: Int = 9): ClassDef {
            val next = ImmutableMethodReference("Ljava/util/Iterator;", "next", emptyList(), "Ljava/lang/Object;")
            val string = ImmutableTypeReference("Ljava/lang/String;")
            val code = listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("MainFeedActionBarDelegate:configureActionBar")),
                ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 1, 0, 0, 0, 0, next),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 9),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 9, string),
                ImmutableInstruction21t(Opcode.IF_EQZ, tested, 22),
                ImmutableInstruction35c(
                    Opcode.INVOKE_VIRTUAL, 1, 9, 0, 0, 0, 0, ImmutableMethodReference("Ljava/lang/String;", "length", emptyList(), "I"),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction21c(Opcode.CONST_STRING, 8, ImmutableStringReference("meta_ai")),
                ImmutableInstruction35c(
                    Opcode.INVOKE_VIRTUAL, 2, compared, 8, 0, 0, 0,
                    ImmutableMethodReference("Ljava/lang/String;", "equals", listOf("Ljava/lang/Object;"), "Z"),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 1, 0, 0, 0, 0, next),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 0, string),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, ImmutableMethodReference("Lfixture/Bar;", "A00", listOf("Ljava/lang/String;"), "Z"),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "AdW", listOf(ImmutableMethodParameter("Lfixture/ActionBar;", null, null)), "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                        ImmutableMethodImplementation(12, code, null, null),
                    ),
                ),
            )
        }

        fun holder(type: String, code: List<Instruction>): ClassDef {
            val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "A00", listOf(ImmutableMethodParameter(SESSION, null, null)), "Z", flags, null, null,
                        ImmutableMethodImplementation(3, code, null, null),
                    ),
                ),
            )
        }
    }
}

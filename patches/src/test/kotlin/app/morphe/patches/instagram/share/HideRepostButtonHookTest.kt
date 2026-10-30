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
import app.morphe.patches.instagram.feed.FeedStateWrite
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideRepostButtonHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val hooks = listOf(
            HIDE_REPOSTS, REPOSTS_ELIGIBLE, REPOSTS_ELIGIBLE_MATCH, REPOSTS_FEED_UFI, REPOSTS_FEED_COMPONENT, REPOSTS_FEED_RESTORE, REPOSTS_FEED_STATE,
        )
        for (hook in hooks) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** The field's hash is the hash of its name, the key Instagram's data trees use. */
    @Test
    fun theHashIsTheFieldNames() {
        assertEquals(-0x207dadd2, REPOSTS_HASH)
    }

    /**
     * The model's getter answers FALSE first on a yes, both tree reads of the field are filtered
     * right after their answer, a read of another field is left alone, and so is the model's own
     * copy of the field.
     */
    @Test
    fun theGetterAndEveryTreeReadAreHooked() {
        val context = PatchContexts.of(classes())

        val sites = context.findRepostSites()
        assertEquals("A3o", sites.getter)
        assertEquals(listOf(2 to 2, 6 to 3), sites.reads.map { it.at to it.register })
        assertTrue(sites.reads.all { it.type == READER })
        context.guardRepostGetter(sites.getter)
        sites.reads.groupBy { Triple(it.type, it.name, it.parameters) }.values.forEach { context.filterRepostReads(it) }
        val feedUfi = context.findFeedUfiSite()
        context.hideFeedUfi(feedUfi)

        assertGetterGuarded("stand-in", context.mutableClassDefBy(MEDIA).methods.single { it.name == "A3o" })
        val reader = context.mutableClassDefBy(READER).methods.single()
        // The first read's filter moves the second's answer from 6 to 8.
        assertReadsFiltered("stand-in", reader, listOf(2, 8))
        val copy = context.mutableClassDefBy(MEDIA).methods.single { it.name == "A06" }
        assertEquals(0, copy.implementation!!.instructions.count { it.names(REPOSTS_ELIGIBLE) })
        assertFeedUfiHidden("stand-in", context.mutableClassDefBy(UFI_BINDER).methods.single { it.name == "bind" })
    }

    @Test
    fun aSecondGetterFailsThePatch() {
        val context = PatchContexts.of(classes(secondGetter = true))
        assertThrows(PatchException::class.java) { context.findRepostSites() }
    }

    /** Without the field's name, the getter could be any Boolean the model keys by that number. */
    @Test
    fun aGetterWithoutTheFieldsNameFailsThePatch() {
        val context = PatchContexts.of(classes(getterString = false))
        assertThrows(PatchException::class.java) { context.findRepostSites() }
    }

    @Test
    fun aGetterWithNoRegisterToSpareFailsThePatch() {
        val context = PatchContexts.of(classes(getterRegisters = 1))
        assertThrows(PatchException::class.java) { context.findRepostSites() }
    }

    /** A read whose answer isn't moved out straight away is a shape the filter can't follow. */
    @Test
    fun aReadDroppingItsAnswerFailsThePatch() {
        val context = PatchContexts.of(classes(dropsAnswer = true))
        assertThrows(PatchException::class.java) { context.findRepostSites() }
    }

    @Test
    fun noTreeReadFailsThePatch() {
        val context = PatchContexts.of(classes().filter { it.type != READER })
        assertThrows(PatchException::class.java) { context.findRepostSites() }
    }

    @Test
    fun noFeedUfiBinderFailsThePatch() {
        val context = PatchContexts.of(classes().filter { it.type != UFI_BINDER })
        assertThrows(PatchException::class.java) { context.findFeedUfiSite() }
    }

    @Test
    fun aNativeBranchToShareCannotSkipHidingRepost() {
        val context = PatchContexts.of(listOf(feedUfiBinder(branchToShare = true)))
        context.hideFeedUfi(context.findFeedUfiSite())
        val code = context.mutableClassDefBy(UFI_BINDER).methods.single().implementation!!.instructions
        val branch = code.filterIsInstance<BuilderOffsetInstruction>().single()
        val hide = code.indexOfFirst { it.names(REPOSTS_FEED_UFI) }
        assertEquals("The native no-count branch must enter the hide, not jump over it", hide - 2, branch.target.location.index)
    }

    @Test
    fun theComponentRendererHidesItsWholeResultBeforeNativeDrawing() {
        val context = PatchContexts.of(listOf(feedComponent()))
        val render = context.findFeedRepostComponent()
        assertEquals("an unmerged renderer is guarded first thing", 0, render.at)
        context.hideFeedComponent(render)
        assertComponentGuarded(context.mutableClassDefBy(render.method.definingClass).methods.single(), 0)
    }

    /**
     * Merged with another component the way 450's Redex does it, only the part that draws Repost
     * returns nothing, and the guard uses a register the part writes before reading.
     */
    @Test
    fun aMergedRendererHidesOnlyItsRepostPart() {
        val context = PatchContexts.of(listOf(mergedComponent()))
        val render = context.findFeedRepostComponent()
        assertEquals("the Repost part starts after its class check", 7, render.at)
        context.hideFeedComponent(render)
        val method = context.mutableClassDefBy(render.method.definingClass).methods.single()
        assertComponentGuarded(method, 7)
        val code = method.implementation!!.instructions.toList()
        assertEquals("the other part still draws", Opcode.CONST_4, code[3].opcode)
        assertEquals("the other part still draws", Opcode.RETURN_OBJECT, code[4].opcode)
        assertEquals("the Repost part's own code follows", REPOSTS_UFI_ICON_ID, (code[12] as NarrowLiteralInstruction).narrowLiteral)
    }

    @Test
    fun aMergedRendererWithTwoRepostPartsFailsThePatch() {
        assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(mergedComponent(otherDrawsRepost = true))).findFeedRepostComponent()
        }
    }

    /**
     * Instagram numbers its string resources per build, so the label's number isn't matched: 450's
     * x86 build 385611439 had the Repost label at 0x7f136e0f, 450's 438 at 0x7f136e0d.
     */
    @Test
    fun aLabelNumberedForAnotherBuildIsStillFound() {
        val context = PatchContexts.of(listOf(feedComponent(label = 0x7f136e0f)))
        assertEquals("Lfixture/RepostComponent;", context.findFeedRepostComponent().method.definingClass)
    }

    @Test
    fun missingAmbiguousOrChangedComponentRenderersFailThePatch() {
        assertThrows(PatchException::class.java) { PatchContexts.of(classes()).findFeedRepostComponent() }
        assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(feedComponent(), feedComponent("Lfixture/OtherComponent;"))).findFeedRepostComponent()
        }
        assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(feedComponent(role = "android.widget.ImageView"))).findFeedRepostComponent()
        }
        val noLocals = PatchContexts.of(listOf(feedComponent(registers = 2)))
        assertThrows(PatchException::class.java) { noLocals.hideFeedComponent(noLocals.findFeedRepostComponent()) }
    }

    @Test
    fun noModelFailsThePatch() {
        val context = PatchContexts.of(classes().filter { it.type != MEDIA })
        assertThrows(PatchException::class.java) { context.findRepostSites() }
    }

    @Test
    fun twoHooksOnOneMethodFailBeforeAnyChange() {
        requireSeparateMethods(mapOf("one" to setOf("Lfixture/A;->a()"), "two" to setOf("Lfixture/A;->b()")))
        assertThrows(PatchException::class.java) {
            requireSeparateMethods(mapOf("one" to setOf("Lfixture/A;->a()"), "two" to setOf("Lfixture/A;->b()", "Lfixture/A;->a()")))
        }
    }

    /**
     * The state hook and the read filter may share a method only when its reads come before its
     * writes (452's row builder); any other pair sharing one still fails, reads first or not.
     */
    @Test
    fun theStateHookAndTheReadFilterShareABuilderOnlyWhenItsReadsComeFirst() {
        val builder = "Lfixture/Builder;->build()"
        val shared = mapOf(STATE_HOOK to setOf(builder), READ_FILTER to setOf(builder, "Lfixture/A;->a()"))
        requireSeparateMethods(shared, readsFirst = setOf(builder))
        assertThrows(PatchException::class.java) { requireSeparateMethods(shared) }
        assertThrows(PatchException::class.java) { requireSeparateMethods(shared, readsFirst = setOf("Lfixture/A;->a()")) }
        assertThrows(PatchException::class.java) {
            requireSeparateMethods(mapOf(STATE_HOOK to setOf(builder), "the getter guard" to setOf(builder)), readsFirst = setOf(builder))
        }
        assertThrows(PatchException::class.java) {
            requireSeparateMethods(mapOf(READ_FILTER to setOf(builder), "the Feed UFI hook" to setOf(builder)), readsFirst = setOf(builder))
        }
    }

    /** A method's reads come first when the last of them sits before its first state write. */
    @Test
    fun readsComeFirstWhenTheLastOneSitsBeforeTheFirstWrite() {
        val builder = "Lfixture/Builder;"
        val flag = ImmutableFieldReference("Lfixture/State;", "enabled", "Z")
        fun write(at: Int) = FeedStateWrite(builder, "build", "Lfixture/State;", emptyList(), at, 1, 0, flag, 1)
        fun read(method: String, at: Int) = RepostRead(builder, method, emptyList(), at, 0)
        val state = FeedRepostState("Lfixture/State;", listOf(flag), listOf(write(20), write(24)))
        val before = RepostSites("getter", listOf(read("build", 10), read("build", 15), read("other", 30)))
        assertEquals(setOf("$builder->build()"), readsBeforeWrites(state, before))
        val between = RepostSites("getter", listOf(read("build", 10), read("build", 22)))
        assertEquals(emptySet<String>(), readsBeforeWrites(state, between))
        val touching = RepostSites("getter", listOf(read("build", 20)))
        assertEquals(emptySet<String>(), readsBeforeWrites(state, touching))
    }

    /**
     * In each declared build, and in each other build of a declared version, the model's getter is
     * guarded, every tree read of the field is filtered, and the component renderer's Repost part
     * returns nothing. On 451's and 453's declared builds there are six reads, two of them in one lambda's
     * invoke; 450's 438 had five, and 449 and 450's 385611395 six, so another build's count isn't
     * pinned. On 073 the hook goes where it went when the renderer was found by its label: in
     * [RENDERER_073], at [PART_073], the start of a renderer that draws Repost alone and reaches both
     * the repost icon and the label.
     */
    @Test
    fun eachDeclaredBuildHidesTheButton() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hidesTheButtonIn(bundle, bundle.name, reads = 6, pinned = bundle.name.contains("-385914557."))
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
        for (base in Fixtures.otherBuilds()) hidesTheButtonIn(base, base.parentFile.name, reads = null)
    }

    /**
     * Hooks everything in [bundle], in the patch's order and through its guard, and checks each
     * hook; [reads] is how many tree reads it has, when that's known, and [pinned] says it's the
     * declared build, whose component hook lands where it's pinned and whose tree reads and flag
     * stores sit in separate methods.
     */
    private fun hidesTheButtonIn(bundle: java.io.File, label: String, reads: Int?, pinned: Boolean = false) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type == MEDIA || classDef.methods.any { it.loadsHash() || it.loadsFeedUfiId() }) {
                    holders += ImmutableClassDef.of(classDef)
                }
            }
        }
        val states = FixtureDex.classesHolding(bundle, REPOST_ENABLED_LABEL)
        holders += states + FixtureDex.classesCreating(bundle, states.mapTo(HashSet()) { it.type })
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders.distinctBy { it.type }))

        val state = context.findFeedRepostState()
        val sites = context.findRepostSites()
        val found = "$label: tree reads ${sites.reads.map { "${it.type}->${it.name}" }}"
        if (reads != null) assertEquals(found, reads, sites.reads.size) else assertTrue(found, sites.reads.isNotEmpty())
        val feedUfi = context.findFeedUfiSite()
        val component = context.findFeedRepostComponent()
        val hooks = repostHookMethods(state, sites, feedUfi, component)
        val readsFirst = readsBeforeWrites(state, sites)
        requireSeparateMethods(hooks, readsFirst)
        if (pinned) {
            // 452's row builder read the tree and stored the flags both; 453's leaves those to separate methods.
            val shared = hooks.getValue(STATE_HOOK) intersect hooks.getValue(READ_FILTER)
            assertEquals("$label: no method both reads the tree and stores the flags", 0, shared.size)
            assertEquals("$label: so no reads need to come first", emptySet<String>(), readsFirst)
        }
        if (pinned) {
            val renderer = "${component.method.definingClass}->${component.method.name}"
            assertEquals("$label: the renderer, at ${component.at}", RENDERER_073, renderer)
            assertEquals("$label: the Repost part", PART_073, component.at)
            val reached = partReached(component.method, component.at)
            val code = component.method.implementation!!.instructions.toList()
            for ((what, id) in listOf("icon" to REPOSTS_UFI_ICON_ID, "label" to LABEL)) {
                assertTrue("$label: the part loads the $what", reached.any { (code[it] as? NarrowLiteralInstruction)?.narrowLiteral == id })
            }
        }
        val branchesToShare = context.mutableClassDefBy(feedUfi.type).methods.single { it.name == feedUfi.name }
            .implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>()
            .filter { it.target.location.index == feedUfi.insert }
        assertTrue("$label: expected native repost branches converging on Share", branchesToShare.isNotEmpty())
        context.hideFeedState(state)
        context.guardRepostGetter(sites.getter)
        val byMethod = sites.reads.groupBy { Triple(it.type, it.name, it.parameters) }
        byMethod.values.forEach { context.filterRepostReads(it) }
        context.hideFeedUfi(feedUfi)
        context.hideFeedComponent(component)

        assertGetterGuarded("$label ${sites.getter}", context.mutableClassDefBy(MEDIA).methods.single {
            it.name == sites.getter && it.parameterTypes.isEmpty()
        })
        for ((key, reads) in byMethod) {
            val method = context.mutableClassDefBy(key.first).methods.single {
                it.name == key.second && it.parameterTypes.map(CharSequence::toString) == key.third
            }
            // Each earlier read's filter moves the later ones down by two; the state hook's lines
            // sit past every read, so they move none.
            val shifted = reads.sortedBy { it.at }.mapIndexed { i, read -> read.at + 2 * i }
            assertReadsFiltered("$label ${key.first}->${key.second}", method, shifted)
        }
        for ((key, writes) in state.writes.groupBy { Triple(it.owner, it.name, it.parameters) }) {
            val code = context.mutableClassDefBy(key.first).methods.single {
                it.name == key.second && it.parameterTypes.map(CharSequence::toString) == key.third
            }.implementation!!.instructions.toList()
            assertEquals("$label ${key.first}->${key.second}: state hooks", writes.size, code.count { it.names(REPOSTS_FEED_STATE) })
        }
        val feedMethod = context.mutableClassDefBy(feedUfi.type).methods.single {
            it.name == feedUfi.name && it.parameterTypes.map(CharSequence::toString) == feedUfi.parameters
        }
        assertFeedUfiHidden("$label ${feedUfi.type}->${feedUfi.name}", feedMethod, feedUfi.icon, feedUfi.count)
        val hide = feedMethod.implementation!!.instructions.indexOfFirst { it.names(REPOSTS_FEED_UFI) }
        branchesToShare.forEach { assertEquals("$label: a native branch skipped hiding", hide - 2, it.target.location.index) }
        val render = context.mutableClassDefBy(component.method.definingClass).methods.single {
            it.name == component.method.name && it.parameterTypes == component.method.parameterTypes
        }
        assertComponentGuarded(render, component.at)
        // The guarded part is the one that draws the repost icon, after the guard.
        val drawn = render.implementation!!.instructions.toList()
        assertTrue("$label: the Repost part draws the icon", (component.at + 5 until drawn.size).any {
            (drawn[it] as? NarrowLiteralInstruction)?.narrowLiteral == REPOSTS_UFI_ICON_ID
        })
    }

    /** The getter opens with the extension call and its test, then on a yes answers FALSE; one call in all. */
    private fun assertGetterGuarded(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: hooks", 1, code.count { it.names(HIDE_REPOSTS) })
        assertEquals("$what: the call", HIDE_REPOSTS, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the test", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: FALSE", "Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;", (code[3] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the early return", Opcode.RETURN_OBJECT, code[4].opcode)
    }

    /** At each index a read's answer is moved out, then passed through the filter and moved back into the same register. */
    private fun assertReadsFiltered(what: String, method: Method, answers: List<Int>) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: filters", answers.size, code.count { it.names(REPOSTS_ELIGIBLE) || it.names(REPOSTS_ELIGIBLE_MATCH) })
        for (at in answers) {
            if (code[at].opcode == Opcode.MOVE_RESULT) {
                // 452 compares in place: the answer and the Boolean it was compared with go through the filter.
                val register = (code[at] as OneRegisterInstruction).registerA
                val filter = code[at + 1]
                assertTrue("$what: the compare filter at ${at + 1}", filter.names(REPOSTS_ELIGIBLE_MATCH))
                assertEquals("$what: the filter's answer at $at", register, (filter as FiveRegisterInstruction).registerC)
                assertEquals("$what: back at ${at + 2}", Opcode.MOVE_RESULT, code[at + 2].opcode)
                assertEquals("$what: into the same register at ${at + 2}", register, (code[at + 2] as OneRegisterInstruction).registerA)
                continue
            }
            assertEquals("$what: the answer at $at", Opcode.MOVE_RESULT_OBJECT, code[at].opcode)
            val register = (code[at] as OneRegisterInstruction).registerA
            val filter = code[at + 1]
            assertTrue("$what: the filter at ${at + 1}", filter.names(REPOSTS_ELIGIBLE))
            assertEquals("$what: the filter's register at ${at + 1}", register, (filter as RegisterRangeInstruction).startRegister)
            assertEquals("$what: back at ${at + 2}", Opcode.MOVE_RESULT_OBJECT, code[at + 2].opcode)
            assertEquals("$what: into the same register at ${at + 2}", register, (code[at + 2] as OneRegisterInstruction).registerA)
        }
    }

    /** The direct Feed UFI hide runs after Instagram has rebound the repost icon and count. */
    private fun assertFeedUfiHidden(
        what: String,
        method: Method,
        iconField: FieldReference = UFI_ICON_FIELD,
        countField: FieldReference = UFI_COUNT_FIELD,
    ) {
        val code = method.implementation!!.instructions.toList()
        val at = code.indexOfFirst { it.names(REPOSTS_FEED_UFI) }
        assertEquals("$what: restore before native binding", 3, code.indexOfFirst { it.names(REPOSTS_FEED_RESTORE) })
        assertEquals("$what: one restore", 1, code.count { it.names(REPOSTS_FEED_RESTORE) })
        assertTrue("$what: feed UFI hook missing", at > 1)
        assertEquals("$what: one feed UFI hook", 1, code.count { it.names(REPOSTS_FEED_UFI) })
        val icon = code[at - 2]
        val count = code[at - 1]
        assertEquals("$what: icon read", Opcode.IGET_OBJECT, icon.opcode)
        assertEquals("$what: count read", Opcode.IGET_OBJECT, count.opcode)
        assertField("$what: icon field", iconField, icon)
        assertField("$what: count field", countField, count)
        assertEquals(
            "$what: next native button",
            Opcode.IGET_OBJECT,
            code[at + 1].opcode,
        )
        assertTrue("$what: Share is still bound next", ((code[at + 1] as ReferenceInstruction).reference as FieldReference).toString() != iconField.toString())
    }

    private fun assertField(what: String, expected: FieldReference, instruction: Instruction) {
        assertEquals(what, expected.toString(), ((instruction as ReferenceInstruction).reference as FieldReference).toString())
    }

    /**
     * The instructions of [method] reached from [at] without crossing into another part: every
     * edge is followed, stopping at an instance-of whose answer the next if-eqz tests.
     */
    private fun partReached(method: Method, at: Int): Set<Int> {
        val flow = ControlFlow.of(method)
        val code = flow.instructions
        fun isCheck(index: Int) = code[index].opcode == Opcode.INSTANCE_OF && code.getOrNull(index + 1)?.let { skip ->
            skip.opcode == Opcode.IF_EQZ && (skip as OneRegisterInstruction).registerA == (code[index] as OneRegisterInstruction).registerA
        } == true
        val seen = HashSet<Int>()
        val pending = ArrayDeque(listOf(at))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (isCheck(next) || !seen.add(next)) continue
            pending.addAll(flow.normal[next])
            pending.addAll(flow.exceptional[next])
        }
        return seen
    }

    private fun assertComponentGuarded(method: Method, at: Int) {
        val code = method.implementation!!.instructions.toList()
        assertEquals(1, code.count { it.names(REPOSTS_FEED_COMPONENT) })
        assertTrue(code[at].names(REPOSTS_FEED_COMPONENT))
        assertEquals(Opcode.MOVE_RESULT, code[at + 1].opcode)
        val register = (code[at + 1] as OneRegisterInstruction).registerA
        assertEquals(Opcode.IF_EQZ, code[at + 2].opcode)
        assertEquals(register, (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals(0, (code[at + 3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(register, (code[at + 3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN_OBJECT, code[at + 4].opcode)
        assertEquals(register, (code[at + 4] as OneRegisterInstruction).registerA)
        if (at == 0) assertEquals(0, register)
        assertEquals("Off must reach the untouched native renderer", at + 5, (code[at + 2] as BuilderOffsetInstruction).target.location.index)
        assertTrue("Off must reach the untouched native renderer", code.size > at + 5)
    }

    private fun Instruction.names(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun Method.loadsHash() = implementation?.instructions?.any {
        it is NarrowLiteralInstruction && it.opcode == Opcode.CONST && it.narrowLiteral == REPOSTS_HASH
    } == true

    private fun Method.loadsFeedUfiId() = implementation?.instructions?.any {
        it is NarrowLiteralInstruction && it.opcode == Opcode.CONST &&
            (it.narrowLiteral == REPOSTS_UFI_ICON_ID || it.narrowLiteral == REPOSTS_UFI_COUNT_ID)
    } == true

    private companion object {
        const val READER = "Lfixture/RepostButtonUseCase;"
        const val UFI_BINDER = "Lfixture/FeedUfiBinder;"
        const val UFI_HOLDER = "Lfixture/FeedUfiHolder;"
        const val BOUNCY = "Lcom/instagram/ui/widget/bouncyufibutton/IgBouncyUfiButtonImageView;"
        const val TEXT = "Lcom/instagram/common/ui/base/IgTextView;"
        val UFI_ICON_FIELD = ImmutableFieldReference(UFI_HOLDER, "A08", BOUNCY)
        val UFI_COUNT_FIELD = ImmutableFieldReference(UFI_HOLDER, "A05", TEXT)
        val UFI_SHARE_FIELD = ImmutableFieldReference(UFI_HOLDER, "A0H", BOUNCY)
        val TREE_READ = ImmutableMethodReference("Lfixture/Tree;", "Crf", listOf("I"), "Ljava/lang/Boolean;")
        val REQUIRE_VIEW = ImmutableMethodReference("Landroid/view/View;", "requireViewById", listOf("I"), "Landroid/view/View;")

        fun treeRead(answer: Int, hash: Int = REPOSTS_HASH, dropsAnswer: Boolean = false) = listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, hash),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 1, 0, 0, 0, 0, TREE_READ),
            if (dropsAnswer) ImmutableInstruction10x(Opcode.NOP) else ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, answer),
        )

        fun method(type: String, name: String, returnType: String, registers: Int, code: List<Instruction>, static: Boolean = false,
                   parameters: List<String> = emptyList()) =
            ImmutableMethod(
                type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
                null, null, ImmutableMethodImplementation(registers, code, null, null),
            )

        fun classOf(type: String, methods: List<ImmutableMethod>) = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, methods,
        )

        fun view(id: Int, field: FieldReference, checkCast: String, holder: Int = 4) = listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, id),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 0, 0, 0, 0, REQUIRE_VIEW),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(checkCast)),
            ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, holder, field),
        )

        fun feedUfiBinder(branchToShare: Boolean = false) = classOf(
            UFI_BINDER,
            listOf(
                method(
                    UFI_BINDER, "bind", "V", 6,
                    listOf(ImmutableInstruction12x(Opcode.MOVE_OBJECT, 4, 5)) +
                        view(REPOSTS_UFI_ICON_ID, UFI_ICON_FIELD, BOUNCY) +
                        view(REPOSTS_UFI_COUNT_ID, UFI_COUNT_FIELD, TEXT) +
                        (if (branchToShare) listOf(ImmutableInstruction21t(Opcode.IF_EQZ, 1, 2)) else emptyList()) +
                        ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 4, UFI_SHARE_FIELD) +
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    parameters = listOf(UFI_HOLDER),
                ),
            ),
        )

        /** 385914557's Repost label, the button's content description, the id 55.79's 385914283 used too. */
        const val LABEL = 0x7f13732b

        /** 385914557's Feed component renderer, a renderer of its own, so its Repost part is the whole of it. */
        const val RENDERER_073 = "LX/07Gw;->A0o"
        const val PART_073 = 0

        /** A renderer of its own: the icon, the label, the [role] it gives the button, then null. */
        fun feedComponent(
            type: String = "Lfixture/RepostComponent;",
            label: Int = LABEL,
            role: String = BUTTON_ROLE,
            registers: Int = 3,
        ) = classOf(
            type,
            listOf(ImmutableMethod(
                type, "render", listOf(ImmutableMethodParameter("Lfixture/Scope;", null, null)), "Lfixture/Component;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(registers, listOf(
                    ImmutableInstruction31i(Opcode.CONST, 0, REPOSTS_UFI_ICON_ID),
                    ImmutableInstruction31i(Opcode.CONST, 0, label),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(role)),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ), null, null),
            )),
        )

        /**
         * Two components merged into one renderer, picked by the receiver's class:
         *
         *     0 move-object v1, v2 (this) | 1 instance-of v0, v1, Other | 2 if-eqz v0 -> 5
         *     3 const/4 v0, 0 | 4 return-object v0
         *     5 instance-of v0, v1, Repost | 6 if-eqz v0 -> 12
         *     7 const v0, icon | 8 const v0, label | 9 const-string v0, Button | 10 const/4 v0, 0 | 11 return-object v0
         *     12 const/4 v0, 0 | 13 return-object v0
         */
        fun mergedComponent(otherDrawsRepost: Boolean = false): ClassDef {
            val type = "Lfixture/MergedComponent;"
            val first = if (otherDrawsRepost) repostPart(12) else listOf(
                ImmutableInstruction22c(Opcode.INSTANCE_OF, 0, 1, ImmutableTypeReference("Lfixture/Other;")),
                ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4),
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
            val code = listOf(ImmutableInstruction12x(Opcode.MOVE_OBJECT, 1, 2)) + first + repostPart(12) + listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;",
                null, null, null, null, listOf(ImmutableMethod(
                    type, "render", listOf(ImmutableMethodParameter("Lfixture/Scope;", null, null)), "Lfixture/Component;",
                    AccessFlags.PUBLIC.value, null, null,
                    ImmutableMethodImplementation(4, code, null, null),
                )),
            )
        }

        /** A class check skipping [skip] code units to the next part, then a part drawing Repost. */
        private fun repostPart(skip: Int) = listOf(
            ImmutableInstruction22c(Opcode.INSTANCE_OF, 0, 1, ImmutableTypeReference("Lfixture/Repost;")),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, skip),
            ImmutableInstruction31i(Opcode.CONST, 0, REPOSTS_UFI_ICON_ID),
            ImmutableInstruction31i(Opcode.CONST, 0, LABEL),
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(BUTTON_ROLE)),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )

        /**
         * The model with its getter (the field's name, its hash, then null) and a copy that reads the
         * field the way a button does, and a button use case reading the field twice and another
         * field once:
         *
         *     0 const v0, hash | 1 invoke-interface {v1, v0} | 2 move-result-object v2
         *     3 const v0, hash | 4 nop | 5 invoke-interface {v1, v0} | 6 move-result-object v3
         *     7 const v0, other | 8 invoke-interface {v1, v0} | 9 move-result-object v4
         *     10 return-object v2
         */
        fun classes(
            secondGetter: Boolean = false,
            getterString: Boolean = true,
            getterRegisters: Int = 4,
            dropsAnswer: Boolean = false,
        ): List<ClassDef> {
            fun getter(name: String) = method(
                MEDIA, name, "Ljava/lang/Boolean;", getterRegisters,
                listOfNotNull(
                    if (getterString) ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(REPOSTS_FIELD)) else null,
                    ImmutableInstruction31i(Opcode.CONST, 0, REPOSTS_HASH),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ),
            )
            val copy = method(MEDIA, "A06", "V", 5, treeRead(2) + ImmutableInstruction10x(Opcode.RETURN_VOID))
            val media = classOf(MEDIA, listOfNotNull(getter("A3o"), if (secondGetter) getter("A3p") else null, copy))

            val second = treeRead(3, dropsAnswer = dropsAnswer).toMutableList().apply { add(1, ImmutableInstruction10x(Opcode.NOP)) }
            val reader = classOf(
                READER,
                listOf(
                    method(
                        READER, "invoke", "Ljava/lang/Object;", 6,
                        treeRead(2) + second + treeRead(4, hash = 0x1234) + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2),
                    ),
                ),
            )
            return listOf(media, reader, feedUfiBinder())
        }
    }
}

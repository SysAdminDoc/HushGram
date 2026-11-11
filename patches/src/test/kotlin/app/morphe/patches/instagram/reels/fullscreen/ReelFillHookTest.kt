/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.fullscreen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #288: Instagram's reel crop decision asks ReelFill.fill with the viewer's source and the crop
 * percentage right after working it out, and returns true on a yes. On no it goes on to Instagram's
 * own checks unchanged.
 */
class ReelFillHookTest {
    @Test
    fun theHookIsInTheExtension() {
        for (hook in listOf(FILL_HOOK, FIT_HOOK, STORY_HOOK, WIDE_HOOK, STORY_BOUND_HOOK, STORY_FRAME_HOOK)) {
            val method = ExtensionDex.classDef(hook.substringBefore("->")).methods.single {
                "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook.substringAfter("->")
            }
            assertTrue(hook, AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags))
        }
    }

    @Test
    fun eachDeclaredBuildAsksAfterTheCropPercentage() = eachDeclaredBundle(::asks)

    /** piko #2183: Show the whole reel asks first, and a yes answers false (fit) before anything else runs. */
    @Test
    fun eachDeclaredBuildAsksFitFirst() = eachDeclaredBundle(::fitsFirst)

    /**
     * #436: the story layout's tall-screen check asks first and answers false on a yes, and its card
     * layout asks first and lays out the whole screen instead. On no both run as Instagram wrote them.
     */
    @Test
    fun eachDeclaredBuildLaysStoriesOutFullScreen() = eachDeclaredBundle(::laysStoriesOut)

    /**
     * #491: the reel item layout asks with the item's shape in front of its compare with a bit over
     * 9:16, and a yes goes where the screen check sends a phone, the plain whole-item layout.
     */
    @Test
    fun eachDeclaredBuildGivesWideScreensTheWholeWidth() = eachDeclaredBundle(::fillsWideScreens)

    private fun fillsWideScreens(bundle: File, label: String) {
        val loading = FixtureDex.classesLoading(bundle, REEL_INSETS)
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, loading))
        val render = loading.map { it.type }.toSet().flatMap { context.mutableClassDefBy(it).methods }
            .single { context.loadsString(it, REEL_INSETS) }
        val before = render.code()
        val check = findWideReelCheck(before) ?: throw AssertionError("$label: no 9:16 checks")
        assertEquals("$label: the screen is compared with 9:16", 0.5625f,
            java.lang.Float.intBitsToFloat((before[check.screenBranch - 2] as NarrowLiteralInstruction).narrowLiteral), 0f)
        assertEquals("$label: the item with a bit more", 0.5675f,
            java.lang.Float.intBitsToFloat((before[check.constant] as NarrowLiteralInstruction).narrowLiteral), 0.0001f)
        val plain = ControlFlow.of(render).normal[check.screenBranch].first()
        assertTrue("$label: the plain layout comes after the column", plain > check.constant)
        val native = NeutralNativePath(render)

        context.fillWideReelScreen()

        val code = render.code()
        val ask = check.constant
        assertEquals("$label: three instructions added", before.count { it.opcode != Opcode.NOP } + 3, code.count { it.opcode != Opcode.NOP })
        assertEquals("$label: one ask", 1, code.count { it.referenceText() == WIDE_HOOK })
        assertEquals("$label: in front of the item's compare", WIDE_HOOK, code[ask].referenceText())
        assertEquals("$label: an invoke-static/range", Opcode.INVOKE_STATIC_RANGE, code[ask].opcode)
        val call = code[ask] as RegisterRangeInstruction
        assertEquals("$label: on the item's shape", listOf(check.shape), listOf(call.startRegister).take(call.registerCount))
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, code[ask + 1].opcode)
        val answer = (code[ask + 1] as OneRegisterInstruction).registerA
        assertTrue("$label: not the shape's register", answer != check.shape)
        assertEquals("$label: a yes jumps", Opcode.IF_NEZ, code[ask + 2].opcode)
        assertEquals("$label: on the answer", answer, (code[ask + 2] as OneRegisterInstruction).registerA)
        val flow = ControlFlow.of(render)
        assertEquals("$label: to the plain layout", plain + 3, flow.normal[ask + 2].first())
        assertEquals("$label: the screen check still goes there", plain + 3, flow.normal[check.screenBranch].first())
        assertEquals("$label: a no reaches Instagram's compare", before[check.constant].opcode, code[ask + 3].opcode)
        native.assertPreserved(label, render, (ask until ask + 3).toSet())
    }

    private fun laysStoriesOut(bundle: File, label: String) {
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, STORY_ASPECT_RATIO)))
        val layout = context.findStoryLayout()
        val methods = context.mutableClassDefBy(layout.card.definingClass).methods
        fun own(call: MethodReference) = methods.single {
            it.name == call.name && it.returnType == call.returnType &&
                it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
        }
        val tall = own(layout.tall)
        val card = own(layout.card)
        assertEquals("$label: the whole-screen layout takes the card's holder", listOf(card.parameterTypes[1].toString()),
            layout.full.parameterTypes.map(CharSequence::toString))
        // 454's other (holder) layout, behind a second check, keeps the card's rounded corners.
        assertTrue("$label: the whole-screen layout squares the card's corners",
            own(layout.full).code().any { it.referenceText()?.endsWith("->setCornerRadius(I)V") == true })
        val holder = card.parameterRegisterNumber(1)
        val before = listOf(tall, card).associateWith { it.code() }
        val natives = listOf(tall, card).associateWith { NeutralNativePath(it) }

        context.fillStoryScreen()

        for ((what, method) in listOf("$label tall-screen check" to tall, "$label card layout" to card)) {
            val stock = before.getValue(method)
            val code = method.code()
            assertEquals("$what: five instructions added", stock.count { it.opcode != Opcode.NOP } + 5, code.count { it.opcode != Opcode.NOP })
            assertEquals("$what: one ask", 1, code.count { it.referenceText() == STORY_HOOK })
            assertEquals("$what: asked first", STORY_HOOK, code[0].referenceText())
            assertEquals("$what: an invoke-static", Opcode.INVOKE_STATIC, code[0].opcode)
            assertEquals("$what: with nothing", 0, (code[0] as FiveRegisterInstruction).registerCount)
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
            assertEquals("$what: in the first local", 0, (code[1] as OneRegisterInstruction).registerA)
            assertEquals("$what: a no goes on", Opcode.IF_EQZ, code[2].opcode)
            assertEquals("$what: on the answer", 0, (code[2] as OneRegisterInstruction).registerA)
            assertEquals("$what: then Instagram's first instruction", stock[0].opcode, code[5].opcode)
            natives.getValue(method).assertPreserved(what, method, (0 until 5).toSet())
        }

        val check = tall.code()
        assertEquals("$label tall-screen check: past the return", 2 + 1 + 1, (check[2] as OffsetInstruction).codeOffset)
        assertEquals("$label tall-screen check: a yes answers", Opcode.CONST_4, check[3].opcode)
        assertEquals("$label tall-screen check: no card", 0, (check[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$label tall-screen check: into the answer", 0, (check[3] as OneRegisterInstruction).registerA)
        assertEquals("$label tall-screen check: and returns it", Opcode.RETURN, check[4].opcode)
        assertEquals("$label tall-screen check: returning the answer", 0, (check[4] as OneRegisterInstruction).registerA)

        val cardCode = card.code()
        assertEquals("$label card layout: past the return", 2 + 3 + 1, (cardCode[2] as OffsetInstruction).codeOffset)
        assertEquals("$label card layout: a yes lays out the whole screen", layout.full.toString(), cardCode[3].referenceText())
        assertEquals("$label card layout: with an invoke-static", Opcode.INVOKE_STATIC, cardCode[3].opcode)
        assertEquals("$label card layout: on the holder", listOf(holder), (cardCode[3] as FiveRegisterInstruction).let {
            listOf(it.registerC).take(it.registerCount)
        })
        assertEquals("$label card layout: and returns", Opcode.RETURN_VOID, cardCode[4].opcode)
    }

    /**
     * #618: the story binder asks first, with its holder and Instagram's own check of whether the story
     * shares a reel, and a yes lays out the whole screen before anything else runs. The whole-screen
     * layout hands the holder and its frame over in front of its one return, and every branch that
     * went to the return reaches the hand-over first. Every other build of the declared versions too.
     */
    @Test
    fun eachBuildLaysStoriesOutAsTheyBind() {
        eachDeclaredBundle(::laysStoriesOutAsTheyBind)
        for (base in Fixtures.otherBuilds()) laysStoriesOutAsTheyBind(base, base.parentFile.name)
    }

    private fun laysStoriesOutAsTheyBind(bundle: File, label: String) {
        val loading = FixtureDex.classesLoading(bundle, STORY_ASPECT_RATIO) + FixtureDex.classesLoading(bundle, STORY_BIND_MEDIA)
        val models = FixtureDex.classes(bundle, setOf(STORY_REEL_ITEM, REEL_PRODUCT_TYPE)).values
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, (loading + models).distinctBy { it.type }))
        val layout = context.findStoryLayout()
        val found = context.findStoryBinder(layout)
        val shared = context.findSharedReelCheck()
        assertEquals("$label: the shared-reel check is the story's own", STORY_REEL_ITEM, shared.definingClass)
        assertEquals("$label: and answers yes or no", "Z", shared.returnType)
        fun MethodReference.same(method: Method) = method.name == name &&
            method.parameterTypes.map(CharSequence::toString) == parameterTypes.map(CharSequence::toString)
        val binder = context.mutableClassDefBy(found.method.definingClass).methods.single { found.method.toReference().same(it) }
        val full = context.mutableClassDefBy(layout.full.definingClass).methods.single {
            layout.full.same(it) && AccessFlags.STATIC.isSet(it.accessFlags)
        }
        assertEquals("$label: the binder binds a story", STORY_REEL_ITEM, binder.parameterTypes[found.item].toString())
        val item = binder.parameterRegisterNumber(found.item)
        val holder = binder.parameterRegisterNumber(found.holder)
        val fullHolder = full.parameterRegisterNumber(0)
        val getter = findStoryFrameGetter(full)
        assertTrue("$label: the frame is the story's rounded frame",
            getter.getter.returnType.endsWith("/RoundedCornerFrameLayout;"))
        val bindBefore = binder.code()
        val fullBefore = full.code()
        val returnAt = fullBefore.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
        val stockFlow = ControlFlow.of(full)
        val branches = fullBefore.indices.filter { fullBefore[it] is OffsetInstruction && stockFlow.normal[it].first() == returnAt }
        assertTrue("$label: something branches to the layout's return", branches.isNotEmpty())
        val bindNative = NeutralNativePath(binder)
        val fullNative = NeutralNativePath(full)

        context.layStoriesOutAsTheyBind()

        val code = binder.code()
        val bind = "$label binder"
        assertEquals("$bind: nine instructions added", bindBefore.count { it.opcode != Opcode.NOP } + 9, code.count { it.opcode != Opcode.NOP })
        assertEquals("$bind: the holder first", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$bind: into the first local", 0, (code[0] as TwoRegisterInstruction).registerA)
        assertEquals("$bind: out of its parameter", holder, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$bind: no until asked", Opcode.CONST_4, code[1].opcode)
        assertEquals("$bind: in the second local", 1, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$bind: no", 0, (code[1] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$bind: no story isn't asked about", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$bind: on the story", item, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$bind: an invoke-virtual/range", Opcode.INVOKE_VIRTUAL_RANGE, code[3].opcode)
        assertEquals("$bind: of the shared-reel check", shared.toString(), code[3].referenceText())
        assertEquals("$bind: on the story", item to 1, (code[3] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
        assertEquals("$bind: its answer", Opcode.MOVE_RESULT, code[4].opcode)
        assertEquals("$bind: in the second local", 1, (code[4] as OneRegisterInstruction).registerA)
        assertEquals("$bind: one ask", 1, code.count { it.referenceText() == STORY_BOUND_HOOK })
        assertEquals("$bind: then the ask", STORY_BOUND_HOOK, code[5].referenceText())
        assertEquals("$bind: an invoke-static", Opcode.INVOKE_STATIC, code[5].opcode)
        assertEquals("$bind: on the holder and the answer", listOf(0, 1), (code[5] as FiveRegisterInstruction).let {
            listOf(it.registerC, it.registerD).take(it.registerCount)
        })
        assertEquals("$bind: the ask's answer", Opcode.MOVE_RESULT, code[6].opcode)
        assertEquals("$bind: in the second local", 1, (code[6] as OneRegisterInstruction).registerA)
        assertEquals("$bind: a no goes on", Opcode.IF_EQZ, code[7].opcode)
        assertEquals("$bind: on the ask's answer", 1, (code[7] as OneRegisterInstruction).registerA)
        assertEquals("$bind: a yes lays out the whole screen", layout.full.toString(), code[8].referenceText())
        assertEquals("$bind: with an invoke-static", Opcode.INVOKE_STATIC, code[8].opcode)
        assertEquals("$bind: on the holder", listOf(0), (code[8] as FiveRegisterInstruction).let { listOf(it.registerC).take(it.registerCount) })
        assertEquals("$bind: then Instagram's first instruction", bindBefore[0].opcode, code[9].opcode)
        val bindFlow = ControlFlow.of(binder)
        assertEquals("$bind: no story goes straight to the ask", 5, bindFlow.normal[2].first())
        assertEquals("$bind: a no skips the layout", 9, bindFlow.normal[7].first())
        bindNative.assertPreserved(bind, binder, (0 until 9).toSet())

        val after = full.code()
        val lay = "$label whole-screen layout"
        assertEquals("$lay: three instructions added", fullBefore.count { it.opcode != Opcode.NOP } + 3, after.count { it.opcode != Opcode.NOP })
        assertEquals("$lay: one hand-over", 1, after.count { it.referenceText() == STORY_FRAME_HOOK })
        assertEquals("$lay: the frame fetched where the return was", getter.getter.toString(), after[returnAt].referenceText())
        assertEquals("$lay: as the layout fetches it", getter.call, if (after[returnAt].opcode == Opcode.INVOKE_INTERFACE) "invoke-interface" else "invoke-virtual")
        assertTrue("$lay: an invoke", after[returnAt].opcode == Opcode.INVOKE_INTERFACE || after[returnAt].opcode == Opcode.INVOKE_VIRTUAL)
        assertEquals("$lay: from the holder", listOf(fullHolder), (after[returnAt] as FiveRegisterInstruction).let {
            listOf(it.registerC).take(it.registerCount)
        })
        assertEquals("$lay: into a local", Opcode.MOVE_RESULT_OBJECT, after[returnAt + 1].opcode)
        val frame = (after[returnAt + 1] as OneRegisterInstruction).registerA
        assertTrue("$lay: a local", frame < full.implementation!!.registerCount - full.parameters.size)
        assertEquals("$lay: then the hand-over", STORY_FRAME_HOOK, after[returnAt + 2].referenceText())
        assertEquals("$lay: an invoke-static", Opcode.INVOKE_STATIC, after[returnAt + 2].opcode)
        assertEquals("$lay: of the holder and the frame", listOf(fullHolder, frame), (after[returnAt + 2] as FiveRegisterInstruction).let {
            listOf(it.registerC, it.registerD).take(it.registerCount)
        })
        assertEquals("$lay: then the return", Opcode.RETURN_VOID, after[returnAt + 3].opcode)
        val flow = ControlFlow.of(full)
        for (branch in branches) {
            assertEquals("$lay: instruction $branch reaches the hand-over", returnAt, flow.normal[branch].first())
        }
        fullNative.assertPreserved(lay, full, (returnAt until returnAt + 3).toSet())
    }

    private fun Method.toReference(): MethodReference =
        com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference(definingClass, name, parameterTypes, returnType)

    private fun eachDeclaredBundle(check: (File, String) -> Unit) {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                check(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun fitsFirst(bundle: File, label: String) {
        val types = FixtureDex.classesHolding(bundle, SHOULD_CROP).map { it.type }.toSet()
        for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
            val what = "$label ($read)"
            val context = PatchContexts.of(classesOf(bundle, types).values)
            val decision = types.flatMap { context.mutableClassDefBy(it).methods }.single { it.loadsBoth() }
            val before = decision.code()
            val multiply = before.indices.first { at ->
                before[at].opcode == Opcode.MUL_FLOAT_2ADDR && before.take(at).any { it.string() == CROP_PERCENTAGE }
            }
            val native = NeutralNativePath(decision)

            // In the patch's order: Fill after the multiply, then Fit at the start.
            context.fillReelScreen()
            context.fitReelScreen()

            val code = decision.code()
            assertEquals("$what: ten instructions added", before.size + 10, code.size)
            assertEquals("$what: one fit ask", 1, code.count { it.referenceText() == FIT_HOOK })
            assertEquals("$what: asked first", FIT_HOOK, code[0].referenceText())
            assertEquals("$what: an invoke-static", Opcode.INVOKE_STATIC, code[0].opcode)
            assertEquals("$what: with nothing", 0, (code[0] as FiveRegisterInstruction).registerCount)
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
            val answer = (code[1] as OneRegisterInstruction).registerA
            assertTrue("$what: in a local", answer < decision.implementation!!.registerCount - decision.parameters.size - 1)
            assertEquals("$what: a no goes on", Opcode.IF_EQZ, code[2].opcode)
            assertEquals("$what: on the answer", answer, (code[2] as OneRegisterInstruction).registerA)
            assertEquals("$what: past the return", 2 + 1 + 1, (code[2] as OffsetInstruction).codeOffset)
            assertEquals("$what: a yes answers", Opcode.CONST_4, code[3].opcode)
            assertEquals("$what: false", 0, (code[3] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$what: into the answer", answer, (code[3] as OneRegisterInstruction).registerA)
            assertEquals("$what: and returns it", Opcode.RETURN, code[4].opcode)
            assertEquals("$what: returning the answer", answer, (code[4] as OneRegisterInstruction).registerA)
            assertEquals("$what: then Instagram's first instruction", before[0].opcode, code[5].opcode)
            assertEquals("$what: Fill still right after the multiply", FILL_HOOK, code[multiply + 7].referenceText())
            native.assertPreserved(what, decision, (0 until 5).toSet() + (multiply + 6 until multiply + 11).toSet())
        }
    }

    private fun asks(bundle: File, label: String) {
        val types = FixtureDex.classesHolding(bundle, SHOULD_CROP).map { it.type }.toSet()
        for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
            val what = "$label ($read)"
            val context = PatchContexts.of(classesOf(bundle, types).values)
            val decision = types.flatMap { context.mutableClassDefBy(it).methods }.single { it.loadsBoth() }
            val before = decision.code()
            val multiply = before.indices.first { at ->
                before[at].opcode == Opcode.MUL_FLOAT_2ADDR && before.take(at).any { it.string() == CROP_PERCENTAGE }
            }
            val percent = (before[multiply] as TwoRegisterInstruction).registerA
            val source = decision.parameterRegisterNumber(decision.parameterTypes.indexOfFirst { it.toString() == REEL_VIEWER_SOURCE })
            val native = NeutralNativePath(decision)

            context.fillReelScreen()

            val code = decision.code()
            val fetch = multiply + 1
            val ask = fetch + 1
            assertEquals("$what: five instructions added", before.size + 5, code.size)
            assertEquals("$what: the source first", Opcode.MOVE_OBJECT_FROM16, code[fetch].opcode)
            assertEquals("$what: out of its parameter", source, (code[fetch] as TwoRegisterInstruction).registerB)
            val holder = (code[fetch] as TwoRegisterInstruction).registerA
            assertTrue("$what: into a local", holder < decision.implementation!!.registerCount - decision.parameters.size - 1)
            assertEquals("$what: one ask", 1, code.count { it.referenceText() == FILL_HOOK })
            assertEquals("$what: right after the multiply", FILL_HOOK, code[ask].referenceText())
            assertEquals("$what: an invoke-static", Opcode.INVOKE_STATIC, code[ask].opcode)
            assertEquals("$what: on the source and the percentage", listOf(holder, percent), (code[ask] as FiveRegisterInstruction).let {
                listOf(it.registerC, it.registerD).take(it.registerCount)
            })
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[ask + 1].opcode)
            val answer = (code[ask + 1] as OneRegisterInstruction).registerA
            assertTrue("$what: not in the percentage's register", answer != percent)
            assertEquals("$what: a no goes on", Opcode.IF_EQZ, code[ask + 2].opcode)
            assertEquals("$what: on the answer", answer, (code[ask + 2] as OneRegisterInstruction).registerA)
            assertEquals("$what: to Instagram's next instruction", before[multiply + 1].opcode, code[ask + 4].opcode)
            assertEquals("$what: past the return", 2 + 1, (code[ask + 2] as OffsetInstruction).codeOffset)
            assertEquals("$what: a yes fills", Opcode.RETURN, code[ask + 3].opcode)
            assertEquals("$what: returning the answer", answer, (code[ask + 3] as OneRegisterInstruction).registerA)
            native.assertPreserved(what, decision, (fetch until ask + 4).toSet())
        }
    }

    private fun Method.loadsBoth(): Boolean {
        val strings = code().mapNotNull { it.string() }
        return returnType == "Z" && SHOULD_CROP in strings && CROP_PERCENTAGE in strings
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.string(): String? =
        ((this as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}

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
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import app.morphe.patcher.patch.PatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #288: Instagram's reel crop decision asks ReelFill.fill with the viewer's source and the crop
 * percentage right after working it out, and returns true on a yes. On no it goes on to Instagram's
 * own checks unchanged.
 */
class ReelFillHookTest {
    private val STICKER_KIND = "Lfixture/StickerKind;"
    private val CLIPS_FIELD = ImmutableFieldReference(REEL_PRODUCT_TYPE, "A07", REEL_PRODUCT_TYPE)

    @Test
    fun theHookIsInTheExtension() {
        for (hook in listOf(FILL_HOOK, FIT_HOOK, FIT_VIDEO_HOOK, FIT_BOX_HOOK, STORY_HOOK, WIDE_HOOK, STORY_BOUND_HOOK, STORY_FRAME_HOOK,
            SHARES_POST_HOOK)) {
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
     * #436: the story layout's tall-screen check and its sized layout's check ask first and answer
     * false on a yes, so no card is laid out. On no both run as Instagram wrote them.
     */
    @Test
    fun eachDeclaredBuildLaysStoriesOutFullScreen() = eachDeclaredBundle(::laysStoriesOut)

    /**
     * #491: the reel item layout asks with the item's shape in front of its compare with a bit over
     * 9:16, and a yes goes where the screen check sends a phone, the plain whole-item layout.
     */
    @Test
    fun eachDeclaredBuildGivesWideScreensTheWholeWidth() = eachDeclaredBundle(::fillsWideScreens)

    /**
     * #681: right before the reel video binder sets the cover's and the video's gravity, it asks
     * ReelFill.fitVideo, and a yes hands those views, the binder's post's own width and height (its
     * getters naming original_width and original_height) and the width the binder gave the video to
     * ReelFill.fitBox. A no, or no post, goes straight on to Instagram's gravity.
     */
    @Test
    fun eachDeclaredBuildNarrowsAReelToItsShape() = eachDeclaredBundle(::narrowsTheReel)

    private fun narrowsTheReel(bundle: File, label: String) {
        val loading = FixtureDex.classesLoading(bundle, REEL_BIND_TRACE)
        val posts = FixtureDex.classes(bundle, setOf(POST_TYPE)).values
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, (loading + posts).distinctBy { it.type }))
        val bind = loading.map { it.type }.toSet().flatMap { context.mutableClassDefBy(it).methods }.single {
            context.loadsString(it, REEL_BIND_TRACE)
        }
        val before = bind.code()
        val store = before.indices.single { before[it].referenceText() == "Landroid/view/ViewGroup\$LayoutParams;->width:I" }
        val made = (store + 1 until before.size).first { before[it].referenceText() == "[Landroid/view/View;" }
        assertTrue("$label: the views made after the width", before[made].opcode in setOf(Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE))
        assertEquals("$label: into a register", Opcode.MOVE_RESULT_OBJECT, before[made + 1].opcode)
        val views = (before[made + 1] as OneRegisterInstruction).registerA
        val gravity = made + 2
        assertTrue("$label: then their gravity", (gravity until before.size).any {
            before[it].referenceText() == "Landroid/widget/FrameLayout\$LayoutParams;->gravity:I"
        })
        val stored = (before[store] as TwoRegisterInstruction).registerA
        val copied = (store - 1 downTo 0).first { (before[it] as? OneRegisterInstruction)?.registerA == stored }
        assertEquals("$label: the video's width copied in", Opcode.MOVE_FROM16, before[copied].opcode)
        val width = (before[copied] as TwoRegisterInstruction).registerB
        fun getter(size: String) = posts.single().methods.single {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Integer;" &&
                it.code().firstOrNull()?.string() == size
        }.toReference().toString()
        val postWidth = getter("original_width")
        val postHeight = getter("original_height")
        val self = bind.implementation!!.registerCount - 1 - bind.parameterTypes.size
        val native = NeutralNativePath(bind)

        context.fitReelVideo()

        val code = bind.code()
        val wide = views > 15
        val added = if (wide) 13 else 12
        assertEquals("$label: $added instructions added", before.count { it.opcode != Opcode.NOP } + added, code.count { it.opcode != Opcode.NOP })
        assertEquals("$label: one ask", 1, code.count { it.referenceText() == FIT_VIDEO_HOOK })
        assertEquals("$label: one hand-over", 1, code.count { it.referenceText() == FIT_BOX_HOOK })
        assertEquals("$label: asked in front of the gravity", FIT_VIDEO_HOOK, code[gravity].referenceText())
        assertEquals("$label: an invoke-static", Opcode.INVOKE_STATIC, code[gravity].opcode)
        assertEquals("$label: with nothing", 0, (code[gravity] as FiveRegisterInstruction).registerCount)
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, code[gravity + 1].opcode)
        val held = (code[gravity + 1] as OneRegisterInstruction).registerA
        assertTrue("$label: in a local an invoke names", held <= 15)
        assertEquals("$label: a no goes on", Opcode.IF_EQZ, code[gravity + 2].opcode)
        assertEquals("$label: on the answer", held, (code[gravity + 2] as OneRegisterInstruction).registerA)
        assertEquals("$label: the binder", Opcode.MOVE_OBJECT_FROM16, code[gravity + 3].opcode)
        assertEquals("$label: out of this", held to self, (code[gravity + 3] as TwoRegisterInstruction).let { it.registerA to it.registerB })
        assertEquals("$label: its post", Opcode.IGET_OBJECT, code[gravity + 4].opcode)
        val post = (code[gravity + 4] as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.FieldReference
        assertEquals("$label: a post", POST_TYPE, post.type)
        assertEquals("$label: of the binder's own", bind.definingClass, post.definingClass)
        assertTrue("$label: the one the binder reads", before.any { it.referenceText() == post.toString() })
        assertEquals("$label: off the binder", held to held, (code[gravity + 4] as TwoRegisterInstruction).let { it.registerA to it.registerB })
        assertEquals("$label: no post goes on", Opcode.IF_EQZ, code[gravity + 5].opcode)
        assertEquals("$label: on the post", held, (code[gravity + 5] as OneRegisterInstruction).registerA)
        assertEquals("$label: the post's width", postWidth, code[gravity + 6].referenceText())
        assertEquals("$label: asked of the post", Opcode.INVOKE_VIRTUAL, code[gravity + 6].opcode)
        assertEquals("$label: the post alone", listOf(held), (code[gravity + 6] as FiveRegisterInstruction).let {
            listOf(it.registerC).take(it.registerCount)
        })
        assertEquals("$label: kept", Opcode.MOVE_RESULT_OBJECT, code[gravity + 7].opcode)
        val across = (code[gravity + 7] as OneRegisterInstruction).registerA
        assertTrue("$label: in another local an invoke names", across <= 15 && across != held)
        assertEquals("$label: the post's height", postHeight, code[gravity + 8].referenceText())
        assertEquals("$label: asked of the post too", Opcode.INVOKE_VIRTUAL, code[gravity + 8].opcode)
        assertEquals("$label: the post alone again", listOf(held), (code[gravity + 8] as FiveRegisterInstruction).let {
            listOf(it.registerC).take(it.registerCount)
        })
        assertEquals("$label: over the post", Opcode.MOVE_RESULT_OBJECT, code[gravity + 9].opcode)
        assertEquals("$label: in its local", held, (code[gravity + 9] as OneRegisterInstruction).registerA)
        assertEquals("$label: the width", Opcode.MOVE_FROM16, code[gravity + 10].opcode)
        val widthCopy = (code[gravity + 10] as TwoRegisterInstruction).registerA
        assertEquals("$label: the video's", width, (code[gravity + 10] as TwoRegisterInstruction).registerB)
        assertTrue("$label: in a third local an invoke names", widthCopy <= 15 && widthCopy != held && widthCopy != across)
        var hand = gravity + 11
        val arrayIn = if (wide) {
            assertEquals("$label: the views copied", Opcode.MOVE_OBJECT_FROM16, code[hand].opcode)
            assertEquals("$label: from their array", views, (code[hand] as TwoRegisterInstruction).registerB)
            (code[hand++] as TwoRegisterInstruction).registerA
        } else {
            views
        }
        assertEquals("$label: then handed over", FIT_BOX_HOOK, code[hand].referenceText())
        assertEquals("$label: an invoke-static", Opcode.INVOKE_STATIC, code[hand].opcode)
        assertEquals("$label: with the views, the post's width and height and the video's width", listOf(arrayIn, across, held, widthCopy),
            (code[hand] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE, it.registerF).take(it.registerCount) })
        if (wide) assertTrue("$label: the views in a fourth local", arrayIn <= 15 && arrayIn !in setOf(held, across, widthCopy))
        assertEquals("$label: then Instagram's gravity", before[gravity].opcode, code[hand + 1].opcode)
        assertEquals("$label: as it was", (before[gravity] as? OneRegisterInstruction)?.registerA,
            (code[hand + 1] as? OneRegisterInstruction)?.registerA)
        val flow = ControlFlow.of(bind)
        assertEquals("$label: a no reaches the gravity", hand + 1, flow.normal[gravity + 2].first())
        assertEquals("$label: and no post", hand + 1, flow.normal[gravity + 5].first())
        native.assertPreserved(label, bind, (gravity..hand).toSet())
    }

    private fun fillsWideScreens(bundle: File, label: String) {
        val types = typesLoading(bundle, REEL_INSETS_FLAG)
        val context = PatchContexts.of(FixtureDex.classes(bundle, types).values)
        val render = types.flatMap { context.mutableClassDefBy(it).methods }.single { it.loads(REEL_INSETS_FLAG) }
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
        val methods = context.mutableClassDefBy(layout.tall.definingClass).methods
        fun own(call: MethodReference) = methods.single {
            it.name == call.name && it.returnType == call.returnType &&
                it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
        }
        val tall = own(layout.tall)
        val sized = own(layout.sizedTall)
        val holder = layout.full.parameterTypes.single().toString()
        assertEquals("$label: the tall-screen check asks about the story", tall.parameterTypes[1].toString(),
            sized.parameterTypes[0].toString())
        assertEquals("$label: the sized check takes a width and a height", listOf("I", "I"),
            sized.parameterTypes.drop(1).map(CharSequence::toString))
        // 454's other (holder) layout, behind a second check, keeps the card's rounded corners.
        assertTrue("$label: the whole-screen layout squares the card's corners",
            own(layout.full).code().any { it.referenceText()?.endsWith("->setCornerRadius(I)V") == true })
        // 457 lays the card out from the holder's frame and views, so each card layout sits behind one of the two checks.
        val cards = methods.filter { method -> method.parameterTypes.any { it.toString().endsWith("/RoundedCornerFrameLayout;") } }
        assertTrue("$label: the card layouts take the story's rounded frame", cards.isNotEmpty())
        assertTrue("$label: the whole-screen layout takes the holder", holder.startsWith("L"))
        val before = listOf(tall, sized).associateWith { it.code() }
        val natives = listOf(tall, sized).associateWith { NeutralNativePath(it) }

        context.fillStoryScreen()

        for ((what, method) in listOf("$label tall-screen check" to tall, "$label sized layout's check" to sized)) {
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
            assertEquals("$what: past the return", 2 + 1 + 1, (code[2] as OffsetInstruction).codeOffset)
            assertEquals("$what: a yes answers", Opcode.CONST_4, code[3].opcode)
            assertEquals("$what: no card", 0, (code[3] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$what: into the answer", 0, (code[3] as OneRegisterInstruction).registerA)
            assertEquals("$what: and returns it", Opcode.RETURN, code[4].opcode)
            assertEquals("$what: returning the answer", 0, (code[4] as OneRegisterInstruction).registerA)
            assertEquals("$what: then Instagram's first instruction", stock[0].opcode, code[5].opcode)
            natives.getValue(method).assertPreserved(what, method, (0 until 5).toSet())
        }
        val hooked = methods.filter { method -> method.code().any { it.referenceText() == STORY_HOOK } }
        assertEquals("$label: only the two checks ask", setOf(tall, sized).map { it.toString() }.toSet(), hooked.map { it.toString() }.toSet())
    }

    /**
     * #618: the story binder asks first, with its holder and whether the story has a sticker in the
     * list Instagram's own shared-reel check reads, which a reshared post is in too, and a yes lays
     * out the whole screen before anything else runs. The whole-screen
     * layout hands the holder and its frame over in front of its one return, and every branch that
     * went to the return reaches the hand-over first. So does the second (holder) layout, which sized
     * a shared reel's frame back to the whole room right after. Every other build of the declared
     * versions too.
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
        val stickers = context.findSharedPostStickers()
        assertEquals("$label: the story's own sticker list", STORY_REEL_ITEM, stickers.list.definingClass)
        assertEquals("$label: a list", "Ljava/util/List;", stickers.list.returnType)
        assertEquals("$label: of one kind", listOf(stickers.kind.type), stickers.list.parameterTypes.map(CharSequence::toString))
        assertEquals("$label: the kind is a constant of its own type", stickers.kind.type, stickers.kind.definingClass)
        fun MethodReference.same(method: Method) = method.name == name &&
            method.parameterTypes.map(CharSequence::toString) == parameterTypes.map(CharSequence::toString)
        val binder = context.mutableClassDefBy(found.method.definingClass).methods.single { found.method.toReference().same(it) }
        fun static(call: MethodReference) = context.mutableClassDefBy(call.definingClass).methods.single {
            call.same(it) && AccessFlags.STATIC.isSet(it.accessFlags)
        }
        val full = static(layout.full)
        // The story layout's other (holder) layout sizes the same frame to the whole room again.
        val reset = static(layout.reset ?: throw AssertionError("$label: no second whole-room layout"))
        assertEquals("$label: the binder binds a story", STORY_REEL_ITEM, binder.parameterTypes[found.item].toString())
        val item = binder.parameterRegisterNumber(found.item)
        val holder = binder.parameterRegisterNumber(found.holder)
        val getter = findStoryFrameGetter(full)
        assertTrue("$label: the frame is the story's rounded frame",
            getter.getter.returnType.endsWith("/RoundedCornerFrameLayout;"))
        assertEquals("$label: the second layout sizes the same frame", getter, findStoryFrameGetter(reset))
        val bindBefore = binder.code()
        val stock = listOf(full, reset).associateWith { method ->
            val before = method.code()
            val returnAt = before.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
            val flow = ControlFlow.of(method)
            val branches = before.indices.filter { before[it] is OffsetInstruction && flow.normal[it].first() == returnAt }
            assertTrue("$label: something branches to the return of ${method.name}", branches.isNotEmpty())
            Triple(before, returnAt, branches) to NeutralNativePath(method)
        }
        val bindNative = NeutralNativePath(binder)

        context.layStoriesOutAsTheyBind()

        val code = binder.code()
        val bind = "$label binder"
        fun FiveRegisterInstruction.arguments() = listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        assertEquals("$bind: thirteen instructions added", bindBefore.count { it.opcode != Opcode.NOP } + 13, code.count { it.opcode != Opcode.NOP })
        assertEquals("$bind: no until asked", Opcode.CONST_4, code[0].opcode)
        assertEquals("$bind: in the second local", 1, (code[0] as OneRegisterInstruction).registerA)
        assertEquals("$bind: no", 0, (code[0] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$bind: no story isn't asked about", Opcode.IF_EQZ, code[1].opcode)
        assertEquals("$bind: on the story", item, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$bind: the story", Opcode.MOVE_OBJECT_FROM16, code[2].opcode)
        assertEquals("$bind: into the first local", 0 to item, (code[2] as TwoRegisterInstruction).let { it.registerA to it.registerB })
        assertEquals("$bind: the kind of sticker", Opcode.SGET_OBJECT, code[3].opcode)
        assertEquals("$bind: that shares a post", stickers.kind.toString(), code[3].referenceText())
        assertEquals("$bind: in the second local", 1, (code[3] as OneRegisterInstruction).registerA)
        assertEquals("$bind: an invoke-virtual", Opcode.INVOKE_VIRTUAL, code[4].opcode)
        assertEquals("$bind: of the story's sticker list", stickers.list.toString(), code[4].referenceText())
        assertEquals("$bind: on the story and the kind", listOf(0, 1), (code[4] as FiveRegisterInstruction).arguments())
        assertEquals("$bind: the stickers", Opcode.MOVE_RESULT_OBJECT, code[5].opcode)
        assertEquals("$bind: in the second local", 1, (code[5] as OneRegisterInstruction).registerA)
        assertEquals("$bind: one look at them", 1, code.count { it.referenceText() == SHARES_POST_HOOK })
        assertEquals("$bind: handed over", SHARES_POST_HOOK, code[6].referenceText())
        assertEquals("$bind: with an invoke-static", Opcode.INVOKE_STATIC, code[6].opcode)
        assertEquals("$bind: of the stickers", listOf(1), (code[6] as FiveRegisterInstruction).arguments())
        assertEquals("$bind: its answer", Opcode.MOVE_RESULT, code[7].opcode)
        assertEquals("$bind: in the second local", 1, (code[7] as OneRegisterInstruction).registerA)
        assertEquals("$bind: then the holder", Opcode.MOVE_OBJECT_FROM16, code[8].opcode)
        assertEquals("$bind: into the first local", 0 to holder, (code[8] as TwoRegisterInstruction).let { it.registerA to it.registerB })
        assertEquals("$bind: one ask", 1, code.count { it.referenceText() == STORY_BOUND_HOOK })
        assertEquals("$bind: then the ask", STORY_BOUND_HOOK, code[9].referenceText())
        assertEquals("$bind: an invoke-static", Opcode.INVOKE_STATIC, code[9].opcode)
        assertEquals("$bind: on the holder and the answer", listOf(0, 1), (code[9] as FiveRegisterInstruction).arguments())
        assertEquals("$bind: the ask's answer", Opcode.MOVE_RESULT, code[10].opcode)
        assertEquals("$bind: in the second local", 1, (code[10] as OneRegisterInstruction).registerA)
        assertEquals("$bind: a no goes on", Opcode.IF_EQZ, code[11].opcode)
        assertEquals("$bind: on the ask's answer", 1, (code[11] as OneRegisterInstruction).registerA)
        assertEquals("$bind: a yes lays out the whole screen", layout.full.toString(), code[12].referenceText())
        assertEquals("$bind: with an invoke-static", Opcode.INVOKE_STATIC, code[12].opcode)
        assertEquals("$bind: on the holder", listOf(0), (code[12] as FiveRegisterInstruction).arguments())
        assertEquals("$bind: then Instagram's first instruction", bindBefore[0].opcode, code[13].opcode)
        val bindFlow = ControlFlow.of(binder)
        assertEquals("$bind: no story goes straight to the holder", 8, bindFlow.normal[1].first())
        assertEquals("$bind: a no skips the layout", 13, bindFlow.normal[11].first())
        bindNative.assertPreserved(bind, binder, (0 until 13).toSet())

        for ((what, method) in listOf("whole-screen layout" to full, "second whole-room layout" to reset)) {
            val (before, returnAt, branches) = stock.getValue(method).first
            val after = method.code()
            val lay = "$label $what"
            val layHolder = method.parameterRegisterNumber(0)
            assertEquals("$lay: three instructions added", before.count { it.opcode != Opcode.NOP } + 3, after.count { it.opcode != Opcode.NOP })
            assertEquals("$lay: one hand-over", 1, after.count { it.referenceText() == STORY_FRAME_HOOK })
            assertEquals("$lay: the frame fetched where the return was", getter.getter.toString(), after[returnAt].referenceText())
            assertEquals("$lay: as the layout fetches it", getter.call, if (after[returnAt].opcode == Opcode.INVOKE_INTERFACE) "invoke-interface" else "invoke-virtual")
            assertTrue("$lay: an invoke", after[returnAt].opcode == Opcode.INVOKE_INTERFACE || after[returnAt].opcode == Opcode.INVOKE_VIRTUAL)
            assertEquals("$lay: from the holder", listOf(layHolder), (after[returnAt] as FiveRegisterInstruction).let {
                listOf(it.registerC).take(it.registerCount)
            })
            assertEquals("$lay: into a local", Opcode.MOVE_RESULT_OBJECT, after[returnAt + 1].opcode)
            val frame = (after[returnAt + 1] as OneRegisterInstruction).registerA
            assertTrue("$lay: a local", frame < method.implementation!!.registerCount - method.parameters.size)
            assertEquals("$lay: then the hand-over", STORY_FRAME_HOOK, after[returnAt + 2].referenceText())
            assertEquals("$lay: an invoke-static", Opcode.INVOKE_STATIC, after[returnAt + 2].opcode)
            assertEquals("$lay: of the holder and the frame", listOf(layHolder, frame), (after[returnAt + 2] as FiveRegisterInstruction).let {
                listOf(it.registerC, it.registerD).take(it.registerCount)
            })
            assertEquals("$lay: then the return", Opcode.RETURN_VOID, after[returnAt + 3].opcode)
            val flow = ControlFlow.of(method)
            for (branch in branches) {
                assertEquals("$lay: instruction $branch reaches the hand-over", returnAt, flow.normal[branch].first())
            }
            stock.getValue(method).second.assertPreserved(lay, method, (returnAt until returnAt + 3).toSet())
        }
    }

    /**
     * #618: a build whose shared-reel check reads no sticker list, or reads one without naming its
     * kind from a constant, stops the patch instead of guessing what a story shares.
     */
    @Test
    fun aSharedReelCheckWithoutOneStickerListFailsThePatch() {
        val kind = ImmutableFieldReference(STICKER_KIND, "media", STICKER_KIND)
        val list = ImmutableMethodReference(STORY_REEL_ITEM, "stickers", listOf(STICKER_KIND), "Ljava/util/List;")
        fun refused(why: String, vararg check: Instruction) {
            val context = PatchContexts.of(storyModels(*check))
            val refusal = assertThrows(PatchException::class.java) { context.findSharedPostStickers() }
            assertTrue(refusal.message, refusal.message!!.contains(why))
        }
        refused("reads 0 sticker lists", ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, CLIPS_FIELD),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0))
        refused("doesn't ask the story for one kind of sticker",
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 2, 0, 0, 0, 0, list),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, CLIPS_FIELD),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0))

        val found = PatchContexts.of(storyModels(
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, kind),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 2, 0, 0, 0, 0, list),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, CLIPS_FIELD),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0),
        )).findSharedPostStickers()
        assertEquals("the list", list.toString(), found.list.toString())
        assertEquals("of that kind", kind.toString(), found.kind.toString())
    }

    /** ProductType naming CLIPS, and a story whose one no-argument check reads it with [check] on three registers. */
    private fun storyModels(vararg check: Instruction): List<ClassDef> {
        val productType = ImmutableClassDef(REEL_PRODUCT_TYPE, AccessFlags.PUBLIC.value, "Ljava/lang/Enum;", null, null, null, null,
            listOf(ImmutableMethod(REEL_PRODUCT_TYPE, "<clinit>", emptyList<ImmutableMethodParameter>(), "V",
                AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(1, listOf(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("CLIPS")),
                    ImmutableInstruction21c(Opcode.SPUT_OBJECT, 0, CLIPS_FIELD),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ), null, null))))
        val story = ImmutableClassDef(STORY_REEL_ITEM, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, listOf(ImmutableMethod(STORY_REEL_ITEM, "A0w", emptyList<ImmutableMethodParameter>(), "Z",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(3, check.toList(), null, null))))
        return listOf(productType, story)
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
        val types = typesLoading(bundle, REEL_CROP_FLAG)
        for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
            val what = "$label ($read)"
            val context = PatchContexts.of(classesOf(bundle, types).values)
            val decision = types.flatMap { context.mutableClassDefBy(it).methods }.single { it.isCropDecision() }
            val before = decision.code()
            val multiply = before.timesHundred(what)
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
        val types = typesLoading(bundle, REEL_CROP_FLAG)
        for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
            val what = "$label ($read)"
            val context = PatchContexts.of(classesOf(bundle, types).values)
            val decision = types.flatMap { context.mutableClassDefBy(it).methods }.single { it.isCropDecision() }
            val before = decision.code()
            val multiply = before.timesHundred(what)
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

    private fun Method.isCropDecision(): Boolean =
        returnType == "Z" && loads(REEL_CROP_FLAG) && parameterTypes.any { it.toString() == REEL_VIEWER_SOURCE }

    private fun Method.loads(flag: Long): Boolean = code().any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == flag
    }

    /** The types of the classes in [bundle] with a method loading [flag] as a wide constant. */
    private fun typesLoading(bundle: File, flag: Long): Set<String> {
        val types = mutableSetOf<String>()
        FixtureDex.forEach(bundle) { dex -> for (classDef in dex.classes) if (classDef.methods.any { it.loads(flag) }) types += classDef.type }
        return types
    }

    /** The one place the decision multiplies by a 100f it just loaded, read straight off the stock code. */
    private fun List<Instruction>.timesHundred(what: String): Int {
        val found = indices.filter { at ->
            val hundred = getOrNull(at - 1)
            this[at].opcode == Opcode.MUL_FLOAT_2ADDR && hundred?.opcode == Opcode.CONST_HIGH16 &&
                java.lang.Float.intBitsToFloat((hundred as NarrowLiteralInstruction).narrowLiteral) == 100f &&
                (hundred as OneRegisterInstruction).registerA == (this[at] as TwoRegisterInstruction).registerB
        }
        assertEquals("$what: multiplies by 100 once", 1, found.size)
        return found.single()
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.string(): String? =
        ((this as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        const val POST_TYPE = "Lcom/instagram/feed/media/Media;"
    }
}

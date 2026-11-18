/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.download

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.story.BIND_DOWNLOAD
import app.morphe.patches.instagram.download.story.LIST_HOLD_REASON
import app.morphe.patches.instagram.download.story.LIST_VIEWER_SHOWN
import app.morphe.patches.instagram.download.story.STORY_LIST_HOLD
import app.morphe.patches.instagram.download.story.STORY_REEL_READER
import app.morphe.patches.instagram.download.story.findStoryButton
import app.morphe.patches.instagram.download.story.findStoryListHold
import app.morphe.patches.instagram.download.story.hookStoryButton
import app.morphe.patches.instagram.download.story.hookStoryListHold
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.stories.autoadvance.STORY_VIEWER
import app.morphe.patches.instagram.stories.follow.BIND_FOLLOW
import app.morphe.patches.instagram.stories.follow.STORY_FOLLOW_BUTTON
import app.morphe.patches.instagram.stories.follow.hideStoryFollow
import app.morphe.patches.instagram.stories.seen.BIND_BUTTON
import app.morphe.patches.instagram.stories.seen.REEL_ITEM
import app.morphe.patches.instagram.stories.seen.StorySeenHookTest
import app.morphe.patches.instagram.stories.seen.USER_SESSION
import app.morphe.patches.instagram.stories.seen.holdBackStoryViews
import app.morphe.patches.instagram.stories.zoom.PAUSE_MARK
import app.morphe.patches.instagram.stories.zoom.RESUME_MARK
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Download button on stories (#400): the story item binder's toolbar bind hands the story, its
 * view holder, the viewer's model of the reel and the session (#745) to StoryDownloadButton.bind,
 * after Mark as seen's and Hide Follow's hooks when they are there, and the stubs read the story's
 * Media, the view holder base's item view and the reel's stories.
 */
class DownloadStoryButtonHookTest {
    private val media = "$REEL_ITEM->media:Lfixture/Media;"

    @Test
    fun theHookAndTheStubsAreInTheExtension() {
        val methods = ExtensionDex.classDef(BUTTON).methods
        fun key(method: Method) = "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
        assertTrue(methods.any {
            key(it) == BIND_DOWNLOAD.substringAfter("->") && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        })
        assertTrue(methods.any { key(it) == "itemView($OBJECT)$VIEW" && AccessFlags.STATIC.isSet(it.accessFlags) })
        assertTrue(methods.any { key(it) == "media($OBJECT)$OBJECT" && AccessFlags.STATIC.isSet(it.accessFlags) })
        assertTrue(ExtensionDex.classDef(STORY_REEL_READER).methods.any {
            key(it) == "items($OBJECT$OBJECT)$LIST" && AccessFlags.STATIC.isSet(it.accessFlags)
        })
        val reader = ExtensionDex.classDef(STORY_REEL_READER).methods
        assertTrue(reader.any { key(it) == "pause($OBJECT$OBJECT)V" && AccessFlags.STATIC.isSet(it.accessFlags) })
        assertTrue(reader.any { key(it) == "play($OBJECT$OBJECT$OBJECT)V" && AccessFlags.STATIC.isSet(it.accessFlags) })
        assertTrue(ExtensionDex.classDef(STORY_LIST_HOLD).methods.any {
            key(it) == LIST_VIEWER_SHOWN.substringAfter("->") && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        })
    }

    /** The stubs pause and play through the viewer's own methods, and onResume says the viewer is back, right after its superclass's. */
    @Test
    fun theViewerIsHeldThroughItsOwnPauseAndResume() {
        val context = PatchContexts.of(listHoldClasses())

        val found = context.findStoryListHold()
        context.hookStoryListHold(found)

        assertEquals("$STORY_VIEWER->A18($STORY_VIEWER$STRING)V", found.playback.pause.reference())
        assertEquals("$STORY_VIEWER->A19($STORY_VIEWER${STRING}Z)V", found.playback.resume.reference())
        assertListHold("stand-in", context, found.playback.pause, found.playback.resume)
    }

    @Test
    fun aSecondRunRefusesTheListHold() {
        val context = PatchContexts.of(listHoldClasses())
        context.hookStoryListHold(context.findStoryListHold())

        val refusal = assertThrows(PatchException::class.java) { context.findStoryListHold() }

        assertTrue(refusal.message, "already calls $STORY_LIST_HOLD" in refusal.message!!)
        assertEquals(1, resumeOf(context).code().count { it.referenceText() == LIST_VIEWER_SHOWN })
    }

    /** The stubs sit in the extension's package, so what they call has to be public. */
    @Test
    fun aViewerTheStubsCantReachFails() {
        val static = AccessFlags.STATIC.value
        assertListHoldRefused(listHoldClasses(storyViewer(viewerFlags = AccessFlags.FINAL.value)), "$STORY_VIEWER isn't public")
        assertListHoldRefused(listHoldClasses(storyViewer(pauseFlags = static)), "$STORY_VIEWER->A18 isn't public")
        assertListHoldRefused(listHoldClasses(storyViewer(resumeFlags = static)), "$STORY_VIEWER->A19 isn't public")
    }

    @Test
    fun aViewerWithNoPlaceForTheCallFails() {
        assertListHoldRefused(listHoldClasses(storyViewer(onResume = false)), "has no onResume() of its own")
        assertListHoldRefused(listHoldClasses(storyViewer(callsSuper = false)), "to call its superclass's once, found 0")
    }

    @Test
    fun anExtensionWithoutTheListHoldFails() {
        assertListHoldRefused(listHoldClasses().filter { it.type != STORY_LIST_HOLD }, "$STORY_LIST_HOLD isn't in the extension")
        assertListHoldRefused(listHoldClasses().filter { it.type != STORY_REEL_READER }, "$STORY_REEL_READER isn't in the extension")
    }

    @Test
    fun theBinderHandsTheButtonTheStoryAndItsViewHolderFirst() {
        val classes = standIns()
        val context = PatchContexts.of(classes)

        context.hookStoryButton(context.findStoryButton(media))

        val binder = binderOf(context)
        assertHooked("stand-in", binder, 0)
        assertEquals(originalBinder(classes).code().map { it.referenceText() }, binder.code().drop(5).map { it.referenceText() })
        assertStubs(context, VIEW_HOLDER, "$VIEW_HOLDER->itemView:$VIEW")
        assertEquals(1, calls(context))
    }

    /** Beside Mark as seen and Hide Follow, in any order, their hooks stay ahead of this one. */
    @Test
    fun theHookSitsAfterTheOtherHooksInAnyOrder() {
        val classes = standIns() + ImmutableClassDef.of(ExtensionDex.classDef(STORY_FOLLOW_BUTTON))
        val orders = listOf<(BytecodePatchContext) -> Unit>(
            { it.holdBackStoryViews(); it.hideStoryFollow(); it.hookStoryButton(it.findStoryButton(media)) },
            { it.hookStoryButton(it.findStoryButton(media)); it.holdBackStoryViews(); it.hideStoryFollow() },
            { it.holdBackStoryViews(); it.hookStoryButton(it.findStoryButton(media)); it.hideStoryFollow() },
        )
        for ((n, order) in orders.withIndex()) {
            val context = PatchContexts.of(classes)
            order(context)
            val code = binderOf(context).code()
            assertEquals("order $n: Mark as seen first", BIND_BUTTON, code[3].referenceText())
            val mine = code.indexOfFirst { it.referenceText() == BIND_DOWNLOAD }
            assertTrue("order $n: after Mark as seen", mine >= 4 + 5 - 1)
            assertEquals("order $n: one call", 1, calls(context))
            assertEquals("order $n: one Follow call", 1, code.count { it.referenceText() == BIND_FOLLOW })
        }
    }

    @Test
    fun aSecondRunRefusesAndLeavesOneHook() {
        val context = PatchContexts.of(standIns())
        context.hookStoryButton(context.findStoryButton(media))

        val refusal = assertThrows(PatchException::class.java) { context.hookStoryButton(context.findStoryButton(media)) }

        assertTrue(refusal.message, "already calls $BIND_DOWNLOAD" in refusal.message!!)
        assertEquals(1, calls(context))
    }

    @Test
    fun aBuildWithoutTheBinderOrTheExtensionClassFails() {
        assertRefused(standIns(binder = false), "story item binder's toolbar bind")
        assertRefused(standIns().filter { it.type != BUTTON }, "$BUTTON isn't in the extension")
        assertRefused(standIns().filter { it.type != STORY_REEL_READER }, "$STORY_REEL_READER isn't in the extension")
    }

    @Test
    fun aBinderWithoutALocalOrTheViewHolderFails() {
        assertRefused(standIns(binderLocals = 3), "needs 4")
        assertRefused(standIns(binderParameters = listOf(DELEGATE, USER_SESSION, REEL_ITEM, VIEWER, VIEWER, "Z")), "to take one view holder, found 0")
        assertRefused(standIns(binderParameters = listOf(DELEGATE, USER_SESSION, VIEWER, VIEWER, HOLDER, "Z")), "to take one story")
    }

    /** Press and hold reads the reel through the model the bind is handed, so a bind without one can't be hooked (#745). */
    @Test
    fun aBinderWithoutTheReelsModelOrTheSessionFails() {
        assertRefused(standIns(binderParameters = listOf(DELEGATE, USER_SESSION, REEL_ITEM, "Z", HOLDER, "Z")), "one model of a reel's stories, found 0")
        assertRefused(standIns(binderParameters = listOf(DELEGATE, DELEGATE, REEL_ITEM, VIEWER, HOLDER, "Z")), "to take one $USER_SESSION, found 0")
        val hidden = standIns().map {
            if (it.type != VIEWER) it
            else ImmutableClassDef(it.type, AccessFlags.FINAL.value, it.superclass, it.interfaces, null, null, it.fields, it.methods)
        }
        assertRefused(hidden, "$VIEWER isn't public")
    }

    @Test
    fun aBranchToTheBindersStartFails() = assertRefused(standIns(binderLoops = true), "lands on its first instruction")

    /** On each declared build the toolbar bind is found, hooked once and the stubs filled. */
    @Test
    fun eachDeclaredBuildGetsTheButton() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildGetsTheButtonToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val classes = StorySeenHookTest().fixtureClasses(bundle) + ImmutableClassDef.of(ExtensionDex.classDef(BUTTON)) +
            ImmutableClassDef.of(ExtensionDex.classDef(STORY_REEL_READER))
        val context = PatchContexts.of(classes)
        val found = context.findStoryButton(media)
        context.hookStoryButton(found)
        val binder = context.mutableClassDefBy(found.binder).methods.single {
            it.name == found.binderName && it.parameterTypes.map(Any::toString) == found.binderParameters
        }
        assertHooked(label, binder, 0, found.item, found.holder, found.reel, found.session)
        assertStubs(context, found.itemView.definingClass, found.itemView.toString(), found.reelClass, found.reelItems)
        assertEquals("$label: one call", 1, calls(context))
        holdsTheStory(bundle, label)
    }

    private fun holdsTheStory(bundle: File, label: String) {
        val viewers = FixtureDex.withStringPools(
            bundle, listOf(PAUSE_MARK, RESUME_MARK).flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type },
        )
        val context = PatchContexts.of(viewers + extensionListHold())
        val found = context.findStoryListHold()
        context.hookStoryListHold(found)
        assertListHold(label, context, found.playback.pause, found.playback.resume)
        val stock = viewers.single { it.type == STORY_VIEWER }.methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() }
        assertEquals(
            "$label: onResume grows by the one call",
            stock.code().count { it.opcode != Opcode.NOP } + 1,
            resumeOf(context).code().count { it.opcode != Opcode.NOP },
        )
    }

    private fun assertListHold(what: String, context: BytecodePatchContext, pause: Method, resume: Method) {
        val shown = resumeOf(context)
        val code = shown.code()
        val at = code.indexOfFirst { it.referenceText() == LIST_VIEWER_SHOWN }
        assertTrue("$what: onResume says the viewer is back", at > 0)
        assertEquals("$what: once", 1, code.count { it.referenceText() == LIST_VIEWER_SHOWN })
        assertTrue("$what: right after the superclass's", code[at - 1].opcode == Opcode.INVOKE_SUPER || code[at - 1].opcode == Opcode.INVOKE_SUPER_RANGE)
        assertTrue("$what: the superclass's onResume", code[at - 1].referenceText()!!.contains("->onResume()V"))
        val call = code[at] as RegisterRangeInstruction
        assertEquals("$what: it's handed the viewer", shown.localRegisterCount() to 1, call.startRegister to call.registerCount)

        val reader = context.mutableClassDefBy(STORY_REEL_READER).methods
        val paused = reader.single { it.name == "pause" }.code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.CONST_STRING, pause.invoke(), Opcode.RETURN_VOID), paused.take(4).map { it.opcode })
        assertEquals(STORY_VIEWER, paused[0].referenceText())
        assertEquals(LIST_HOLD_REASON, paused[1].stringValue())
        assertEquals(pause.reference(), paused[2].referenceText())
        val played = reader.single { it.name == "play" }.code()
        assertEquals(
            listOf(Opcode.CHECK_CAST, Opcode.CONST_STRING, Opcode.CONST_4, resume.invoke(), Opcode.RETURN_VOID),
            played.take(5).map { it.opcode },
        )
        assertEquals(STORY_VIEWER, played[0].referenceText())
        assertEquals("resume", played[1].stringValue())
        assertEquals(1, (played[2] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(resume.reference(), played[3].referenceText())
    }

    private fun assertListHoldRefused(classes: List<ClassDef>, why: String) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(why, PatchException::class.java) { context.hookStoryListHold(context.findStoryListHold()) }
        assertTrue(refusal.message, refusal.message!!.startsWith("Download any story: ") && why in refusal.message!!)
        val viewer = context.mutableClassDefBy(STORY_VIEWER)
        assertTrue("$why: something was written", viewer.methods.none { method -> method.code().any { it.referenceText() == LIST_VIEWER_SHOWN } })
    }

    private fun resumeOf(context: BytecodePatchContext): Method =
        context.mutableClassDefBy(STORY_VIEWER).methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() }

    private fun extensionListHold(): List<ClassDef> =
        listOf(STORY_REEL_READER, STORY_LIST_HOLD).map { ImmutableClassDef.of(ExtensionDex.classDef(it)) }

    private fun listHoldClasses(viewer: ClassDef = storyViewer()): List<ClassDef> = listOf(viewer) + extensionListHold()

    /** The story viewer as the list hold reads it: its static pause and resume, and an onResume that calls its superclass's. */
    private fun storyViewer(
        viewerFlags: Int = AccessFlags.PUBLIC.value,
        pauseFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        resumeFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        onResume: Boolean = true,
        callsSuper: Boolean = true,
    ): ClassDef {
        fun method(name: String, parameters: List<String>, flags: Int, registers: Int, vararg code: Instruction) = ImmutableMethod(
            STORY_VIEWER, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", flags, null, null,
            ImmutableMethodImplementation(registers, code.toList(), null, null),
        )
        val superCall = ImmutableInstruction35c(
            Opcode.INVOKE_SUPER, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(FRAGMENT, "onResume", emptyList<String>(), "V"),
        )
        val back = ImmutableInstruction10x(Opcode.RETURN_VOID)
        val methods = listOfNotNull(
            method(
                "A18", listOf(STORY_VIEWER, STRING), pauseFlags, 3,
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(PAUSE_MARK)), back,
            ),
            method(
                "A19", listOf(STORY_VIEWER, STRING, "Z"), resumeFlags, 4,
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(RESUME_MARK)), back,
            ),
            when {
                !onResume -> null
                callsSuper -> method("onResume", emptyList(), AccessFlags.PUBLIC.value, 1, superCall, back)
                else -> method("onResume", emptyList(), AccessFlags.PUBLIC.value, 1, back)
            },
        )
        return ImmutableClassDef(STORY_VIEWER, viewerFlags, FRAGMENT, null, null, null, emptyList<ImmutableField>(), methods)
    }

    private fun Method.reference() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Method.invoke() = if (AccessFlags.STATIC.isSet(accessFlags)) Opcode.INVOKE_STATIC else Opcode.INVOKE_VIRTUAL

    private fun Instruction.stringValue(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun assertRefused(classes: List<ClassDef>, why: String) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.findStoryButton(media).also { context.hookStoryButton(it) } }
        assertTrue(refusal.message, refusal.message!!.startsWith("Download any story: ") && why in refusal.message!!)
        assertEquals("a refused build calls the hook", 0, calls(context))
    }

    /** At [at]: the story, the holder, the reel's model and the session moved into v0 to v3, then [BIND_DOWNLOAD] called on them. */
    private fun assertHooked(
        what: String, binder: Method, at: Int, item: Int = ITEM_AT, holder: Int = HOLDER_AT, reel: Int = REEL_AT, session: Int = SESSION_AT,
    ) {
        val code = binder.code()
        assertEquals(
            "$what: the hook's opcodes",
            List(4) { Opcode.MOVE_OBJECT_FROM16 } + Opcode.INVOKE_STATIC,
            code.drop(at).take(5).map { it.opcode },
        )
        assertEquals(
            "$what: v0 to v3 hold the story, the holder, the reel's model and the session",
            listOf(item, holder, reel, session).mapIndexed { local, parameter -> local to binder.parameterRegisterNumber(parameter) },
            code.drop(at).take(4).map { (it as TwoRegisterInstruction).let { move -> move.registerA to move.registerB } },
        )
        val call = code[at + 4] as FiveRegisterInstruction
        assertEquals(BIND_DOWNLOAD, code[at + 4].referenceText())
        assertEquals(
            "$what: the call takes v0 to v3",
            listOf(4, 0, 1, 2, 3),
            listOf(call.registerCount, call.registerC, call.registerD, call.registerE, call.registerF),
        )
        assertTrue("$what: locals to borrow", binder.localRegisterCount() >= 4)
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        for ((index, instruction) in code.withIndex()) {
            if (instruction !is OffsetInstruction) continue
            assertTrue("$what: the branch at $index lands in the hook", addresses[index] + instruction.codeOffset !in addresses.take(at + 5))
        }
    }

    private fun assertStubs(
        context: BytecodePatchContext, base: String, itemView: String, reelClass: String = VIEWER,
        reelItems: String = "$VIEWER->stories($USER_SESSION)$LIST",
    ) {
        val methods = context.mutableClassDefBy(BUTTON).methods
        val view = methods.single { it.name == "itemView" }.code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), view.take(3).map { it.opcode })
        assertEquals(base, view[0].referenceText())
        assertEquals(itemView, view[1].referenceText())
        val story = methods.single { it.name == "media" }.code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), story.take(3).map { it.opcode })
        assertEquals(REEL_ITEM, story[0].referenceText())
        assertEquals(media, story[1].referenceText())
        val items = context.mutableClassDefBy(STORY_REEL_READER).methods.single { it.name == "items" }.code()
        assertEquals(
            listOf(Opcode.CHECK_CAST, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            items.take(5).map { it.opcode },
        )
        assertEquals(reelClass, items[0].referenceText())
        assertEquals(USER_SESSION, items[1].referenceText())
        assertEquals(reelItems, items[2].referenceText())
    }

    private fun calls(context: BytecodePatchContext): Int {
        var count = 0
        context.classDefForEach { classDef ->
            classDef.methods.forEach { method -> count += method.code().count { it.referenceText() == BIND_DOWNLOAD } }
        }
        return count
    }

    private fun standIns(
        binder: Boolean = true,
        binderParameters: List<String> = listOf(DELEGATE, USER_SESSION, REEL_ITEM, VIEWER, HOLDER, "Z"),
        binderLoops: Boolean = false,
        binderLocals: Int = 11,
    ): List<ClassDef> = StorySeenHookTest().standIns(
        binder = binder, binderParameters = binderParameters, binderLoops = binderLoops, binderLocals = binderLocals,
    ) + ImmutableClassDef.of(ExtensionDex.classDef(BUTTON)) + ImmutableClassDef.of(ExtensionDex.classDef(STORY_REEL_READER))

    private fun binderOf(context: BytecodePatchContext): Method = context.mutableClassDefBy(BINDER).methods.single { it.name == "A06" }

    private fun originalBinder(classes: List<ClassDef>): Method = classes.single { it.type == BINDER }.methods.single { it.name == "A06" }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        const val BUTTON = "Lapp/hushgram/extension/instagram/stories/StoryDownloadButton;"
        const val BINDER = "Lfixture/ReelViewerItemBinder;"
        const val VIEW_HOLDER = "Lfixture/ViewHolder;"
        const val HOLDER = "Lfixture/ReelViewerHolder;"
        const val DELEGATE = "Lfixture/Delegate;"
        const val VIEWER = "Lfixture/Viewer;"
        const val OBJECT = "Ljava/lang/Object;"
        const val VIEW = "Landroid/view/View;"
        const val LIST = "Ljava/util/List;"
        const val STRING = "Ljava/lang/String;"
        const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
        const val SESSION_AT = 1
        const val ITEM_AT = 2
        const val REEL_AT = 3
        const val HOLDER_AT = 4
    }
}

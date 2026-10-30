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
import app.morphe.patches.instagram.download.story.BIND_DOWNLOAD
import app.morphe.patches.instagram.download.story.findStoryButton
import app.morphe.patches.instagram.download.story.hookStoryButton
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.stories.follow.BIND_FOLLOW
import app.morphe.patches.instagram.stories.follow.STORY_FOLLOW_BUTTON
import app.morphe.patches.instagram.stories.follow.hideStoryFollow
import app.morphe.patches.instagram.stories.seen.BIND_BUTTON
import app.morphe.patches.instagram.stories.seen.REEL_ITEM
import app.morphe.patches.instagram.stories.seen.StorySeenHookTest
import app.morphe.patches.instagram.stories.seen.USER_SESSION
import app.morphe.patches.instagram.stories.seen.holdBackStoryViews
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Download button on stories (#400): the story item binder's toolbar bind hands the story and its
 * view holder to StoryDownloadButton.bind, after Mark as seen's and Hide Follow's hooks when they
 * are there, and the two stubs read the story's Media and the view holder base's item view.
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
    }

    @Test
    fun theBinderHandsTheButtonTheStoryAndItsViewHolderFirst() {
        val classes = standIns()
        val context = PatchContexts.of(classes)

        context.hookStoryButton(context.findStoryButton(media))

        val binder = binderOf(context)
        assertHooked("stand-in", binder, 0)
        assertEquals(originalBinder(classes).code().map { it.referenceText() }, binder.code().drop(3).map { it.referenceText() })
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
            assertTrue("order $n: after Mark as seen", mine >= 4 + 3 - 1)
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
    }

    @Test
    fun aBinderWithoutALocalOrTheViewHolderFails() {
        assertRefused(standIns(binderLocals = 1), "needs 2")
        assertRefused(standIns(binderParameters = listOf(DELEGATE, USER_SESSION, REEL_ITEM, VIEWER, VIEWER, "Z")), "to take one view holder, found 0")
        assertRefused(standIns(binderParameters = listOf(DELEGATE, USER_SESSION, VIEWER, VIEWER, HOLDER, "Z")), "to take one story")
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
        val classes = StorySeenHookTest().fixtureClasses(bundle) + ImmutableClassDef.of(ExtensionDex.classDef(BUTTON))
        val context = PatchContexts.of(classes)
        val found = context.findStoryButton(media)
        context.hookStoryButton(found)
        val binder = context.mutableClassDefBy(found.binder).methods.single {
            it.name == found.binderName && it.parameterTypes.map(Any::toString) == found.binderParameters
        }
        assertHooked(label, binder, 0, found.item, found.holder)
        assertStubs(context, found.itemView.definingClass, found.itemView.toString())
        assertEquals("$label: one call", 1, calls(context))
    }

    private fun assertRefused(classes: List<ClassDef>, why: String) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.findStoryButton(media).also { context.hookStoryButton(it) } }
        assertTrue(refusal.message, refusal.message!!.startsWith("Download any story: ") && why in refusal.message!!)
        assertEquals("a refused build calls the hook", 0, calls(context))
    }

    /** At [at]: the story and the holder moved into v0 and v1, then [BIND_DOWNLOAD] called on them. */
    private fun assertHooked(what: String, binder: Method, at: Int, item: Int = ITEM_AT, holder: Int = HOLDER_AT) {
        val code = binder.code()
        assertEquals(
            "$what: the hook's opcodes",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC),
            code.drop(at).take(3).map { it.opcode },
        )
        val first = code[at] as TwoRegisterInstruction
        val second = code[at + 1] as TwoRegisterInstruction
        assertEquals("$what: v0 holds the story", 0 to binder.parameterRegisterNumber(item), first.registerA to first.registerB)
        assertEquals("$what: v1 holds the holder", 1 to binder.parameterRegisterNumber(holder), second.registerA to second.registerB)
        val call = code[at + 2] as FiveRegisterInstruction
        assertEquals(BIND_DOWNLOAD, code[at + 2].referenceText())
        assertEquals("$what: the call takes v0 and v1", Triple(2, 0, 1), Triple(call.registerCount, call.registerC, call.registerD))
        assertTrue("$what: locals to borrow", binder.localRegisterCount() >= 2)
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        for ((index, instruction) in code.withIndex()) {
            if (instruction !is OffsetInstruction) continue
            assertTrue("$what: the branch at $index lands in the hook", addresses[index] + instruction.codeOffset !in addresses.take(at + 3))
        }
    }

    private fun assertStubs(context: BytecodePatchContext, base: String, itemView: String) {
        val methods = context.mutableClassDefBy(BUTTON).methods
        val view = methods.single { it.name == "itemView" }.code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), view.take(3).map { it.opcode })
        assertEquals(base, view[0].referenceText())
        assertEquals(itemView, view[1].referenceText())
        val story = methods.single { it.name == "media" }.code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), story.take(3).map { it.opcode })
        assertEquals(REEL_ITEM, story[0].referenceText())
        assertEquals(media, story[1].referenceText())
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
    ) + ImmutableClassDef.of(ExtensionDex.classDef(BUTTON))

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
        const val ITEM_AT = 2
        const val HOLDER_AT = 4
    }
}

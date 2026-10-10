/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.share.REPOSTS_FEED_RESTORE
import app.morphe.patches.instagram.share.REPOSTS_FEED_UFI
import app.morphe.patches.instagram.share.REPOSTS_UFI_COUNT_ID
import app.morphe.patches.instagram.share.REPOSTS_UFI_ICON_ID
import app.morphe.patches.instagram.share.findFeedUfiSite
import app.morphe.patches.instagram.share.hideFeedUfi
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hook behind Download button on feed posts (#97): the binder Hide the Repost button hooks,
 * found the same way, handed the Save button, the post, the carousel's feed state and the activity.
 * The builds themselves are covered by DownloadVideoHookTest, which has their menu classes.
 */
class FeedDownloadButtonHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_BUTTON.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FEED_BUTTON is not in the extension: $declared", FEED_BUTTON.substringAfter("->") in declared)
    }

    @Test
    fun theBinderIsFoundByItsHolderStateFeedStateAndActivity() {
        val site = PatchContexts.of(classes()).findFeedButtonSite(PAGE)
        assertEquals(BINDER, site.type)
        assertEquals("bind", site.name)
        assertEquals(listOf(ITEM, STATE, HOLDER), site.parameters)
        assertEquals("the holder", 2, site.holder)
        assertEquals("the row state", 1, site.state)
        assertEquals("the feed state", 0, site.item)
        assertEquals("$HOLDER->A0I:$BOUNCY", site.save.toString())
        assertEquals("$STATE->post:$MEDIA", site.media.toString())
        assertEquals("$BINDER->activity:$ACTIVITY", site.activity.toString())
    }

    @Test
    fun theHookIsFirstInTheBinderAndHandsOverTheFourThings() {
        val context = PatchContexts.of(classes())
        context.addFeedDownloadButton(context.findFeedButtonSite(PAGE))
        val code = context.mutableClassDefBy(BINDER).methods.single { it.name == "bind" }.implementation!!.instructions.toList()
        assertHooked("stand-in", code)
    }

    /** Hide the Repost button's hook goes in either side of this one and each still lands once. */
    @Test
    fun bothOrdersWithHideTheRepostButtonKeepEachHook() {
        for (mineFirst in listOf(true, false)) {
            val context = PatchContexts.of(classes())
            val site = context.findFeedButtonSite(PAGE)
            if (mineFirst) context.addFeedDownloadButton(site)
            context.hideFeedUfi(context.findFeedUfiSite())
            if (!mineFirst) context.addFeedDownloadButton(context.findFeedButtonSite(PAGE))
            val code = context.mutableClassDefBy(BINDER).methods.single { it.name == "bind" }.implementation!!.instructions.toList()
            val order = if (mineFirst) "mine first" else "Hide the Repost button first"
            assertEquals("$order: one button hook", 1, code.count { it.names(FEED_BUTTON) })
            assertEquals("$order: one repost hide", 1, code.count { it.names(REPOSTS_FEED_UFI) })
            assertEquals("$order: one repost restore", 1, code.count { it.names(REPOSTS_FEED_RESTORE) })
            val at = code.indexOfFirst { it.names(FEED_BUTTON) }
            assertHooked(order, code.subList(at - 7, code.size))
        }
    }

    @Test
    fun aBuildWithoutTheBinderHolderStateOrActivityFailsThePatch() {
        for ((case, classes) in mapOf(
            "no binder" to classes().filter { it.type != BINDER },
            "no Save button in the holder" to classes(saveId = 0x7f0b0001),
            "two Save buttons in the holder" to classes(twoSaves = true),
            "no post in the row state" to classes(post = false),
            "a feed state that isn't a parameter" to classes(itemParameter = false),
            "two activities" to classes(activities = 2),
            "no activity" to classes(activities = 0),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).findFeedButtonSite(PAGE) }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Download any video: "))
        }
    }

    @Test
    fun aBinderWithTooFewLocalsFailsBeforeAnyChange() {
        val context = PatchContexts.of(classes(locals = 3))
        val site = context.findFeedButtonSite(PAGE)
        assertThrows(PatchException::class.java) { context.addFeedDownloadButton(site) }
    }

    /**
     * The binder opens with the four reads and the call: the holder's Save button, the post from
     * the row state, the feed state, then the activity from the binder itself, all through 16-bit
     * moves of the parameter registers. Nothing else is called.
     */
    private fun assertHooked(what: String, code: List<Instruction>) {
        val locals = 5
        val self = locals
        assertEquals("$what: the holder", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$what: from the holder parameter", self + 3, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the Save button", "$HOLDER->A0I:$BOUNCY", code[1].reference())
        assertEquals("$what: the row state", self + 2, (code[2] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the post", "$STATE->post:$MEDIA", code[3].reference())
        assertEquals("$what: the feed state", self + 1, (code[4] as TwoRegisterInstruction).registerB)
        assertEquals("$what: this", self, (code[5] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the activity", "$BINDER->activity:$ACTIVITY", code[6].reference())
        val call = code[7] as Instruction35c
        assertEquals("$what: the call", FEED_BUTTON, call.reference())
        assertEquals("$what: its four arguments", listOf(4, 0, 1, 2, 3), listOf(call.registerCount, call.registerC, call.registerD, call.registerE, call.registerF))
    }

    private fun Instruction.names(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun Instruction.reference() = (this as ReferenceInstruction).reference.toString()

    private companion object {
        const val BINDER = "Lfixture/UfiRow;"
        const val HOLDER = "Lfixture/UfiHolder;"
        const val STATE = "Lfixture/RowState;"
        const val ITEM = "Lfixture/ItemState;"
        const val ACTIVITY = "Landroid/app/Activity;"
        const val BOUNCY = "Lcom/instagram/ui/widget/bouncyufibutton/IgBouncyUfiButtonImageView;"
        const val TEXT = "Lcom/instagram/common/ui/base/IgTextView;"
        val PAGE = PageIndex(
            ImmutableFieldReference(ITEM, "page", "I"),
            ImmutableFieldReference(STATE, "item", ITEM),
            ImmutableFieldReference("Lfixture/Menu;", "item", ITEM),
        )
        val REQUIRE_VIEW = ImmutableMethodReference("Landroid/view/View;", "requireViewById", listOf("I"), "Landroid/view/View;")

        fun field(owner: String, name: String, type: String) =
            ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null)

        /** `const v0, id` then the view lookup on [view], checked to [type] and stored to [owner]'s [name] through [target]. */
        fun lookup(id: Int, view: Int, type: String, target: Int, owner: String, name: String) = listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, id),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, view, 0, 0, 0, 0, REQUIRE_VIEW),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(type)),
            ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, target, ImmutableFieldReference(owner, name, type)),
        )

        fun classOf(type: String, fields: List<ImmutableField>, methods: List<ImmutableMethod>) = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, methods,
        )

        /**
         * The binder, its holder, its row state and its feed state, as 450 has them. The binder keeps
         * [locals] locals, then `this` and (feed state, row state, holder) as the parameters.
         */
        fun classes(
            saveId: Int = 0x7f0b370e,
            twoSaves: Boolean = false,
            post: Boolean = true,
            itemParameter: Boolean = true,
            activities: Int = 1,
            locals: Int = 5,
        ): List<ClassDef> {
            val constructor = ImmutableMethod(
                HOLDER, "<init>", listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(
                    4,
                    lookup(saveId, 3, BOUNCY, 2, HOLDER, "A0I") +
                        (if (twoSaves) lookup(saveId, 3, BOUNCY, 2, HOLDER, "A0J") else emptyList()) +
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    null, null,
                ),
            )
            val holder = classOf(
                HOLDER,
                listOf(field(HOLDER, "A08", BOUNCY), field(HOLDER, "A05", TEXT), field(HOLDER, "A0H", BOUNCY),
                    field(HOLDER, "A0I", BOUNCY), field(HOLDER, "A0J", BOUNCY)),
                listOf(constructor),
            )
            val rowState = classOf(STATE, if (post) listOf(field(STATE, "post", MEDIA), field(STATE, "item", ITEM)) else listOf(field(STATE, "item", ITEM)), emptyList())
            val item = classOf(ITEM, listOf(field(ITEM, "page", "I")), emptyList())
            val parameters = if (itemParameter) listOf(ITEM, STATE, HOLDER) else listOf("Lfixture/Other;", STATE, HOLDER)
            val bind = ImmutableMethod(
                BINDER, "bind", parameters.map { ImmutableMethodParameter(it, null, null) }, "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(
                    locals + 1 + 3,
                    listOf(ImmutableInstruction12x(Opcode.MOVE_OBJECT, 4, 8)) +
                        lookup(REPOSTS_UFI_ICON_ID, 1, BOUNCY, 4, HOLDER, "A08") +
                        lookup(REPOSTS_UFI_COUNT_ID, 1, TEXT, 4, HOLDER, "A05") +
                        ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 4, ImmutableFieldReference(HOLDER, "A0H", BOUNCY)) +
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    null, null,
                ),
            )
            val binder = classOf(BINDER, List(activities) { field(BINDER, if (it == 0) "activity" else "other$it", ACTIVITY) }, listOf(bind))
            return listOf(binder, holder, rowState, item)
        }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.follow

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Follow on stories: the story item binder's toolbar bind hands its view holder to
 * StoryFollowButton.bind first thing, or right after View stories anonymously's hook when that's
 * there, whichever patch goes first, and the item view stub reads the view holder base's field.
 */
class HideStoryFollowHookTest {
    /** The hook the patch writes and the stub it fills are in the extension the bundle ships, static. */
    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val methods = ExtensionDex.classDef(STORY_FOLLOW_BUTTON).methods
        fun key(method: Method) = "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
        assertTrue(methods.any {
            key(it) == BIND_FOLLOW.substringAfter("->") && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        })
        assertTrue(methods.any { key(it) == "itemView($OBJECT)$VIEW" && AccessFlags.STATIC.isSet(it.accessFlags) })
    }

    /** Alone, the hook goes first in the binder: the view holder moved into v0, then the call. */
    @Test
    fun theBinderHandsTheButtonItsViewHolderFirst() {
        val classes = standIns()
        val context = PatchContexts.of(classes)

        context.hideStoryFollow()

        val binder = binderOf(context)
        assertFollowHooked("stand-in", binder, at = 0, holder = HOLDER_AT)
        assertEquals("stand-in: Instagram's code follows the hook", originalBinder(classes).code().map { it.shape() }, binder.code().drop(2).map { it.shape() })
        assertStubFilled(context, VIEW_HOLDER, "$VIEW_HOLDER->itemView:$VIEW")
        assertEquals("stand-in: one call of the hook in all", 1, calls(context))
    }

    /**
     * With View stories anonymously in the same build, the binder starts with its hook, then this
     * one, then Instagram's code, whichever of the two patches goes first, so its hook stays first
     * where its rule wants it.
     */
    @Test
    fun theHookSitsAfterTheSeenButtonsHookInEitherOrder() {
        val classes = standIns()
        val followFirst = PatchContexts.of(classes).apply { hideStoryFollow(); holdBackStoryViews() }
        val seenFirst = PatchContexts.of(classes).apply { holdBackStoryViews(); hideStoryFollow() }

        for ((label, context) in listOf("follow first" to followFirst, "seen first" to seenFirst)) {
            val binder = binderOf(context)
            assertSeenButtonFirst(label, binder)
            assertFollowHooked(label, binder, at = 4, holder = HOLDER_AT)
            assertEquals("$label: Instagram's code follows both hooks", originalBinder(classes).code().map { it.shape() }, binder.code().drop(6).map { it.shape() })
            assertStubFilled(context, VIEW_HOLDER, "$VIEW_HOLDER->itemView:$VIEW")
        }
        assertEquals("the order changed the binder", binderOf(followFirst).code().map { it.shape() }, binderOf(seenFirst).code().map { it.shape() })
    }

    @Test
    fun aSecondRunRefusesAndLeavesOneHook() {
        val context = PatchContexts.of(standIns())
        context.hideStoryFollow()

        val refusal = assertThrows(PatchException::class.java) { context.hideStoryFollow() }

        assertTrue(refusal.message, refusal.message!!.startsWith("$PATCH: ") && "already calls $BIND_FOLLOW" in refusal.message!!)
        assertEquals(1, calls(context))
    }

    @Test
    fun aBuildWithoutTheBinderFails() = assertRefused(standIns(binder = false), "story item binder's toolbar bind in this Instagram build, found none")

    @Test
    fun aDuplicatedBinderFails() {
        val classes = standIns()
        assertRefused(classes + copyOf(classes.single { it.type == BINDER }, "Lfixture/SecondBinder;"), "story item binder's toolbar bind")
    }

    @Test
    fun aBinderOfAnotherShapeFails() {
        assertRefused(standIns(binderParameters = listOf(DELEGATE, USER_SESSION, REEL_ITEM, VIEWER, VIEWER, "Z")), "to take one view holder, found 0")
        assertRefused(standIns(binderParameters = listOf(DELEGATE, USER_SESSION, REEL_ITEM, HOLDER, HOLDER, "Z")), "to take one view holder, found 2")
    }

    /** Code put in front of an instruction a branch lands on is skipped by the branch. */
    @Test
    fun aBranchToTheBindersStartFails() = assertRefused(standIns(binderLoops = true), "lands on its first instruction")

    @Test
    fun aBinderWithoutALocalFails() = assertRefused(standIns(binderLocals = 0), "has 0 local register(s), needs 1")

    @Test
    fun anItemViewTheExtensionCantReadFails() {
        assertRefused(standIns(itemViewPublic = false), "isn't a public field the extension can read")
        val missing = standIns().map { if (it.type == VIEW_HOLDER) rebuilt(it, fields = emptyList()) else it }
        assertRefused(missing, "isn't a public field the extension can read")
    }

    @Test
    fun aViewHolderBaseTheExtensionCantReachFails() {
        val hidden = standIns().map { if (it.type == VIEW_HOLDER) rebuilt(it, flags = it.accessFlags and AccessFlags.PUBLIC.value.inv()) else it }
        assertRefused(hidden, "$VIEW_HOLDER isn't public, so the extension can't reach it")
    }

    @Test
    fun aBuildWithoutTheExtensionClassFails() =
        assertRefused(standIns().filter { it.type != STORY_FOLLOW_BUTTON }, "$STORY_FOLLOW_BUTTON isn't in the extension")

    /**
     * On each declared build the toolbar bind is found and hooked, alone and beside View stories
     * anonymously in either order, the stub reads the item view, and the same classes with the
     * binder copied are refused untouched.
     */
    @Test
    fun eachDeclaredBuildHidesFollow() {
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
    fun eachOtherBuildHidesFollowToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val classes = StorySeenHookTest().fixtureClasses(bundle) + ImmutableClassDef.of(ExtensionDex.classDef(STORY_FOLLOW_BUTTON))
        val found = PatchContexts.of(classes).findStoryFollow()
        assertEquals("$label: the item view", VIEW, found.itemView.type)
        val original = classes.single { it.type == found.binder }.methods.single {
            it.name == found.binderName && it.parameterTypes.map(Any::toString) == found.binderParameters
        }

        val alone = PatchContexts.of(classes).apply { hideStoryFollow() }
        val followFirst = PatchContexts.of(classes).apply { hideStoryFollow(); holdBackStoryViews() }
        val seenFirst = PatchContexts.of(classes).apply { holdBackStoryViews(); hideStoryFollow() }
        for ((what, context, at) in listOf(Triple("alone", alone, 0), Triple("follow first", followFirst, 4), Triple("seen first", seenFirst, 4))) {
            val binder = context.mutableClassDefBy(found.binder).methods.single {
                it.name == found.binderName && it.parameterTypes.map(Any::toString) == found.binderParameters
            }
            if (at == 4) assertSeenButtonFirst("$label, $what", binder)
            assertFollowHooked("$label, $what", binder, at, found.holder)
            assertEquals("$label, $what: Instagram's code follows the hooks", original.code().map { it.shape() }, binder.code().drop(at + 2).map { it.shape() })
            assertStubFilled(context, found.itemView.definingClass, found.itemView.toString())
            assertEquals("$label, $what: one call of the hook in all", 1, calls(context))
        }

        val binderClass = classes.single { it.type == found.binder }
        assertRefused(classes + copyOf(binderClass, "Lfixture/SecondBinder;"), "story item binder's toolbar bind")
    }

    // ---- checks ---------------------------------------------------------------------------------

    /** The patch refuses [classes] saying [why], and nothing calls the hook or has the stub filled. */
    private fun assertRefused(classes: List<ClassDef>, why: String) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.hideStoryFollow() }
        assertTrue(refusal.message, refusal.message!!.startsWith("$PATCH: ") && why in refusal.message!!)
        assertEquals("a refused build calls the hook", 0, calls(context))
        val stock = ExtensionDex.classDef(STORY_FOLLOW_BUTTON).methods.single { it.name == "itemView" }.code().size
        context.classDefByOrNull(STORY_FOLLOW_BUTTON)?.methods?.single { it.name == "itemView" }?.let {
            assertEquals("a refused build filled the stub", stock, it.code().size)
        }
    }

    /** At [at]: [holder]'s register moved into v0, then [BIND_FOLLOW] called on v0, once in the binder. */
    private fun assertFollowHooked(what: String, binder: Method, at: Int, holder: Int) {
        val code = binder.code()
        assertEquals("$what: the hook's opcodes", listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC), code.drop(at).take(2).map { it.opcode })
        val move = code[at] as TwoRegisterInstruction
        assertEquals("$what: v0 is borrowed", 0, move.registerA)
        assertEquals("$what: v0 holds the view holder", binder.parameterRegisterNumber(holder), move.registerB)
        val call = code[at + 1] as FiveRegisterInstruction
        assertEquals("$what: the hook called", BIND_FOLLOW, code[at + 1].referenceText())
        assertEquals("$what: the hook takes v0", 1 to 0, call.registerCount to call.registerC)
        assertTrue("$what: a local to borrow", binder.localRegisterCount() >= 1)
        assertEquals("$what: calls of the hook", 1, code.count { it.referenceText() == BIND_FOLLOW })
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        for ((index, instruction) in code.withIndex()) {
            if (instruction !is OffsetInstruction) continue
            val target = addresses[index] + instruction.codeOffset
            assertTrue("$what: the branch at $index lands in the hooks", target !in addresses.take(at + 2))
        }
    }

    /** The binder starts with View stories anonymously's hook: three moves, then its call. */
    private fun assertSeenButtonFirst(what: String, binder: Method) {
        val code = binder.code()
        assertEquals(
            "$what: the seen button's hook comes first",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC),
            code.take(4).map { it.opcode },
        )
        assertEquals("$what: the seen button's call", BIND_BUTTON, code[3].referenceText())
        assertEquals("$what: calls of the seen button's hook", 1, code.count { it.referenceText() == BIND_BUTTON })
    }

    private fun assertStubFilled(context: BytecodePatchContext, base: String, itemView: String) {
        val code = context.mutableClassDefBy(STORY_FOLLOW_BUTTON).methods.single { it.name == "itemView" }.code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), code.take(3).map { it.opcode })
        assertEquals(base, code[0].referenceText())
        assertEquals(itemView, code[1].referenceText())
    }

    private fun calls(context: BytecodePatchContext): Int {
        var count = 0
        context.classDefForEach { classDef ->
            classDef.methods.forEach { method -> count += method.code().count { it.referenceText() == BIND_FOLLOW } }
        }
        return count
    }

    // ---- stand-ins ------------------------------------------------------------------------------

    /** View stories anonymously's stand-ins, shaped like Instagram 449's, and the extension's class. */
    private fun standIns(
        binder: Boolean = true,
        binderParameters: List<String> = listOf(DELEGATE, USER_SESSION, REEL_ITEM, VIEWER, HOLDER, "Z"),
        binderLoops: Boolean = false,
        binderLocals: Int = 11,
        itemViewPublic: Boolean = true,
    ): List<ClassDef> = StorySeenHookTest().standIns(
        binder = binder, binderParameters = binderParameters, binderLoops = binderLoops, binderLocals = binderLocals,
        itemViewPublic = itemViewPublic,
    ) + ImmutableClassDef.of(ExtensionDex.classDef(STORY_FOLLOW_BUTTON))

    private fun binderOf(context: BytecodePatchContext): Method = context.mutableClassDefBy(BINDER).methods.single { it.name == "A06" }

    private fun originalBinder(classes: List<ClassDef>): Method = classes.single { it.type == BINDER }.methods.single { it.name == "A06" }

    private fun rebuilt(classDef: ClassDef, flags: Int = classDef.accessFlags, fields: List<ImmutableField>? = null): ClassDef =
        ImmutableClassDef(
            classDef.type, flags, classDef.superclass, classDef.interfaces, null, null,
            fields ?: classDef.fields.map { ImmutableField.of(it) }, classDef.methods,
        )

    /** [classDef] under another name, its members moved with it. */
    private fun copyOf(classDef: ClassDef, type: String): ClassDef = ImmutableClassDef(
        type, classDef.accessFlags, classDef.superclass, classDef.interfaces, null, null,
        classDef.fields.map { ImmutableField(type, it.name, it.type, it.accessFlags, null, null, null) },
        classDef.methods.map {
            ImmutableMethod(type, it.name, it.parameters, it.returnType, it.accessFlags, null, null, it.implementation)
        },
    )

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** An instruction's opcode, reference and registers, which prepending code doesn't change. */
    private fun Instruction.shape(): String {
        val registers = when (this) {
            is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
            is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
            is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
            is TwoRegisterInstruction -> listOf(registerA, registerB)
            is OneRegisterInstruction -> listOf(registerA)
            else -> emptyList()
        }
        return "$opcode ${referenceText()} $registers"
    }

    private companion object {
        const val PATCH = "Hide Follow on stories"
        const val BINDER = "Lfixture/ReelViewerItemBinder;"
        const val VIEW_HOLDER = "Lfixture/ViewHolder;"
        const val HOLDER = "Lfixture/ReelViewerHolder;"
        const val DELEGATE = "Lfixture/Delegate;"
        const val VIEWER = "Lfixture/Viewer;"
        const val OBJECT = "Ljava/lang/Object;"
        const val VIEW = "Landroid/view/View;"
        const val HOLDER_AT = 4
    }
}

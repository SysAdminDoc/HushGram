/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.friendship

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The patch's two parts: the profile label, which the patch needs, and the Following list mark, a
 * second switch a build can lack. A follow list that moved leaves the label in and the mark out; a
 * profile that moved stops the whole patch before anything changes.
 */
class FriendshipStatusPartsTest {
    @Test
    fun bothPartsGoInWhereBothAreFound() {
        val context = PatchContexts.of(classes(FollowingListHookTest.standIns()))
        val warnings = PatchLogCapture.warnings { friendshipStatusPatch.execute(context) }

        assertEquals(emptyList<String>(), warnings)
        assertEquals("the profile label's hooks", 2, calls(context, BESIDE_PRONOUNS) + calls(context, IN_PLACE_OF_PRONOUNS))
        assertEquals("the list's hook", 1, calls(context, FOLLOWING_ROW))
        assertEquals(1, answer(context, "friendshipStatus"))
        assertEquals(1, answer(context, FOLLOWING_LIST_STATUS))
    }

    @Test
    fun aFollowListThatMovedLeavesTheLabelInAndTheMarkOut() {
        val context = PatchContexts.of(classes(FollowingListHookTest.standIns(rowState = "null cannot be cast to non-null type Something")))
        val warnings = PatchLogCapture.warnings { friendshipStatusPatch.execute(context) }

        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().startsWith("Show if a profile follows you: expected one method loading"))
        assertTrue(warnings.single(), warnings.single().endsWith("The profile label goes in without Mark who doesn't follow you back."))
        assertEquals("the profile label's hooks", 2, calls(context, BESIDE_PRONOUNS) + calls(context, IN_PLACE_OF_PRONOUNS))
        assertEquals("the list's hook", 0, calls(context, FOLLOWING_ROW))
        assertFollowingListStubsStock(context)
        assertEquals(1, answer(context, "friendshipStatus"))
        assertEquals(0, answer(context, FOLLOWING_LIST_STATUS))
    }

    /**
     * A build whose options sheet reads the screen's answer some other way keeps the label on the
     * status kept on the account (as before #40) and says so, with the screen's stubs as shipped.
     */
    @Test
    fun aScreenAnswerThatMovedLeavesTheLabelOnTheKeptStatus() {
        val context = PatchContexts.of(classes(FollowingListHookTest.standIns(), FriendshipStatusHookTest.standIns(sheets = 0)))
        val warnings = PatchLogCapture.warnings { friendshipStatusPatch.execute(context) }

        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().startsWith("Show if a profile follows you: expected one place reading"))
        assertTrue(warnings.single(), warnings.single().endsWith("The label goes by the follow status Instagram keeps on the account."))
        assertEquals("the profile label's hooks", 2, calls(context, BESIDE_PRONOUNS) + calls(context, IN_PLACE_OF_PRONOUNS))
        val stock = ExtensionDex.classDef(FRIENDSHIP_STATUS).methods.associate { it.key() to it.implementation?.instructions?.count() }
        for (stub in listOf("screenFriendship", "statusFlag")) {
            val method = context.classDefByOrNull(FRIENDSHIP_STATUS)!!.methods.single { it.name == stub }
            assertEquals("$stub was filled", stock[method.key()], method.implementation?.instructions?.count())
        }
        assertEquals(1, answer(context, "friendshipStatus"))
        assertEquals(1, answer(context, FOLLOWING_LIST_STATUS))
    }

    @Test
    fun aProfileThatMovedStopsThePatchBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(FollowingListHookTest.standIns(), FriendshipStatusHookTest.standIns(traceName = "bindBio")))
        assertThrows(PatchException::class.java) { friendshipStatusPatch.execute(context) }

        assertEquals(0, calls(context, BESIDE_PRONOUNS) + calls(context, IN_PLACE_OF_PRONOUNS) + calls(context, FOLLOWING_ROW))
        assertFollowingListStubsStock(context)
        assertEquals(0, answer(context, "friendshipStatus"))
        assertEquals(0, answer(context, FOLLOWING_LIST_STATUS))
    }

    /** The profile's and the list's stand-ins and the status class, with a class both declare merged into one. */
    private fun classes(list: List<ClassDef>, profile: List<ClassDef> = FriendshipStatusHookTest.standIns()): List<ClassDef> =
        (profile + list + ExtensionDex.classDef(SETTINGS_STATUS)).groupBy { it.type }.map { (type, defs) ->
            val first = defs.first()
            if (defs.size == 1) first
            else ImmutableClassDef(
                type, first.accessFlags, first.superclass, first.interfaces, null, null,
                defs.flatMap { it.fields }.distinctBy { it.name },
                defs.flatMap { it.methods }.distinctBy { it.key() },
            )
        }

    private fun calls(context: BytecodePatchContext, hook: String): Int {
        var found = 0
        context.classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                found += method.implementation?.instructions?.count { (it as? ReferenceInstruction)?.reference?.toString() == hook } ?: 0
            }
        }
        return found
    }

    /** What a SettingsStatus method answers: 1 once the patch switches it on, 0 as shipped. */
    private fun answer(context: BytecodePatchContext, status: String): Int =
        (context.classDefByOrNull(SETTINGS_STATUS)!!.methods.single { it.name == status }
            .implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral

    private fun assertFollowingListStubsStock(context: BytecodePatchContext) {
        val stock = ExtensionDex.classDef(FOLLOWING_LIST).methods.associate { it.key() to it.implementation?.instructions?.count() }
        context.classDefByOrNull(FOLLOWING_LIST)!!.methods.forEach {
            assertEquals("${it.key()} was filled", stock[it.key()], it.implementation?.instructions?.count())
        }
    }

    private fun Method.key() = "$name(${parameterTypes.joinToString("")})$returnType"
}

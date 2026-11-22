/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.otherBuilds
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Posts saved into a locked collection leave Saved's All and Posts while the lock is shut (#678):
 * the reader of Saved collections hands each collection it made to the extension, so its id is
 * known by name, and the grid of All and Posts asks about each post between getting it and making
 * its row, skipping to the next post when the answer is yes. Three bridges read a post's collections
 * and a collection's id and name. A build where any of it moved refuses before anything changes,
 * and the rest of the lock goes on.
 */
class LockedCollectionGridHookTest {
    @Test
    fun theHooksAndBridgesAreInTheExtension() {
        val declared = ExtensionDex.classDef(LOCKED_ITEMS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(READ_COLLECTION, LEAVE_OUT_POST, LEAVE_OUT_REELS, LEAVE_OUT_FOUND)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
        for (bridge in listOf("savedIn(Ljava/lang/Object;)Ljava/util/List;", "idOf(Ljava/lang/Object;)Ljava/lang/String;",
            "nameOf(Ljava/lang/Object;)Ljava/lang/String;", "mediaOf(Ljava/lang/Object;)Ljava/lang/Object;",
            "collectionOf(Ljava/lang/Object;)Ljava/lang/Object;")) {
            assertTrue("$bridge is not in the extension: $declared", bridge in declared)
        }
    }

    /** A build without Saved's reader refuses naming the lock, and the rest of the lock goes on. */
    @Test
    fun aBuildWithoutItRefusesNamingTheLockAndTheRestGoesOn() {
        val empty = PatchContexts.of(listOf(ExtensionDex.classDef(LOCKED_ITEMS)))
        val failure = assertThrows(PatchException::class.java) { empty.findLockedGrid() }
        assertTrue(failure.message, failure.message!!.startsWith("$LOCK_PATCH: "))
        assertNull(PatchContexts.of(listOf(ExtensionDex.classDef(LOCKED_ITEMS))).findLockedGridOrSkip())
    }

    @Test fun declaredBuildsLeaveLockedPostsOutOfSaved() = fixtures { builds(it) }

    @Test fun otherBuildsLeaveLockedPostsOutOfSavedToo() = otherBuilds { builds(it) }

    private fun builds(bundle: File) {
        val label = bundle.parentFile.name + "/" + bundle.name
        // Saved's search gets what it found in a class of its own, which writes the screen's field.
        val searchScreens = FixtureDex.classesLoading(bundle, SAVED_SEARCH_MODULE).map { it.type }.toSet()
        val classes = FixtureDex.classesLoadingAny(
            bundle, setOf(COLLECTION_NAME_KEY, SAVED_COLLECTION_IDS_KEY, SAVED_POSTS_FEED, SAVED_REELS_FETCHER, SAVED_SEARCH_MODULE),
        ) { owner ->
            owner.type == SAVED_GRID_MEDIA || owner.methods.any { method ->
                method.implementation?.instructions?.any {
                    it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).definingClass in searchScreens
                } == true
            }
        }
        val context = PatchContexts.of(classes + ExtensionDex.classDef(LOCKED_ITEMS))
        val targets = try {
            context.findLockedGrid()
        } catch (failure: PatchException) {
            throw AssertionError("$label: ${failure.message}", failure)
        }
        val reader = targets.reader
        assertNotEquals("$label: a collection's id and name", reader.id, reader.name)
        assertEquals("$label: Saved's grid of All and Posts", 1, targets.rows.size)
        val rows = targets.rows.single()
        // Alignment nops before a switch payload move when code goes in, so neither side counts them.
        val readerBefore = reader.method.instructions().filter { it.opcode != Opcode.NOP }.map(::text)
        val rowsBefore = rows.method.instructions().filter { it.opcode != Opcode.NOP }.map(::text)
        val reels = targets.reels
            ?: throw AssertionError("$label: Saved's Reels: ${runCatching { context.findSavedReelsBatch() }.exceptionOrNull()?.message}")
        val cast = reels.method.instructions()[reels.at - 1]
        assertEquals("$label: the hook goes just after the Reels screen is cast", Opcode.CHECK_CAST, cast.opcode)
        assertEquals("$label: into the screen register", reels.screen, (cast as OneRegisterInstruction).registerA)
        assertEquals("$label: the screen's collection", reels.collection.definingClass, (cast as ReferenceInstruction).reference.toString())
        assertEquals("$label: a reel's post", SAVED_GRID_MEDIA, reels.media.type)
        val reelsBefore = reels.method.instructions().filter { it.opcode != Opcode.NOP }.map(::text)
        val search = targets.search
            ?: throw AssertionError("$label: Saved's search: ${runCatching { context.findSavedSearchResults() }.exceptionOrNull()?.message}")
        val stored = search.method.instructions()[search.at]
        assertEquals("$label: the search screen's list", Opcode.IPUT_OBJECT, stored.opcode)
        val searchBefore = search.method.instructions().filter { it.opcode != Opcode.NOP }.map(::text)
        // Whatever jumped to the write jumps to the hook after.
        val searchCode = search.method.instructions()
        val jumpsToWrite = searchCode.indices.filter { it in jumping(searchCode) && targetOf(searchCode, it) == search.at }

        hookLockedGrid(targets)

        // Each collection the reader made goes to the extension just before it's returned.
        val read = reader.method.instructions()
        val returns = read.indices.filter {
            read[it].opcode == Opcode.RETURN_OBJECT && (read[it] as OneRegisterInstruction).registerA == reader.collection
        }
        assertEquals("$label: the reader's returns", reader.returns.size, returns.size)
        for (at in returns) {
            val hand = read[at - 1]
            assertEquals("$label: before return $at", READ_COLLECTION, (hand as ReferenceInstruction).reference.toString())
            assertEquals("$label: the collection", reader.collection, (hand as RegisterRangeInstruction).startRegister)
        }
        assertEquals("$label: the reader's own code stays", readerBefore,
            read.filter { it.opcode != Opcode.NOP && (it as? ReferenceInstruction)?.reference?.toString() != READ_COLLECTION }.map(::text))

        // The grid asks about each post before making its row, and goes on to the next post on a yes.
        val code = rows.method.instructions()
        val at = rows.at
        val field = (code[at] as ReferenceInstruction).reference as FieldReference
        assertEquals("$label: the screen's collection", Opcode.IGET_OBJECT, code[at].opcode)
        assertEquals("$label: of type", SAVED_COLLECTION_TYPE, field.type)
        assertEquals("$label: read from the screen", rows.screen, (code[at] as TwoRegisterInstruction).registerB)
        assertEquals("$label: into the borrowed register", rows.scratch, (code[at] as TwoRegisterInstruction).registerA)
        val ask = code[at + 1] as FiveRegisterInstruction
        assertEquals("$label: the ask", LEAVE_OUT_POST, (ask as ReferenceInstruction).reference.toString())
        assertEquals("$label: with the post and the collection", listOf(rows.media, rows.scratch), listOf(ask.registerC, ask.registerD))
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, code[at + 2].opcode)
        assertEquals("$label: on a yes", Opcode.IF_NEZ, code[at + 3].opcode)
        val next = targetOf(code, at + 3)
        val hasNext = (code[next] as ReferenceInstruction).reference as MethodReference
        assertEquals("$label: the next post", "Ljava/util/Iterator;->hasNext", "${hasNext.definingClass}->${hasNext.name}")
        val made = (code[at + 4] as ReferenceInstruction).reference as MethodReference
        assertEquals("$label: then the row, as before", listOf(SAVED_GRID_MEDIA), made.parameterTypes.map(Any::toString))
        assertEquals("$label: the grid's own code stays", rowsBefore,
            code.filterIndexed { index, each -> index !in at until at + 4 && each.opcode != Opcode.NOP }.map(::text))
        assertEquals("$label: nothing reads the borrowed register after", emptyList<Int>(),
            rows.method.readsAfter(at + 3, rows.scratch))

        // Each batch of reels goes to the extension with the screen, and what comes back goes on in its place.
        val batch = reels.method.instructions()
        val leave = batch[reels.at] as FiveRegisterInstruction
        assertEquals("$label: the Reels ask", LEAVE_OUT_REELS, (leave as ReferenceInstruction).reference.toString())
        assertEquals("$label: with the reels and the screen", listOf(2, reels.reels, reels.screen),
            listOf(leave.registerCount, leave.registerC, leave.registerD))
        assertEquals("$label: the reels to show", Opcode.MOVE_RESULT_OBJECT, batch[reels.at + 1].opcode)
        assertEquals("$label: in the same register", reels.reels, (batch[reels.at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$label: the Reels screen's own code stays", reelsBefore,
            batch.filterIndexed { index, each -> index !in reels.at until reels.at + 2 && each.opcode != Opcode.NOP }.map(::text))

        // What Saved's search found goes to the extension with the screen before the screen keeps it.
        val found = search.method.instructions()
        val searchAsk = found[search.at] as FiveRegisterInstruction
        assertEquals("$label: the search ask", LEAVE_OUT_FOUND, (searchAsk as ReferenceInstruction).reference.toString())
        assertEquals("$label: with the posts and the screen", listOf(2, search.posts, search.screen),
            listOf(searchAsk.registerCount, searchAsk.registerC, searchAsk.registerD))
        assertEquals("$label: the posts to show", Opcode.MOVE_RESULT_OBJECT, found[search.at + 1].opcode)
        assertEquals("$label: in the same register", search.posts, (found[search.at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$label: then the screen keeps them", Opcode.IPUT_OBJECT, found[search.at + 2].opcode)
        assertEquals("$label: the search's own code stays", searchBefore,
            found.filterIndexed { index, each -> index !in search.at until search.at + 2 && each.opcode != Opcode.NOP }.map(::text))
        for (jump in jumpsToWrite) {
            // A jump after the write moved down by the two instructions that went in.
            val now = if (jump < search.at) jump else jump + 2
            assertEquals("$label: the jump at $jump lands on the hook", search.at, targetOf(found, now))
        }

        // The bridges read Instagram's own.
        assertEquals("$label: a reel's post", reels.media.toString(),
            (targets.bridges.getValue("mediaOf").instructions()[1] as ReferenceInstruction).reference.toString())
        assertEquals("$label: the Reels screen's collection", reels.collection.toString(),
            (targets.bridges.getValue("collectionOf").instructions()[1] as ReferenceInstruction).reference.toString())
        val savedIn = targets.bridges.getValue("savedIn").instructions()
        assertEquals("$label: a post's collections", targets.savedIn.toString(), (savedIn[1] as ReferenceInstruction).reference.toString())
        assertEquals("$label: a collection's id", reader.id.toString(),
            (targets.bridges.getValue("idOf").instructions()[1] as ReferenceInstruction).reference.toString())
        assertEquals("$label: a collection's name", reader.name.toString(),
            (targets.bridges.getValue("nameOf").instructions()[1] as ReferenceInstruction).reference.toString())
    }

    /** The indexes of the branches and gotos in [code]. */
    private fun jumping(code: List<Instruction>): Set<Int> = code.indices.filter { code[it] is OffsetInstruction &&
        code[it].opcode != Opcode.PACKED_SWITCH && code[it].opcode != Opcode.SPARSE_SWITCH && code[it].opcode != Opcode.FILL_ARRAY_DATA }.toSet()

    /** The index the branch at [at] goes to. */
    private fun targetOf(code: List<Instruction>, at: Int): Int {
        var address = 0
        val addresses = code.map { each -> address.also { address += each.codeUnits } }
        return addresses.indexOf(addresses[at] + (code[at] as OffsetInstruction).codeOffset)
    }

    /** An instruction's kind and reference. A goto the code going in pushed out of reach widens, so all gotos read alike. */
    private fun text(instruction: Instruction): String {
        val reference = (instruction as? ReferenceInstruction)?.reference?.toString().orEmpty()
        val kind = when (instruction.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> instruction.opcode
        }
        return "$kind $reference"
    }
}

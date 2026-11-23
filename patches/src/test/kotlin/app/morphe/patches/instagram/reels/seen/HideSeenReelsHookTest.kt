/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.seen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.reels.watchhistory.HOLD_BACK
import app.morphe.patches.instagram.reels.watchhistory.RECORDS
import app.morphe.patches.instagram.reels.watchhistory.holdBackWatchedReels
import app.morphe.patches.instagram.reels.watchhistory.recordParameters
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide reels you've seen (#830): on every declared build the record of a reel reached tells the
 * extension of it first thing, with its media id, and Don't send reel watch history's early return
 * comes right after that call whichever patch goes in first, so a reel held back from Instagram is
 * still kept. The page and data source filters ride the same joins as the other Reels filters,
 * checked by their own tests and by the contracts on the patched builds.
 */
class HideSeenReelsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(SEEN_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(SEEN_PAGE_FILTER, SEEN_ADDED_FILTER, SEEN_WATCHED)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
        for (stub in listOf("media(Ljava/lang/Object;)Ljava/lang/Object;", "mediaId(Ljava/lang/Object;)Ljava/lang/String;")) {
            assertTrue("$stub is not in the extension: $declared", stub in declared)
        }
    }

    @Test
    fun eachDeclaredBuildKeepsAReelReachedEitherOrder() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /** The media id stub can be filled on every declared build: Media keeps a public getId(). */
    @Test
    fun eachDeclaredBuildHasTheMediaId() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = Fixtures.files { bundle -> bundle.extension == "apks" && versions.any { bundle.name.contains("-$it-") } }
        assertTrue("no fixture was checked", bundles.isNotEmpty())
        for (bundle in bundles) {
            val classes = FixtureDex.classes(bundle, setOf(MEDIA_TYPE)).values.toList() + ExtensionDex.classDef(SEEN_REELS)
            PatchContexts.of(classes).requireSeenStubs()
        }
    }

    @Test
    fun withoutTheExtensionThePatchFails() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).requireSeenStubs() }
        assertTrue(failure.message!!, failure.message!!.contains(SEEN_REELS))
    }

    private fun builds(bundle: File, label: String) {
        for (seenFirst in listOf(true, false)) {
            val holders = holders(bundle)
            val context = PatchContexts.of(holders)
            if (seenFirst) {
                tellWatched(context.findWatchedRecord())
                context.holdBackWatchedReels()
            } else {
                context.holdBackWatchedReels()
                tellWatched(context.findWatchedRecord())
            }
            val where = "$label, ${if (seenFirst) "seen first" else "hold back first"}"
            val batch = context.batchType()
            val stock = holders.single { it.type == batch }
            val patched = context.mutableClassDefBy(batch)
            val (reached, _) = RECORDS.first()
            val before = stock.methods.single { it.recordParameters(batch) == reached }
            val after = patched.methods.single { it.name == before.name && it.recordParameters(batch) == reached }
            val code = after.code()
            assertEquals("$where: the record grew by the call and the guard", before.code().size + 5, code.size)
            val tell = code[0] as RegisterRangeInstruction
            assertEquals("$where: tells first", SEEN_WATCHED, code[0].reference())
            val mediaId = after.implementation!!.registerCount - after.parameterTypes.size - (if (after.isStatic()) 0 else 1) + 1
            assertEquals("$where: with the media id", listOf(Opcode.INVOKE_STATIC_RANGE, mediaId, 1), listOf(tell.opcode, tell.startRegister, tell.registerCount))
            assertEquals("$where: then asks whether to hold back", HOLD_BACK, code[1].reference())
            val jump = code[3] as OffsetInstruction
            assertEquals("$where: past the return to the record's own start", code.subList(3, 5).sumOf { it.codeUnits }, jump.codeOffset)
            assertEquals("$where: the record's own code follows", before.code().map { it.opcode }, code.drop(5).map { it.opcode })
            val (progress, _) = RECORDS.last()
            val other = patched.methods.single { it.recordParameters(batch) == progress }
            assertTrue("$where: the progress record isn't told", other.code().none { it.reference() == SEEN_WATCHED })
        }
    }

    private fun holders(bundle: File): List<ClassDef> {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.stringSection.none { it == WRITE_SEEN_STATE }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.code().any { it.string() == WRITE_SEEN_STATE } }) {
                    holders += ImmutableClassDef.of(classDef)
                }
            }
        }
        return holders
    }

    private fun BytecodePatchContext.batchType(): String = findWatchedRecord().definingClass

    private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private companion object {
        const val WRITE_SEEN_STATE = "clips/write_seen_state/"
        const val MEDIA_TYPE = "Lcom/instagram/feed/media/Media;"
    }
}

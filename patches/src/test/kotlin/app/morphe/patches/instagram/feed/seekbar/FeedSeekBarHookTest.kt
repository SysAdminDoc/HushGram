/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.seekbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.tabswipe.stopTabSwipingPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeedSeekBarHookTest {
    /** The call the patch writes into the player is in the FeedSeekBar the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_SEEK_BAR).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FEED_PROGRESS is not in the extension: $declared", FEED_PROGRESS.substringAfter("->") in declared)
    }

    /** The bar holds the main tabs through Stop swiping between tabs' hooks while a finger is on it. */
    @Test
    fun theBarBringsTheTabSwipeHooks() {
        assertTrue(stopTabSwipingPatch in feedSeekBarPatch.dependencies)
    }

    /**
     * On 453.0.0.57.79's arm64 build 385914557 the patch finds the feed's player (05cP) and its
     * progress callback (G3c), the holder's view (0Fai's CWW) and the controller's seek (03Nk's A0T,
     * the one a SeekBar listener's onStopTrackingTouch makes), fills both stubs and hands the callback
     * to the extension first thing. Each build has its own names; 385914510's player is 05nU.
     */
    @Test
    fun eachDeclaredBuildFindsTheSeekAndHooksThePlayer() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val sites = builds(bundle, bundle.name)
                if (version == "453.0.0.57.79") {
                    assertEquals(
                        "${bundle.name}: the player, its progress callback, the holder's view and the seek",
                        listOf("LX/05cP;", "G3c", "LX/0Fai;->CWW()Landroid/view/View;", "LX/03Nk;->A0T(IZ)V"),
                        listOf(sites.player, sites.progress, sites.view.toString(), sites.seek.toString()),
                    )
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildFindsTheSeekToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /** A second run finds its own call and refuses instead of handing the callback over twice. */
    @Test
    fun aSecondRunRefuses() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val context = PatchContexts.of(classes(bundle))
        context.hookFeedSeek(context.findFeedSeek())
        val failure = assertThrows(PatchException::class.java) { context.findFeedSeek() }
        assertTrue(failure.message, failure.message!!.contains("already calls $FEED_SEEK_BAR"))
    }

    /** A build without the callback, without a SeekBar listener seeking the controller or without the stubs fails before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val all = classes(bundle)
        val player = all.single { classDef -> classDef.methods.any { it.isCallback() } }.type
        val cases = listOf(
            all.filter { it.type != player } to "holding $COVER_REPORT, found 0",
            all.filter { SEEK_LISTENER !in it.interfaces } to "where a SeekBar drag lets go, found 0",
            all.filter { it.type != FEED_SEEK_READER } to "the extension has no $FEED_SEEK_READER",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findFeedSeek() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            if (classes.any { it.type == player }) {
                val methods = context.mutableClassDefBy(player).methods
                assertTrue("$expected: something was written", methods.none { m -> m.code().any { it.method()?.definingClass == FEED_SEEK_BAR } })
            }
        }
    }

    private fun builds(bundle: File, label: String): FeedSeekSites {
        val classes = classes(bundle)
        val context = PatchContexts.of(classes)
        val sites = context.findFeedSeek()
        context.hookFeedSeek(sites)

        val stock = classes.single { it.type == sites.player }.callback(sites.progress).code()
        val hooked = context.mutableClassDefBy(sites.player).callback(sites.progress).code()
        assertEquals("$label: the callback grows by the one call", stock.size + 1, hooked.size)
        assertEquals("$label: the callback hands on first thing", Opcode.INVOKE_STATIC_RANGE, hooked.first().opcode)
        assertEquals("$label: the callback hands on to the extension", FEED_PROGRESS, hooked.first().method()?.toString())
        assertEquals("$label: the callback hands on once", 1, hooked.count { it.method()?.toString() == FEED_PROGRESS })

        val reader = context.mutableClassDefBy(FEED_SEEK_READER)
        fun stub(name: String) = reader.methods.single { it.name == name }.code()
        for (name in listOf("view", "seekTo")) assertEquals("$label: $name is filled", Opcode.CHECK_CAST, stub(name).first().opcode)
        assertEquals("$label: the view stub asks the holder", listOf(sites.view.toString()), stub("view").mapNotNull { it.method()?.toString() })
        assertEquals("$label: the seek stub seeks the controller", listOf(sites.seek.toString()), stub("seekTo").mapNotNull { it.method()?.toString() })
        return sites
    }

    /**
     * The player, every class its progress callback names, every SeekBar listener and the
     * extension's stubs.
     */
    private fun classes(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        val named = mutableSetOf<String>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val callback = classDef.methods.firstOrNull { it.isCallback() }
                if (callback != null) {
                    named += callback.code().flatMap { instruction ->
                        when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                            is FieldReference -> listOf(reference.definingClass, reference.type)
                            is MethodReference -> listOf(reference.definingClass)
                            else -> emptyList()
                        }
                    }
                }
                if (callback != null || SEEK_LISTENER in classDef.interfaces) found += ImmutableClassDef.of(classDef)
            }
        }
        val taken = found.map { it.type }.toSet()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) if (classDef.type in named && classDef.type !in taken) found += ImmutableClassDef.of(classDef)
        }
        return FixtureDex.withStringPools(bundle, found) + ExtensionDex.classDef(FEED_SEEK_READER)
    }

    private fun ClassDef.callback(name: String): Method =
        methods.single { it.name == name && it.isCallback(holding = false) }

    private fun Method.isCallback(holding: Boolean = true): Boolean =
        !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" && parameterTypes.joinToString("") == "IIZ" &&
            (!holding || code().any { (it as? ReferenceInstruction)?.reference.let { r -> r is StringReference && r.string == COVER_REPORT } })

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
}

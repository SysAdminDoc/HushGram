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
import app.morphe.patches.instagram.download.IMAGE_INFO
import app.morphe.patches.instagram.download.PANDO_IMAGE_INFO
import app.morphe.patches.instagram.feed.tabswipe.stopTabSwipingPatch
import app.morphe.patches.instagram.misc.extension.loadsString
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
     * On 454.0.0.54.77's arm64 build 386014758 the patch finds the feed's player (07jT) and its
     * progress callback (G4a), the holder's view (0Ba1's CXD) and the controller's seek (04Te's A0T,
     * the one a SeekBar listener's onStopTrackingTouch makes), and the player's pause and play that
     * listener makes around the seek (A0H and A04, #472), fills every stub and hands the callback to
     * the extension first thing. Each build has its own names.
     */
    @Test
    fun eachDeclaredBuildFindsTheSeekAndHooksThePlayer() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val sites = builds(bundle, bundle.name)
                if (version == "454.0.0.54.77") {
                    assertEquals(
                        "${bundle.name}: the player, its progress callback, the holder's view, the seek, the pause and the play",
                        listOf(
                            "LX/07jT;", "G4a", "LX/0Ba1;->CXD()Landroid/view/View;", "LX/04Te;->A0T(IZ)V",
                            "LX/07jT;->A0H(Ljava/lang/String;)V", "LX/07jT;->A04(LX/07jT;Ljava/lang/String;ZZZ)V",
                        ),
                        listOf(
                            sites.player, sites.progress, sites.view.toString(), sites.seek.toString(),
                            sites.pause.toString(), sites.play.toString(),
                        ),
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

    /** A build without the post class or the strip's map builder stops at the look, before the patch writes anything. */
    @Test
    fun aBuildThePreviewCantReadFailsBeforeAnythingChanges() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val all = classes(bundle)
        val sites = PatchContexts.of(all).findFeedSeek()
        val builder = PatchContexts.of(all).findFeedSeekSheet(sites).builder.definingClass
        val cases = listOf(
            all.filter { it.type != FEED_MEDIA } to "this build has no $FEED_MEDIA",
            all.filter { it.type != builder } to "turning a $SPRITESHEET_INFO into a map, found 0",
        )
        for ((classes, expected) in cases) {
            val failure = assertThrows(expected, PatchException::class.java) { PatchContexts.of(classes).findFeedSeekSheet(sites) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /** A build whose reel strip can't be told still goes in, with the post's own strip and the reel strip's stub left as shipped. */
    @Test
    fun aBuildWithoutTheReelStripGoesInWithThePostsOwn() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val classes = classes(bundle).filter { it.type != PANDO_SPRITE_CANDIDATES }
        val context = PatchContexts.of(classes)
        val sites = context.findFeedSeek()
        val sheet = context.findFeedSeekSheet(sites)
        assertEquals("no chain without the tree-backed candidates", null, sheet.scrubber)
        context.hookFeedSeekSheet(sites, sheet)
        val reader = context.mutableClassDefBy(FEED_SEEK_READER)
        fun first(name: String) = reader.methods.single { it.name == name }.code().first().opcode
        assertEquals("the post's own strip is still read", Opcode.CHECK_CAST, first("sheet"))
        assertEquals("the reel strip's stub answers null as shipped", Opcode.CONST_4, first("scrubberSheet"))
    }

    private fun builds(bundle: File, label: String): FeedSeekSites {
        val classes = classes(bundle)
        val context = PatchContexts.of(classes)
        val sites = context.findFeedSeek()
        val sheet = context.findFeedSeekSheet(sites)
        context.hookFeedSeek(sites)
        context.hookFeedSeekSheet(sites, sheet)
        for (key in listOf(SPRITE_URLS, "thumbnail_width", "thumbnail_height", "thumbnails_per_row", "max_thumbnails_per_sprite", "thumbnail_duration")) {
            assertTrue("$label: the map holds $key", context.loadsString(sheet.builder, key))
        }
        if (label.contains("386014758")) {
            assertEquals(
                "$label: the post, its strip and the strip's map",
                listOf("LX/07Jc;->A03:Ljava/lang/Object;", "A2e", "LX/08cg;", "A02"),
                listOf(sheet.media.toString(), sheet.getter.name, sheet.builder.definingClass, sheet.builder.name),
            )
        }

        val stock = classes.single { it.type == sites.player }.callback(sites.progress).code()
        val hooked = context.mutableClassDefBy(sites.player).callback(sites.progress).code()
        assertEquals("$label: the callback grows by the one call", stock.size + 1, hooked.size)
        assertEquals("$label: the callback hands on first thing", Opcode.INVOKE_STATIC_RANGE, hooked.first().opcode)
        assertEquals("$label: the callback hands on to the extension", FEED_PROGRESS, hooked.first().method()?.toString())
        assertEquals("$label: the callback hands on once", 1, hooked.count { it.method()?.toString() == FEED_PROGRESS })

        val reader = context.mutableClassDefBy(FEED_SEEK_READER)
        fun stub(name: String) = reader.methods.single { it.name == name }.code()
        for (name in listOf("view", "seekTo", "pause", "play")) assertEquals("$label: $name is filled", Opcode.CHECK_CAST, stub(name).first().opcode)
        assertEquals("$label: the view stub asks the holder", listOf(sites.view.toString()), stub("view").mapNotNull { it.method()?.toString() })
        assertEquals("$label: the seek stub seeks the controller", listOf(sites.seek.toString()), stub("seekTo").mapNotNull { it.method()?.toString() })
        assertEquals("$label: the pause stub pauses the player", listOf(sites.pause.toString()), stub("pause").mapNotNull { it.method()?.toString() })
        assertEquals("$label: the play stub plays the player", listOf(sites.play.toString()), stub("play").mapNotNull { it.method()?.toString() })
        fun reasons(name: String) = stub(name).mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
        assertEquals("$label: the pause gives the scrubber's reason", listOf(SEEK_PAUSE_REASON), reasons("pause"))
        assertEquals("$label: the play gives the scrubber's reason", listOf(SEEK_PLAY_REASON), reasons("play"))
        fun fields(name: String) = stub(name).mapNotNull { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.toString() }
        assertEquals("$label: the media stub starts at the player", Opcode.CHECK_CAST, stub("media").first().opcode)
        assertEquals("$label: the media stub reads the item, then its post", listOf(sites.item.toString(), sheet.media.toString()), fields("media"))
        assertEquals(
            "$label: the sheet stub asks the post for its strip, then the strip for its map",
            listOf(
                "$FEED_MEDIA->${sheet.getter.name}()$SPRITESHEET_INFO",
                "${sheet.builder.definingClass}->${sheet.builder.name}($SPRITESHEET_INFO)Ljava/util/Map;",
            ),
            stub("sheet").mapNotNull { it.method()?.toString() },
        )
        val scrubber = stub("scrubberSheet").mapNotNull { it.method() }
        assertEquals("$label: the reel strip stub starts at the post", Opcode.CHECK_CAST, stub("scrubberSheet").first().opcode)
        assertEquals(
            "$label: the reel strip stub goes post, picture sizes, strip candidates, strip, then the strip's map",
            listOf(FEED_MEDIA to IMAGE_INFO, IMAGE_INFO to SPRITE_CANDIDATES, SPRITE_CANDIDATES to SPRITESHEET_INFO, sheet.builder.definingClass to "Ljava/util/Map;"),
            scrubber.map { it.definingClass to it.returnType },
        )
        assertEquals("$label: the map is the same builder's", sheet.builder.name, scrubber.last().name)
        if (label.contains("386014758")) {
            assertEquals("$label: the reel strip's getters", listOf("A3N", "DT4", "EA6"), scrubber.dropLast(1).map { it.name })
        }
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
        named += listOf(FEED_MEDIA, IMAGE_INFO, PANDO_IMAGE_INFO, SPRITE_CANDIDATES, PANDO_SPRITE_CANDIDATES)
        val taken = found.map { it.type }.toSet()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) if (classDef.type in named && classDef.type !in taken) found += ImmutableClassDef.of(classDef)
        }
        val have = found.map { it.type }.toSet()
        found += FixtureDex.classesLoading(bundle, SPRITE_URLS).filter { it.type !in have }
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

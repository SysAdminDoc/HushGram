/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.time

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.reels.suggested.TO_CLIPS_ITEM
import app.morphe.patches.instagram.reels.time.ShowReelTimeHookTest.Companion.assertNothingElseChanged
import app.morphe.patches.instagram.reels.time.ShowReelTimeHookTest.Companion.assertPickHook
import app.morphe.patches.instagram.reels.time.ShowReelTimeHookTest.Companion.assertRenderHook
import app.morphe.patches.instagram.reels.time.ShowReelTimeHookTest.Companion.assertStubsFilled
import app.morphe.patches.instagram.reels.time.ShowReelTimeHookTest.Companion.assertTimeLineMaker
import app.morphe.patches.instagram.reels.time.ShowReelTimeHookTest.Companion.code
import app.morphe.patches.instagram.reels.time.ShowReelTimeHookTest.Companion.reference
import app.morphe.patches.instagram.reels.time.ShowReelTimeHookTest.Companion.snapshot
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The patch on each of 450's seven builds, with nothing of the extension in the build but the two
 * classes of its own, so no download patch's bridge to a post is there to lean on: the hook in the
 * Reels title in front of its column, the hook first in the subtitle's pick, the maker the title
 * gains, the line made a subtitle state, the three stubs filled, and nothing else changed.
 */
class NativeShowReelTimeTest {
    @Test fun everyBuildGivesTheTitleItsLineAndChangesNothingElse() {
        val bundles = declared() + Fixtures.otherBuilds()
        val checked = mutableListOf<String>()
        for (bundle in bundles) {
            val name = bundle.absolutePath
            val classes = load(bundle) { types -> FixtureDex.classes(bundle, types) }
            val extension = listOf(ExtensionDex.classDef(REEL_POST_TIME), ExtensionDex.classDef(REEL_TIME_LINE))
            val context = PatchContexts.of(classes.values + extension)
            val before = (classes.values + extension).associate { it.type to snapshot(it.methods) }
            val methods = classes.values.flatMap { it.methods }
            val render = methods.single { TITLE_RENDER in it.markers() }
            val maker = methods.single { SUBTITLE_MAKER in it.markers() }
            val pick = methods.single { SUBTITLE_PICK in it.markers() }
            val iconText = methods.single { ICON_TEXT_RENDER in it.markers() }.definingClass

            val sites = context.findReelTime()
            context.showReelTime(sites)

            assertEquals("$name: the title", render.definingClass, sites.title)
            assertEquals("$name: the maker is the title's", sites.title, maker.definingClass)
            assertEquals("$name: the subtitle", maker.returnType, sites.subtitle)
            assertEquals("$name: the pick is the subtitle's", sites.subtitle, pick.definingClass)
            assertEquals("$name: the subtitle text", iconText, sites.iconText)
            val titleMethods = context.mutableClassDefBy(sites.title).methods
            assertRenderHook(name, titleMethods.single { it.toString() == render.toString() }, render.code(), sites)
            assertTimeLineMaker(name, titleMethods.filter { it.name == TIME_LINE_MAKER }, maker, sites)
            assertPickHook(name, context.mutableClassDefBy(sites.subtitle).methods.single { it.toString() == pick.toString() }, pick.code(), sites)
            assertStubsFilled(name, context, sites)
            assertEquals("$name: the line is a subtitle state", listOf(sites.state), context.mutableClassDefBy(REEL_TIME_LINE).interfaces.toList())
            assertNothingElseChanged(name, before, context, sites)

            // The time is the post's own taken_at, read by the hash of that name as the server's fields are.
            assertTrue(
                "$name: the getter reads $TAKEN_AT_FIELD",
                sites.takenAt.code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == TAKEN_AT_FIELD.hashCode() },
            )
            assertEquals("$name: the getter answers the seconds", "Ljava/lang/Long;", sites.takenAt.returnType)
            assertEquals("$name: the getter is the post's", MEDIA, sites.takenAt.definingClass)
            assertEquals(
                "$name: the post is the Reels item's one",
                classes.getValue(sites.item.type).fields.single { it.type == MEDIA }.name, sites.media.name,
            )
            checked += name
        }
        for (code in BUILDS) assertTrue("no fixture of build $code among $checked", checked.any { it.contains("-$code") })
    }

    @Test fun dexBackedInstructionReReadsResolveTheSameMethods() {
        val bundles = declared()
        assertTrue("no fixture of a declared build", bundles.isNotEmpty())
        for (bundle in bundles) {
            val name = bundle.absolutePath
            val classes = load(bundle) { types -> FixtureDex.classesAsRead(bundle, types) }
            val context = PatchContexts.of(classes.values + ExtensionDex.classDef(REEL_POST_TIME) + ExtensionDex.classDef(REEL_TIME_LINE))
            val sites = context.findReelTime()
            val render = sites.render.code()
            val pick = sites.pick.code()
            context.showReelTime(sites)

            val titleMethods = context.mutableClassDefBy(sites.title).methods
            assertRenderHook(name, titleMethods.single { it.toString() == sites.render.toString() }, render, sites)
            assertTimeLineMaker(name, titleMethods.filter { it.name == TIME_LINE_MAKER }, sites.maker, sites)
            assertPickHook(name, context.mutableClassDefBy(sites.subtitle).methods.single { it.toString() == sites.pick.toString() }, pick, sites)
            assertStubsFilled(name, context, sites)
        }
    }

    private companion object {
        val BUILDS = listOf(385914557, 385914503, 385914510, 385914517, 385914518, 385914546, 385914556, 385914558)

        /** The Reels item converter's marker, by the name the others are found by. */
        val CONVERTER = PURGE_MARKER.find(TO_CLIPS_ITEM)!!.groupValues[1]
        val WANTED = setOf(TITLE_RENDER, SUBTITLE_MAKER, SUBTITLE_RENDER, SUBTITLE_PICK, ICON_TEXT_RENDER, CONVERTER)

        /** The bundles of the builds the patches declare, as the other native tests read them. */
        fun declared(): List<File> {
            val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
            return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        }

        /**
         * Every class holding one of the markers, the reel the title's maker reads its state through,
         * that state, the Reels item the converter answers, the subtitle text's superclasses, and
         * Media.
         */
        fun load(bundle: File, read: (Set<String>) -> Map<String, ClassDef>): Map<String, ClassDef> {
            val supers = HashMap<String, String?>()
            val holding = mutableMapOf<String, MutableList<Method>>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) supers.putIfAbsent(classDef.type, classDef.superclass)
                val holds = dex.stringSection.any { string ->
                    string.startsWith("android_purge_") && PURGE_MARKER.find(string)?.groupValues?.get(1) in WANTED
                }
                if (!holds) return@forEach
                for (classDef in dex.classes) {
                    for (method in classDef.methods) {
                        val found = method.markers().filter { it in WANTED }.distinct()
                        if (found.isEmpty()) continue
                        val copy = ImmutableMethod.of(method)
                        found.forEach { holding.getOrPut(it) { mutableListOf() } += copy }
                    }
                }
            }
            fun one(marker: String): Method {
                val found = holding[marker].orEmpty()
                assertEquals("${bundle.name}: the methods holding $marker: $found", 1, found.size)
                return found.single()
            }
            val maker = one(SUBTITLE_MAKER)
            val title = maker.definingClass
            val through = maker.code().filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.reference() as? FieldReference }
                .single { it.definingClass != title }
            val chain = mutableSetOf<String>()
            var next = supers[one(ICON_TEXT_RENDER).definingClass]
            while (next != null && next in supers && chain.add(next)) next = supers[next]
            assertTrue("${bundle.name}: the subtitle text is what the pick answers", one(SUBTITLE_PICK).returnType in chain)
            val types = WANTED.map { one(it).definingClass }.toSet() + through.definingClass + through.type +
                one(CONVERTER).returnType + chain + MEDIA
            val classes = read(types)
            assertEquals(bundle.name, types.size, classes.size)
            return classes
        }
    }
}

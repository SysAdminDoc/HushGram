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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
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
            val render = renderOf(classes.values.toList())
            val maker = makerOf(render, classes.getValue(render.definingClass))
            val pick = pickOf(classes.getValue(maker.returnType), stateOf(maker))
            val iconText = iconTextOf(pick, classes)

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
        val BUILDS = listOf(386215122, 386215069, 386215121, 386215123, 386215124)

        /** The Reels title's render among [classes]: the one method loading [TITLE_COMPONENT], however Redex pooled it. */
        fun renderOf(classes: List<ClassDef>): Method {
            val byType = classes.associateBy { it.type }
            return classes.flatMap { it.methods }.single { method ->
                method.parameterTypes.map(Any::toString) != listOf("I") && FixtureDex.loads(method, TITLE_COMPONENT, byType)
            }
        }

        /** The subtitle maker of [title]: its one instance method taking a flag that [render] calls. */
        fun makerOf(render: Method, title: ClassDef): Method = title.methods.single { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.map(Any::toString) == listOf("Z") &&
                render.code().any { instruction ->
                    (instruction.reference() as? MethodReference)?.let {
                        it.definingClass == method.definingClass && it.name == method.name && it.parameterTypes.map(Any::toString) == listOf("Z")
                    } == true
                }
        }

        /** The state the maker reads through the title's reel: the one object it reads off another class. */
        fun stateOf(maker: Method): String = maker.code().filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { it.reference() as? FieldReference }.single { it.definingClass != maker.definingClass }.type

        /** The subtitle's pick: its one instance method taking [state] once that the method reading its state calls. */
        fun pickOf(subtitle: ClassDef, state: String): Method {
            val kept = subtitle.fields.single { it.type == state }
            val render = subtitle.methods.single { method ->
                method.code().any { instruction ->
                    instruction.opcode == Opcode.IGET_OBJECT &&
                        (instruction.reference() as? FieldReference)?.let { it.definingClass == subtitle.type && it.name == kept.name } == true
                }
            }
            return subtitle.methods.single { method ->
                val takes = method.parameterTypes.map(Any::toString)
                !AccessFlags.STATIC.isSet(method.accessFlags) && takes.count { it == state } == 1 &&
                    render.code().any { instruction ->
                        (instruction.reference() as? MethodReference)?.let {
                            it.definingClass == subtitle.type && it.name == method.name && it.parameterTypes.map(Any::toString) == takes
                        } == true
                    }
            }
        }

        /** What [pick] makes, by type. */
        fun madeBy(pick: Method): Set<String> =
            pick.code().filter { it.opcode == Opcode.NEW_INSTANCE }.mapNotNull { (it.reference() as? TypeReference)?.type }.toSet()

        /**
         * The subtitle text [pick] makes: the one class it makes whose single constructor takes nine
         * values, a listener first, the text fifth, an int icon and a flag last.
         */
        fun iconTextOf(pick: Method, classes: Map<String, ClassDef>): String = madeBy(pick).single { type ->
            val takes = classes[type]?.methods?.filter { it.name == "<init>" }?.singleOrNull()?.parameterTypes?.map(Any::toString)
            takes != null && takes.size == 9 && takes[0] == "Landroid/view/View\$OnClickListener;" &&
                takes[4] == "Ljava/lang/CharSequence;" && takes[7] == "I" && takes[8] == "Z"
        }

        /** The bundles of the builds the patches declare, as the other native tests read them. */
        fun declared(): List<File> {
            val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
            return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        }

        /**
         * The Reels title and every class loading its name, the reel the title's maker reads its
         * state through, that state, the subtitle and what its pick makes, the Reels item converter
         * with any pool it asks, the item it answers, the subtitle text's superclasses, and Media.
         */
        fun load(bundle: File, read: (Set<String>) -> Map<String, ClassDef>): Map<String, ClassDef> {
            val supers = HashMap<String, String?>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) supers.putIfAbsent(classDef.type, classDef.superclass)
            }
            val titled = FixtureDex.classesLoading(bundle, TITLE_COMPONENT)
            val render = renderOf(titled)
            val maker = makerOf(render, titled.single { it.type == render.definingClass })
            val title = maker.definingClass
            val subtitle = maker.returnType
            val subtitleClass = FixtureDex.classes(bundle, setOf(subtitle)).getValue(subtitle)
            val pick = pickOf(subtitleClass, stateOf(maker))
            val made = FixtureDex.classes(bundle, madeBy(pick))
            val iconText = iconTextOf(pick, made)
            val converting = FixtureDex.classesLoading(bundle, TO_CLIPS_ITEM)
            val convertingByType = converting.associateBy { it.type }
            val converter = converting.flatMap { it.methods }.single { method ->
                !(method.parameterTypes.map(Any::toString) == listOf("I") && method.returnType == "Ljava/lang/String;") &&
                    FixtureDex.loads(method, TO_CLIPS_ITEM, convertingByType)
            }
            val through = maker.code().filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.reference() as? FieldReference }
                .single { it.definingClass != title }
            val chain = mutableSetOf<String>()
            var next = supers[iconText]
            while (next != null && next in supers && chain.add(next)) next = supers[next]
            assertTrue("${bundle.name}: the subtitle text is what the pick answers", pick.returnType in chain)
            val types = titled.map { it.type }.toSet() + subtitle + iconText + through.definingClass + through.type +
                converting.map { it.type } + converter.returnType + chain + MEDIA
            val classes = read(types)
            assertEquals(bundle.name, types.size, classes.size)
            return classes
        }
    }
}

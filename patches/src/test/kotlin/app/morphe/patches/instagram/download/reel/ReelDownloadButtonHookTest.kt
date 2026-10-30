/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Download button beside a reel (#178) on every build of the declared version: the column's
 * render adds Save and its count in two places, the hook lands right after each count's add, reads
 * the builders' own registers, borrows six free ones, and the bridges read the reel's item and Media
 * and make the icon with Save's own styles. The place over More asks through lithoAboveMore, and the
 * bridge it reads More's top margin through is More's own check of the share count.
 */
class ReelDownloadButtonHookTest {
    @Test
    fun theHooksAndBridgesAreInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_BUTTON_TYPE).methods
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }.toSet()
        for (needed in listOf(
            REEL_BUTTON_ON.substringAfter("->"),
            REEL_BUTTON_LITHO.substringAfter("->"),
            REEL_BUTTON_LITHO_ABOVE_MORE.substringAfter("->"),
            "reelItem(Ljava/lang/Object;)Ljava/lang/Object;",
            "itemMedia(Ljava/lang/Object;)Ljava/lang/Object;",
            "moreTop(Ljava/lang/Object;)I",
            "lithoIcon(Ljava/lang/Object;Ljava/lang/CharSequence;Ljava/lang/Object;JJ)Ljava/lang/Object;",
        )) assertTrue("$REEL_BUTTON_TYPE has no public static $needed", needed in declared)
    }

    @Test
    fun eachBuildGetsTheIconRightAfterSavesCount() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertTrue("three builds of the declared version", bundles.size >= 3)
        for (bundle in bundles) check(bundle, if (bundle.extension == "apks") bundle.name else bundle.parentFile.name)
    }

    private fun check(bundle: File, name: String) {
        val markers = setOf(COLUMN_MARKER, SAVE_MARKER, SAVE_COUNT_MARKER, TINT_MARKER, SAVE_ICON_MARKER, SHARE_MARKER, MORE_MARKER)
        val classes = LinkedHashMap<String, ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val holds = dex.stringSection.any { string ->
                string.startsWith("android_purge_") && PURGE_MARKER.find(string)?.groupValues?.get(1) in markers
            }
            if (!holds) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.markers().any { it in markers } }) classes[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        // What the hook reads around the marked methods: what their code names, the column's fields,
        // then a few steps out by superclass, interface, field and getter, which reaches the reel's
        // item, its kind's enum and the icon's superclasses.
        val marked = classes.values.flatMap { it.methods }.filter { method -> method.markers().any { it in markers } }
        var wanted = marked.flatMap { codeTypes(it) }.toSet() + classes.values.flatMap { it.fields.map { field -> field.type } }
        repeat(4) {
            val missing = wanted.filter { it.startsWith("L") && it !in classes }.toSet()
            FixtureDex.classes(bundle, missing).forEach { (type, classDef) -> classes[type] = ImmutableClassDef.of(classDef) }
            wanted = missing.mapNotNull { classes[it] }.flatMap { outward(it) }.toSet()
        }
        classes[REEL_BUTTON_TYPE] = ExtensionDex.classDef(REEL_BUTTON_TYPE)
        val context = PatchContexts.of(classes.values.toList())

        val site = context.findReelButtonSite()
        assertEquals("$name: Save is added in two places", 2, site.sites.size)
        assertEquals("$name: the item's Media", MEDIA, site.media.type)
        assertEquals("$name: the item is what the getter answers", site.itemType, site.item.returnType)
        assertTrue("$name: the column's filter is its own", site.tint.definingClass == site.column && site.tintPrivate)

        // Where the gap under the icon comes from. Save sets one margin of nine, its bottom, to the
        // margin the column hands it (8dp). More sets its top, slot 5 of the same style, to
        // clips_viewer_media_info_ufi_button_side_margin (minus 8dp), and to none while the column's
        // state answers a share count. Save goes over Share first, then past the share count over More.
        assertEquals("$name: Save's margin is its bottom", 7 to 9, site.marginSlot to site.marginSlots)
        assertEquals("$name: More's margin is its top, of the same style", 5, site.moreTopSlot)
        assertEquals("$name: More's top is the side margin dimension", 0x7f070111, site.moreTop)
        assertEquals("$name: the share count is the column state's", site.state.type, site.shareCount.definingClass)
        assertTrue("$name: and answers a count", site.countType.startsWith("L") && site.countType != site.shareCount.returnType)
        assertEquals("$name: the first Save goes over Share, the second over More", listOf(false, true),
            site.sites.sortedBy { it.at }.map { it.aboveMore })

        val before = context.mutableClassDefBy(site.column).methods.single { it.name == site.render.name && it.markers().contains(COLUMN_MARKER) }
            .instructions()
        context.addReelDownloadButton(site)
        val code = context.mutableClassDefBy(site.column).methods.single { it.name == site.render.name && it.markers().contains(COLUMN_MARKER) }
            .instructions()
        assertEquals("$name: fourteen instructions at each place", before.size + 28, code.size)
        val asks = code.indices.filter { code[it].names(REEL_BUTTON_ON) }
        val makes = code.indices.filter { code[it].names(REEL_BUTTON_LITHO) || code[it].names(REEL_BUTTON_LITHO_ABOVE_MORE) }
        assertEquals("$name: on() at both places", 2, asks.size)
        assertEquals("$name: litho() at both places", 2, makes.size)
        assertEquals("$name: lithoAboveMore() over More alone", 1, code.count { it.names(REEL_BUTTON_LITHO_ABOVE_MORE) })
        assertEquals("$name: the icon is added once at each place", before.count { it.names(site.add.toString()) } + 2,
            code.count { it.names(site.add.toString()) })
        asks.zip(site.sites.sortedBy { it.at }).forEach { (at, place) ->
            val countAdd = code[at - 1] as FiveRegisterInstruction
            assertTrue("$name: right after the count's add", code[at - 1].names(site.add.toString()))
            assertEquals("$name: the count's result is what was added", (code[at - 2] as OneRegisterInstruction).registerA, countAdd.registerD)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[at - 2].opcode)
            assertTrue("$name: the count's builder before it", (code[at - 3] as ReferenceInstruction).reference.toString().contains(";->"))
            val made = code[at + 8] as RegisterRangeInstruction
            val litho = if (place.aboveMore) REEL_BUTTON_LITHO_ABOVE_MORE else REEL_BUTTON_LITHO
            assertTrue("$name: $litho eight after on()", code[at + 8].names(litho))
            assertEquals("$name: litho() takes the six borrowed registers", place.window to 6, made.startRegister to made.registerCount)
            assertTrue("$name: none borrowed is one the builders were handed or the list",
                (place.window until place.window + 6).none { it in place.first until place.first + 6 || it == place.list })
            val tint = code[at + 3] as RegisterRangeInstruction
            assertEquals("$name: the filter asked of the column the builders were handed", place.first to 2, tint.startRegister to tint.registerCount)
            val add = code[at + 13] as RegisterRangeInstruction
            assertTrue("$name: the icon is added by the column's own add", code[at + 13].names(site.add.toString()))
            assertEquals(place.window to 2, add.startRegister to add.registerCount)
        }

        val bridges = context.mutableClassDefBy(REEL_BUTTON_TYPE).methods.associateBy { it.name }
        fun body(name: String) = bridges.getValue(name).instructions().map { (it as? ReferenceInstruction)?.reference?.toString() }
        assertEquals("$name: reelItem", listOf(site.column, site.state.toString(), site.item.toString(), null, null), body("reelItem"))
        assertEquals("$name: itemMedia", listOf(site.itemType, site.media.toString(), null), body("itemMedia"))
        assertEquals("$name: moreTop asks the share count as More does",
            listOf(site.column, site.state.toString(), site.shareCount.toString(), null, site.countType, null, null, null, null, null),
            body("moreTop"))
        val literal = bridges.getValue("moreTop").instructions().mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }
        assertEquals("$name: none while the count shows, else the side margin", listOf(0, site.moreTop), literal)
        val icon = body("lithoIcon")
        for (needed in listOf(site.empty, site.width, site.height, site.margin, site.alignTo, site.align, site.description, site.tap, site.image)) {
            assertEquals("$name: the icon bridge uses $needed once", 1, icon.count { it == needed.toString() })
        }
    }

    /** The types [method]'s code names: the classes of what it calls and reads, their types, and the types it checks or makes. */
    private fun codeTypes(method: Method): List<String> = method.instructions().flatMap { instruction ->
        when (val reference = (instruction as? ReferenceInstruction)?.reference) {
            is MethodReference -> listOf(reference.definingClass, reference.returnType) + reference.parameterTypes.map(Any::toString)
            is FieldReference -> listOf(reference.definingClass, reference.type)
            is TypeReference -> listOf(reference.type)
            else -> emptyList()
        }
    }

    /** One step out from [classDef]: its superclass, interfaces, field types and what its getters answer. */
    private fun outward(classDef: ClassDef): List<String> =
        listOfNotNull(classDef.superclass) + classDef.interfaces + classDef.fields.map { it.type } +
            classDef.methods.filter { it.parameterTypes.isEmpty() }.map { it.returnType }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.names(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference
}

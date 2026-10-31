/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Not interested button beside a reel (#191) on every build of the declared version: the
 * delegate that binds the viewer's items is found by its two markers, the column's state hands back
 * the reel's state and the viewer's config, the icon's block goes in front of the Download one at both
 * places Save is added, the delegate's constructor reports itself with the config, and the bridges
 * run Instagram's own Not interested. The Block icon (#191) then goes in front of that block, and its
 * bridge opens Instagram's own block sheet with the delegate's activity and session. The sound icon
 * of Hide reels by sound (#191) goes in front of all three, and the Copy text icon (#303) in front of
 * it.
 */
class ReelNotInterestedHookTest {
    @Test
    fun theHooksAndBridgesAreInTheExtension() {
        val declared = ExtensionDex.classDef(NOT_INTERESTED_TYPE).methods
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }.toSet()
        for (needed in listOf(
            NOT_INTERESTED_ON.substringAfter("->"),
            NOT_INTERESTED_LITHO.substringAfter("->"),
            NOT_INTERESTED_LITHO_ABOVE_MORE.substringAfter("->"),
            NOT_INTERESTED_REMEMBER.substringAfter("->"),
            "notInterestedConfig(Ljava/lang/Object;)Ljava/lang/Object;",
            "notInterestedState(Ljava/lang/Object;)Ljava/lang/Object;",
            "notInterestedRun(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
        )) assertTrue("$NOT_INTERESTED_TYPE has no public static $needed", needed in declared)
        val blocks = ExtensionDex.classDef(BLOCK_TYPE).methods
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }.toSet()
        for (needed in listOf(
            BLOCK_ON.substringAfter("->"),
            BLOCK_LITHO.substringAfter("->"),
            BLOCK_LITHO_ABOVE_MORE.substringAfter("->"),
            "blockRun(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
        )) assertTrue("$BLOCK_TYPE has no public static $needed", needed in blocks)
        val sounds = ExtensionDex.classDef(SOUND_TYPE).methods
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }.toSet()
        for (needed in listOf(SOUND_ON, SOUND_LITHO, SOUND_LITHO_ABOVE_MORE).map { it.substringAfter("->") }) {
            assertTrue("$SOUND_TYPE has no public static $needed", needed in sounds)
        }
        val list = ExtensionDex.classDef(HIDDEN_SOUNDS).methods
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }.toSet()
        assertTrue("$HIDDEN_SOUNDS has no public static page", SOUND_PAGE_FILTER.substringAfter("->") in list)
        val texts = ExtensionDex.classDef(TEXT_TYPE).methods
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }.toSet()
        for (needed in listOf(TEXT_ON, TEXT_LITHO, TEXT_LITHO_ABOVE_MORE).map { it.substringAfter("->") }) {
            assertTrue("$TEXT_TYPE has no public static $needed", needed in texts)
        }
    }

    @Test
    fun eachBuildGetsTheIconInFrontOfDownloadsAndTheDelegateReportsItself() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertTrue("three builds of the declared version", bundles.size >= 3)
        for (bundle in bundles) check(bundle, if (bundle.extension == "apks") bundle.name else bundle.parentFile.name)
    }

    private fun check(bundle: File, name: String) {
        val markers = setOf(
            COLUMN_MARKER, SAVE_MARKER, SAVE_COUNT_MARKER, TINT_MARKER, SAVE_ICON_MARKER, SHARE_MARKER, MORE_MARKER,
            NOT_INTERESTED_MARKER, BINDER_INIT_MARKER,
        )
        val classes = LinkedHashMap<String, ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val holds = dex.stringSection.any { string ->
                string.startsWith("android_purge_") && PURGE_MARKER.find(string)?.groupValues?.get(1) in markers
            }
            val anchors = dex.stringSection.any { it == BLOCK_HELPER_ANCHOR }
            if (!holds && !anchors) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.markers().any { it in markers } } ||
                    anchors && classDef.methods.any { it.loads(BLOCK_HELPER_ANCHOR) }
                ) classes[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        val marked = classes.values.flatMap { it.methods }.filter { method -> method.markers().any { it in markers } }
        var wanted = marked.flatMap { codeTypes(it) }.toSet() + classes.values.flatMap { it.fields.map { field -> field.type } }
        repeat(4) {
            val missing = wanted.filter { it.startsWith("L") && it !in classes }.toSet()
            FixtureDex.classes(bundle, missing).forEach { (type, classDef) -> classes[type] = ImmutableClassDef.of(classDef) }
            wanted = missing.mapNotNull { classes[it] }.flatMap { outward(it) }.toSet()
        }
        classes[REEL_BUTTON_TYPE] = ExtensionDex.classDef(REEL_BUTTON_TYPE)
        classes[NOT_INTERESTED_TYPE] = ExtensionDex.classDef(NOT_INTERESTED_TYPE)
        classes[BLOCK_TYPE] = ExtensionDex.classDef(BLOCK_TYPE)
        classes[SOUND_TYPE] = ExtensionDex.classDef(SOUND_TYPE)
        classes[HIDDEN_SOUNDS] = ExtensionDex.classDef(HIDDEN_SOUNDS)
        classes[TEXT_TYPE] = ExtensionDex.classDef(TEXT_TYPE)
        val context = PatchContexts.of(classes.values.toList())

        val site = context.findReelButtonSite()
        val found = context.findNotInterestedSite(site)
        assertEquals("$name: the delegate's own Not interested takes the column's reel item", site.itemType, found.itemType)
        assertEquals("$name: the state interface is the column's", site.state.type, found.stateInterface)
        assertEquals("$name: the delegate is where Not interested is", found.delegate, found.run.definingClass)
        assertEquals("$name: and where the constructor is", found.delegate, found.constructor.definingClass)
        assertTrue("$name: the getters are the state's own",
            found.configGetter.definingClass == site.state.type && found.stateGetter.definingClass == site.state.type &&
                found.configGetter.name != found.stateGetter.name)
        assertEquals("$name: the config getter answers the viewer's config", VIEWER_CONFIG, found.configGetter.returnType)
        assertEquals("$name: the state getter answers what Not interested takes", found.stateType, found.stateGetter.returnType)

        val renderOf = { context.mutableClassDefBy(site.column).methods.single { it.name == site.render.name && it.markers().contains(COLUMN_MARKER) } }
        val initOf = { context.mutableClassDefBy(found.delegate).methods.single { it.name == "<init>" && it.markers().contains(BINDER_INIT_MARKER) } }
        context.addReelDownloadButton(site)
        val before = renderOf().instructions()
        assertEquals("$name: alone, the sound icon's block goes in front of Download's",
            before.indices.filter { before[it].names(REEL_BUTTON_ON) }, context.findSoundStarts(site).map { it.second })
        val constructorBefore = initOf().instructions()
        context.addNotInterestedButton(site, found)
        val code = renderOf().instructions()
        assertEquals("$name: fourteen instructions at each place", before.size + 28, code.size)
        val asks = code.indices.filter { code[it].names(NOT_INTERESTED_ON) }
        assertEquals("$name: on() at both places", 2, asks.size)
        assertEquals("$name: lithoAboveMore() over More alone", 1, code.count { it.names(NOT_INTERESTED_LITHO_ABOVE_MORE) })
        assertEquals("$name: litho() over Share", 1, code.count { it.names(NOT_INTERESTED_LITHO) })
        asks.zip(site.sites.sortedBy { it.at }).forEach { (at, place) ->
            assertTrue("$name: Download's block follows right after, to skip on its own", code[at + 14].names(REEL_BUTTON_ON))
            val litho = if (place.aboveMore) NOT_INTERESTED_LITHO_ABOVE_MORE else NOT_INTERESTED_LITHO
            val made = code[at + 8] as RegisterRangeInstruction
            assertTrue("$name: $litho eight after on()", code[at + 8].names(litho))
            assertEquals("$name: litho() takes the six borrowed registers", place.window to 6, made.startRegister to made.registerCount)
            val add = code[at + 13] as RegisterRangeInstruction
            assertTrue("$name: the icon is added by the column's own add", code[at + 13].names(site.add.toString()))
            assertEquals(place.window to 2, add.startRegister to add.registerCount)
        }

        val init = initOf().instructions()
        assertEquals("$name: three instructions in front of the constructor's return", constructorBefore.size + 3, init.size)
        assertEquals("$name: the return stays last", Opcode.RETURN_VOID, init.last().opcode)
        assertTrue("$name: the delegate reports itself", init[init.size - 2].names(NOT_INTERESTED_REMEMBER))
        assertEquals("$name: the first move is the config parameter", Opcode.MOVE_OBJECT_FROM16, init[init.size - 4].opcode)
        assertEquals("$name: the second is the delegate itself", Opcode.MOVE_OBJECT_FROM16, init[init.size - 3].opcode)

        val bridges = context.mutableClassDefBy(NOT_INTERESTED_TYPE).methods.associateBy { it.name }
        fun body(method: String) = bridges.getValue(method).instructions().map { (it as? ReferenceInstruction)?.reference?.toString() }
        assertEquals("$name: the config bridge", listOf(site.column, site.state.toString(), found.configGetter.toString(), null, null), body("notInterestedConfig"))
        assertEquals("$name: the state bridge", listOf(site.column, site.state.toString(), found.stateGetter.toString(), null, null), body("notInterestedState"))
        assertEquals("$name: the run bridge",
            listOf(found.delegate, found.itemType, found.stateType, found.run.toString(), null, null),
            body("notInterestedRun"))

        // The Block icon goes in front of Not interested's block and opens Instagram's own block sheet.
        val block = context.findBlockSite(found)
        assertEquals("$name: the delegate's activity", "Landroidx/fragment/app/FragmentActivity;", block.activity.type)
        assertEquals("$name: the delegate's session", "Lcom/instagram/common/session/UserSession;", block.session.type)
        assertEquals("$name: the helper's one instance", block.helper, block.instance.type)
        val withoutHelper = PatchContexts.of(classes.values.filter { it.type != block.helper })
        val refused = runCatching { withoutHelper.findBlockSite(found) }.exceptionOrNull()
        assertTrue("$name: a build without the helper is refused before anything changes", refused is PatchException)

        context.addBlockButton(site, found, block)
        val blocked = renderOf().instructions()
        assertEquals("$name: fourteen more at each place", code.size + 28, blocked.size)
        val blockAsks = blocked.indices.filter { blocked[it].names(BLOCK_ON) }
        assertEquals("$name: the Block on() at both places", 2, blockAsks.size)
        blockAsks.zip(site.sites.sortedBy { it.at }).forEach { (at, place) ->
            assertTrue("$name: Not interested's block follows right after", blocked[at + 14].names(NOT_INTERESTED_ON))
            assertTrue("$name: and Download's after that", blocked[at + 28].names(REEL_BUTTON_ON))
            val litho = if (place.aboveMore) BLOCK_LITHO_ABOVE_MORE else BLOCK_LITHO
            assertTrue("$name: $litho eight after on()", blocked[at + 8].names(litho))
            assertTrue("$name: the Block icon is added by the column's own add", blocked[at + 13].names(site.add.toString()))
        }
        val run = context.mutableClassDefBy(BLOCK_TYPE).methods.single { it.name == "blockRun" }.instructions()
            .map { (it as? ReferenceInstruction)?.reference?.toString() }
        assertEquals("$name: the block bridge",
            listOf(
                found.delegate, "Lcom/instagram/user/model/User;", block.instance.toString(), block.activity.toString(), null,
                block.session.toString(), null, null, null, block.open.toString(), null, null,
            ),
            run)

        // The sound icon (#191) goes in front of Block's block, the first at each place.
        val starts = context.findSoundStarts(site)
        assertEquals("$name: the sound icon starts where the Block icon does", blockAsks, starts.map { it.second })
        context.addSoundIcons(site, starts)
        val sounded = renderOf().instructions()
        assertEquals("$name: fourteen more again at each place", blocked.size + 28, sounded.size)
        val soundAsks = sounded.indices.filter { sounded[it].names(SOUND_ON) }
        assertEquals("$name: the sound on() at both places", 2, soundAsks.size)
        assertEquals("$name: its lithoAboveMore() over More alone", 1, sounded.count { it.names(SOUND_LITHO_ABOVE_MORE) })
        soundAsks.zip(site.sites.sortedBy { it.at }).forEach { (at, place) ->
            assertTrue("$name: Block's block follows right after", sounded[at + 14].names(BLOCK_ON))
            assertTrue("$name: then Not interested's", sounded[at + 28].names(NOT_INTERESTED_ON))
            assertTrue("$name: then Download's", sounded[at + 42].names(REEL_BUTTON_ON))
            val litho = if (place.aboveMore) SOUND_LITHO_ABOVE_MORE else SOUND_LITHO
            assertTrue("$name: $litho eight after on()", sounded[at + 8].names(litho))
            assertTrue("$name: the sound icon is added by the column's own add", sounded[at + 13].names(site.add.toString()))
        }

        // The Copy text icon (#303) goes in front of the sound icon's block, now the first at each place.
        val textStarts = context.findTextStarts(site)
        assertEquals("$name: the text icon starts where the sound icon does", soundAsks, textStarts.map { it.second })
        context.addTextIcons(site, textStarts)
        val texted = renderOf().instructions()
        assertEquals("$name: fourteen more again at each place", sounded.size + 28, texted.size)
        val textAsks = texted.indices.filter { texted[it].names(TEXT_ON) }
        assertEquals("$name: the text on() at both places", 2, textAsks.size)
        assertEquals("$name: its lithoAboveMore() over More alone", 1, texted.count { it.names(TEXT_LITHO_ABOVE_MORE) })
        textAsks.zip(site.sites.sortedBy { it.at }).forEach { (at, place) ->
            assertTrue("$name: the sound icon's block follows right after", texted[at + 14].names(SOUND_ON))
            assertTrue("$name: then Block's", texted[at + 28].names(BLOCK_ON))
            assertTrue("$name: then Download's, after Not interested's", texted[at + 56].names(REEL_BUTTON_ON))
            val litho = if (place.aboveMore) TEXT_LITHO_ABOVE_MORE else TEXT_LITHO
            assertTrue("$name: $litho eight after on()", texted[at + 8].names(litho))
            assertTrue("$name: the text icon is added by the column's own add", texted[at + 13].names(site.add.toString()))
        }
        val withoutText = PatchContexts.of(classes.values.filter { it.type != TEXT_TYPE })
        val noText = runCatching { withoutText.findTextStarts(site) }.exceptionOrNull()
        assertTrue("$name: an extension without the icon is refused before anything changes", noText is PatchException)
        val withoutList = PatchContexts.of(classes.values.filter { it.type != HIDDEN_SOUNDS })
        val noList = runCatching { withoutList.findSoundStarts(site) }.exceptionOrNull()
        assertTrue("$name: an extension without the list is refused before anything changes", noList is PatchException)
    }

    private fun Method.loads(string: String) = instructions().any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
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

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.origin

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.reels.origin.FindReelOriginHookTest.Companion.assertGridHook
import app.morphe.patches.instagram.reels.origin.FindReelOriginHookTest.Companion.assertOpenHook
import app.morphe.patches.instagram.reels.origin.FindReelOriginHookTest.Companion.assertResumeHook
import app.morphe.patches.instagram.reels.origin.FindReelOriginHookTest.Companion.code
import app.morphe.patches.instagram.reels.origin.FindReelOriginHookTest.Companion.reference
import app.morphe.patches.instagram.reels.origin.FindReelOriginHookTest.Companion.snapshot
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The hooks on each of 451's eight builds: the one profile opener, the one Reels tab onResume and
 * the posts grid's onResume they land in, nothing else changed, and the media type bridge a photo
 * reel is told by and the trial bridge a trial reel is (#225). And what the extension's search goes by: the grid's binder
 * names each tile by the string its reel's getId() returns, and the Reels tab puts up nine unnamed
 * placeholder tiles while its first page loads, which the search waits out (#30).
 */
class NativeFindReelOriginTest {
    @Test fun everyBuildHandsTheReelAndTheReelsTabToTheExtensionFirst() {
        val bundles = declared() + Fixtures.otherBuilds()
        val checked = mutableListOf<String>()
        for (bundle in bundles) {
            val name = bundle.absolutePath
            val classes = load(bundle) { types -> FixtureDex.classes(bundle, types) }
            val context = PatchContexts.of(classes.values + ExtensionDex.classDef(REEL_ORIGIN) + ExtensionDex.classDef(INSTAGRAM_MEDIA))
            val methods = classes.values.flatMap { it.methods }
            val open = methods.single { OPEN_PROFILE in it.markers() }
            val resume = methods.single { TAB_RESUME in it.markers() }
            val gridResume = classes.getValue(POSTS_GRID).methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() }
            val hooked = setOf(open.toString(), resume.toString(), gridResume.toString())
            val media = classes.getValue(open.parameterTypes.single().toString()).fields.single { it.type == MEDIA }
            context.hookReelOrigin()

            assertOpenHook(context.mutableClassDefBy(open.definingClass).methods.single { it.toString() == open.toString() },
                open.code(), "${media.definingClass}->${media.name}:$MEDIA")
            assertResumeHook(context.mutableClassDefBy(resume.definingClass).methods.single { it.toString() == resume.toString() },
                resume.code())
            // A photo reel is looked for in the profile's posts grid, which goes to the extension right
            // after its super.onResume(), leaving the first spot to Profile posts as a list (#225).
            assertGridHook(context.mutableClassDefBy(POSTS_GRID).methods.single { it.toString() == gridResume.toString() },
                gridResume.code())
            val stubs = context.mutableClassDefBy(INSTAGRAM_MEDIA).methods
            assertBridge(name, classes, stubs.single { it.name == "mediaType" }, "media_type", "Ljava/lang/Integer;")
            // A trial reel, kept off its poster's profile, is told by is_active_trial and not looked for.
            assertBridge(name, classes, stubs.single { it.name == "activeTrial" }, "is_active_trial", "Ljava/lang/Boolean;")
            for (classDef in classes.values) {
                assertEquals("$name: ${classDef.type} changed", snapshot(classDef.methods.filter { it.toString() !in hooked }),
                    snapshot(context.mutableClassDefBy(classDef.type).methods.filter { it.toString() !in hooked }))
            }

            // Instagram names each tile of a profile's Reels grid by its reel as it binds it.
            val bind = methods.single { GRID_BIND in it.markers() }
            assertTrue("$name: the grid's binder sets each tile's transition name", bind.code().any { it.reference() == SET_TRANSITION_NAME })
            assertTilesNamedByTheReelsId(name, classes, methods, bind)
            assertNinePlaceholdersFirst(name, classes, methods)
            checked += name
        }
        for (code in BUILDS) assertTrue("no fixture of build $code among $checked", checked.any { it.contains("-$code") })
    }

    /** A Media bridge casts to Media and calls its one getter answering [returns] that loads [field]'s key. */
    private fun assertBridge(name: String, classes: Map<String, ClassDef>, bridge: Method, field: String, returns: String) {
        val code = bridge.code()
        assertEquals("$name: the bridge's code", listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            code.take(4).map { it.opcode })
        assertEquals("$name: casts to Media", MEDIA, code[0].reference())
        val call = (code[1] as ReferenceInstruction).reference as MethodReference
        assertEquals("$name: calls Media", MEDIA, call.definingClass)
        assertEquals("$name: answers $returns", returns, call.returnType)
        val getter = classes.getValue(MEDIA).methods.single { it.name == call.name && it.parameterTypes.isEmpty() && it.returnType == call.returnType }
        assertTrue("$name: ${call.name} loads $field's key",
            getter.code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == field.hashCode() })
    }

    /**
     * The name the binder gives a tile is a String of the tile's state, which the state's builder
     * reads the way Media.getId() does, through the same two fields. So the id the opener holds and
     * the tile's name are one string, and a tile matches the reel by it.
     */
    private fun assertTilesNamedByTheReelsId(name: String, classes: Map<String, ClassDef>, methods: List<Method>, bind: Method) {
        val getId = classes.getValue(MEDIA).methods.single { it.name == "getId" && it.parameterTypes.isEmpty() }.code()
        val idReads = getId.filter { it.opcode == Opcode.IGET_OBJECT }.map { it.reference() }
        assertEquals("$name: getId reads its holder, then the id", 2, idReads.size)

        val bindCode = bind.code()
        val naming = bindCode.indexOfFirst { it.reference() == SET_TRANSITION_NAME }
        val read = writer(bindCode, naming, bindCode[naming].namedRegisters()[1])
        assertNotNull("$name: the tile's name comes from a field", read)
        val nameField = (bindCode[read!!] as ReferenceInstruction).reference as FieldReference
        assertEquals("$name: the tile's name is read from a field", Opcode.IGET_OBJECT, bindCode[read].opcode)
        assertEquals("$name: a String", "Ljava/lang/String;", nameField.type)

        val build = methods.single { GRID_ITEM in it.markers() }
        assertEquals("$name: the tile's state is what $GRID_ITEM builds", nameField.definingClass, build.returnType)
        val buildCode = build.code()
        val sets = buildCode.indices.filter { buildCode[it].opcode == Opcode.IPUT_OBJECT && buildCode[it].reference() == nameField.toString() }
        assertEquals("$name: the state's name is set once", 1, sets.size)
        val id = writer(buildCode, sets.single(), buildCode[sets.single()].namedRegisters()[0])
        assertEquals("$name: the state's name is getId's id", idReads[1], id?.let { buildCode[it].reference() })
        val holder = writer(buildCode, id!!, buildCode[id].namedRegisters()[1])
        assertEquals("$name: read from what getId reads it from", idReads[0], holder?.let { buildCode[it].reference() })
    }

    /**
     * While its first page loads, the Reels tab posts a runnable that asks the grid's adapter for
     * nine placeholder tiles: as many as fill a phone's screen, so the grid can't scroll on them.
     */
    private fun assertNinePlaceholdersFirst(name: String, classes: Map<String, ClassDef>, methods: List<Method>) {
        val shows = methods.single { PLACEHOLDERS in it.markers() }
        val tab = shows.definingClass
        assertTrue("$name: $PLACEHOLDERS is the Reels tab's", methods.any { it.definingClass == tab && TAB_RESUME in it.markers() })
        val posted = shows.code().filter { it.opcode == Opcode.NEW_INSTANCE }.map { it.reference()!! }
            .filter { type -> classes[type]?.interfaces?.contains(RUNNABLE) == true }
        assertEquals("$name: the tab posts one runnable for them", 1, posted.size)
        val run = classes.getValue(posted.single()).methods.single { it.name == "run" && it.parameterTypes.isEmpty() }.code()
        val adapters = run.filter { it.opcode == Opcode.IGET_OBJECT }
            .map { (it as ReferenceInstruction).reference as FieldReference }.filter { it.definingClass == tab }.map { it.type }
        val asks = run.indices.filter { at ->
            val call = (run[at] as? ReferenceInstruction)?.reference as? MethodReference
            run[at].opcode == Opcode.INVOKE_VIRTUAL && call != null && call.definingClass in adapters &&
                call.parameterTypes.map(Any::toString) == listOf("I") && call.returnType == "V"
        }
        assertEquals("$name: the runnable asks the tab's adapter once for a number of tiles", 1, asks.size)
        val count = writer(run, asks.single(), run[asks.single()].namedRegisters()[1])
        assertEquals("$name: nine of them", 9, count?.let { (run[it] as? NarrowLiteralInstruction)?.narrowLiteral })
    }

    /**
     * The instruction that last wrote [register] before [at], going back through plain moves and
     * casts, or null. It reads the code in order, which these straight runs of Instagram's are.
     */
    private fun writer(code: List<Instruction>, at: Int, register: Int): Int? {
        for (index in at - 1 downTo 0) {
            val instruction = code[index]
            if (!instruction.opcode.setsRegister() || instruction.namedRegisters().firstOrNull() != register) continue
            return when (instruction.opcode) {
                Opcode.CHECK_CAST -> continue
                Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 ->
                    writer(code, index, instruction.namedRegisters()[1])
                else -> index
            }
        }
        return null
    }

    @Test fun dexBackedInstructionReReadsResolveTheSameMethods() {
        val bundles = declared()
        assertTrue("no fixture of a declared build", bundles.isNotEmpty())
        for (bundle in bundles) {
            val classes = load(bundle) { types -> FixtureDex.classesAsRead(bundle, types) }
            val context = PatchContexts.of(classes.values + ExtensionDex.classDef(REEL_ORIGIN) + ExtensionDex.classDef(INSTAGRAM_MEDIA))
            val found = context.findReelOriginTargets()
            assertNotNull("${bundle.name}: a photo reel's posts grid is found", found.photoReels)
            assertNotNull("${bundle.name}: Media's trial getter is found", found.writeTrial)
            val open = found.openProfile.code()
            val resume = found.tabResume.code()
            context.hookReelOrigin()
            assertOpenHook(context.mutableClassDefBy(found.openProfile.definingClass).methods
                .single { it.toString() == found.openProfile.toString() }, open,
                "${found.media.definingClass}->${found.media.name}:$MEDIA")
            assertResumeHook(context.mutableClassDefBy(found.tabResume.definingClass).methods
                .single { it.toString() == found.tabResume.toString() }, resume)
        }
    }

    private companion object {
        val BUILDS = listOf(385914283, 385914282, 385914284, 385914285)
        /** The binder of a tile in a profile's Reels grid, and what builds the state it binds from a reel. */
        const val GRID_BIND = "ClipsGridItemDefinition_bind"
        const val GRID_ITEM = "ClipsGridExtensions_convertToClipsGridItem"
        /** Where the Reels tab puts up placeholder tiles before its first page. */
        const val PLACEHOLDERS = "ClipsProfileTabFragment_maybeShowShimmerAndGhostItems"
        const val SET_TRANSITION_NAME = "Landroid/view/View;->setTransitionName(Ljava/lang/String;)V"
        const val RUNNABLE = "Ljava/lang/Runnable;"
        val WANTED = setOf(OPEN_PROFILE, TAB_RESUME, TAB_CREATE, TILE_LOOKUP, GRID_BIND, GRID_ITEM, PLACEHOLDERS)

        /** The bundles of the builds the patches declare, as the other native tests read them. */
        fun declared(): List<File> {
            val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
            return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        }

        /**
         * Every class holding one of the markers, what the opener is handed, Media, the Reels tab's
         * superclasses up to Fragment, and the app's classes the placeholder method makes.
         */
        fun load(bundle: File, read: (Set<String>) -> Map<String, ClassDef>): Map<String, ClassDef> {
            val supers = HashMap<String, String?>()
            val marked = mutableSetOf<String>()
            val destinations = mutableSetOf<String>()
            val made = mutableSetOf<String>()
            var tab: String? = null
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) supers.putIfAbsent(classDef.type, classDef.superclass)
                val holds = dex.stringSection.any { string ->
                    string.startsWith("android_purge_") && PURGE_MARKER.find(string)?.groupValues?.get(1) in WANTED
                }
                if (!holds) return@forEach
                for (classDef in dex.classes) {
                    for (method in classDef.methods) {
                        val markers = method.markers()
                        if (markers.none { it in WANTED }) continue
                        marked += classDef.type
                        if (OPEN_PROFILE in markers) destinations += method.parameterTypes.map(Any::toString)
                        if (TAB_RESUME in markers) tab = classDef.type
                        if (PLACEHOLDERS in markers) made += method.code().filter { it.opcode == Opcode.NEW_INSTANCE }.mapNotNull { it.reference() }
                    }
                }
            }
            val chain = mutableSetOf<String>()
            var next = supers[checkNotNull(tab) { "${bundle.name}: no Reels tab" }]
            while (next != null && next in supers && chain.add(next)) next = supers[next]
            assertTrue("${bundle.name}: the Reels tab is a Fragment", FRAGMENT in chain)
            assertTrue("${bundle.name}: no $POSTS_GRID", POSTS_GRID in supers)
            val gridChain = mutableSetOf(POSTS_GRID)
            next = supers[POSTS_GRID]
            while (next != null && next in supers && gridChain.add(next)) next = supers[next]
            assertTrue("${bundle.name}: the posts grid is a Fragment", FRAGMENT in gridChain)
            val types = marked + destinations + MEDIA + chain + gridChain + made.filter { it in supers }
            val classes = read(types)
            assertEquals(bundle.name, types.size, classes.size)
            return classes
        }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.reels.live.REELS_ADD_ITEM
import app.morphe.patches.instagram.reels.live.REELS_INSERT_TRACE
import app.morphe.patches.instagram.reels.live.findReelsItemInserts
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picks' own part on every retained build (#503): how the friends named on a post are found and
 * the stubs they fill. The Reels page join is read on every build by the paid partnership tests,
 * which share it.
 */
class FriendLanePicksFixtureTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FRIEND_LANE_PICKS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (wanted in listOf(
            FRIEND_PICKS_FILTER.substringAfter("->"), FRIEND_PICKS_ADDED.substringAfter("->"),
            "media(Ljava/lang/Object;)Ljava/lang/Object;",
            "socialContexts(Ljava/lang/Object;)Ljava/util/List;", "faces(Ljava/lang/Object;)Ljava/util/List;",
            "sourceUser(Ljava/lang/Object;)Ljava/lang/Object;", "topLikers(Ljava/lang/Object;)Ljava/util/List;",
            "username(Ljava/lang/Object;)Ljava/lang/String;",
        ) + FRIEND_LANE_PAGE_STUBS.map { (name, shape) -> "$name(${shape.first.joinToString("")})${shape.second}" }) {
            assertTrue("$wanted is not in the extension: $declared", wanted in declared)
        }
    }

    /**
     * #503: a list that leaves the Friends tab no reel has the viewer asked for its next page. On every
     * build the registry hands out the viewer on screen, the viewer's toString names its data source
     * and load, the load is what Instagram's own trigger runs, and the stubs read those same fields.
     */
    @Test
    fun eachBuildAsksTheFriendsTabForItsNextPage() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var witnessed = 0
        for (bundle in bundles) {
            val label = bundle.parentFile.name.takeIf { bundle.name == "base.apk" } ?: bundle.name
            val classes = pagesClasses(bundle)
            val context = PatchContexts.of(classes + ExtensionDex.classDef(FRIEND_LANE_PICKS))
            val inserts = context.findReelsItemInserts("Clean up Reels", FRIEND_LANE_PICKS)
            val pages = context.findFriendLanePages(inserts)

            assertEquals("$label: the load", "Lkotlin/jvm/functions/Function0;", pages.load.type)
            assertEquals("$label: the registries", "Ljava/util/Map;", pages.registries.type)
            assertEquals("$label: the lock", "Ljava/lang/Object;", pages.lock.type)
            assertEquals("$label: the viewer on screen", pages.context, pages.current.type)

            // The registry's lookup by viewer session hands out the viewer it holds.
            val lookup = context.classDefBy(pages.registry).methods.single {
                it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;") && it.returnType == pages.context
            }
            assertTrue("$label: the lookup reads the viewer on screen", lookup.code().any { it.fieldText() == pages.current.toString() })

            // Instagram's own trigger runs the same load when you near the end.
            val trigger = classes.flatMap { it.methods }.filter { method -> method.code().any { it.stringText() == TRIGGER } }
            if (trigger.isNotEmpty()) witnessed++
            for (method in trigger) {
                val code = method.code()
                val read = code.indexOfFirst { it.opcode == Opcode.IGET_OBJECT && it.fieldText() == pages.load.toString() }
                assertTrue("$label: the trigger reads the load", read >= 0)
                assertTrue("$label: and runs it", code.drop(read).any { it.methodText() == "Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;" })
            }

            context.fillFriendLanePages(pages, inserts)
            val stubs = context.mutableClassDefBy(FRIEND_LANE_PICKS).methods
            fun body(name: String) = stubs.single { it.name == name }.code()
            assertEquals("$label: the registries", listOf(pages.registries.toString()), body("viewerRegistries").mapNotNull { it.fieldText() })
            assertEquals("$label: the lock", listOf(pages.lock.toString()), body("viewerRegistriesLock").mapNotNull { it.fieldText() })
            assertEquals("$label: the viewer", listOf(pages.current.toString()), body("viewerContext").mapNotNull { it.fieldText() })
            assertEquals(
                "$label: the module",
                listOf(pages.items.toString(), inserts.moduleField.toString()),
                body("viewerModule").mapNotNull { it.fieldText() },
            )
            val load = body("loadNextPage")
            assertEquals("$label: the load", listOf(pages.load.toString()), load.mapNotNull { it.fieldText() })
            assertTrue("$label: run", load.any { it.methodText() == "Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;" })
        }
        assertTrue("no build was read", bundles.isNotEmpty())
        assertTrue("no build held Instagram's own trigger to check the load against", witnessed > 0)
    }

    /** A build without the registry is refused by name, and the warning leaves the picks keeping a reel. */
    @Test
    fun aBuildWithoutTheRegistryIsRefused() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val classes = pagesClasses(bundle)
        val inserts = PatchContexts.of(classes + ExtensionDex.classDef(FRIEND_LANE_PICKS))
            .findReelsItemInserts("Clean up Reels", FRIEND_LANE_PICKS)
        val type = contextType(classes)
        val bare = PatchContexts.of(
            classes.filter { classDef -> classDef.type == type || classDef.fields.none { it.type == type } } +
                ExtensionDex.classDef(FRIEND_LANE_PICKS),
        )
        val failure = assertThrows(PatchException::class.java) { bare.findFriendLanePages(inserts) }
        assertTrue(failure.message, failure.message!!.startsWith("Clean up Reels: expected one registry holding a $type"))
        assertNull(bare.findFriendLanePagesOrWarn(inserts))

        val noStubs = PatchContexts.of(classes)
        val refused = assertThrows(PatchException::class.java) { noStubs.findFriendLanePages(inserts) }
        assertEquals("Clean up Reels: the extension has no $FRIEND_LANE_PICKS", refused.message)
    }

    @Test
    fun eachDeclaredBuildReadsTheFriendsNamedOnAPost() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                readsTheFriends(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildReadsThemToo() {
        val builds = Fixtures.otherBuilds()
        for (apk in builds) readsTheFriends(apk, apk.parentFile.name)
        assertTrue("no other build was read", builds.isNotEmpty())
    }

    @Test
    fun aBuildWithoutTheExtensionOrTheWriterIsRefused() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val classes = picksClasses(bundle)
        val bare = PatchContexts.of(classes.filter { it.type != FRIEND_LANE_PICKS })
        val failure = assertThrows(PatchException::class.java) { bare.findFriendPicks() }
        assertEquals("Clean up Reels: the extension has no $FRIEND_LANE_PICKS", failure.message)

        val writer = FixtureDex.classesLoading(bundle, FACEPILE_KEY)
            .filter { classDef -> classDef.methods.any { method -> method.parameterTypes.any { it.toString() == SOCIAL_CONTEXT_INFO } } }
            .map { it.type }.toSet()
        val noWriter = PatchContexts.of(classes.filter { it.type !in writer })
        val refused = assertThrows(PatchException::class.java) { noWriter.findFriendPicks() }
        assertTrue(refused.message, refused.message!!.contains(FACEPILE_KEY))
    }

    private fun readsTheFriends(bundle: File, label: String) {
        val context = PatchContexts.of(picksClasses(bundle))
        val reads = context.findFriendPicks()
        context.fillFriendPicks(PAGE, reads)

        assertTrue("$label: social_context", context.called("socialContexts", MEDIA).loads(context, MEDIA, SOCIAL_CONTEXT_FIELD))
        assertTrue("$label: top_likers", context.called("topLikers", MEDIA).loads(context, MEDIA, TOP_LIKERS_FIELD))
        assertTrue("$label: username", context.called("username", USER).loads(context, USER, "username"))

        val source = context.called("sourceUser", SOCIAL_CONTEXT_INFO)
        assertEquals("$label: the source is a user", USER, source.returnType)

        // The faces' getter holds no hash: the tree-backed model fills the field it reads ahead of
        // time, from a method that does. Follow that, so the writer's order isn't the only witness.
        val faces = context.called("faces", SOCIAL_CONTEXT_INFO)
        assertEquals("$label: the faces are a list", "Ljava/util/List;", faces.returnType)
        val model = context.classDefBy(PANDO_SOCIAL_CONTEXT)
        val getter = model.methods.single { it.name == faces.name && it.parameterTypes.isEmpty() }
        val field = getter.code().filter { it.opcode == Opcode.IGET_OBJECT }
            .map { (it as ReferenceInstruction).reference as FieldReference }.single()
        val filler = model.methods.single { method ->
            method.parameterTypes.size == 1 && method.returnType == "Ljava/util/List;" &&
                method.code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == FACEPILE_KEY.hashCode() }
        }
        val fills = model.methods.flatMap { method ->
            method.code().zipWithNext().zipWithNext().mapNotNull { (first, third) ->
                val call = (first.first as? ReferenceInstruction)?.reference as? MethodReference
                val put = (third.second as? ReferenceInstruction)?.reference as? FieldReference
                if (call?.name == filler.name && call.definingClass == filler.definingClass &&
                    third.second.opcode == Opcode.IPUT_OBJECT
                ) {
                    put?.name
                } else {
                    null
                }
            }
        }
        assertEquals("$label: the getter reads the field its filler's answer goes into", listOf(field.name), fills)
    }

    /** What the stub [name] calls on [type], read off its body. */
    private fun BytecodePatchContext.called(name: String, type: String): MethodReference {
        val body = mutableClassDefBy(FRIEND_LANE_PICKS).methods.single { it.name == name }.code()
        return body.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .single { it.definingClass == type }
    }

    /** Whether [this], a getter on [type], holds the hash of [field] as a constant. */
    private fun MethodReference.loads(context: BytecodePatchContext, type: String, field: String): Boolean =
        context.classDefBy(type).methods.single { it.name == name && it.parameterTypes.isEmpty() && it.returnType == returnType }
            .code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == field.hashCode() }

    private fun picksClasses(bundle: File): List<ClassDef> =
        (
            FixtureDex.classes(bundle, setOf(MEDIA, USER, SOCIAL_CONTEXT_INFO, PANDO_SOCIAL_CONTEXT)).values +
                FixtureDex.classesLoading(bundle, FACEPILE_KEY) +
                ExtensionDex.classDef(FRIEND_LANE_PICKS)
            ).associateBy { it.type }.values.toList()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.fieldText() = ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString()

    private fun Instruction.methodText() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.stringText() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    /** The viewer context's type among [classes]: the one whose toString prints its label. */
    private fun contextType(classes: List<ClassDef>): String =
        classes.single { classDef -> classDef.methods.any { m -> m.name == "toString" && m.code().any { it.stringText() == VIEWER_CONTEXT_LABEL } } }.type

    /**
     * What finding the next page load reads off [bundle]: the Reels data source and the viewer context
     * by their strings, Instagram's trigger, every class holding a context (the registry among them),
     * and the data source's supertypes, which say it's what the context shows.
     */
    private fun pagesClasses(bundle: File): List<ClassDef> {
        val loading = FixtureDex.classesLoadingAny(bundle, setOf(REELS_INSERT_TRACE, REELS_ADD_ITEM, VIEWER_CONTEXT_LABEL, TRIGGER))
        val context = contextType(loading)
        val dataSource = loading.single { classDef -> classDef.methods.any { m -> m.code().any { it.stringText() == REELS_INSERT_TRACE } } }
        val supers = HashMap<String, List<String>>()
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                supers[classDef.type] = listOfNotNull(classDef.superclass) + classDef.interfaces
                if (classDef.type != context && classDef.fields.any { it.type == context }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val chain = mutableSetOf<String>()
        var next = supers[dataSource.type].orEmpty()
        while (next.isNotEmpty()) {
            chain += next
            next = next.flatMap { supers[it].orEmpty() }.filter { it !in chain && it.startsWith("LX/") }
        }
        val above = FixtureDex.classes(bundle, chain.filter { it.startsWith("LX/") }.toSet()).values
        return (loading + holders + above).associateBy { it.type }.values.toList()
    }

    private companion object {
        const val PANDO_SOCIAL_CONTEXT = "Lcom/instagram/api/schemas/ImmutablePandoSocialContextInfo;"

        /** Instagram's own check for the next page as you scroll, a witness to which field is the load. */
        const val TRIGGER = "android_purge_26_q3_ClipsViewerContextRegistry_triggerLoadNextPage"

        /** The page join is the shared one; only the stubs are written here. */
        val PAGE = ReelsPageSites("Lfixture/Callback;", "success", "Lfixture/Source;", 0, 0, 0, "module", "Lfixture/ClipsItem;", "post")
    }
}

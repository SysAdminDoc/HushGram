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
import java.io.File
import org.junit.Assert.assertEquals
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
            FRIEND_PICKS_FILTER.substringAfter("->"), "media(Ljava/lang/Object;)Ljava/lang/Object;",
            "socialContexts(Ljava/lang/Object;)Ljava/util/List;", "faces(Ljava/lang/Object;)Ljava/util/List;",
            "sourceUser(Ljava/lang/Object;)Ljava/lang/Object;", "topLikers(Ljava/lang/Object;)Ljava/util/List;",
            "username(Ljava/lang/Object;)Ljava/lang/String;",
        )) {
            assertTrue("$wanted is not in the extension: $declared", wanted in declared)
        }
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

    private companion object {
        const val PANDO_SOCIAL_CONTEXT = "Lcom/instagram/api/schemas/ImmutablePandoSocialContextInfo;"

        /** The page join is the shared one; only the stubs are written here. */
        val PAGE = ReelsPageSites("Lfixture/Callback;", "success", "Lfixture/Source;", 0, 0, 0, "module", "Lfixture/ClipsItem;", "post")
    }
}

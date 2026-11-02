/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.lowviews

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.profile.friendship.RELATIONSHIP
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillReelsItemMedia
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The patch's own part on every retained build of 453: how a post's view counts and its author's
 * following answer are found, and the stubs they fill. The Reels page join is read on every build
 * by the paid partnership tests, which share it.
 */
class HideLowViewReelsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(LOW_VIEW_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (wanted in listOf(
            LOW_VIEW_PAGE_FILTER.substringAfter("->"), "media(Ljava/lang/Object;)Ljava/lang/Object;",
            "playCount(Ljava/lang/Object;)Ljava/lang/Integer;", "igPlayCount(Ljava/lang/Object;)Ljava/lang/Integer;",
            "followed(Ljava/lang/Object;)Ljava/lang/Boolean;",
        )) {
            assertTrue("$wanted is not in the extension: $declared", wanted in declared)
        }
    }

    /** On each retained build the counts, the author and the following answer are found and the stubs read them. */
    @Test
    fun eachBuildReadsViewsAndFollowing() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            val classes = FixtureDex.classes(bundle, setOf(MEDIA, USER, RELATIONSHIP, PANDO_RELATIONSHIP)).values.toList() +
                ExtensionDex.classDef(LOW_VIEW_REELS)
            val context = PatchContexts.of(classes)
            val reads = context.findLowViewReels()
            context.hideStubs(reads)

            val plays = context.stub("playCount")
            assertTrue("${bundle.name}: play_count", plays.getterLoading(context, MEDIA, PLAY_COUNT_FIELD.hashCode()))
            val igPlays = context.stub("igPlayCount")
            assertTrue("${bundle.name}: ig_play_count", igPlays.getterLoading(context, MEDIA, IG_PLAY_COUNT_FIELD.hashCode()))
            val followed = context.stub("followed").mapNotNull { it.text() }
            assertTrue("${bundle.name}: the author is read: $followed", followed.any { it.startsWith("$MEDIA->") && it.endsWith(")$USER") })
            assertTrue("${bundle.name}: the status is read: $followed", followed.any { it.startsWith("$USER->") && it.endsWith(")$RELATIONSHIP") })
            val follow = followed.single { it.startsWith("$RELATIONSHIP->") }
            val getter = context.classDefBy(PANDO_RELATIONSHIP).methods.single {
                it.name == follow.substringAfter("->").substringBefore("(") && it.parameterTypes.isEmpty()
            }
            assertTrue("${bundle.name}: following loads its field", getter.code().any {
                (it as? NarrowLiteralInstruction)?.narrowLiteral == FOLLOWING_FIELD.hashCode()
            })
            checked++
        }
        assertEquals("every retained build is read", Fixtures.BUILDS, checked)
    }

    @Test
    fun aBuildWithoutTheExtensionOrAStatusIsRefused() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val classes = FixtureDex.classes(bundle, setOf(MEDIA, USER, RELATIONSHIP, PANDO_RELATIONSHIP)).values.toList()
        val bare = PatchContexts.of(classes)
        val failure = assertThrows(PatchException::class.java) { bare.findLowViewReels() }
        assertEquals("Hide reels with few views: the extension has no $LOW_VIEW_REELS", failure.message)
        val noStatus = PatchContexts.of(classes.filter { it.type != PANDO_RELATIONSHIP } + ExtensionDex.classDef(LOW_VIEW_REELS))
        assertThrows(PatchException::class.java) { noStatus.findLowViewReels() }
    }

    private fun BytecodePatchContext.hideStubs(reads: LowViewReads) {
        val page = ReelsPageSites("Lfixture/Callback;", "success", "Lfixture/Source;", 0, 0, 0, "module", "Lfixture/ClipsItem;", "post")
        // The page join is the shared one; only the stubs are written here.
        fillReelsItemMedia(LOW_VIEW_REELS, page)
        fillStub(LOW_VIEW_REELS, "playCount", "Ljava/lang/Integer;", 1, reads.playCount)
        fillStub(LOW_VIEW_REELS, "igPlayCount", "Ljava/lang/Integer;", 1, reads.igPlayCount)
        fillStub(LOW_VIEW_REELS, "followed", "Ljava/lang/Boolean;", 2, reads.followed)
    }

    private fun BytecodePatchContext.stub(name: String): List<Instruction> =
        mutableClassDefBy(LOW_VIEW_REELS).methods.single { it.name == name }.code()

    private fun List<Instruction>.getterLoading(context: BytecodePatchContext, type: String, hash: Int): Boolean {
        val called = mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.single { it.definingClass == type }
        return context.classDefBy(type).methods.single { it.name == called.name && it.parameterTypes.isEmpty() && it.returnType == called.returnType }
            .code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == hash }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.text(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)
        ?.let { "${it.definingClass}->${it.name}()${it.returnType}" }
}

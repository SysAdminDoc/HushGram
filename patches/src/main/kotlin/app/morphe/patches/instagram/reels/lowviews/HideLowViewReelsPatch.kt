/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.lowviews

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.download.throughInterface
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.profile.friendship.FRIENDSHIP_STATUS_KEY
import app.morphe.patches.instagram.profile.friendship.RELATIONSHIP
import app.morphe.patches.instagram.profile.friendship.holdsString
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillReelsItemMedia
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.reels.partnership.filterReelsPage
import app.morphe.patches.instagram.reels.partnership.findReelsPage
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags

private const val PATCH = "Hide reels with few views"
internal const val LOW_VIEW_REELS = "$EXTENSION_PACKAGE/reels/LowViewReels;"
internal const val LOW_VIEW_PAGE_FILTER = "$LOW_VIEW_REELS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

/** The tree-backed class behind [RELATIONSHIP], whose getters are named by the hash of their field. */
internal const val PANDO_RELATIONSHIP = "Lcom/instagram/api/schemas/ImmutablePandoRelationshipInfoDict;"

/** The Media fields holding how many times a reel was played and who posted it, and the relationship's following field. */
internal const val PLAY_COUNT_FIELD = "play_count"
internal const val IG_PLAY_COUNT_FIELD = "ig_play_count"
internal const val OWNER_FIELD = "user"
internal const val FOLLOWING_FIELD = "following"

private const val OBJECT = "Ljava/lang/Object;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val BOOLEAN = "Ljava/lang/Boolean;"

/**
 * Leaves reels with fewer views than a number the user typed out of the Reels tab. Reels from
 * accounts the user follows, and reels the user went to see, stay. In the default selection with its
 * switch off, so nothing changes until the user picks a number. Asked for in #444.
 */
@Suppress("unused")
val hideLowViewReelsPatch = bytecodePatch(
    name = "Hide reels with few views",
    description = "Leaves out reels in the Reels tab that fewer people have watched than a number you pick. " +
        "Accounts you follow stay. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("lowViewReels")
        val page = findReelsPage(PATCH)
        val reads = findLowViewReels()
        hideLowViewReels(page, reads)
        enableStatus("lowViewReels")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The bodies of the extension's three post stubs, written once everything they call is found. */
internal class LowViewReads(val playCount: String, val igPlayCount: String, val followed: String)

/**
 * Checks the extension has the page filter and the four stubs, then finds how a post's views and
 * author are read: Media's play counts, its author, the author's friendship status and the status's
 * following answer, by the name the tree-backed status gives the interface method. Fails before
 * anything changes when any of it can't be told, since without a count there's nothing to compare.
 */
internal fun BytecodePatchContext.findLowViewReels(): LowViewReads {
    val extension = classDefByOrNull(LOW_VIEW_REELS) ?: refuse("the extension has no $LOW_VIEW_REELS")
    if (extension.methods.none { it.isStaticStub("page", listOf("Ljava/util/List;", "Ljava/lang/String;"), "Ljava/util/List;") }) {
        refuse("the extension has no static page(List, String)List")
    }
    listOf("media" to OBJECT, "playCount" to INTEGER, "igPlayCount" to INTEGER, "followed" to BOOLEAN)
        .filter { (stub, returns) -> extension.methods.none { it.isStaticStub(stub, listOf(OBJECT), returns) } }
        .forEach { (stub, returns) -> refuse("the extension has no static $stub(Object)$returns to fill") }

    val plays = pandoGetter(PATCH, MEDIA, PLAY_COUNT_FIELD, INTEGER)
    val igPlays = pandoGetter(PATCH, MEDIA, IG_PLAY_COUNT_FIELD, INTEGER)
    val owner = pandoGetter(PATCH, MEDIA, OWNER_FIELD, USER)
    val userClass = classDefByOrNull(USER) ?: refuse("$USER isn't in this build")
    val friendships = userClass.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == RELATIONSHIP &&
            it.holdsString(FRIENDSHIP_STATUS_KEY)
    }
    val friendship = friendships.singleOrNull()
        ?: refuse("expected one getter in $USER answering a $RELATIONSHIP and loading $FRIENDSHIP_STATUS_KEY, found ${friendships.size}")
    val following = throughInterface(PATCH, RELATIONSHIP, PANDO_RELATIONSHIP, FOLLOWING_FIELD, BOOLEAN)
        .substringAfter("->").substringBefore("()")

    for (type in listOf(MEDIA, USER, RELATIONSHIP)) {
        if (!AccessFlags.PUBLIC.isSet((classDefByOrNull(type) ?: refuse("$type isn't in this build")).accessFlags)) {
            refuse("$type isn't public, so the extension can't reach it")
        }
    }
    listOf(plays, igPlays, owner).forEach { getter ->
        if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("$MEDIA->${getter.name} isn't public")
    }
    if (!AccessFlags.PUBLIC.isSet(friendship.accessFlags)) refuse("$USER->${friendship.name} isn't public")

    return LowViewReads(
        playCount = getterBody(plays.name),
        igPlayCount = getterBody(igPlays.name),
        followed = """
            check-cast p0, $MEDIA
            invoke-virtual { p0 }, $MEDIA->${owner.name}()$USER
            move-result-object v0
            if-eqz v0, :none
            invoke-virtual { v0 }, $USER->${friendship.name}()$RELATIONSHIP
            move-result-object v0
            if-eqz v0, :none
            invoke-interface { v0 }, $RELATIONSHIP->$following()$BOOLEAN
            move-result-object v0
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )
}

/** A stub's body that hands its argument, a Media, to its Integer getter [name] and answers what it reads. */
private fun getterBody(name: String) = """
    check-cast p0, $MEDIA
    invoke-virtual { p0 }, $MEDIA->$name()$INTEGER
    move-result-object p0
    return-object p0
"""

/**
 * Fills the extension's item stub and the three post stubs, then hands each Reels page to
 * [LOW_VIEW_PAGE_FILTER] where the page and its fallback meet.
 */
internal fun BytecodePatchContext.hideLowViewReels(page: ReelsPageSites, reads: LowViewReads) {
    fillReelsItemMedia(LOW_VIEW_REELS, page)
    fillStub(LOW_VIEW_REELS, "playCount", INTEGER, 1, reads.playCount)
    fillStub(LOW_VIEW_REELS, "igPlayCount", INTEGER, 1, reads.igPlayCount)
    fillStub(LOW_VIEW_REELS, "followed", BOOLEAN, 2, reads.followed)
    filterReelsPage(page, LOW_VIEW_PAGE_FILTER)
}

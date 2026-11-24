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
import app.morphe.patches.instagram.feed.likes.LIKE_COUNT_FIELD
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.profile.friendship.FRIENDSHIP_STATUS_KEY
import app.morphe.patches.instagram.profile.friendship.RELATIONSHIP
import app.morphe.patches.instagram.profile.friendship.holdsString
import app.morphe.patches.instagram.reels.live.ReelsItemInserts
import app.morphe.patches.instagram.reels.live.filterReelsItemInserts
import app.morphe.patches.instagram.reels.live.findReelsItemInserts
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillReelsItemMedia
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.reels.partnership.filterReelsPage
import app.morphe.patches.instagram.reels.partnership.findReelsPage
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import app.morphe.patches.instagram.reels.time.TAKEN_AT_FIELD
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags

private const val PATCH = "Hide reels with few views"
internal const val LOW_VIEW_REELS = "$EXTENSION_PACKAGE/reels/LowViewReels;"
internal const val LOW_VIEW_PAGE_FILTER = "$LOW_VIEW_REELS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"
internal const val LOW_VIEW_ADDED_FILTER = "$LOW_VIEW_REELS->added(Ljava/util/List;Ljava/lang/String;I)Ljava/util/List;"

/** The tree-backed class behind [RELATIONSHIP], whose getters are named by the hash of their field. */
internal const val PANDO_RELATIONSHIP = "Lcom/instagram/api/schemas/ImmutablePandoRelationshipInfoDict;"

/** The Media fields holding how many times a reel was played and who posted it, and the relationship's following field. */
internal const val PLAY_COUNT_FIELD = "play_count"
internal const val IG_PLAY_COUNT_FIELD = "ig_play_count"
internal const val VIEW_COUNT_FIELD = "view_count"
internal const val OWNER_FIELD = "user"
internal const val FOLLOWING_FIELD = "following"

/** The Media fields holding a post's comment count and video length, in seconds (#780). */
internal const val COMMENT_COUNT_FIELD = "comment_count"
internal const val VIDEO_DURATION_FIELD = "video_duration"

/** The Media fields holding how many times a post was shared and saved (#780). */
internal const val RESHARE_COUNT_FIELD = "reshare_count"
internal const val SAVE_COUNT_FIELD = "save_count"

private const val OBJECT = "Ljava/lang/Object;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val DOUBLE = "Ljava/lang/Double;"
private const val LONG = "Ljava/lang/Long;"

/**
 * Leaves reels outside numbers the user typed out of the Reels tab: fewer views, likes, comments,
 * shares or saves than a minimum or more than a maximum, fewer likes or comments for each 1,000
 * views, a video shorter or longer than asked, or a post older than a number of days. Instagram 453 sends most Reels tab posts
 * without a view count, so likes judge most of them. Can also put each page's reels newest first, and
 * judge Home's posts by the same numbers. Reels from accounts the user follows, and reels the user went
 * to see, stay. In the default selection with its switch off, so nothing changes until the user picks a
 * number. Asked for in #444 and #780.
 */
@Suppress("unused")
val hideLowViewReelsPatch = bytecodePatch(
    name = "Hide reels with few views",
    description = "Leaves out reels in the Reels tab outside numbers you pick: views, likes, comments, shares, " +
        "saves, likes and comments per 1,000 views, video length and age. Can put reels newest first. Accounts you follow stay. " +
        "Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("lowViewReels")
        val page = findReelsPage(PATCH)
        val reads = findLowViewReels()
        val inserts = findReelsItemInserts(PATCH, LOW_VIEW_REELS)
        hideLowViewReels(page, reads, inserts)
        enableStatus("lowViewReels")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The bodies of the extension's ten post stubs, written once everything they call is found. */
internal class LowViewReads(
    val playCount: String,
    val igPlayCount: String,
    val viewCount: String,
    val likeCount: String,
    val followed: String,
    val commentCount: String,
    val videoLength: String,
    val postedAt: String,
    val shareCount: String,
    val saveCount: String,
)

/**
 * Checks the extension has the page filter and the eleven stubs, then finds how a post's numbers and
 * author are read: Media's play, view, like, comment, share and save counts, its video length and post time, its
 * author, the author's friendship status and the status's following answer, by the name the
 * tree-backed status gives the interface method. Fails before anything changes when any of it can't
 * be told, since without a count there's nothing to compare.
 */
internal fun BytecodePatchContext.findLowViewReels(): LowViewReads {
    val extension = classDefByOrNull(LOW_VIEW_REELS) ?: refuse("the extension has no $LOW_VIEW_REELS")
    if (extension.methods.none { it.isStaticStub("page", listOf("Ljava/util/List;", "Ljava/lang/String;"), "Ljava/util/List;") }) {
        refuse("the extension has no static page(List, String)List")
    }
    listOf(
        "media" to OBJECT, "playCount" to INTEGER, "igPlayCount" to INTEGER, "viewCount" to INTEGER,
        "likeCount" to INTEGER, "followed" to BOOLEAN, "commentCount" to INTEGER, "videoLength" to DOUBLE,
        "postedAt" to LONG, "shareCount" to INTEGER, "saveCount" to INTEGER,
    )
        .filter { (stub, returns) -> extension.methods.none { it.isStaticStub(stub, listOf(OBJECT), returns) } }
        .forEach { (stub, returns) -> refuse("the extension has no static $stub(Object)$returns to fill") }

    val plays = pandoGetter(PATCH, MEDIA, PLAY_COUNT_FIELD, INTEGER)
    val igPlays = pandoGetter(PATCH, MEDIA, IG_PLAY_COUNT_FIELD, INTEGER)
    val views = pandoGetter(PATCH, MEDIA, VIEW_COUNT_FIELD, INTEGER)
    val likes = pandoGetter(PATCH, MEDIA, LIKE_COUNT_FIELD, INTEGER)
    val comments = pandoGetter(PATCH, MEDIA, COMMENT_COUNT_FIELD, INTEGER)
    val length = pandoGetter(PATCH, MEDIA, VIDEO_DURATION_FIELD, DOUBLE)
    val posted = pandoGetter(PATCH, MEDIA, TAKEN_AT_FIELD, LONG)
    val shares = pandoGetter(PATCH, MEDIA, RESHARE_COUNT_FIELD, INTEGER)
    val saves = pandoGetter(PATCH, MEDIA, SAVE_COUNT_FIELD, INTEGER)
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
    listOf(plays, igPlays, views, likes, comments, length, posted, shares, saves, owner).forEach { getter ->
        if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("$MEDIA->${getter.name} isn't public")
    }
    if (!AccessFlags.PUBLIC.isSet(friendship.accessFlags)) refuse("$USER->${friendship.name} isn't public")

    return LowViewReads(
        playCount = getterBody(plays.name),
        igPlayCount = getterBody(igPlays.name),
        viewCount = getterBody(views.name),
        likeCount = getterBody(likes.name),
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
        commentCount = getterBody(comments.name),
        videoLength = getterBody(length.name, DOUBLE),
        postedAt = getterBody(posted.name, LONG),
        shareCount = getterBody(shares.name),
        saveCount = getterBody(saves.name),
    )
}

/** A stub's body that hands its argument, a Media, to its getter [name] answering [returns], and answers what it reads. */
private fun getterBody(name: String, returns: String = INTEGER) = """
    check-cast p0, $MEDIA
    invoke-virtual { p0 }, $MEDIA->$name()$returns
    move-result-object p0
    return-object p0
"""

/**
 * Fills the extension's item stub and the ten post stubs, then hands each Reels page to
 * [LOW_VIEW_PAGE_FILTER] where the page and its fallback meet, and each list the viewer's data source
 * takes in to [LOW_VIEW_ADDED_FILTER] first thing. The page filter keeps a page whole when every reel
 * on it would go, and the data source's filter takes those out once the viewer holds reels (#780).
 */
internal fun BytecodePatchContext.hideLowViewReels(page: ReelsPageSites, reads: LowViewReads, inserts: ReelsItemInserts) {
    fillReelsItemMedia(LOW_VIEW_REELS, page)
    fillStub(LOW_VIEW_REELS, "playCount", INTEGER, 1, reads.playCount)
    fillStub(LOW_VIEW_REELS, "igPlayCount", INTEGER, 1, reads.igPlayCount)
    fillStub(LOW_VIEW_REELS, "viewCount", INTEGER, 1, reads.viewCount)
    fillStub(LOW_VIEW_REELS, "likeCount", INTEGER, 1, reads.likeCount)
    fillStub(LOW_VIEW_REELS, "followed", BOOLEAN, 2, reads.followed)
    fillStub(LOW_VIEW_REELS, "commentCount", INTEGER, 1, reads.commentCount)
    fillStub(LOW_VIEW_REELS, "videoLength", DOUBLE, 1, reads.videoLength)
    fillStub(LOW_VIEW_REELS, "postedAt", LONG, 1, reads.postedAt)
    fillStub(LOW_VIEW_REELS, "shareCount", INTEGER, 1, reads.shareCount)
    fillStub(LOW_VIEW_REELS, "saveCount", INTEGER, 1, reads.saveCount)
    filterReelsPage(page, LOW_VIEW_PAGE_FILTER)
    filterReelsItemInserts(inserts, LOW_VIEW_ADDED_FILTER)
}

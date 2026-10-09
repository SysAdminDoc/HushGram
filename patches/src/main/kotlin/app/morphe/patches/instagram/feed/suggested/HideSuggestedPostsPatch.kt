/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.feed.filterParsedFeedItems
import app.morphe.patches.instagram.feed.home.HomeFeedReads
import app.morphe.patches.instagram.feed.home.findHomeFeedReads
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Hide suggested posts"
internal const val SUGGESTIONS_FILTER =
    "$EXTENSION_PACKAGE/feed/FeedSuggestions;->filter(Ljava/lang/Object;)Ljava/lang/Object;"

/** The feed item kinds of suggested accounts, shops, hashtags and lists, which FeedSuggestions drops. */
internal val ACCOUNT_UNITS = listOf(
    "SUGGESTED_USERS", "SUGGESTED_TOP_ACCOUNTS", "SUGGESTED_PRODUCERS", "SUGGESTED_PRODUCERS_V2",
    "SUGGESTED_CLOSE_FRIENDS", "SUGGESTED_BUSINESSES", "SUGGESTED_SHOPS", "SUGGESTED_HASHTAGS",
    "SUGGESTED_SHAREABLE_LISTS", "FOLLOW_CHAIN_USERS", "TYA_SUGGESTIONS_IN_FEED_UNIT",
)

/** The kind of a single suggested post or reel ("explore_story" in the feed's JSON). */
internal const val SUGGESTED_POST = "EXPLORE_STORY"

/**
 * Threads' units: its posts, and the accounts, communities, live chats, game threads and topics it
 * suggests. The last two are new in Instagram 450.
 */
internal val THREADS_UNITS = listOf(
    "THREADS_IN_FEED_UNIT", "TIFU_IN_EXPLORE", "EOF_TIFU", "KICKSTART_FEED_UNIT",
    "COMMUNITIES_IN_FEED_UNIT", "SMSL_IN_FEED_UNIT", "LIVE_CHAT_IN_FEED_UNIT", "SPORT_GAME_IN_FEED_UNIT",
    "THREADS_IN_FEED_UNIT_MUSE", "VERTICALS_IN_FEED_UNIT",
)

/** The survey Instagram asks you to fill in between posts ("in_feed_survey" in the feed's JSON). */
internal val SURVEY_UNITS = listOf("FEED_SURVEY")

/** The shopping units: products to shop, product picks from a post, and live shopping. */
internal val SHOPPING_UNITS = listOf("SHOPPING_RECOMMENDATION_UNIT", "PRODUCT_PIVOTS", "LIVE_SHOPPING_NETEGO")

@Suppress("unused")
val hideSuggestedPostsPatch = bytecodePatch(
    name = "Hide suggested posts",
    description = "Removes posts from accounts you don't follow, suggested accounts, surveys and shopping rows " +
        "from Home. Posts from accounts you follow stay. Extra switches can also hide all videos, photos or " +
        "carousels. On by default. Turn it off in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch, emptiedFeedEndPatch)

    execute {
        requireStatusMethod(FEED_TYPES_STATUS)
        filterSuggestedFeedItems()
        endFollowingAtItsCard()
        // The post type switches are found whole before their part changes anything, so a build
        // where Home's reads or a post's type moved gets the rest of the patch alone.
        homeFeedTypesOrWarn()?.let {
            it.write()
            enableStatus(FEED_TYPES_STATUS)
        }
        enableStatus("feedSuggestions")
    }
}

internal const val FEED_TYPES_STATUS = "feedTypes"
internal const val FEED_SUGGESTIONS = "$EXTENSION_PACKAGE/feed/FeedSuggestions;"
internal const val HOME_TYPES_FILTER = "$FEED_SUGGESTIONS->homeItem(Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * Hide videos, Hide photos and Hide carousels, found: Home's reads, the feed item's post field, the
 * post's media_type getter and the extension's stub reading them. [write] passes each item Home
 * reads through FeedSuggestions.homeItem and fills the stub.
 */
internal class HomeFeedTypes(
    private val reads: HomeFeedReads,
    private val post: FieldReference,
    private val mediaType: Method,
    private val stub: MutableMethod,
) {
    fun write(): Int {
        // Each way out returns on its own, so p0 never merges an object and an int at one return.
        stub.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${reads.itemType}
                iget-object p0, p0, ${post.definingClass}->${post.name}:${post.type}
                if-nez p0, :post
                const/4 p0, 0x0
                return p0
                :post
                invoke-virtual { p0 }, $MEDIA->${mediaType.name}()${mediaType.returnType}
                move-result-object p0
                if-nez p0, :typed
                const/4 p0, 0x0
                return p0
                :typed
                invoke-virtual { p0 }, Ljava/lang/Integer;->intValue()I
                move-result p0
                return p0
            """,
        )
        return reads.filterWith(HOME_TYPES_FILTER)
    }
}

/**
 * Finds what the post type switches need, or answers null after the patch log says why, and the
 * patch goes in without them. They sit on Home's own reads, as Hide the home feed's filter does,
 * not on the feed item helper, so Explore's chain of posts and the shop and ad feeds keep theirs.
 */
internal fun BytecodePatchContext.homeFeedTypesOrWarn(): HomeFeedTypes? = try {
    val reads = findHomeFeedReads(PATCH)
    val stub = mutableClassDefBy(FEED_SUGGESTIONS).methods.singleOrNull {
        it.name == "mediaType" && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "I" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$PATCH: $FEED_SUGGESTIONS has no static mediaType(Object)I")
    val post = itemPost(reads.itemType)
    val mediaType = pandoGetter(PATCH, MEDIA, "media_type", "Ljava/lang/Integer;")
    if (!AccessFlags.PUBLIC.isSet(classDefBy(MEDIA).accessFlags) || !AccessFlags.PUBLIC.isSet(mediaType.accessFlags)) {
        throw PatchException("$PATCH: $MEDIA->${mediaType.name} isn't public, so the extension can't reach it")
    }
    HomeFeedTypes(reads, post, mediaType, stub)
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. Hide suggested posts goes in without Hide videos, Hide photos and Hide carousels.")
    null
}

/**
 * The field a feed item of [itemType] keeps its post in: the one post field the item's static
 * factory from a post (taking a Media and answering an item) writes. On 450 that's the field the
 * item's parser fills from "media_or_ad", where a post from an account you follow, a suggested
 * post and an ad all come. The item's other post fields hold other units' posts. It has to be a
 * public instance field of a public class, since the extension reads it.
 */
internal fun BytecodePatchContext.itemPost(itemType: String): FieldReference {
    val item = classDefBy(itemType)
    if (!AccessFlags.PUBLIC.isSet(item.accessFlags)) throw PatchException("$PATCH: $itemType isn't public, so the extension can't reach it")
    val factories = item.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == itemType &&
            it.parameterTypes.map(Any::toString) == listOf(MEDIA)
    }
    val factory = factories.singleOrNull()
        ?: throw PatchException("$PATCH: expected one static method of $itemType making one from a post, found ${factories.size}")
    val writes = factory.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.IPUT_OBJECT }
        .map { (it as ReferenceInstruction).reference as FieldReference }
        .filter { it.definingClass == itemType && it.type == MEDIA }
        .distinctBy { it.name }
    val post = writes.singleOrNull()
        ?: throw PatchException("$PATCH: expected $itemType's factory from a post to keep it in one field, found ${writes.map { it.name }}")
    val field = item.fields.singleOrNull { it.name == post.name && it.type == MEDIA }
    if (field == null || !AccessFlags.PUBLIC.isSet(field.accessFlags) || AccessFlags.STATIC.isSet(field.accessFlags)) {
        throw PatchException("$PATCH: $itemType's post field ${post.name} isn't a public instance field")
    }
    return post
}

/**
 * Passes each feed item Instagram's static parse helper answers through FeedSuggestions. On 449
 * that helper reads the home feed's page loads and its cache of recommended posts, and not
 * Explore's grid.
 */
internal fun BytecodePatchContext.filterSuggestedFeedItems() =
    filterParsedFeedItems(PATCH, SUGGESTIONS_FILTER, ACCOUNT_UNITS + SUGGESTED_POST + THREADS_UNITS + SURVEY_UNITS + SHOPPING_UNITS)

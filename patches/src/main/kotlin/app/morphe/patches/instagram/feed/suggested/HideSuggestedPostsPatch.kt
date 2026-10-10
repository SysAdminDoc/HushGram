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
        "from Home. It also takes shop tiles out of Explore and the shopping bag off posts with tagged products. " +
        "Posts from accounts you follow stay. Extra switches can also hide all videos, photos, carousels or posts " +
        "you've liked. On by default. Turn it off in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch, emptiedFeedEndPatch)

    execute {
        requireStatusMethod(FEED_TYPES_STATUS)
        // Found before anything changes, so a build where Explore's sections or the products
        // indicator moved stops the patch with Home's filter not yet written.
        val exploreShops = findExploreShopSections()
        val productTags = findProductTagIndicator()
        filterSuggestedFeedItems()
        endFollowingAtItsCard()
        exploreShops.write(this)
        productTags.write(this)
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

/** First thing in Home's feed response parser: a page of Home's own feed starts on this thread. */
internal const val HOME_PAGE_STARTS = "$FEED_SUGGESTIONS->homePageStarts()V"

/** Right before each return of Home's feed response parser: that page is parsed. */
internal const val HOME_PAGE_PARSED = "$FEED_SUGGESTIONS->homePageParsed()V"

/**
 * Hide videos, Hide photos, Hide carousels and Hide posts you've liked, found: Home's reads, the
 * feed item's post field, the post's media_type and has_liked getters and the extension's stubs
 * reading them. [write] passes each item Home reads through FeedSuggestions.homeItem and fills the
 * stubs. It also marks each page of Home's feed response, so FeedSuggestions can tell a page of
 * Home's own that the suggestion switches emptied from Home's store and other feeds (#105, #28).
 */
internal class HomeFeedTypes(
    private val reads: HomeFeedReads,
    private val post: FieldReference,
    private val mediaType: Method,
    private val stub: MutableMethod,
    private val hasLiked: Method,
    private val likedStub: MutableMethod,
) {
    fun write(): Int {
        fillIntStub(stub, mediaType, "Ljava/lang/Integer;->intValue()I")
        fillIntStub(likedStub, hasLiked, "Ljava/lang/Boolean;->booleanValue()Z")
        val filtered = reads.filterWith(HOME_TYPES_FILTER)
        reads.markPages(HOME_PAGE_STARTS, HOME_PAGE_PARSED)
        return filtered
    }

    /**
     * Fills [target], a static (Object)I stub, to read the item's post and answer [getter]'s boxed
     * value through [unbox], or 0 when the item has no post or the post doesn't say. Each way out
     * returns on its own, so p0 never merges an object and an int at one return.
     */
    private fun fillIntStub(target: MutableMethod, getter: Method, unbox: String) {
        target.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${reads.itemType}
                iget-object p0, p0, ${post.definingClass}->${post.name}:${post.type}
                if-nez p0, :post
                const/4 p0, 0x0
                return p0
                :post
                invoke-virtual { p0 }, $MEDIA->${getter.name}()${getter.returnType}
                move-result-object p0
                if-nez p0, :boxed
                const/4 p0, 0x0
                return p0
                :boxed
                invoke-virtual { p0 }, $unbox
                move-result p0
                return p0
            """,
        )
    }
}

/**
 * Finds what the post type switches need, or answers null after the patch log says why, and the
 * patch goes in without them. They sit on Home's own reads, as Hide the home feed's filter does,
 * not on the feed item helper, so Explore's chain of posts and the shop and ad feeds keep theirs.
 */
internal fun BytecodePatchContext.homeFeedTypesOrWarn(): HomeFeedTypes? = try {
    val reads = findHomeFeedReads(PATCH)
    if (reads.pageReturns() == 0) throw PatchException("$PATCH: Home's feed response parser never returns")
    val stub = intStub("mediaType")
    val likedStub = intStub("liked")
    val post = itemPost(reads.itemType)
    val mediaType = publicMediaGetter("media_type", "Ljava/lang/Integer;")
    val hasLiked = publicMediaGetter("has_liked", "Ljava/lang/Boolean;")
    HomeFeedTypes(reads, post, mediaType, stub, hasLiked, likedStub)
} catch (moved: PatchException) {
    patchLog.warning(
        "${moved.message}. Hide suggested posts goes in without Hide videos, Hide photos, Hide carousels and " +
            "Hide posts you've liked.",
    )
    null
}

/** FeedSuggestions' static (Object)I stub [name], which the patch fills. */
private fun BytecodePatchContext.intStub(name: String): MutableMethod =
    mutableClassDefBy(FEED_SUGGESTIONS).methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "I" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$PATCH: $FEED_SUGGESTIONS has no static $name(Object)I")

/** Media's getter for [field], which the extension calls, so it and Media have to be public. */
private fun BytecodePatchContext.publicMediaGetter(field: String, returns: String): Method {
    val getter = pandoGetter(PATCH, MEDIA, field, returns)
    if (!AccessFlags.PUBLIC.isSet(classDefBy(MEDIA).accessFlags) || !AccessFlags.PUBLIC.isSet(getter.accessFlags)) {
        throw PatchException("$PATCH: $MEDIA->${getter.name} isn't public, so the extension can't reach it")
    }
    return getter
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

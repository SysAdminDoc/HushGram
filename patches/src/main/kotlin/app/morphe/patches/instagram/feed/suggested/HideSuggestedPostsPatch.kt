/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.feed.filterParsedFeedItems
import app.morphe.patches.instagram.feed.home.HomeFeedReads
import app.morphe.patches.instagram.feed.home.findHomeFeedReads
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

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
        "Posts from accounts you follow stay. Extra switches can also hide the 'Are you interested in this post?' " +
        "question on Home and Reels, the caught up card, all videos, photos, carousels, posts " +
        "you've liked or posts from accounts you pick. A post's menu has Hide posts from this account to add one " +
        "to that list, and those posts leave Home on the next refresh. On by default. Turn it off in HushGram " +
        "settings > Feed.",
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
        // Where Home's row builder adds the "Are you interested in this post?" row, found with the rest.
        val interest = findInterestQuestion()
        // And where the feed item binder group adds the same row, which Home can be built with too (#131).
        val groupedInterest = findGroupedInterestQuestion(interest)
        // Reels ask the same question in their footer (#131). A build without it keeps Reels' row.
        val reelsInterest = reelsInterestControlOrWarn()
        // Asking for For you's next page after a page the switches left short (#52), found before the
        // show check changes. A build without it waits for the scroll as Instagram has it.
        val nextPage = homeNextPageOrWarn()
        // The Suggested for you header after Home's end of feed card (#104). A build without it keeps the title.
        val sectionHeader = suggestedSectionHeaderOrWarn()
        // On 451 the end of feed card shows that title itself, over the same section (#104).
        val caughtUpTitle = caughtUpCardTitleOrWarn(sectionHeader)
        // Hide the caught up card folds the same card's row (#307). A build without it keeps the card.
        val caughtUpCard = hideCaughtUpCardOrWarn()
        filterSuggestedFeedItems()
        endFollowingAtItsCard()
        // The Older Posts page's loading row, a separate builder; a build without it keeps Instagram's row.
        endOlderPostsSpinnerOrWarn()
        nextPage?.write(this)
        val itemsFilled = sectionHeader?.write(this) == true
        caughtUpTitle?.write(this, itemsFilled)
        // After the title's hook, which checks the binder's code by index.
        caughtUpCard?.write(this)
        exploreShops.write(this)
        productTags.write(this)
        interest.write(this)
        groupedInterest.write(this)
        reelsInterest?.write(this)
        // The post type switches are found whole before their part changes anything, so a build
        // where Home's reads or a post's type moved gets the rest of the patch alone.
        homeFeedTypesOrWarn()?.let {
            // Found with the rest, before anything is written: the menu row reads the author stub it fills.
            val menuRow = hideAccountRowOrWarn()
            it.write()
            // Hidden accounts tells signed-in accounts apart when Home's cache source is found too.
            // Without it, one list counts for every account.
            homeAccountOrWarn()?.write()
            menuRow?.write()
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
 * Hide videos, Hide photos, Hide carousels, Hide posts you've liked and Hidden accounts, found:
 * Home's reads, the feed item's post field, the suggested post unit's posts when the build has one
 * ([story]), the post's media_type, has_liked and user getters, the user's username getter and the
 * extension's stubs reading them. [write] passes each item Home reads through
 * FeedSuggestions.homeItem and fills the stubs. It also marks each page of Home's feed response, so
 * FeedSuggestions can tell a page of Home's own that the suggestion switches emptied from Home's
 * store and other feeds (#105, #28).
 */
internal class HomeFeedTypes(
    private val reads: HomeFeedReads,
    private val post: FieldReference,
    private val story: ExploreStoryPosts?,
    private val stubs: Map<String, MutableMethod>,
    private val mediaType: Method,
    private val hasLiked: Method,
    private val owner: Method,
    private val username: Method,
    private val postAuthorStub: MutableMethod,
) {
    fun write(): Int {
        fillFieldStub(stubs.getValue("itemPost"), reads.itemType, post)
        story?.let {
            fillFieldStub(stubs.getValue("exploreStory"), reads.itemType, it.story)
            fillFieldStub(stubs.getValue("storyPost"), it.story.type, it.mediaOrAd)
            fillFieldStub(stubs.getValue("storyMedia"), it.story.type, it.media)
        }
        fillIntStub(stubs.getValue("mediaType"), mediaType, "Ljava/lang/Integer;->intValue()I")
        fillIntStub(stubs.getValue("liked"), hasLiked, "Ljava/lang/Boolean;->booleanValue()Z")
        fillPostAuthorStub()
        val filtered = reads.filterWith(HOME_TYPES_FILTER)
        reads.markPages(HOME_PAGE_STARTS, HOME_PAGE_PARSED)
        return filtered
    }

    /** Fills [target], a static (Object)Object stub, to cast p0 to [type] and answer its [field]. */
    private fun fillFieldStub(target: MutableMethod, type: String, field: FieldReference) {
        target.addInstructions(
            0,
            """
                check-cast p0, $type
                iget-object p0, p0, ${field.definingClass}->${field.name}:${field.type}
                return-object p0
            """,
        )
    }

    /**
     * Fills [target], a static (Object)I stub taking a post, to answer [getter]'s boxed value through
     * [unbox], or 0 when the post doesn't say. Each way out returns on its own, so p0 never merges
     * an object and an int at one return.
     */
    private fun fillIntStub(target: MutableMethod, getter: Method, unbox: String) {
        target.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $MEDIA
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

    /**
     * Fills HiddenAccounts.authorOfPost, a static (Object)String stub, to read a Media's user and that
     * user's username, or null when the post has no user. Hide posts from this account reads the
     * post a menu is open on with it. Each way out returns on its own.
     */
    private fun fillPostAuthorStub() {
        postAuthorStub.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $MEDIA
                invoke-virtual { p0 }, $MEDIA->${owner.name}()${owner.returnType}
                move-result-object p0
                if-nez p0, :user
                const/4 p0, 0x0
                return-object p0
                :user
                invoke-virtual { p0 }, $USER->${username.name}()${username.returnType}
                move-result-object p0
                return-object p0
            """,
        )
    }

}

/**
 * A feed item's suggested post unit ("explore_story", which Instagram 451 sends every suggested
 * post on Home as) and the unit's two post fields, its "media_or_ad" and its "media".
 */
internal class ExploreStoryPosts(val story: FieldReference, val mediaOrAd: FieldReference, val media: FieldReference)

/** The constructor of the suggested post unit names its GraphQL type with this string. */
private const val EXPLORE_STORY_TYPE = "XDTExploreStory"

/**
 * The field of [itemType] holding the suggested post unit, the one whose type's constructor names
 * [EXPLORE_STORY_TYPE], and the unit's post fields: the Media field read right before the unit's
 * own map of itself puts it under "media_or_ad", and the one under "media". All of them public, since
 * the extension reads them. A failure names [patch].
 */
internal fun BytecodePatchContext.exploreStoryPosts(itemType: String, patch: String = PATCH): ExploreStoryPosts {
    val fields = classDefBy(itemType).fields.filter { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && classDefByOrNull(field.type)?.methods?.any { method ->
            method.name == "<init>" && method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == EXPLORE_STORY_TYPE
            } == true
        } == true
    }
    val field = fields.singleOrNull()
        ?: throw PatchException("$patch: expected one field of $itemType holding a suggested post unit, found ${fields.map { it.name }}")
    val unit = classDefBy(field.type)
    if (!AccessFlags.PUBLIC.isSet(field.accessFlags) || !AccessFlags.PUBLIC.isSet(unit.accessFlags)) {
        throw PatchException("$patch: the suggested post unit ${field.type} or its field ${field.name} isn't public")
    }
    fun postUnder(key: String): FieldReference {
        // 451 wrote the unit's map in the unit itself. 452 moves it into serializer classes that read the
        // unit's fields, so every class holding the key is read for reads off the unit.
        val found = (listOf(unit) + classesHolding(key)).distinctBy { it.type }.flatMap { it.methods }.flatMap { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            code.indices.filter { ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == key }
                .mapNotNull { at ->
                    (at - 1 downTo maxOf(0, at - 4)).asSequence().map { code[it] }
                        .firstOrNull { it.opcode == Opcode.IGET_OBJECT }
                        ?.let { (it as ReferenceInstruction).reference as FieldReference }
                        ?.takeIf { it.definingClass == unit.type && it.type == MEDIA }
                }
        }.distinctBy { it.name }
        val post = found.singleOrNull()
            ?: throw PatchException("$patch: expected one post field of ${unit.type} under \"$key\", found ${found.map { it.name }}")
        val declared = unit.fields.singleOrNull { it.name == post.name && it.type == MEDIA }
        if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags)) {
            throw PatchException("$patch: ${unit.type}'s post field ${post.name} isn't public")
        }
        return post
    }
    val mediaOrAd = postUnder("media_or_ad")
    val media = postUnder("media")
    if (mediaOrAd.name == media.name) throw PatchException("$patch: ${unit.type} keeps \"media_or_ad\" and \"media\" in one field")
    return ExploreStoryPosts(ImmutableFieldReference(itemType, field.name, field.type), mediaOrAd, media)
}

/**
 * Finds what the post type switches need, or answers null after the patch log says why, and the
 * patch goes in without them. They sit on Home's own reads, as Hide the home feed's filter does,
 * not on the feed item helper, so Explore's chain of posts and the shop and ad feeds keep theirs.
 */
internal fun BytecodePatchContext.homeFeedTypesOrWarn(): HomeFeedTypes? = try {
    val reads = findHomeFeedReads(PATCH)
    if (reads.pageReturns() == 0) throw PatchException("$PATCH: Home's feed response parser never returns")
    val stubs = listOf("itemPost", "exploreStory", "storyPost", "storyMedia").associateWith { staticStub(FEED_SUGGESTIONS, it, OBJECT) } +
        listOf("mediaType", "liked").associateWith { staticStub(FEED_SUGGESTIONS, it, "I") }
    val postAuthorStub = staticStub(HIDDEN_ACCOUNTS, "authorOfPost", STRING)
    val post = itemPost(reads.itemType)
    val mediaType = publicGetter(MEDIA, "media_type", "Ljava/lang/Integer;")
    val hasLiked = publicGetter(MEDIA, "has_liked", "Ljava/lang/Boolean;")
    val owner = publicGetter(MEDIA, "user", USER)
    val username = publicGetter(USER, "username", STRING)
    // Without the suggested post unit the switches still read posts from accounts you follow.
    val story = try {
        exploreStoryPosts(reads.itemType)
    } catch (moved: PatchException) {
        patchLog.warning("${moved.message}. Hide videos, Hide photos, Hide carousels, Hide posts you've liked and Hidden accounts leave suggested posts on Home.")
        null
    }
    HomeFeedTypes(reads, post, story, stubs, mediaType, hasLiked, owner, username, postAuthorStub)
} catch (moved: PatchException) {
    patchLog.warning(
        "${moved.message}. Hide suggested posts goes in without Hide videos, Hide photos, Hide carousels, " +
            "Hide posts you've liked and Hidden accounts.",
    )
    null
}

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/** The extension's static (Object) stub [name] on [owner], answering [returns], which the patch fills. */
internal fun BytecodePatchContext.staticStub(owner: String, name: String, returns: String): MutableMethod =
    mutableClassDefBy(owner).methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$PATCH: $owner has no static $name(Object)$returns")

/** [type]'s getter for [field], which the extension calls, so it and [type] have to be public. */
private fun BytecodePatchContext.publicGetter(type: String, field: String, returns: String): Method {
    val getter = pandoGetter(PATCH, type, field, returns)
    if (!AccessFlags.PUBLIC.isSet(classDefBy(type).accessFlags) || !AccessFlags.PUBLIC.isSet(getter.accessFlags)) {
        throw PatchException("$PATCH: $type->${getter.name} isn't public, so the extension can't reach it")
    }
    return getter
}

/**
 * The field a feed item of [itemType] keeps its post in: the one post field the item's static
 * factory from a post (taking a Media and answering an item) writes. On 450 that's the field the
 * item's parser fills from "media_or_ad", where a post from an account you follow, a suggested
 * post and an ad all come. The item's other post fields hold other units' posts. It has to be a
 * public instance field of a public class, since the extension reads it. A failure names [patch].
 */
internal fun BytecodePatchContext.itemPost(itemType: String, patch: String = PATCH): FieldReference {
    val item = classDefBy(itemType)
    if (!AccessFlags.PUBLIC.isSet(item.accessFlags)) throw PatchException("$patch: $itemType isn't public, so the extension can't reach it")
    val factories = item.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == itemType &&
            it.parameterTypes.map(Any::toString) == listOf(MEDIA)
    }
    val factory = factories.singleOrNull()
        ?: throw PatchException("$patch: expected one static method of $itemType making one from a post, found ${factories.size}")
    val writes = factory.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.IPUT_OBJECT }
        .map { (it as ReferenceInstruction).reference as FieldReference }
        .filter { it.definingClass == itemType && it.type == MEDIA }
        .distinctBy { it.name }
    val post = writes.singleOrNull()
        ?: throw PatchException("$patch: expected $itemType's factory from a post to keep it in one field, found ${writes.map { it.name }}")
    val field = item.fields.singleOrNull { it.name == post.name && it.type == MEDIA }
    if (field == null || !AccessFlags.PUBLIC.isSet(field.accessFlags) || AccessFlags.STATIC.isSet(field.accessFlags)) {
        throw PatchException("$patch: $itemType's post field ${post.name} isn't a public instance field")
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

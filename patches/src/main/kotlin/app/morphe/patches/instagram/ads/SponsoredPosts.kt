/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.feed.filterParsedFeedItems
import app.morphe.patches.instagram.feed.findFeedItemHelper
import app.morphe.patches.instagram.feed.suggested.itemPost
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * The model key of a post's ad details, which a sponsored post carries and no other post does.
 * Instagram's own check of whether a post is sponsored reads this key, and so does its Explore chain
 * before it turns a post into an ad item.
 */
internal const val AD_DETAILS = "injected"

/**
 * The feed item kinds Instagram gives only its own ad units, which Hide ads' filter takes out
 * whatever post they carry. INTENT_AWARE_AD_PIVOT is the row of ads a contextual ad response fills
 * ("intent_aware_ad_pivot" in a page), and Instagram's own check of whether an item is sponsored
 * answers yes for every one. STAND_ALONE_MULTI_AD_PIVOT is a post carrying "multi_ads_info" and a
 * row of other posts ("stand_alone_multi_ad_pivot" in a page), which Instagram shows as that row.
 * Instagram's feed turns only these two kinds into rows of ads (anything else is an "Unsupported
 * multi ad type" there). Neither keeps a post in the field the sponsored check reads, so that
 * check alone never saw them. The item's kind enum has to name both; see [requireOneKindField].
 */
internal val AD_UNITS = listOf("INTENT_AWARE_AD_PIVOT", "STAND_ALONE_MULTI_AD_PIVOT")

/** Hide ads' filter for each item the feed item parse helper answers. */
internal const val FEED_ADS_FILTER = "$ADS->feedItem(Ljava/lang/Object;)Ljava/lang/Object;"

/** The extension's stub answering whether an item's post is sponsored, which [SponsoredPosts.write] fills. */
internal const val SPONSORED_STUB = "sponsored"

/**
 * What Hide ads needs to take sponsored posts out of a feed's own pages, found before anything
 * changes: the feed item's type, the field it keeps its post in, Media's getter for its ad details
 * and the extension's stub that reads them.
 *
 * On 450 an ad the server sends inside a page (Home's Older Posts page had two on the S25) is an
 * ordinary post item, kind MEDIA, whose post has ad details; the feed item parser never makes an
 * item of kind AD, only the app's own insert does, and Hide ads' guard on the insert never sees it.
 * [write] fills the stub and passes every item the parse helper answers through the extension, which
 * answers null for a sponsored one, or one of the [AD_UNITS], while the switch is on. The helper
 * reads Home's pages (Following and Older Posts are pages of Home's response too), Home's store of
 * the last run, Explore's chain of posts and the contextual and shop ad responses, and every caller
 * skips a null item.
 */
internal class SponsoredPosts(
    private val itemType: String,
    private val post: FieldReference,
    private val details: Method,
    private val stub: MutableMethod,
) {
    /**
     * Fills the stub to read the item's post and the post's ad details, answering 1 when there are
     * some and 0 when there aren't or the item has no post. Each way out returns on its own, so p0
     * never merges an object and an int at one return. Then the helper's answer goes through the
     * filter.
     */
    fun write(context: BytecodePatchContext) {
        stub.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $itemType
                iget-object p0, p0, ${post.definingClass}->${post.name}:${post.type}
                if-nez p0, :post
                const/4 p0, 0x0
                return p0
                :post
                invoke-virtual { p0 }, $MEDIA->${details.name}()${details.returnType}
                move-result-object p0
                if-nez p0, :sponsored
                const/4 p0, 0x0
                return p0
                :sponsored
                const/4 p0, 0x1
                return p0
            """,
        )
        context.filterParsedFeedItems(PATCH, FEED_ADS_FILTER, AD_UNITS)
    }
}

/** Finds what [SponsoredPosts] writes, or stops the patch naming what moved. */
internal fun BytecodePatchContext.findSponsoredPosts(): SponsoredPosts {
    val (itemType, _) = findFeedItemHelper(PATCH, AD_UNITS)
    val post = itemPost(itemType, PATCH)
    val details = adDetailsGetter()
    val stub = mutableClassDefBy(ADS).methods.singleOrNull {
        it.name == SPONSORED_STUB && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "I" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$PATCH: $ADS has no static $SPONSORED_STUB(Object)I")
    return SponsoredPosts(itemType, post, details, stub)
}

/**
 * Media's getter for its ad details: the one instance method taking nothing and answering an object
 * that loads the Java hash of [AD_DETAILS], the way Instagram's models read a field from the tree
 * the server sent. Media's boolean checks of the same key answer no object, and its setter takes one,
 * so neither counts. The extension's stub calls it, so Media and the getter have to be public.
 * A patch other than Hide ads names itself in [patch], so a build that differs stops it under its
 * own name.
 */
internal fun BytecodePatchContext.adDetailsGetter(patch: String = PATCH): Method {
    val key = AD_DETAILS.hashCode()
    val getters = classDefBy(MEDIA).methods.filter { method ->
        method.parameterTypes.isEmpty() && method.returnType.startsWith("L") && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation?.instructions?.any { it.loadsLiteral(key) } == true
    }
    val getter = getters.singleOrNull() ?: throw PatchException(
        "$patch: expected one getter on $MEDIA for its ad details ($AD_DETAILS), found " +
            if (getters.isEmpty()) "none" else getters.joinToString { it.name },
    )
    if (!AccessFlags.PUBLIC.isSet(classDefBy(MEDIA).accessFlags) || !AccessFlags.PUBLIC.isSet(getter.accessFlags)) {
        throw PatchException("$patch: $MEDIA->${getter.name} isn't public, so the extension can't reach it")
    }
    return getter
}

private fun Instruction.loadsLiteral(value: Int): Boolean =
    (opcode == Opcode.CONST || opcode == Opcode.CONST_16 || opcode == Opcode.CONST_HIGH16 || opcode == Opcode.CONST_4) &&
        (this as NarrowLiteralInstruction).narrowLiteral == value

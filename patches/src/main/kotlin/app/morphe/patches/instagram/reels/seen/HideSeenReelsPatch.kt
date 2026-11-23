/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.live.ReelsItemInserts
import app.morphe.patches.instagram.reels.live.filterReelsItemInserts
import app.morphe.patches.instagram.reels.live.findReelsItemInserts
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillReelsItemMedia
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.reels.partnership.filterReelsPage
import app.morphe.patches.instagram.reels.partnership.findReelsPage
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import app.morphe.patches.instagram.reels.watchhistory.WatchedReelsRequestFingerprint
import app.morphe.patches.instagram.reels.watchhistory.recordParameters
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide reels you've seen"
internal const val SEEN_REELS = "$EXTENSION_PACKAGE/reels/SeenReels;"
internal const val SEEN_PAGE_FILTER = "$SEEN_REELS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"
internal const val SEEN_WATCHED = "$SEEN_REELS->watched(Ljava/lang/String;)V"
internal const val SEEN_ADDED_FILTER = "$SEEN_REELS->added(Ljava/util/List;Ljava/lang/String;I)Ljava/util/List;"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val MEDIA = "Lcom/instagram/feed/media/Media;"

/** Media's id getter, which Redex keeps by name. */
private const val MEDIA_ID = "$MEDIA->getId()$STRING"

/** The parameters, after the batch, of the record of a reel reached: its media id and the id of the blend it came in. */
internal const val WATCHED_RECORD = "$STRING$STRING"

/**
 * Leaves reels already watched out of the Reels tab when Instagram shows them again (#830). The
 * pending seen-state batch's record of a reel reached hands its media id to the extension first
 * thing, and the Reels viewer's network callback hands each page to the extension, which drops the
 * reels it was told of. Reels a profile or a chat opens stay. Starts off.
 */
@Suppress("unused")
val hideSeenReelsPatch = bytecodePatch(
    name = "Hide reels you've seen",
    description = "Leaves reels you've already watched out of the Reels tab when Instagram shows them again. " +
        "Reels you open from a profile or a chat still play. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("seenReels")
        val page = findReelsPage(PATCH)
        val inserts = findReelsItemInserts(PATCH, SEEN_REELS)
        val record = findWatchedRecord()
        requireSeenStubs()
        hideSeenReels(page, inserts, record)
        enableStatus("seenReels")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The seen-state batch's record of a reel reached: the one method returning nothing beside the
 * request posting clips/write_seen_state/ whose parameters after the batch are a media id and a
 * blend id. Its media id is its first String, p1 whether it's an instance method or a static one
 * taking the batch first. Fails before anything changes when there isn't exactly one.
 */
internal fun BytecodePatchContext.findWatchedRecord(): MutableMethod {
    val request = uniqueMethod(PATCH, "watched reels request", WatchedReelsRequestFingerprint)
    val batch = mutableClassDefBy(request.definingClass)
    val records = batch.methods.filter {
        it.returnType == "V" && it.implementation != null && it.recordParameters(batch.type) == WATCHED_RECORD
    }
    return records.singleOrNull()
        ?: refuse("expected one method ($WATCHED_RECORD)V on ${batch.type}, the watched reel record, found ${records.size}")
}

/** Checks the extension has the page filter, the record and both item stubs before anything changes. */
internal fun BytecodePatchContext.requireSeenStubs() {
    val extension = classDefByOrNull(SEEN_REELS) ?: refuse("the extension has no $SEEN_REELS")
    val stubs = listOf(
        Triple("page", listOf(LIST, STRING), LIST),
        Triple("watched", listOf(STRING), "V"),
        Triple("media", listOf(OBJECT), OBJECT),
        Triple("mediaId", listOf(OBJECT), STRING),
    )
    stubs.forEach { (name, parameters, returns) ->
        if (extension.methods.none { it.isStaticStub(name, parameters, returns) }) {
            refuse("the extension has no static $name(${parameters.joinToString("")})$returns")
        }
    }
    val media = classDefByOrNull(MEDIA) ?: refuse("there's no $MEDIA")
    val getId = media.methods.singleOrNull { it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == STRING }
    if (getId == null || !AccessFlags.PUBLIC.isSet(getId.accessFlags) || AccessFlags.STATIC.isSet(getId.accessFlags)) {
        refuse("$MEDIA has no public getId()")
    }
}

/**
 * Fills the extension's item and media id stubs, hands each Reels page to [SEEN_PAGE_FILTER] where
 * the page and its fallback meet and each list the viewer's data source takes in to
 * [SEEN_ADDED_FILTER] first thing, and tells [SEEN_WATCHED] of each reel reached, first thing in
 * [record]. The page filter keeps a page whole when every reel on it was seen, and the data
 * source's filter takes those out once the viewer holds reels. Don't send reel watch history puts
 * its early return in the same record after this call (see [watchedCallEnd]), so a reel held back
 * from Instagram is still kept here.
 */
internal fun BytecodePatchContext.hideSeenReels(page: ReelsPageSites, inserts: ReelsItemInserts, record: MutableMethod) {
    fillReelsItemMedia(SEEN_REELS, page)
    fillStub(
        SEEN_REELS, "mediaId", STRING, 1,
        """
            check-cast p0, $MEDIA
            invoke-virtual { p0 }, $MEDIA_ID
            move-result-object p0
            return-object p0
        """,
    )
    filterReelsPage(page, SEEN_PAGE_FILTER)
    filterReelsItemInserts(inserts, SEEN_ADDED_FILTER)
    tellWatched(record)
}

/**
 * Hands [record]'s media id, p1 (after this or after the batch a static record takes first), to
 * [SEEN_WATCHED] first thing. Ahead of Don't send reel watch history's early return, whichever
 * patch goes in first.
 */
internal fun tellWatched(record: MutableMethod) {
    record.addInstructions(
        0,
        """
            invoke-static/range { p1 .. p1 }, $SEEN_WATCHED
        """,
    )
}

/**
 * Where an early return put first thing in [record] goes so the call telling the extension of the
 * reel ([SEEN_WATCHED]) still runs ahead of it: just past that call when it leads the method, else 0.
 */
internal fun watchedCallEnd(record: MutableMethod): Int {
    val first = record.implementation?.instructions?.firstOrNull() ?: return 0
    val called = (first as? ReferenceInstruction)?.reference as? MethodReference
    return if (called != null && "${called.definingClass}->${called.name}(${called.parameterTypes.joinToString("")})${called.returnType}" == SEEN_WATCHED) 1 else 0
}

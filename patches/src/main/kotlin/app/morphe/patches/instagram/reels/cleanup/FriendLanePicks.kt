/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillReelsItemMedia
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.reels.partnership.filterReelsPage
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Clean up Reels"

internal const val FRIEND_LANE_PICKS = "$EXTENSION_PACKAGE/reels/FriendLanePicks;"
internal const val FRIEND_PICKS_FILTER = "$FRIEND_LANE_PICKS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

/** What a post says about friends and it, the public interface over Instagram's two models of it. */
internal const val SOCIAL_CONTEXT_INFO = "Lcom/instagram/api/schemas/SocialContextInfoIntf;"

/** The Media fields naming friends on a post, and the JSON key of a social context's faces. */
internal const val SOCIAL_CONTEXT_FIELD = "social_context"
internal const val TOP_LIKERS_FIELD = "top_likers"
internal const val FACEPILE_KEY = "social_context_facepile_users"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"

/** The bodies of the extension's stubs that read the friends named on a post. */
internal class FriendPicksReads(
    val socialContexts: String,
    val faces: String,
    val sourceUser: String,
    val topLikers: String,
    val username: String,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Checks the extension has the page filter and its stubs, then finds how a post's friends are read:
 * Media's social context and top likers and User's username by the hash of their field, and on the
 * social context, whose faces are filled in ahead of time so its getter holds no hash, the source
 * user as the one getter answering a user and the faces as the list getter its writer calls right
 * before writing their key. Fails before anything changes when any of it can't be told.
 */
internal fun BytecodePatchContext.findFriendPicks(): FriendPicksReads {
    val extension = classDefByOrNull(FRIEND_LANE_PICKS) ?: refuse("the extension has no $FRIEND_LANE_PICKS")
    if (extension.methods.none { it.isStaticStub("page", listOf(LIST, STRING), LIST) }) {
        refuse("the extension has no static page(List, String)List")
    }
    listOf(
        "media" to OBJECT, "socialContexts" to LIST, "faces" to LIST, "sourceUser" to OBJECT,
        "topLikers" to LIST, "username" to STRING,
    )
        .filter { (stub, returns) -> extension.methods.none { it.isStaticStub(stub, listOf(OBJECT), returns) } }
        .forEach { (stub, returns) -> refuse("the extension has no static $stub(Object)$returns to fill") }

    val contexts = pandoGetter(PATCH, MEDIA, SOCIAL_CONTEXT_FIELD, LIST)
    val likers = pandoGetter(PATCH, MEDIA, TOP_LIKERS_FIELD, LIST)
    val username = pandoGetter(PATCH, USER, "username", STRING)
    listOf(contexts, likers, username).forEach { getter ->
        if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("${getter.definingClass}->${getter.name} isn't public")
    }
    for (type in listOf(MEDIA, USER)) {
        if (!AccessFlags.PUBLIC.isSet((classDefByOrNull(type) ?: refuse("$type isn't in this build")).accessFlags)) {
            refuse("$type isn't public, so the extension can't reach it")
        }
    }

    val info = classDefByOrNull(SOCIAL_CONTEXT_INFO) ?: refuse("$SOCIAL_CONTEXT_INFO isn't in this build")
    if (!AccessFlags.INTERFACE.isSet(info.accessFlags) || !AccessFlags.PUBLIC.isSet(info.accessFlags)) {
        refuse("$SOCIAL_CONTEXT_INFO is no longer a public interface")
    }
    val sources = info.methods.filter { it.parameterTypes.isEmpty() && it.returnType == USER }
    val source = sources.singleOrNull()
        ?: refuse("expected one getter on $SOCIAL_CONTEXT_INFO answering a user, found ${sources.size}")
    val faces = facesGetter()

    return FriendPicksReads(
        socialContexts = virtualBody(MEDIA, contexts.name, LIST),
        faces = interfaceBody(faces, LIST),
        sourceUser = interfaceBody(source.name, USER),
        topLikers = virtualBody(MEDIA, likers.name, LIST),
        username = virtualBody(USER, username.name, STRING),
    )
}

/** The social context's getter for its faces: the last list getter its writer calls before their key. */
private fun BytecodePatchContext.facesGetter(): String {
    val writers = classesLoadingString(FACEPILE_KEY).flatMap { it.methods }.filter { method ->
        method.parameterTypes.any { it.toString() == SOCIAL_CONTEXT_INFO } && loadsString(method, FACEPILE_KEY)
    }
    val writer = writers.singleOrNull()
        ?: refuse("expected one method writing a $SOCIAL_CONTEXT_INFO with $FACEPILE_KEY, found ${writers.size}")
    return facesCalledIn(writer)
}

private fun BytecodePatchContext.facesCalledIn(writer: Method): String {
    val code = writer.implementation?.instructions?.toList().orEmpty()
    val key = code.indices.firstOrNull { stringLoadedAt(code, it) == FACEPILE_KEY }
        ?: refuse("${writer.definingClass}->${writer.name} doesn't load $FACEPILE_KEY where it can be read")
    val call = (key - 1 downTo 0).asSequence().mapNotNull { at ->
        val instruction = code[at]
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        called?.takeIf {
            instruction.opcode == Opcode.INVOKE_INTERFACE && it.definingClass == SOCIAL_CONTEXT_INFO &&
                it.parameterTypes.isEmpty() && it.returnType == LIST
        }
    }.firstOrNull() ?: refuse("${writer.definingClass}->${writer.name} calls no list getter before $FACEPILE_KEY")
    return call.name
}

/** A stub's body that hands its argument, a [type], to its getter [name] and answers what it reads. */
private fun virtualBody(type: String, name: String, returns: String) = """
    check-cast p0, $type
    invoke-virtual { p0 }, $type->$name()$returns
    move-result-object p0
    return-object p0
"""

/** The same through [SOCIAL_CONTEXT_INFO]. */
private fun interfaceBody(name: String, returns: String) = """
    check-cast p0, $SOCIAL_CONTEXT_INFO
    invoke-interface { p0 }, $SOCIAL_CONTEXT_INFO->$name()$returns
    move-result-object p0
    return-object p0
"""

/** Fills the stubs that read the friends named on a post. */
internal fun BytecodePatchContext.fillFriendPicks(page: ReelsPageSites, reads: FriendPicksReads) {
    fillReelsItemMedia(FRIEND_LANE_PICKS, page)
    fillStub(FRIEND_LANE_PICKS, "socialContexts", LIST, 1, reads.socialContexts)
    fillStub(FRIEND_LANE_PICKS, "faces", LIST, 1, reads.faces)
    fillStub(FRIEND_LANE_PICKS, "sourceUser", OBJECT, 1, reads.sourceUser)
    fillStub(FRIEND_LANE_PICKS, "topLikers", LIST, 1, reads.topLikers)
    fillStub(FRIEND_LANE_PICKS, "username", STRING, 1, reads.username)
}

/** Fills the stubs and hands each Reels page to [FRIEND_PICKS_FILTER] where the page and its fallback meet. */
internal fun BytecodePatchContext.pickFriends(page: ReelsPageSites, reads: FriendPicksReads) {
    fillFriendPicks(page, reads)
    filterReelsPage(page, FRIEND_PICKS_FILTER)
}

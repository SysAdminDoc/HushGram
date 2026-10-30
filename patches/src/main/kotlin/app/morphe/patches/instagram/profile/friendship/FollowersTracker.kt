/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.friendship

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val UNFOLLOWERS = "$EXTENSION_PACKAGE/profile/Unfollowers;"

/** The status of Recent unfollowers (#208), which a build can lack while the Following list switches go in. */
internal const val UNFOLLOWERS_STATUS = "unfollowers"

/** The user model's keys of an account's follower count and its username, which their getters load by hash. */
internal const val FOLLOWER_COUNT = "follower_count"
internal const val USERNAME = "username"

/** The one method Instagram keeps the name of on the follow list's pager, which tells the pager apart. */
internal const val IS_LOADING = "isLoading"

private const val INTEGER = "Ljava/lang/Integer;"

/**
 * What Recent unfollowers reads from a follow list's state holder, the row builder's class: its pager
 * and the pager's answer to whether there are more accounts to load, its account of the list's owner,
 * and the user model's getters of a follower count and a username.
 */
internal class FollowersParts(
    val builder: String,
    val pager: FieldReference,
    val hasMore: String,
    val owner: FieldReference,
    val followerCount: String,
    val username: String,
)

/**
 * Finds, in the class of the follow list's row builder ([findFollowOrder]):
 * - the pager, its one public field whose interface declares [IS_LOADING];
 * - whether it has more to load, the one other `()Z` method of that interface the row builder asks,
 *   which on 451 decides whether a Load more row goes at the end;
 * - the list owner's account, its one public [USER] field.
 *
 * The follower count and the username are the user model's getters that load [FOLLOWER_COUNT] and
 * [USERNAME] ([pandoGetter]). Fails when any of them isn't there, there's more than one, or the
 * extension can't reach it, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findFollowersParts(order: FollowOrder): FollowersParts {
    val builderClass = classDefBy(order.type)
    val builder = builderClass.methods.single {
        it.name == order.name && it.parameterTypes.map(CharSequence::toString) == order.parameters
    }
    val pagers = builderClass.fields.filter { field ->
        if (AccessFlags.STATIC.isSet(field.accessFlags) || !AccessFlags.PUBLIC.isSet(field.accessFlags)) return@filter false
        val pager = classDefByOrNull(field.type) ?: return@filter false
        AccessFlags.INTERFACE.isSet(pager.accessFlags) &&
            pager.methods.any { it.name == IS_LOADING && it.parameterTypes.isEmpty() && it.returnType == "Z" }
    }
    val pager = pagers.singleOrNull()
        ?: refuse("expected ${order.type} to keep one public pager declaring $IS_LOADING(), found ${pagers.size}")
    val pagerClass = classDefBy(pager.type)
    if (!AccessFlags.PUBLIC.isSet(pagerClass.accessFlags)) refuse("${pager.type} isn't public, so the extension can't reach it")

    val asked = builder.implementation!!.instructions.mapNotNull { instruction ->
        val called = instruction.methodReference() ?: return@mapNotNull null
        val onPager = (instruction.opcode == Opcode.INVOKE_INTERFACE || instruction.opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
            called.definingClass == pager.type && called.parameterTypes.isEmpty() && called.returnType == "Z"
        if (onPager && called.name != IS_LOADING) called.name else null
    }.distinct()
    val hasMore = asked.singleOrNull()
        ?: refuse("expected ${order.type}->${order.name} to ask its pager one question, found $asked")

    val owner = builderClass.instanceFields(USER).singleOrNull()
        ?: refuse("expected ${order.type} to keep one public $USER, the list's owner")
    val count = pandoGetter(PATCH, USER, FOLLOWER_COUNT, INTEGER)
    val username = pandoGetter(PATCH, USER, USERNAME, STRING)
    val userClass = classDefBy(USER)
    if (!AccessFlags.PUBLIC.isSet(userClass.accessFlags)) refuse("$USER isn't public, so the extension can't reach it")
    listOf(count, username).forEach { getter ->
        if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("$USER->${getter.name} isn't public")
    }

    return FollowersParts(order.type, pager, hasMore, owner, count.name, username.name)
}

/**
 * The parts of Recent unfollowers, or null with a warning in the patch log when this build's list
 * doesn't match. It's a switch of its own, off to start, so a list that moved leaves it out rather
 * than the Following list switches or the profile label; settings then don't offer it.
 */
internal fun BytecodePatchContext.followersPartsOrWarn(order: FollowOrder): FollowersParts? = try {
    findFollowersParts(order)
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. The Following list switches go in without Recent unfollowers.")
    null
}

/** The stubs of Recent unfollowers, found before anything changes, and the step that fills them. */
internal class FollowersStubs(
    private val listHasMore: MutableMethod,
    private val listOwner: MutableMethod,
    private val followerCount: MutableMethod,
    private val username: MutableMethod,
) {
    fun fill(found: FollowersParts) {
        // Each way out of a stub answering a Boolean returns on its own (see FriendshipStubs).
        listHasMore.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${found.builder}
                iget-object p0, p0, ${found.pager}
                if-nez p0, :paged
                const/4 p0, 0x0
                return-object p0
                :paged
                invoke-interface { p0 }, ${found.pager.type}->${found.hasMore}()Z
                move-result p0
                invoke-static { p0 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                move-result-object p0
                return-object p0
            """,
        )
        // Answers an Object.
        listOwner.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${found.builder}
                iget-object p0, p0, ${found.owner}
                return-object p0
            """,
        )
        followerCount.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $USER
                invoke-virtual { p0 }, $USER->${found.followerCount}()$INTEGER
                move-result-object p0
                return-object p0
            """,
        )
        username.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $USER
                invoke-virtual { p0 }, $USER->${found.username}()$STRING
                move-result-object p0
                return-object p0
            """,
        )
    }
}

internal fun BytecodePatchContext.followersStubs(): FollowersStubs {
    val extension = mutableClassDefBy(UNFOLLOWERS)
    fun stub(name: String, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf(OBJECT)
    } ?: refuse("$UNFOLLOWERS has no static $returns $name($OBJECT)")

    return FollowersStubs(
        listHasMore = stub("listHasMore", "Ljava/lang/Boolean;"),
        listOwner = stub("listOwner", OBJECT),
        followerCount = stub("followerCount", INTEGER),
        username = stub("username", STRING),
    )
}

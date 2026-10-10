/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

internal const val HIDDEN_ACCOUNTS = "$EXTENSION_PACKAGE/feed/HiddenAccounts;"

/** First thing in the constructor of Home's cache source: the session it's made for. */
internal const val HOME_SESSION = "$HIDDEN_ACCOUNTS->homeSession(Ljava/lang/Object;)V"

/**
 * Home's cache source, which Instagram makes once for each signed-in session. It keeps its name in
 * every 450 build, and so does the session's getUserId().
 */
internal const val MAIN_FEED_CACHE_SOURCE = "Lcom/instagram/mainfeed/network/MainFeedCacheDataSource;"
private const val SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * Hidden accounts' account hook, found: the cache source's one constructor taking a boolean and the
 * session, the session's public getUserId(), and HiddenAccounts.accountOf, the stub reading it.
 * [write] fills the stub and hands the session over first thing in the constructor, so the list
 * Home leaves out is the signed-in account's own.
 */
internal class HomeAccount(
    private val constructor: MutableMethod,
    private val userId: Method,
    private val stub: MutableMethod,
) {
    fun write() {
        stub.addInstructions(
            0,
            """
                check-cast p0, $SESSION
                invoke-virtual { p0 }, $SESSION->${userId.name}()${userId.returnType}
                move-result-object p0
                return-object p0
            """,
        )
        // The session is the last parameter, so its register is the method's last, past what a
        // plain invoke can name. Put first, before the parent constructor runs, it's still the caller's.
        val session = constructor.implementation!!.registerCount - 1
        constructor.addInstructions(0, "invoke-static/range { v$session .. v$session }, $HOME_SESSION")
    }
}

/**
 * Finds the account hook, or answers null after the patch log says why. Hidden accounts then keeps
 * one list for every account, and the rest of the patch goes in as it is.
 */
internal fun BytecodePatchContext.homeAccountOrWarn(): HomeAccount? = try {
    classDefByOrNull(MAIN_FEED_CACHE_SOURCE) ?: throw PatchException("Hidden accounts: Instagram has no $MAIN_FEED_CACHE_SOURCE")
    val constructors = mutableClassDefBy(MAIN_FEED_CACHE_SOURCE).methods.filter {
        it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf("Z", SESSION)
    }
    val constructor = constructors.singleOrNull()
        ?: throw PatchException("Hidden accounts: expected one constructor of $MAIN_FEED_CACHE_SOURCE taking (boolean, UserSession), found ${constructors.size}")
    if (constructor.implementation == null) throw PatchException("Hidden accounts: $MAIN_FEED_CACHE_SOURCE's constructor has no code")
    val session = classDefByOrNull(SESSION) ?: throw PatchException("Hidden accounts: Instagram has no $SESSION")
    if (!AccessFlags.PUBLIC.isSet(session.accessFlags)) throw PatchException("Hidden accounts: $SESSION isn't public")
    val userId = session.methods.singleOrNull {
        it.name == "getUserId" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: throw PatchException("Hidden accounts: $SESSION has no public getUserId()")
    HomeAccount(constructor, userId, staticStub(HIDDEN_ACCOUNTS, "accountOf", "Ljava/lang/String;"))
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. Hidden accounts keeps one list for every account you sign in to.")
    null
}

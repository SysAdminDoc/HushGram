/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.direct.seen.AccountIdBridge
import app.morphe.patches.instagram.direct.seen.USER_SESSION
import app.morphe.patches.instagram.direct.seen.findAccountId
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val CHAT_ACCOUNTS = "$EXTENSION_PACKAGE/direct/ChatAccounts;"
internal const val SCREEN_RESUMED = "$CHAT_ACCOUNTS->resumed(Ljava/lang/Object;)V"

/** What Instagram's base screen logs first in onResume, which every one of its screens runs. */
internal const val SCREEN_RESUME = "IgFragment.onResume"

/** The name Instagram's base screen keeps for the call answering the session it's signed in with. */
internal const val GET_SESSION = "getSession"

private const val SESSION_BRIDGE = "session"
private const val OBJECT = "Ljava/lang/Object;"

/** Instagram's base screen's onResume: the one method logging [SCREEN_RESUME]. */
internal object ScreenResumeFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string(SCREEN_RESUME)),
    custom = { method, _ -> method.name == "onResume" && method.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * What tells the lists of locked and hidden chats which account is in front, all proved before
 * anything changes: Instagram's base screen's onResume, the extension's session bridge with the body
 * that reads the screen's own session, and the account id bridge, which "Read messages without the
 * seen receipt" may write too.
 */
internal class ChatAccountTargets(
    val resume: MutableMethod,
    val bridge: MutableMethod,
    val bridgeBody: String,
    val accountId: AccountIdBridge,
)

private fun refuse(why: String): Nothing = throw PatchException("$LOCK_PATCH: $why")

/**
 * Instagram's base screen, found by what its onResume logs, and its getSession(), which must answer
 * a type a signed-in session is, so the account id bridge can read the id off it. A build whose base
 * screen moved, or whose session isn't one, fails here.
 */
internal fun BytecodePatchContext.findChatAccountTargets(): ChatAccountTargets {
    val resume = uniqueMethod(LOCK_PATCH, "screen onResume", ScreenResumeFingerprint)
    if (0 in resume.jumpTargets()) refuse("something jumps back to the screen's onResume first instruction")
    val screen = resume.definingClass
    val screenClass = classDefByOrNull(screen) ?: refuse("$screen is missing")
    val getter = screenClass.methods.filter {
        it.name == GET_SESSION && it.parameterTypes.isEmpty() && it.returnType.startsWith("L") && !AccessFlags.STATIC.isSet(it.accessFlags)
    }.one("session $screen answers")
    val session = getter.returnType
    if (!signedInIs(session)) refuse("$screen answers a $session, which $USER_SESSION isn't")
    val call = ImmutableMethodReference(screen, GET_SESSION, emptyList(), session)
    requirePublic(screen, null)
    requirePublic(screen, call)

    val extension = classDefByOrNull(CHAT_ACCOUNTS) ?: refuse("the extension has no $CHAT_ACCOUNTS")
    if (extension.methods.none {
            it.name == "resumed" && it.parameters() == listOf(OBJECT) && it.returnType == "V" &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
        }
    ) refuse("the extension has no public static $SCREEN_RESUMED")
    val bridge = mutableClassDefBy(extension).methods.filter {
        it.name == SESSION_BRIDGE && it.parameters() == listOf(OBJECT) && it.returnType == OBJECT &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }.one("extension's screen session bridge")
    // Answers an Object: the screen's session, as its own getSession() answers it.
    val bridgeBody = """
        check-cast p0, $screen
        invoke-virtual { p0 }, ${call.signature()}
        move-result-object p0
        return-object p0
    """.trimIndent()
    val accountId = findAccountId(LOCK_PATCH)
    return ChatAccountTargets(resume, bridge, bridgeBody, accountId)
}

/** [type] is the signed-in session class or one of the classes it extends. */
private fun BytecodePatchContext.signedInIs(type: String): Boolean {
    var at: String? = USER_SESSION
    while (at != null) {
        if (at == type) return true
        at = classDefByOrNull(at)?.superclass
    }
    return false
}

/**
 * Writes both bridges and has every screen tell the extension it came to the front, first thing in
 * Instagram's base screen's onResume, so the account in front is known before the screen draws.
 */
internal fun hookChatAccounts(targets: ChatAccountTargets) {
    targets.bridge.addInstructions(0, targets.bridgeBody)
    targets.accountId.write()
    targets.resume.addInstructions(0, "invoke-static/range { p0 .. p0 }, $SCREEN_RESUMED")
}

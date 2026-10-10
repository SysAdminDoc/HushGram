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
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val HIDDEN_ACCOUNTS = "$EXTENSION_PACKAGE/feed/HiddenAccounts;"

/** First thing in the constructor of Home's cache source: the session it's made for. */
internal const val HOME_SESSION = "$HIDDEN_ACCOUNTS->homeSession(Ljava/lang/Object;)V"

/**
 * Home's cache source, which Instagram makes once for each signed-in session. It keeps its name in
 * every 450 build, and so does the session's getUserId().
 */
internal const val MAIN_FEED_CACHE_SOURCE = "Lcom/instagram/mainfeed/network/MainFeedCacheDataSource;"
private const val SESSION = "Lcom/instagram/common/session/UserSession;"

/** The two strings of the cache source's start method, the only method of the class holding both. */
internal const val START_TRACE = "MainFeedCacheDataSource.start"
internal const val START_LOAD = "feed_schedule_initial_cache_load"

/**
 * Where Home's cache source starts: its instance method, taking one object and answering void,
 * that [start] is found by, and the field the source keeps its session in. Instagram makes one
 * source for each session and keeps it, so the constructor runs once for an account and a switch
 * back to it never reaches it again, while Home's controller starts the source each time it comes up.
 */
internal class HomeStart(val start: MutableMethod, val sessionField: FieldReference)

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
    private val start: HomeStart? = null,
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
        start?.let { found ->
            // Nothing is live at the first instruction but the parameters, so v0 is free to carry the session.
            found.start.addInstructions(
                0,
                """
                    iget-object v0, p0, ${found.sessionField.definingClass}->${found.sessionField.name}:$SESSION
                    invoke-static { v0 }, $HOME_SESSION
                """,
            )
        }
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
    HomeAccount(constructor, userId, staticStub(HIDDEN_ACCOUNTS, "accountOf", "Ljava/lang/String;"), homeStartOrWarn())
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. Hidden accounts keeps one list for every account you sign in to.")
    null
}

/**
 * Finds the cache source's start method and its session field, or answers null after the patch log
 * says why. Hidden accounts then learns the account from the constructor alone, which runs once for
 * each account, so a switch back to an account you used before keeps the list of the one you left.
 *
 * The method is the one instance method of the source taking one object, answering void and holding
 * both strings of the contract rule. Its first register has to be one a plain iget-object can name
 * and it has to have a local to carry the session in. The field is the source's one instance field
 * of the session's type, which its constructor writes.
 */
internal fun BytecodePatchContext.homeStartOrWarn(): HomeStart? = try {
    val source = mutableClassDefBy(MAIN_FEED_CACHE_SOURCE)
    val starts = source.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.name != "<init>" && method.returnType == "V" &&
            method.parameterTypes.size == 1 && method.parameterTypes.first().startsWith("L") &&
            method.holdsStrings(START_TRACE, START_LOAD)
    }
    val start = starts.singleOrNull()
        ?: throw PatchException("Hidden accounts: expected one start method of $MAIN_FEED_CACHE_SOURCE, found ${starts.size}")
    val code = start.implementation ?: throw PatchException("Hidden accounts: $MAIN_FEED_CACHE_SOURCE's start has no code")
    val self = code.registerCount - 2
    if (self < 1 || self > 15) {
        throw PatchException("Hidden accounts: $MAIN_FEED_CACHE_SOURCE's start keeps its source in register $self, which can't be read plainly or has no local beside it")
    }
    val fields = source.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == SESSION }
    val field = fields.singleOrNull()
        ?: throw PatchException("Hidden accounts: expected one session field in $MAIN_FEED_CACHE_SOURCE, found ${fields.map { it.name }}")
    val written = source.methods.filter { it.name == "<init>" }.any { constructor ->
        constructor.implementation?.instructions?.any {
            it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as? FieldReference)?.let { ref ->
                ref.definingClass == MAIN_FEED_CACHE_SOURCE && ref.name == field.name && ref.type == SESSION
            } == true
        } == true
    }
    if (!written) throw PatchException("Hidden accounts: $MAIN_FEED_CACHE_SOURCE's constructor never writes ${field.name}")
    HomeStart(start, fieldReference(field.name))
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. Hidden accounts won't follow a switch back to an account you used before.")
    null
}

private fun fieldReference(name: String): FieldReference =
    com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference(MAIN_FEED_CACHE_SOURCE, name, SESSION)

private fun Method.holdsStrings(vararg wanted: String): Boolean {
    val held = implementation?.instructions?.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
        else ((instruction as ReferenceInstruction).reference as? StringReference)?.string
    }.orEmpty().toSet()
    return wanted.all { it in held }
}

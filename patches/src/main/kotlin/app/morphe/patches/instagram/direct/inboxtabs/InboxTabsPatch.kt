/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.inboxtabs

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagRead
import app.morphe.patches.instagram.misc.flags.answerFlagReads
import app.morphe.patches.instagram.misc.flags.findFlagReads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PATCH = "Primary and General tabs"
internal const val INBOX_TABS_STATUS = "inboxTabs"
internal const val INBOX_TABS = "$EXTENSION_PACKAGE/direct/InboxTabs;->tabs(I)Z"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * The server test that gives an account the Primary and General tabs in Direct (Instagram's
 * "twinbox", which professional accounts get). On 457 it's read ten times, the same on every
 * build: the gate your messages ask (a static `(UserSession)Z` that returns the read as it is),
 * the twinbox state holder's setup, the folder queries, the main activity's start-up and the
 * smaller gates built on it, each of those ANDing it with its own sub-test that keeps the
 * server's answer.
 *
 * Its companion 0x81064000421ecc is left alone: every reader of it is about where message
 * requests go once twinbox is on (an inbox_requests tab, requests_hidden, the requests filter),
 * and the plain twinbox layout is the one where it reads false, which is what Instagram answers
 * an account that isn't in that test.
 *
 * One read takes the id another read in the same method loaded: 00hU.A00 on 457 reads it first,
 * goes on to the end on a yes, and reads it again only on a no. With the switch on the first read
 * answers yes, so the second can't run. With it off both keep Instagram's answer.
 */
internal const val INBOX_TABS_FLAG = 0x81064000001ec6L

/**
 * Primary and General tabs (#832). Instagram already draws the tabs for the accounts in its test,
 * so the patch only answers the test's reads through [INBOX_TABS]: yes while the switch is on,
 * Instagram's own answer otherwise. Which chats land in Primary or General stays the server's
 * call, so a personal account may get every chat in Primary until one is moved.
 */
@Suppress("unused")
val inboxTabsPatch = bytecodePatch(
    name = "Primary and General tabs",
    description = "Adds the Primary and General tabs professional accounts get to your messages, for any " +
        "account. Experimental. It takes a restart. Starts off. Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(INBOX_TABS_STATUS)
        inboxTabs()
        enableStatus(INBOX_TABS_STATUS)
    }
}

/** Answers every read of [INBOX_TABS_FLAG] through [INBOX_TABS]. */
internal fun BytecodePatchContext.inboxTabs() {
    answerFlagReads(findInboxTabs(), INBOX_TABS)
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Every read of [INBOX_TABS_FLAG], one of them the gate your messages ask: a static method taking
 * the session that returns the read as it is. Fails before anything changes when that isn't so,
 * since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findInboxTabs(): List<FlagRead> {
    val reads = findFlagReads(PATCH, listOf(INBOX_TABS_FLAG))
    val gates = reads.filter { isGate(it) }
    if (gates.size != 1) {
        refuse(
            "expected one static (UserSession)Z returning ${INBOX_TABS_FLAG.toString(16)} as it reads it, found " +
                "${gates.size} among ${reads.map { "${it.type}->${it.name}" }}",
        )
    }
    return reads
}

/** Whether [read] is in a static `(UserSession)Z` that returns its answer straight away. */
private fun BytecodePatchContext.isGate(read: FlagRead): Boolean {
    if (read.parameters != listOf(USER_SESSION) || read.returnType != "Z") return false
    val method = classDefBy(read.type).methods.single {
        it.name == read.name && it.returnType == read.returnType &&
            it.parameterTypes.map(CharSequence::toString) == read.parameters
    }
    if (!AccessFlags.STATIC.isSet(method.accessFlags)) return false
    val next = method.implementation?.instructions?.elementAtOrNull(read.moveResult + 1) ?: return false
    return next.opcode == Opcode.RETURN && (next as OneRegisterInstruction).registerA == read.register
}

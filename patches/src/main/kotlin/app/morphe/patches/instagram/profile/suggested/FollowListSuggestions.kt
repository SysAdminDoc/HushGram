/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide suggested people on profiles"

internal const val FOLLOW_LIST = "$PROFILE_SUGGESTIONS->followList(I)I"
internal const val SUGGESTED_TAB = "$PROFILE_SUGGESTIONS->suggestedTab(I)I"
internal const val EXCLUDE_REQUEST_SUGGESTIONS = "$PROFILE_SUGGESTIONS->excludeRequestSuggestions(I)Z"
internal const val REQUEST_SUGGESTIONS_EMPTY = "$PROFILE_SUGGESTIONS->requestSuggestionsEmpty(I)Z"
internal const val SUGGESTED_SUBSCRIPTIONS = "$PROFILE_SUGGESTIONS->suggestedSubscriptions(Ljava/util/List;)Ljava/util/List;"
internal const val MORE_SUGGESTED_SUBSCRIPTIONS = "$PROFILE_SUGGESTIONS->moreSuggestedSubscriptions(I)Z"
internal const val FRIENDING_BUTTON = "$PROFILE_SUGGESTIONS->friendingButton(I)I"
internal const val FRIENDING_CENTER_MODE = "$PROFILE_SUGGESTIONS->friendingCenterMode(Ljava/lang/String;)V"
internal const val FRIENDING_UNIT = "$PROFILE_SUGGESTIONS->friendingUnit(Ljava/lang/String;)Ljava/lang/String;"

/**
 * The key the friending center's list gives the header it shows on a Follow requests page with no
 * requests, and the name that page and its unit of requests go by (#814). Instagram keeps both: the
 * key is what the list tells rows apart by, and the name is the server's and the notification route's.
 * Of the methods loading the key, only the list loads the name too.
 */
internal const val EMPTY_REQUESTS_HEADER = "item_key_empty_follow_requests_header"
internal const val FOLLOW_REQUESTS_PAGE = "follow_requests"

/**
 * The requests the Subscriptions tab of your own follow lists makes for its Suggested subscriptions
 * (#539): creators you follow that you could subscribe to, a page at a time, and creators you don't
 * follow. Instagram keeps both paths, misspelling and all, since they're the server's.
 */
internal const val FOLLOWED_CREATORS_TO_SUBSCRIBE = "api/v1/fan_club/followed_creators_to_susbcribe_to/"
internal const val UNCONNECTED_CREATORS_TO_SUBSCRIBE = "api/v1/fan_club/unconnected_creators_to_susbcribe_to/"

/**
 * What the follow lists' action bar names once it has added the Discover people button on your own
 * lists (#539): the logger it checks it has, and where it says the button was seen. Of the
 * follow lists pager's methods, only the bar loads both.
 */
internal const val FRIENDING_LOGGER = "friendingCenterEntryPointLogger"
internal const val SELF_FOLLOW_LISTS = "self_follow_lists"

/**
 * The variables of the request that loads the Follow requests screen (#441). Instagram keeps them:
 * they're the names the server's query takes. Only that request's builder loads either one.
 */
internal const val EXCLUDE_SUGGESTED_USERS = "exclude_suggested_users"
internal const val DEFER_SUGGESTED_USERS = "defer_suggested_users"

/**
 * The keys the Follow requests screen's list gives each suggested person's row under the requests, and
 * each request's row (#814). Instagram keeps them: they're what the list tells its rows apart by. A
 * suggested person's row model loads the first too, so the list is the one method loading both.
 */
internal const val SUGGESTED_USER_ROW = "item_key_suggested_user_%s"
internal const val FOLLOW_REQUEST_ROW = "item_key_follow_request_%s"

/**
 * The argument the followers and following list reads to know whether to fetch the accounts it
 * suggests under the list. Instagram keeps the key: it's the name of the fragment's argument.
 */
internal const val FETCH_SUGGESTED = "FollowListFragment.ShouldFetchSuggestedUsers"

/**
 * The argument the pager over someone else's follow lists (mutual, followers, following) reads to
 * know whether to add its Suggested tab (#302). Instagram keeps this key too.
 */
internal const val HAS_SIMILAR_ACCOUNTS = "UnifiedFollowFragment.HAS_SIMILAR_ACCOUNTS"

/** The reads of a boolean argument the follow lists make, either spelling of it, with a default or without. */
private val GET_BOOLEAN = setOf(
    "Landroid/os/BaseBundle;->getBoolean(Ljava/lang/String;Z)Z",
    "Landroid/os/Bundle;->getBoolean(Ljava/lang/String;Z)Z",
    "Landroid/os/BaseBundle;->getBoolean(Ljava/lang/String;)Z",
    "Landroid/os/Bundle;->getBoolean(Ljava/lang/String;)Z",
)

/** A follow list's read of a boolean argument: its method, and the move-result holding the answer. */
internal class FollowListSite(val type: String, val name: String, val parameters: List<String>, val result: Int, val register: Int)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the one place that reads [FETCH_SUGGESTED] out of a fragment's arguments: the key loaded
 * (or asked of Redex's string pool, which differs by build), the next call a `getBoolean` handed
 * that key, and its move-result right after. Putting the key into arguments is a `putBoolean`, so
 * the places opening the list don't count. Fails before anything changes when there isn't exactly
 * one such read, or something jumps past its answer, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findFollowListSuggestions(): FollowListSite = findArgumentRead(FETCH_SUGGESTED)

/**
 * Finds the pager's one read of [HAS_SIMILAR_ACCOUNTS] the way [findFollowListSuggestions] finds
 * its key. The places opening the pager put the key or ask whether it's there, and neither counts.
 */
internal fun BytecodePatchContext.findSuggestedTab(): FollowListSite = findArgumentRead(HAS_SIMILAR_ACCOUNTS)

/** [findSuggestedTab], or null with a warning, so the patch's other parts still go in. */
internal fun BytecodePatchContext.findSuggestedTabOrWarn(): FollowListSite? = try {
    findSuggestedTab()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without hiding the Suggested tab on other people's follow lists.")
    null
}

private fun BytecodePatchContext.findArgumentRead(argument: String): FollowListSite {
    val reads = mutableListOf<FollowListSite>()
    classesLoadingString(argument).forEach { classDef ->
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            code.indices.filter { stringLoadedAt(code, it) == argument }.forEach { at ->
                val key = keyRegister(code, at) ?: return@forEach
                val call = (at + 1 until code.size).firstOrNull { code[it].methodReference() != null } ?: return@forEach
                if (code[call].methodReference().toString() !in GET_BOOLEAN) return@forEach
                if ((code[call] as? FiveRegisterInstruction)?.registerD != key) return@forEach
                val result = code.getOrNull(call + 1)
                if (result?.opcode != Opcode.MOVE_RESULT) refuse("${classDef.type}->${method.name} reads $argument into nothing")
                if (call + 2 in method.jumpTargets()) refuse("something in ${classDef.type}->${method.name} jumps past its read of $argument")
                reads += FollowListSite(
                    classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), call + 1,
                    (result as OneRegisterInstruction).registerA,
                )
            }
        }
    }
    return reads.singleOrNull() ?: refuse("expected one read of the follow list's $argument, found ${reads.size}")
}

/**
 * Hands the follow list's answer to [FOLLOW_LIST] right after its move-result and puts the
 * extension's answer back in the same register. A 0 means the list never asks for suggestions, so
 * no Suggested for you section is fetched or drawn under it.
 */
internal fun BytecodePatchContext.hideFollowListSuggestions(site: FollowListSite) = answer(site, FOLLOW_LIST)

/**
 * Hands the pager's answer to [SUGGESTED_TAB] the same way. A 0 means the pager never adds the
 * Suggested tab, and a list opened on that tab starts on its first tab, as Instagram does when the
 * tab it was asked for isn't there.
 */
internal fun BytecodePatchContext.hideSuggestedTab(site: FollowListSite) = answer(site, SUGGESTED_TAB)

private fun BytecodePatchContext.answer(site: FollowListSite, hook: String) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructions(
        site.result + 1,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $hook
            move-result v${site.register}
        """,
    )
}

/** The register holding the key loaded at [at]: a const-string's, or the move-result after a pool's answer. */
private fun keyRegister(code: List<Instruction>, at: Int): Int? = when (code[at].opcode) {
    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> (code[at] as OneRegisterInstruction).registerA
    else -> code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT }?.let { (it as OneRegisterInstruction).registerA }
}

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The request builder's boolean parameter that becomes [EXCLUDE_SUGGESTED_USERS]: its method and the register holding the parameter. */
internal class RequestSuggestionsSite(val type: String, val name: String, val parameters: List<String>, val register: Int)

/**
 * Finds the one method that builds the Follow requests screen's request: it loads both
 * [EXCLUDE_SUGGESTED_USERS] and [DEFER_SUGGESTED_USERS], and after the first key it hands a boolean to
 * the variable builder, a boolean that was copied there straight from one of the method's own boolean
 * parameters. That parameter's register is the site. Fails before anything changes when there isn't
 * exactly one such method, the copy isn't a plain move of a parameter, or something jumps to the
 * method's first instruction, where the hook goes.
 */
internal fun BytecodePatchContext.findRequestSuggestions(): RequestSuggestionsSite {
    val builders = classesLoadingString(EXCLUDE_SUGGESTED_USERS).flatMap { classDef ->
        classDef.methods.filter { method ->
            val code = method.implementation?.instructions?.toList() ?: return@filter false
            code.indices.any { stringLoadedAt(code, it) == EXCLUDE_SUGGESTED_USERS } &&
                code.indices.any { stringLoadedAt(code, it) == DEFER_SUGGESTED_USERS }
        }
    }
    val method = builders.singleOrNull()
        ?: refuse("expected one request builder loading \"$EXCLUDE_SUGGESTED_USERS\" and \"$DEFER_SUGGESTED_USERS\", found ${builders.size}")
    val where = "${method.definingClass}->${method.name}"
    val types = method.parameterTypes.map(CharSequence::toString)
    if (AccessFlags.STATIC.isSet(method.accessFlags) || types.size < 3 || types[types.size - 1] != "Z" || types[types.size - 2] != "Z") {
        refuse("$where doesn't take two boolean parameters after its first")
    }
    val code = method.implementation!!.instructions.toList()
    val keys = code.indices.filter { stringLoadedAt(code, it) == EXCLUDE_SUGGESTED_USERS }
    val key = keys.singleOrNull() ?: refuse("$where doesn't load \"$EXCLUDE_SUGGESTED_USERS\" once")
    val keyRegister = keyRegister(code, key) ?: refuse("$where doesn't keep \"$EXCLUDE_SUGGESTED_USERS\" in a register")
    val call = (key + 1 until code.size).firstOrNull {
        val instruction = code[it] as? FiveRegisterInstruction
        instruction != null && instruction.opcode == Opcode.INVOKE_STATIC && instruction.registerCount == 3 && instruction.registerD == keyRegister
    } ?: refuse("$where doesn't hand \"$EXCLUDE_SUGGESTED_USERS\" to a builder")
    val flag = (code[call] as FiveRegisterInstruction).registerE
    val copy = (call - 1 downTo key).firstOrNull { (code[it] as? OneRegisterInstruction)?.registerA == flag }
        ?: refuse("$where doesn't set the flag it hands over")
    val move = code[copy]
    if (move.opcode != Opcode.MOVE && move.opcode != Opcode.MOVE_FROM16 && move.opcode != Opcode.MOVE_16) {
        refuse("$where sets the \"$EXCLUDE_SUGGESTED_USERS\" flag by something other than a copy of a parameter")
    }
    val source = (move as TwoRegisterInstruction).registerB
    // The first of the two boolean parameters sits two registers from the end of the method's registers.
    val parameter = method.implementation!!.registerCount - 2
    if (source != parameter) refuse("$where copies \"$EXCLUDE_SUGGESTED_USERS\" from v$source, not its first boolean parameter v$parameter")
    if (0 in method.jumpTargets()) refuse("something in $where jumps to its first instruction")
    return RequestSuggestionsSite(method.definingClass, method.name, types, parameter)
}

/** [findRequestSuggestions], or null with a warning, so the patch's other parts still go in. */
internal fun BytecodePatchContext.findRequestSuggestionsOrWarn(): RequestSuggestionsSite? = try {
    findRequestSuggestions()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without hiding the suggested people on the Follow requests screen.")
    null
}

/**
 * Hands the request's first boolean parameter to [EXCLUDE_REQUEST_SUGGESTIONS] before anything reads
 * it, and puts the answer back in the same register: true asks the server to leave out the suggested
 * people, so the screen gets only the real requests and draws no Suggested for you section.
 */
internal fun BytecodePatchContext.hideRequestSuggestions(site: RequestSuggestionsSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructions(
        0,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $EXCLUDE_REQUEST_SUGGESTIONS
            move-result v${site.register}
        """,
    )
}

/**
 * Finds where the Follow requests screen's list asks whether it has no suggested people (#814): in the
 * one method keying rows by [SUGGESTED_USER_ROW] and [FOLLOW_REQUEST_ROW], the List.isEmpty answer an if-nez right after it
 * reads to jump past the code that makes those rows, the Suggested for you header and the See all
 * button. Its move-result is the site. Fails before anything changes when there isn't exactly one such
 * method or one such check, or something jumps onto the if-nez, where the hook goes.
 */
internal fun BytecodePatchContext.findRequestSection(): FollowListSite {
    val lists = classesLoadingString(SUGGESTED_USER_ROW).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, SUGGESTED_USER_ROW) && loadsString(it, FOLLOW_REQUEST_ROW) }
    }
    val method = lists.singleOrNull()
        ?: refuse("expected one list keying rows by \"$SUGGESTED_USER_ROW\" and \"$FOLLOW_REQUEST_ROW\", found ${lists.size}")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val row = code.indices.singleOrNull { stringLoadedAt(code, it) == SUGGESTED_USER_ROW }
        ?: refuse("$where doesn't load \"$SUGGESTED_USER_ROW\" once")
    val flow = ControlFlow.of(method)
    val checks = (0 until code.size - 2).filter { at ->
        val ask = code[at].methodReference()
        val result = code[at + 1] as? OneRegisterInstruction
        val branch = code[at + 2] as? OneRegisterInstruction
        code[at].opcode == Opcode.INVOKE_INTERFACE && ask?.definingClass == "Ljava/util/List;" && ask.name == "isEmpty" &&
            code[at + 1].opcode == Opcode.MOVE_RESULT && code[at + 2].opcode == Opcode.IF_NEZ &&
            result?.registerA == branch?.registerA && at + 2 < row && (flow.normal[at + 2].firstOrNull() ?: -1) > row
    }
    val check = checks.singleOrNull()
        ?: refuse("$where doesn't skip its \"$SUGGESTED_USER_ROW\" rows on one empty list, found ${checks.size}")
    if (check + 2 in method.jumpTargets()) refuse("something in $where jumps to the check on its suggested people")
    return FollowListSite(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), check + 1,
        (code[check + 1] as OneRegisterInstruction).registerA,
    )
}

/** [findRequestSection], or null with a warning, so the patch's other parts still go in. */
internal fun BytecodePatchContext.findRequestSectionOrWarn(): FollowListSite? = try {
    findRequestSection()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without hiding the Suggested for you section the Follow requests screen draws.")
    null
}

/**
 * Hands the list's isEmpty answer to [REQUEST_SUGGESTIONS_EMPTY] right before the branch reads it and
 * puts the answer back in the same register: true skips the Suggested for you header, its Follow rows
 * and the See all button, whatever the reply carried.
 */
internal fun BytecodePatchContext.hideRequestSection(site: FollowListSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructions(
        site.result + 1,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $REQUEST_SUGGESTIONS_EMPTY
            move-result v${site.register}
        """,
    )
}

/**
 * The friending center's list builder and where it reads two things (#814): which page it builds,
 * Follow requests among them (the iget of that mode and the register it lands in), and each server
 * unit's name right before it looks the name up (the lookup call and the register holding the name).
 */
internal class FriendingRequestsSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val mode: Int,
    val modeRegister: Int,
    val lookup: Int,
    val unitRegister: Int,
)

/**
 * Finds the friending center's list, the one that builds the Follow requests page a follow request
 * notification opens (#814). It's the one method loading both [EMPTY_REQUESTS_HEADER] and
 * [FOLLOW_REQUESTS_PAGE]; the empty header's row model loads the key too, but not the page. Of its calls, one
 * hands a unit's name to the static lookup that names the units the list knows (the method loading
 * [FOLLOW_REQUESTS_PAGE] that answers an Integer for a String), and an if-eqz right after skips the unit
 * when the lookup answers null. The page's mode is the String field the list compares with
 * [FOLLOW_REQUESTS_PAGE], read last before that lookup. Fails before anything changes when there isn't
 * exactly one list, lookup or comparison, the mode isn't read before the lookup, or something jumps in
 * where a hook goes.
 */
internal fun BytecodePatchContext.findFriendingRequests(): FriendingRequestsSite {
    val lists = classesLoadingString(EMPTY_REQUESTS_HEADER).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, EMPTY_REQUESTS_HEADER) && loadsString(it, FOLLOW_REQUESTS_PAGE) }
    }
    val method = lists.singleOrNull()
        ?: refuse("expected one list loading \"$EMPTY_REQUESTS_HEADER\" and \"$FOLLOW_REQUESTS_PAGE\", found ${lists.size}")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val jumps = method.jumpTargets()
    val namers = classesLoadingString(FOLLOW_REQUESTS_PAGE).flatMap { classDef ->
        classDef.methods.filter { candidate ->
            AccessFlags.STATIC.isSet(candidate.accessFlags) && candidate.returnType == "Ljava/lang/Integer;" &&
                candidate.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                loadsString(candidate, FOLLOW_REQUESTS_PAGE)
        }
    }.map { "${it.definingClass}->${it.name}(Ljava/lang/String;)Ljava/lang/Integer;" }.toSet()
    val lookups = code.indices.filter { at ->
        val result = code.getOrNull(at + 1)
        val skip = code.getOrNull(at + 2)
        code[at].methodReference()?.toString() in namers && result?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            skip?.opcode == Opcode.IF_EQZ && (skip as OneRegisterInstruction).registerA == (result as OneRegisterInstruction).registerA
    }
    val lookup = lookups.singleOrNull() ?: refuse("$where doesn't look its units up once and skip the ones it doesn't know, found ${lookups.size}")
    val unitRegister = onlyRegister(code[lookup]) ?: refuse("$where hands its unit lookup something other than one name")
    if (lookup in jumps) refuse("something in $where jumps to its unit lookup")

    val comparisons = code.indices.filter { stringLoadedAt(code, it) == FOLLOW_REQUESTS_PAGE }.mapNotNull { at ->
        val key = keyRegister(code, at) ?: return@mapNotNull null
        val call = (at + 1 until code.size).firstOrNull { code[it].methodReference() != null } ?: return@mapNotNull null
        val equals = code[call] as? FiveRegisterInstruction ?: return@mapNotNull null
        if (code[call].methodReference().toString() != "Ljava/lang/String;->equals(Ljava/lang/Object;)Z" || equals.registerC != key) return@mapNotNull null
        val write = (at - 1 downTo 0).firstOrNull { code[it].opcode.setsRegister() && (code[it] as? OneRegisterInstruction)?.registerA == equals.registerD }
            ?: return@mapNotNull null
        if (code[write].opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        ((code[write] as ReferenceInstruction).reference as? FieldReference)?.takeIf { it.type == "Ljava/lang/String;" }
    }
    val field = comparisons.map(Any::toString).distinct().singleOrNull()
        ?: refuse("$where doesn't compare one page mode with \"$FOLLOW_REQUESTS_PAGE\", found ${comparisons.size}")
    val mode = (lookup - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.IGET_OBJECT && (code[it] as ReferenceInstruction).reference.toString() == field
    } ?: refuse("$where doesn't read its page mode before its unit lookup")
    if (mode + 1 in jumps) refuse("something in $where jumps past its read of the page mode")
    return FriendingRequestsSite(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString),
        mode, (code[mode] as TwoRegisterInstruction).registerA, lookup, unitRegister,
    )
}

/** [findFriendingRequests], or null with a warning, so the patch's other parts still go in. */
internal fun BytecodePatchContext.findFriendingRequestsOrWarn(): FriendingRequestsSite? = try {
    findFriendingRequests()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without hiding Suggested for you on the Follow requests page notifications open.")
    null
}

/**
 * Hands each unit's name to [FRIENDING_UNIT] right before the lookup and puts the answer back in the
 * same register, and hands the page's mode to [FRIENDING_CENTER_MODE] right after the list reads it. On
 * the Follow requests page the extension answers a name the lookup doesn't know for every unit but the
 * requests, so the list skips each suggestion unit whole: its Suggested for you header, its rows and
 * its See all. The lookup is later, so it goes in first and the mode read's index holds.
 */
internal fun BytecodePatchContext.hideFriendingRequests(site: FriendingRequestsSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructions(
        site.lookup,
        """
            invoke-static/range { v${site.unitRegister} .. v${site.unitRegister} }, $FRIENDING_UNIT
            move-result-object v${site.unitRegister}
        """,
    )
    method.addInstructions(
        site.mode + 1,
        "invoke-static/range { v${site.modeRegister} .. v${site.modeRegister} }, $FRIENDING_CENTER_MODE",
    )
}

/** The one register a call is handed, in either form, or null when it's handed more or none. */
private fun onlyRegister(call: Instruction): Int? = when (call) {
    is FiveRegisterInstruction -> call.takeIf { it.registerCount == 1 }?.registerC
    is RegisterRangeInstruction -> call.takeIf { it.registerCount == 1 }?.startRegister
    else -> null
}

/** Where a Subscriptions tab request's reply says whether there's another page: the move-result and its register. */
internal class PageCheck(val result: Int, val register: Int)

/**
 * A Subscriptions tab request's reply, read: its method, the move-result holding the creators it
 * suggests and their register, and for the request that pages, where it reads whether there's more.
 */
internal class SubscriptionSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val result: Int,
    val register: Int,
    val more: PageCheck?,
)

/**
 * Finds where the Subscriptions tab of your own follow lists reads the creators [endpoint] answers
 * with (#539). One method loads the path, sends the request and reads its reply: after the path,
 * the first call taking nothing but its receiver and answering a List hands over the creators, kept
 * by the move-result right after it. With [pages], the next such call answering a boolean says
 * whether there's another page, and its move-result goes straight into a field. Fails before
 * anything changes when there isn't exactly one load of the path, either read is missing or kept in
 * nothing, or something jumps in right after a read.
 */
internal fun BytecodePatchContext.findSuggestedSubscriptions(endpoint: String, pages: Boolean): SubscriptionSite {
    val loads = classesLoadingString(endpoint).flatMap { classDef ->
        classDef.methods.flatMap { method ->
            val code = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
            code.indices.filter { stringLoadedAt(code, it) == endpoint }.map { method to it }
        }
    }
    val (method, load) = loads.singleOrNull() ?: refuse("expected one request for $endpoint, found ${loads.size}")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val jumps = method.jumpTargets()
    val read = (load + 1 until code.size).firstOrNull { code[it].asksReceiverFor("Ljava/util/List;") }
        ?: refuse("$where never reads the creators $endpoint answers with")
    if (code.getOrNull(read + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where reads the creators $endpoint answers with into nothing")
    if (read + 2 in jumps) refuse("something in $where jumps past its read of the creators $endpoint answers with")
    val more = if (!pages) null else {
        val check = (read + 2 until code.size).firstOrNull { code[it].asksReceiverFor("Z") }
            ?: refuse("$where never reads whether $endpoint has another page")
        val result = code.getOrNull(check + 1)
        if (result?.opcode != Opcode.MOVE_RESULT) refuse("$where reads whether $endpoint has another page into nothing")
        val register = (result as OneRegisterInstruction).registerA
        val keep = code.getOrNull(check + 2)
        if (keep?.opcode != Opcode.IPUT_BOOLEAN || (keep as TwoRegisterInstruction).registerA != register) {
            refuse("$where doesn't keep whether $endpoint has another page")
        }
        if (check + 2 in jumps) refuse("something in $where jumps past its read of whether $endpoint has another page")
        PageCheck(check + 1, register)
    }
    return SubscriptionSite(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), read + 1,
        (code[read + 1] as OneRegisterInstruction).registerA, more,
    )
}

/** A virtual or interface call taking nothing but its receiver and answering [returns]. */
private fun Instruction.asksReceiverFor(returns: String): Boolean {
    if (opcode != Opcode.INVOKE_INTERFACE && opcode != Opcode.INVOKE_VIRTUAL) return false
    val call = methodReference() ?: return false
    return call.parameterTypes.isEmpty() && call.returnType == returns
}

/** Both of the Subscriptions tab's suggestion requests, each found or warned about, so the patch's other parts still go in. */
internal fun BytecodePatchContext.findSuggestedSubscriptionsOrWarn(): List<SubscriptionSite> =
    listOf(FOLLOWED_CREATORS_TO_SUBSCRIBE to true, UNCONNECTED_CREATORS_TO_SUBSCRIBE to false).mapNotNull { (endpoint, pages) ->
        try {
            findSuggestedSubscriptions(endpoint, pages)
        } catch (refusal: PatchException) {
            patchLog.warning("${refusal.message}. $PATCH goes in without hiding the Suggested subscriptions $endpoint brings.")
            null
        }
    }

/**
 * Hands the creators to [SUGGESTED_SUBSCRIPTIONS] right after their move-result and, for the request
 * that pages, whether there's another page to [MORE_SUGGESTED_SUBSCRIPTIONS] right after its own,
 * each answer back in the same register. An empty list and no next page leave the tab with nothing
 * to draw under Suggested subscriptions. The later read goes in first, so the earlier index holds.
 */
internal fun BytecodePatchContext.hideSuggestedSubscriptions(site: SubscriptionSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    site.more?.let { more ->
        method.addInstructions(
            more.result + 1,
            """
                invoke-static/range { v${more.register} .. v${more.register} }, $MORE_SUGGESTED_SUBSCRIPTIONS
                move-result v${more.register}
            """,
        )
    }
    method.addInstructions(
        site.result + 1,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $SUGGESTED_SUBSCRIPTIONS
            move-result-object v${site.register}
        """,
    )
}

/**
 * Finds the check the follow lists' action bar makes before adding the Discover people button
 * (#539). The bar is the pager's (the class [findSuggestedTab] finds), and its one method loading
 * both [FRIENDING_LOGGER] and [SELF_FOLLOW_LISTS]; the button's click handler loads both too, in a
 * class of its own. Before the logger, one call answering a boolean has its answer tested right
 * away by an if-eqz that leaves through a return-void: on someone else's lists the bar ends there,
 * with no button. Fails before anything changes when the pager isn't found, there isn't exactly one
 * such method or check, or something jumps in after the check.
 */
internal fun BytecodePatchContext.findFriendingButton(): FollowListSite {
    val pager = findSuggestedTab().type
    val bars = classesLoadingString(FRIENDING_LOGGER).filter { it.type == pager }.flatMap { classDef ->
        classDef.methods.filter { method ->
            val code = method.implementation?.instructions?.toList() ?: return@filter false
            code.indices.any { stringLoadedAt(code, it) == FRIENDING_LOGGER } &&
                code.indices.any { stringLoadedAt(code, it) == SELF_FOLLOW_LISTS }
        }
    }
    val method = bars.singleOrNull()
        ?: refuse("expected one action bar in $pager loading \"$FRIENDING_LOGGER\" and \"$SELF_FOLLOW_LISTS\", found ${bars.size}")
    val where = "${method.definingClass}->${method.name}"
    val flow = ControlFlow.of(method)
    val code = flow.instructions
    val logger = code.indices.first { stringLoadedAt(code, it) == FRIENDING_LOGGER }
    val checks = (0 until logger).filter { at ->
        val result = code.getOrNull(at + 1)
        val test = code.getOrNull(at + 2)
        code[at].methodReference()?.returnType == "Z" &&
            result?.opcode == Opcode.MOVE_RESULT && test?.opcode == Opcode.IF_EQZ &&
            (test as OneRegisterInstruction).registerA == (result as OneRegisterInstruction).registerA &&
            code[flow.normal[at + 2].first()].opcode == Opcode.RETURN_VOID
    }
    val check = checks.singleOrNull() ?: refuse("expected one check in $where that ends the bar before its button, found ${checks.size}")
    if (check + 2 in method.jumpTargets()) refuse("something in $where jumps past its check before the button")
    return FollowListSite(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), check + 1,
        (code[check + 1] as OneRegisterInstruction).registerA,
    )
}

/** [findFriendingButton], or null with a warning, so the patch's other parts still go in. */
internal fun BytecodePatchContext.findFriendingButtonOrWarn(): FollowListSite? = try {
    findFriendingButton()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without hiding the Discover people button on your follow lists.")
    null
}

/**
 * Hands the action bar's answer to [FRIENDING_BUTTON] right after its move-result, the answer back
 * in the same register. A 0 takes the bar out through its return, as on someone else's lists, so
 * the Discover people button is never added. Only that button and the note that it was seen come
 * after the check.
 */
internal fun BytecodePatchContext.hideFriendingButton(site: FollowListSite) = answer(site, FRIENDING_BUTTON)

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.methodsCalling
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Swipe through a chat's reels"
internal const val CHAT_REELS = "$EXTENSION_PACKAGE/direct/ChatReels;"
internal const val CHAIN = "$CHAT_REELS->chain(I)Z"
internal const val STOP = "$CHAT_REELS->stop()Z"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** What a chat's reel opener is handed: the chat and the message holding the reel. Redex keeps both names. */
internal const val THREAD_KEY = "Lcom/instagram/model/direct/DirectThreadKey;"
internal const val MESSAGE_ID = "Lcom/instagram/model/direct/messageid/MessageIdentifier;"

/**
 * The server flag behind Instagram's own chat reels test. On 453 it's read once, in a test taking
 * the session and answering it (LX/01On;->A00 on 385914557, LX/01PZ;->A00 on 385914556, an instance
 * method of a singleton), which the opener of a reel shared in a chat asks before it hands the chat's
 * other shared reels to the Reels viewer, and which the chat and the Reels viewer ask too. 452.1's
 * was 0x81155f00006ed0.
 */
internal const val CHAT_REELS_FLAG = 0x81153c00006fb6L

/**
 * The trace name of the Reels viewer's dual paging request (LX/0Dyz;->A03 on 385914557, LX/0Fit;->A03
 * on 385914556), which pages a source with two sides: a chat's own shared reels and, after them,
 * Instagram's suggested ones. Only that request loads it.
 */
internal const val DUAL_PAGING = "AbstractPaginatingClipsItemsFetcher.makeDualPaginationRequest"

/**
 * What the source of a chat's reels asks the server for to page the chat's own shared reels
 * (LX/0Mes;->BYn on 385914557, LX/0Mkk;->BYs on 385914556). Only that method loads it.
 */
internal const val CHAT_REELS_PATH = "clips/direct_thread_clips/"

/**
 * What the Reels viewer's request for more reels past the ones it holds logs (LX/02HQ;->A1Q on 453's 385914557).
 * Opened from a chat with the chain, the viewer holds the chat's reels and this request asks the
 * default source, or Instagram's prefetched Reels, for suggested ones. Only that request loads it.
 */
internal const val MORE_REELS = "active_section_on_start"

/** What the Reels viewer was opened with. Redex keeps the name. */
internal const val VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"

/** The chat a reel was opened from, which [VIEWER_CONFIG] holds only then. Redex keeps the name. */
internal const val DIRECT_DATA = "Lcom/instagram/clips/intf/ClipsViewerDirectData;"

internal const val STOP_CACHED = "$CHAT_REELS->stopCached(Ljava/lang/Object;)Z"

/**
 * The trace name of the Reels viewer's insert of a reel Instagram keeps cached for the Reels tab
 * (LX/04HZ;->A0A on 453's 385914557, in ClipsViewerFlashMediaController). As a viewer nears its last
 * reel the controller puts a cached one after it, without asking the server, which is where the
 * suggested reels after a chat's own come from. Only that insert loads it.
 */
internal const val CACHED_REEL = "ClipsViewerFlashMediaController.insertItemToAdapter"

/** Where a Reels viewer was opened from, DIRECT for a chat. Redex keeps the name. */
internal const val VIEWER_SOURCE = "Lcom/instagram/clips/intf/ClipsViewerSource;"

internal const val PLACEHOLDER = "$CHAT_REELS->placeholder(Ljava/lang/Object;I)Z"

/**
 * The trace name of the Reels viewer adapter's add of reels (LX/044l;->A0a on 453's 385914557,
 * ClipsViewerAdapter.addClipsItems). With its flag set it puts Instagram's loading placeholder, a
 * gray reel, after the reels it adds, for the next ones to replace. With the viewer's other ways to
 * more reels stopped nothing replaces it, so a chat's reels end on it. Only that add loads it.
 */
internal const val ADD_REELS = "ClipsViewerAdapter.addClipsItems"

private const val LIST = "Ljava/util/List;"
private const val POSITION = "Ljava/lang/Integer;"
private const val VIEW = "Landroid/view/View;"

internal const val OPEN = "$CHAT_REELS->open(Ljava/lang/Object;Landroid/view/View;)Z"

/**
 * Swiping up on a reel opened from a chat goes through the other reels shared in that chat before
 * any suggested reels (#350), and with the second switch stops after them (#390). In the default
 * selection with both switches off. The first answers Instagram's own test, so the chain, its order
 * and Back to the chat are Instagram's. Whatever the switches, a repeat tap on media in a chat waits
 * for the first tap's viewer instead of opening another (#390).
 */
@Suppress("unused")
val swipeChatReelsPatch = bytecodePatch(
    name = "Swipe through a chat's reels",
    description = "Swiping up on a reel you opened from a chat goes through the other reels shared in that chat before " +
        "any suggested reels, and can stop after them. Both start off. Turn them on in HushGram settings > Messages.",
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("chatReels")
        // Every finder refuses before either switch changes any code.
        val read = findChatReels()
        val end = findChatReelsEnd()
        val more = findMoreReels()
        val cached = findCachedReels()
        val placeholders = findPlaceholders()
        val tap = findChatMediaTap(read)
        chainChatReels(read)
        stopAfterChatReels(end)
        stopMoreReels(more)
        stopCachedReels(cached)
        skipPlaceholders(placeholders)
        guardChatMediaTaps(tap)
        enableStatus("chatReels")
    }
}

/** Has the extension answer the chat reels test's read, found with [findChatReels], through [CHAIN]. */
internal fun BytecodePatchContext.chainChatReels(read: FlagLoad = findChatReels()) {
    answerFlagLoads(listOf(read to CHAIN))
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the one read of [CHAT_REELS_FLAG]. It has to be the flag's own read, in a test taking the
 * session and answering a boolean, and a method handed a chat ([THREAD_KEY]) and one of its messages
 * ([MESSAGE_ID]), the opener of a reel shared there, has to ask that test. Fails before anything
 * changes when any of it isn't so, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findChatReels(): FlagLoad {
    val flag = CHAT_REELS_FLAG.toString(16)
    val reads = findFlagLoads(PATCH, CHAT_REELS_FLAG, "Z")
    val read = reads.singleOrNull() ?: refuse("expected one read of the chat reels flag $flag, found ${reads.size}")
    val where = "${read.type}->${read.name}"
    if (read.shared) refuse("the read of $flag in $where is shared with another flag")
    if (read.parameters != listOf(USER_SESSION) || read.returnType != "Z") {
        refuse("$flag is read in $where(${read.parameters.joinToString("")})${read.returnType}, not in a test of the session")
    }
    if (openersOf(read).isEmpty()) refuse("nothing handed a chat and a message asks $where, so it isn't the chat reels test")
    return read
}

/** The methods handed a chat and a message that ask the chat reels test [read]: the openers of a reel shared in a chat. */
private fun BytecodePatchContext.openersOf(read: FlagLoad) = methodsCalling(read.type, read.name).filter { caller ->
    val takes = caller.parameterTypes.map(CharSequence::toString)
    THREAD_KEY in takes && MESSAGE_ID in takes
}

/** The tap on media in a chat, and the parameter registers its tapped view and message come in. */
internal class ChatMediaTap(val tap: MutableMethod, val view: Int, val message: Int)

/**
 * Finds the tap on media in a chat before anything changes (LX/0QbG;->F1J on 453's 385914557, which
 * opens a shared post or reel, slowly on some phones, through the opener [findChatReels] checks).
 * It's the one instance method returning nothing in an opener's class that is handed the tapped
 * view and the message ([MESSAGE_ID]), one of each, with two locals for the hook and nothing jumping
 * to its first instruction. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findChatMediaTap(read: FlagLoad = findChatReels()): ChatMediaTap {
    val owners = openersOf(read).map { it.definingClass }.distinct()
    val taps = owners.flatMap { owner ->
        classDefBy(owner).methods.filter { method ->
            val takes = method.parameterTypes.map(CharSequence::toString)
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && VIEW in takes && MESSAGE_ID in takes
        }
    }
    val tap = taps.singleOrNull()
        ?: refuse("expected one tap in ${owners.joinToString()} handed a view and a message, found ${taps.size}")
    val takes = tap.parameterTypes.map(CharSequence::toString)
    val where = "${tap.definingClass}->${tap.name}"
    if (takes.count { it == VIEW } != 1 || takes.count { it == MESSAGE_ID } != 1) {
        refuse("$where(${takes.joinToString("")})V is handed more than one view or message")
    }
    if (tap.localRegisterCount() < 2) refuse("$where has fewer than two locals for the hook")
    if (0 in tap.jumpTargets()) refuse("a jump or exception handler enters $where at its first instruction")
    fun register(type: String) = 1 + takes.take(takes.indexOf(type)).sumOf { if (it == "J" || it == "D") 2 else 1 }

    val mutable = mutableClassDefBy(tap.definingClass).methods.single { method ->
        method.name == tap.name && method.parameterTypes.map(CharSequence::toString) == takes
    }
    return ChatMediaTap(mutable, register(VIEW), register(MESSAGE_ID))
}

/**
 * Asks the extension through [OPEN] first on a tap on media in a chat, handing it the message and
 * the tapped view. A no (a repeat tap while the first tap's viewer is on its way) returns, so the
 * viewers don't stack. Everything else goes on to Instagram.
 */
internal fun BytecodePatchContext.guardChatMediaTaps(found: ChatMediaTap = findChatMediaTap()) {
    val tap = found.tap
    tap.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p${found.message}
            move-object/from16 v1, p${found.view}
            invoke-static { v0, v1 }, $OPEN
            move-result v0
            if-nez v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", tap.getInstruction(0)),
    )
}

/** The viewer's dual paging request, and the class of the source of a chat's reels it pages. */
internal class ChatReelsEnd(val paging: MutableMethod, val chat: String)

private val BRANCHES = setOf(
    Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
)
private val INTERFACE_CALLS = setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)

/**
 * Finds the viewer's dual paging request and the source of a chat's reels, before anything changes.
 * The request is the one method loading [DUAL_PAGING]: an instance method taking a source and three
 * flags, with a local for the hook's answer and nothing jumping to its first instruction. Its first
 * branch is an `if-eqz` on the last flag that goes to the source's page of the chat's own reels, the
 * one method loading [CHAT_REELS_PATH], in a class that is that source. Falling through asks for the
 * stock side, Instagram's suggested reels. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findChatReelsEnd(): ChatReelsEnd {
    val pagings = classesLoadingString(DUAL_PAGING).flatMap { holder -> holder.methods.filter { loadsString(it, DUAL_PAGING) } }
    val paging = pagings.singleOrNull() ?: refuse("expected one method loading \"$DUAL_PAGING\", found ${pagings.size}")
    val takes = paging.parameterTypes.map(CharSequence::toString)
    val where = "${paging.definingClass}->${paging.name}"
    if (AccessFlags.STATIC.isSet(paging.accessFlags) || paging.returnType != "V" || takes.size != 4 ||
        !takes[0].startsWith("L") || takes.drop(1) != listOf("Z", "Z", "Z")
    ) {
        refuse("$where(${takes.joinToString("")})${paging.returnType} isn't an instance request taking a source and three flags")
    }
    if (paging.localRegisterCount() < 1) refuse("$where has no local for the answer")
    if (0 in paging.jumpTargets()) refuse("a jump or exception handler enters $where at its first instruction")
    val source = takes[0]

    val pages = classesLoadingString(CHAT_REELS_PATH).flatMap { holder -> holder.methods.filter { loadsString(it, CHAT_REELS_PATH) } }
    val page = pages.singleOrNull() ?: refuse("expected one method asking for $CHAT_REELS_PATH, found ${pages.size}")
    val chat = page.definingClass
    if (source !in classDefBy(chat).interfaces) refuse("$chat asks for $CHAT_REELS_PATH but isn't a $source, what $where pages")

    val code = paging.implementation!!.instructions.toList()
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    val last = paging.implementation!!.registerCount - 1
    val branch = code.indexOfFirst { it.opcode in BRANCHES }
    val target = if (branch < 0) -1 else address.indexOf(address[branch] + (code[branch] as OffsetInstruction).codeOffset)
    fun sideAt(from: Int): MethodReference? = (from until code.size).firstNotNullOfOrNull { at ->
        if (code[at].opcode in INTERFACE_CALLS) (code[at] as ReferenceInstruction).reference as MethodReference else null
    }
    val chatSide = if (target < 0) null else sideAt(target)
    val stockSide = if (branch < 0) null else sideAt(branch + 1)
    if (branch < 0 || code[branch].opcode != Opcode.IF_EQZ || (code[branch] as OneRegisterInstruction).registerA != last ||
        chatSide?.definingClass != source || chatSide.name != page.name || stockSide == null || stockSide.name == page.name
    ) {
        refuse("the first branch in $where isn't the last flag picking $source->${page.name}, the chat's own reels")
    }

    val mutable = mutableClassDefBy(paging.definingClass).methods.single { method ->
        method.name == paging.name && method.parameterTypes.map(CharSequence::toString) == takes
    }
    return ChatReelsEnd(mutable, chat)
}

/**
 * Asks the extension through [STOP] first in the dual paging request, only when the request is for
 * the stock side (the last flag set) of the source of a chat's reels. A yes returns before anything
 * is asked for, so the viewer ends after the chat's own reels. Everything else goes on to Instagram.
 */
internal fun BytecodePatchContext.stopAfterChatReels(found: ChatReelsEnd = findChatReelsEnd()) {
    val paging = found.paging
    paging.addInstructionsWithLabels(
        0,
        """
            if-eqz p${paging.parameterTypes.size}, :instagram
            move-object/from16 v0, p1
            instance-of v0, v0, ${found.chat}
            if-eqz v0, :instagram
            invoke-static { }, $STOP
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", paging.getInstruction(0)),
    )
}

/**
 * The viewer's request for more reels, the viewer's [VIEWER_CONFIG] field it reads, and the
 * config's [DIRECT_DATA] field.
 */
internal class MoreReels(val request: MutableMethod, val config: String, val direct: String)

/**
 * Finds the viewer's request for more reels before anything changes: the one method loading
 * [MORE_REELS], an instance method returning nothing, with a local for the hook's answer and nothing
 * jumping to its first instruction. It reads one [VIEWER_CONFIG] field of its own class, and the
 * config holds one [DIRECT_DATA] field. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findMoreReels(): MoreReels {
    val requests = classesLoadingString(MORE_REELS).flatMap { holder -> holder.methods.filter { loadsString(it, MORE_REELS) } }
    val request = requests.singleOrNull() ?: refuse("expected one method loading \"$MORE_REELS\", found ${requests.size}")
    val where = "${request.definingClass}->${request.name}"
    if (AccessFlags.STATIC.isSet(request.accessFlags) || request.returnType != "V") {
        refuse("$where(${request.parameterTypes.joinToString("")})${request.returnType} isn't an instance request returning nothing")
    }
    if (request.localRegisterCount() < 1) refuse("$where has no local for the answer")
    if (0 in request.jumpTargets()) refuse("a jump or exception handler enters $where at its first instruction")

    val configs = request.implementation!!.instructions.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        if (field.definingClass == request.definingClass && field.type == VIEWER_CONFIG) "$field" else null
    }.distinct()
    val config = configs.singleOrNull() ?: refuse("$where reads ${configs.size} $VIEWER_CONFIG fields of its viewer, not one")
    val directs = classDefBy(VIEWER_CONFIG).fields.filter { it.type == DIRECT_DATA && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val direct = directs.singleOrNull() ?: refuse("$VIEWER_CONFIG holds ${directs.size} $DIRECT_DATA fields, not one")

    val takes = request.parameterTypes.map(CharSequence::toString)
    val mutable = mutableClassDefBy(request.definingClass).methods.single { method ->
        method.name == request.name && method.parameterTypes.map(CharSequence::toString) == takes
    }
    return MoreReels(mutable, config, "$VIEWER_CONFIG->${direct.name}:$DIRECT_DATA")
}

/**
 * Asks the extension through [STOP] first in the viewer's request for more reels, only when the
 * viewer was opened from a chat (its config holds the chat). A yes returns before anything is asked
 * for, so the viewer ends after the reels it was opened with, the chat's own. Everything else goes
 * on to Instagram.
 */
internal fun BytecodePatchContext.stopMoreReels(found: MoreReels = findMoreReels()) {
    val request = found.request
    request.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${found.config}
            if-eqz v0, :instagram
            iget-object v0, v0, ${found.direct}
            if-eqz v0, :instagram
            invoke-static { }, $STOP
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", request.getInstruction(0)),
    )
}

/**
 * The insert of a cached reel, the word its controller is handed in, and the controller's
 * [VIEWER_SOURCE] field.
 */
internal class CachedReels(val insert: MutableMethod, val controller: Int, val source: String)

/**
 * Finds the insert of a cached reel before anything changes: the one method loading [CACHED_REEL],
 * a static method returning nothing handed one controller of its own class, with a local for the
 * hook's answer and nothing jumping to its first instruction. The controller holds one
 * [VIEWER_SOURCE] field. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findCachedReels(): CachedReels {
    val inserts = classesLoadingString(CACHED_REEL).flatMap { holder -> holder.methods.filter { loadsString(it, CACHED_REEL) } }
    val insert = inserts.singleOrNull() ?: refuse("expected one method loading \"$CACHED_REEL\", found ${inserts.size}")
    val takes = insert.parameterTypes.map(CharSequence::toString)
    val where = "${insert.definingClass}->${insert.name}"
    if (!AccessFlags.STATIC.isSet(insert.accessFlags) || insert.returnType != "V" || takes.count { it == insert.definingClass } != 1) {
        refuse("$where(${takes.joinToString("")})${insert.returnType} isn't a static insert handed its controller")
    }
    if (insert.localRegisterCount() < 1) refuse("$where has no local for the answer")
    if (0 in insert.jumpTargets()) refuse("a jump or exception handler enters $where at its first instruction")

    val sources = classDefBy(insert.definingClass).fields.filter { it.type == VIEWER_SOURCE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val source = sources.singleOrNull() ?: refuse("${insert.definingClass} holds ${sources.size} $VIEWER_SOURCE fields, not one")
    val controller = takes.take(takes.indexOf(insert.definingClass)).sumOf { if (it == "J" || it == "D") 2 else 1 }

    val mutable = mutableClassDefBy(insert.definingClass).methods.single { method ->
        method.name == insert.name && method.parameterTypes.map(CharSequence::toString) == takes
    }
    return CachedReels(mutable, controller, "${insert.definingClass}->${source.name}:$VIEWER_SOURCE")
}

/**
 * Asks the extension through [STOP_CACHED] first in the insert of a cached reel, handing it the
 * controller's viewer source. A yes (a viewer opened from a chat, with the switch on) returns before
 * the reel goes in, so the viewer ends after the chat's own reels. Everything else goes on to
 * Instagram.
 */
internal fun BytecodePatchContext.stopCachedReels(found: CachedReels = findCachedReels()) {
    val insert = found.insert
    insert.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p${found.controller}
            iget-object v0, v0, ${found.source}
            invoke-static { v0 }, $STOP_CACHED
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", insert.getInstruction(0)),
    )
}

/**
 * The adapter's add of reels and its placeholder at a position, the adapter's [VIEWER_CONFIG] field
 * the add reads, and the config's [VIEWER_SOURCE] field it reads.
 */
internal class Placeholders(val add: MutableMethod, val shimmer: MutableMethod, val config: String, val source: String)

/**
 * Finds the adapter's add of reels and its placeholder at a position before anything changes. The
 * add is the one method loading [ADD_REELS], an instance method handed reels and a flag and returning
 * nothing, with two locals for the hook and nothing jumping to its first instruction. It reads one
 * [VIEWER_CONFIG] field of its own class and one [VIEWER_SOURCE] field of the config. The placeholder
 * (LX/044l;->A0X on 385914557) is the one instance method of the same class handed a position that
 * calls the add, with a local and nothing jumping to its first instruction. Fails when any of it
 * isn't so.
 */
internal fun BytecodePatchContext.findPlaceholders(): Placeholders {
    val adds = classesLoadingString(ADD_REELS).flatMap { holder -> holder.methods.filter { loadsString(it, ADD_REELS) } }
    val add = adds.singleOrNull() ?: refuse("expected one method loading \"$ADD_REELS\", found ${adds.size}")
    val takes = add.parameterTypes.map(CharSequence::toString)
    val where = "${add.definingClass}->${add.name}"
    if (AccessFlags.STATIC.isSet(add.accessFlags) || add.returnType != "V" || takes != listOf(LIST, "Z")) {
        refuse("$where(${takes.joinToString("")})${add.returnType} isn't an instance add handed reels and a flag")
    }
    if (add.localRegisterCount() < 2) refuse("$where has fewer than two locals for the answer")
    if (0 in add.jumpTargets()) refuse("a jump or exception handler enters $where at its first instruction")

    fun reads(owner: String, type: String) = add.implementation!!.instructions.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        if (field.definingClass == owner && field.type == type) "$field" else null
    }.distinct()
    val configs = reads(add.definingClass, VIEWER_CONFIG)
    val config = configs.singleOrNull() ?: refuse("$where reads ${configs.size} $VIEWER_CONFIG fields of its adapter, not one")
    val sources = reads(VIEWER_CONFIG, VIEWER_SOURCE)
    val source = sources.singleOrNull() ?: refuse("$where reads ${sources.size} $VIEWER_SOURCE fields of its config, not one")

    val shimmers = classDefBy(add.definingClass).methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(POSITION) &&
            method.implementation?.instructions?.any { instruction ->
                val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                call != null && call.definingClass == add.definingClass && call.name == add.name &&
                    call.parameterTypes.map(CharSequence::toString) == takes
            } == true
    }
    val shimmer = shimmers.singleOrNull()
        ?: refuse("expected one method of ${add.definingClass} handed a position that calls ${add.name}, found ${shimmers.size}")
    val at = "${shimmer.definingClass}->${shimmer.name}"
    if (shimmer.localRegisterCount() < 1) refuse("$at has no local for the answer")
    if (0 in shimmer.jumpTargets()) refuse("a jump or exception handler enters $at at its first instruction")

    val methods = mutableClassDefBy(add.definingClass).methods
    val mutableAdd = methods.single { it.name == add.name && it.parameterTypes.map(CharSequence::toString) == takes }
    val mutableShimmer = methods.single { it.name == shimmer.name && it.parameterTypes.map(CharSequence::toString) == listOf(POSITION) }
    return Placeholders(mutableAdd, mutableShimmer, config, source)
}

/**
 * Has the adapter's add ask the extension through [PLACEHOLDER] whether it puts its placeholder after
 * the reels it adds, handing it the viewer's source and Instagram's flag, and writes the answer back
 * into the flag. A viewer opened from a chat with the switch on gets none. The placeholder at a
 * position asks [STOP_CACHED] first and returns on a yes. Without a position it goes on to Instagram,
 * whose add then asks. Everything else is Instagram's.
 */
internal fun BytecodePatchContext.skipPlaceholders(found: Placeholders = findPlaceholders()) {
    val add = found.add
    add.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${found.config}
            if-eqz v0, :instagram
            iget-object v0, v0, ${found.source}
            move/from16 v1, p2
            invoke-static { v0, v1 }, $PLACEHOLDER
            move-result v1
            move/from16 p2, v1
        """,
        ExternalLabel("instagram", add.getInstruction(0)),
    )
    val shimmer = found.shimmer
    shimmer.addInstructionsWithLabels(
        0,
        """
            if-eqz p1, :instagram
            move-object/from16 v0, p0
            iget-object v0, v0, ${found.config}
            if-eqz v0, :instagram
            iget-object v0, v0, ${found.source}
            invoke-static { v0 }, $STOP_CACHED
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", shimmer.getInstruction(0)),
    )
}

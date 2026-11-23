/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.direct.history.MessageFields
import app.morphe.patches.instagram.direct.history.findMessageFields
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.methodsCalling
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.reels.scrolling.VIEW_PAGER
import app.morphe.patches.instagram.reels.scrolling.reelsPager
import app.morphe.patches.instagram.reels.scrolling.reelsPagerSetup
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Swipe through a chat's reels"
internal const val CHAT_REELS = "$EXTENSION_PACKAGE/direct/ChatReels;"
internal const val CHAIN = "$CHAT_REELS->chain(I)Z"
internal const val STOP = "$CHAT_REELS->stop()Z"
internal const val RECEIVED = "$CHAT_REELS->received(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val LEAVE_OUT_SENT = "$CHAT_REELS->leaveOutSent(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;"

/** What a reel item holds and the id the extension keys a sent reel by. Redex keeps both names. */
internal const val MEDIA = "Lcom/instagram/feed/media/Media;"
private const val MEDIA_ID = "$MEDIA->getId()Ljava/lang/String;"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val STRING = "Ljava/lang/String;"
/** The signed-in account's user id, which a message you sent names as its sender. Redex keeps the name. */
internal const val USER_ID = "$USER_SESSION->userId:$STRING"

/** What a chat's reel opener is handed: the chat and the message holding the reel. Redex keeps both names. */
internal const val THREAD_KEY = "Lcom/instagram/model/direct/DirectThreadKey;"
internal const val MESSAGE_ID = "Lcom/instagram/model/direct/messageid/MessageIdentifier;"

/**
 * The server flag behind Instagram's own chat reels test. On 456 it's read once, in a test taking
 * the session and answering it (LX/05IB;->A00 on 386214574),
 * which the opener of a reel shared in a chat asks before it hands the chat's other shared reels to
 * the Reels viewer, and which the chat and the Reels viewer ask too. 455's was 0x8114d60000718c,
 * 454's 0x8114ef00007096, 453's 0x81153c00006fb6 and 452.1's 0x81155f00006ed0.
 */
internal const val CHAT_REELS_FLAG = 0x8114b00000723cL

/**
 * The trace name of the Reels viewer's dual paging request (LX/0Dyz;->A03 on 385914557, LX/0Fit;->A03
 * on 385914556), which pages a source with two sides: a chat's own shared reels and, after them,
 * Instagram's suggested ones. Only that request loads it.
 */
internal const val DUAL_PAGING = "AbstractPaginatingClipsItemsFetcher.makeDualPaginationRequest"

/**
 * What the source of a chat's reels asks the server for to page the chat's own shared reels. Up to
 * 455 only that source's page loads it. 456 moved the request into a static builder of its own,
 * which the page calls, and which the blend source calls too when a blend was opened from a chat.
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

internal const val REFRESH = "$CHAT_REELS->refresh(Ljava/lang/Object;)Z"

/**
 * What the Reels viewer's refresh logs when its config doesn't allow one (LX/02HQ;->A1T on 453's
 * 385914557, ClipsViewerRefreshUseCase.handleRefresh). Its first read of a [VIEWER_CONFIG] boolean,
 * followed by an `if-eqz` on it, is that permission (A3G there). Only the refresh loads it.
 */
internal const val REFRESH_NOT_ALLOWED = "refresh_not_allowed"

internal const val CHAT_REELS_EXIT = "$EXTENSION_PACKAGE/direct/ChatReelsExit;"
internal const val VIEWER = "$CHAT_REELS_EXIT->viewer(Ljava/lang/Object;Ljava/lang/Object;)V"

internal const val COLLECTED = "$CHAT_REELS->collected(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val REPLY_TO = "$CHAT_REELS->replyTo(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;"
internal const val REPLY_INFO = "$CHAT_REELS->replyInfo(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * What the reply info for the reel on screen of the reply bar under a reel opened from a chat
 * (ClipsViewerDirectReplyBarController, `LX/0I4a;->A03` on 456's 386214574) reports when it finds
 * none. It's the one method loading it, so it finds the controller, its hint update for a page
 * (A1G there) and its reel on screen (A02).
 */
internal const val REPLY_INFO_MISSING = "ClipsReshareInfo was null"

internal const val CHAT_REELS_NOTICE = "$EXTENSION_PACKAGE/direct/ChatReelsNotice;"
internal const val NOTICE_PAGE = "$CHAT_REELS_NOTICE->page(Ljava/lang/Object;I)V"

/**
 * Swiping up on a reel opened from a chat goes through the other reels shared in that chat before
 * any suggested reels (#350), and with the second switch stops after them (#390). In the default
 * selection with both switches off. The first answers Instagram's own test, so the chain, its order
 * and Back to the chat are Instagram's. With the second switch on, a pull down at a chat's first reel
 * loads nothing, and a swipe up past its last reel goes back to the chat (#390). Whatever the
 * switches, a repeat tap on media in a chat waits for the first tap's viewer instead of opening
 * another (#390). With Messages after a reel on, a banner over the viewer says when the other person
 * wrote after the reel on screen, and a tap on it goes back to the chat at that message (#489). With
 * Don't open reels from chats on, a tap on a reel shared in a chat leaves it closed (#810).
 */
@Suppress("unused")
val swipeChatReelsPatch = bytecodePatch(
    name = "Swipe through a chat's reels",
    description = "Swiping up on a reel you opened from a chat goes through the other reels shared in that chat before " +
        "any suggested reels, can stop after them and can leave out the ones you sent. Reels shared in chats can also " +
        "stay closed when tapped. All start off. Turn them on in HushGram settings > Messages.",
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("chatReels")
        // Every finder refuses before either switch changes any code.
        val read = findChatReels()
        val end = findChatReelsEnd()
        val blend = findBlendAfterChat(end.chat)
        val more = findMoreReels(end)
        val cached = findCachedReels()
        val placeholders = findPlaceholders()
        val tap = findChatMediaTap(read)
        val refreshes = findRefreshReads()
        val viewers = findChatViewers()
        val collector = findReelCollector(read)
        val replies = findReplyBar(collector)
        val page = findReplyPage(collector, replies)
        val carried = findReelMessageOrWarn(collector, replies)
        val opener = findChatReelOpener(read, collector)
        checkAddTakesReels(placeholders, collector)
        chainChatReels(read)
        skipSentReels(collector)
        giveSwipedReelsTheirReplies(collector, replies)
        carried?.let { buildRepliesFromReels(it) }
        pointOutMessagesAfterReels(collector, replies, page)
        leaveOutSentReels(placeholders)
        stopAfterChatReels(end)
        keepChatReelsBeforeBlend(blend)
        stopMoreReels(more)
        stopCachedReels(cached)
        skipPlaceholders(placeholders)
        guardChatMediaTaps(tap)
        keepChatReelsShut(opener)
        watchChatViewers(viewers)
        refreshFromChats(refreshes)
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

/**
 * The method that registers one of a chat's messages for the chain (LX/02YM;->A01 on 453's 385914557,
 * handed the message wrapper LX/01RZ), the wrapper's field holding the message, the message fields
 * the extension's bridges read, the wrapper's method answering the message's reel item (LX/01RZ;->A0O,
 * LX/03n2 there), the item's [MEDIA] field and the wrapper's field holding the account it was made for
 * (LX/05NJ;->A0z on 456's 386214574).
 */
internal class ReelCollector(
    val collect: MutableMethod,
    val message: FieldReference,
    val fields: MessageFields,
    val reel: MethodReference,
    val media: FieldReference,
    val session: FieldReference,
)

/** The names of the fields of [owner] that [method] touches with [opcode]. */
private fun fieldsTouched(method: Method, opcode: Opcode, owner: String): Set<String> =
    method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        if (instruction.opcode != opcode) return@mapNotNull null
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        if (field.definingClass == owner) field.name else null
    }.toSet()

/**
 * Finds the method that registers a chat's messages for the chain before anything changes. The class
 * holding it has a constructor, handed the session first, that asks the chat reels test [read] and keeps
 * the answer in a flag; the method is the one instance method of that class taking one object and
 * returning nothing that reads such a flag. The object holds one field typed as a subclass of the
 * direct message class [fields] reads (the message itself), with three locals for the hook and nothing
 * jumping to the method's first instruction. The method asks the object, with nothing, for one reel
 * item: an object holding one [MEDIA], which has its `getId()`. The object also holds one public
 * [USER_SESSION], the account whose `userId` a message you sent names as its sender (#738). Fails when
 * any of it isn't so.
 */
internal fun BytecodePatchContext.findReelCollector(
    read: FlagLoad = findChatReels(),
    fields: MessageFields = findMessageFields(),
): ReelCollector {
    val gates = methodsCalling(read.type, read.name).filter {
        it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == USER_SESSION
    }
    val collectors = gates.map { it.definingClass }.distinct().flatMap { owner ->
        val flags = gates.filter { it.definingClass == owner }.flatMap { fieldsTouched(it, Opcode.IPUT_BOOLEAN, owner) }.toSet()
        classDefBy(owner).methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.name != "<init>" && method.returnType == "V" &&
                method.parameterTypes.size == 1 && fieldsTouched(method, Opcode.IGET_BOOLEAN, owner).any { it in flags }
        }
    }
    val collector = collectors.singleOrNull() ?: refuse("expected one method registering a chat's messages for the chain, found ${collectors.size}")
    val where = "${collector.definingClass}->${collector.name}"
    if (collector.localRegisterCount() < 3) refuse("$where has fewer than three locals for the hook")
    if (0 in collector.jumpTargets()) refuse("a jump or exception handler enters $where at its first instruction")

    val wrapper = collector.parameterTypes.single().toString()
    val wrapperClass = classDefByOrNull(wrapper) ?: refuse("$wrapper isn't in this build")
    val held = wrapperClass.fields.filter { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) &&
            generateSequence(field.type) { classDefByOrNull(it)?.superclass }.take(4).any { it == fields.owner }
    }
    val message = held.singleOrNull() ?: refuse("$wrapper holds ${held.size} ${fields.owner} messages, not one")
    val sessions = wrapperClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == USER_SESSION }
    val session = sessions.singleOrNull() ?: refuse("$wrapper holds ${sessions.size} accounts, not one")
    if (!AccessFlags.PUBLIC.isSet(session.accessFlags)) refuse("$wrapper's account ${session.name} isn't public")
    val account = classDefByOrNull(USER_SESSION) ?: refuse("there's no $USER_SESSION")
    if (account.fields.none { !AccessFlags.STATIC.isSet(it.accessFlags) && it.name == "userId" && it.type == STRING }) {
        refuse("$USER_SESSION has no userId")
    }

    val reels = collector.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@mapNotNull null
        val call = (instruction as ReferenceInstruction).reference as MethodReference
        if (call.definingClass != wrapper || call.parameterTypes.isNotEmpty() || !call.returnType.startsWith("L")) return@mapNotNull null
        mediaOf(call.returnType)?.let { call to it }
    }.distinctBy { "${it.first}" }
    val (reel, media) = reels.singleOrNull() ?: refuse("$where asks $wrapper for ${reels.size} reel items holding a $MEDIA, not one")
    val ids = classDefByOrNull(MEDIA)?.methods?.count { method ->
        method.name == "getId" && method.parameterTypes.isEmpty() && method.returnType == "Ljava/lang/String;"
    } ?: 0
    if (ids != 1) refuse("$MEDIA has no getId() for the extension to key a sent reel by")

    val takes = collector.parameterTypes.map(CharSequence::toString)
    val mutable = mutableClassDefBy(collector.definingClass).methods.single { method ->
        method.name == collector.name && method.parameterTypes.map(CharSequence::toString) == takes
    }
    return ReelCollector(mutable, message, fields, reel, media, session)
}

/** The one instance [MEDIA] field of [type], or null when it has none or more than one. */
private fun BytecodePatchContext.mediaOf(type: String): FieldReference? =
    classDefByOrNull(type)?.fields?.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == MEDIA }?.singleOrNull()

/**
 * Checks, before anything changes, that the adapter's add [placeholders] found takes the reel items
 * the [collector]'s wrapper answers: its code casts what it's handed to that class.
 */
internal fun checkAddTakesReels(placeholders: Placeholders, collector: ReelCollector) {
    val item = collector.media.definingClass
    val add = placeholders.add
    val casts = add.implementation?.instructions?.any { instruction ->
        instruction.opcode == Opcode.CHECK_CAST && "${(instruction as ReferenceInstruction).reference}" == item
    } == true
    if (!casts) refuse("${add.definingClass}->${add.name} doesn't take $item, the reel items a chat's messages hold")
}

/**
 * Has the collector ask the extension through [RECEIVED] first, handing it the message, its wrapper and
 * itself, one collector per chat (#658), and return on a no, so a message the extension turns away never
 * joins the chain. Also writes the
 * bodies of the extension's message bridges, `sentByViewer`, `senderId` and `itemId` from the fields the
 * chat history reads, `viewerId` from the wrapper's account, `reel` from the wrapper's reel item and
 * `mediaId` from an item's [MEDIA]. The sent-by-you flag isn't set on every copy of a message, the one
 * Instagram makes for a message you've just sent included, so the extension also compares the sender
 * with the account, as Instagram does (#738).
 */
internal fun BytecodePatchContext.skipSentReels(found: ReelCollector = findReelCollector()) {
    val collect = found.collect
    collect.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v1, p1
            iget-object v0, v1, ${found.message.definingClass}->${found.message.name}:${found.message.type}
            move-object/from16 v2, p0
            invoke-static { v0, v1, v2 }, $RECEIVED
            move-result v0
            if-nez v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", collect.getInstruction(0)),
    )
    val stubs = mutableClassDefBy(CHAT_REELS).methods
    fun bridge(name: String, returns: String) = stubs.singleOrNull {
        it.name == name && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") && it.returnType == returns &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("the extension has no public static $name bridge")
    val fields = found.fields
    bridge("sentByViewer", "Z").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${fields.owner}
            iget-boolean p0, p0, ${fields.sent.definingClass}->${fields.sent.name}:Z
            return p0
        """.trimIndent(),
    )
    bridge("senderId", STRING).addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${fields.owner}
            iget-object p0, p0, ${fields.sender.definingClass}->${fields.sender.name}:${fields.sender.type}
            return-object p0
        """.trimIndent(),
    )
    // No account goes back as a fresh null, so the return never merges a UserSession with a String.
    val session = found.session
    bridge("viewerId", STRING).addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${session.definingClass}
            iget-object p0, p0, ${session.definingClass}->${session.name}:${session.type}
            if-nez p0, :account
            const/4 p0, 0x0
            return-object p0
            :account
            iget-object p0, p0, $USER_ID
            return-object p0
        """.trimIndent(),
    )
    bridge("itemId", "Ljava/lang/String;").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${fields.owner}
            iget-object p0, p0, ${fields.id.definingClass}->${fields.id.name}:${fields.id.type}
            return-object p0
        """.trimIndent(),
    )
    bridge("reel", "Ljava/lang/Object;").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.reel.definingClass}
            invoke-virtual { p0 }, ${found.reel}
            move-result-object p0
            return-object p0
        """.trimIndent(),
    )
    // A null media goes back as a fresh null, so the return never merges a Media with a String.
    bridge("mediaId", "Ljava/lang/String;").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.media.definingClass}
            iget-object p0, p0, ${found.media}
            if-nez p0, :read
            const/4 p0, 0x0
            return-object p0
            :read
            invoke-virtual { p0 }, $MEDIA_ID
            move-result-object p0
            return-object p0
        """.trimIndent(),
    )
}

/**
 * Has the adapter's add, update and insert at a position hand the extension the reels they're handed,
 * through [LEAVE_OUT_SENT] with the viewer's source, and take what comes back instead. A viewer opened
 * from a chat pages the chat's own reels from the server, newest messages included, past the collector
 * [skipSentReels] hooks, so with "Only received reels" on the extension leaves out the reels the viewer
 * sent there. A page that answers a request goes in through the update, merged with the reels already
 * there, so all three ways in are asked (#460). A config that isn't there goes on to Instagram.
 */
internal fun BytecodePatchContext.leaveOutSentReels(found: Placeholders = findPlaceholders()) {
    for (way in listOf(found.add, found.update, found.insert)) {
        way.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                iget-object v0, v0, ${found.config}
                if-eqz v0, :instagram
                iget-object v0, v0, ${found.source}
                move-object/from16 v1, p1
                invoke-static { v1, v0 }, $LEAVE_OUT_SENT
                move-result-object v1
                move-object/from16 p1, v1
            """,
            ExternalLabel("instagram", way.getInstruction(0)),
        )
    }
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
 * one method loading [CHAT_REELS_PATH] or, where a static builder loads it, the page [chatReelsPage]
 * finds calling that builder, in a class that is that source. Falling through asks for the
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

    // A pool's own getter holds every string it pools, so it's left out.
    val asks = classesLoadingString(CHAT_REELS_PATH).flatMap { holder ->
        holder.methods.filter { method ->
            !(method.parameterTypes.map(Any::toString) == listOf("I") && method.returnType == "Ljava/lang/String;") &&
                loadsString(method, CHAT_REELS_PATH)
        }
    }
    val ask = asks.singleOrNull() ?: refuse("expected one method asking for $CHAT_REELS_PATH, found ${asks.size}")
    val page = if (AccessFlags.STATIC.isSet(ask.accessFlags)) chatReelsPage(ask, source) else ask
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
 * The page of the source of a chat's reels where [builder], a static request builder, is what asks
 * for [CHAT_REELS_PATH], as on 456. The page is the one method of a [source] that calls [builder]
 * and no other static method answering what it answers. The blend source calls [builder] too, for a
 * blend opened from a chat, but asks for blends otherwise, so it isn't the chat's own.
 */
private fun BytecodePatchContext.chatReelsPage(builder: Method, source: String): Method {
    val takes = builder.parameterTypes.map(Any::toString)
    fun requestAt(instruction: Instruction): MethodReference? {
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return null
        return ((instruction as ReferenceInstruction).reference as? MethodReference)?.takeIf { it.returnType == builder.returnType }
    }
    val pages = classesCalling(builder.definingClass, builder.name, takes, builder.returnType)
        .filter { source in it.interfaces }
        .flatMap { it.methods }
        .filter { method ->
            val requests = method.implementation?.instructions?.mapNotNull(::requestAt).orEmpty()
            requests.isNotEmpty() && requests.all { request ->
                request.definingClass == builder.definingClass && request.name == builder.name &&
                    request.parameterTypes.map(Any::toString) == takes
            }
        }
    return pages.singleOrNull() ?: refuse(
        "expected one $source asking only ${builder.definingClass}->${builder.name} for $CHAT_REELS_PATH, found ${pages.size}",
    )
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
 * The viewer's request for more reels, the viewer's [VIEWER_CONFIG] field it reads, the config's
 * [DIRECT_DATA] field, the viewer's fetcher field the request asks, the fetcher's source field, and
 * the class of the source of a chat's reels.
 */
internal class MoreReels(
    val request: MutableMethod,
    val config: String,
    val direct: String,
    val fetcher: String,
    val source: String,
    val chat: String,
)

/**
 * Finds the viewer's request for more reels before anything changes: the one method loading
 * [MORE_REELS], an instance method returning nothing, with a local for the hook's answer and nothing
 * jumping to its first instruction. It reads one [VIEWER_CONFIG] field of its own class, and the
 * config holds one [DIRECT_DATA] field. It asks for the reels through a fetcher, a field of its own
 * class it calls the dual paging request's class [end] found on, and that class holds one field for
 * the source it pages, typed as that request's source or one of the interfaces it extends. Fails
 * when any of it isn't so.
 */
internal fun BytecodePatchContext.findMoreReels(end: ChatReelsEnd = findChatReelsEnd()): MoreReels {
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

    val pagingClass = end.paging.definingClass
    val code = request.implementation!!.instructions.toList()
    val fetchers = code.indices.mapNotNull { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        if (call?.definingClass != pagingClass) return@mapNotNull null
        val receiver = when (val invoke = code[at]) {
            is RegisterRangeInstruction -> invoke.startRegister
            is FiveRegisterInstruction -> invoke.registerC
            else -> return@mapNotNull null
        }
        fetcherRead(code, at, receiver, request.definingClass)
    }.distinct()
    val fetcher = fetchers.singleOrNull()
        ?: refuse("$where asks $pagingClass through ${fetchers.size} fields of its viewer, not one")
    val sourceType = end.paging.parameterTypes.first().toString()
    val sourceTypes = setOf(sourceType) + classDefByOrNull(sourceType)?.interfaces.orEmpty()
    val sources = classDefBy(pagingClass).fields.filter { it.type in sourceTypes && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val source = sources.singleOrNull() ?: refuse("$pagingClass holds ${sources.size} fields for the source it pages, not one")

    val takes = request.parameterTypes.map(CharSequence::toString)
    val mutable = mutableClassDefBy(request.definingClass).methods.single { method ->
        method.name == request.name && method.parameterTypes.map(CharSequence::toString) == takes
    }
    return MoreReels(
        mutable, config, "$VIEWER_CONFIG->${direct.name}:$DIRECT_DATA", fetcher,
        "$pagingClass->${source.name}:${source.type}", end.chat,
    )
}

/**
 * The field of [owner] that the call at [at] in [code] was handed its receiver from, register
 * [receiver]: the `iget-object` writing that register last before the call. Null when something
 * else wrote it there.
 */
private fun fetcherRead(code: List<Instruction>, at: Int, receiver: Int, owner: String): String? {
    for (index in at - 1 downTo 0) {
        val instruction = code[index] as? OneRegisterInstruction ?: continue
        if (instruction.registerA != receiver) continue
        if (instruction.opcode != Opcode.IGET_OBJECT) return null
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        return if (field.definingClass == owner) "$field" else null
    }
    return null
}

/**
 * Asks the extension through [STOP] first in the viewer's request for more reels, only when the
 * viewer was opened from a chat (its config holds the chat). A yes returns before anything is asked
 * for, so the viewer ends after the reels it was opened with, the chat's own. A viewer paging the
 * source of a chat's reels goes on to Instagram whatever the answer, since paging the chat's own
 * reels comes through here too, and [stopAfterChatReels] stops that source's second side. Everything
 * else goes on to Instagram.
 */
internal fun BytecodePatchContext.stopMoreReels(found: MoreReels = findMoreReels()) {
    val request = found.request
    request.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${found.fetcher}
            if-eqz v0, :ask
            iget-object v0, v0, ${found.source}
            instance-of v0, v0, ${found.chat}
            if-nez v0, :instagram
            :ask
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
internal class Placeholders(
    val add: MutableMethod,
    val shimmer: MutableMethod,
    val config: String,
    val source: String,
    val update: MutableMethod,
    val insert: MutableMethod,
)

/**
 * Finds the adapter's add of reels and its placeholder at a position before anything changes. The
 * add is the one method loading [ADD_REELS], an instance method handed reels and a flag and returning
 * nothing, with two locals for the hook and nothing jumping to its first instruction. It reads one
 * [VIEWER_CONFIG] field of its own class and one [VIEWER_SOURCE] field of the config. The placeholder
 * (LX/044l;->A0X on 385914557) is the one instance method of the same class handed a position that
 * calls the add, with a local and nothing jumping to its first instruction. The adapter's two other
 * ways in for reels go past the add: its update (LX/044l;->A0b there, ClipsViewerAdapter
 * updateClipsItems), the one other instance method handed reels and a flag that calls the add, which
 * swaps the whole list, and its insert at a position (LX/044l;->A0Y), the one instance method handed
 * reels and an int that reads the field the update hands its reels to (LX/044l;->A0Z). The adapter's
 * insert listener (LX/044l;->FjX) takes reels and an int too and never reads it. Each needs two locals
 * and nothing jumping to its first instruction. Fails when any of it isn't so.
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

    fun wayIn(what: String, wanted: List<String>, extra: (Method) -> Boolean): Method {
        val found = classDefBy(add.definingClass).methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.name != add.name &&
                method.parameterTypes.map(CharSequence::toString) == wanted && extra(method)
        }
        val way = found.singleOrNull()
            ?: refuse("expected one $what of ${add.definingClass} handed ${wanted.joinToString("")}, found ${found.size}")
        val there = "${way.definingClass}->${way.name}"
        if (way.localRegisterCount() < 2) refuse("$there has fewer than two locals for the hook")
        if (0 in way.jumpTargets()) refuse("a jump or exception handler enters $there at its first instruction")
        return way
    }
    val update = wayIn("update calling ${add.name}", takes) { method ->
        method.implementation?.instructions?.any { instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            call != null && call.definingClass == add.definingClass && call.name == add.name &&
                call.parameterTypes.map(CharSequence::toString) == takes
        } == true
    }
    // The update hands its reels (p1) to a call on one field of the adapter, the list the insert also
    // reads. The adapter's insert listener takes reels and an int too but never touches that field.
    val code = update.implementation!!.instructions.toList()
    val reels = update.implementation!!.registerCount - 2
    val fields = code.withIndex().mapNotNull { (index, instruction) ->
        if (instruction.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        if (field.definingClass != add.definingClass) return@mapNotNull null
        val into = (instruction as TwoRegisterInstruction).registerA
        val call = code.drop(index + 1).firstOrNull { later ->
            later is FiveRegisterInstruction && later.registerCount > 1 && later.registerC == into &&
                (later as ReferenceInstruction).reference is MethodReference
        } as FiveRegisterInstruction? ?: return@mapNotNull null
        val handed = listOf(call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount - 1)
        if (reels in handed) "$field" else null
    }.distinct()
    val items = fields.singleOrNull()
        ?: refuse("${update.definingClass}->${update.name} hands its reels to ${fields.size} fields of its adapter, not one")
    val insert = wayIn("insert at a position reading $items", listOf(LIST, "I")) { method ->
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.IGET_OBJECT && "${(instruction as ReferenceInstruction).reference}" == items
        } == true
    }

    val methods = mutableClassDefBy(add.definingClass).methods
    fun mutable(method: Method) = methods.single { m ->
        m.name == method.name && m.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }
    return Placeholders(mutable(add), mutable(shimmer), config, source, mutable(update), mutable(insert))
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

/** The config's [VIEWER_SOURCE] field, of which it has to hold one. */
private fun BytecodePatchContext.configSource(): String {
    val sources = classDefBy(VIEWER_CONFIG).fields.filter { it.type == VIEWER_SOURCE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val source = sources.singleOrNull() ?: refuse("$VIEWER_CONFIG holds ${sources.size} $VIEWER_SOURCE fields, not one")
    return "$VIEWER_CONFIG->${source.name}:$VIEWER_SOURCE"
}

/**
 * The viewer config's permission to load fresh reels at a pull down, the config's [VIEWER_SOURCE]
 * field, and the methods outside the config reading the permission where a hook can follow.
 */
internal class RefreshReads(val flag: String, val source: String, val readers: List<MutableMethod>)

/** Whether [instruction] reads [flag] into a register other than the config's, where a hook can follow. */
private fun readsFlag(instruction: Instruction, flag: String): Boolean {
    if (instruction.opcode != Opcode.IGET_BOOLEAN) return false
    val read = instruction as TwoRegisterInstruction
    return "${(instruction as ReferenceInstruction).reference}" == flag && read.registerA != read.registerB &&
        read.registerA <= 15 && read.registerB <= 15
}

/**
 * Finds every read of the viewer config's permission to refresh before anything changes. The
 * permission is the first [VIEWER_CONFIG] boolean the viewer's refresh, the one method loading
 * [REFRESH_NOT_ALLOWED], reads, and the refresh has to test it with an `if-eqz` right after. The
 * refresh's read and the others outside the config (six methods on 453's 385914557, one of them the
 * pager setup, which turns the pull down on with it) are each read into a register other than the
 * config's, which the hook needs. Fails when the refresh or its read isn't so.
 */
internal fun BytecodePatchContext.findRefreshReads(): RefreshReads {
    val refreshes = classesLoadingString(REFRESH_NOT_ALLOWED).flatMap { holder -> holder.methods.filter { loadsString(it, REFRESH_NOT_ALLOWED) } }
    val refresh = refreshes.singleOrNull() ?: refuse("expected one method loading \"$REFRESH_NOT_ALLOWED\", found ${refreshes.size}")
    val where = "${refresh.definingClass}->${refresh.name}"
    val code = refresh.implementation!!.instructions.toList()
    val read = code.indexOfFirst { instruction ->
        instruction.opcode == Opcode.IGET_BOOLEAN &&
            ((instruction as ReferenceInstruction).reference as FieldReference).definingClass == VIEWER_CONFIG
    }
    val test = code.getOrNull(read + 1)
    if (read < 0 || test?.opcode != Opcode.IF_EQZ ||
        (test as OneRegisterInstruction).registerA != (code[read] as OneRegisterInstruction).registerA
    ) {
        refuse("$where doesn't test a $VIEWER_CONFIG boolean first, its permission to refresh")
    }
    val field = (code[read] as ReferenceInstruction).reference as FieldReference
    val flag = "$field"
    if (!readsFlag(code[read], flag)) refuse("$where reads $flag into the config's own register")
    val source = configSource()

    val readers = classesAccessing(VIEWER_CONFIG, field.name, Opcode.IGET_BOOLEAN).filter { it.type != VIEWER_CONFIG }.flatMap { holder ->
        mutableClassDefBy(holder.type).methods.filter { method ->
            method.implementation?.instructions?.any { readsFlag(it, flag) } == true
        }
    }
    return RefreshReads(flag, source, readers)
}

/**
 * Has every read [findRefreshReads] found ask the extension through [REFRESH] whether the viewer may
 * still refresh, handing it the config's source, when Instagram says it may. A viewer opened from a
 * chat with the second switch on can't, so a pull down at its first reel doesn't swap the chat's
 * reels for suggested ones. Each read is found again, last first, so the code put after one doesn't
 * move the others.
 */
internal fun BytecodePatchContext.refreshFromChats(found: RefreshReads = findRefreshReads()) {
    found.readers.forEach { method ->
        val code = method.implementation!!.instructions.toList()
        code.indices.filter { readsFlag(code[it], found.flag) }.sortedDescending().forEach { at ->
            val read = code[at] as TwoRegisterInstruction
            method.addInstructionsWithLabels(
                at + 1,
                """
                    if-eqz v${read.registerA}, :done
                    iget-object v${read.registerA}, v${read.registerB}, ${found.source}
                    invoke-static { v${read.registerA} }, $REFRESH
                    move-result v${read.registerA}
                """,
                ExternalLabel("done", method.getInstruction(at + 1)),
            )
        }
    }
}

/**
 * The Reels pager setup, the pager field it stores, the holder's [VIEWER_CONFIG] field, the config's
 * [VIEWER_SOURCE] field, and the registers of the pager, its holder and a free local at the store.
 */
internal class ChatViewers(
    val setup: MutableMethod,
    val field: String,
    val config: String,
    val source: String,
    val pager: Int,
    val holder: Int,
    val free: Int,
)

/**
 * Finds where the Reels viewer stores its pager before anything changes: the setup is
 * [reelsPagerSetup], and it stores one [VIEW_PAGER] field, with nothing jumping to the store. The
 * field's class holds one [VIEWER_CONFIG] field, and a local up to v15 is free at the store. Fails
 * when any of it isn't so.
 */
internal fun BytecodePatchContext.findChatViewers(): ChatViewers {
    val setup = reelsPagerSetup(reelsPager(::refuse), ::refuse)
    val where = "${setup.definingClass}->${setup.name}"
    val code = setup.implementation!!.instructions.toList()
    val stores = code.indices.filter { at ->
        code[at].opcode == Opcode.IPUT_OBJECT && ((code[at] as ReferenceInstruction).reference as FieldReference).type == VIEW_PAGER
    }
    val store = stores.singleOrNull() ?: refuse("expected $where to store one $VIEW_PAGER, found ${stores.size}")
    if (store in setup.jumpTargets()) refuse("something in $where jumps to its store of the pager")
    val field = (code[store] as ReferenceInstruction).reference as FieldReference
    val configs = classDefBy(field.definingClass).fields.filter { it.type == VIEWER_CONFIG && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val config = configs.singleOrNull() ?: refuse("${field.definingClass} holds ${configs.size} $VIEWER_CONFIG fields, not one")
    val stored = code[store] as TwoRegisterInstruction
    val free = setup.freeLocalsAt(PATCH, store, 1, except = listOf(stored.registerA, stored.registerB)).single()

    val takes = setup.parameterTypes.map(CharSequence::toString)
    val mutable = mutableClassDefBy(setup.definingClass).methods.single { method ->
        method.name == setup.name && method.parameterTypes.map(CharSequence::toString) == takes
    }
    return ChatViewers(
        mutable, "$field", "${field.definingClass}->${config.name}:$VIEWER_CONFIG", configSource(),
        stored.registerA, stored.registerB, free,
    )
}

/**
 * Hands each Reels viewer's pager to the extension through [VIEWER] with the viewer's source, just
 * before the setup stores it, so a swipe up past a chat's last reel can go back to the chat. The
 * store is found again, since other patches put code into the same setup.
 */
internal fun BytecodePatchContext.watchChatViewers(found: ChatViewers = findChatViewers()) {
    val setup = found.setup
    val store = setup.implementation!!.instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.IPUT_OBJECT && "${(instruction as ReferenceInstruction).reference}" == found.field
    }
    if (store < 0) refuse("${setup.definingClass}->${setup.name} no longer stores ${found.field}")
    setup.addInstructionsWithLabels(
        store,
        """
            iget-object v${found.free}, v${found.holder}, ${found.config}
            if-eqz v${found.free}, :done
            iget-object v${found.free}, v${found.free}, ${found.source}
            invoke-static { v${found.pager}, v${found.free} }, $VIEWER
        """,
        ExternalLabel("done", setup.getInstruction(store)),
    )
}

/**
 * The reply bar under a reel opened from a chat, ClipsViewerDirectReplyBarController (LX/0T5D on 453's
 * 385914557, LX/0I4a on 456's 386214574): its hint update for a page (updateHintText), the method that
 * reads the page's info for it ([hintAsks] on 455, the hint update itself up to 454 and again on 456),
 * and its reply info for the reel on screen (getCurrentClipsReshareInfo, loading [REPLY_INFO_MISSING]),
 * each with the register holding Instagram's info where its ways in meet and the locals the hook
 * borrows there, its reel on screen (getCurrentClipsItem), the info class with its
 * constructor and message field, the fields leading from the controller to its adapter (one up to 455,
 * the controller's viewer state and then its adapter on 456) with the adapter's count and item at a
 * position, and the controller's [VIEWER_SOURCE] field. Also the collector's register holding the info it
 * just built and the two locals its hook borrows, for the message's wrapper and the collector itself.
 */
internal class ReplyBar(
    val hint: MutableMethod,
    val hintAsks: MutableMethod,
    val hintInfo: Int,
    val hintFree: List<Int>,
    val current: MutableMethod,
    val currentInfo: Int,
    val currentFree: List<Int>,
    val currentItem: String,
    val info: String,
    val init: String,
    val message: FieldReference,
    val adapter: List<FieldReference>,
    val count: MethodReference,
    val item: MethodReference,
    val source: String,
    val built: Int,
    val wrapper: Int,
    val chat: Int,
)

private val INFO_TESTS = setOf(Opcode.IF_EQZ, Opcode.IF_NEZ)

/** The smali form of [method]'s reference. */
private fun replyCall(method: Method) =
    "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/**
 * Where [method]'s ways to its reply info meet: the one null test of a register, up to v15, that
 * something jumps to, with at least one way in leaving a [info] in that register just before it,
 * cast to it or answered by a call, in the way's last instruction or the one before its goto. On 456
 * the reply info for the reel on screen casts one way's info and goes to the test, and the hint update
 * gets it from a call on two of its three ways. Null when there isn't exactly one.
 */
private fun replyJoin(method: Method, info: String): Int? {
    if (method.implementation == null) return null
    val flow = ControlFlow.of(method)
    val code = flow.instructions
    val ways = Array(code.size) { mutableListOf<Int>() }
    code.indices.forEach { from -> flow.normal[from].forEach { ways[it] += from } }
    fun leavesInfo(at: Int, register: Int): Boolean {
        val instruction = code.getOrNull(at) ?: return false
        if ((instruction as? OneRegisterInstruction)?.registerA != register) return false
        return when (instruction.opcode) {
            Opcode.CHECK_CAST -> "${(instruction as ReferenceInstruction).reference}" == info
            Opcode.MOVE_RESULT_OBJECT -> ((code.getOrNull(at - 1) as? ReferenceInstruction)?.reference as? MethodReference)?.returnType == info
            else -> false
        }
    }
    val targets = method.jumpTargets()
    return code.indices.filter { at ->
        val test = code[at]
        if (test.opcode !in INFO_TESTS || at !in targets) return@filter false
        val register = (test as OneRegisterInstruction).registerA
        register <= 15 && ways[at].any { way ->
            val last = if (code[way].opcode in GOTOS && ways[way] == listOf(way - 1)) way - 1 else way
            leavesInfo(last, register)
        }
    }.singleOrNull()
}

/** The register [replyJoin] found [at] in [method] tests. */
private fun replyRegister(method: Method, at: Int) =
    (method.implementation!!.instructions.elementAt(at) as OneRegisterInstruction).registerA

/**
 * The one instance method of [hint]'s class taking a page that [hint] hands its own this and page to,
 * and that has one place its ways to [info] meet, with the calls handing them over. From 455 the hint
 * update leaves reading the page's info to handleReshareChaining (`LX/0TRG;->A1J(I)Z` on 386113969,
 * which casts it at 21 and tests it at 22). Null when there isn't exactly one.
 */
private fun BytecodePatchContext.hintAsks(hint: Method, info: String): Pair<Method, List<Int>>? {
    val code = hint.implementation?.instructions?.toList() ?: return null
    val self = hint.localRegisterCount()
    val page = hint.parameterRegisterNumber(0)
    val handed = code.indices.mapNotNull { at ->
        val call = code[at]
        val reference = (call as? ReferenceInstruction)?.reference as? MethodReference
        if (call.opcode != Opcode.INVOKE_VIRTUAL || reference == null || reference.definingClass != hint.definingClass ||
            reference.parameterTypes.map(CharSequence::toString) != listOf("I")
        ) return@mapNotNull null
        val registers = call as FiveRegisterInstruction
        if (registers.registerCount != 2 || registers.registerC != self || registers.registerD != page) null else reference to at
    }
    val asked = handed.groupBy { (reference, _) -> "$reference" }.mapNotNull { (_, calls) ->
        val reference = calls.first().first
        val method = classDefBy(hint.definingClass).methods.singleOrNull { method ->
            method.name == reference.name && method.returnType == reference.returnType && !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.parameterTypes.map(CharSequence::toString) == listOf("I")
        }
        if (method == null || replyJoin(method, info) == null) null else method to calls.map { it.second }
    }
    return asked.singleOrNull()
}

/**
 * The object fields [code] reads, in order, from what [start] holds on entry to the object in [register]
 * at [at], walking back through the instructions before it. Null when something else writes one of them
 * or [start] is written before its read.
 */
private fun fieldsTo(code: List<Instruction>, at: Int, register: Int, start: Int): List<FieldReference>? {
    fun writes(index: Int, register: Int): Boolean {
        val instruction = code[index]
        val destination = (instruction as? OneRegisterInstruction)?.registerA ?: return false
        return instruction.opcode.setsRegister() &&
            (destination == register || (instruction.opcode.setsWideRegister() && destination + 1 == register))
    }
    val path = ArrayDeque<FieldReference>()
    var wanted = register
    var index = at - 1
    while (wanted != start) {
        val write = (index downTo 0).firstOrNull { writes(it, wanted) } ?: return null
        if (code[write].opcode != Opcode.IGET_OBJECT) return null
        path.addFirst((code[write] as ReferenceInstruction).reference as FieldReference)
        wanted = (code[write] as TwoRegisterInstruction).registerB
        index = write - 1
    }
    return if (path.isEmpty() || (0..index).any { writes(it, start) }) null else path.toList()
}

/** The virtual calls in [code]. */
private fun virtualCalls(code: List<Instruction>) = code.mapNotNull { instruction ->
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) null else (instruction as ReferenceInstruction).reference as MethodReference
}.distinctBy { "$it" }

/**
 * Finds the reply bar under a reel opened from a chat before anything changes. The reply info for the
 * reel on screen is the one method loading [REPLY_INFO_MISSING], a static one taking the controller, its
 * class, and answering the info. The controller has one instance method taking an int page and returning
 * nothing, the hint update, and one static method taking it and answering the [collector]'s reel item,
 * the reel on screen. The item comes from one virtual call on an adapter taking a position, next to one call of the adapter's
 * count, on one field of the controller, and the controller keeps one [VIEWER_SOURCE]. The info class
 * has one constructor, storing its first String, the message, in one field, and the collector builds
 * one with two free locals after it where nothing jumps. Both reply methods test the info they cast where
 * their two ways in meet, with free locals there, the hint update through [hintAsks] where it doesn't
 * itself. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findReplyBar(collector: ReelCollector = findReelCollector()): ReplyBar {
    fun names(types: List<CharSequence>) = types.map(CharSequence::toString)
    fun takes(method: Method) = names(method.parameterTypes)
    fun isStatic(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags)
    val loaders = classesLoadingString(REPLY_INFO_MISSING).flatMap { it.methods }.filter { method ->
        // A static (int)String pool loads it too.
        !(isStatic(method) && takes(method) == listOf("I") && method.returnType == "Ljava/lang/String;") && loadsString(method, REPLY_INFO_MISSING)
    }
    val current = loaders.singleOrNull()
        ?: refuse("expected one method loading \"$REPLY_INFO_MISSING\", the reply bar's reply info, found ${loaders.size}")
    val controller = current.definingClass
    if (!isStatic(current) || takes(current) != listOf(controller) || !current.returnType.startsWith("L")) {
        refuse("$controller->${current.name} isn't a static method taking $controller and answering an object")
    }
    val methods = classDefBy(controller).methods
    val hints = methods.filter { !isStatic(it) && takes(it) == listOf("I") && it.returnType == "V" && it.implementation != null }
    val hint = hints.singleOrNull() ?: refuse("expected $controller to have one instance (I)V method, its hint update, found ${hints.size}")
    val itemType = collector.media.definingClass
    val currentItems = methods.filter { isStatic(it) && takes(it) == listOf(controller) && it.returnType == itemType }
    val currentItem = currentItems.singleOrNull()
        ?: refuse("expected $controller to have one static method taking it and answering a $itemType, its reel on screen, found ${currentItems.size}")

    val itemCode = currentItem.implementation?.instructions?.toList().orEmpty()
    val items = virtualCalls(itemCode).filter { names(it.parameterTypes) == listOf("I") && it.returnType == itemType }
    val item = items.singleOrNull() ?: refuse("$controller->${currentItem.name} asks for ${items.size} reel items at a position, not one")
    val counts = virtualCalls(itemCode).filter { it.definingClass == item.definingClass && it.parameterTypes.isEmpty() && it.returnType == "I" }
    val count = counts.singleOrNull() ?: refuse("$controller->${currentItem.name} asks ${item.definingClass} for ${counts.size} counts, not one")
    val asked = itemCode.indexOfFirst { it.opcode == Opcode.INVOKE_VIRTUAL && "${(it as ReferenceInstruction).reference}" == "$item" }
    val adapter = (itemCode[asked] as? FiveRegisterInstruction)?.let { call ->
        fieldsTo(itemCode, asked, call.registerC, currentItem.parameterRegisterNumber(0))
    } ?: refuse("$controller->${currentItem.name} doesn't reach its ${item.definingClass} through fields of $controller")
    for (field in adapter) {
        val declared = classDefByOrNull(field.definingClass)?.fields?.singleOrNull { it.name == field.name && it.type == field.type }
        if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
            refuse("$field, on the way to the reply bar's adapter, isn't a public instance field the extension can read")
        }
    }
    val sources = classDefBy(controller).fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == VIEWER_SOURCE }
    val source = sources.singleOrNull() ?: refuse("$controller holds ${sources.size} $VIEWER_SOURCE fields, not one")

    val info = current.returnType
    val inits = (classDefByOrNull(info) ?: refuse("$info isn't in this build")).methods.filter { it.name == "<init>" }
    val init = inits.singleOrNull() ?: refuse("$info has ${inits.size} constructors, not one")
    val params = takes(init)
    val first = params.indexOf("Ljava/lang/String;")
    if (first < 0) refuse("$info's constructor takes no String message")
    fun width(type: String) = if (type == "J" || type == "D") 2 else 1
    val self = init.implementation!!.registerCount - 1 - params.sumOf { width(it) }
    val handed = self + 1 + params.take(first).sumOf { width(it) }
    val messages = init.implementation!!.instructions.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.IPUT_OBJECT) return@mapNotNull null
        val stored = instruction as TwoRegisterInstruction
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        if (stored.registerA == handed && stored.registerB == self && field.definingClass == info) field else null
    }.distinctBy { "$it" }
    val message = messages.singleOrNull() ?: refuse("$info's constructor stores its message in ${messages.size} fields, not one")

    val collect = collector.collect
    val where = "${collect.definingClass}->${collect.name}"
    val initCall = replyCall(init)
    val collectCode = collect.implementation!!.instructions.toList()
    val builds = collectCode.indices.filter { at ->
        collectCode[at].opcode == Opcode.INVOKE_DIRECT && "${(collectCode[at] as ReferenceInstruction).reference}" == initCall
    }
    val built = builds.singleOrNull() ?: refuse("$where builds ${builds.size} $info, not one")
    if (built + 1 >= collectCode.size || built + 1 in collect.jumpTargets()) refuse("something in $where jumps to just after it builds a $info")
    val infoRegister = (collectCode[built] as FiveRegisterInstruction).registerC
    val (wrapper, chat) = collect.freeLocalsAt(PATCH, built + 1, 2, except = listOf(infoRegister))

    val (asks, handing) = if (replyJoin(hint, info) != null) hint to emptyList() else {
        hintAsks(hint, info) ?: refuse("$controller->${hint.name} has no one place its ways to a $info meet")
    }
    val hintJoin = replyJoin(asks, info)!!
    val hintInfo = replyRegister(asks, hintJoin)
    val hintFree = asks.freeLocalsAt(PATCH, hintJoin, 3, except = listOf(hintInfo))
    val currentJoin = replyJoin(current, info) ?: refuse("$controller->${current.name} has no one place its ways to a $info meet")
    val currentInfo = replyRegister(current, currentJoin)
    val currentFree = current.freeLocalsAt(PATCH, currentJoin, 2, except = listOf(currentInfo))
    // Each hook reads a parameter where it goes in: the collector's message wrapper and the collector,
    // the hint's controller and page, and the reply info's controller. Nothing before may write over them.
    collect.requireParameterIntact(PATCH, 0, listOf(built + 1))
    collect.requireThisIntact(PATCH, listOf(built + 1))
    asks.requireThisIntact(PATCH, listOf(hintJoin))
    asks.requireParameterIntact(PATCH, 0, listOf(hintJoin))
    if (handing.isNotEmpty()) {
        hint.requireThisIntact(PATCH, handing)
        hint.requireParameterIntact(PATCH, 0, handing)
    }
    current.requireParameterIntact(PATCH, 0, listOf(currentJoin))

    val mutable = mutableClassDefBy(controller).methods
    fun mutableOf(method: Method) = mutable.single { it.name == method.name && takes(it) == takes(method) }
    return ReplyBar(
        mutableOf(hint), mutableOf(asks), hintInfo, hintFree, mutableOf(current), currentInfo, currentFree, replyCall(currentItem),
        info, initCall, message, adapter, count, item, "$controller->${source.name}:$VIEWER_SOURCE", infoRegister, wrapper, chat,
    )
}

/**
 * Gives each reel swiped to from a chat the reply bar and reactions for its own message there (#488).
 * Instagram keeps the reply info of the reel tapped and asks for it by position, so past the first reel
 * the bar hid, and a quick reaction or a reply typed on another reel quoted the tapped one. The collector,
 * one per chat that goes through the chat's messages as it loads (#658), hands each info it builds to
 * the extension with its message's wrapper and itself through [COLLECTED], and the
 * hint update and the reply info ask [REPLY_TO] and [REPLY_INFO] with Instagram's info, the viewer's
 * source and the reel on screen, and take what comes back. Nothing is sent: the bar only gets the message
 * a reply would quote. Also writes the extension's bridges reading an info's message and an adapter's
 * count and item. The places are found again, since other patches put code into the same methods.
 */
internal fun BytecodePatchContext.giveSwipedReelsTheirReplies(collector: ReelCollector, found: ReplyBar = findReplyBar(collector)) {
    val collect = collector.collect
    val built = collect.implementation!!.instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_DIRECT && "${(instruction as ReferenceInstruction).reference}" == found.init
    }
    if (built < 0) refuse("${collect.definingClass}->${collect.name} no longer builds a ${found.info}")
    collect.addInstructions(
        built + 1,
        """
            move-object/from16 v${found.wrapper}, p1
            move-object/from16 v${found.chat}, p0
            invoke-static { v${found.built}, v${found.wrapper}, v${found.chat} }, $COLLECTED
        """,
    )

    val hint = found.hintAsks
    val hintJoin = replyJoin(hint, found.info) ?: refuse("${hint.definingClass}->${hint.name} no longer tests a ${found.info}")
    val (page, adapter, source) = found.hintFree
    hint.addInstructionsAtControlFlowLabel(
        hintJoin,
        """
            move-object/from16 v$source, p0
            iget-object v$adapter, v$source, ${found.adapter.first()}
            ${found.adapter.drop(1).joinToString("\n") { "iget-object v$adapter, v$adapter, $it" }}
            iget-object v$source, v$source, ${found.source}
            move/from16 v$page, p1
            invoke-static { v${found.hintInfo}, v$source, v$adapter, v$page }, $REPLY_TO
            move-result-object v${found.hintInfo}
            check-cast v${found.hintInfo}, ${found.info}
        """,
    )

    val current = found.current
    val currentJoin = replyJoin(current, found.info) ?: refuse("${current.definingClass}->${current.name} no longer tests a ${found.info}")
    val (viewer, item) = found.currentFree
    current.addInstructionsAtControlFlowLabel(
        currentJoin,
        """
            move-object/from16 v$viewer, p0
            invoke-static { v$viewer }, ${found.currentItem}
            move-result-object v$item
            iget-object v$viewer, v$viewer, ${found.source}
            invoke-static { v${found.currentInfo}, v$viewer, v$item }, $REPLY_INFO
            move-result-object v${found.currentInfo}
            check-cast v${found.currentInfo}, ${found.info}
        """,
    )

    val stubs = mutableClassDefBy(CHAT_REELS).methods
    fun bridge(name: String, takes: List<String>, returns: String) = stubs.singleOrNull {
        it.name == name && it.parameterTypes.map(Any::toString) == takes && it.returnType == returns &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("the extension has no public static $name bridge")
    bridge("messageOf", listOf("Ljava/lang/Object;"), "Ljava/lang/String;").addInstructions(
        0,
        """
            check-cast p0, ${found.info}
            iget-object p0, p0, ${found.message}
            return-object p0
        """,
    )
    bridge("count", listOf("Ljava/lang/Object;"), "I").addInstructions(
        0,
        """
            check-cast p0, ${found.count.definingClass}
            invoke-virtual { p0 }, ${found.count}
            move-result p0
            return p0
        """,
    )
    bridge("item", listOf("Ljava/lang/Object;", "I"), "Ljava/lang/Object;").addInstructions(
        0,
        """
            check-cast p0, ${found.item.definingClass}
            invoke-virtual { p0, p1 }, ${found.item}
            move-result-object p0
            return-object p0
        """,
    )
}

/**
 * What the extension builds a reel's reply info from when nothing it remembered and nothing Instagram
 * kept answers it (#804): the [item] class's fields holding the reel's own message in the chat
 * ([message], its direct_item_id), who sent it ([senders], users) and when ([sent], a Long), and how
 * Instagram's Reels data source builds the info from them, in [builder]: a [sender] made with
 * [senderInit] and given the [user] and the [time], then the [info] made with [init] from the sender,
 * the message, a second String and a list.
 */
internal class ReelMessage(
    val builder: String,
    val item: String,
    val message: FieldReference,
    val senders: FieldReference,
    val sent: FieldReference,
    val sender: String,
    val senderInit: MethodReference,
    val user: FieldReference,
    val time: FieldReference,
    val info: String,
    val init: String,
)

internal const val OWN_MESSAGE = "$CHAT_REELS->ownMessage(Ljava/lang/Object;)Ljava/lang/Object;"

/** How far before Instagram's build of an info its reads of the reel's fields may sit. */
private const val BUILD_WINDOW = 40

/** The registers [instruction], a call, hands over, in order. */
private fun callRegisters(instruction: Instruction): List<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
    ).take(instruction.registerCount)
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    else -> emptyList()
}

/** The field [instruction] reads or writes, when it's [opcode] on a field of [owner] typed [type]. */
private fun fieldOf(instruction: Instruction, opcode: Opcode, owner: String, type: String? = null): FieldReference? {
    if (instruction.opcode != opcode) return null
    val field = (instruction as ReferenceInstruction).reference as? FieldReference ?: return null
    return if (field.definingClass == owner && (type == null || field.type == type)) field else null
}

/**
 * Reads how [method] builds a reply info from a reel item, or null when it doesn't: at its one
 * [init] call, the message String comes from one [item] String field read into that register, the
 * sender is a new [senderType] made with a no-argument constructor and given one User and one long,
 * and the reel's one List field and one Long field are read just before.
 */
private fun readReelMessage(method: Method, item: String, info: String, init: String, senderType: String): ReelMessage? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val calls = code.indices.filter { at ->
        (code[at].opcode == Opcode.INVOKE_DIRECT || code[at].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            "${(code[at] as ReferenceInstruction).reference}" == init
    }
    val call = calls.singleOrNull() ?: return null
    val handed = callRegisters(code[call])
    if (handed.size != 5) return null
    val (senderRegister, messageRegister) = handed[1] to handed[2]
    val window = (call - BUILD_WINDOW).coerceAtLeast(0) until call
    val messages = window.mapNotNull { at ->
        val read = fieldOf(code[at], Opcode.IGET_OBJECT, item, STRING) ?: return@mapNotNull null
        if ((code[at] as TwoRegisterInstruction).registerA == messageRegister) read else null
    }.distinctBy { "$it" }
    val message = messages.singleOrNull() ?: return null
    val made = window.lastOrNull { at ->
        code[at].opcode == Opcode.NEW_INSTANCE && (code[at] as OneRegisterInstruction).registerA == senderRegister &&
            "${(code[at] as ReferenceInstruction).reference}" == senderType
    } ?: return null
    val construct = code.getOrNull(made + 1)?.takeIf { it.opcode == Opcode.INVOKE_DIRECT } ?: return null
    val senderInit = (construct as ReferenceInstruction).reference as? MethodReference ?: return null
    if (senderInit.name != "<init>" || senderInit.parameterTypes.isNotEmpty() || callRegisters(construct) != listOf(senderRegister)) return null
    val users = (made + 2 until call).mapNotNull { at ->
        fieldOf(code[at], Opcode.IPUT_OBJECT, senderType)?.takeIf { (code[at] as TwoRegisterInstruction).registerB == senderRegister }
    }.distinctBy { "$it" }
    val times = (made + 2 until call).mapNotNull { at ->
        fieldOf(code[at], Opcode.IPUT_WIDE, senderType, "J")?.takeIf { (code[at] as TwoRegisterInstruction).registerB == senderRegister }
    }.distinctBy { "$it" }
    val user = users.singleOrNull() ?: return null
    val time = times.singleOrNull() ?: return null
    val before = (made - BUILD_WINDOW).coerceAtLeast(0) until call
    val senders = before.mapNotNull { fieldOf(code[it], Opcode.IGET_OBJECT, item, "Ljava/util/List;") }.distinctBy { "$it" }
    val sent = before.mapNotNull { fieldOf(code[it], Opcode.IGET_OBJECT, item, "Ljava/lang/Long;") }.distinctBy { "$it" }
    if (senders.size != 1 || sent.size != 1) return null
    return ReelMessage(
        "${method.definingClass}->${method.name}", item, message, senders.single(), sent.single(),
        senderType, senderInit, user, time, info, init,
    )
}

/**
 * Finds how Instagram's Reels data source builds the reply info for a reel it pages in from a chat,
 * out of the reel's own message id, sender and time (`LX/07NS;->A1Z` on 456.1's 386215122), before
 * anything changes. The info is the reply bar's, whose one constructor takes a sender, the message, a
 * second String and a list, and exactly one method building one reads them off a reel item, the
 * [collector]'s. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findReelMessage(collector: ReelCollector, replies: ReplyBar): ReelMessage {
    val item = collector.media.definingClass
    val types = classDefBy(replies.info).methods.single { it.name == "<init>" }.parameterTypes.map { it.toString() }
    if (types.size != 4 || types[1] != STRING || types[2] != STRING || types[3] != "Ljava/util/List;") {
        refuse("${replies.info}'s constructor takes (${types.joinToString("")}), not a sender, two Strings and a list")
    }
    val senderType = types[0]
    val builders = classesCalling(replies.info, "<init>").flatMap { it.methods }
        .mapNotNull { readReelMessage(it, item, replies.info, replies.init, senderType) }
    // Builders that read the same fields the same way agree; any other mix is a build this hasn't seen.
    val ways = builders.distinctBy { listOf(it.message, it.senders, it.sent, it.user, it.time, it.senderInit).joinToString(" ") }
    val found = ways.singleOrNull()
        ?: refuse("expected one way to build a ${replies.info} from a $item's own message, found ${ways.size}")
    fun declared(field: FieldReference) = classDefByOrNull(field.definingClass)?.fields
        ?.singleOrNull { it.name == field.name && it.type == field.type }
    for (field in listOf(found.message, found.senders, found.sent)) {
        val flags = declared(field)?.accessFlags
        if (flags == null || !AccessFlags.PUBLIC.isSet(flags) || AccessFlags.STATIC.isSet(flags)) {
            refuse("$field, a reel's own message, isn't a public instance field the extension can read")
        }
    }
    for (field in listOf(found.user, found.time)) {
        val flags = declared(field)?.accessFlags
        if (flags == null || !AccessFlags.PUBLIC.isSet(flags) || AccessFlags.STATIC.isSet(flags) || AccessFlags.FINAL.isSet(flags)) {
            refuse("$field, the sender of a reel's message, isn't a public field the extension can set")
        }
    }
    return found
}

/** [findReelMessage], or null with a warning, so the rest of the patch goes in without it. */
internal fun BytecodePatchContext.findReelMessageOrWarn(collector: ReelCollector, replies: ReplyBar): ReelMessage? = try {
    findReelMessage(collector, replies)
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in, but a reel past the chat's loaded messages keeps Instagram's reply bar.")
    null
}

/**
 * Writes the extension's [OWN_MESSAGE] bridge (#804): the reply info for a reel's own message in the
 * chat, built as Instagram's data source builds it, from the message id, the first sender and the time
 * the reel item carries, with an empty second String and list. Null when the reel carries no message,
 * no sender or no time. Nothing is sent: the reply bar and its quick reactions only learn which message
 * they answer.
 */
internal fun BytecodePatchContext.buildRepliesFromReels(found: ReelMessage) {
    fillStub(
        CHAT_REELS, "ownMessage", "Ljava/lang/Object;", 9,
        """
            check-cast p0, ${found.item}
            iget-object v0, p0, ${found.message}
            if-eqz v0, :none
            invoke-virtual { v0 }, Ljava/lang/String;->isEmpty()Z
            move-result v1
            if-nez v1, :none
            iget-object v1, p0, ${found.senders}
            if-eqz v1, :none
            invoke-interface { v1 }, Ljava/util/List;->isEmpty()Z
            move-result v2
            if-nez v2, :none
            const/4 v2, 0x0
            invoke-interface { v1, v2 }, Ljava/util/List;->get(I)Ljava/lang/Object;
            move-result-object v1
            instance-of v2, v1, ${found.user.type}
            if-eqz v2, :none
            check-cast v1, ${found.user.type}
            iget-object v2, p0, ${found.sent}
            if-eqz v2, :none
            invoke-virtual { v2 }, Ljava/lang/Long;->longValue()J
            move-result-wide v2
            new-instance v4, ${found.sender}
            invoke-direct { v4 }, ${found.senderInit}
            iput-object v1, v4, ${found.user}
            iput-wide v2, v4, ${found.time}
            new-instance v5, ${found.info}
            const-string v6, ""
            invoke-static { }, Ljava/util/Collections;->emptyList()Ljava/util/List;
            move-result-object v7
            invoke-direct { v5, v4, v0, v6, v7 }, ${found.init}
            return-object v5
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )
}

/**
 * The reply bar's page select, 0T5D.Fxj on 453's 385914557 (onPageSelected, `LX/0I4X;->GAC` on 456's
 * 386214574), and where its hint update leaves the controller and the page: the instruction after that
 * call, [at], and the call's own registers, a [range] call's or a five-register one's.
 */
internal class ReplyPage(val select: MutableMethod, val at: Int, val controller: Int, val page: Int, val range: Boolean)

/**
 * Finds the reply bar's page select before anything changes: the one instance method taking an int page
 * first and returning nothing that calls [replies]' hint update, in the controller's class or one it
 * extends. Redex hoists it into the superclass on some builds (454's 386014757 casts this to the
 * controller first), and on others (456) merges the reply bars of two viewers into it behind an
 * instance-of. The method calls the hint update once, handing it the controller and the page parameter,
 * still in its own register, with nothing jumping onto the instruction after the call. The
 * [collector]'s message time is text, for the extension's bridge to answer. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findReplyPage(collector: ReelCollector, replies: ReplyBar): ReplyPage {
    val controller = replies.hint.definingClass
    val hint = replyCall(replies.hint)
    fun callsHint(method: Method) = method.implementation?.instructions?.any { "${(it as? ReferenceInstruction)?.reference}" == hint } == true
    val chain = generateSequence(classDefByOrNull(controller)) { type -> type.superclass?.let { classDefByOrNull(it) } }.toList()
    val selects = chain.flatMap { it.methods }.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.firstOrNull()?.toString() == "I" &&
            method.returnType == "V" && callsHint(method)
    }
    val select = selects.singleOrNull()
        ?: refuse("expected one instance method of $controller or a class it extends taking a page first and returning nothing to call $hint, its page select, found ${selects.size}")
    val where = "${select.definingClass}->${select.name}"
    val takes = select.parameterTypes.map(CharSequence::toString)
    val code = select.implementation?.instructions?.toList() ?: refuse("$where has no code")
    val calls = code.indices.filter { "${(code[it] as? ReferenceInstruction)?.reference}" == hint }
    val call = calls.singleOrNull() ?: refuse("expected $where to call $hint once, found ${calls.size}")
    val page = select.parameterRegisterNumber(0)
    val (held, handed, range) = when (val instruction = code[call]) {
        is FiveRegisterInstruction -> Triple(instruction.registerC, instruction.registerD, false)
        is RegisterRangeInstruction -> Triple(instruction.startRegister, instruction.startRegister + 1, true)
        else -> refuse("$where calls $hint in a form it can't read")
    }
    if (handed != page) refuse("$where doesn't hand its page to $hint")
    select.requireParameterIntact("$where hands $hint its page", 0, listOf(call))
    val at = call + 1
    if (at >= code.size || at in select.jumpTargets()) refuse("a jump or exception handler enters $where right after it calls $hint")
    val time = collector.fields.time
    if (time.type != "Ljava/lang/String;") refuse("the message keeps its time as ${time.type}, not as text")
    val mutable = mutableClassDefBy(select.definingClass).methods.single { it.name == select.name && it.parameterTypes.map(CharSequence::toString) == takes }
    return ReplyPage(mutable, at, held, page, range)
}

/**
 * Points out a message sent after the reel on screen (#489). Swiping through a chat's reels, what the
 * other person wrote between them went by unseen. The reply bar's page select hands the controller and
 * its page to [NOTICE_PAGE] first, and the extension, which the collector already hands each of the
 * chat's messages through [RECEIVED], shows a banner over the viewer when the other person wrote after
 * that reel; a tap goes back to the chat at that message. Nothing is sent. Also writes the extension's
 * bridges answering a message's time and whether an object is a message, from the fields the chat
 * history reads, and the controller's [VIEWER_SOURCE] and adapter.
 */
internal fun BytecodePatchContext.pointOutMessagesAfterReels(collector: ReelCollector, replies: ReplyBar, found: ReplyPage) {
    val hook = if (found.range) "invoke-static/range { v${found.controller} .. v${found.page} }" else "invoke-static { v${found.controller}, v${found.page} }"
    found.select.addInstructions(found.at, "$hook, $NOTICE_PAGE")

    val stubs = mutableClassDefBy(CHAT_REELS_NOTICE).methods
    fun bridge(name: String, returns: String) = stubs.singleOrNull {
        it.name == name && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") && it.returnType == returns &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("the extension has no public static $name bridge for messages after a reel")
    val fields = collector.fields
    val controller = replies.hint.definingClass
    bridge("timeOf", "Ljava/lang/String;").addInstructions(
        0,
        """
            check-cast p0, ${fields.owner}
            iget-object p0, p0, ${fields.time.definingClass}->${fields.time.name}:${fields.time.type}
            return-object p0
        """,
    )
    bridge("isMessage", "Z").addInstructions(
        0,
        """
            instance-of p0, p0, ${fields.owner}
            return p0
        """,
    )
    bridge("sourceOf", "Ljava/lang/Object;").addInstructions(
        0,
        """
            check-cast p0, $controller
            iget-object p0, p0, ${replies.source}
            return-object p0
        """,
    )
    bridge("adapterOf", "Ljava/lang/Object;").addInstructions(
        0,
        """
            check-cast p0, $controller
            ${replies.adapter.joinToString("\n") { "iget-object p0, p0, $it" }}
            return-object p0
        """,
    )
}

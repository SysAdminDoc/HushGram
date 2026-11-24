/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.live.findReelsItemInserts
import app.morphe.patches.instagram.reels.partnership.findReelsPage
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.RegisterLiveness
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Clean up Reels"

/**
 * Takes fourteen things off the Reels viewer, each behind its own switch: the Follow button beside a
 * reel's author, the pills that prompt you to make something or promote something, and friends'
 * activity with the comment preview, and the Add comment bar under a reel opened from a profile's
 * reposts, and the same bar under every reel, the Reels tab's included, the reply bar under a reel
 * opened from a chat, the Like, Comment and Save buttons in a reel's button column (each with its
 * count, #668), the big pause icon and sound button of a paused reel (#686), the Reels tab's top bar, the
 * Create (+) and Your algorithm buttons in the top bar of reels, and the Friends tab beside Reels.
 * Each number in the button column also has a switch of its own that leaves the button, the repost
 * and share counts included (#719).
 * The patch is in the default selection and every switch starts off. Friends' activity covers the
 * floating bubbles and the Liked by or Followed by line with its faces. See ReelParts.kt for each
 * part.
 *
 * The Reels viewer's own top bar and its two buttons change no Instagram code: the extension's ReelsTopBar, started by
 * the application hook the settings patch writes once this patch switches its status method on,
 * finds the bar by the class and id names Instagram keeps and hides it at runtime when its viewer
 * is the Reels tab's. It finds the Create button by the kind Instagram tags it with and Your
 * algorithm by its class, in the bar of any reel viewer, and sets just that button gone.
 *
 * A reel opened from Saved, a profile or a post gets the screen's own top bar instead, so the one
 * place Instagram adds Create to that bar asks ReelsTopBar first, with the viewer's source, and
 * leaves the button out when it's hidden (#620). See CameraButton.kt.
 *
 * The Friends tab has no name, id or tag to find it by, so the one place Instagram builds it, where
 * the tab gets its facepile icon, hands the tab's view to ReelsTopBar, which hides it the same way.
 * See FriendsTab.kt. Holding that view lists friends to pick, and each Reels page goes through a
 * filter that keeps the Friends tab's reels with those friends on them (#503). See FriendLanePicks.kt.
 *
 * A twelfth switch, Keep clear mode on, has Instagram's own clear mode carry from the reel you
 * turned it on for to the next reels, and keeps the seek bar of a long reel off while it's in clear
 * mode (#277). See ClearMode.kt. Its places are found and checked before any part is hidden, so a
 * build that lacks one changes nothing.
 *
 * Pinch for clear mode answers the server flag behind Instagram's own pinch for clear mode, and
 * hands that pinch's write of the reel's state, which shows nothing, to the handler Keep clear mode
 * on found, so two fingers on a reel turn clear mode on and off. Hide the exit button in clear mode
 * draws the round button in its corner as nothing that takes no tap (#744). See PinchClearMode.kt.
 * The reel's touch also hands a second finger to the extension, which keeps the list out of the
 * gesture so fingers spreading apart reach the pinch too. See PinchTouch.kt. A
 * build where either doesn't fit, or where Keep clear mode on doesn't, gets the patch without it,
 * with a warning.
 *
 * Every part is required: a build where one can't be found once, in the shape its kind has, stops
 * the patch with what's wrong, rather than shipping a switch that quietly does nothing.
 */
@Suppress("unused")
val cleanUpReelsPatch = bytecodePatch(
    name = "Clean up Reels",
    description = "Hides extra buttons and prompts on reels, such as Follow, Edits, templates, Meta AI, Ray-Ban " +
        "Meta glasses, friends' activity, the Add a comment bar, the reply bar on reels from chats, the Like, Comment and Save buttons, the " +
        "count under any of the buttons beside a reel, the pause icon on a paused reel, the Create and Your algorithm buttons, the Friends tab and the Reels tab's top bar. Each one has its own switch, and Burn-in guard can dim a reel's buttons, and a story's, while you aren't " +
        "touching the screen. Keep clear mode on carries Instagram's clear mode to the next reels, and can start every reel in it. " +
        "Pinch for clear mode turns clear mode on and off with two fingers, and can hide the button that leaves it. " +
        "Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("reelDeclutter")
        val clearMode = findClearModeWhereItFits()
        val clearModeStubs = clearModeStubs()
        val pinchClearMode = findPinchClearModeWhereItFits(clearMode)
        val pinchTouch = if (pinchClearMode != null) findPinchTouch() else null
        val friendsTab = findFriendsTab()
        val friendsRow = findFriendsRow()
        val friendPicks = findFriendPicks()
        val reelsPage = findReelsPage(PATCH)
        val reelsInserts = findReelsItemInserts(PATCH, FRIEND_LANE_PICKS)
        val friendPages = findFriendLanePagesOrWarn(reelsInserts)
        val cameraButton = findCameraButton()
        hideReelParts()
        hideCameraButton(cameraButton)
        markFriendsTab(friendsTab)
        keepFriendsRow(friendsRow)
        pickFriends(reelsPage, friendPicks, reelsInserts, friendPages)
        if (clearMode != null) {
            applyClearMode(clearMode)
            clearModeStubs.fill(clearMode)
        }
        // After Keep clear mode on's hooks, which go into the exit button's render first.
        pinchClearMode?.let { pinchClearMode(it) }
        pinchTouch?.let { pinchTouch(it) }
        enableStatus("reelDeclutter")
    }
}

/**
 * Where each part is hidden, found and checked before anything changes: the method behind each of
 * [REEL_PARTS], [SAVE_PARTS], the clips UFI's builders in [ufiParts] and [PAUSE_PARTS], the reel
 * CTA's product button, the floating bubbles use case with its no-bubbles state [none], the read of
 * the social context line's type, the share count's add, and the two bars. [anchored] names the
 * markers whose part was found by its anchor, there being no method holding the marker.
 */
internal class ReelPartSites(
    val renders: Map<ReelPart, Method>,
    val saves: Map<ReelPart, Method>,
    val ufiParts: Map<ReelPart, Method>,
    val pauses: Map<ReelPart, Method>,
    val productCta: Method,
    val bubbles: Method,
    val none: String,
    val line: SocialContextRead,
    val shareCount: ShareCountAdd,
    val bar: CommentBar,
    val replyBar: DirectReplyBar,
    val anchored: Set<String>,
)

/**
 * Finds the method behind each part, by its marker or, on a build that dropped the marker, by the
 * anchor [ReelPartFinder] keeps for it, and checks each has the shape its kind has.
 */
internal fun BytecodePatchContext.findReelParts(): ReelPartSites {
    val finder = ReelPartFinder(this)
    fun holding(marker: String): Method = finder.holding(marker)

    val methods = REEL_PARTS.associateWith { holding(it.marker) }
    val saves = SAVE_PARTS.associateWith { holding(it.marker) }
    val productCta = holding(PRODUCT_CTA.marker)
    val bubbles = holding(FLOATING_BUBBLES)
    val socialContext = holding(SOCIAL_CONTEXT_CHECK)
    methods.forEach { (part, method) -> requireShape(part, method) }
    saves.forEach { (part, method) -> requireSaveShape(part, method) }
    requireProductCtaShape(productCta)
    // The Like and Comment buttons and their counts, built in the clips UFI that holds the Like builders (#668).
    val likes = LIKE_PARTS.associateWith { holding(it.marker) }
    val ufi = likes.getValue(LIKE_PARTS[0]).definingClass
    likes.forEach { (part, method) -> requireUfiBuilder(part.marker, method, ufi) }
    val (commentButton, commentCount) = commentBuilders(classDefBy(ufi), holding(COMMENT_BUTTON_RENDER).definingClass)
    // The repost count and the share count, which have a switch of their own and no button's (#719).
    val repostCount = repostCountBuilder(classDefBy(ufi), saves.values)
    val shareCount = shareCountAdd(holding(SHARE_COUNT_MARKER), ufi, commentCount.returnType)
    val ufiParts = likes + mapOf(COMMENT_BUTTON to commentButton, COMMENT_COUNT to commentCount, REPOST_COUNT to repostCount)
    // The big pause icon and the sound button of a paused reel (#686).
    val pauses = PAUSE_PARTS.associateWith { holding(it.marker) }
    requirePauseBuilders(pauses)
    val everyMethod = (methods.values + saves.values + ufiParts.values + pauses.values + productCta + bubbles + socialContext)
        .map { "${it.definingClass}->${it.name}" }
    if (everyMethod.toSet().size != everyMethod.size) throw PatchException("$PATCH: two parts are held by one method: $everyMethod")
    // The share count's hook goes in at an index worked out before anything changes, so no guard may go first in its method.
    val shareCountRender = "${shareCount.render.definingClass}->${shareCount.render.name}"
    if (shareCountRender in everyMethod) throw PatchException("$PATCH: the share count is built in $shareCountRender, which another part changes")
    val renders = methods.filterKeys { !it.check }.values
    val shapes = renders.map { it.parameterTypes.single().toString() to it.returnType }.toSet()
    if (shapes.size != 1) throw PatchException("$PATCH: the renders don't share one shape: $shapes")
    val none = noBubbles(bubbles)
    val line = socialContextRead(socialContext)
    val bar = commentBar(holding(COMMENT_BAR_SHOW), holding(COMMENT_BAR_HIDE), holding(COMMENT_BAR_CREATED), holding(COMMENT_BAR_UNVANISH))
    val replyBar = directReplyBar(holding(DIRECT_REPLY_BAR_SHOW), holding(DIRECT_REPLY_BAR_HIDE), holding(DIRECT_REPLY_BAR_CREATED))
    // The reply bar's hooks go in further down their methods, at places worked out before anything
    // changes, so neither method may be one another part writes into.
    val written = (methods.values + saves.values + ufiParts.values + pauses.values + productCta + bubbles + socialContext + bar.show + bar.created + bar.unVanish.method + shareCount.render)
        .map { it.signature() }
    val replyBarMethods = listOf(replyBar.show, replyBar.created).map { it.signature() }
    if (replyBarMethods.toSet().size != replyBarMethods.size || replyBarMethods.any { it in written }) {
        throw PatchException("$PATCH: the direct reply bar's show or onViewCreated is a method another part changes: $replyBarMethods")
    }
    return ReelPartSites(methods, saves, ufiParts, pauses, productCta, bubbles, none, line, shareCount, bar, replyBar, finder.anchored.toSet())
}

/**
 * Asks each part's hook first thing in its method, the parts [findReelParts] found. A render returns
 * null when the hook says to hide, which Instagram's own renders answer whenever they have nothing
 * to draw. The check returns false. The floating bubbles and the social context line are then taken
 * out where Instagram decides on them, see [hideFloatingBubbles] and [hideFriendsSocialContext].
 */
internal fun BytecodePatchContext.hideReelParts(sites: ReelPartSites = findReelParts()) {
    (sites.renders + sites.saves + sites.ufiParts + sites.pauses + (PRODUCT_CTA to sites.productCta)).forEach { (part, found) ->
        val method = mutable(found)
        method.requireLocals(PATCH, 1)
        val answer = if (part.check) "return v0" else "return-object v0"
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, ${part.hook}
                move-result v0
                if-eqz v0, :draw
                const/4 v0, 0x0
                $answer
            """,
            ExternalLabel("draw", method.getInstruction(0)),
        )
    }
    hideShareCount(sites.shareCount)
    hideFloatingBubbles(sites.bubbles, sites.none)
    hideFriendsSocialContext(sites.line)
    hideCommentBar(sites.bar)
    hideDirectReplyBar(sites.replyBar)
}

private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * The comment bar's controller: [show], its showCommentBar, [hide], its hideCommentBar, and
 * [created], its onViewCreated, which keeps the bar it inflates at instruction [stored] - 1. The
 * viewer's source is in [source], and [free] is a local nothing reads at [stored]. [unVanish] is
 * where Instagram puts the bar back without asking the show. The hide reads the bar from [bar] and
 * ends at each instruction of [ends], each with two locals nothing reads there.
 */
internal class CommentBar(
    val show: Method,
    val hide: Method,
    val created: Method,
    val stored: Int,
    val free: Int,
    val source: String,
    val unVanish: UnVanish,
    val bar: String,
    val ends: List<Pair<Int, List<Int>>>,
)

/**
 * The comment bar's unVanish, [method]: the hook goes in at instruction [at], right after the
 * marker's trace call, reads the source off the controller in v[controller], borrows v[free], and
 * jumps to the call of Instagram's own hide at instruction [hidden] when it says to hide.
 */
internal class UnVanish(val method: Method, val at: Int, val controller: Int, val hidden: Int, val free: Int)

/**
 * Checks the comment bar's controller before anything changes. Its show and hide are instance
 * methods of one class taking nothing; the hide reads the bar from one View field, which its
 * onViewCreated, in the same class, stores once on `this`; and the class holds the viewer's source
 * in one field. A hook put right after that store has to find `this` intact and a local free, and
 * nothing may jump there past it.
 */
private fun BytecodePatchContext.commentBar(show: Method, hide: Method, created: Method, unVanish: Method): CommentBar {
    val type = show.definingClass
    val what = "$PATCH: the comment bar's controller $type"
    if (hide.definingClass != type || created.definingClass != type) {
        throw PatchException("$PATCH: the comment bar's show, hide and onViewCreated are in $type, ${hide.definingClass} and ${created.definingClass}, not one class")
    }
    for (method in listOf(show, hide)) {
        if (AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.isNotEmpty() || method.returnType != "V" ||
            method.implementation == null
        ) throw PatchException("$what: ${method.name} isn't an instance method taking nothing")
    }
    if (created.name != "onViewCreated" || AccessFlags.STATIC.isSet(created.accessFlags) || created.implementation == null) {
        throw PatchException("$what: ${created.name}, holding the $COMMENT_BAR_CREATED marker, isn't its onViewCreated")
    }
    val sources = classDefBy(type).fields.filter { it.type == CLIPS_VIEWER_SOURCE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val source = sources.singleOrNull() ?: throw PatchException("$what: expected one field holding the viewer's source, found ${sources.size}")
    if (unVanish.definingClass != type && !AccessFlags.PUBLIC.isSet(source.accessFlags)) {
        throw PatchException("$what: its unVanish, in ${unVanish.definingClass}, can't read the viewer's source, which isn't public")
    }
    val bars = hide.implementation!!.instructions.filter { it.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
        .filter { it.definingClass == type && it.type == VIEW }.map { "${it.definingClass}->${it.name}:${it.type}" }.distinct()
    val bar = bars.singleOrNull() ?: throw PatchException("$what: expected its hide to read one View field, found ${bars.size}")
    val code = created.implementation!!.instructions.toList()
    val stores = code.indices.filter { at ->
        code[at].opcode == Opcode.IPUT_OBJECT &&
            ((code[at] as ReferenceInstruction).reference as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" } == bar
    }
    val store = stores.singleOrNull() ?: throw PatchException("$what: expected onViewCreated to store the bar once, found ${stores.size}")
    if ((code[store] as TwoRegisterInstruction).registerB != created.localRegisterCount()) {
        throw PatchException("$what: onViewCreated stores the bar on something other than the controller")
    }
    val after = store + 1
    if (after >= code.size || after in created.jumpTargets()) {
        throw PatchException("$what: onViewCreated jumps to just after it stores the bar, so the hook would be skipped")
    }
    created.requireThisIntact(what, listOf(after))
    val free = created.freeLocalsAt(what, after, 1).single()
    val hidden = hide.implementation!!.instructions.toList()
    val ends = hidden.indices.filter { hidden[it].opcode == Opcode.RETURN_VOID }.map { it to hide.freeLocalsAt(what, it, 2) }
    if (ends.isEmpty()) throw PatchException("$what: its hide never returns")
    if (hide.localRegisterCount() > 15) throw PatchException("$what: its hide keeps this in v${hide.localRegisterCount()}, past what the hook can read")
    hide.requireThisIntact(what, ends.map { it.first })
    return CommentBar(
        show, hide, created, after, free, "${source.definingClass}->${source.name}:${source.type}", unVanished(unVanish, hide), bar, ends,
    )
}

/**
 * Checks the comment bar's unVanish before anything changes. It loads its marker once and hands
 * it straight to a trace call, and calls the controller's [hide] once, on its hidden branch. The
 * hook goes in right after the trace, where the method's own next instruction reads a field of the
 * controller off the register the hide is later called on, so the controller is known to be there.
 * Nothing may jump past the hook, and it needs a local that neither its own code nor the hide reads.
 */
private fun unVanished(method: Method, hide: Method): UnVanish {
    val type = hide.definingClass
    val what = "$PATCH: the comment bar's unVanish ${method.definingClass}->${method.name}"
    val code = method.implementation?.instructions?.toList() ?: throw PatchException("$what has no body")
    val loads = code.indices.filter { at ->
        (code[at].opcode == Opcode.CONST_STRING || code[at].opcode == Opcode.CONST_STRING_JUMBO) &&
            ((code[at] as ReferenceInstruction).reference as StringReference).string
                .let { PURGE_MARKER.find(it)?.groupValues?.get(1) } == COMMENT_BAR_UNVANISH
    }
    val loaded = loads.singleOrNull() ?: throw PatchException("$what: expected it to load its marker once, found ${loads.size}")
    val trace = code.getOrNull(loaded + 1) as? FiveRegisterInstruction
    if (trace == null || trace.opcode != Opcode.INVOKE_STATIC || trace.registerCount != 1 ||
        trace.registerC != (code[loaded] as OneRegisterInstruction).registerA
    ) throw PatchException("$what doesn't hand its marker to a trace call right after loading it")
    val at = loaded + 2
    val hides = code.indices.filter { index ->
        code[index].opcode == Opcode.INVOKE_VIRTUAL && ((code[index] as ReferenceInstruction).reference as MethodReference).let {
            it.definingClass == type && it.name == hide.name && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
    }
    val hidden = hides.singleOrNull() ?: throw PatchException("$what: expected it to call the hide once, found ${hides.size}")
    val controller = (code[hidden] as FiveRegisterInstruction).registerC
    val next = code.getOrNull(at)
    val field = (next as? ReferenceInstruction)?.reference as? FieldReference
    if (next == null || next.opcode !in INSTANCE_READS || field?.definingClass != type ||
        (next as TwoRegisterInstruction).registerB != controller
    ) throw PatchException("$what doesn't read the controller in v$controller right after its trace, so the hook can't tell it's there")
    if (at in method.jumpTargets()) throw PatchException("$what jumps to just after its trace, so the hook would be skipped")
    // The hook's jump reaches the hide with each register as it is at the hook. What the hide's
    // path reads before writing has to hold the same there as on Instagram's own way to the hide,
    // so nothing on a path from the hook to the hide may write it. An instruction is on one when the
    // hook reaches it before the hide and it reaches the hide: the shown branch's writes don't count.
    val flow = ControlFlow.of(method)
    val predecessors = List(code.size) { mutableListOf<Int>() }
    for (index in code.indices) (flow.normal[index] + flow.exceptional[index]).forEach { predecessors[it] += index }
    val fromHook = reachable(at) { index -> if (index == hidden) emptyList() else flow.normal[index] + flow.exceptional[index] }
    val between = (fromHook intersect reachable(hidden) { predecessors[it] }) - hidden
    val rewritten = RegisterLiveness.of(method).liveInto(hidden).filter { register -> between.any { code[it].writes(register) } }
    if (rewritten.isNotEmpty()) {
        throw PatchException(
            "$what writes ${rewritten.sorted().joinToString { "v$it" }} between its trace and the hide, and the hide's path reads it, " +
                "so the hook can't jump to the hide",
        )
    }
    val free = method.freeLocalsAt(what, at, 1, listOf(hidden)).single()
    return UnVanish(method, at, controller, hidden, free)
}

/** Every instruction index reached from [start] by following [next], [start] included. */
private fun reachable(start: Int, next: (Int) -> Collection<Int>): Set<Int> {
    val seen = mutableSetOf<Int>()
    val pending = ArrayDeque(listOf(start))
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        if (seen.add(index)) pending += next(index)
    }
    return seen
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

private val INSTANCE_READS = setOf(
    Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN, Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT,
)

/**
 * Has the comment bar hidden, with Instagram's own hide, when the hook says so for the viewer's
 * source: first thing whenever the controller would show it, right after onViewCreated keeps the
 * bar it inflated, which is drawn until the controller first decides, and in unVanish, which would
 * otherwise put the bar back after a vanish, by taking its own hidden branch. Where Instagram's hide
 * returns, the bar and the source go to a second hook, which can leave the bar's room empty instead
 * of gone so the reel doesn't drop down into it (#523).
 */
private fun BytecodePatchContext.hideCommentBar(bar: CommentBar) {
    val hide = "${bar.hide.definingClass}->${bar.hide.name}()V"
    val hidden = mutable(bar.hide)
    bar.ends.sortedByDescending { it.first }.forEach { (at, free) ->
        val (view, source) = free
        hidden.addInstructionsAtControlFlowLabel(
            at,
            """
                iget-object v$view, p0, ${bar.bar}
                iget-object v$source, p0, ${bar.source}
                invoke-static { v$view, v$source }, $KEEP_COMMENT_BAR_SPACE
            """,
        )
    }
    val show = mutable(bar.show)
    show.requireLocals(PATCH, 1)
    show.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, ${bar.source}
            invoke-static { v0 }, $HIDE_COMMENT_BAR
            move-result v0
            if-eqz v0, :show
            invoke-virtual { p0 }, $hide
            return-void
        """,
        ExternalLabel("show", show.getInstruction(0)),
    )
    val created = mutable(bar.created)
    created.addInstructionsWithLabels(
        bar.stored,
        """
            iget-object v${bar.free}, p0, ${bar.source}
            invoke-static { v${bar.free} }, $HIDE_COMMENT_BAR
            move-result v${bar.free}
            if-eqz v${bar.free}, :keep
            invoke-virtual { p0 }, $hide
        """,
        ExternalLabel("keep", created.getInstruction(bar.stored)),
    )
    val back = bar.unVanish
    val unVanish = mutable(back.method)
    unVanish.addInstructionsWithLabels(
        back.at,
        """
            iget-object v${back.free}, v${back.controller}, ${bar.source}
            invoke-static { v${back.free} }, $HIDE_COMMENT_BAR
            move-result v${back.free}
            if-nez v${back.free}, :hide
        """,
        ExternalLabel("hide", unVanish.getInstruction(back.hidden)),
    )
}

/**
 * The reply bar's controller under a reel opened from a chat: [show], its showReplyBar, and
 * [created], its onViewCreated, each read the controller's hidden flag once, at [shown] and [made].
 * The viewer's source is in [source].
 */
internal class DirectReplyBar(val show: Method, val shown: FlagRead, val created: Method, val made: FlagRead, val source: String)

/**
 * A read of the reply bar's hidden flag: instruction [at], into v[flag], off the controller in
 * v[controller]. When the read puts the flag over the controller, as 456's show shared with the
 * Spotlight reply bar does over the controller it cast, [kept] is that register, and the controller
 * is first copied into v[controller], a local nothing reads there.
 */
internal class FlagRead(val at: Int, val flag: Int, val controller: Int, val kept: Int? = null)

/**
 * Checks the reply bar's controller before anything changes. Its onViewCreated names its class,
 * which holds the viewer's source in one field, an enum naming [CHAT_SOURCE]. Its show and hide
 * take nothing and sit in the controller or, merged with the Spotlight reply bar's, in a class it
 * extends. The show reads one boolean field of the controller, once: the flag Instagram sets to
 * keep the bar hidden, which the show turns into visible or gone. The onViewCreated reads the same
 * flag once and, when it's set, calls the hide on the controller it read it off, which is what
 * makes it the hidden flag. A hook right after either read only adds to the flag. It reads the
 * source off the register the flag came off, which the read itself proves is the controller, so
 * the two registers have to differ, and nothing may jump to just past the read.
 */
private fun BytecodePatchContext.directReplyBar(show: Method, hide: Method, created: Method): DirectReplyBar {
    val type = created.definingClass
    val what = "$PATCH: the direct reply bar's controller $type"
    if (created.name != "onViewCreated" || AccessFlags.STATIC.isSet(created.accessFlags) || created.implementation == null) {
        throw PatchException("$what: ${created.name}, holding the $DIRECT_REPLY_BAR_CREATED marker, isn't its onViewCreated")
    }
    val controller = classDefBy(type)
    val extended = generateSequence(controller.superclass) { classDefByOrNull(it)?.superclass }.toSet()
    for (method in listOf(show, hide)) {
        if (AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.isNotEmpty() || method.returnType != "V" ||
            method.implementation == null
        ) throw PatchException("$what: ${method.definingClass}->${method.name} isn't an instance method taking nothing")
        if (method.definingClass != type && method.definingClass !in extended) {
            throw PatchException("$what: ${method.definingClass}->${method.name} is in neither the controller nor a class it extends")
        }
    }
    val sources = controller.fields.filter { it.type == CLIPS_VIEWER_SOURCE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val source = sources.singleOrNull() ?: throw PatchException("$what: expected one field holding the viewer's source, found ${sources.size}")
    if (show.definingClass != type && !AccessFlags.PUBLIC.isSet(source.accessFlags)) {
        throw PatchException("$what: its show, in ${show.definingClass}, can't read the viewer's source, which isn't public")
    }
    val named = classDefByOrNull(CLIPS_VIEWER_SOURCE)?.methods?.filter { it.name == "<clinit>" }.orEmpty().flatMap { initializer ->
        initializer.implementation?.instructions?.toList().orEmpty().mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
    }
    if (CHAT_SOURCE !in named) throw PatchException("$what: the viewer's source doesn't name $CHAT_SOURCE, which tells a reel from a chat")
    fun Method.flagReads(): List<Pair<Int, String>> = implementation!!.instructions.withIndex().mapNotNull { (at, instruction) ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        if (instruction.opcode == Opcode.IGET_BOOLEAN && field != null && field.definingClass == type) at to field.name else null
    }
    val shown = show.flagReads()
    val (shownAt, flag) = shown.singleOrNull()
        ?: throw PatchException("$what: expected its show to read one flag of the controller once, found ${shown.map { it.second }}")
    val made = created.flagReads().filter { it.second == flag }
    val madeAt = made.singleOrNull()?.first
        ?: throw PatchException("$what: expected onViewCreated to read the hidden flag $flag once, found ${made.size} read(s)")
    val code = created.implementation!!.instructions.toList()
    val read = code[madeAt] as TwoRegisterInstruction
    val check = code.getOrNull(madeAt + 1)
    val call = code.getOrNull(madeAt + 2)
    val called = (call as? ReferenceInstruction)?.reference as? MethodReference
    // The call may name the hide on a class the controller extends (on 451's 385713073 onViewCreated
    // calls the abstract LX/0Fk2;->A0r, which LX/0P82, the controller, overrides with the hide): on the
    // controller it reads the flag off, it has to reach the hide all the same.
    val hides = check != null && check.opcode == Opcode.IF_EQZ && (check as OneRegisterInstruction).registerA == read.registerA &&
        call != null && call.opcode == Opcode.INVOKE_VIRTUAL && called != null && called.name == hide.name &&
        called.parameterTypes.isEmpty() && called.returnType == "V" &&
        (called.definingClass == type || called.definingClass in extended) && resolvesTo(type, called, hide) &&
        (call as FiveRegisterInstruction).let { it.registerCount == 1 && it.registerC == read.registerB }
    if (!hides) {
        throw PatchException("$what: onViewCreated doesn't call the hide on the controller right after finding the hidden flag $flag set")
    }
    return DirectReplyBar(
        show, flagRead(show, shownAt, "$what: its show"),
        created, flagRead(created, madeAt, "$what: onViewCreated"),
        "${source.definingClass}->${source.name}:${source.type}",
    )
}

/**
 * Whether a virtual call of [called] on an object of [type] runs [target]: the first class from
 * [type] up through its superclasses declaring a method of that name and shape with a body is
 * [target]'s.
 */
private fun BytecodePatchContext.resolvesTo(type: String, called: MethodReference, target: Method): Boolean {
    val shape = called.parameterTypes.map(Any::toString)
    val declaring = generateSequence(classDefByOrNull(type)) { it.superclass?.let(::classDefByOrNull) }.firstOrNull { owner ->
        owner.methods.any {
            it.name == called.name && it.returnType == called.returnType && it.parameterTypes.map(Any::toString) == shape &&
                it.implementation != null && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
    }
    return declaring?.type == target.definingClass && target.name == called.name
}

/** The registers of the hidden flag's read at instruction [at] of [method], checked for a hook right after it. */
private fun flagRead(method: Method, at: Int, what: String): FlagRead {
    val code = method.implementation!!.instructions.toList()
    val read = code[at] as TwoRegisterInstruction
    if (at + 1 >= code.size || at + 1 in method.jumpTargets()) {
        throw PatchException("$what jumps to just after it reads the hidden flag, so the hook would be skipped")
    }
    if (read.registerA != read.registerB) return FlagRead(at, read.registerA, read.registerB)
    if (at in method.jumpTargets()) {
        throw PatchException("$what reads the hidden flag over the controller where something jumps to, so the controller can't be kept")
    }
    val kept = method.freeLocalsAt("$what, keeping the controller past its read of the hidden flag", at, 1).single()
    return FlagRead(at, read.registerA, kept, read.registerB)
}

/**
 * Keeps the reply bar under a reel from a chat hidden when the hook says so for the viewer's
 * source, by adding to Instagram's own hidden flag right where the show and onViewCreated read it:
 * a flag already set stays set, and a clear one becomes the hook's answer. The show then sets the
 * bar gone through its own visibility call, and onViewCreated takes its own branch to Instagram's
 * hide. The flag's field is never written, so a no from the hook is Instagram's own behavior.
 */
private fun BytecodePatchContext.hideDirectReplyBar(bar: DirectReplyBar) {
    for ((found, read) in listOf(bar.show to bar.shown, bar.created to bar.made)) {
        val method = mutable(found)
        val after = read.at + 1
        method.addInstructionsWithLabels(
            after,
            """
                if-nez v${read.flag}, :hidden
                iget-object v${read.flag}, v${read.controller}, ${bar.source}
                invoke-static { v${read.flag} }, $HIDE_DIRECT_REPLY_BAR
                move-result v${read.flag}
            """,
            ExternalLabel("hidden", method.getInstruction(after)),
        )
        read.kept?.let { method.addInstructions(read.at, "move-object/from16 v${read.controller}, v$it") }
    }
}

private const val VIEW = "Landroid/view/View;"

private fun BytecodePatchContext.mutable(found: Method) = mutableClassDefBy(found.definingClass).methods.single {
    it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString) &&
        it.returnType == found.returnType
}

/**
 * The state the floating bubbles use case answers when there are no bubbles: the one field it reads
 * and answers straight away, a single instance of its own class.
 */
private fun noBubbles(method: Method): String {
    val nones = noBubbleStates(method)
    return nones.singleOrNull() ?: throw PatchException(
        "$PATCH: expected ${method.definingClass}->${method.name}, holding the $FLOATING_BUBBLES marker, to answer " +
            "one state of its own as it is, found ${nones.ifEmpty { listOf("none") }.joinToString()}",
    )
}

/** Has the floating bubbles use case answer its no-bubbles state when friends' activity is hidden. */
private fun BytecodePatchContext.hideFloatingBubbles(found: Method, none: String) {
    val method = mutable(found)
    method.requireLocals(PATCH, 1)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_SOCIAL_FOOTER
            move-result v0
            if-eqz v0, :build
            sget-object v0, $none
            return-object v0
        """,
        ExternalLabel("build", method.getInstruction(0)),
    )
}

/**
 * Where [method], the one deciding on the social context line, reads the line's type: the hook goes
 * in at instruction [at], right after the read, with the type in v[type] and v[free] a local nothing
 * reads there.
 */
internal class SocialContextRead(val method: Method, val at: Int, val type: Int, val free: Int)

/**
 * Finds where the method deciding on the social context line reads the line's type, before
 * anything changes. The method answers a boolean, whether to show the line. It makes the line with
 * a call and reads its type straight off the answer: a field holding an enum naming
 * [SOCIAL_CONTEXT_TYPES], whose ordinal it asks later. That read has to be there once, and nothing
 * may jump to just past it.
 */
private fun BytecodePatchContext.socialContextRead(method: Method): SocialContextRead {
    val what = "$PATCH: ${method.definingClass}->${method.name}, holding the $SOCIAL_CONTEXT_CHECK marker,"
    if (method.implementation == null || method.returnType != "Z") throw PatchException("$what is not a check answering a boolean")
    val code = method.implementation!!.instructions.toList()
    val reads = code.indices.filter { at ->
        val read = code[at]
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference
        val made = code.getOrNull(at - 1)
        if (read.opcode != Opcode.IGET_OBJECT || field == null || made?.opcode != Opcode.MOVE_RESULT_OBJECT) return@filter false
        val registers = read as TwoRegisterInstruction
        (made as OneRegisterInstruction).registerA == registers.registerB &&
            (at + 1 until code.size).any { code[it].asksOrdinalOf(registers.registerA) } && namesSocialContextTypes(field.type)
    }
    val read = reads.singleOrNull() ?: throw PatchException(
        "$what: expected it to read the type of the line it makes, an enum naming $SOCIAL_CONTEXT_TYPES, once, found ${reads.size}",
    )
    val at = read + 1
    if (at >= code.size || at in method.jumpTargets()) {
        throw PatchException("$what jumps to just after it reads the line's type, so the hook would be skipped")
    }
    val type = (code[read] as TwoRegisterInstruction).registerA
    val free = method.freeLocalsAt(what, at, 1, except = listOf(type)).single()
    return SocialContextRead(method, at, type, free)
}

private fun Instruction.asksOrdinalOf(register: Int): Boolean {
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return opcode == Opcode.INVOKE_VIRTUAL && call.definingClass == "Ljava/lang/Enum;" && call.name == "ordinal" &&
        (this as FiveRegisterInstruction).registerC == register
}

private fun BytecodePatchContext.namesSocialContextTypes(type: String): Boolean {
    val named = classDefByOrNull(type)?.methods?.filter { it.name == "<clinit>" }.orEmpty().flatMap { initializer ->
        initializer.implementation?.instructions?.toList().orEmpty().mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
    }.toSet()
    return SOCIAL_CONTEXT_TYPES.all { it in named }
}

/**
 * Has the method deciding on the social context line answer no, don't show it, for a line about
 * friends' activity, right after it reads the line's type. The type comes off the line the method
 * has just read it from, so the hook adds no failure of its own.
 */
private fun BytecodePatchContext.hideFriendsSocialContext(line: SocialContextRead) {
    val method = mutable(line.method)
    method.addInstructionsWithLabels(
        line.at,
        """
            invoke-static/range { v${line.type} .. v${line.type} }, $HIDE_SOCIAL_CONTEXT
            move-result v${line.free}
            if-eqz v${line.free}, :check
            const/4 v${line.free}, 0x0
            return v${line.free}
        """,
        ExternalLabel("check", method.getInstruction(line.at)),
    )
}

/**
 * The Save button's and the save count's builders are private instance methods of the clips UFI
 * taking the component scope and two longs, and answering the component or nothing.
 */
private fun requireSaveShape(part: ReelPart, method: Method) {
    val types = method.parameterTypes.map { it.toString() }
    val problem = when {
        method.implementation == null -> "has no body"
        AccessFlags.STATIC.isSet(method.accessFlags) || types.size != 3 || types[1] != "J" || types[2] != "J" ||
            !method.returnType.startsWith("L") -> "is not a builder taking a scope and two longs and answering a component"
        else -> null
    } ?: return
    throw PatchException("$PATCH: ${method.definingClass}->${method.name}, holding the ${part.marker} marker, $problem")
}

/**
 * A builder of the reel's button column is a private instance method of the clips UFI [ufi] with a
 * body, answering the component or nothing.
 */
private fun requireUfiBuilder(what: String, method: Method, ufi: String) {
    val problem = when {
        method.definingClass != ufi -> "is not in the clips UFI $ufi"
        method.implementation == null -> "has no body"
        AccessFlags.STATIC.isSet(method.accessFlags) || !method.returnType.startsWith("L") ->
            "is not an instance builder answering a component"
        else -> null
    } ?: return
    throw PatchException("$PATCH: ${method.definingClass}->${method.name}, building the $what part, $problem")
}

/**
 * The pause icon's and the sound button's builders (#686) are instance methods of one component with a
 * body, both taking the component scope first and answering the component or nothing.
 */
private fun requirePauseBuilders(builders: Map<ReelPart, Method>) {
    val owners = builders.values.map { it.definingClass }.toSet()
    val scopes = builders.values.map { it.parameterTypes.firstOrNull()?.toString() }.toSet()
    builders.forEach { (part, method) ->
        val problem = when {
            owners.size != 1 -> "is not in the same component as the other paused reel builder: $owners"
            method.implementation == null -> "has no body"
            AccessFlags.STATIC.isSet(method.accessFlags) || !method.returnType.startsWith("L") ->
                "is not an instance builder answering a component"
            scopes.size != 1 || scopes.single() == null -> "doesn't take the same component scope first as the other: $scopes"
            else -> null
        } ?: return@forEach
        throw PatchException("$PATCH: ${method.definingClass}->${method.name}, holding the ${part.marker} marker, $problem")
    }
}

/**
 * The Comment button's builder in the clips UFI [ufi], the one method answering the button's
 * [component], and the comment count's, the one other method of the UFI taking the same parameters
 * and loading [UFI_COUNT] (#668).
 */
private fun commentBuilders(ufi: ClassDef, component: String): Pair<Method, Method> {
    val buttons = ufi.methods.filter { it.returnType == component }
    val button = buttons.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method of ${ufi.type} answering the Comment button $component, found ${buttons.map { it.name }}",
    )
    requireUfiBuilder(COMMENT_BUTTON_RENDER, button, ufi.type)
    val parameters = button.parameterTypes.map { it.toString() }
    val counts = ufi.methods.filter { method ->
        method.name != button.name && method.parameterTypes.map { it.toString() } == parameters &&
            method.implementation?.instructions?.any { it.loadsUfiCount() } == true
    }
    val count = counts.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method of ${ufi.type} taking ${parameters.joinToString("")} and loading \"$UFI_COUNT\", " +
            "found ${counts.map { it.name }}",
    )
    requireUfiBuilder("comment count", count, ufi.type)
    return button to count
}

/**
 * The repost count's builder in the clips UFI [ufi] (#719): the one method holding no marker, other
 * than the Save button's and the save count's builders ([saves]), that takes the Save button's
 * builder's parameters and loads [UFI_COUNT].
 */
private fun repostCountBuilder(ufi: ClassDef, saves: Collection<Method>): Method {
    val parameters = saves.first().parameterTypes.map { it.toString() }
    val saveBuilders = saves.map { it.signature() }.toSet()
    val counts = ufi.methods.filter { method ->
        method.markers().isEmpty() && method.signature() !in saveBuilders && method.parameterTypes.map { it.toString() } == parameters &&
            method.implementation?.instructions?.any { it.loadsUfiCount() } == true
    }
    val count = counts.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one unmarked method of ${ufi.type} taking ${parameters.joinToString("")} and loading \"$UFI_COUNT\" " +
            "for the repost count, found ${counts.map { it.name }}",
    )
    requireUfiBuilder("repost count", count, ufi.type)
    return count
}

/**
 * Where the clips UFI's render [render] adds the share count (#719): instruction [at] hands the
 * column v[count], which [SHARE_COUNT] answers in its place cast back to [type], the add's parameter.
 */
internal class ShareCountAdd(val render: Method, val at: Int, val count: Int, val type: String)

/**
 * Finds the share count's add in the clips UFI's render [render] before anything changes. After the
 * [SHARE_COUNT_MARKER] marker comes the first call of one of the UFI [ufi]'s own builders answering
 * its [text], that answer moved into a register, and right after it that register handed to a
 * one-argument add answering nothing. Every way to the add meets there, a count or none, so the hook
 * goes in at its label, and nothing may read the register after the add, its own handlers included.
 */
private fun shareCountAdd(render: Method, ufi: String, text: String): ShareCountAdd {
    val what = "$PATCH: the share count in ${render.definingClass}->${render.name}"
    if (render.definingClass != ufi || AccessFlags.STATIC.isSet(render.accessFlags)) {
        throw PatchException("$what is not built in an instance method of the clips UFI $ufi")
    }
    val code = render.implementation?.instructions?.toList().orEmpty()
    val marker = code.indexOfFirst { instruction ->
        (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
            PURGE_MARKER.find(((instruction as ReferenceInstruction).reference as StringReference).string)?.groupValues?.get(1) == SHARE_COUNT_MARKER
    }
    // On 456 the marker is gone, and the render loads the UFI's own id right before the share count.
    val start = if (marker >= 0) marker else code.indexOfFirst { it.loadsLiteral(UFI_ID) }
    if (start < 0) throw PatchException("$what: it loads neither the $SHARE_COUNT_MARKER marker nor the UFI's id")
    val build = (start + 1 until code.size).firstOrNull { index ->
        val call = code[index]
        (call.opcode == Opcode.INVOKE_DIRECT || call.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            ((call as ReferenceInstruction).reference as MethodReference).let { it.definingClass == ufi && it.returnType == text }
    } ?: throw PatchException("$what: no call of the UFI's own $text builder follows the $SHARE_COUNT_MARKER marker")
    val moved = code.getOrNull(build + 1)
    if (moved?.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("$what: the text builder's answer isn't kept in a register")
    val count = (moved as OneRegisterInstruction).registerA
    val at = build + 2
    val add = code.getOrNull(at)
    val reference = (add as? ReferenceInstruction)?.reference as? MethodReference
    val registers = when {
        add is FiveRegisterInstruction && add.registerCount == 2 -> listOf(add.registerC, add.registerD)
        add is RegisterRangeInstruction && add.registerCount == 2 -> listOf(add.startRegister, add.startRegister + 1)
        else -> emptyList()
    }
    if ((add?.opcode != Opcode.INVOKE_VIRTUAL && add?.opcode != Opcode.INVOKE_VIRTUAL_RANGE) || reference == null ||
        reference.parameterTypes.size != 1 || !reference.parameterTypes.single().toString().startsWith("L") ||
        reference.returnType != "V" || registers.getOrNull(1) != count
    ) throw PatchException("$what: the count isn't handed to a one-argument add right after it's built")
    val reads = render.readsAfter(at, count)
    if (reads.isNotEmpty()) throw PatchException("$what: v$count is read again after the add, at $reads")
    return ShareCountAdd(render, at, count, reference.parameterTypes.single().toString())
}

/**
 * Hands the share count to the extension right before its add, at the add's label, so the count
 * built there and every branch that jumps to the add with another count or none all ask (#719).
 */
private fun BytecodePatchContext.hideShareCount(add: ShareCountAdd) {
    val count = add.count
    mutable(add.render).addInstructionsAtControlFlowLabel(
        add.at,
        """
            invoke-static/range { v$count .. v$count }, $SHARE_COUNT
            move-result-object v$count
            check-cast v$count, ${add.type}
        """,
    )
}

private fun Instruction.loadsUfiCount() =(opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
    ((this as ReferenceInstruction).reference as StringReference).string == UFI_COUNT

/** The reel CTA's product button is built from the component scope and its label, and answers the component. */
private fun requireProductCtaShape(method: Method) {
    val types = method.parameterTypes.map { it.toString() }
    val problem = when {
        method.implementation == null -> "has no body"
        AccessFlags.STATIC.isSet(method.accessFlags) || types.size != 2 || types[1] != "Ljava/lang/String;" ||
            !method.returnType.startsWith("L") -> "is not a builder taking a scope and a label and answering a component"
        else -> null
    } ?: return
    throw PatchException("$PATCH: ${method.definingClass}->${method.name}, holding the ${PRODUCT_CTA.marker} marker, $problem")
}

/** A render takes one component scope and answers an object; the check is static and answers a boolean. */
private fun requireShape(part: ReelPart, method: Method) {
    val static = AccessFlags.STATIC.isSet(method.accessFlags)
    val problem = when {
        method.implementation == null -> "has no body"
        part.check && (!static || method.returnType != "Z") -> "is not a static check answering a boolean"
        !part.check && (static || method.parameterTypes.size != 1 || !method.returnType.startsWith("L")) ->
            "is not a render taking one scope and answering a component"
        else -> null
    } ?: return
    throw PatchException("$PATCH: ${method.definingClass}->${method.name}, holding the ${part.marker} marker, $problem")
}

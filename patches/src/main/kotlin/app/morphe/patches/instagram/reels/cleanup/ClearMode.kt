/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.BitSet

private const val KEEP = "Clean up Reels: Keep clear mode on"

internal const val CLEAR_MODE = "$EXTENSION_PACKAGE/reels/ReelClearMode;"
internal const val CLEAR_MODE_ENTERED = "$CLEAR_MODE->entered(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val CLEAR_MODE_LEFT = "$CLEAR_MODE->left(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val CLEAR_MODE_CURRENT = "$CLEAR_MODE->current(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val CLEAR_MODE_PAGED = "$CLEAR_MODE->paged()V"
internal const val CLEAR_MODE_BUILT = "$CLEAR_MODE->built(Ljava/lang/Object;)V"
internal const val CLEAR_MODE_SCRUBBER = "$CLEAR_MODE->scrubber(II)Z"
internal const val CLEAR_MODE_CHROME_ALPHA = "$CLEAR_MODE->chrome(FF)F"
internal const val CLEAR_MODE_SETTLED = "$CLEAR_MODE->settled(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val CLEAR_MODE_ENTER_STUB = "enter"
internal const val CLEAR_MODE_ID_STUB = "reelId"

/**
 * The markers, after Instagram's release prefix, of the methods Keep clear mode on changes.
 * Instagram 450 calls clear mode its fullscreen view. The action handler's two methods turn it on
 * and off, each through an action the Reels viewer hands it, and the viewer's turn-on action is the
 * one that reads the reel the pager is on. The exit button a reel in clear mode draws is a
 * component of its own, and the pager's listener reports each page it lands on. The seek bar row
 * under a reel draws Instagram's scrubber on long reels, clear mode or not (#277). Every reel page
 * writes the one alpha its buttons, author row and top bar share through its holder's setter,
 * which clear mode's fade animates and Instagram resets to shown when it binds a page (#233). The
 * pager's listener also handles each change of the pager's scroll state, and reads the pager on its
 * way to rest, which is where a swipe that snapped back shows (#233).
 */
internal const val CLEAR_MODE_ENABLE = "FullscreenViewActionHandler_handleFullscreenViewEnable"
internal const val CLEAR_MODE_DISMISS = "FullscreenViewActionHandler_handleFullscreenViewDismiss"
internal const val CLEAR_MODE_ENABLED = "ClipsViewerNavigatorProvider_handleFullscreenViewEnable"
internal const val CLEAR_MODE_BUTTON = "ClipsFullscreenViewComponent_render"
internal const val REEL_PAGE_SELECTED = "ClipsViewerViewPagerListener_onPageSelected"
internal const val CLEAR_MODE_SCRUBBER_ROW = "ClipsScrubberRowComponent_render"
internal const val CLEAR_MODE_CHROME = "LithoClipsItemDefinition_Holder_updateChromeAlpha"
internal const val REEL_SCROLL_STATE = "ClipsViewerViewPagerListener_onScrollStateChanged"

internal val CLEAR_MODE_MARKERS = listOf(
    CLEAR_MODE_ENABLE, CLEAR_MODE_DISMISS, CLEAR_MODE_ENABLED, CLEAR_MODE_BUTTON, REEL_PAGE_SELECTED, CLEAR_MODE_SCRUBBER_ROW,
    CLEAR_MODE_CHROME, REEL_SCROLL_STATE,
)

/** The reason Instagram turns clear mode off with when a reel leaves the screen, which its enum has to name. */
internal const val CLEAR_MODE_SWIPED_AWAY = "REELS_VIEWER_SWIPE_OR_EXIT"

/** What the pager's scroll state calls the pager at rest, which its enum has to name for the extension to tell. */
internal const val REEL_AT_REST = "IDLE"

/** The pager the scroll state change reads. */
internal const val VIEW_PAGER = "Landroidx/viewpager2/widget/ViewPager2;"

/** What the reel's state names its clear mode flag with in its toString, right before appending it. */
internal const val CLEAR_MODE_FLAG_NAME = ", isFullscreenViewActive="

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/**
 * Clear mode stays on from reel to reel. See the extension's ReelClearMode for the rule.
 *
 * The handler's turn-on and turn-off each tell the extension right after their action ran, with
 * what they were handed. A handler with no action set branches past its call, and the hooks go in
 * after the call, where that branch doesn't land, so they report only what happened. The viewer's
 * turn-on passes the reel it read off the pager through the extension before testing it, so a
 * turn-on the extension asked for can be held back by answering no reel, which Instagram's own test
 * takes as nothing to do. The pager's listener tells the extension first thing about each page, and
 * the exit button's render hands over its reel first thing. The seek bar row's render asks the
 * extension at its test of whether to draw the scrubber, with the answer and the reel state's clear
 * mode flag, and tests what comes back. The reel holder's chrome alpha setter passes the alpha it
 * was asked for through the extension, with the one it holds, right after reading that one, and
 * stores what comes back. The pager listener's scroll state change hands the extension the pager
 * and the state right after reading the pager. The extension's two stubs are filled with the
 * handler's turn-on and the reel's getId.
 *
 * Everything is found by Instagram's own markers and checked before anything changes, so a build
 * that differs stops the patch saying what it couldn't find, and nothing is half done.
 */
internal fun BytecodePatchContext.keepClearMode() {
    val found = findClearMode()
    val stubs = clearModeStubs()
    applyClearMode(found)
    stubs.fill(found)
}

/**
 * What [findClearMode] finds, or null with a warning when the build differs, so Clean up Reels goes
 * in without Keep clear mode on rather than not at all. 452.1's x86 build (385811007) inlines the
 * handler's turn-on into its three callers, which leaves no method for the enter stub to call.
 */
internal fun BytecodePatchContext.findClearModeWhereItFits(): ClearModeHooks? = try {
    findClearMode()
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. Clean up Reels goes in without Keep clear mode on.")
    null
}

/** Every place Keep clear mode on changes, by method and instruction index, and what the stubs reach. */
internal class ClearModeHooks(
    /** The handler's turn-on, and the index after its action's call. */
    val enable: Method,
    val entered: Int,
    /** The handler's turn-off, and the index after its action's call. */
    val dismiss: Method,
    val left: Int,
    /** The viewer's turn-on, the index of its test of the pager's reel, and the register the reel is in. */
    val enabled: Method,
    val current: Int,
    val reel: Int,
    /** The pager listener's page report. */
    val paged: Method,
    /** The exit button's render, and the field of its class holding the reel. */
    val button: Method,
    val buttonReel: String,
    /** The handler, the reason's enum and the reel, as types. */
    val handler: String,
    val reason: String,
    val item: String,
    /**
     * The seek bar row's render, the index of its test of whether to draw the scrubber, the register
     * tested, and the local the hook borrows to read the clear mode flag into.
     */
    val row: Method,
    val scrubber: Int,
    val shown: Int,
    val borrowed: Int,
    /** The row's field holding its state, that state's field holding the reel's, and the reel state's clear mode flag. */
    val rowState: String,
    val reelState: String,
    val flag: String,
    /** The reel holder's chrome alpha setter, the index after it read the alpha it holds, and the register that alpha is in. */
    val chrome: Method,
    val hold: Int,
    val alpha: Int,
    /** The pager listener's scroll state change, the index after it read the pager, and the registers of the pager and the state. */
    val scrolled: Method,
    val settle: Int,
    val pager: Int,
    val scrollState: Int,
    /**
     * How many instructions the turn-on, the turn-off, the viewer's turn-on, the row, the chrome
     * setter and the scroll state change had when their places were found.
     */
    val lengths: List<Int>,
)

internal fun BytecodePatchContext.findClearMode(): ClearModeHooks {
    val wanted = CLEAR_MODE_MARKERS.toSet()
    val marked = mutableMapOf<String, MutableList<Method>>()
    val holders = typesMarked(*CLEAR_MODE_MARKERS.toTypedArray())
    classDefForEach { classDef ->
        if (classDef.type !in holders) return@classDefForEach
        classDef.methods.forEach { method ->
            method.markers().filter { it in wanted }.distinct().forEach { marked.getOrPut(it) { mutableListOf() } += method }
        }
    }
    fun single(marker: String): Method {
        val found = marked[marker].orEmpty()
        return found.singleOrNull() ?: stop("expected one method marked $marker, found ${found.size}")
    }

    // The handler's turn-on: a public instance method taking the reason, which runs one action with it.
    val enable = single(CLEAR_MODE_ENABLE)
    val handler = enable.definingClass
    val handlerClass = classDefByOrNull(handler) ?: stop("the handler $handler isn't in the app")
    if (enable.isStatic() || !enable.isPublic() || enable.returnType != "V" || enable.parameterTypes.size != 1 ||
        !AccessFlags.PUBLIC.isSet(handlerClass.accessFlags)
    ) {
        stop("${enable.describe()}, marked $CLEAR_MODE_ENABLE, isn't a public instance method of a public class taking the reason")
    }
    val reason = enable.parameterTypes.single().toString()
    val reasonClass = classDefByOrNull(reason) ?: stop("the reason $reason isn't in the app")
    if (reasonClass.superclass != "Ljava/lang/Enum;" || !AccessFlags.PUBLIC.isSet(reasonClass.accessFlags)) {
        stop("the reason $reason isn't a public enum, so the extension can't tell a swipe from your own way out")
    }
    if (CLEAR_MODE_SWIPED_AWAY !in reasonClass.namedConstants()) stop("the reason $reason doesn't name $CLEAR_MODE_SWIPED_AWAY")
    val entered = enable.afterAction(listOf(0))

    // The handler's turn-off: the same class, taking the reel and the reason, which runs one action with both.
    val dismiss = single(CLEAR_MODE_DISMISS)
    if (dismiss.definingClass != handler) stop("$CLEAR_MODE_DISMISS isn't in $handler")
    if (dismiss.isStatic() || dismiss.returnType != "V" || dismiss.parameterTypes.size != 2 ||
        dismiss.parameterTypes[1].toString() != reason
    ) {
        stop("${dismiss.describe()}, marked $CLEAR_MODE_DISMISS, isn't an instance (reel, reason) method")
    }
    val item = dismiss.parameterTypes[0].toString()
    val itemClass = classDefByOrNull(item) ?: stop("the reel $item isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(itemClass.accessFlags)) stop("the reel $item isn't public, so the extension can't reach it")
    val ids = itemClass.methods.filter {
        it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == STRING && it.isPublic() && !it.isStatic()
    }
    if (ids.size != 1) stop("the reel $item has ${ids.size} public getId() answering a string, expected one")
    val left = dismiss.afterAction(listOf(0, 1))

    // The viewer's turn-on: reads the reel the pager is on once, keeps it and tests it for null right away.
    // The read asks the pager for its reel, or, where Redex folded the pager lookup into it (451's
    // x86 385713117 has LX/03ST->A01), is a static call handed the one thing the pager comes from.
    val enabled = single(CLEAR_MODE_ENABLED)
    val code = enabled.body()
    val reads = code.indices.filter { at ->
        val call = code[at].methodReference() ?: return@filter false
        call.returnType == item &&
            if (code[at].isStaticCall()) call.parameterTypes.size == 1 else call.parameterTypes.isEmpty()
    }
    val read = reads.singleOrNull()
        ?: stop("${enabled.describe()} reads the reel the pager is on ${reads.size} times, expected once")
    val kept = code.getOrNull(read + 1) as? OneRegisterInstruction
    val test = code.getOrNull(read + 2) as? OneRegisterInstruction
    if (kept == null || test == null || kept.opcode != Opcode.MOVE_RESULT_OBJECT || test.opcode != Opcode.IF_EQZ ||
        test.registerA != kept.registerA
    ) {
        stop("${enabled.describe()} doesn't test the reel for null right after reading it off the pager")
    }
    val current = read + 2
    if (current in enabled.jumpTargets()) stop("${enabled.describe()} jumps to its test of the reel, so the hook would be skipped")
    enabled.requireNothingWithoutReel(current)

    // The pager listener's page report.
    val paged = single(REEL_PAGE_SELECTED)
    if (paged.isStatic() || paged.returnType != "V" || paged.parameterTypes.map(Any::toString) != listOf("I", "I") ||
        paged.implementation == null
    ) {
        stop("${paged.describe()}, marked $REEL_PAGE_SELECTED, isn't an instance (int, int) method returning nothing")
    }

    // The exit button's render: an instance method taking the scope, in a class holding the reel once.
    val button = single(CLEAR_MODE_BUTTON)
    if (button.isStatic() || button.parameterTypes.size != 1 || !button.returnType.startsWith("L") || button.implementation == null) {
        stop("${button.describe()}, marked $CLEAR_MODE_BUTTON, isn't a render taking one scope and answering a component")
    }
    val reels = classDefBy(button.definingClass).fields.filter { it.type == item && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val buttonReel = reels.singleOrNull()
        ?: stop("the exit button ${button.definingClass} holds the reel in ${reels.size} fields, expected one")
    if (button.localRegisterCount() < 1) stop("${button.describe()} has no local register for the hook")

    // The seek bar row's render: an instance method taking the scope, in a class holding its state,
    // whose state holds the reel's. The reel's state is the class whose toString names the clear mode flag.
    val row = single(CLEAR_MODE_SCRUBBER_ROW)
    if (row.isStatic() || row.parameterTypes.size != 1 || !row.returnType.startsWith("L") || row.implementation == null) {
        stop("${row.describe()}, marked $CLEAR_MODE_SCRUBBER_ROW, isn't a render taking one scope and answering a component")
    }
    val flagged = classesLoadingString(CLEAR_MODE_FLAG_NAME).mapNotNull { state ->
        state.methods.singleOrNull {
            it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == STRING && loadsString(it, CLEAR_MODE_FLAG_NAME)
        }
    }.associateBy { it.definingClass }
    val held = classDefBy(row.definingClass).instanceFields.flatMap { holder ->
        classDefByOrNull(holder.type)?.instanceFields?.filter { it.type in flagged }?.map { holder to it }.orEmpty()
    }
    val (rowState, reelState) = held.singleOrNull()
        ?: stop("the seek bar row ${row.definingClass} holds a reel state naming its clear mode flag through ${held.size} fields, expected one")
    val flag = clearModeFlag(flagged.getValue(reelState.type))
    listOf(rowState, reelState, flag).forEach { field ->
        if (!readableFrom(field, row.definingClass)) stop("the seek bar row ${row.definingClass} can't read ${field.describe()}")
    }

    // What the row draws the scrubber from: everything it builds from both the reel and its state.
    val rowCode = row.body()
    val children = rowCode.indices.filter { at ->
        val built = (rowCode[at] as? ReferenceInstruction)?.reference as? TypeReference
        rowCode[at].opcode == Opcode.NEW_INSTANCE && built != null &&
            classDefByOrNull(built.type)?.methods?.any { constructor ->
                constructor.name == "<init>" && constructor.parameterTypes.map(Any::toString).let { item in it && reelState.type in it }
            } == true
    }
    if (children.isEmpty()) stop("${row.describe()} builds nothing from the reel and its state, so there's no scrubber to hold back")

    // The test of whether to draw the scrubber: the one test of an unboxed yes or no that skips
    // everything the scrubber is built from when no, and leads to all of it when yes.
    val flow = ControlFlow.of(row)
    val tests = rowCode.indices.filter { at ->
        val test = rowCode[at] as? OneRegisterInstruction
        val kept = rowCode.getOrNull(at - 1) as? OneRegisterInstruction
        val unboxed = rowCode.getOrNull(at - 2)?.methodReference()
        test != null && kept != null && test.opcode == Opcode.IF_EQZ && kept.opcode == Opcode.MOVE_RESULT &&
            kept.registerA == test.registerA && unboxed?.definingClass == "Ljava/lang/Boolean;" && unboxed.name == "booleanValue"
    }.filter { at ->
        val skipped = flow.reachedFrom(flow.normal[at].first())
        val passed = flow.reachedFrom(at + 1)
        children.none { skipped[it] } && children.all { passed[it] }
    }
    val scrubber = tests.singleOrNull() ?: stop("${row.describe()} tests whether to draw the scrubber ${tests.size} times, expected once")
    val shown = (rowCode[scrubber] as OneRegisterInstruction).registerA
    if (scrubber in row.jumpTargets()) stop("${row.describe()} jumps to its test of the scrubber, so the hook would be skipped")
    if (shown > 15) stop("${row.describe()} tests the scrubber in v$shown, which the hook's call can't name")
    row.requireThisIntact(KEEP, listOf(scrubber))
    val borrowed = row.freeLocalsAt(KEEP, scrubber, 1, except = listOf(shown)).single()

    // The reel holder's chrome alpha setter: an instance method taking the alpha, which reads the
    // alpha it holds once, compares and stores the asked one. The hook goes right after the read,
    // where both are in hand, and answers the alpha to store in the asked one's register.
    val chrome = single(CLEAR_MODE_CHROME)
    if (chrome.isStatic() || chrome.returnType != "V" || chrome.parameterTypes.map(Any::toString) != listOf("F") ||
        chrome.implementation == null
    ) {
        stop("${chrome.describe()}, marked $CLEAR_MODE_CHROME, isn't an instance method taking the alpha")
    }
    val chromeCode = chrome.body()
    val heldReads = chromeCode.indices.filter { at ->
        val call = chromeCode[at].methodReference()
        call?.definingClass == NUMBER && call.name == "floatValue" && call.parameterTypes.isEmpty() && call.returnType == "F"
    }
    val heldRead = heldReads.singleOrNull()
        ?: stop("${chrome.describe()} reads the alpha it holds ${heldReads.size} times, expected once")
    val heldKept = chromeCode.getOrNull(heldRead + 1) as? OneRegisterInstruction
    if (heldKept == null || heldKept.opcode != Opcode.MOVE_RESULT) stop("${chrome.describe()} doesn't keep the alpha it holds")
    val hold = heldRead + 2
    if (hold >= chromeCode.size) stop("${chrome.describe()} ends at reading the alpha it holds")
    if (hold in chrome.jumpTargets()) stop("${chrome.describe()} jumps past its read of the alpha it holds, so the hook would be skipped")
    val asked = chrome.parameterRegisterNumber(0)
    val alpha = heldKept.registerA
    if (asked > 15 || alpha > 15) stop("${chrome.describe()} keeps the alphas in v$asked and v$alpha, which the hook's call can't name")
    chrome.requireParameterIntact(KEEP, 0, listOf(hold))
    val stored = (hold until chromeCode.size).any { at ->
        val call = chromeCode[at].methodReference()
        call?.definingClass == FLOAT && call.name == "valueOf" && call.parameterTypes.map(Any::toString) == listOf("F") &&
            chromeCode[at].registers() == listOf(asked)
    }
    if (!stored) stop("${chrome.describe()} doesn't store the alpha it was asked for")

    // The pager listener's scroll state change: an instance method taking the state, an enum naming
    // the pager at rest, which reads the pager once. On 452 that read sits on the way to rest only.
    // The hook goes right after it, where the pager and the state are both in hand.
    val scrolled = single(REEL_SCROLL_STATE)
    if (scrolled.isStatic() || scrolled.returnType != "V" || scrolled.parameterTypes.size != 1 || scrolled.implementation == null) {
        stop("${scrolled.describe()}, marked $REEL_SCROLL_STATE, isn't an instance method taking the scroll state")
    }
    val state = scrolled.parameterTypes.single().toString()
    val stateClass = classDefByOrNull(state) ?: stop("the scroll state $state isn't in the app")
    if (stateClass.superclass != "Ljava/lang/Enum;") stop("the scroll state $state isn't an enum")
    if (REEL_AT_REST !in stateClass.namedConstants()) stop("the scroll state $state doesn't name $REEL_AT_REST")
    val scrolledCode = scrolled.body()
    val pagerReads = scrolledCode.indices.filter { at ->
        scrolledCode[at].opcode == Opcode.IGET_OBJECT &&
            ((scrolledCode[at] as ReferenceInstruction).reference as FieldReference).type == VIEW_PAGER
    }
    val pagerRead = pagerReads.singleOrNull()
        ?: stop("${scrolled.describe()} reads the pager ${pagerReads.size} times, expected once")
    val settle = pagerRead + 1
    if (settle >= scrolledCode.size) stop("${scrolled.describe()} ends at reading the pager")
    if (settle in scrolled.jumpTargets()) stop("${scrolled.describe()} jumps past its read of the pager, so the hook would be skipped")
    val pager = (scrolledCode[pagerRead] as TwoRegisterInstruction).registerA
    val scrollState = scrolled.parameterRegisterNumber(0)
    if (pager > 15 || scrollState > 15) {
        stop("${scrolled.describe()} keeps the pager and the state in v$pager and v$scrollState, which the hook's call can't name")
    }
    scrolled.requireParameterIntact(KEEP, 0, listOf(settle))

    val every = listOf(enable, dismiss, enabled, paged, button, row, chrome, scrolled).map { it.describe() }
    if (every.toSet().size != every.size) stop("two hooks would go in one method: $every")

    return ClearModeHooks(
        enable, entered, dismiss, left, enabled, current, kept.registerA, paged, button,
        buttonReel.describe(), handler, reason, item,
        row, scrubber, shown, borrowed, rowState.describe(), reelState.describe(), flag.describe(),
        chrome, hold, alpha,
        scrolled, settle, pager, scrollState,
        listOf(enable, dismiss, enabled, row, chrome, scrolled).map { it.body().size },
    )
}

/** The names an enum's initializer gives its constants, as the strings it loads. */
private fun ClassDef.namedConstants(): List<String> = methods.filter { it.name == "<clinit>" }.flatMap { initializer ->
    initializer.body().mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
}

/**
 * The boolean field of its own class that [toString] appends right after naming the clear mode
 * flag: the register handed to the first boolean append after the name, traced back through moves
 * to the field read that filled it, with nothing jumping in between.
 */
private fun BytecodePatchContext.clearModeFlag(toString: Method): Field {
    val code = toString.body()
    val names = code.indices.filter { stringLoadedAt(code, it) == CLEAR_MODE_FLAG_NAME }
    val name = names.singleOrNull() ?: stop("${toString.describe()} names the clear mode flag ${names.size} times, expected once")
    val append = (name + 1 until code.size).firstOrNull { at ->
        val call = code[at].methodReference()
        call?.definingClass == BUILDER && call.name == "append" && call.parameterTypes.map(Any::toString) == listOf("Z")
    } ?: stop("${toString.describe()} appends no yes or no after naming the clear mode flag")
    if ((name + 1 until append).any { stringLoadedAt(code, it) != null }) {
        stop("${toString.describe()} names something else before appending the clear mode flag")
    }
    var register = code[append].registers().getOrNull(1) ?: stop("${toString.describe()} appends the clear mode flag from no register")
    var read = append
    while (true) {
        read = (read - 1 downTo 0).firstOrNull { code[it].writes(register) }
            ?: stop("${toString.describe()} appends the clear mode flag from a register nothing filled")
        val write = code[read]
        when (write.opcode) {
            Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16 -> register = (write as TwoRegisterInstruction).registerB
            Opcode.IGET_BOOLEAN -> break
            else -> stop("${toString.describe()} appends the clear mode flag from a ${write.opcode.name}, not a field it read") // opcode-name-ok
        }
    }
    if (toString.jumpTargets().any { it in read + 1..append }) {
        stop("${toString.describe()} jumps between reading the clear mode flag and appending it")
    }
    val field = (code[read] as ReferenceInstruction).reference as FieldReference
    if (field.definingClass != toString.definingClass) stop("${toString.describe()} appends ${field.definingClass}'s flag, not its own")
    return classDefBy(field.definingClass).instanceFields.singleOrNull { it.name == field.name && it.type == "Z" }
        ?: stop("${field.definingClass} has no boolean field ${field.name}")
}

/** Whether code in [reader] may read [field]: it's [reader]'s own, or the field and its class are public, or same package and not private. */
private fun BytecodePatchContext.readableFrom(field: Field, reader: String): Boolean {
    if (field.definingClass == reader) return true
    val owner = classDefByOrNull(field.definingClass) ?: return false
    val near = field.definingClass.substringBeforeLast('/') == reader.substringBeforeLast('/')
    fun open(flags: Int) = AccessFlags.PUBLIC.isSet(flags) || (near && !AccessFlags.PRIVATE.isSet(flags))
    return open(owner.accessFlags) && open(field.accessFlags)
}

/** Every instruction a path from [start] can run, [start] included, over normal and exceptional edges. */
private fun ControlFlow.reachedFrom(start: Int): BitSet {
    val reached = BitSet()
    val pending = ArrayDeque(listOf(start))
    reached.set(start)
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        (normal[at] + exceptional[at]).forEach { if (!reached[it]) { reached.set(it); pending += it } }
    }
    return reached
}

private const val BUILDER = "Ljava/lang/StringBuilder;"
private const val NUMBER = "Ljava/lang/Number;"
private const val FLOAT = "Ljava/lang/Float;"

/**
 * The index after the one action [this] handler method runs: an interface call named invoke that
 * takes objects and is handed each of the method's [parameters], whose registers and `this` still
 * hold what the method was called with there.
 */
private fun Method.afterAction(parameters: List<Int>): Int {
    val code = body()
    val actions = code.indices.filter { at ->
        val call = code[at].methodReference() ?: return@filter false
        (code[at].opcode == Opcode.INVOKE_INTERFACE || code[at].opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
            call.name == "invoke" && call.returnType == OBJECT && call.parameterTypes.size == parameters.size &&
            call.parameterTypes.all { it.toString() == OBJECT }
    }
    val action = actions.singleOrNull() ?: stop("${describe()} runs ${actions.size} actions, expected one")
    val handed = code[action].registers().drop(1).toSet()
    if (handed != parameters.mapTo(HashSet()) { parameterRegisterNumber(it) }) {
        stop("${describe()} doesn't hand its action what it was called with")
    }
    val after = action + 1
    if (after >= code.size) stop("${describe()} ends at its action's call")
    // A hook between a call and the move that keeps its result leaves a class that won't verify.
    if (code[after].opcode == Opcode.MOVE_RESULT_OBJECT) {
        stop("${describe()} keeps what its action answers, so no hook can go right after the call")
    }
    requireThisIntact(KEEP, listOf(after))
    parameters.forEach { requireParameterIntact(KEEP, it, listOf(after)) }
    return after
}

/**
 * Throws unless the branch [this] takes at instruction [test] when there's no reel only returns:
 * nothing is called and nothing branches on the way. That is what lets the extension hold a
 * turn-on back by answering no reel.
 */
private fun Method.requireNothingWithoutReel(test: Int) {
    val code = body()
    var at = ControlFlow.of(this).normal[test].first()
    while (at < code.size && code[at].opcode !in RETURNS) {
        if (code[at].methodReference() != null || code[at] is OffsetInstruction || code[at].opcode in THROWS) {
            stop("${describe()} does more than return when the pager has no reel, at instruction $at")
        }
        at++
    }
    if (at >= code.size) stop("${describe()} doesn't return when the pager has no reel")
}

private val RETURNS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE)
private val THROWS = setOf(Opcode.THROW)

/**
 * Each hook reads registers the find phase checked and borrows none but the exit button's, which
 * goes in first where no local holds anything yet, and the seek bar row's, a local nothing reads
 * past its test. The two handler hooks and the viewer's go in with a plain insert on purpose: the
 * handler's own branch around a missing action keeps landing past the hook. Those three, the row's,
 * the chrome setter's and the scroll state change's go in by the index the find phase read, which
 * holds only while each method is as long as it was, so one that changed in between stops the patch
 * before any hook is in.
 */
internal fun BytecodePatchContext.applyClearMode(found: ClearModeHooks) {
    val enable = changing(found.enable)
    val dismiss = changing(found.dismiss)
    val enabled = changing(found.enabled)
    val row = changing(found.row)
    val chrome = changing(found.chrome)
    val scrolled = changing(found.scrolled)
    listOf(enable, dismiss, enabled, row, chrome, scrolled).forEachIndexed { at, method ->
        if (method.body().size != found.lengths[at]) stop("${method.describe()} changed after its hook's place was found")
    }
    enable.addInstructions(found.entered, "invoke-static/range { p0 .. p1 }, $CLEAR_MODE_ENTERED")
    dismiss.addInstructions(found.left, "invoke-static/range { p0 .. p2 }, $CLEAR_MODE_LEFT")
    val reel = found.reel
    enabled.addInstructions(
        found.current,
        """
            invoke-static/range { v$reel .. v$reel }, $CLEAR_MODE_CURRENT
            move-result-object v$reel
            check-cast v$reel, ${found.item}
        """,
    )
    changing(found.paged).addInstructions(0, "invoke-static { }, $CLEAR_MODE_PAGED")
    changing(found.button).addInstructions(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${found.buttonReel}
            invoke-static { v0 }, $CLEAR_MODE_BUILT
        """,
    )
    // A row or reel state that isn't there yet counts as clear mode off. The labels sit mid-method,
    // so they go through addInstructionsWithLabels, which places them where the snippet lands.
    val shown = found.shown
    val flag = found.borrowed
    row.addInstructionsWithLabels(
        found.scrubber,
        """
            move-object/from16 v$flag, p0
            iget-object v$flag, v$flag, ${found.rowState}
            if-eqz v$flag, :off
            iget-object v$flag, v$flag, ${found.reelState}
            if-eqz v$flag, :off
            iget-boolean v$flag, v$flag, ${found.flag}
            goto :ask
            :off
            const/4 v$flag, 0x0
            :ask
            invoke-static { v$shown, v$flag }, $CLEAR_MODE_SCRUBBER
            move-result v$shown
        """,
    )
    // The alpha asked for and the one held go in, and what comes back is what Instagram compares and stores.
    chrome.addInstructions(
        found.hold,
        """
            invoke-static { p1, v${found.alpha} }, $CLEAR_MODE_CHROME_ALPHA
            move-result p1
        """,
    )
    // The pager just read and the state go in, and nothing comes back.
    scrolled.addInstructions(found.settle, "invoke-static { v${found.pager}, v${found.scrollState} }, $CLEAR_MODE_SETTLED")
}

/** The extension's stubs, found before anything changes, and the step that fills them. */
internal class ClearModeStubs(val enter: MutableMethod, val reelId: MutableMethod) {
    fun fill(found: ClearModeHooks) {
        enter.addInstructions(
            0,
            """
                check-cast p0, ${found.handler}
                check-cast p1, ${found.reason}
                invoke-virtual { p0, p1 }, ${found.handler}->${found.enable.name}(${found.reason})V
                return-void
            """,
        )
        reelId.addInstructions(
            0,
            """
                check-cast p0, ${found.item}
                invoke-virtual { p0 }, ${found.item}->getId()$STRING
                move-result-object p0
                return-object p0
            """,
        )
    }
}

internal fun BytecodePatchContext.clearModeStubs(): ClearModeStubs {
    val extension = mutableClassDefBy(CLEAR_MODE)
    fun stub(name: String, parameters: List<String>, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == parameters
    } ?: stop("$CLEAR_MODE has no static $returns $name(${parameters.joinToString("")})")
    return ClearModeStubs(
        enter = stub(CLEAR_MODE_ENTER_STUB, listOf(OBJECT, OBJECT), "V"),
        reelId = stub(CLEAR_MODE_ID_STUB, listOf(OBJECT), STRING),
    )
}

private fun stop(detail: String): Nothing = throw PatchException("$KEEP: $detail")

private fun BytecodePatchContext.changing(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.isStatic(): Boolean = AccessFlags.STATIC.isSet(accessFlags)

private fun Method.isPublic(): Boolean = AccessFlags.PUBLIC.isSet(accessFlags)

private fun Method.describe(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Field.describe(): String = "$definingClass->$name:$type"

/** Whether this instruction writes [register], or the pair starting just below it. */
private fun Instruction.writes(register: Int): Boolean {
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return opcode.setsRegister() && (written == register || (opcode.setsWideRegister() && written + 1 == register))
}

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.isStaticCall(): Boolean = opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE

/** The registers an invoke names, in order. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

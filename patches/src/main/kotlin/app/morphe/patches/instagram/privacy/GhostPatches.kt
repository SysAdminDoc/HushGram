/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/*
 * The four privacy patches that keep other people from learning something about you. Each one finds
 * its place by things Instagram keeps from one build to the next (a log string, a request path, a
 * class and method name of its own network layer, or the flags a text box asks for) and refuses
 * before changing anything when it isn't found exactly once. The patches only ask the extension a
 * question first thing in the method; what happens to the answer is in Ghost.
 */

// ---- View chats anonymously -------------------------------------------------------------------

private const val VIEW_CHATS = "View chats anonymously"

/** The string the chat-read request puts in its trace names, which Instagram keeps. */
internal const val MARK_THREAD_SEEN = "mark_thread_seen-"

@Suppress("unused")
val viewChatsAnonymouslyPatch = bytecodePatch(
    name = "View chats anonymously",
    description = "Opening a chat doesn't tell the other person you've read it, so it never shows Seen. " +
        "Replying still does. Has its own switch in HushGram's settings.",
    default = false,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("viewChats")
        holdChatSeen()
        enableStatus("viewChats")
    }
}

/**
 * The one static method that sends a thread's read marker names "mark_thread_seen-" in its trace and
 * takes the account, a callback and the thread, message and sender ids. It returns at once while the
 * switch is on.
 */
internal fun BytecodePatchContext.findChatSeen(): Method {
    val found = methodsHolding(MARK_THREAD_SEEN).filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.size == 5 && it.parameterTypes[0] == "Lcom/instagram/common/session/UserSession;" &&
            it.parameterTypes.drop(2).all { type -> type == "Ljava/lang/String;" }
    }
    return found.exactlyOne(VIEW_CHATS, "static method sending a chat read marker")
}

internal fun BytecodePatchContext.holdChatSeen() {
    mutable(findChatSeen()).returnVoidWhen(VIEW_CHATS, HOLD_CHAT_SEEN)
}

// ---- Disable typing status --------------------------------------------------------------------

private const val TYPING = "Disable typing status"

/**
 * The flags Instagram's chat box asks the keyboard for, which only its message composer's text
 * watcher loads together (0x800033 and 0x800013).
 */
internal const val COMPOSER_FLAG_A = 0x800033L
internal const val COMPOSER_FLAG_B = 0x800013L

@Suppress("unused")
val disableTypingStatusPatch = bytecodePatch(
    name = "Disable typing status",
    description = "Chats don't show that you're typing. Has its own switch in HushGram's settings.",
    default = false,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("typingStatus")
        holdTyping(findTyping())
        enableStatus("typingStatus")
    }
}

/** Where the typing hook goes: the message composer's `onTextChanged` and the index of the flag read. */
internal class TypingSite(val method: Method, val at: Int, val register: Int)

/**
 * The text watcher both flags are loaded in has an `afterTextChanged(Editable)` and an
 * `onTextChanged(CharSequence, int, int, int)`. In the second, the first boolean field it reads and
 * tests (an iget-boolean straight into an if-nez on the same register) is the "already typing"
 * check, and the hook goes right before it. Nothing may jump to that read, and the register it
 * writes mustn't be the one it reads from, so using it before the read costs nothing.
 */
internal fun BytecodePatchContext.findTyping(): TypingSite {
    val both = classesLoading(COMPOSER_FLAG_A).map { it.type }.toSet()
        .intersect(classesLoading(COMPOSER_FLAG_B).map { it.type }.toSet())
    val watchers = both.mapNotNull { type ->
        val classDef = classDefByOrNull(type) ?: return@mapNotNull null
        val after = classDef.methods.any {
            it.name == "afterTextChanged" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/text/Editable;")
        }
        val on = classDef.methods.singleOrNull {
            it.name == "onTextChanged" && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;", "I", "I", "I")
        }
        if (after) on else null
    }
    val watcher = watchers.exactlyOne(TYPING, "message composer text watcher")
    val code = watcher.code()
    val at = code.indices.firstOrNull { index ->
        val read = code[index]
        val test = code.getOrNull(index + 1)
        read.opcode == Opcode.IGET_BOOLEAN && test?.opcode == Opcode.IF_NEZ &&
            (test as OneRegisterInstruction).registerA == (read as TwoRegisterInstruction).registerA &&
            read.registerB != read.registerA
    } ?: refuse(TYPING, "${watcher.describe()} has no boolean field read straight into a test")
    val register = (code[at] as OneRegisterInstruction).registerA
    return TypingSite(watcher, at, register)
}

internal fun BytecodePatchContext.holdTyping(site: TypingSite) {
    val method = mutable(site.method)
    if (site.at in method.jumpTargets()) refuse(TYPING, "something jumps to the check in ${method.describe()}")
    if (site.register > 255) refuse(TYPING, "${method.describe()} keeps the check's value in v${site.register}")
    method.addInstructionsWithLabels(
        site.at,
        """
            invoke-static { }, $HOLD_TYPING
            move-result v${site.register}
            if-eqz v${site.register}, :instagram
            return-void
        """,
        ExternalLabel("instagram", method.getInstruction(site.at)),
    )
}

// ---- Disable screenshot detection -------------------------------------------------------------

private const val SCREENSHOTS = "Disable screenshot detection"

/** The log name of the event Instagram files when a screenshot is taken in a chat. */
internal const val CHAT_SCREENSHOT = "igd_screenshot_capture"

/** The two strings the screenshot folder watcher loads, which Instagram keeps. */
internal val FOLDER_WATCHER = arrayOf("ig_android_story_screenshot_directory", "screenshot_detector")

@Suppress("unused")
val disableScreenshotDetectionPatch = bytecodePatch(
    name = "Disable screenshot detection",
    description = "A screenshot of a chat or a story isn't reported to the other person. " +
        "Has its own switch in HushGram's settings.",
    default = false,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("screenshotDetection")
        val capture = findScreenshotReport()
        val watcher = findFolderWatcher()
        mutable(capture).returnVoidWhen(SCREENSHOTS, HOLD_SCREENSHOTS)
        holdFolderWatcher(watcher)
        enableStatus("screenshotDetection")
    }
}

/** The instance method taking a timestamp that files [CHAT_SCREENSHOT]: what tells the chat. */
internal fun BytecodePatchContext.findScreenshotReport(): Method =
    methodsHolding(CHAT_SCREENSHOT).filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("J")
    }.exactlyOne(SCREENSHOTS, "method reporting a chat screenshot")

internal class WatcherSite(val method: Method, val at: Int, val register: Int)

/**
 * The runnable that starts watching the phone's screenshot folders names both [FOLDER_WATCHER]
 * strings. Where it calls `FileObserver.startWatching`, the hook skips that call while the switch
 * is on. The instruction after the call loads a constant into a register the call doesn't use, which
 * is borrowed for the answer: it's written again right there.
 */
internal fun BytecodePatchContext.findFolderWatcher(): WatcherSite {
    val method = methodsHolding(*FOLDER_WATCHER).filter { it.returnType == "V" && it.parameterTypes.isEmpty() }
        .exactlyOne(SCREENSHOTS, "runnable watching the screenshot folders")
    val code = method.code()
    val calls = code.indices.filter { index ->
        val called = code[index].methodReference()
        called?.definingClass == "Landroid/os/FileObserver;" && called.name == "startWatching"
    }
    val at = calls.exactlyOne(SCREENSHOTS, "startWatching call in ${method.describe()}")
    val next = code.getOrNull(at + 1) ?: refuse(SCREENSHOTS, "${method.describe()} ends at startWatching")
    if (next.opcode != Opcode.CONST_4 && next.opcode != Opcode.CONST_16 && next.opcode != Opcode.CONST) {
        refuse(SCREENSHOTS, "${method.describe()} doesn't load a constant after startWatching")
    }
    val register = (next as OneRegisterInstruction).registerA
    return WatcherSite(method, at, register)
}

internal fun BytecodePatchContext.holdFolderWatcher(site: WatcherSite) {
    val method = mutable(site.method)
    if (site.at in method.jumpTargets()) refuse(SCREENSHOTS, "something jumps to startWatching in ${method.describe()}")
    method.addInstructionsWithLabels(
        site.at,
        """
            invoke-static { }, $HOLD_SCREENSHOTS
            move-result v${site.register}
            if-nez v${site.register}, :instagram
        """,
        ExternalLabel("instagram", method.getInstruction(site.at + 1)),
    )
}

// ---- View live anonymously --------------------------------------------------------------------

private const val LIVE = "View live anonymously"

/** Instagram's own network layer, a name it keeps, and the method every request starts through. */
internal const val TIGON_LAYER = "Lcom/instagram/api/tigon/TigonServiceLayer;"
internal const val START_REQUEST = "startRequest"
private const val URI_TYPE = "Ljava/net/URI;"

@Suppress("unused")
val viewLiveAnonymouslyPatch = bytecodePatch(
    name = "View live anonymously",
    description = "You aren't counted or listed as a viewer of a live video. The video can still show a " +
        "notice about a connection problem. Has its own switch in HushGram's settings.",
    default = false,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("viewLive")
        gateRequests(findRequestStart())
        enableStatus("viewLive")
    }
}

internal class RequestSite(val method: Method, val after: Int, val register: Int)

/**
 * The network layer's `startRequest` reads the request's address out of the request object before it
 * does anything else with it: the first iget-object of a `java.net.URI`. The hook goes right after
 * it, handed that register, and nothing may jump to the instruction after the read.
 */
internal fun BytecodePatchContext.findRequestStart(): RequestSite {
    val layer = classDefByOrNull(TIGON_LAYER) ?: refuse(LIVE, "$TIGON_LAYER isn't in this build")
    val method = layer.methods.filter {
        it.name == START_REQUEST && it.parameterTypes.size == 3 && it.returnType != "V" &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    }.exactlyOne(LIVE, "$START_REQUEST in $TIGON_LAYER")
    val code = method.code()
    val read = code.indices.firstOrNull { index ->
        code[index].opcode == Opcode.IGET_OBJECT && code[index].fieldReference()?.type == URI_TYPE
    } ?: refuse(LIVE, "${method.describe()} never reads a request's address")
    val register = (code[read] as OneRegisterInstruction).registerA
    return RequestSite(method, read + 1, register)
}

internal fun BytecodePatchContext.gateRequests(site: RequestSite) {
    val method = mutable(site.method)
    if (site.after in method.jumpTargets()) refuse(LIVE, "something jumps past the address read in ${method.describe()}")
    method.addInstructions(
        site.after,
        "invoke-static/range { v${site.register} .. v${site.register} }, $GATE",
    )
}

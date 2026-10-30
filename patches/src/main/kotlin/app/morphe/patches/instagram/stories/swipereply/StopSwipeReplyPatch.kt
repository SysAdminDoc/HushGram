/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.swipereply

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Stop swipe up to reply"
internal const val STORY_SWIPE_REPLY = "$EXTENSION_PACKAGE/stories/StorySwipeReply;"
internal const val KEEPS_SWIPE = "$STORY_SWIPE_REPLY->keepsSwipe()Z"

/** The name Instagram's build keeps for the story viewer's item delegate, in a static field. */
internal const val ITEM_DELEGATE = "ReelViewerItemDelegateImpl"

/** What the delegate throws when it reaches its listener manager before it's set, a Kotlin lateinit's name. */
internal const val LISTENER_MANAGER = "reelViewerListenerManager"

internal const val REQUEST_FOCUS = "Landroid/view/View;->requestFocus()Z"

/**
 * Strings the story reply composer's class loads in every 453 build. 453 has a second listener
 * focusing a text box, the Live viewer's comment box (inlined into the listener on some builds, a
 * forward to IgLiveViewerPageViewHolder's controller on others), which loads none.
 */
internal val COMPOSER_MARKERS = listOf("fragment_paused", "ig4a-instagram-schema")

private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val INTEGER = "Ljava/lang/Integer;"

/** The delegate's scroll and fling handler: two motion events, the view, two distances and a flag. */
internal val GESTURE_PARAMETERS = listOf(MOTION_EVENT, MOTION_EVENT, "Ljava/lang/Object;", "F", "F", "Z")

/**
 * Keeps a swipe up on someone's story from opening the reply box (piko #2134). In the default
 * selection with its switch off, since it changes how a gesture works.
 *
 * On 452 the story viewer hands an upward swipe to its item delegate (0ASJ,
 * ReelViewerItemDelegateImpl). For a story you can reply to, the delegate opens a reply sheet when a
 * server test says so, and otherwise asks its listener manager (0AUj), which asks each listener in
 * turn. The reply composer's listener (0Axw) focuses the Send message box, opens the keyboard with
 * the emoji reactions above it and answers that it took the swipe. The patch asks
 * [KEEPS_SWIPE] first thing in that listener and right before the delegate opens the sheet; a no
 * makes the listener answer that it didn't take the swipe and sends the delegate on to its listeners
 * instead of the sheet, the way a story nobody can reply to goes.
 */
@Suppress("unused")
val stopSwipeReplyPatch = bytecodePatch(
    name = "Stop swipe up to reply",
    description = "Stops a swipe up on someone's story from opening the reply box and its reactions. Tapping " +
        "Send message still replies. Starts off. Turn it on in HushGram settings > Stories.",
    default = true,
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storySwipeReply")
        stopSwipeReply()
        enableStatus("storySwipeReply")
    }
}

/** Finds everything first, so a build missing any of it changes nothing, then puts both guards in. */
internal fun BytecodePatchContext.stopSwipeReply() = hookSwipeReply(findSwipeReply())

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Where the guards go: in the delegate's gesture handler, the load of the reply sheet's source
 * ([sheet]) and the start of the listener block it jumps to instead ([listeners]); and the
 * composer's listener method.
 */
internal class SwipeReplySites(
    val delegate: String,
    val gesture: String,
    val sheet: Int,
    val listeners: Int,
    val listenerManager: MethodReference,
    val composer: String,
    val composerMethod: String,
)

/**
 * Follows the swipe from the delegate to the composer, each step exactly once:
 *
 * - the delegate's gesture handler, the one [GESTURE_PARAMETERS] method answering a boolean in a
 *   class Instagram's build names [ITEM_DELEGATE] that loads [LISTENER_MANAGER];
 * - in it, the one `(Integer)V` call on its own class, the reply sheet, handed an Integer the
 *   instruction before it loads from a static field, with a return right after it and, after that,
 *   the listener block: the listener manager read from a field and asked a no-argument boolean;
 * - that method of the listener manager, asked by nothing but the gesture handler, asking one
 *   listener interface's method of the same name;
 * - the one implementation of it that calls [REQUEST_FOCUS] in a class loading [COMPOSER_MARKERS],
 *   the composer's.
 *
 * Fails before anything changes when any of it isn't so, since that's an update this patch hasn't
 * seen, or when code the guards go in front of is something jumps to.
 */
internal fun BytecodePatchContext.findSwipeReply(): SwipeReplySites {
    val gestures = classesLoadingString(LISTENER_MANAGER).filter { it.originalName() == ITEM_DELEGATE }.flatMap { classDef ->
        classDef.methods.filter {
            !it.isStatic() && it.parameters() == GESTURE_PARAMETERS && it.returnType == "Z" && loadsString(it, LISTENER_MANAGER)
        }
    }
    val gesture = gestures.singleOrNull()
        ?: refuse("expected one gesture handler of the story viewer's item delegate ($ITEM_DELEGATE) in this Instagram build, found ${gestures.size}")
    val where = "${gesture.definingClass}->${gesture.name}"
    val code = gesture.code()
    if (code.any { it.reference() == KEEPS_SWIPE }) refuse("$where already calls $KEEPS_SWIPE")

    val sheets = code.indices.filter { at ->
        val called = code[at].method()
        code[at].opcode == Opcode.INVOKE_VIRTUAL && called != null && called.definingClass == gesture.definingClass &&
            called.parameters() == listOf(INTEGER) && called.returnType == "V"
    }
    val call = sheets.singleOrNull() ?: refuse("expected $where to open one reply sheet, an (Integer)V call on its own class, found ${sheets.size}")
    val source = code.getOrNull(call - 1)
    val sourceField = (source as? ReferenceInstruction)?.reference as? FieldReference
    val invoke = code[call] as FiveRegisterInstruction
    if (source?.opcode != Opcode.SGET_OBJECT || sourceField?.type != INTEGER || invoke.registerCount != 2 ||
        (source as OneRegisterInstruction).registerA != invoke.registerD
    ) {
        refuse("$where doesn't load the reply sheet's source from a static Integer right before opening it")
    }
    val sheet = call - 1
    if (code.getOrNull(call + 1)?.opcode != Opcode.RETURN) refuse("$where doesn't return right after opening the reply sheet")

    val listeners = call + 2
    val managerRead = code.getOrNull(listeners)
    val managerField = ((managerRead as? ReferenceInstruction)?.reference as? FieldReference)
        ?.takeIf { managerRead?.opcode == Opcode.IGET_OBJECT && it.definingClass == gesture.definingClass }
        ?: refuse("$where doesn't read its listener manager after the reply sheet")
    val managerRegister = (managerRead as TwoRegisterInstruction).registerA
    val asked = (listeners + 1 until minOf(code.size - 2, listeners + 12)).firstOrNull { at ->
        val called = code[at].method()
        code[at].opcode == Opcode.INVOKE_VIRTUAL && called != null && called.definingClass == managerField.type &&
            called.parameterTypes.isEmpty() && called.returnType == "Z" && (code[at] as FiveRegisterInstruction).registerC == managerRegister
    } ?: refuse("$where doesn't ask its listener manager ${managerField.type} a boolean after the reply sheet")
    if (code[asked + 1].opcode != Opcode.MOVE_RESULT || code[asked + 2].opcode != Opcode.RETURN) {
        refuse("$where doesn't answer what its listener manager says")
    }
    val manager = code[asked].method()!!
    val managerAt = "${manager.definingClass}->${manager.name}"

    val askers = classesCalling(manager.definingClass, manager.name).flatMap { classDef ->
        classDef.methods.filter { method -> method.code().any { it.method()?.matches(manager) == true } }
    }
    if (askers.size != 1 || askers.single().definingClass != gesture.definingClass || askers.single().name != gesture.name) {
        refuse("expected only $where to ask $managerAt, found ${askers.joinToString { "${it.definingClass}->${it.name}" }}")
    }
    val managerMethod = classDefByOrNull(manager.definingClass)?.methods?.singleOrNull {
        it.name == manager.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
    } ?: refuse("$managerAt isn't in this build")
    // The loop over the listeners makes interface calls of its own, a List's or an Iterator's.
    val listenerCalls = managerMethod.code().mapNotNull { instruction ->
        instruction.method().takeIf {
            instruction.opcode == Opcode.INVOKE_INTERFACE && it?.name == manager.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }
    }.distinctBy { it.toString() }
    val listenerCall = listenerCalls.singleOrNull()
        ?: refuse("expected $managerAt to ask one listener's ${manager.name}()Z, found ${listenerCalls.joinToString()}")
    val listener = listenerCall.definingClass
    // The composer's guard answers every ask through this interface, so the manager has to be the only one asking.
    val listenerAskers = classesCalling(listener, listenerCall.name).flatMap { classDef ->
        classDef.methods.filter { method -> method.code().any { it.method()?.matches(listenerCall) == true } }
    }
    if (listenerAskers.size != 1 || listenerAskers.single().definingClass != manager.definingClass || listenerAskers.single().name != manager.name) {
        refuse("expected only $managerAt to ask ${listener}->${listenerCall.name}, found ${listenerAskers.joinToString { "${it.definingClass}->${it.name}" }}")
    }

    val composers = classesCalling("Landroid/view/View;", "requestFocus").filter { classDef ->
        listener in classDef.interfaces && COMPOSER_MARKERS.all { marker -> classDef.methods.any { loadsString(it, marker) } }
    }.flatMap { classDef ->
        classDef.methods.filter { method ->
            !method.isStatic() && method.name == listenerCall.name && method.parameterTypes.isEmpty() && method.returnType == "Z" &&
                method.code().any { it.reference() == REQUEST_FOCUS }
        }
    }
    val composer = composers.singleOrNull()
        ?: refuse("expected one $listener focusing a text box when the story is swiped up, found ${composers.size}")
    val composerAt = "${composer.definingClass}->${composer.name}"
    if (composer.code().any { it.reference() == KEEPS_SWIPE }) refuse("$composerAt already calls $KEEPS_SWIPE")
    // Only the swipe reaches it, through the listener manager; a direct call could be a tap the guard would stop.
    val direct = classesCalling(composer.definingClass, composer.name).filter { classDef ->
        classDef.methods.any { method ->
            method.code().any { val called = it.method(); called != null && called.definingClass == composer.definingClass && called.name == composer.name }
        }
    }
    if (direct.isNotEmpty()) refuse("expected only the swipe to reach $composerAt, ${direct.joinToString { it.type }} calls it too")
    if (0 in composer.jumpTargets()) refuse("a branch in $composerAt lands on its first instruction, where the guard goes")
    if (sheet in gesture.jumpTargets()) refuse("a branch in $where lands on the reply sheet's source, where the guard goes")

    return SwipeReplySites(gesture.definingClass, gesture.name, sheet, listeners, manager, composer.definingClass, composer.name)
}

/**
 * First thing in the composer's listener, asks [KEEPS_SWIPE] and answers false on a no, so the box
 * stays closed and the delegate hears nobody took the swipe; v0 is free before Instagram's first
 * instruction. In the delegate, right before the sheet's source is loaded, asks it again and on a no
 * jumps to the listener block instead of the sheet. The one local it borrows there is one nothing
 * reads afterwards on either way on.
 */
internal fun BytecodePatchContext.hookSwipeReply(sites: SwipeReplySites) {
    val composer = mutableClassDefBy(sites.composer).methods.single {
        it.name == sites.composerMethod && it.parameterTypes.isEmpty() && it.returnType == "Z"
    }
    composer.requireLocals(PATCH, 1)
    composer.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEPS_SWIPE
            move-result v0
            if-nez v0, :instagram
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("instagram", composer.getInstruction(0)),
    )

    val gesture: MutableMethod = mutableClassDefBy(sites.delegate).methods.single {
        it.name == sites.gesture && it.parameters() == GESTURE_PARAMETERS
    }
    val free = gesture.freeLocalsAt(PATCH, sites.sheet, 1, targets = listOf(sites.listeners), highest = 255).single()
    gesture.addInstructionsWithLabels(
        sites.sheet,
        """
            invoke-static { }, $KEEPS_SWIPE
            move-result v$free
            if-eqz v$free, :listeners
        """,
        ExternalLabel("listeners", gesture.getInstruction(sites.listeners)),
    )
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.parameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun MethodReference.parameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.matches(other: MethodReference) =
    definingClass == other.definingClass && name == other.name && parameters() == other.parameters() && returnType == other.returnType

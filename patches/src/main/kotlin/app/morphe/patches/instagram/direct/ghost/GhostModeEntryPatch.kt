/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.ghost

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Ghost mode entry"

/** The extension's listener for the New message button, null when the build has no ghost patch. */
internal const val GHOST_LONG_PRESS =
    "$EXTENSION_PACKAGE/settings/GhostModeEntry;->longPress()Landroid/view/View\$OnLongClickListener;"

/** What Instagram logs when the New message button in the inbox's top bar is tapped. */
internal const val NEW_MESSAGE_TAPPED = "direct_new_message_button_tapped"

/** What Instagram logs when the inbox's options button is tapped, which pins the inbox header's controller. */
internal const val INBOX_OPTIONS_TAPPED = "direct_inbox_options_button_click"

private const val VIEW = "Landroid/view/View;"
private const val CLICK_LISTENER = "Landroid/view/View\$OnClickListener;"
private const val LONG_LISTENER = "Landroid/view/View\$OnLongClickListener;"

/**
 * Gives the New message button in the inbox's top bar a long press that turns Ghost mode on or off
 * from inside Instagram. It has no patch of its own: the six Ghost mode patches depend on it, so
 * it goes in once, and the extension decides at press time whether anything is there to turn and
 * whether HushGram is paused. A build whose header it can't read keeps every Ghost mode switch in
 * HushGram's settings and loses only the shortcut, with a warning in the patch log.
 */
internal val ghostModeEntryPatch = bytecodePatch {
    dependsOn(instagramExtensionPatch)

    execute {
        addGhostModeEntry()
    }
}

/**
 * Where the button's configuration is built: the method, the instruction right after the one
 * that stores the button's tap listener in it, the register holding the configuration, a local
 * register free there, and the configuration's long press field.
 */
internal class GhostEntry(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val insertAt: Int,
    val config: Int,
    val free: Int,
    val longPress: String,
)

/**
 * Puts the extension's listener in each New message button's configuration, which Instagram's action bar binds
 * to the button with setOnLongClickListener when the field isn't null. Returns whether it did.
 */
internal fun BytecodePatchContext.addGhostModeEntry(): Boolean {
    val entries = try {
        findGhostEntries()
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. Ghost mode is still in HushGram settings, and the long press on New message is left out.")
        return false
    }
    // Later instructions first, so an insert doesn't move the index of one still to come in the same method.
    for (found in entries.sortedByDescending { it.insertAt }) {
        val method = mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }
        method.addInstructions(
            found.insertAt,
            """
                invoke-static { }, $GHOST_LONG_PRESS
                move-result-object v${found.free}
                iput-object v${found.free}, v${found.config}, ${found.longPress}
            """,
        )
    }
    return true
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun Instruction.call() = reference() as? MethodReference
private fun Method.holds(string: String) = code().any { (it.reference() as? StringReference)?.string == string }

/**
 * Finds the New message button's configuration in 450's inbox header without a name that a build
 * renames. The New message tap logs [NEW_MESSAGE_TAPPED] in one method, and the one method of the
 * inbox header's controller (the class holding [INBOX_OPTIONS_TAPPED]) that calls it starts a new
 * message. The only View.OnClickListener class that calls that starts the same way is a switch over
 * its constructor's number, and the one case that reaches the call is the button's. The header
 * builder is the one place that makes that listener with that number. A different build fails the
 * search with a reason, and nothing is changed.
 */
internal fun BytecodePatchContext.findGhostEntries(): List<GhostEntry> {
    val taps = classesHolding(NEW_MESSAGE_TAPPED).flatMap { owner -> owner.methods.filter { it.holds(NEW_MESSAGE_TAPPED) }.map { owner to it } }
    val (tapOwner, tap) = taps.singleOrNull() ?: refuse("expected one method that logs $NEW_MESSAGE_TAPPED, found ${taps.size}")
    if (tap.isStatic() || tap.parameterTypes.isNotEmpty() || tap.returnType != "V") refuse("the New message tap isn't a no-argument instance method")

    val controllers = classesHolding(INBOX_OPTIONS_TAPPED)
    val controller = controllers.singleOrNull() ?: refuse("expected one inbox header controller holding $INBOX_OPTIONS_TAPPED, found ${controllers.size}")
    val starts = controller.methods.filter { method ->
        !method.isStatic() && method.parameterTypes.isEmpty() && method.returnType == "V" && method.code().any {
            val call = it.call()
            call != null && call.definingClass == tapOwner.type && call.name == tap.name && call.parameterTypes.isEmpty()
        }
    }
    val start = starts.singleOrNull() ?: refuse("expected one inbox header method that starts a new message, found ${starts.size}")

    fun Method.callsStart() = code().indices.filter {
        val call = code()[it].call()
        call != null && call.definingClass == controller.type && call.name == start.name && call.parameterTypes.isEmpty()
    }
    val listeners = classesCalling(controller.type, start.name).filter { owner ->
        CLICK_LISTENER in owner.interfaces && owner.methods.any { it.name == "onClick" && it.callsStart().isNotEmpty() }
    }
    val listener = listeners.singleOrNull() ?: refuse("expected one tap listener class that starts a new message, found ${listeners.size}")
    val onClick = listener.methods.singleOrNull { it.name == "onClick" && it.parameterTypes.map(CharSequence::toString) == listOf(VIEW) }
        ?: refuse("the tap listener has no onClick(View)")
    val keys = onClick.callsStart().flatMap { caseReaching(onClick, it) }.distinct()
    if (keys.isEmpty()) refuse("no case of the tap listener starts a new message")

    // Every place that makes one of those listeners in a button configuration. The phone's New
    // message button is one; the same call also starts a message from other buttons, whose
    // listeners are made elsewhere and are left alone.
    val makers = classesCreating(listener.type).flatMap { owner ->
        owner.methods.flatMap { method -> keys.mapNotNull { listening(method, listener.type, it) }.map { method to it } }
    }
    val entries = makers.mapNotNull { (builder, made) -> entryAt(builder, made, controller.type) }
    if (entries.isEmpty()) refuse("none of the ${makers.size} places that make the New message listener is the inbox header's button builder")
    if (entries.size > 2) refuse("${entries.size} button builders make the New message listener, expected the phone's and at most one more")
    return entries
}

/** The hook's place in the header builder [builder], whose `invoke-direct` at [made] makes the listener, or null when it isn't a button builder. */
private fun BytecodePatchContext.entryAt(builder: Method, made: Int, controller: String): GhostEntry? {
    val owner = classDefByOrNull(builder.definingClass) ?: return null
    if (!builder.isStatic() || builder.returnType != "V" || owner.fields.none { it.type == controller }) return null
    val code = builder.code()
    val listenerRegister = (code[made] as FiveRegisterInstruction).registerC
    val store = (made + 1..minOf(made + 3, code.lastIndex)).firstOrNull { at ->
        val put = code[at]
        put.opcode == Opcode.IPUT_OBJECT && (put as TwoRegisterInstruction).registerA == listenerRegister &&
            (put.reference() as? FieldReference)?.type == CLICK_LISTENER
    } ?: return null
    val tapField = code[store].reference() as FieldReference
    val config = (code[store] as TwoRegisterInstruction).registerB
    val configClass = classDefByOrNull(tapField.definingClass) ?: refuse("the button configuration's class is missing")
    val field = configClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LONG_LISTENER }
        .singleOrNull() ?: refuse("the button configuration has no single long press field")
    val longPress = "${configClass.type}->${field.name}:${field.type}"

    // A fresh configuration has no long press: nothing writes the field in the builder or in the
    // configuration's constructors, so the button has none until this hook gives it one.
    fun Method.writesLongPress() = code().any { it.opcode == Opcode.IPUT_OBJECT && it.reference().toString() == longPress }
    if (builder.writesLongPress()) refuse("the header builder already sets the button's long press")
    if (configClass.methods.any { it.name == "<init>" && it.writesLongPress() }) refuse("a new button configuration already has a long press")
    if (config > 15) refuse("the button configuration sits in a register past v15")

    val insertAt = store + 1
    if (insertAt >= code.size || insertAt in builder.jumpTargets()) refuse("the place after the listener is stored is a jump target")
    val free = builder.freeLocalsAt(PATCH, insertAt, 1, except = listOf(config)).single()
    return GhostEntry(builder.definingClass, builder.name, builder.parameterTypes.map(CharSequence::toString), insertAt, config, free, longPress)
}

/**
 * The numbers of the cases of the listener's switch whose code reaches the instruction at [call].
 * The cases end in the shared tail, so a case that reaches the call is a case that makes it.
 */
private fun caseReaching(onClick: Method, call: Int): List<Int> {
    val code = onClick.code()
    val switches = code.indices.filter { code[it].opcode == Opcode.PACKED_SWITCH || code[it].opcode == Opcode.SPARSE_SWITCH }
    val at = switches.singleOrNull() ?: refuse("the tap listener has ${switches.size} switches, not one")
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    fun indexOf(unit: Int): Int = address.indexOf(unit).also { if (it < 0 || it >= code.size) refuse("a switch arm lands between instructions") }
    val payload = code[indexOf(address[at] + (code[at] as OffsetInstruction).codeOffset)] as? SwitchPayload
        ?: refuse("the tap listener's switch has no payload")
    val flow = try { ControlFlow.of(onClick) } catch (failure: IllegalArgumentException) { refuse("the tap listener has unreadable control flow") }
    return payload.switchElements.filter { arm ->
        val seen = HashSet<Int>()
        val pending = ArrayDeque<Int>().apply { add(indexOf(address[at] + arm.offset)) }
        while (pending.isNotEmpty()) {
            val index = pending.removeFirst()
            if (index == at || !seen.add(index)) continue
            pending.addAll(flow.normal[index])
        }
        call in seen
    }.map { it.key }
}


/**
 * The index of the `invoke-direct` in [method] that makes a [listener] with [number] as the int
 * it is constructed with, set by a constant just before the call, or null when there isn't one.
 */
private fun listening(method: Method, listener: String, number: Int): Int? {
    val code = method.code()
    return code.indices.firstOrNull { at ->
        val call = code[at].call()
        if (code[at].opcode != Opcode.INVOKE_DIRECT || call == null || call.definingClass != listener || call.name != "<init>" ||
            call.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;", "I")) return@firstOrNull false
        val counter = (code[at] as FiveRegisterInstruction).registerE
        val write = (at - 1 downTo maxOf(0, at - 6)).firstOrNull { before ->
            code[before].opcode.setsRegister() && (code[before] as? OneRegisterInstruction)?.registerA == counter
        } ?: return@firstOrNull false
        val literal = code[write] as? NarrowLiteralInstruction
        literal != null && literal.narrowLiteral == number
    }
}

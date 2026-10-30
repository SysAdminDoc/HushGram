/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.messagebox

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.metaai.COMPOSER_BUTTON
import app.morphe.patches.instagram.metaai.leadingHookPair
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Message box buttons"
internal const val MESSAGE_BOX = "$EXTENSION_PACKAGE/direct/MessageBox;"
internal const val BUTTON_HOOK = "$MESSAGE_BOX->button(Ljava/lang/Object;I)Z"
internal const val CREATED_HOOK = "$MESSAGE_BOX->composerCreated(Landroid/view/View;Landroid/view/View;Landroid/view/View;)V"

private const val VIEW = "Landroid/view/View;"
private const val FIND_VIEW = "$VIEW->findViewById(I)$VIEW"

/** The names Instagram's message box button enum gives the buttons this patch can leave off. */
internal val BOX_BUTTON_NAMES = listOf("GALLERY", "STICKER", "VOICE_MESSAGE")

/**
 * Instagram 453's row_thread_composer_button_gallery, row_thread_composer_button_sticker and
 * row_thread_composer_voice ids, in the order of [BOX_BUTTON_NAMES]. The same in all four 453 builds (aapt2).
 * 453 gave 0x7f0b3859 to a row_thread_composer_button_voice nothing here looks up, so the voice id
 * isn't the one after the sticker's any more.
 */
internal val BOX_BUTTON_IDS = listOf(0x7f0b384d, 0x7f0b3857, 0x7f0b3871)

/**
 * Leaves the gallery, sticker and voice message buttons off the message box of a chat, as the user
 * picks. Included in the default selection with nothing picked, so every button stays until the user
 * turns one off. The text box and Send are never touched.
 *
 * Instagram shows or hides each message box button through one method of the composer's view holder,
 * which takes the button (an enum) and whether to show it. The patch hands that pair to the extension
 * first, and a show of a button the user unticked becomes a hide. The holder finds the three views
 * once when it's made without asking to show them, so the constructor's end hands the three to the
 * extension too. The camera, plus and GIF buttons aren't offered, since no view of theirs is found
 * the same checked way.
 */
@Suppress("unused")
val messageBoxPatch = bytecodePatch(
    name = "Message box buttons",
    description = "Lets you take the gallery, sticker and voice message buttons off the message box of a chat, and " +
        "keep the text box and Send. Starts with every button showing. Pick which to hide in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("messageBox")
        val site = findMessageBox()
        hideMessageBoxButtons(site)
        enableStatus("messageBox")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Where the hooks go: the show-or-hide method, the constructor and the three fields in between. */
internal class MessageBoxSite(
    val type: String,
    val dispatcher: String,
    val dispatcherParameters: List<String>,
    val returnAt: Int,
    val gallery: FieldReference,
    val sticker: FieldReference,
    val voice: FieldReference,
)

/**
 * Finds the composer view holder: the one class whose constructor looks up all three of
 * [BOX_BUTTON_IDS], each stored straight into a plain View field of the holder. Its show-or-hide
 * method is the one non-static (location, button enum, boolean) method, where the enum is the one
 * holding all of [BOX_BUTTON_NAMES], that asks the holder's own view getter first. Nothing changes
 * until all of it holds: one holder, one such method, one return in the constructor with nothing
 * jumping onto it, and room for the three views in registers the constructor no longer needs.
 */
internal fun BytecodePatchContext.findMessageBox(): MessageBoxSite {
    val enums = classesHolding(*BOX_BUTTON_NAMES.toTypedArray()).filter { candidate ->
        candidate.superclass == "Ljava/lang/Enum;" && candidate.methods.any { method ->
            method.name == "<clinit>" && boxCode(method).mapNotNull { boxString(it) }.containsAll(BOX_BUTTON_NAMES)
        }
    }
    val buttons = enums.singleOrNull() ?: refuse("${enums.size} message box button enums, not one")
    val loading = BOX_BUTTON_IDS.map { id -> classesLoading(id.toLong()).mapTo(HashSet()) { it.type } }
    val holders = classesLoading(BOX_BUTTON_IDS.first().toLong()).filter { candidate -> loading.all { candidate.type in it } }
        .filter { candidate -> candidate.methods.any { it.name == "<init>" && boxLookups(candidate.type, it) != null } }
    val holder = holders.singleOrNull() ?: refuse("${holders.size} message box holders, not one")
    val constructors = holder.methods.filter { it.name == "<init>" && boxLookups(holder.type, it) != null }
    val constructor = constructors.singleOrNull() ?: refuse("${constructors.size} holder constructors, not one")
    val (gallery, sticker, voice) = boxLookups(holder.type, constructor)!!

    val code = boxCode(constructor)
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    val returnAt = returns.singleOrNull() ?: refuse("${holder.type}-><init> returns in ${returns.size} places")
    if (returnAt in constructor.jumpTargets()) refuse("${holder.type}-><init> has a jump onto its return")
    val locals = constructor.localRegisterCount()
    val first = constructor.parameterTypes.firstOrNull()?.toString()
    if (locals < 2 || first == null || !first.startsWith("L") || constructor.implementation!!.registerCount > 15) {
        refuse("${holder.type}-><init> has no registers to lend the hook")
    }

    val dispatchers = holder.methods.filter { method ->
        val parameters = method.parameterTypes.map(CharSequence::toString)
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && parameters.size == 3 &&
            parameters[1] == buttons.type && parameters[2] == "Z" && boxAsksOwnGetter(holder.type, method, parameters)
    }
    val dispatcher = dispatchers.singleOrNull() ?: refuse("${dispatchers.size} show-or-hide methods, not one")
    return MessageBoxSite(
        holder.type, dispatcher.name, dispatcher.parameterTypes.map(CharSequence::toString), returnAt, gallery, sticker, voice,
    )
}

/**
 * Hands the pair to [BUTTON_HOOK] first in the show-or-hide method, right after Hide Meta AI's own
 * hook when that patch already put it first (Meta AI's stays first whichever runs first), and the
 * three views to [CREATED_HOOK] right before the constructor returns. The constructor's last
 * register is its first parameter, an Activity nothing reads after the return.
 */
internal fun BytecodePatchContext.hideMessageBoxButtons(site: MessageBoxSite) {
    val holder = mutableClassDefBy(site.type)
    val dispatcher = holder.methods.single {
        it.name == site.dispatcher && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == site.dispatcherParameters
    }
    val flag = dispatcher.implementation!!.registerCount - 1
    val first = leadingHookPair(boxCode(dispatcher), COMPOSER_BUTTON, flag)
    val constructor = holder.methods.single { it.name == "<init>" && boxLookups(site.type, it) != null }
    constructor.addInstructions(
        site.returnAt,
        """
            iget-object v0, p0, ${site.gallery.definingClass}->${site.gallery.name}:${site.gallery.type}
            iget-object v1, p0, ${site.sticker.definingClass}->${site.sticker.name}:${site.sticker.type}
            iget-object p1, p0, ${site.voice.definingClass}->${site.voice.name}:${site.voice.type}
            invoke-static { v0, v1, p1 }, $CREATED_HOOK
        """,
    )
    dispatcher.addInstructions(
        first,
        """
            invoke-static/range { p2 .. p3 }, $BUTTON_HOOK
            move-result p3
        """,
    )
}

/**
 * The three fields [BOX_BUTTON_IDS] are stored in by [constructor], in the ids' order, or null when the
 * constructor isn't that. Each id is loaded once, handed to the view lookup, and the result stored
 * straight into a View field of [owner] on the holder itself.
 */
private fun boxLookups(owner: String, constructor: Method): Triple<FieldReference, FieldReference, FieldReference>? {
    val code = boxCode(constructor)
    val self = (constructor.implementation?.registerCount ?: return null) -
        constructor.parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } - 1
    val fields = BOX_BUTTON_IDS.map { id ->
        val loads = code.indices.filter { (code[it] as? NarrowLiteralInstruction)?.narrowLiteral == id }
        val load = loads.singleOrNull() ?: return null
        val call = (code.getOrNull(load + 1) as? ReferenceInstruction)?.reference as? MethodReference
        val result = code.getOrNull(load + 2)
        val store = code.getOrNull(load + 3)
        val field = (store as? ReferenceInstruction)?.reference as? FieldReference
        if (call?.toString() != FIND_VIEW || code[load + 1].opcode != Opcode.INVOKE_VIRTUAL ||
            result?.opcode != Opcode.MOVE_RESULT_OBJECT || store?.opcode != Opcode.IPUT_OBJECT || field == null ||
            (store as TwoRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA ||
            store.registerB != self || field.definingClass != owner || field.type != VIEW) return null
        field
    }
    return if (fields.toSet().size == 3) Triple(fields[0], fields[1], fields[2]) else null
}

/**
 * Whether [method] is the show-or-hide method: within its first instructions (after any hook another
 * patch put first) it calls the holder's own `(location, button, boolean)` View getter, and that
 * getter asks a `getView()`.
 */
private fun BytecodePatchContext.boxAsksOwnGetter(owner: String, method: Method, parameters: List<String>): Boolean {
    val call = boxCode(method).take(6).mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .firstOrNull { it.definingClass == owner && it.returnType == VIEW && it.parameterTypes.map(CharSequence::toString) == parameters }
        ?: return false
    val getter = classDefBy(owner).methods.singleOrNull {
        it.name == call.name && it.returnType == VIEW && it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: return false
    return boxCode(getter).any {
        (it as? ReferenceInstruction)?.reference.let { ref -> ref is MethodReference && ref.name == "getView" && ref.parameterTypes.isEmpty() }
    }
}

private fun boxCode(method: Method): List<Instruction> = method.implementation?.instructions?.toList().orEmpty()
private fun boxString(instruction: Instruction): String? = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string

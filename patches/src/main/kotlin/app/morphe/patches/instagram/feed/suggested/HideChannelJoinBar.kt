/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide suggested posts"

internal const val CHANNEL_JOIN_BAR = "$EXTENSION_PACKAGE/feed/ChannelJoinBar;"
internal const val CHANNEL_JOIN_BAR_DETAILS =
    "$CHANNEL_JOIN_BAR->details(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

/** The extension's stub the patch fills to read the bar's row from its view holder. */
internal const val CHANNEL_JOIN_BAR_ROW_STUB = "row"

private const val OBJECT = "Ljava/lang/Object;"
private const val VIEW = "Landroid/view/View;"
private val INFLATE = listOf("Landroid/content/Context;", "Landroid/view/ViewGroup;")

/**
 * Hide the Join bar on channel posts (#625), found: the bar's binder, a static method taking the
 * bar's view holder, the post and the channel's details. With no details it sets the bar gone and
 * returns, which is how Instagram leaves the bar off a post that isn't from a channel. [row] is the
 * holder's field that path sets gone, the bar's own row in the feed's list.
 *
 * [write] runs the binder's parameters through ChannelJoinBar.details first thing, so with the switch
 * on the binder takes that path, and fills ChannelJoinBar.row to read [row], so the extension can fold
 * the row the list would otherwise keep at its height. The post above the bar keeps its place.
 */
internal class HideChannelJoinBar(
    val owner: String,
    val binder: String,
    val parameters: List<String>,
    val row: String,
) {
    fun write(context: BytecodePatchContext) = with(context) {
        val bind = mutableClassDefBy(owner).methods.single {
            it.name == binder && it.parameterTypes.map(CharSequence::toString) == parameters
        }
        // The holder, the post and the details are p0 to p2, side by side, so a range call reaches them past v15 too.
        val details = "p${parameters.size - 1}"
        bind.addInstructions(
            0,
            """
                invoke-static/range { p0 .. $details }, $CHANNEL_JOIN_BAR_DETAILS
                move-result-object $details
                check-cast $details, ${parameters.last()}
            """,
        )
        staticStub(CHANNEL_JOIN_BAR, CHANNEL_JOIN_BAR_ROW_STUB, VIEW).addInstructions(
            0,
            """
                check-cast p0, ${parameters.first()}
                iget-object p0, p0, $row
                return-object p0
            """,
        )
    }
}

/**
 * Finds the Join bar's binder for Hide the Join bar on channel posts. The bar's class is a final
 * class with an inflater (Context, ViewGroup)View that makes the view holder and tags the root with
 * it, and a static binder whose first parameter is that holder. The binder sets a touch listener and
 * an attach listener on the bar, and its first branch tests the details for null, falling through to
 * hide the holder's view and return. On 454's 386014758 that's LX/0fkf's A01 and A00, the holder
 * LX/0kcF, its row LX/0kcF->A02 and the details LX/0YGI. The holder's class and the row's field have
 * to be public, since the extension reads the row. The inflater's view lookups aren't counted: the
 * armeabi-v7a build 386014704 moves one requireViewById into a shared helper. Anything else is an
 * update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findHideChannelJoinBar(): HideChannelJoinBar {
    val found = mutableListOf<Triple<ClassDef, Method, FieldReference>>()
    classesCalling(VIEW, "addOnAttachStateChangeListener").forEach { classDef ->
        if (classDef.superclass != OBJECT || !AccessFlags.FINAL.isSet(classDef.accessFlags)) return@forEach
        val holders = classDef.methods.filter { it.isInflater() }.flatMap { it.created() }.toSet()
        if (holders.isEmpty()) return@forEach
        classDef.methods.forEach { method -> method.binderRow(holders)?.let { found += Triple(classDef, method, it) } }
    }
    val (owner, bind, row) = found.singleOrNull()
        ?: refuse("expected one binder that hides the bar without details, found ${found.size}")
    if (0 in bind.jumpTargets()) refuse("${owner.type}->${bind.name} jumps back to its first instruction")
    val holder = classDefByOrNull(row.definingClass) ?: refuse("${row.definingClass} isn't in this build")
    val declared = holder.fields.singleOrNull { it.name == row.name && it.type == row.type }
    if (!AccessFlags.PUBLIC.isSet(holder.accessFlags) || declared == null ||
        !AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)
    ) {
        refuse("${row.definingClass}->${row.name} isn't a public instance field of a public class, so the extension can't read it")
    }
    staticStub(CHANNEL_JOIN_BAR, CHANNEL_JOIN_BAR_ROW_STUB, VIEW)
    return HideChannelJoinBar(
        owner.type,
        bind.name,
        bind.parameterTypes.map(CharSequence::toString),
        "${row.definingClass}->${row.name}:${row.type}",
    )
}

/** [findHideChannelJoinBar], or null after the patch log says why, and the switch does nothing. */
internal fun BytecodePatchContext.hideChannelJoinBarOrWarn(): HideChannelJoinBar? = try {
    findHideChannelJoinBar()
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. Hide the Join bar on channel posts does nothing in this build.")
    null
}

private fun Method.isInflater(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != VIEW) return false
    if (parameterTypes.map(CharSequence::toString) != INFLATE) return false
    return code().count { it.calls("setTag") } == 1
}

private fun Method.created(): List<String> = code().filter { it.opcode == Opcode.NEW_INSTANCE }
    .map { ((it as ReferenceInstruction).reference as TypeReference).type }

/** The row [this] sets gone without details when it's the bar's binder, or null. */
private fun Method.binderRow(holders: Set<String>): FieldReference? {
    val parameters = parameterTypes.map(CharSequence::toString)
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" || parameters.size != 3) return null
    if (parameters.first() !in holders || parameters.any { !it.startsWith("L") }) return null
    val code = code()
    if (code.none { it.calls("setOnTouchListener") } || code.none { it.calls("addOnAttachStateChangeListener") }) return null
    return hiddenWithoutDetails(parameters.first())
}

/**
 * The first branch is an if-nez on the details, the last parameter, and its fall through reads a
 * view of the holder, hands it to a static (View, int) call and returns. Answers that view's field.
 */
private fun Method.hiddenWithoutDetails(holder: String): FieldReference? {
    val details = (implementation ?: return null).registerCount - 1
    val code = code()
    val branch = code.indexOfFirst { it is OffsetInstruction }
    if (branch < 0 || code[branch].opcode != Opcode.IF_NEZ) return null
    if ((code[branch] as OneRegisterInstruction).registerA != details) return null
    val end = (branch + 1 until code.size).firstOrNull { code[it].opcode == Opcode.RETURN_VOID } ?: return null
    val path = code.subList(branch + 1, end)
    if (path.isEmpty() || path.size > 4 || path.first().opcode != Opcode.IGET_OBJECT) return null
    val read = (path.first() as ReferenceInstruction).reference as FieldReference
    if (read.definingClass != holder || read.type != VIEW) return null
    val hides = path.count { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            call != null && call.returnType == "V" && call.parameterTypes.map(CharSequence::toString) == listOf(VIEW, "I")
    } == 1
    return read.takeIf { hides }
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.calls(name: String) =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.name == name

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the Join bar on channel posts: $detail")

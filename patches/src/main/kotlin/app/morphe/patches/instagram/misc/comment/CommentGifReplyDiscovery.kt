/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val GIF_REPLY_NATIVE = "$EXTENSION_PACKAGE/comment/CommentGifReplyNative;"
internal const val GIF_REPLY_ROW = "$EXTENSION_PACKAGE/comment/GifReplyRow;"
internal const val COMMENT_GIF_REPLY = "$EXTENSION_PACKAGE/comment/CommentGifReply;"
internal const val GIF_REPLY_STATUS = "commentGifReply"
internal const val GIF_REPLY_SELECTING = "$COMMENT_GIF_REPLY->selecting($OBJECT$OBJECT$STRING$STRING)V"
/** Compose's source label on the quick reply bar, whose GIF button carries Instagram's own GIF label and icon. */
internal const val REPLY_BAR ="com.instagram.quicksnap.viewer.compose.QuickSnapReplyBar ("
private const val ACTIVITY = "Landroid/app/Activity;"

/**
 * Reply with a comment's GIF (#402), beside Save comment photo. The hook in the comment screen's
 * selection hands the extension the screen, the comment it resolved and the two IDs it resolved it
 * from ([registers], copied into [spares] at [hookAt]). [reply] is the screen's own Reply on those
 * IDs, [target] reads the reply target Reply sets through the screen's [composer], and [send] is the
 * screen's GIF send, handed the comment's GIF after [toHelper] and [toInfo] convert it. All of it
 * holds plain references, never the app's classes or methods.
 */
internal data class GifReplyPlan(
    val select: MethodReference, val hookAt: Int, val registers: List<Int>, val spares: List<Int>,
    val reply: MethodReference, val composer: FieldReference, val target: MethodReference,
    val toHelper: MethodReference, val toInfo: MethodReference, val helperInterface: Boolean, val send: MethodReference,
    val icon: Int, val label: Int,
)

/** The extension's bridges for the GIF reply, by name, with their parameters and return type. */
private val GIF_REPLY_BRIDGES: List<Pair<String, Pair<List<String>, String>>> = listOf(
    "reply" to (listOf(OBJECT, STRING, STRING) to "V"),
    "target" to (listOf(OBJECT) to OBJECT),
    "info" to (listOf(OBJECT) to OBJECT),
    "send" to (listOf(OBJECT, OBJECT, OBJECT) to "V"),
)

/** The GIF reply, or null after the patch log says why, so Save comment photo still goes in. */
internal fun BytecodePatchContext.gifReplyOrNull(surface: CommentSurface, classes: Map<String, ClassDef>): GifReplyPlan? = try {
    findGifReply(surface, classes)
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. Save comment photo goes in without Reply with a comment's GIF.")
    null
}

private fun BytecodePatchContext.findGifReply(surface: CommentSurface, classes: Map<String, ClassDef>): GifReplyPlan {
    for (type in listOf(GIF_REPLY_NATIVE, GIF_REPLY_ROW, COMMENT_GIF_REPLY)) {
        if (type !in classes) refuse("missing extension boundary $type")
    }
    fun clazz(type: String) = classes[type] ?: refuse("missing native class $type")
    val select = commentSelect()
    val screen = clazz(select.definingClass)
    requirePublic(screen)

    // The selection resolves its comment from the two IDs it was handed, and the hook keeps all three.
    val code = select.code()
    val resolverAt = code.indices.filter { at -> code[at].call()?.let {
        it.returnType == surface.selectedType && it.parameters().size == 3 && it.parameters().takeLast(2) == listOf(STRING, STRING)
    } == true }.one("selection's comment resolver call")
    val resolver = code[resolverAt].call()!!
    val self = select.implementation!!.registerCount - 5
    val ids = code[resolverAt].arguments().takeLast(2)
    if (!select.carries(resolverAt, ids[0], self + 1) || !select.carries(resolverAt, ids[1], self + 2)) {
        refuse("selection resolves its comment from something other than the IDs it was handed")
    }
    val resolved = (code.getOrNull(resolverAt + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)
        ?.registerA ?: refuse("selection drops the comment it resolves")
    val hookAt = resolverAt + 2
    if (hookAt >= code.size) refuse("selection ends where the hook goes")
    if (code.take(hookAt).any { it.writes(self) || it.writes(self + 1) || it.writes(self + 2) }) {
        refuse("selection reuses its parameters before it resolves the comment")
    }
    if (hookAt in select.jumpTargets()) refuse("a branch enters the selection right after it resolves the comment")
    val spares = try {
        select.getFreeRegisterProvider(hookAt, 4, resolved).let { provider -> List(4) { provider.getFreeRegister4Bit() } }
    } catch (failure: IllegalStateException) {
        refuse("selection has no free registers for the hook: ${failure.message}")
    } catch (failure: IllegalArgumentException) {
        refuse("selection has no free registers for the hook: ${failure.message}")
    }

    // Reply resolves the same comment from the same two IDs, then hands it to the composer as its target.
    fun setsTarget(call: MethodReference) = call.returnType == "V" && call.parameters().let { types ->
        types.size == 9 && types[0] == USER && types.subList(2, 5).all { it == STRING } && types.takeLast(4).all { it == "Z" }
    }
    val reply = screen.methods.filter { method -> method.publicInstance() && method.returnType == "V" &&
        method.parameters().let { it.size == 3 && it[0].startsWith("L") && it[0] != STRING && it[1] == STRING && it[2] == STRING } &&
        method.code().any { it.call()?.toString() == resolver.toString() } && method.code().any { it.call()?.let(::setsTarget) == true }
    }.one("comment screen's Reply")
    val replyCode = reply.code()
    val replySelf = reply.implementation!!.registerCount - 4
    val replyResolves = replyCode.indices.filter { replyCode[it].call()?.toString() == resolver.toString() }.one("Reply's comment resolver call")
    val replyIds = replyCode[replyResolves].arguments().takeLast(2)
    if (!reply.carries(replyResolves, replyIds[0], replySelf + 2) || !reply.carries(replyResolves, replyIds[1], replySelf + 3)) {
        refuse("Reply resolves its comment from something other than the IDs it was handed")
    }
    val setTarget = replyCode.mapNotNull { it.call() }.filter(::setsTarget).distinctBy { it.toString() }.one("Reply's composer target call")
    val composerType = clazz(setTarget.definingClass)
    requirePublic(composerType)
    val composer = screen.fields.filter { it.type == composerType.type && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .one("comment screen's composer")
    requirePublicField(screen, composer)
    val set = composerType.methods.filter { it.matches(setTarget) }.one("composer's target setter")

    // The GIF send takes the Activity and the comment GIF's model, converted from the interface a comment's GIF is read as.
    val giphy = clazz(GIPHY)
    val conversions = giphy.methods.filter { it.parameterTypes.isEmpty() && AccessFlags.ABSTRACT.isSet(it.accessFlags) }
        .flatMap { toHelper -> (classes[toHelper.returnType]?.methods ?: emptyList())
            .filter { it.publicInstance() && it.parameterTypes.isEmpty() }.map { toHelper to it } }
    val (send, conversion) = screen.methods.filter { method -> method.publicInstance() && method.returnType == "V" &&
        method.parameters().size == 2 && method.parameters()[0] == ACTIVITY
    }.flatMap { send -> conversions.filter { it.second.returnType == send.parameters()[1] }.map { send to it } }
        .one("comment screen's GIF send")
    val (toHelper, toInfo) = conversion
    val helper = clazz(toHelper.returnType)
    requirePublic(helper)
    requirePublic(clazz(send.parameters()[1]))

    // The send answers the composer's target, and Reply makes a new one each time it takes a comment,
    // so the extension can tell Reply took this one by the target changing.
    val target = send.code().mapNotNull { it.call() }.filter { it.definingClass == composerType.type &&
        it.parameterTypes.isEmpty() && it.returnType.startsWith("L") }.distinctBy { it.toString() }.one("GIF send's reply target read")
    composerType.methods.filter { it.matches(target) && it.publicInstance() }.one("composer's public reply target getter")
    if (set.code().none { it.opcode == Opcode.NEW_INSTANCE && (it.reference() as? TypeReference)?.type == target.returnType }) {
        refuse("Reply no longer makes a new reply target")
    }

    val (icon, label) = replyBarGif(classes)
    stub(COMMENT_GIF_REPLY, "selecting", listOf(OBJECT, OBJECT, STRING, STRING), "V")
    GIF_REPLY_BRIDGES.forEach { (name, shape) -> stub(GIF_REPLY_NATIVE, name, shape.first, shape.second) }
    validateActionRow(GIF_REPLY_ROW, GIF_REPLY_NATIVE)
    return GifReplyPlan(ImmutableMethodReference.of(select), hookAt, listOf(self, resolved, self + 1, self + 2), spares,
        ImmutableMethodReference.of(reply), ImmutableFieldReference.of(composer), ImmutableMethodReference.of(target),
        ImmutableMethodReference.of(toHelper), ImmutableMethodReference.of(toInfo),
        AccessFlags.INTERFACE.isSet(helper.accessFlags), ImmutableMethodReference.of(send), icon, label)
}

/** Only called after [gifReplyOrNull] found every step and checked every bridge. */
internal fun BytecodePatchContext.applyGifReply(surface: CommentSurface, plan: GifReplyPlan) {
    val bridges = GIF_REPLY_BRIDGES.associate { (name, shape) -> name to stub(GIF_REPLY_NATIVE, name, shape.first, shape.second) }
    val screen = plan.select.definingClass
    // Reply takes no row model of its own here; it resolves the comment from its IDs, as a tap on Reply does.
    replace(bridges.getValue("reply"), 4, """
        const/4 v0, 0x0
        check-cast p0, $screen
        invoke-virtual { p0, v0, p1, p2 }, ${plan.reply}
        return-void
    """)
    replace(bridges.getValue("target"), 1, """
        check-cast p0, $screen
        iget-object p0, p0, ${plan.composer}
        if-eqz p0, :none
        invoke-virtual { p0 }, ${plan.target}
        move-result-object p0
        :none
        return-object p0
    """)
    replace(bridges.getValue("info"), 2, """
        instance-of v0, p0, $GIPHY
        if-eqz v0, :none
        check-cast p0, $GIPHY
        invoke-interface { p0 }, ${plan.toHelper}
        move-result-object p0
        if-eqz p0, :none
        ${if (plan.helperInterface) "invoke-interface" else "invoke-virtual"} { p0 }, ${plan.toInfo}
        move-result-object p0
        return-object p0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
    replace(bridges.getValue("send"), 3, """
        check-cast p0, $screen
        check-cast p1, $ACTIVITY
        check-cast p2, ${plan.send.parameters()[1]}
        invoke-virtual { p0, p1, p2 }, ${plan.send}
        return-void
    """)
    val (screenCopy, comment, id, parent) = plan.spares
    val (self, resolved, idParameter, parentParameter) = plan.registers
    mutableClassDefBy(screen).methods.single { it.matches(plan.select) }.addInstructions(plan.hookAt, """
        move-object/from16 v$screenCopy, v$self
        move-object/from16 v$comment, v$resolved
        move-object/from16 v$id, v$idParameter
        move-object/from16 v$parent, v$parentParameter
        invoke-static { v$screenCopy, v$comment, v$id, v$parent }, $GIF_REPLY_SELECTING
    """)
    applyActionRow(surface, GIF_REPLY_ROW, GIF_REPLY_NATIVE, plan.icon, plan.label)
}

/**
 * Whether [register] holds parameter register [parameter] at [at]: it is that register, or it was
 * last set to a copy of it, on a straight path or as the only time the method sets it.
 */
private fun Method.carries(at: Int, register: Int, parameter: Int): Boolean {
    val code = code()
    if (code.take(at).any { it.writes(parameter) }) return false
    if (register == parameter) return true
    val writes = code.indices.filter { code[it].writes(register) }
    val write = writes.lastOrNull { it < at } ?: return false
    val copy = code[write]
    if (copy.opcode !in copies || (copy as TwoRegisterInstruction).registerB != parameter) return false
    return writes.size == 1 || jumpTargets().none { it in write + 1..at }
}

private val copies = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val literals = setOf(Opcode.CONST, Opcode.CONST_16, Opcode.CONST_HIGH16, Opcode.CONST_4)

/**
 * Instagram's own GIF label and icon, from the quick reply bar's GIF button: a string read of the
 * label's constant, then at once the icon's constant, handed to the icon builder as its resource.
 */
private fun BytecodePatchContext.replyBarGif(classes: Map<String, ClassDef>): Pair<Int, Int> {
    val bar = classDefByStrings(REPLY_BAR, StringComparisonType.STARTS_WITH).map { it.type }.distinct()
        .mapNotNull { classes[it] }.one("quick reply bar")
    return bar.methods.flatMap { method -> method.code().let { code -> code.indices.mapNotNull { gifButton(method, code, it) } } }
        .distinct().one("quick reply bar's GIF button")
}

private fun gifButton(method: Method, code: List<Instruction>, at: Int): Pair<Int, Int>? {
    code[at].call()?.takeIf { code[at].opcode == Opcode.INVOKE_STATIC && it.returnType == STRING &&
        it.parameters().size == 2 && it.parameters()[1] == "I" } ?: return null
    if (code.getOrNull(at + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val label = method.constantBefore(at, code[at].arguments()[1]) ?: return null
    val load = code.getOrNull(at + 2)?.takeIf { it.opcode in literals } ?: return null
    val iconRegister = (load as OneRegisterInstruction).registerA
    val buildAt = (at + 3 until code.size).firstOrNull { code[it].call() != null } ?: return null
    val build = code[buildAt].call()!!
    if (code[buildAt].opcode != Opcode.INVOKE_STATIC || build.parameters().drop(1) != listOf("I", "I", "I", "Z") ||
        code[buildAt].arguments().getOrNull(1) != iconRegister || code.subList(at + 3, buildAt).any { it.writes(iconRegister) }) return null
    val icon = (load as NarrowLiteralInstruction).narrowLiteral
    return (icon to label).takeIf { icon ushr 24 == 0x7f && label ushr 24 == 0x7f && icon != label }
}

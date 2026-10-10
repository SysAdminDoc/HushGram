/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.reel.code
import app.morphe.patches.instagram.download.reel.newOption
import app.morphe.patches.instagram.download.reel.optionIcon
import app.morphe.patches.instagram.download.video.FeedMenu
import app.morphe.patches.instagram.download.video.MenuHandler
import app.morphe.patches.instagram.download.video.downloadRow
import app.morphe.patches.instagram.download.video.findFeedMenu
import app.morphe.patches.instagram.download.video.findMenuHandler
import app.morphe.patches.instagram.download.video.mutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val ROW_PATCH = "Hide suggested posts, Hide posts from this account"

internal const val HIDE_ROW = "$EXTENSION_PACKAGE/feed/HideAccountRow;"
internal const val OFFER_HIDE = "$HIDE_ROW->offer(Ljava/lang/Object;Ljava/util/ArrayList;)V"
internal const val ALLOW_HIDE = "$HIDE_ROW->allow(Ljava/util/List;)Ljava/util/List;"
internal const val HIDE_OPTION = "$HIDE_ROW->option()Ljava/lang/Object;"
internal const val HIDE_TAPPED = "$HIDE_ROW->hide(Ljava/lang/Object;Landroid/app/Activity;)V"

private const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * Hide posts from this account, a row in the menu of someone else's post, found: the same feed menu
 * Download any video adds its rows to (its builder, handler and short menu list, found by the same
 * finders, so a build one can use the other can) and the row's own stubs in the extension's
 * HideAccountRow. It shares nothing the Download patch writes, so the row shows with Download any
 * video left out, and both patches in one build add their own hooks side by side.
 *
 * [write] puts one call where anyone else's rows start, one before each return of the short menu's
 * list of kept options, and one in front of the handler of a tapped option. Rows are added through
 * the builder's own adder, as Download's Details row is, with an option made by the option's own
 * constructor with Download's ordinal and icon.
 */
internal class HideAccountRow(
    private val context: BytecodePatchContext,
    private val menu: FeedMenu,
    private val tap: MenuHandler,
    private val kept: Method,
    private val icon: String,
    private val row: MutableClass,
    private val postOf: MutableMethod,
    private val adder: MutableMethod,
    private val optionOf: MutableMethod,
) {
    fun write() = with(context) {
        val others = menu.others
        // The short list's last return first, so the ones before it stay where they were.
        val short = mutable(kept)
        val code = short.code()
        for (index in code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }.reversed()) {
            val list = (code[index] as OneRegisterInstruction).registerA
            short.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static { v$list }, $ALLOW_HIDE
                    move-result-object v$list
                """,
            )
        }
        mutable(menu.builder).addInstructionsAtControlFlowLabel(
            others.at,
            "invoke-static { v${others.state}, v${others.rows} }, $OFFER_HIDE",
        )
        val type = menu.helper.type
        val handler = mutable(tap.handler)
        handler.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                invoke-static {}, $HIDE_OPTION
                move-result-object v1
                if-eqz v1, :handle
                if-ne v0, v1, :handle
                move-object/from16 v0, p0
                invoke-static { v0 }, $type->${tap.media.name}($type)$MEDIA
                move-result-object v1
                iget-object v2, v0, $type->${tap.activity.name}:$FRAGMENT_ACTIVITY
                invoke-static { v1, v2 }, $HIDE_TAPPED
                return-void
            """,
            ExternalLabel("handle", handler.getInstruction(0)),
        )
        postOf.addInstructions(
            0,
            """
                check-cast p0, ${others.stateType}
                iget-object p0, p0, ${others.media}
                return-object p0
            """,
        )
        row.methods.remove(adder)
        row.methods.add(downloadRow(adder, others, all = true))
        row.methods.remove(optionOf)
        row.methods.add(
            ImmutableMethod(
                optionOf.definingClass, optionOf.name, optionOf.parameters, optionOf.returnType,
                optionOf.accessFlags, optionOf.annotations, optionOf.hiddenApiRestrictions,
                ImmutableMethodImplementation(5, emptyList(), null, null),
            ).toMutable().apply { addInstructions(0, newOption(icon, "move-object v1, p0")) },
        )
    }
}

/**
 * Finds the row's hooks, or answers null after the patch log says why, and Hide suggested posts goes
 * in without the row. Nothing is changed here.
 */
internal fun BytecodePatchContext.hideAccountRowOrWarn(): HideAccountRow? = try {
    val menu = try {
        findFeedMenu()
    } catch (moved: PatchException) {
        throw PatchException(moved.message.orEmpty().replace("Download any video:", ROW_PATCH + ":"))
    }
    val tap = try {
        findMenuHandler(menu.helper)
    } catch (moved: PatchException) {
        throw PatchException(moved.message.orEmpty().replace("Download any video:", ROW_PATCH + ":"))
    }
    val shortList = menu.shortLists.singleOrNull() ?: throw PatchException(
        "$ROW_PATCH: expected one list of the options the short feed menu keeps, found " +
            if (menu.shortLists.isEmpty()) "none" else menu.shortLists.joinToString { "${it.definingClass}->${it.name}" },
    )
    val returns = shortList.code().filter { it.opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) throw PatchException("$ROW_PATCH: ${shortList.definingClass}->${shortList.name} never returns its list")
    returns.map { (it as OneRegisterInstruction).registerA }.firstOrNull { it > 15 }?.let {
        throw PatchException("$ROW_PATCH: ${shortList.definingClass}->${shortList.name} returns its list in v$it, out of an invoke's reach")
    }
    mutable(tap.handler).requireLocals(ROW_PATCH, 3)
    val icon = optionIcon(ROW_PATCH)
    val row = mutableClassDefBy(HIDE_ROW)
    fun stub(name: String, returns: String, vararg parameters: String) = row.methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterTypes.map(Any::toString) == parameters.toList()
    } ?: throw PatchException("$ROW_PATCH: $HIDE_ROW has no static $returns $name(${parameters.joinToString()})")
    HideAccountRow(
        this, menu, tap, shortList, icon, row,
        stub("menuMedia", OBJECT, OBJECT),
        stub("addRow", "V", OBJECT, ARRAY_LIST, OBJECT, CHAR_SEQUENCE),
        stub("newOption", OBJECT, "Ljava/lang/String;"),
    )
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. Hide suggested posts goes in without the Hide posts from this account row.")
    null
}

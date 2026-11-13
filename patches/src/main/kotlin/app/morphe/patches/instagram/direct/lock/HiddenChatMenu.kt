/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.direct.preview.DEBUG_LABEL
import app.morphe.patches.instagram.direct.preview.PREVIEW_OFFER
import app.morphe.patches.instagram.direct.preview.chatMenuBuilder
import app.morphe.patches.instagram.direct.seen.ChatMenuActionsFingerprint
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.direct.seen.visualString
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Hide chat and Unhide chat in a chat's long press menu (#661). */
internal const val HIDDEN_CHATS_MENU = "$EXTENSION_PACKAGE/direct/HiddenChatsMenu;"
internal const val MENU_OFFER = "$HIDDEN_CHATS_MENU->offer(Ljava/util/List;Ljava/lang/Object;)V"
internal const val MENU_SHOWN = "$HIDDEN_CHATS_MENU->shown(Ljava/lang/Object;Ljava/lang/String;)V"
internal const val MENU_LABEL = "$HIDDEN_CHATS_MENU->label(Ljava/util/List;[Ljava/lang/String;)V"
internal const val MENU_TAP = "$HIDDEN_CHATS_MENU->tap(Ljava/lang/Object;Ljava/lang/Object;)Z"

/**
 * Instagram's Details row of the chat menu. The release app builds it in the menu enum without
 * keeping it in a field, so nothing in the app can name it, offer it or act on it.
 */
internal const val DETAILS_ROW = "DETAILS"

private const val MENU_THREAD_ID = "threadId"
private const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LABELS = "[Ljava/lang/String;"

private fun refuseMenu(why: String): Nothing = throw PatchException("$LOCK_PATCH: $why")
private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

internal class HiddenChatMenuTargets(
    val menu: String,
    /** The builder of a chat's menu, its list of rows, where the offer goes in and the locals it borrows there. */
    val builder: MutableMethod,
    val builderRows: Int,
    val builderAt: Int,
    val builderLocals: List<Int>,
    /** The method that shows a chat's menu, with the chat's key, the name over it and the locals the hook borrows. */
    val show: MutableMethod,
    val showKey: Int,
    val showTitle: Int,
    val showLocals: List<Int>,
    /** The menu's label builder, its rows, the return the label hook goes before and the labels it returns. */
    val labels: MutableMethod,
    val labelRows: Int,
    val labelAt: Int,
    val labelArray: Int,
    /** The menu's tap handler, the row it's handed, the chat key and the locals the hook borrows. */
    val action: MutableMethod,
    val chosen: Int,
    val key: Int,
    val actionLocals: List<Int>,
    /** The extension's reader of a chat key's id and the key's id field it reads. */
    val threadId: MutableMethod,
    val idField: FieldReference,
)

/**
 * [findHiddenChatMenu], or null with a warning in the patch log when this build's menu has moved:
 * the menu row is the one part of the lock that can be left out, and the rest goes on without it.
 */
internal fun BytecodePatchContext.findHiddenChatMenuOrSkip(): HiddenChatMenuTargets? = try {
    findHiddenChatMenu()
} catch (e: PatchException) {
    patchLog.warning("${e.message}. Hide chat stays out of the chat menu, and the rest of the lock goes on.")
    null
}

/** Everything the Hide chat row needs, all proved before any edit. */
internal fun BytecodePatchContext.findHiddenChatMenu(): HiddenChatMenuTargets {
    val menuInit = uniqueMethod(LOCK_PATCH, "chat menu action enum", ChatMenuActionsFingerprint)
    val menu = menuInit.definingClass
    requireUnofferedRow(menuInit, menu)

    val methods = mutableListOf<Method>()
    classDefForEach { owner -> owner.methods.filterTo(methods) { it.implementation != null } }

    // The builder, where the row goes in: Instagram's own list of a chat's rows, the one Preview
    // chats offers its row in. The menu that shows them gets a read-only copy. Preview's offer stays
    // the builder's first call, so when Preview chats went first the row goes in straight after it.
    val builder = mutable(chatMenuBuilder(LOCK_PATCH, menuInit, methods))
    val builderRows = builder.parameters().indexOf(LIST)
    val builderAt = builder.visualCode().indexOfFirst { it.visualReference()?.toString() == PREVIEW_OFFER } + 1
    if (builderAt in builder.jumpTargets()) refuseMenu("a jump reaches the start of the chat menu builder")
    val builderLocals = builder.freeLocalsAt(LOCK_PATCH, builderAt, 2)

    // The labels: the static method handing back the menu's labels for its rows, the one that
    // labels the debug row.
    val labelsRef = classesLoadingString(DEBUG_LABEL).flatMap { owner ->
        owner.methods.filter { method ->
            method.isStatic() && method.returnType == LABELS && method.parameters().count { it == LIST } == 1 &&
                loadsString(method, DEBUG_LABEL)
        }
    }.one("chat menu label builder")
    val labels = mutable(labelsRef)
    val labelRows = labels.parameters().indexOf(LIST)
    val labelCode = labels.visualCode()
    val labelAt = labelCode.indices.filter { labelCode[it].opcode == Opcode.RETURN_OBJECT }.one("return of the chat menu labels")
    val labelArray = (labelCode[labelAt] as OneRegisterInstruction).registerA
    labels.requireParameterIntact(LOCK_PATCH, labelRows, listOf(labelAt))
    if (labelArray > 15 || labels.parameterRegisterNumber(labelRows) > 15) {
        refuseMenu("the chat menu label builder keeps its rows or labels past v15")
    }

    // The menu itself: the static method taking the chat's key, its rows and the name over the menu,
    // which labels its rows through the label builder. The name is its first String.
    val labelsCall = labelsRef.signature()
    val showRef = methods.filter { method ->
        val parameters = method.parameters()
        method.isStatic() && method.returnType == "V" && parameters.count { it == THREAD_KEY } == 1 &&
            parameters.count { it == LIST } == 1 && STRING in parameters &&
            method.visualCode().any { (it.visualReference() as? MethodReference)?.signature() == labelsCall }
    }.distinctBy { it.signature() }.one("chat menu that labels its rows")
    val show = mutable(showRef)
    val showParameters = show.parameters()
    if (0 in show.jumpTargets()) refuseMenu("a jump reaches the start of the chat menu")
    val showLocals = show.freeLocalsAt(LOCK_PATCH, 0, 2)

    // The tap handler: the one instance method handed one row of the menu enum and the chat's key.
    val actionRef = methods.filter { method ->
        !method.isStatic() && method.name != "<init>" && method.returnType == "V" && method.parameters().count { it == menu } == 1
    }.distinctBy { it.signature() }.one("chat menu action handler")
    val action = mutable(actionRef)
    val actionParameters = action.parameters()
    if (actionParameters.count { it == THREAD_KEY } != 1) refuseMenu("the chat menu action handler doesn't take one chat key")
    if (0 in action.jumpTargets()) refuseMenu("a jump reaches the start of the chat menu action handler")
    val actionLocals = action.freeLocalsAt(LOCK_PATCH, 0, 2)

    val keyClass = classDefByOrNull(THREAD_KEY) ?: refuseMenu("$THREAD_KEY is missing")
    val idField = threadIdField(keyClass)
    requirePublic(THREAD_KEY, idField)

    val extension = mutableClassDefBy(HIDDEN_CHATS_MENU)
    fun hook(name: String, parameters: List<String>, returns: String) = extension.methods.filter {
        it.name == name && it.parameters() == parameters && it.returnType == returns &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }.one("extension's public static $name")
    hook("offer", listOf(LIST, OBJECT), "V")
    hook("shown", listOf(OBJECT, STRING), "V")
    hook("label", listOf(LIST, LABELS), "V")
    hook("tap", listOf(OBJECT, OBJECT), "Z")
    val threadId = hook(MENU_THREAD_ID, listOf(OBJECT), STRING)

    return HiddenChatMenuTargets(
        menu,
        builder, builderRows, builderAt, builderLocals,
        show, showParameters.indexOf(THREAD_KEY), showParameters.indexOf(STRING), showLocals,
        labels, labelRows, labelAt, labelArray,
        action, actionParameters.indexOf(menu), actionParameters.indexOf(THREAD_KEY), actionLocals,
        threadId, idField,
    )
}

/**
 * Refuses unless the menu enum builds a [DETAILS_ROW] row and doesn't keep it in a field: a row the
 * app can't name is one it never offers, so the patch can have it.
 */
private fun requireUnofferedRow(init: Method, menu: String) {
    val code = init.visualCode()
    val named = code.indexOfFirst { it.visualString() == DETAILS_ROW }
    if (named < 0) refuseMenu("the chat menu enum has no $DETAILS_ROW row")
    val built = (named + 1 until code.size).firstOrNull { at ->
        val call = code[at].visualReference() as? MethodReference
        (code[at].opcode == Opcode.INVOKE_DIRECT || code[at].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            call?.definingClass == menu && call.name == "<init>"
    } ?: refuseMenu("the chat menu enum never builds its $DETAILS_ROW row")
    if (code.getOrNull(built + 1)?.opcode == Opcode.SPUT_OBJECT) {
        refuseMenu("the chat menu enum keeps its $DETAILS_ROW row in a field, so Instagram may offer it")
    }
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameters() == method.parameters() && it.returnType == method.returnType
    }

/**
 * Offers the row first thing in the menu builder (straight after Preview's offer, when that's there),
 * hands the chat's key and its name to the extension first thing as a chat's menu opens, relabels the
 * row as the labels are handed back, and takes its tap first thing in the tap handler: a yes returns
 * before Instagram's own code for the row. Preview chats hooks the same builder, label return and tap
 * handler for its own row, in either order. Then writes the key reader's body.
 */
internal fun hookHiddenChatMenu(found: HiddenChatMenuTargets) {
    val (menuClass, rows) = found.builderLocals
    found.builder.addInstructions(
        found.builderAt,
        """
            const-class v$menuClass, ${found.menu}
            move-object/from16 v$rows, ${found.builder.parameterRegister(found.builderRows)}
            invoke-static { v$rows, v$menuClass }, $MENU_OFFER
        """,
    )
    val (key, title) = found.showLocals
    found.show.addInstructions(
        0,
        """
            move-object/from16 v$key, ${found.show.parameterRegister(found.showKey)}
            move-object/from16 v$title, ${found.show.parameterRegister(found.showTitle)}
            invoke-static { v$key, v$title }, $MENU_SHOWN
        """,
    )
    found.labels.addInstructionsAtControlFlowLabel(
        found.labelAt,
        "invoke-static { ${found.labels.parameterRegister(found.labelRows)}, v${found.labelArray} }, $MENU_LABEL",
    )
    val (answer, chatKey) = found.actionLocals
    found.action.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v$answer, ${found.action.parameterRegister(found.chosen)}
            move-object/from16 v$chatKey, ${found.action.parameterRegister(found.key)}
            invoke-static { v$answer, v$chatKey }, $MENU_TAP
            move-result v$answer
            if-eqz v$answer, :instagram
            return-void
        """,
        ExternalLabel("instagram", found.action.getInstruction(0)),
    )
    found.threadId.addInstructions(
        0,
        """
            check-cast p0, $THREAD_KEY
            iget-object p0, p0, ${found.idField.signature()}
            return-object p0
        """,
    )
}

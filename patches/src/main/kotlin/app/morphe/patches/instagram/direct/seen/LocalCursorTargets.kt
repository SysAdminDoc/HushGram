/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.direct.keep.USER_SESSION
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val LOCAL_CURSOR = "$THREAD_SEEN->localCursor(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"

private const val OBJECT_TYPE = "Ljava/lang/Object;"
private const val TEXT_TYPE = "Ljava/lang/String;"
private const val LIST_TYPE = "Ljava/util/List;"
private const val LONG_TYPE = "Ljava/lang/Long;"

private fun refuse(why: String): Nothing = throw PatchException("$THREAD_SEEN_PATCH: $why")
private fun <T> Collection<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")
private fun Method.parameters() = parameterTypes.map(Any::toString)
private fun Instruction.call() = visualReference() as? MethodReference
private fun Instruction.field() = visualReference() as? FieldReference
private fun Instruction.type() = (visualReference() as? TypeReference)?.type

internal class LocalCursorTargets(
    /** Instagram's read of the chat's own read cursor, which also decides where its New Messages divider goes. */
    val read: MutableMethod,
    /** The instruction the hook goes in front of: the first one past the cursor being fetched. */
    val at: Int,
    val bridges: List<ChatBridge>,
)

/**
 * What the cursor read is made of, as the method spells it. The fetch asks the chat's thread for the
 * account's own cursor (the id of its last seen item) and keeps it. Then the reader finds
 * the first message from someone else past that cursor in the chat's items, and that is where
 * the divider goes. With none past it, the chat shows no divider and no jump.
 */
private class CursorShape(
    val cursor: FieldReference,
    val session: FieldReference,
    val store: FieldReference,
    val storeType: String,
    val thread: MethodReference,
    val items: FieldReference,
    val itemType: String,
    val cursorType: String,
    val threadType: String,
    val index: MethodReference,
    val firstRead: Int,
)

/** The cheap part of the shape, enough to pick the method out of the app. */
private fun looksLikeCursorRead(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags)) return false
    val code = method.visualCode()
    if (code.none { it.opcode == Opcode.IGET_OBJECT && it.field()?.let { f -> f.definingClass == USER_SESSION && f.name == "userId" } == true }) return false
    if (code.none { it.opcode == Opcode.INVOKE_INTERFACE && it.call()?.parameterTypes?.map(Any::toString) == listOf(TEXT_TYPE) }) return false
    return code.any {
        it.opcode == Opcode.INVOKE_STATIC && it.call()?.let { c -> c.returnType == "I" && c.parameterTypes.size == 2 } == true
    } && code.any { it.opcode == Opcode.IGET_OBJECT && it.field()?.type == LONG_TYPE }
}

/**
 * Instagram's read of the account's own cursor in a chat: the instance method taking the thread
 * source and the chat key, answering the chat's messages, that fetches the cursor by the account id.
 */
internal object CursorReadFingerprint : Fingerprint(
    returnType = LIST_TYPE,
    parameters = listOf("L", THREAD_KEY),
    custom = { method, _ -> looksLikeCursorRead(method) },
)

private fun BytecodePatchContext.shapeOf(method: Method): CursorShape {
    val code = method.visualCode()
    val self = method.localRegisterCount()
    fun reads(at: Int) = (code[at] as? TwoRegisterInstruction)?.registerB == self

    val fetch = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_INTERFACE && code[at].call()?.let {
            it.parameterTypes.map(Any::toString) == listOf(TEXT_TYPE) && it.returnType.startsWith("L") && it.returnType != TEXT_TYPE
        } == true
    }.one("fetch of the account's own cursor")
    val cursorType = code[fetch].call()!!.returnType
    val store = code.indices.drop(fetch + 1).take(2).singleOrNull { code[it].opcode == Opcode.IPUT_OBJECT }
        ?.takeIf { code[it].field()?.type == cursorType && reads(it) }
        ?: refuse("cursor fetch isn't kept on the reader")
    val cursor = code[store].field()!!
    val session = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT && reads(it) && code[it].field()?.type == USER_SESSION }
        .map { code[it].field()!! }.distinctBy { it.name }.one("session the reader keeps")

    val index = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_STATIC && code[at].call()?.let {
            it.returnType == "I" && it.parameterTypes.map(Any::toString).let { p -> p.size == 2 && p[0] == cursorType }
        } == true
    }.map { code[it].call()!! }.distinctBy { it.name }.one("lookup of the cursor's place in the chat")
    val threadType = index.parameterTypes[1].toString()

    val threadAt = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_VIRTUAL && code[at].call()?.let {
            it.parameterTypes.map(Any::toString) == listOf(THREAD_KEY) && it.returnType == threadType
        } == true
    }.one("read of the chat's thread")
    val thread = code[threadAt].call()!!
    val castAt = (threadAt - 1 downTo maxOf(0, threadAt - 4)).firstOrNull { code[it].opcode == Opcode.CHECK_CAST && code[it].type() == thread.definingClass }
        ?: refuse("thread read isn't cast off the chat store")
    val storeAt = castAt - 1
    if (storeAt < 0 || code[storeAt].opcode != Opcode.IGET_OBJECT || !reads(storeAt) ||
        (code[storeAt] as OneRegisterInstruction).registerA != (code[castAt] as OneRegisterInstruction).registerA
    ) refuse("chat store isn't read off the reader")
    val storeField = code[storeAt].field()!!

    val items = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT && code[it].field()?.let { f -> f.definingClass == threadType && f.type == LIST_TYPE } == true }
        .map { code[it].field()!! }.distinctBy { it.name }.one("list of the chat's items")

    val itemType = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_INTERFACE && code[at].call()?.let { it.definingClass == LIST_TYPE && it.name == "get" } == true &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT && code.getOrNull(at + 2)?.opcode == Opcode.CHECK_CAST
    }.mapNotNull { code[it + 2].type() }.distinct().one("kind of item the reader walks")
    val firstRead = code.indices.firstOrNull { it > store && code[it].opcode == Opcode.IGET_OBJECT && reads(it) && code[it].field()?.type == LONG_TYPE }
        ?: refuse("reader never reads its boundary after fetching the cursor")
    return CursorShape(cursor, session, storeField, thread.definingClass, thread, items, itemType, cursorType, threadType, index, firstRead)
}

/**
 * Resolve, before any edit, where Instagram reads the account's own cursor in a chat. That cursor is
 * the server's idea of the last item this account saw, and with the seen receipt held it never moves,
 * so the chat opens with the New Messages divider and jumps up to it however often it's read here.
 * The bridges give the extension the cursor, the account, the chat's items, an item's id, and a way
 * to move the cursor.
 */
internal fun BytecodePatchContext.findLocalCursor(messageClass: String): LocalCursorTargets? {
    // A build without the reader has no divider to correct, so the hook is left out.
    if (CursorReadFingerprint.matchAllOrNull().orEmpty().isEmpty()) return null
    val read = uniqueMethod(THREAD_SEEN_PATCH, "read of the account's own cursor in a chat", CursorReadFingerprint)
    val shape = shapeOf(read)
    val owner = read.definingClass
    val cursorClass = classDefByOrNull(shape.cursorType) ?: refuse("cursor ${shape.cursorType} is missing")

    // The id lives on the cursor's own class or one it extends.
    val chain = generateSequence(cursorClass as ClassDef?) { it.superclass?.let(::classDefByOrNull) }.toList()
    val lookup = (classDefByOrNull(shape.threadType) ?: refuse("chat thread ${shape.threadType} is missing")).methods.filter {
        it.name == shape.index.name && it.parameters() == shape.index.parameterTypes.map(Any::toString) && it.returnType == "I"
    }.one("lookup of the cursor's place")
    val first = lookup.visualCode().firstOrNull() ?: refuse("cursor lookup is empty")
    val idField = first.field()?.takeIf {
        first.opcode == Opcode.IGET_OBJECT && it.type == TEXT_TYPE && chain.any { c -> c.type == it.definingClass }
    } ?: refuse("cursor lookup doesn't start from the cursor's item id")

    val types = listOf(owner, shape.storeType, shape.threadType, shape.cursorType, idField.definingClass)
    val byType = types.distinct().associateWith { classDefByOrNull(it) ?: refuse("$it is missing") }
    requirePublic(byType, owner, shape.cursor)
    requirePublic(byType, owner, shape.session)
    requirePublic(byType, owner, shape.store)
    requirePublic(byType, shape.storeType, shape.thread)
    requirePublic(byType, shape.threadType, shape.items)
    requirePublic(byType, shape.cursorType, null)
    requirePublic(byType, idField.definingClass, idField)
    // The extension reads an item's id through the bridge Mark as read already uses, so the items
    // the reader walks have to be the messages that bridge reads.
    if (shape.itemType != messageClass) refuse("the reader walks ${shape.itemType}, not the chat's messages ($messageClass)")

    val extension = classDefByOrNull(THREAD_SEEN) ?: refuse("extension is missing")
    requireHook(extension, "localCursor", listOf(OBJECT_TYPE, OBJECT_TYPE, OBJECT_TYPE), "V")
    val bridges = chatBridges(listOf(
        Triple("cursorOf", listOf(OBJECT_TYPE), OBJECT_TYPE) to """
            check-cast p0, $owner
            iget-object p0, p0, ${shape.cursor}
            return-object p0
        """,
        Triple("cursorSession", listOf(OBJECT_TYPE), OBJECT_TYPE) to """
            check-cast p0, $owner
            iget-object p0, p0, ${shape.session}
            return-object p0
        """,
        Triple("cursorItems", listOf(OBJECT_TYPE, OBJECT_TYPE), OBJECT_TYPE) to """
            check-cast p0, $owner
            iget-object p0, p0, ${shape.store}
            if-eqz p0, :none
            check-cast p0, ${shape.storeType}
            check-cast p1, $THREAD_KEY
            invoke-virtual { p0, p1 }, ${shape.thread.definingClass}->${shape.thread.name}($THREAD_KEY)${shape.threadType}
            move-result-object p0
            if-eqz p0, :none
            iget-object p0, p0, ${shape.items}
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
        Triple("cursorItem", listOf(OBJECT_TYPE), TEXT_TYPE) to """
            check-cast p0, ${shape.cursorType}
            iget-object p0, p0, $idField
            return-object p0
        """,
        Triple("moveCursor", listOf(OBJECT_TYPE, TEXT_TYPE), "V") to """
            check-cast p0, ${shape.cursorType}
            iput-object p1, p0, $idField
            return-void
        """,
    ))
    read.requireThisIntact(THREAD_SEEN_PATCH, listOf(shape.firstRead))
    read.requireParameterIntact(THREAD_SEEN_PATCH, 0, listOf(shape.firstRead))
    read.requireParameterIntact(THREAD_SEEN_PATCH, 1, listOf(shape.firstRead))
    return LocalCursorTargets(read, shape.firstRead, bridges)
}

/**
 * Asks the extension, once the account's cursor is fetched and before the reader goes on to look
 * for the first message past it, to move the cursor to the newest message this phone opened the
 * chat to, when that is past the server's. The reader then finds nothing new past it, so the chat
 * shows no divider and doesn't jump. A branch to that read runs the call too. Then writes the
 * bridges' bodies.
 */
internal fun keepCursorLocal(found: LocalCursorTargets) {
    found.read.addInstructionsAtControlFlowLabel(
        found.at,
        "invoke-static/range { p0 .. p2 }, $LOCAL_CURSOR",
    )
    found.bridges.forEach { it.stub.addInstructions(0, it.body) }
}

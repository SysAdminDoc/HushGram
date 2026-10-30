/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val READ_HERE = "$THREAD_SEEN->readHere(Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val SEEN_HERE = "$THREAD_SEEN->seenHere(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Z"

private const val OBJECT_TYPE = "Ljava/lang/Object;"
private const val TEXT_TYPE = "Ljava/lang/String;"

/** How many fields deep the newest item's id may sit under the summary. */
private const val MAX_DEPTH = 6

internal class ReadHereTargets(
    /** The chat summary's answer whether the inbox shows the chat unread. */
    val unread: MutableMethod,
    /**
     * The flags that summary asks itself before it looks at the newest item, Mark as unread among
     * them. A chat kept read never overrides one that's set.
     */
    val flags: List<MethodReference>,
    /**
     * The summary's check whether an account has seen one of the chat's items, taking the account's
     * id, the item's and its sender's. The flags and Instagram's sync ask it too.
     */
    val seen: MutableMethod,
    /** The bridges reading the chat's key and its newest item's id off the summary. */
    val bridges: List<ChatBridge>,
)

private fun refuse(why: String): Nothing = throw PatchException("$THREAD_SEEN_PATCH: $why")
private fun <T> List<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")
private fun Method.parameters() = parameterTypes.map(Any::toString)
private fun Instruction.call() = visualReference() as? MethodReference
private fun Instruction.field() = visualReference() as? FieldReference
private fun ClassDef.isInterface() = AccessFlags.INTERFACE.isSet(accessFlags)

private fun Instruction.writes(register: Int): Boolean {
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return opcode.setsRegister() && (destination == register || opcode.setsWideRegister() && destination + 1 == register)
}

/**
 * Resolve where the inbox asks whether a chat shows unread, before any edit. [chat] is the chat
 * interface Instagram's handler for marking a chat read picks its message from, and it declares one
 * abstract question taking the account and answering whether the chat shows unread. Its one
 * implementation outside the extension, through the interfaces extending it, is the chat summary the
 * inbox draws its rows from. That summary's answer first asks itself a few no-argument booleans, each
 * of which shows the chat unread on its own on 451: Instagram's own Mark as unread flag is the one
 * the chat interface doesn't declare. Then it reads the chat's newest item through a chain of fields
 * and checks that item's id against the last one the account has seen. The bridges read the
 * summary's chat key, and the newest item's id through the same fields, so the extension can tell
 * when a newer message comes in.
 */
internal fun BytecodePatchContext.findReadHere(chat: String): ReadHereTargets {
    val classes = mutableListOf<ClassDef>()
    classDefForEach { classes += it }
    val byType = classes.associateBy { it.type }
    val chatClass = byType[chat] ?: refuse("chat interface $chat is missing")
    if (!chatClass.isInterface()) refuse("chat $chat isn't an interface")
    val asked = chatClass.methods.filter {
        AccessFlags.ABSTRACT.isSet(it.accessFlags) && it.parameters() == listOf(USER_SESSION) && it.returnType == "Z"
    }.one("question whether a chat shows unread")

    val kinds = mutableSetOf(chat)
    while (true) {
        val more = classes.filter { it.isInterface() && it.type !in kinds && it.interfaces.any(kinds::contains) }
        if (more.isEmpty()) break
        more.mapTo(kinds) { it.type }
    }
    val summary = classes.filter {
        !it.isInterface() && !AccessFlags.ABSTRACT.isSet(it.accessFlags) && !it.type.startsWith(EXTENSION_ROOT) &&
            it.interfaces.any(kinds::contains)
    }.one("chat summary the inbox draws its rows from")
    val answer = summary.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null && it.name == asked.name &&
            it.parameters() == listOf(USER_SESSION) && it.returnType == "Z"
    }.one("summary's answer whether the chat shows unread")
    val code = answer.visualCode()
    val compare = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_VIRTUAL && code[at].call()?.let {
            it.definingClass == summary.type && it.parameterTypes.map(Any::toString) == List(3) { TEXT_TYPE } && it.returnType == "Z"
        } == true
    }.one("check of the chat's newest item against the last one seen")
    // A chat kept read waits for each of these, not just Mark as unread: on 451 the other two show
    // the chat unread for an item the newest one doesn't cover, such as a pending or unseen one (#186).
    // The hook asks each one with invoke-virtual on the summary, so only those asked that way of this count.
    val self = answer.localRegisterCount()
    val asks = code.indices.take(compare).filter { at ->
        code[at].opcode == Opcode.INVOKE_VIRTUAL && (code[at] as FiveRegisterInstruction).registerC == self &&
            code[at].call()?.let {
                it.definingClass == summary.type && it.parameterTypes.isEmpty() && it.returnType == "Z"
            } == true
    }
    answer.requireThisIntact(THREAD_SEEN_PATCH, asks)
    val flags = asks.mapNotNull { code[it].call() }.distinctBy { it.name }
    val declared = chatClass.methods.mapTo(mutableSetOf()) { it.name }
    flags.filter { it.name !in declared }.one("Mark as unread flag the summary checks")
    // The check takes the account's id, the newest item's id and its sender's id, in that order.
    val newest = newestItem(answer, code, compare, (code[compare] as FiveRegisterInstruction).registerE)
    val check = code[compare].call()!!
    val key = summary.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == THREAD_KEY
    }.one("summary's chat key")

    requirePublic(byType, summary.type, key)
    flags.forEach { requirePublic(byType, summary.type, it) }
    newest.forEach { requirePublic(byType, it.definingClass, it) }

    val unread = mutableClassDefBy(summary.type).methods.single {
        it.name == answer.name && it.parameters() == answer.parameters() && it.returnType == answer.returnType
    }
    unread.requireLocals(THREAD_SEEN_PATCH, 2)
    if (0 in unread.jumpTargets()) refuse("a jump reaches the start of the summary's answer whether the chat shows unread")
    val seen = mutableClassDefBy(summary.type).methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null && it.name == check.name &&
            it.parameters() == check.parameterTypes.map(Any::toString) && it.returnType == check.returnType
    }.one("summary's own check whether an item was seen")
    seen.requireLocals(THREAD_SEEN_PATCH, 1)
    if (0 in seen.jumpTargets()) refuse("a jump reaches the start of the summary's check whether an item was seen")

    val extension = byType[THREAD_SEEN] ?: refuse("extension is missing")
    requireHook(extension, "readHere", listOf(OBJECT_TYPE, OBJECT_TYPE), "Z")
    requireHook(extension, "seenHere", listOf(OBJECT_TYPE, TEXT_TYPE, TEXT_TYPE), "Z")
    val reads = newest.dropLast(1).joinToString("\n") { "iget-object p0, p0, $it\nif-eqz p0, :none" }
    val bridges = chatBridges(listOf(
        Triple("chatKey", listOf(OBJECT_TYPE), OBJECT_TYPE) to """
            check-cast p0, ${summary.type}
            invoke-virtual { p0 }, ${summary.type}->${key.name}()$THREAD_KEY
            move-result-object p0
            return-object p0
        """,
        Triple("newestMessage", listOf(OBJECT_TYPE), TEXT_TYPE) to """
            check-cast p0, ${summary.type}
            $reads
            iget-object p0, p0, ${newest.last()}
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    ))
    return ReadHereTargets(unread, flags, seen, bridges)
}

/**
 * The fields [method] reads, starting from `this`, to get the value [register] holds at [at]: each
 * an iget-object of the object the read before it got, reaching the next read on every path, the
 * last one a String.
 */
private fun newestItem(method: Method, code: List<Instruction>, at: Int, register: Int): List<FieldReference> {
    val self = method.localRegisterCount()
    val chain = ArrayDeque<FieldReference>()
    var use = at
    var held = register
    while (held != self) {
        if (chain.size == MAX_DEPTH) refuse("chat's newest item sits deeper than $MAX_DEPTH fields in the summary")
        val read = (use - 1 downTo 0).firstOrNull { code[it].writes(held) }
            ?: refuse("chat's newest item doesn't come from the summary")
        val field = code[read].field()
        if (code[read].opcode != Opcode.IGET_OBJECT || field == null) refuse("chat's newest item isn't read off a field")
        requireOrigin(THREAD_SEEN_PATCH, method, use, held, read, "chat's newest item")
        chain.addFirst(field)
        use = read
        held = (code[read] as TwoRegisterInstruction).registerB
    }
    method.requireThisIntact(THREAD_SEEN_PATCH, listOf(use))
    if (chain.isEmpty() || chain.first().definingClass != method.definingClass || chain.last().type != TEXT_TYPE) {
        refuse("chat's newest item id isn't a String the summary reaches through its own fields")
    }
    return chain.toList()
}

/**
 * Asks the extension at the start of the summary's answer, with the summary and the account,
 * whether the chat stays read on this phone. A yes returns that the chat isn't unread, unless one of
 * the summary's own flags is set, Mark as unread among them, which a chat kept read never overrides.
 * A no, and a set flag, run Instagram's answer as it is.
 *
 * The summary's seen check asks the extension first too, with the summary, the account's id and the
 * item's, and a yes answers seen. A sync fills one of the flags' items with the newest message the
 * server still has unseen, which is the one opened here while its receipt was held, so without this
 * the chat showed unread again after a refresh (#186). Then writes the bridges' bodies.
 */
internal fun keepChatsRead(found: ReadHereTargets) {
    val flags = found.flags.joinToString("\n") {
        "invoke-virtual { v0 }, ${it.definingClass}->${it.name}()Z\nmove-result v1\nif-nez v1, :instagram"
    }
    found.unread.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, p1
            invoke-static { v0, v1 }, $READ_HERE
            move-result v1
            if-eqz v1, :instagram
            $flags
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("instagram", found.unread.getInstruction(0)),
    )
    found.seen.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p2 }, $SEEN_HERE
            move-result v0
            if-eqz v0, :instagram
            return v0
        """,
        ExternalLabel("instagram", found.seen.getInstruction(0)),
    )
    found.bridges.forEach { it.stub.addInstructions(0, it.body) }
}

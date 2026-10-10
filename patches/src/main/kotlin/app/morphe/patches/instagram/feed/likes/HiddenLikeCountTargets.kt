/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.likes

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val HIDDEN_LIKE_COUNTS_PATCH = "Show hidden like counts"

internal const val HIDDEN_LIKE_COUNTS = "$EXTENSION_PACKAGE/feed/HiddenLikeCounts;"
internal const val HIDDEN_DECISION = "$HIDDEN_LIKE_COUNTS->hidden(I)Z"
internal const val SAW_LIKE_COUNT = "$HIDDEN_LIKE_COUNTS->sawCount(Ljava/lang/Object;Ljava/lang/Object;)V"

/** The extension's stub the patch fills: a boolean read off a post's data, by key. */
internal const val TREE_FLAG_STUB = "flag"

/** The post model, a kept name. */
internal const val POST_MODEL = "Lcom/instagram/feed/media/Media;"

/**
 * The post's fields the patch goes by. Instagram's data trees key a field by its name's hash, and
 * the post model's getters hold that hash, which proves it before anything is found by it.
 */
internal const val LIKES_HIDDEN_FIELD = "like_and_view_counts_disabled"
internal val LIKES_HIDDEN_KEY = LIKES_HIDDEN_FIELD.hashCode()
internal const val LIKE_COUNT_FIELD = "like_count"
internal val LIKE_COUNT_KEY = LIKE_COUNT_FIELD.hashCode()

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private val DECIDER_PARAMETERS = listOf(USER_SESSION, "Ljava/lang/String;", "Z")
private const val FLAG_PARAMETER = 2
private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val OBJECT = "Ljava/lang/Object;"

/** How many instructions past a read of the flag a like row asks the decider, at most. */
private const val READER_REACH = 40

/** Fewer like rows than this asking one decider and it isn't the method every row asks. 450 has six. */
private const val FEWEST_READERS = 3

private fun refuse(why: String): Nothing = throw PatchException("$HIDDEN_LIKE_COUNTS_PATCH: $why")

/**
 * What the patch changes. [decider] is the method every like row asks whether to hide a post's
 * count, with the post's hidden-count flag in register [flag]. [counter] is Instagram's like count
 * reader, which reads the post's `like_count` off its data tree in register [tree] into register
 * [count] at instruction [countAt]. [treeFlagRead] is the boolean read the counter makes on that
 * tree, which the stub uses to read the flag.
 */
internal class HiddenLikeCountAnchors(
    val decider: Method,
    val flag: Int,
    val counter: Method,
    val countAt: Int,
    val tree: Int,
    val count: Int,
    val treeFlagRead: MethodReference,
)

/**
 * Finds both anchors and proves what the hooks rely on, before any change.
 *
 * The decider: the like rows read the flag off a post's data tree by its key, an interface call
 * answering a Boolean, and within a few instructions hand it to an instance or static method taking
 * the session, the poster's id and the flag and answering whether to hide the count. On 450 it
 * answers hidden at once when the flag is set, and otherwise asks whether the post is yours and
 * whether you hid like counts on everything. Fails when fewer than [FEWEST_READERS] rows ask one, when
 * rows ask more than one, or when its first instruction isn't a test of the flag that goes straight
 * to answering true, or is something a branch lands on.
 *
 * The counter: the post model's int getter for its like count calls one static method taking one
 * object, which reads `like_count` once, by its key, with an interface call answering an Integer.
 * Fails when no getter or more than one reader is found, when the read isn't kept, when the data
 * tree or the count sits in a register an invoke can't name, when a branch lands right after the
 * read, or when the reader makes no single boolean read on the same tree to borrow for the stub.
 *
 * Also fails when the post model's getters don't hold the two keys, or the extension lacks its
 * hooks or the stub.
 */
internal fun BytecodePatchContext.findHiddenLikeCounts(): HiddenLikeCountAnchors {
    pandoGetter(HIDDEN_LIKE_COUNTS_PATCH, POST_MODEL, LIKES_HIDDEN_FIELD, BOOLEAN)
    pandoGetter(HIDDEN_LIKE_COUNTS_PATCH, POST_MODEL, LIKE_COUNT_FIELD, INTEGER)

    val (decider, flag) = findDecider()
    val counter = findCounter()
    val code = counter.code()
    val read = code.indices.single { code.readsKey(it, LIKE_COUNT_KEY, INTEGER) }
    val invoke = code[read] as FiveRegisterInstruction
    val tree = invoke.registerC
    val count = (code[read + 1] as OneRegisterInstruction).registerA
    val where = "${counter.definingClass}->${counter.name}"
    if (tree == count) refuse("$where reads the like count over its own data tree")
    if (tree > 15 || count > 15) refuse("$where keeps the data tree or the count in a register the hook can't name")
    if (read + 2 >= code.size || read + 2 in counter.jumpTargets()) refuse("a branch in $where lands right after its like count read")

    val countRead = (code[read] as ReferenceInstruction).reference as MethodReference
    val flagReads = code.mapNotNull { instruction ->
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        called?.takeIf {
            instruction.opcode == Opcode.INVOKE_INTERFACE && (instruction as FiveRegisterInstruction).registerC == tree &&
                it.definingClass == countRead.definingClass && it.returnType == BOOLEAN &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I")
        }
    }.distinctBy { it.toString() }
    val treeFlagRead = flagReads.singleOrNull()
        ?: refuse("$where makes ${flagReads.size} kinds of boolean read on its data tree, not one")

    val extension = classDefByOrNull(HIDDEN_LIKE_COUNTS) ?: refuse("the extension has no $HIDDEN_LIKE_COUNTS")
    for (hook in listOf(HIDDEN_DECISION, SAW_LIKE_COUNT)) {
        extension.methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: refuse("the extension has no public static $hook")
    }
    extension.methods.singleOrNull {
        it.name == TREE_FLAG_STUB && it.returnType == BOOLEAN && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT, "I") && it.implementation != null
    } ?: refuse("$HIDDEN_LIKE_COUNTS has no static $BOOLEAN $TREE_FLAG_STUB($OBJECT I)")

    return HiddenLikeCountAnchors(decider, flag, counter, read + 1, tree, count, treeFlagRead)
}

/** The decider and the register its flag parameter is in. */
private fun BytecodePatchContext.findDecider(): Pair<Method, Int> {
    val asked = classesLoading(LIKES_HIDDEN_KEY.toLong()).filter { it.type != POST_MODEL }.flatMap { classDef ->
        classDef.methods.mapNotNull { method -> method.deciderAsked()?.let { method.key() to it } }
    }
    val deciders = asked.map { it.second }.distinct()
    val reference = deciders.singleOrNull()
        ?: refuse("expected the like rows to ask one method whether to hide the count, found ${deciders.size}: ${deciders.joinToString()}")
    val readers = asked.map { it.first }.distinct()
    if (readers.size < FEWEST_READERS) refuse("only ${readers.size} like rows ask $reference, fewer than $FEWEST_READERS")

    val owner = reference.substringBefore("->")
    val decider = classDefByOrNull(owner)?.methods?.singleOrNull { it.key() == reference }
        ?: refuse("the like rows ask $reference, which isn't in the app")
    val code = decider.code()
    val flag = decider.parameterRegisterNumber(FLAG_PARAMETER)
    val test = code.firstOrNull()
    if (test?.opcode != Opcode.IF_NEZ || (test as OneRegisterInstruction).registerA != flag) {
        refuse("$reference doesn't test the flag first")
    }
    val target = code.branchTarget(0)
    val answer = code.getOrNull(target)
    val returned = code.getOrNull(target + 1)
    if (answer?.opcode != Opcode.CONST_4 || (answer as NarrowLiteralInstruction).narrowLiteral != 1 ||
        returned?.opcode != Opcode.RETURN || (returned as OneRegisterInstruction).registerA != (answer as OneRegisterInstruction).registerA
    ) {
        refuse("$reference doesn't answer hidden at once when the flag is set")
    }
    if (0 in decider.jumpTargets()) refuse("a jump or exception handler enters $reference at its first instruction")
    if (flag > 255) refuse("$reference keeps the flag in a register the hook can't write")
    return decider to flag
}

/**
 * The decider this method asks after it reads the flag: the first call taking the session, an id
 * and a boolean and answering a boolean within [READER_REACH] instructions of a read. Null when it
 * makes no such read, or asks nothing after one.
 */
private fun Method.deciderAsked(): String? {
    val code = code()
    for (read in code.indices) {
        if (!code.readsKey(read, LIKES_HIDDEN_KEY, BOOLEAN)) continue
        for (at in read + 2 until minOf(code.size, read + READER_REACH)) {
            val called = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (called.returnType == "Z" && called.parameterTypes.map(CharSequence::toString) == DECIDER_PARAMETERS) {
                return called.key()
            }
        }
    }
    return null
}

/**
 * Instagram's like count reader: the one static method taking one object and answering an int
 * that an int getter of the post model calls, and that reads `like_count` once.
 */
private fun BytecodePatchContext.findCounter(): Method {
    val post = classDefByOrNull(POST_MODEL) ?: refuse("no $POST_MODEL")
    val called = post.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == "I"
    }.flatMap { getter ->
        getter.code().mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                instruction.opcode == Opcode.INVOKE_STATIC && it.returnType == "I" && it.parameterTypes.size == 1
            }
        }
    }.distinctBy { it.key() }
    val counters = called.mapNotNull { reference ->
        classDefByOrNull(reference.definingClass)?.methods?.singleOrNull { it.key() == reference.key() }
    }.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.code().let { code -> code.indices.count { code.readsKey(it, LIKE_COUNT_KEY, INTEGER) } == 1 }
    }
    return counters.singleOrNull() ?: refuse(
        "expected one like count reader behind $POST_MODEL's int getters, found " +
            if (counters.isEmpty()) "none" else counters.joinToString { "${it.definingClass}->${it.name}" },
    )
}

/**
 * Whether instruction [at] reads [key] off a data tree: an interface call taking only the key,
 * loaded by the instruction before, answering [answer], with the answer kept by the one after.
 */
private fun List<Instruction>.readsKey(at: Int, key: Int, answer: String): Boolean {
    val call = this[at]
    val called = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (call.opcode != Opcode.INVOKE_INTERFACE || called.returnType != answer ||
        called.parameterTypes.map(CharSequence::toString) != listOf("I")
    ) {
        return false
    }
    val loaded = getOrNull(at - 1) as? NarrowLiteralInstruction ?: return false
    return loaded.narrowLiteral == key && (loaded as OneRegisterInstruction).registerA == (call as FiveRegisterInstruction).registerD &&
        getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
}

/** The index of the instruction the branch at [at] goes to. */
private fun List<Instruction>.branchTarget(at: Int): Int {
    var address = 0
    val addresses = IntArray(size)
    forEachIndexed { index, instruction -> addresses[index] = address; address += instruction.codeUnits }
    val target = addresses[at] + (this[at] as OffsetInstruction).codeOffset
    return addresses.indexOf(target)
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.key(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun MethodReference.key(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

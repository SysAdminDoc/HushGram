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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val HIDDEN_LIKE_COUNTS_PATCH = "Show hidden like counts"

internal const val HIDDEN_LIKE_COUNTS = "$EXTENSION_PACKAGE/feed/HiddenLikeCounts;"
internal const val HIDDEN_DECISION = "$HIDDEN_LIKE_COUNTS->hidden(Ljava/lang/String;I)Z"
internal const val SAW_LIKE_COUNT = "$HIDDEN_LIKE_COUNTS->sawCount(Ljava/lang/Object;Ljava/lang/Object;)V"

internal const val ROW_READ = "$HIDDEN_LIKE_COUNTS->rowRead(Ljava/lang/Object;Ljava/lang/Object;)V"

/** The extension's stub the patch fills: a boolean read off a post's data, by key. */
internal const val TREE_FLAG_STUB = "flag"

/** The extension's stub the patch fills: the like count read off a post's data, by key. */
internal const val TREE_COUNT_STUB = "count"

/** The extension's stub the patch fills: the part of a post's data kept under a key, its poster. */
internal const val TREE_CHILD_STUB = "child"

/** The extension's stub the patch fills: the string a poster's data keeps under a key, their id. */
internal const val TREE_TEXT_STUB = "text"

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
internal const val POSTER_FIELD = "user"
internal val POSTER_KEY = POSTER_FIELD.hashCode()
internal const val ID_FIELD = "id"
internal val ID_KEY = ID_FIELD.hashCode()

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private val DECIDER_PARAMETERS = listOf(USER_SESSION, "Ljava/lang/String;", "Z")
private const val POSTER_PARAMETER = 1
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
 * count, with the poster's id in register [poster] and the post's hidden-count flag in register
 * [flag], the next one. [counter] is Instagram's like count
 * reader, which reads the post's `like_count` off its data tree in register [tree] into register
 * [count] at instruction [countAt]. [treeFlagRead] is the boolean read the counter makes on that
 * tree, which the stub uses to read the flag. [childRead] and [textRead] are the reads the like rows
 * make of a post's poster and of that poster's id, which the stubs use to find who posted a post.
 */
internal class HiddenLikeCountAnchors(
    val decider: Method,
    val poster: Int,
    val flag: Int,
    val counter: Method,
    val countAt: Int,
    val tree: Int,
    val count: Int,
    val treeFlagRead: MethodReference,
    val countRead: MethodReference,
    val childRead: MethodReference,
    val textRead: MethodReference,
    val rows: List<RowRead>,
)

/**
 * A like row's read of a post's flag: [method] reads it by an interface call on the data tree in
 * register [tree], and keeps the answer, a Boolean, in register [flag] at instruction [at].
 */
internal class RowRead(val method: Method, val at: Int, val tree: Int, val flag: Int)

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

    val (decider, flag, asked) = findDecider()
    val poster = decider.parameterRegisterNumber(POSTER_PARAMETER)
    if (flag != poster + 1) refuse("${decider.definingClass}->${decider.name} doesn't keep the poster's id and the flag in neighboring registers")
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

    val (childRead, textRead) = findPosterReads(treeFlagRead.definingClass)

    val extension = classDefByOrNull(HIDDEN_LIKE_COUNTS) ?: refuse("the extension has no $HIDDEN_LIKE_COUNTS")
    for (hook in listOf(HIDDEN_DECISION, SAW_LIKE_COUNT, ROW_READ)) {
        extension.methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: refuse("the extension has no public static $hook")
    }
    extension.methods.singleOrNull {
        it.name == TREE_FLAG_STUB && it.returnType == BOOLEAN && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT, "I") && it.implementation != null
    } ?: refuse("$HIDDEN_LIKE_COUNTS has no static $BOOLEAN $TREE_FLAG_STUB($OBJECT I)")
    for (stub in listOf(TREE_COUNT_STUB, TREE_CHILD_STUB, TREE_TEXT_STUB)) {
        extension.methods.singleOrNull {
            it.name == stub && it.returnType == OBJECT && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT, "I") && it.implementation != null
        } ?: refuse("$HIDDEN_LIKE_COUNTS has no static $OBJECT $stub($OBJECT I)")
    }

    val rows = asked.map { (method, readAt) ->
        val reader = "${method.definingClass}->${method.name}"
        val rowCode = method.code()
        val call = rowCode[readAt] as FiveRegisterInstruction
        val rowTree = call.registerC
        val rowFlag = (rowCode[readAt + 1] as OneRegisterInstruction).registerA
        if ((call as ReferenceInstruction).reference.toString() != treeFlagRead.toString()) {
            refuse("$reader reads the flag with a different call than $where does")
        }
        if (rowTree == rowFlag) refuse("$reader reads the flag over its own data tree")
        if (rowTree > 15 || rowFlag > 15) refuse("$reader keeps the data tree or the flag in a register the hook can't name")
        if (readAt + 2 >= rowCode.size || readAt + 2 in method.jumpTargets()) refuse("a branch in $reader lands right after its flag read")
        RowRead(method, readAt + 1, rowTree, rowFlag)
    }

    return HiddenLikeCountAnchors(decider, poster, flag, counter, read + 1, tree, count, treeFlagRead, countRead, childRead, textRead, rows)
}

/**
 * The two reads the like rows make to find who posted a post: the call on a post's data that
 * answers the poster's data, loaded with the key of `user`, and the call on that answering the
 * poster's id, loaded with the key of `id`. Each is looked for in the classes the rows are in, on
 * the data interface the flag is read with, and each has to be the same call everywhere, or the
 * patch refuses rather than guess which one reads the poster.
 */
private fun BytecodePatchContext.findPosterReads(tree: String): Pair<MethodReference, MethodReference> {
    val reads = classesLoading(LIKES_HIDDEN_KEY.toLong()).filter { it.type != POST_MODEL }.flatMap { classDef ->
        classDef.methods.flatMap { method ->
            val code = method.code()
            code.indices.mapNotNull { at -> code.keyedRead(at)?.let { (key, called) -> key to called } }
        }
    }.filter { (_, called) -> called.definingClass == tree }
    fun one(what: String, key: Int, returns: String): MethodReference {
        val found = reads.filter { (read, called) -> read == key && called.returnType == returns }.map { it.second }.distinctBy { it.key() }
        return found.singleOrNull()
            ?: refuse("expected the like rows to read $what with one call on $tree, found ${found.size}: ${found.joinToString { it.key() }}")
    }
    return one("a post's poster", POSTER_KEY, tree) to one("a poster's id", ID_KEY, "Ljava/lang/String;")
}

/** The decider and the register its flag parameter is in. */
private fun BytecodePatchContext.findDecider(): Triple<Method, Int, List<Pair<Method, Int>>> {
    val asked = classesLoading(LIKES_HIDDEN_KEY.toLong()).filter { it.type != POST_MODEL }.flatMap { classDef ->
        classDef.methods.flatMap { method -> method.deciderAsked().map { (read, decider) -> Triple(method, read, decider) } }
    }
    val deciders = asked.map { it.third }.distinct()
    val reference = deciders.singleOrNull()
        ?: refuse("expected the like rows to ask one method whether to hide the count, found ${deciders.size}: ${deciders.joinToString()}")
    val readers = asked.map { it.first.key() }.distinct()
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
    return Triple(decider, flag, asked.map { it.first to it.second })
}

/**
 * Each read of the flag in this method, with the decider asked after it: the first call taking the
 * session, an id and a boolean and answering a boolean within [READER_REACH] instructions of the
 * read. Empty when it makes no such read, or asks nothing after one.
 */
private fun Method.deciderAsked(): List<Pair<Int, String>> {
    val code = code()
    return code.indices.mapNotNull { read ->
        if (!code.readsFlag(read)) return@mapNotNull null
        (read + 2 until minOf(code.size, read + READER_REACH)).firstNotNullOfOrNull { at ->
            val called = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
            called?.takeIf { it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == DECIDER_PARAMETERS }
                ?.let { read to it.key() }
        }
    }
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

/**
 * Whether instruction [at] reads a post's hidden-count flag: an interface call taking only an int
 * and answering a Boolean, whose argument is the key of the flag, and whose answer is kept by the
 * instruction after it. The key may be loaded just before the call, or once at the top of the method
 * and kept in its register, as the facepile's reader does.
 */
private fun List<Instruction>.readsFlag(at: Int): Boolean {
    val called = (this[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return keyedRead(at)?.let { (key, _) -> key == LIKES_HIDDEN_KEY && called.returnType == BOOLEAN } == true &&
        getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
}

/**
 * The key and the call when instruction [at] is an interface call taking only an int, whose
 * argument is a constant: loaded within the four instructions before it, straight or through moves,
 * or else the one constant the whole method ever writes to that register.
 */
private fun List<Instruction>.keyedRead(at: Int): Pair<Int, MethodReference>? {
    val call = this[at]
    val called = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (call.opcode != Opcode.INVOKE_INTERFACE || called.parameterTypes.map(CharSequence::toString) != listOf("I")) return null
    return constantIn((call as FiveRegisterInstruction).registerD, at, 3)?.let { it to called }
}

/** The constant register [register] holds before instruction [before], following up to [moves] moves. */
private fun List<Instruction>.constantIn(register: Int, before: Int, moves: Int): Int? {
    val nearest = (before - 1 downTo maxOf(0, before - 4)).firstOrNull { writes(it, register) }
    val written = nearest ?: indices.filter { it < before && writes(it, register) }.singleOrNull() ?: return null
    val instruction = this[written]
    if (instruction is NarrowLiteralInstruction) return instruction.narrowLiteral
    if (moves == 0 || (instruction.opcode != Opcode.MOVE && instruction.opcode != Opcode.MOVE_FROM16 && instruction.opcode != Opcode.MOVE_16)) {
        return null
    }
    return constantIn((instruction as TwoRegisterInstruction).registerB, written, moves - 1)
}

/** Whether instruction [at] might write [register]: anything naming it first, or a wide write ending in it. */
private fun List<Instruction>.writes(at: Int, register: Int): Boolean {
    val instruction = this[at] as? OneRegisterInstruction ?: return false
    return instruction.registerA == register || (instruction.opcode.name.contains("wide") && instruction.registerA == register - 1)
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

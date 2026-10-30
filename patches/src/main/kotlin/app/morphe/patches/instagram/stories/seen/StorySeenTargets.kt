/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.util.entryReads
import app.morphe.util.extendsClass
import app.morphe.util.literalReads
import app.morphe.util.namedRegisters
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val STORY_SEEN = "$EXTENSION_PACKAGE/stories/StorySeen;"
internal const val TO_SEND = "$STORY_SEEN->toSend(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
internal const val TO_RETRY = "$STORY_SEEN->toRetry(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
internal const val STORY_SEEN_BUTTON = "$EXTENSION_PACKAGE/stories/StorySeenButton;"
internal const val BIND_BUTTON = "$STORY_SEEN_BUTTON->bind(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"

/** Classes Instagram keeps the names of: the account signed in and a story in the viewer. */
internal const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
internal const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"

/** The getter of the account's user ID, a name Instagram keeps. */
internal const val USER_ID = "getUserId"

/** The name the seen request sends the stories you watched under. */
internal const val REELS_KEY = "reels"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val MAP = "Ljava/util/Map;"
private const val VIEW = "Landroid/view/View;"
private const val VALUE_OF = "Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;"

/** What a batch may hold its stories in, so the extension can read it as a Map. */
private val MAP_TYPES = setOf(MAP, "Ljava/util/HashMap;", "Ljava/util/LinkedHashMap;")

/** The collections a new batch may start with, empty, in what the seen request sends. */
private val FRESH_TYPES = setOf("Ljava/util/HashMap;", "Ljava/util/LinkedHashMap;", "Ljava/util/ArrayList;")

/** What a static field the seen request reads may go into besides the call starting its builder: a number or a flag. */
private val SETTING_TYPES = setOf("I", "Z", "Ljava/lang/Integer;", "Ljava/lang/Boolean;")

/** The questions a collection answers without changing: whether it's empty, and its size. */
private val QUERIES = setOf("isEmpty", "size")

/** What a route making a batch of its own may do with a collection of it: add constants. */
private val ADDS = setOf("put", "add")

private val IGETS = setOf(
    Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN, Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT,
)
private val IPUTS = setOf(
    Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_CHAR, Opcode.IPUT_SHORT,
)
private val ZERO_CONSTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)
private val CONSTANTS = ZERO_CONSTS + setOf(Opcode.CONST_HIGH16, Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)
private val VIRTUAL_INVOKES = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
private val STATIC_INVOKES = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val DIRECT_INVOKES = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Everything View stories anonymously works on, found before anything changes.
 *
 * The batch is the class whose method builds the seen request; the store is the class that sends a
 * batch, [send] the one method doing it, [retry] its rebuild of a batch it retries if it has one,
 * [getter] the store's static getter for an account and [sessionGetter] the instance method
 * answering the account a store sends for, on [sessionOwner]. [reels] is the batch's map of the
 * stories it holds, the one the request writes under "reels". The story binder hands the hook its
 * account, story and view holder, parameters [session], [item] and [holder], and [itemView] is the
 * view holder's root view.
 */
internal class StorySeenTargets(
    val batch: String,
    val store: String,
    val send: String,
    val retry: StoreRetry?,
    val getter: String,
    val sessionOwner: String,
    val sessionGetter: String,
    val reels: FieldReference,
    val binder: String,
    val binderName: String,
    val binderParameters: List<String>,
    val session: Int,
    val item: Int,
    val holder: Int,
    val itemView: FieldReference,
    val queue: StoryRetryQueue?,
    internal val stubs: StorySeenStubs,
)

/**
 * The store's rebuild of a batch it retries, by name and parameters, the instruction building the
 * seen request there and the register holding the batch at it.
 */
internal class StoreRetry(val name: String, val parameters: List<String>, val build: Int, val batch: Int)

/** The extension methods the patch hooks with or fills in, found before anything changes. */
internal class StorySeenStubs(
    val emptyBatch: MutableMethod,
    val seenStories: MutableMethod,
    val sendBatch: MutableMethod,
    val storeAccount: MutableMethod,
    val sessionAccount: MutableMethod,
    val storyId: MutableMethod,
    val itemView: MutableMethod,
)

/** Where the seen request adds the stories: the batch's map of them, and the instruction adding them. */
private class Stories(val field: FieldReference, val add: Int)

/**
 * Finds what the patch needs and checks it's safe to write, or refuses naming what's wrong:
 *
 * - the batch, final, extending Object, its seen request named by no interface it implements, so
 *   the request can be called under no other name;
 * - the store's one send, an instance method taking the batch that builds the seen request from
 *   the batch it's handed, in a register a byte can name, with nothing written over that parameter
 *   before the request is built and nothing done with it before then but reading it;
 * - the batch's map of stories: the seen request names [REELS_KEY] once and adds, under it, what a
 *   static `(Map)String` made of one map field of its own batch, read on a straight run from there;
 * - that a batch the extension starts holds nothing the seen request sends: the request reads only
 *   fields of its own batch and hands the batch to nothing, and the public constructor taking
 *   nothing, with the constructors it runs, starts each of those fields as a new empty collection
 *   or as nothing at all, and the stories as a new empty map;
 * - that the request sends nothing but its batch's fields and constants ([requireRequestSendsOnlyItsBatch]);
 * - that every call of the seen request anywhere goes through a hook or is handed a batch made right
 *   there with only constants in it ([requireEveryRouteToTheRequest]);
 * - the store's one public static getter taking a [USER_SESSION], so a tap can send through it, and
 *   the public getter of the account a store sends for, with the account's [USER_ID];
 * - the story item binder's toolbar bind ([STORY_BINDER_TRACE]), static, taking one
 *   [USER_SESSION], one [REEL_ITEM] and one view holder, whose first instruction no branch lands on,
 *   with three locals the hook can borrow;
 * - the view holder base's item view, the public View field its constructor keeps its parameter in,
 *   and [REEL_ITEM]'s public getId(), on a class rather than an interface;
 * - the extension's hooks and stubs.
 */
internal fun BytecodePatchContext.findStorySeen(): StorySeenTargets {
    val request = methodLoading("story seen request", SEEN_PATH, FORCE_SEEN_KEY)
    val batch = request.definingClass
    val storeReader = storeReader()
    val store = storeReader.definingClass
    val storeClass = classDefByOrNull(store) ?: refuse("$store isn't in this build")
    val batchClass = classDefByOrNull(batch) ?: refuse("$batch isn't in this build")
    requireSealedBatch(batchClass, request)

    val senders = storeClass.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(Any::toString) == listOf(batch) &&
            method.code().any { it.methodReference()?.sameAs(request) == true }
    }
    val send = senders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one instance method ($batch)V in $store that builds the seen request, found ${senders.size}",
    )
    requireByteRegister("$store->${send.name}", send.parameterRegisterNumber(0))
    val sendCode = send.code()
    val builds = sendCode.indices.filter { sendCode[it].methodReference()?.sameAs(request) == true }
    val build = builds.singleOrNull() ?: refuse("expected $store->${send.name} to build the seen request once, found ${builds.size}")
    val handed = sendCode[build].namedRegisters()
    if (handed.firstOrNull() != send.parameterRegisterNumber(0) || handed.count { it == handed.first() } != 1) {
        refuse("$store->${send.name} builds the seen request from something other than the batch it's handed")
    }
    send.requireParameterIntact(PATCH, 0, listOf(build))
    requireSendLeavesItsBatchAlone(send, build, batchClass)

    val stories = storiesField(request, batch)
    val reels = stories.field
    val constructors = requireFreshBatchIsEmpty(request, batchClass, reels)
    requireRequestSendsOnlyItsBatch(request, batch, stories.add)
    val retry = requireEveryRouteToTheRequest(request, batch, store, send, constructors)

    val getters = storeClass.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags) &&
            method.parameterTypes.map(Any::toString) == listOf(USER_SESSION) && method.returnType == store
    }
    val getter = getters.singleOrNull()
        ?: refuse("expected $store to have one public static getter taking a $USER_SESSION, found ${getters.size}")
    if (!AccessFlags.PUBLIC.isSet(storeClass.accessFlags) || !AccessFlags.PUBLIC.isSet(send.accessFlags)) {
        refuse("$store->${send.name} isn't public, so a tap can't send through it")
    }
    val sessionGetter = sessionGetter(storeClass)
    requireUserId()

    val binder = uniqueMethod(PATCH, "story item binder's toolbar bind", StoryHeaderBinderFingerprint)
    val where = "${binder.definingClass}->${binder.name}"
    if (!AccessFlags.STATIC.isSet(binder.accessFlags)) refuse("$where isn't static, as the story item binder's toolbar bind is")
    val parameters = binder.parameterTypes.map(Any::toString)
    val holderInit = uniqueMethod(PATCH, "view holder constructor", ViewHolderFingerprint)
    val base = holderInit.definingClass
    fun one(what: String, matches: (String) -> Boolean): Int {
        val found = parameters.indices.filter { matches(parameters[it]) }
        return found.singleOrNull() ?: refuse("expected $where to take one $what, found ${found.size}")
    }
    val session = one(USER_SESSION) { it == USER_SESSION }
    val item = one(REEL_ITEM) { it == REEL_ITEM }
    val holder = one("view holder") { it.startsWith("L") && extendsClass(it, base) }
    if (0 in binder.jumpTargets()) refuse("a branch in $where lands on its first instruction, where the hook goes")
    val locals = binder.localRegisterCount()
    if (locals < 3) refuse("$where has $locals local register(s), needs 3")

    val itemView = itemViewField(holderInit)
    val reelItem = classDefByOrNull(REEL_ITEM) ?: refuse("$REEL_ITEM isn't in this build")
    if (AccessFlags.INTERFACE.isSet(reelItem.accessFlags)) refuse("$REEL_ITEM is an interface, and the patch calls getId() on it as a class")
    reelItem.methods.singleOrNull {
        it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == STRING &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$REEL_ITEM has no public getId()")
    listOf(batchClass, storeClass, reelItem, classDefByOrNull(base)!!).forEach { reachable ->
        if (!AccessFlags.PUBLIC.isSet(reachable.accessFlags)) refuse("${reachable.type} isn't public, so the extension can't reach it")
    }

    val stubs = storySeenStubs()
    val queue = retry?.let { findStoryRetryQueue(storeClass, it, storeReader, sessionGetter) }
    return StorySeenTargets(
        batch, store, send.name, retry, getter.name, sessionGetter.definingClass, sessionGetter.name, reels, binder.definingClass,
        binder.name, parameters, session, item, holder, itemView, queue, stubs,
    )
}

/**
 * The one method loading every one of [strings], each either held or asked from a static pool of
 * shared strings, which Redex picks differently per build (451's 385713073 asks a pool for
 * [FORCE_SEEN_KEY]). The pools themselves aren't taken, though they hold the strings.
 */
private fun BytecodePatchContext.methodLoading(what: String, vararg strings: String): MutableMethod {
    val found = classesLoadingString(strings.first()).flatMap { owner ->
        owner.methods.filter { method -> !method.isStringPool() && strings.all { loadsString(method, it) } }
    }
    val method = found.singleOrNull() ?: refuse(
        "expected exactly one $what in this Instagram build, found " +
            if (found.isEmpty()) "none" else found.joinToString { "${it.definingClass}->${it.name}" },
    )
    return mutableClassDefBy(method.definingClass).methods.single { it.sameAs(method) }
}

/**
 * The pending story store's disk reader: the one method loading [PENDING_READ] whose disk key starts
 * with [PENDING_PREFIX]. 451 loads the prefix in the reader itself; 452 moved it into a private
 * `()String` key helper of the store, which the reader calls on its own `this`.
 */
private fun BytecodePatchContext.storeReader(): MutableMethod {
    val found = classesLoadingString(PENDING_READ).flatMap { owner ->
        owner.methods.filter { method ->
            !method.isStringPool() && loadsString(method, PENDING_READ) &&
                (loadsString(method, PENDING_PREFIX) || diskKeyHelpers(owner, method).any { loadsString(it, PENDING_PREFIX) })
        }
    }
    val method = found.singleOrNull() ?: refuse(
        "expected exactly one pending story seen store in this Instagram build, found " +
            if (found.isEmpty()) "none" else found.joinToString { "${it.definingClass}->${it.name}" },
    )
    return mutableClassDefBy(method.definingClass).methods.single { it.sameAs(method) }
}

/** The private instance `()String` methods of [owner] that [method] calls directly. */
private fun diskKeyHelpers(owner: ClassDef, method: Method): List<Method> = method.code().mapNotNull { instruction ->
    val called = instruction.methodReference()?.takeIf { instruction.opcode in DIRECT_INVOKES } ?: return@mapNotNull null
    owner.methods.singleOrNull { it.sameAs(called) }?.takeIf {
        AccessFlags.PRIVATE.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.isEmpty() && it.returnType == STRING
    }
}.distinct()

/** A static `(I)String` method: the shape of Redex's pools of shared strings. */
private fun Method.isStringPool() = AccessFlags.STATIC.isSet(accessFlags) && returnType == STRING &&
    parameterTypes.map(Any::toString) == listOf("I")

/**
 * Refuses unless the batch can't be reached under another name: final, so no class extends it and
 * inherits the request, extending Object, whose constructor puts nothing in it, and implementing no
 * interface that names the request.
 */
private fun BytecodePatchContext.requireSealedBatch(batchClass: ClassDef, request: Method) {
    val batch = batchClass.type
    if (batchClass.superclass != OBJECT) refuse("$batch extends ${batchClass.superclass}, whose constructor the patch doesn't follow")
    if (!AccessFlags.FINAL.isSet(batchClass.accessFlags)) {
        refuse("$batch isn't final, so a class extending it could build the seen request under its own name")
    }
    for (type in batchClass.interfaces) {
        if (classDefByOrNull(type)?.methods?.any { it.name == request.name && it.parameterTypes.map(Any::toString) == request.parameterTypes.map(Any::toString) } == true) {
            refuse("$batch's seen request is also $type->${request.name}, which the patch can't follow")
        }
    }
}

/** Refuses unless [register], where a hook moves its answer, branches on it and casts it, is one a byte names. */
private fun requireByteRegister(where: String, register: Int) {
    if (register > 255) refuse("$where keeps its batch in v$register, past v255, which the hook's move-result, branch and cast can't name")
}

/**
 * Refuses unless the send only reads the batch it's handed before the request: everything naming
 * the batch's register but the request's build is a null check, or a call of one of the batch's
 * own methods that changes nothing ([readOnly]) with the batch only as its receiver. The hook's
 * answer takes the batch's place before any of it, so nothing is added to it on the way.
 */
private fun requireSendLeavesItsBatchAlone(send: Method, build: Int, batchClass: ClassDef) {
    val where = "${send.definingClass}->${send.name}"
    val register = send.parameterRegisterNumber(0)
    send.code().forEachIndexed { index, instruction ->
        val named = instruction.namedRegisters()
        if (index == build || register !in named) return@forEachIndexed
        val called = instruction.methodReference()
        val reads = when {
            instruction.opcode == Opcode.IF_EQZ || instruction.opcode == Opcode.IF_NEZ -> true
            instruction.opcode in VIRTUAL_INVOKES && called != null && called.definingClass == batchClass.type &&
                named.first() == register && named.count { it == register } == 1 ->
                batchClass.methods.singleOrNull { it.sameAs(called) }?.let { readOnly(it) } == true
            else -> false
        }
        if (!reads) {
            refuse("$where hands its batch to ${called ?: instruction.opcode.name} at instruction $index, before the request, which may change what it sends")
        }
    }
}

/**
 * Whether [method], one of the batch's own, only reads: it writes no field or array, uses `this`
 * only to read its fields, and calls nothing but isEmpty() and size() of a java.util collection.
 */
private fun readOnly(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.implementation == null) return false
    val self = method.localRegisterCount()
    return method.code().all { instruction ->
        val opcode = instruction.opcode
        val called = instruction.methodReference()
        when {
            opcode.kind("iput") || opcode.kind("sput") || opcode.kind("aput") -> false
            opcode.kind("invoke") ->
                opcode in VIRTUAL_INVOKES && called?.query() == true && self !in instruction.namedRegisters()
            self in instruction.namedRegisters() ->
                opcode in IGETS && (instruction as TwoRegisterInstruction).registerB == self && instruction.registerA != self
            else -> true
        }
    }
}

/**
 * The batch's map of stories: the seen request names [REELS_KEY] once, and the very next call adds
 * a String under it, which a static `(Map)String` made straight before from a map field of `this`.
 * No branch lands between that read and the call, so it's the only way the value gets there.
 */
private fun storiesField(request: Method, batch: String): Stories {
    val where = "$batch->${request.name}"
    if (AccessFlags.STATIC.isSet(request.accessFlags)) refuse("$where, the seen request, is static")
    val code = request.code()
    val named = code.indices.filter { code[it].string() == REELS_KEY }
    val at = named.singleOrNull() ?: refuse("expected $where to name \"$REELS_KEY\" once, found ${named.size}")
    val key = (code[at] as OneRegisterInstruction).registerA
    val put = code.getOrNull(at + 1)
    val added = put?.methodReference()
    val putRegisters = put?.namedRegisters().orEmpty()
    if (put == null || added == null || put.opcode != Opcode.INVOKE_VIRTUAL || added.returnType != "V" ||
        added.parameterTypes.map(Any::toString) != listOf(STRING, STRING) || putRegisters.size != 3 || putRegisters[1] != key
    ) {
        refuse("$where doesn't add the stories under \"$REELS_KEY\" right after naming them")
    }
    val value = putRegisters[2]
    val made = (at - 1 downTo 0).firstOrNull { code[it].writes(value) }
        ?.takeIf { code[it].opcode == Opcode.MOVE_RESULT_OBJECT }
        ?: refuse("$where doesn't make what it sends under \"$REELS_KEY\" from a map")
    val serializer = code.getOrNull(made - 1)
    val serialized = serializer?.methodReference()
    if (serializer == null || serialized == null || serializer.opcode != Opcode.INVOKE_STATIC ||
        serialized.parameterTypes.map(Any::toString) != listOf(MAP) || serialized.returnType != STRING
    ) {
        refuse("$where doesn't make what it sends under \"$REELS_KEY\" from a map")
    }
    val map = serializer.namedRegisters().single()
    val read = (made - 2 downTo 0).firstOrNull { code[it].writes(map) }
    val field = read?.let { code[it].fieldReference() }
    if (read == null || field == null || code[read].opcode != Opcode.IGET_OBJECT || field.definingClass != batch ||
        field.type !in MAP_TYPES || (code[read] as TwoRegisterInstruction).registerB != request.localRegisterCount()
    ) {
        refuse("$where doesn't read the stories it sends under \"$REELS_KEY\" from a map of its own batch")
    }
    if (request.jumpTargets().any { it in read + 1..at + 1 }) {
        refuse("a branch in $where lands between its read of the stories and where it adds them under \"$REELS_KEY\"")
    }
    request.requireThisIntact(PATCH, listOf(read))
    return Stories(field, at + 1)
}

/**
 * Refuses unless a batch made by the public `<init>()V` holds nothing the seen request sends, so a
 * batch the extension starts and gives only the stories you marked sends those and nothing else.
 * Answers the constructors that proof covers: `<init>()V` and those it runs.
 */
private fun requireFreshBatchIsEmpty(request: Method, batchClass: ClassDef, reels: FieldReference): List<Method> {
    val batch = batchClass.type
    val where = "$batch->${request.name}"
    val code = request.code()
    val self = request.localRegisterCount()
    val reads = code.indices.filter { code[it].opcode in IGETS && code[it].fieldReference()?.definingClass == batch }
    code.forEachIndexed { index, instruction ->
        if (self !in instruction.namedRegisters()) return@forEachIndexed
        if (index !in reads || (instruction as TwoRegisterInstruction).registerB != self) {
            refuse("$where hands its batch on at instruction $index, so what it sends can't be told from the batch's fields")
        }
    }
    request.requireThisIntact(PATCH, reads)
    val sent = reads.map { code[it].fieldReference()!! }.distinctBy { it.toString() }

    val start = batchClass.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.isEmpty() }
    if (start == null || !AccessFlags.PUBLIC.isSet(start.accessFlags)) {
        refuse("$batch has no public constructor taking nothing, so the extension can't start a batch of its own")
    }
    val constructors = mutableListOf<Method>()
    var next: List<Method> = listOf(start)
    while (next.isNotEmpty()) {
        if (constructors.size > 8) refuse("$batch's constructors call each other more than the patch follows")
        constructors += next
        next = next.flatMap { constructor -> chained(constructor, batchClass) }.filter { it !in constructors }
    }

    val written = mutableMapOf<String, MutableList<Boolean>>()
    for (constructor in constructors) {
        val body = constructor.code()
        val own = constructor.localRegisterCount()
        val jumps = constructor.jumpTargets()
        val named = body.indices.filter { own in body[it].namedRegisters() }
        constructor.requireThisIntact(PATCH, named)
        for (index in named) {
            val instruction = body[index]
            val called = instruction.methodReference()
            val field = instruction.fieldReference()
            val registers = instruction.namedRegisters()
            val chains = instruction.opcode in DIRECT_INVOKES && called?.name == "<init>" &&
                (called.definingClass == batch || called.definingClass == batchClass.superclass) &&
                registers.first() == own && registers.count { it == own } == 1
            val onSelf = (instruction.opcode in IPUTS || instruction.opcode in IGETS) && field != null &&
                (instruction as TwoRegisterInstruction).registerB == own && instruction.registerA != own
            if (!chains && !onSelf) {
                refuse("$batch's constructor hands the batch on at instruction $index, so a new batch may not start empty")
            }
            if (instruction.opcode !in IPUTS || field == null || sent.none { it.toString() == field.toString() }) continue
            val stored = (instruction as TwoRegisterInstruction).registerA
            val fresh = when (freshValue(body, index, stored, jumps, own)) {
                Fresh.NOTHING -> false
                Fresh.EMPTY -> true
                null -> refuse(
                    "$batch's constructor starts ${field.name}, which the seen request sends, as something other " +
                        "than a new empty collection or nothing",
                )
            }
            written.getOrPut(field.toString()) { mutableListOf() } += fresh
        }
    }
    val stories = written[reels.toString()]
    if (stories.isNullOrEmpty() || !stories.all { it }) {
        refuse("$batch's constructor doesn't start ${reels.name}, its stories, as a new empty map")
    }
    return constructors
}

/** The constructors of [batchClass] that [constructor] runs on its own `this`. */
private fun chained(constructor: Method, batchClass: ClassDef): List<Method> {
    val own = constructor.localRegisterCount()
    return constructor.code().mapNotNull { instruction ->
        val called = instruction.methodReference() ?: return@mapNotNull null
        if (called.definingClass != batchClass.type || called.name != "<init>" || instruction.namedRegisters().firstOrNull() != own) {
            return@mapNotNull null
        }
        batchClass.methods.singleOrNull { it.name == "<init>" && it.sameAs(called) }
    }
}

private enum class Fresh { NOTHING, EMPTY }

/**
 * What the [register] that instruction [at] stores holds: nothing (a zero loaded on a straight run
 * to it), or a new empty collection the register holds and does nothing else with but go into
 * fields of [own], the batch being made, or neither.
 */
private fun freshValue(code: List<Instruction>, at: Int, register: Int, jumps: Set<Int>, own: Int): Fresh? {
    val loaded = (at - 1 downTo 0).firstOrNull { code[it].writes(register) } ?: return null
    val load = code[loaded]
    if (load.opcode in ZERO_CONSTS && (load as NarrowLiteralInstruction).narrowLiteral == 0) {
        return if (jumps.none { it in loaded + 1..at }) Fresh.NOTHING else null
    }
    if (load.opcode != Opcode.NEW_INSTANCE) return null
    val type = ((load as ReferenceInstruction).reference as TypeReference).type
    if (type !in FRESH_TYPES) return null
    val uses = code.indices.filter { register in code[it].namedRegisters() }
    val writes = uses.filter { code[it].writes(register) }
    val starts = uses.filter { index ->
        val called = code[index].methodReference()
        code[index].opcode == Opcode.INVOKE_DIRECT && called?.definingClass == type && called.name == "<init>" &&
            called.parameterTypes.isEmpty() && code[index].namedRegisters() == listOf(register)
    }
    val stores = uses.filter {
        code[it].opcode in IPUTS && (code[it] as TwoRegisterInstruction).let { put -> put.registerA == register && put.registerB == own }
    }
    if (writes != listOf(loaded) || starts.size != 1 || starts.single() < loaded || uses.toSet() != setOf(loaded) + starts + stores) {
        return null
    }
    return Fresh.EMPTY
}

/**
 * Refuses unless what the seen request sends is made of its batch's fields and constants. The
 * request starts its builder with one call, the one whose result the stories are added to. A static
 * field it reads goes only into that call, or into a setting of the builder taking a number or a
 * flag; a parameter of the request (the account, on Instagram 449) goes only into that call. The
 * static methods it calls are the batch's own `(Map)String` serializers, String.valueOf and string
 * pools asked for a constant (451's 385713073 asks two for key names), and any other call is that
 * start, a call on the builder, or isEmpty() or size() of a collection.
 */
private fun BytecodePatchContext.requireRequestSendsOnlyItsBatch(request: Method, batch: String, add: Int) {
    val where = "$batch->${request.name}"
    val code = request.code()
    val builder = code[add].namedRegisters().first()
    val made = code.indices.filter { code[it].writes(builder) }.singleOrNull()
        ?.takeIf { it > 0 && code[it].opcode == Opcode.MOVE_RESULT_OBJECT && code[it - 1].methodReference() != null }
        ?: refuse("$where doesn't start what it adds the stories to with one call")
    val start = made - 1
    val onBuilder = request.literalReads(made).filter { index ->
        code[index].methodReference() != null && code[index].opcode !in STATIC_INVOKES &&
            code[index].namedRegisters().firstOrNull() == builder
    }.toSet()

    fun setting(index: Int, register: Int): Boolean {
        val called = code[index].methodReference() ?: return false
        return index in onBuilder && code[index].namedRegisters().indexOf(register) > 0 &&
            called.parameterTypes.all { it.toString() in SETTING_TYPES }
    }

    code.forEachIndexed { index, instruction ->
        val opcode = instruction.opcode
        val called = instruction.methodReference()
        when {
            opcode.kind("sget") -> {
                val field = instruction.fieldReference()
                val register = (instruction as OneRegisterInstruction).registerA
                if (opcode == Opcode.SGET_WIDE || request.literalReads(index).any { it != start && !setting(it, register) }) {
                    refuse("$where reads $field into what it sends, which its batch doesn't hold")
                }
            }
            opcode.kind("invoke") && called == null -> refuse("$where makes a call the patch can't follow at instruction $index")
            called != null && opcode in STATIC_INVOKES -> {
                val serializer = called.definingClass == batch && called.parameterTypes.map(Any::toString) == listOf(MAP) &&
                    called.returnType == STRING
                val pooled = stringLoadedAt(code, index) != null
                if (!serializer && !pooled && called.toString() != VALUE_OF) refuse("$where calls $called, which may send what its batch doesn't hold")
            }
            called != null -> {
                if (index != start && index !in onBuilder && !(opcode in VIRTUAL_INVOKES && called.query())) {
                    refuse("$where calls $called, which may send what its batch doesn't hold")
                }
            }
        }
    }

    var register = request.localRegisterCount() + 1
    request.parameterTypes.forEachIndexed { position, type ->
        val width = if (type.toString() == "J" || type.toString() == "D") 2 else 1
        val reads = (register until register + width).flatMap { request.entryReads(it) }
        if (reads.any { it != start }) refuse("$where reads its parameter $position into what it sends, which its batch doesn't hold")
        register += width
    }
}

/**
 * Refuses unless every call of the seen request in the app is one of three: the store's send, which
 * the patch hooks first thing; the store's rebuild of a batch it retries, which the patch hooks
 * right before the call ([retryOf]); or a route handing the request a batch it made right there
 * with only constants in it ([freshRoute]). Answers the retry, or null without one.
 *
 * On Instagram 449 the request is called from those three: PendingReelSeenStateStore's send, its
 * retry of what an earlier session saved to disk, and the Reset NUX developer option, which sends
 * a NUX seen in a batch of its own and no story.
 */
private fun BytecodePatchContext.requireEveryRouteToTheRequest(
    request: Method,
    batch: String,
    store: String,
    send: Method,
    constructors: List<Method>,
): StoreRetry? {
    val storeClass = classDefBy(store)
    val owner = storeClass.superclass?.let { classDefByOrNull(it) }
    val builders = owner?.methods?.filter { AccessFlags.ABSTRACT.isSet(it.accessFlags) &&
        it.parameterTypes.map(Any::toString) == listOf(OBJECT) && it.returnType.startsWith("L") }.orEmpty()
    if (storeClass.methods.any { method ->
            (AccessFlags.NATIVE.isSet(method.accessFlags) || AccessFlags.ABSTRACT.isSet(method.accessFlags)) && builders.any {
                it.name == method.name && it.returnType == method.returnType &&
                    it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
            }
        }) refuse("retry bridge has no executable DEX body")
    val callers = mutableListOf<Pair<Method, Int>>()
    // A call to the request names its class and name, which the patcher's index keeps, so only the
    // classes it names are read, in the app's order. Reading every class was a measured share of
    // patching (#173).
    val calling = classesCalling(request.definingClass, request.name).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT) || classDef.type !in calling) return@classDefForEach
        for (method in classDef.methods) {
            method.implementation?.instructions?.forEachIndexed { index, instruction ->
                if (instruction.methodReference()?.sameAs(request) == true) callers += method to index
            }
        }
    }
    val retries = mutableListOf<StoreRetry>()
    for ((method, build) in callers) {
        if (method.definingClass == store && method.sameAs(send)) continue
        val retry = retryOf(method, build, store, batch)
        if (retry != null) {
            retries += retry
            continue
        }
        if (freshRoute(method, build, batch, constructors)) continue
        refuse(
            "${method.definingClass}->${method.name} builds the seen request at instruction $build from a batch that " +
                "doesn't go through $store->${send.name}",
        )
    }
    if (retries.size > 1) refuse("expected $store to rebuild a batch it retries in one place at most, found ${retries.size}")
    return retries.singleOrNull()
}

/**
 * The store's rebuild of a batch it retries, when [method] is one: an instance method of the store
 * taking one parameter, which builds the seen request at [build] from that parameter. Refuses one
 * the hook can't go into: a parameter written over by anything but a cast to the batch, one past
 * v255, a branch landing on the build, `this` written over first, or the batch read after the build.
 */
private fun retryOf(method: Method, build: Int, store: String, batch: String): StoreRetry? {
    if (method.definingClass != store || AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.size != 1) return null
    val register = method.parameterRegisterNumber(0)
    val named = method.code()[build].namedRegisters()
    if (named.firstOrNull() != register || named.count { it == register } != 1) return null
    val where = "$store->${method.name}"
    val code = method.code()
    if (code.any { it.writes(register) && !(it.opcode == Opcode.CHECK_CAST && it.typeReference() == batch) }) {
        refuse("$where writes over the batch it rebuilds with something other than a cast to it")
    }
    requireByteRegister(where, register)
    if (build in method.jumpTargets()) refuse("a branch in $where lands on its build of the seen request, where the hook goes")
    method.requireThisIntact(PATCH, listOf(build))
    if (method.readsAfter(build, register).isNotEmpty()) refuse("$where reads its batch again after building the seen request")
    return StoreRetry(method.name, method.parameterTypes.map(Any::toString), build, register)
}

/**
 * Whether [method] builds the seen request, at [build], from a batch it makes right there: a new
 * one from a constructor [requireFreshBatchIsEmpty] proved starts it empty, on a straight run no
 * branch lands in, handled only by that constructor, by reads of its fields and by the build. Each
 * field it reads only has constants added to it, so nothing that batch sends names a story you
 * watched.
 */
private fun freshRoute(method: Method, build: Int, batch: String, constructors: List<Method>): Boolean {
    val code = method.code()
    val handed = code[build].namedRegisters()
    val receiver = handed.firstOrNull() ?: return false
    if (handed.count { it == receiver } != 1) return false
    val made = (build - 1 downTo 0).firstOrNull { code[it].writes(receiver) } ?: return false
    if (code[made].opcode != Opcode.NEW_INSTANCE || code[made].typeReference() != batch) return false
    if (method.jumpTargets().any { it in made + 1..build }) return false
    var started = false
    for (index in made + 1 until build) {
        val instruction = code[index]
        val named = instruction.namedRegisters()
        if (receiver !in named) continue
        val called = instruction.methodReference()
        if (!started && instruction.opcode in DIRECT_INVOKES && called != null && called.name == "<init>" &&
            named.first() == receiver && named.count { it == receiver } == 1 && constructors.any { it.sameAs(called) }
        ) {
            started = true
            continue
        }
        if (!started || instruction.opcode != Opcode.IGET_OBJECT || instruction.fieldReference()?.definingClass != batch) return false
        val read = instruction as TwoRegisterInstruction
        if (read.registerB != receiver || read.registerA == receiver || !onlyConstantsInto(code, index, read.registerA, build, made)) return false
    }
    return started
}

/**
 * Whether what the read at [at] put in [register] has nothing done with it before [build] but
 * constants put or added into it, each loaded after [from] on the same straight run, until the
 * register is written again.
 */
private fun onlyConstantsInto(code: List<Instruction>, at: Int, register: Int, build: Int, from: Int): Boolean {
    for (index in at + 1 until build) {
        val instruction = code[index]
        val named = instruction.namedRegisters()
        if (register !in named) continue
        if (instruction.writes(register) && named.count { it == register } == 1 && instruction.methodReference() == null) return true
        val called = instruction.methodReference() ?: return false
        if (instruction.opcode !in VIRTUAL_INVOKES || named.first() != register || named.count { it == register } != 1 ||
            called.name !in ADDS || !called.definingClass.startsWith("Ljava/util/")
        ) {
            return false
        }
        if (named.drop(1).any { argument -> !constantAt(code, index, argument, from) }) return false
    }
    return register !in code[build].namedRegisters()
}

/** Whether [register] at [at] holds a constant loaded after [from], on the straight run between them. */
private fun constantAt(code: List<Instruction>, at: Int, register: Int, from: Int): Boolean {
    val loaded = (at - 1 downTo from + 1).firstOrNull { code[it].writes(register) } ?: return false
    return code[loaded].opcode in CONSTANTS
}

/**
 * The public getter of the account the store sends for, the instance method taking nothing that
 * answers a [USER_SESSION], declared on the store or the closest class it extends that has one.
 */
private fun BytecodePatchContext.sessionGetter(storeClass: ClassDef): Method {
    var type: String? = storeClass.type
    repeat(8) {
        val classDef = type?.takeIf { it != OBJECT }?.let { classDefByOrNull(it) } ?: return@repeat
        val getters = classDef.methods.filter {
            !AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                it.parameterTypes.isEmpty() && it.returnType == USER_SESSION
        }
        if (getters.size > 1) refuse("expected ${classDef.type} to have one public getter of its account, found ${getters.size}")
        val getter = getters.singleOrNull()
        if (getter != null) {
            if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) refuse("${classDef.type}, which answers the store's account, isn't public")
            return getter
        }
        type = classDef.superclass
    }
    refuse("${storeClass.type} has no public getter of the account it sends for")
}

/** Refuses unless [USER_SESSION] is a class with a public [USER_ID] answering the account's user ID. */
private fun BytecodePatchContext.requireUserId() {
    val session = classDefByOrNull(USER_SESSION) ?: refuse("$USER_SESSION isn't in this build")
    if (AccessFlags.INTERFACE.isSet(session.accessFlags) || !AccessFlags.PUBLIC.isSet(session.accessFlags)) {
        refuse("$USER_SESSION isn't a public class the extension can call $USER_ID() on")
    }
    session.methods.singleOrNull {
        it.name == USER_ID && it.parameterTypes.isEmpty() && it.returnType == STRING &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$USER_SESSION has no public $USER_ID()")
}

/**
 * The public View field of the view holder base that its constructor stores its one parameter in.
 * Hide Follow on stories reads the same field, so [patch] names whichever patch is asking.
 */
internal fun BytecodePatchContext.itemViewField(constructor: Method, patch: String = PATCH): FieldReference {
    fun refuse(detail: String): Nothing = throw PatchException("$patch: $detail")
    val base = constructor.definingClass
    val code = constructor.code()
    val view = constructor.parameterRegisterNumber(0)
    val own = constructor.localRegisterCount()
    val stores = code.indices.filter { index ->
        val field = code[index].fieldReference()
        code[index].opcode == Opcode.IPUT_OBJECT && field?.definingClass == base && field.type == VIEW &&
            (code[index] as TwoRegisterInstruction).let { it.registerA == view && it.registerB == own }
    }
    val fields = stores.map { code[it].fieldReference()!! }.distinctBy { it.toString() }
    val field = fields.singleOrNull() ?: refuse("expected $base's constructor to keep its item view in one field, found ${fields.size}")
    constructor.requireParameterIntact(patch, 0, stores)
    constructor.requireThisIntact(patch, stores)
    val declared = classDefByOrNull(base)?.fields?.singleOrNull { it.name == field.name && it.type == VIEW }
    if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
        refuse("$base->${field.name}, the item view, isn't a public field the extension can read")
    }
    return field
}

private fun BytecodePatchContext.storySeenStubs(): StorySeenStubs {
    val seen = classDefByOrNull(STORY_SEEN)?.let { mutableClassDefBy(STORY_SEEN) } ?: refuse("$STORY_SEEN isn't in the extension")
    val button = classDefByOrNull(STORY_SEEN_BUTTON)?.let { mutableClassDefBy(STORY_SEEN_BUTTON) }
        ?: refuse("$STORY_SEEN_BUTTON isn't in the extension")
    fun stub(owner: String, methods: Iterable<MutableMethod>, name: String, parameters: List<String>, returns: String) =
        methods.singleOrNull {
            it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypes.map(Any::toString) == parameters
        } ?: refuse("$owner has no static $returns $name(${parameters.joinToString("")})")
    for ((owner, methods, hook) in listOf(
        Triple(STORY_SEEN, seen.methods, TO_SEND), Triple(STORY_SEEN, seen.methods, TO_RETRY),
        Triple(STORY_SEEN_BUTTON, button.methods, BIND_BUTTON),
    )) {
        methods.singleOrNull {
            "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook.substringAfter("->") &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: refuse("$owner has no public static hook ${hook.substringAfter("->")}")
    }
    val emptyBatch = stub(STORY_SEEN, seen.methods, "emptyBatch", emptyList(), OBJECT)
    if (emptyBatch.localRegisterCount() < 1) refuse("$STORY_SEEN->emptyBatch() has no local register to start a batch in")
    return StorySeenStubs(
        emptyBatch = emptyBatch,
        seenStories = stub(STORY_SEEN, seen.methods, "seenStories", listOf(OBJECT), MAP),
        sendBatch = stub(STORY_SEEN, seen.methods, "send", listOf(OBJECT, OBJECT), "V"),
        storeAccount = stub(STORY_SEEN, seen.methods, "storeAccount", listOf(OBJECT), STRING),
        sessionAccount = stub(STORY_SEEN, seen.methods, "sessionAccount", listOf(OBJECT), STRING),
        storyId = stub(STORY_SEEN_BUTTON, button.methods, "storyId", listOf(OBJECT), STRING),
        itemView = stub(STORY_SEEN_BUTTON, button.methods, "itemView", listOf(OBJECT), VIEW),
    )
}

/**
 * Puts the hook first in the send: the extension is handed the store and the batch, and answers
 * the batch to send, Instagram's own when nothing is held back, one of its own holding only the
 * stories you marked, or null, and the send returns before building anything on null. The answer
 * takes the batch's place, so the send's empty check and the request are both made from it.
 */
internal fun BytecodePatchContext.hookStorySend(found: StorySeenTargets) {
    val send = mutableClassDefBy(found.store).methods.single {
        it.name == found.send && it.parameterTypes.map(Any::toString) == listOf(found.batch) && it.returnType == "V"
    }
    val batch = send.parameterRegister(0)
    send.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. $batch }, $TO_SEND
            move-result-object $batch
            if-nez $batch, :send
            return-void
            :send
            check-cast $batch, ${found.batch}
        """,
    )
}

/**
 * Puts the button's hook first in the story binder ([STORY_BINDER_TRACE]), handing it the account signed in, the
 * story and its view holder in three borrowed locals: nothing is in them before the binder's own
 * first instruction, and the parameters may sit past v15.
 */
internal fun BytecodePatchContext.hookStoryHeader(found: StorySeenTargets) {
    val binder = mutableClassDefBy(found.binder).methods.single {
        it.name == found.binderName && it.parameterTypes.map(Any::toString) == found.binderParameters
    }
    binder.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${binder.parameterRegister(found.session)}
            move-object/from16 v1, ${binder.parameterRegister(found.item)}
            move-object/from16 v2, ${binder.parameterRegister(found.holder)}
            invoke-static { v0, v1, v2 }, $BIND_BUTTON
        """,
    )
}

/** Fills the extension's stubs in. Each answers on one path, so none joins two ways at one return. */
internal fun StorySeenTargets.fillStubs() {
    stubs.emptyBatch.addInstructionsWithLabels(
        0,
        """
            new-instance v0, $batch
            invoke-direct { v0 }, $batch-><init>()V
            return-object v0
        """,
    )
    stubs.seenStories.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $batch
            iget-object p0, p0, $reels
            return-object p0
        """,
    )
    stubs.sendBatch.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $USER_SESSION
            invoke-static { p0 }, $store->$getter($USER_SESSION)$store
            move-result-object p0
            check-cast p1, $batch
            invoke-virtual { p0, p1 }, $store->$send($batch)V
            return-void
        """,
    )
    stubs.storeAccount.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $sessionOwner
            invoke-virtual { p0 }, $sessionOwner->$sessionGetter()$USER_SESSION
            move-result-object p0
            invoke-virtual { p0 }, $USER_SESSION->$USER_ID()$STRING
            move-result-object p0
            return-object p0
        """,
    )
    stubs.sessionAccount.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $USER_SESSION
            invoke-virtual { p0 }, $USER_SESSION->$USER_ID()$STRING
            move-result-object p0
            return-object p0
        """,
    )
    stubs.storyId.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $REEL_ITEM
            invoke-virtual { p0 }, $REEL_ITEM->getId()$STRING
            move-result-object p0
            return-object p0
        """,
    )
    stubs.itemView.addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${itemView.definingClass}
            iget-object p0, p0, $itemView
            return-object p0
        """,
    )
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.typeReference(): String? = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun MethodReference.sameAs(other: MethodReference): Boolean =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map(Any::toString) == other.parameterTypes.map(Any::toString)

/**
 * Whether the opcode is of the kind its name starts with, such as "invoke" or "sget". Its name reads
 * either way, "invoke-virtual" as smali writes it or INVOKE_VIRTUAL, so it's compared in lower case.
 */
private fun Opcode.kind(prefix: String): Boolean = name.lowercase().startsWith(prefix)

/** A question a java.util collection answers without changing: isEmpty() or size(). */
private fun MethodReference.query(): Boolean = name in QUERIES && parameterTypes.isEmpty() && definingClass.startsWith("Ljava/util/")

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}


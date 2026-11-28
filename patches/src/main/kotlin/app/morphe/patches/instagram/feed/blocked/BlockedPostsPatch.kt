/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.blocked

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.methodsCalling
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

internal const val BLOCKED_PATCH = "Hide posts when you block someone"
internal const val BLOCKED_POSTS = "$EXTENSION_PACKAGE/feed/BlockedPosts;"
internal const val BLOCKED_FEED = "$BLOCKED_POSTS->feed(Ljava/lang/Object;)V"
internal const val BLOCKED_ACCOUNT = "$BLOCKED_POSTS->blocked(Ljava/lang/Object;)V"
internal const val BLOCKED_USER_ID = "$BLOCKED_POSTS->userId(Ljava/lang/Object;)Ljava/lang/String;"
internal const val BLOCKED_REMOVE = "$BLOCKED_POSTS->remove(Ljava/lang/Object;Ljava/lang/String;)V"

/** The trace title Instagram gives the making of Home's list, which only that list's constructor loads. */
internal const val HOME_LIST_TRACE = "MainFeedAdapter:init"

/** The path of the request that blocks an account, which only the request's builder loads. */
internal const val BLOCK_PATH = "friendships/block/%s/"

/**
 * Asked first thing as Instagram shows its message after a block or an unblock, with the message's kind:
 * whether to skip it (#888). Kind 1 is the unblock's, which always shows.
 */
internal const val BLOCK_MESSAGE_SKIPS = "$BLOCKED_POSTS->skipsMessage(I)Z"

/** The kind of message the message method shows after an unblock, the one it compares with first after 0. */
internal const val UNBLOCK_KIND = 1

private const val CONTEXT = "Landroid/content/Context;"
private const val LIST = "Ljava/util/List;"
private const val GET_DRAWABLE = "Landroid/content/Context;->getDrawable(I)Landroid/graphics/drawable/Drawable;"
private const val GET_STRING = "Landroid/content/Context;->getString(I)Ljava/lang/String;"

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING_EQUALS = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"

/**
 * How Home's removal reads each post's owner's id: off the owner's model ([owner], the class declaring
 * [field]), the field holding its fields, asked through [read] for the one numbered [key].
 */
internal data class IdRead(val owner: String, val field: String, val key: Int, val read: String)

/**
 * What the patch found: Home's list ([list]), its constructor ([made]) with the register holding the
 * list right after the call to its superclass's constructor ([listRegister], before instruction
 * [madeAt]), the list's removal of one account's posts ([removal]) and how that reads an owner's id
 * ([id]); the block request's callback ([callback]), the method taking the server's answer
 * ([answered]) and the callback's field holding the blocked account ([account]).
 */
internal class BlockedPostsSite(
    val list: String,
    val made: Method,
    val madeAt: Int,
    val listRegister: Int,
    val removal: String,
    val id: IdRead,
    val callback: String,
    val answered: Method,
    val account: String,
)

/**
 * Where Skip the message after a block goes (#888): the method that shows the message Instagram puts
 * up once a block is sent ([message]), and the call it has made on the block flow's listener as the
 * message goes away ([gone]).
 */
internal class BlockMessageSite(val message: Method, val gone: String)

/**
 * Hide posts when you block someone (#861). Included in the default selection with its switch off.
 *
 * Blocking someone from a post's menu in Home leaves their posts there until Home is refreshed. Home's
 * list has its own removal of one account's posts, which it uses when you mute someone's posts. The
 * patch tells the extension about each Home list as it's made, right after the call to its
 * superclass's constructor, and about the blocked account as the block request's callback takes the
 * server's answer. The extension hands that account's id to each list's removal through two bridges
 * the patch writes: one reads an account's id the way the removal reads each post's owner, the other
 * is the removal. Every refusal happens before anything changes.
 *
 * It also carries Skip the message after you block someone (#888), off to start: the message
 * Instagram shows once a block is sent asks the extension first, and when it says skip, the block
 * flow's listener hears the message went, as it would have, and nothing is shown. The same method shows
 * the message after an unblock, which the extension always lets through. The block request itself is
 * never touched. When the message can't be found, the patch goes in without it.
 */
@Suppress("unused")
val blockedPostsPatch = bytecodePatch(
    name = "Hide posts when you block someone",
    description = "Takes the posts of someone you block out of Home right away, instead of after you " +
        "refresh it, and can skip the message Instagram shows after a block. Both start off. Turn them on " +
        "in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("blockedPosts")
        val message = findBlockMessageOrWarn()
        val site = findBlockedPosts()
        applyBlockedPosts(site)
        message?.let { applyBlockMessage(it) }
        enableStatus("blockedPosts")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$BLOCKED_PATCH: $detail")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.parameters(): List<String> = parameterTypes.map(Any::toString)
private fun Method.reference(): String = "$definingClass->$name(${parameters().joinToString("")})$returnType"
private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.isPublic() = AccessFlags.PUBLIC.isSet(accessFlags)
private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun MethodReference.text(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** The registers an invoke hands its method, in order. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** Whether this instruction puts a value in [register]. */
private fun Instruction.writes(register: Int): Boolean =
    opcode.setsRegister() && this is OneRegisterInstruction && registerA == register

/** The last instruction before [at] that puts a value in [register], walking straight back. */
private fun List<Instruction>.lastWrite(at: Int, register: Int): Instruction? =
    (at - 1 downTo 0).firstOrNull { this[it].writes(register) }?.let { this[it] }

/**
 * Finds Home's list and its removal, the block request's callback, and the extension's hooks, and
 * checks each can be written. Throws, naming what it found, before anything changes.
 */
internal fun BytecodePatchContext.findBlockedPosts(): BlockedPostsSite {
    val extension = classDefByOrNull(BLOCKED_POSTS) ?: refuse("the extension has no $BLOCKED_POSTS")
    listOf(BLOCKED_FEED, BLOCKED_ACCOUNT, BLOCKED_USER_ID, BLOCKED_REMOVE).forEach { wanted ->
        extension.methods.singleOrNull { it.reference() == wanted && it.isStatic() && it.isPublic() }
            ?: refuse("the extension has no public static $wanted")
    }

    // Home's list: the one constructor loading its trace title.
    val makers = classesLoadingString(HOME_LIST_TRACE).flatMap { it.methods }
        .filter { it.name == "<init>" && loadsString(it, HOME_LIST_TRACE) }
    val made = makers.singleOrNull() ?: refuse("expected one constructor loading \"$HOME_LIST_TRACE\", found ${makers.size}")
    val list = classDefBy(made.definingClass)
    val superclass = list.superclass ?: refuse("${list.type} has no superclass")
    val code = made.code()
    val supers = code.indices.filter { at ->
        (code[at].opcode == Opcode.INVOKE_DIRECT || code[at].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            code[at].methodReference()?.let { it.definingClass == superclass && it.name == "<init>" } == true
    }
    val superAt = supers.singleOrNull() ?: refuse("expected ${made.reference()} to call $superclass's constructor once, found ${supers.size}")
    val madeAt = superAt + 1
    if (madeAt >= code.size) refuse("${made.reference()} ends at its call to $superclass's constructor")
    if (madeAt in made.jumpTargets()) refuse("${made.reference()} jumps straight past its call to $superclass's constructor")
    val listRegister = code[superAt].registers().firstOrNull() ?: refuse("${made.reference()} calls $superclass's constructor on nothing")

    // Its removal of one account's posts, and how that reads each post's owner's id.
    val removals = list.methods.filter { !it.isStatic() && it.isPublic() && it.name != "<init>" && it.parameters() == listOf(STRING) && it.returnType == "V" }
    val removal = removals.singleOrNull() ?: refuse("expected one public (String)V method in ${list.type}, found ${removals.size}")
    if (!AccessFlags.PUBLIC.isSet(list.accessFlags)) refuse("${list.type} isn't public, so the extension can't reach it")
    val id = idReadOf(removal)

    // The block request's callback, and the method taking the server's answer.
    val builders = classesLoadingString(BLOCK_PATH).flatMap { it.methods }.filter { loadsString(it, BLOCK_PATH) }
    val builder = builders.singleOrNull() ?: refuse("expected one method loading \"$BLOCK_PATH\", found ${builders.size}")
    if (!builder.isStatic() || !builder.returnType.startsWith("L")) refuse("${builder.reference()} isn't a static request builder")
    val callbacks = methodsCalling(builder.definingClass, builder.name)
        .filter { caller -> caller.code().any { it.methodReference()?.text() == builder.reference() } }
        .flatMap { caller -> callbacksOf(caller, builder.reference()) }
        .toSet()
    val callback = callbacks.singleOrNull() ?: refuse("expected the block request's callers to hand it one callback, found ${callbacks.sorted()}")
    val callbackClass = classDefBy(callback)
    val accounts = callbackClass.fields.filter { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && extendsType(field.type, id.owner)
    }
    val accountField = accounts.singleOrNull() ?: refuse("expected one field of a ${id.owner} in $callback, found ${accounts.size}")
    val account = "${accountField.definingClass}->${accountField.name}:${accountField.type}"
    val answers = callbackClass.methods.filter { method ->
        !method.isStatic() && method.parameters() == listOf(OBJECT) && method.returnType == "V" && method.code().any {
            it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).toString() == account
        }
    }
    val answered = answers.singleOrNull() ?: refuse("expected one (Object)V method of $callback to read $account, found ${answers.size}")
    answered.freeLocalsAt(BLOCKED_PATCH, 0, 1)

    return BlockedPostsSite(list.type, made, madeAt, listRegister, removal.reference(), id, callback, answered, account)
}

/**
 * How this removal reads each post's owner's id: its one call of an interface's (I)String method,
 * whose answer it compares with the id it's handed, on a value read off a field of the owner's model
 * right before, handed a number loaded before that. Refuses anything else, and a model, field or
 * call the extension can't reach.
 */
private fun BytecodePatchContext.idReadOf(removal: Method): IdRead {
    val code = removal.code()
    val compares = code.indices.filter { code[it].methodReference()?.text() == STRING_EQUALS }
    val compare = compares.singleOrNull() ?: refuse("expected ${removal.reference()} to compare strings once, found ${compares.size}")
    val reads = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_INTERFACE && code[at].methodReference()?.let {
            it.returnType == STRING && it.parameterTypes.map(Any::toString) == listOf("I")
        } == true && at < compare
    }
    val readAt = reads.singleOrNull() ?: refuse("expected ${removal.reference()} to read one id before comparing, found ${reads.size}")
    val result = code.getOrNull(readAt + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || (result as OneRegisterInstruction).registerA !in code[compare].registers()) {
        refuse("${removal.reference()} doesn't compare the id it reads")
    }
    val (model, number) = code[readAt].registers()
    val key = code.lastWrite(readAt, number) as? NarrowLiteralInstruction
        ?: refuse("${removal.reference()} doesn't hand its id read a number")
    val fields = code.lastWrite(readAt, model)
    if (fields?.opcode != Opcode.IGET_OBJECT) refuse("${removal.reference()} doesn't read its id off a field")
    val field = (fields as ReferenceInstruction).reference as FieldReference
    val owner = classDefByOrNull(field.definingClass) ?: refuse("the owner's model ${field.definingClass} is missing")
    val read = code[readAt].methodReference()!!
    val reaches = AccessFlags.PUBLIC.isSet(owner.accessFlags) &&
        owner.fields.any { it.name == field.name && it.type == field.type && AccessFlags.PUBLIC.isSet(it.accessFlags) } &&
        classDefByOrNull(read.definingClass)?.let { AccessFlags.PUBLIC.isSet(it.accessFlags) } == true
    if (!reaches) refuse("the extension can't reach $field or ${read.text()}")
    return IdRead(owner.type, field.toString(), (key as NarrowLiteralInstruction).narrowLiteral, read.text())
}

/**
 * The callbacks [caller] hands the request [builder] makes: the request's one-object call right after
 * the builder's answer names the callbacks' base, and each class [caller] gets from a static call or
 * makes that extends that base is one.
 */
private fun BytecodePatchContext.callbacksOf(caller: Method, builder: String): List<String> {
    val code = caller.code()
    val bases = code.indices.filter { code[it].methodReference()?.text() == builder }.mapNotNull { at ->
        val request = (code.getOrNull(at + 1) as? OneRegisterInstruction)?.takeIf { code[at + 1].opcode == Opcode.MOVE_RESULT_OBJECT }?.registerA
            ?: return@mapNotNull null
        code.drop(at + 2).firstOrNull { it.opcode == Opcode.INVOKE_VIRTUAL && it.registers().firstOrNull() == request }
            ?.methodReference()?.takeIf { it.returnType == "V" && it.parameterTypes.size == 1 }
            ?.parameterTypes?.single()?.toString()
    }.toSet()
    val made = code.mapNotNull { instruction ->
        when (instruction.opcode) {
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> instruction.methodReference()?.returnType
            Opcode.NEW_INSTANCE -> ((instruction as ReferenceInstruction).reference as TypeReference).type
            else -> null
        }
    }.toSet()
    return made.filter { type -> classDefByOrNull(type)?.superclass in bases }
}

/** [type] is [ancestor] or extends it. */
private fun BytecodePatchContext.extendsType(type: String, ancestor: String): Boolean {
    var at: String? = type
    while (at != null) {
        if (at == ancestor) return true
        at = classDefByOrNull(at)?.superclass
    }
    return false
}

/**
 * Writes the hooks and the bridges: the list's constructor tells the extension about the list right
 * after its superclass's constructor, the callback's answer tells it about the blocked account first
 * thing, and the bridges read an account's id and call the removal.
 */
internal fun BytecodePatchContext.applyBlockedPosts(site: BlockedPostsSite) {
    val made = editable(site.made)
    val told = if (site.listRegister <= 15) "invoke-static { v${site.listRegister} }, $BLOCKED_FEED"
    else "invoke-static/range { v${site.listRegister} .. v${site.listRegister} }, $BLOCKED_FEED"
    made.addInstructions(site.madeAt, told)

    val answered = editable(site.answered)
    val into = answered.freeLocalsAt(BLOCKED_PATCH, 0, 1).single()
    val self = answered.implementation!!.registerCount - parameterRegisters(answered)
    val read = if (self <= 15) "iget-object v$into, v$self, ${site.account}"
    else "move-object/from16 v$into, v$self\niget-object v$into, v$into, ${site.account}"
    answered.addInstructions(0, """
        $read
        invoke-static { v$into }, $BLOCKED_ACCOUNT
    """)

    rewrite(stub(BLOCKED_USER_ID), 3, """
        check-cast p0, ${site.id.owner}
        iget-object v0, p0, ${site.id.field}
        const v1, ${site.id.key}
        invoke-interface { v0, v1 }, ${site.id.read}
        move-result-object v0
        return-object v0
    """)
    rewrite(stub(BLOCKED_REMOVE), 2, """
        check-cast p0, ${site.list}
        invoke-virtual { p0, p1 }, ${site.removal}
        return-void
    """)
}

/**
 * The message Instagram shows once a block is sent (#888): the one static method taking a context, a
 * listener, a list and a number that the block request's callers call, which loads an icon and a
 * string and branches on nothing but its own arguments. It hands its listener to a holder the message
 * keeps, whose one call on the listener's interface is the call made as the message goes away. The
 * number is the message's kind, and the method compares it with [UNBLOCK_KIND] for the unblock's text.
 */
internal fun BytecodePatchContext.findBlockMessage(): BlockMessageSite {
    val builders = classesLoadingString(BLOCK_PATH).flatMap { it.methods }.filter { loadsString(it, BLOCK_PATH) }
    val builder = builders.singleOrNull() ?: refuse("expected one method loading \"$BLOCK_PATH\", found ${builders.size}")
    val callers = methodsCalling(builder.definingClass, builder.name)
        .filter { caller -> caller.code().any { it.methodReference()?.text() == builder.reference() } }
    val shown = callers.flatMap { it.code() }
        .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { it.methodReference() }
        .filter { call ->
            val types = call.parameterTypes.map(Any::toString)
            call.returnType == "V" && types.size == 4 && types[0] == CONTEXT && types[2] == LIST && types[3] == "I" &&
                classDefByOrNull(types[1])?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
        }
        .map { it.text() }.toSet()
    val reference = shown.singleOrNull()
        ?: refuse("expected the block request's callers to show one message, found ${shown.sorted()}")
    val message = classDefBy(reference.substringBefore("->")).methods.singleOrNull { it.reference() == reference }
        ?: refuse("$reference isn't declared where it's called")
    if (!message.isStatic()) refuse("$reference isn't static")
    val code = message.code()
    if (code.none { it.methodReference()?.text() == GET_DRAWABLE } || code.none { it.methodReference()?.text() == GET_STRING }) {
        refuse("$reference doesn't load an icon and a string, so it isn't the message")
    }
    val dispatch = code.firstOrNull { it.opcode in setOf(Opcode.INSTANCE_OF, Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH) }
    if (dispatch != null) refuse("$reference has a ${dispatch.opcode}, so it may be more than the message")
    if (0 in message.jumpTargets()) refuse("$reference jumps back to its first instruction")

    // The listener's register, the kind's, and the one holder the message hands the listener to.
    val listener = message.parameters()[1]
    val arguments = message.parameters().sumOf { if (it == "J" || it == "D") 2 else 1 }
    val listenerRegister = message.implementation!!.registerCount - arguments + 1
    val kindRegister = listenerRegister + 2
    val unblock = code.indices.any { at ->
        val load = code[at]
        val compare = code.getOrNull(at + 1)
        load.opcode == Opcode.CONST_4 && (load as NarrowLiteralInstruction).narrowLiteral == UNBLOCK_KIND &&
            compare != null && (compare.opcode == Opcode.IF_EQ || compare.opcode == Opcode.IF_NE) &&
            (compare as TwoRegisterInstruction).let {
                setOf(it.registerA, it.registerB) == setOf(kindRegister, (load as OneRegisterInstruction).registerA)
            }
    }
    if (!unblock) refuse("$reference doesn't compare its kind with $UNBLOCK_KIND, so the unblock's message can't be told apart")
    // 386314341 makes the holder through a static (builder, listener, kind) helper on its class, and
    // 386314340 through the holder's own (listener, kind) constructor.
    val holders = code.filter { listenerRegister in it.registers() }
        .filter { call ->
            val reference = call.methodReference() ?: return@filter false
            val types = reference.parameterTypes.map(Any::toString)
            when (call.opcode) {
                Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE ->
                    reference.returnType == "V" && types.size == 3 && types[1] == OBJECT && types[2] == "I"
                Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> reference.name == "<init>" && types == listOf(OBJECT, "I")
                else -> false
            }
        }
        .map { it.methodReference()!!.definingClass }.toSet()
    val holder = holders.singleOrNull() ?: refuse("expected $reference to hand its listener to one holder, found ${holders.sorted()}")
    val calls = classDefBy(holder).methods.flatMap { it.code() }
        .filter { it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_INTERFACE_RANGE }
        .mapNotNull { it.methodReference() }
        .filter { it.definingClass == listener && it.parameterTypes.isEmpty() && it.returnType == "V" }
        .map { it.text() }.toSet()
    val gone = calls.singleOrNull() ?: refuse("expected $holder to make one call on $listener, found ${calls.sorted()}")
    return BlockMessageSite(message, gone)
}

/** [findBlockMessage], or null with a warning, so the patch goes in without Skip the message after you block someone. */
internal fun BytecodePatchContext.findBlockMessageOrWarn(): BlockMessageSite? = try {
    findBlockMessage()
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. $BLOCKED_PATCH goes in without Skip the message after you block someone.")
    null
}

/**
 * Puts the skip first in the message: when the extension says skip, the listener, when there is one,
 * hears the message went, and the method returns before anything is built. Otherwise the message's own
 * first instruction runs as before.
 */
internal fun BytecodePatchContext.applyBlockMessage(site: BlockMessageSite) {
    val message = editable(site.message)
    val answer = message.freeLocalsAt(BLOCKED_PATCH, 0, 1, highest = 255).single()
    message.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p3 .. p3 }, $BLOCK_MESSAGE_SKIPS
            move-result v$answer
            if-eqz v$answer, :shown
            if-eqz p1, :skipped
            invoke-interface/range { p1 .. p1 }, ${site.gone}
            :skipped
            return-void
        """.trimIndent(),
        ExternalLabel("shown", message.getInstruction(0)),
    )
}

/** The registers [method] takes its parameters in, `this` included. */
private fun parameterRegisters(method: Method): Int =
    1 + method.parameters().sumOf { if (it == "J" || it == "D") 2 else 1 }

private fun BytecodePatchContext.editable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single { it.reference() == method.reference() }

private fun BytecodePatchContext.stub(signature: String): MutableMethod =
    mutableClassDefBy(BLOCKED_POSTS).methods.single { it.reference() == signature }

private fun BytecodePatchContext.rewrite(method: MutableMethod, registers: Int, body: String) {
    val replacement = ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
        method.accessFlags, method.annotations, method.hiddenApiRestrictions,
        ImmutableMethodImplementation(registers, emptyList(), null, null)).toMutable().apply {
        addInstructions(0, body.trimIndent())
    }
    val owner = mutableClassDefBy(method.definingClass)
    owner.methods.remove(method)
    owner.methods.add(replacement)
}

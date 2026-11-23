/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.methodsCalling
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.media.resume.PRODUCT_TYPE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SHUT_PATCH = "Swipe through a chat's reels"
internal const val MAY_OPEN = "$CHAT_REELS->mayOpen(Ljava/lang/Object;)Z"

private const val TEXT = "Ljava/lang/String;"
private const val PARSED_URI = "Landroid/net/Uri;"
private const val ANY_LIST = "Ljava/util/List;"
private const val CARD_LIST = "Lcom/google/common/collect/ImmutableList;"

/**
 * Where Don't open reels from chats (#810) goes in, and how the extension reads what was tapped.
 * [opener] is the opener of media shared in a chat (LX/0aoY;->A01 on 456's 386214574), which looks
 * the tapped message's wrapper up by its id ([wrapper] holds it from [index]) before it opens
 * anything. Instagram's own test there for a reel reads the message ([message]), its shared media
 * ([media]) and that media's product type name ([product], "clips" for a reel), and for a share
 * with no media, the link of its first share card: [cards] lists them, [firstCard] picks the first
 * and [link] is its address. [scratch] is a local free at [index].
 */
internal class ChatReelOpener(
    val opener: MutableMethod,
    val index: Int,
    val wrapper: Int,
    val scratch: Int,
    val message: FieldReference,
    val media: FieldReference,
    val product: MethodReference,
    val cards: MethodReference,
    val firstCard: MethodReference,
    val link: FieldReference,
)

private fun refuseShut(detail: String): Nothing = throw PatchException("$SHUT_PATCH: $detail")

private fun Instruction.calledMethod(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.readField(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun MethodReference.takesOnly(vararg types: String) = parameterTypes.map(CharSequence::toString) == types.toList()

/**
 * Finds the chat's opener and Instagram's reel test in it before anything changes. Of the methods
 * that ask the chat reels test [read], it's the one static method handed the chat and the message
 * that looks the message's wrapper, the type [collector] reads ([ReelCollector.message]'s owner), up
 * by a string and then reads a [MEDIA] off the wrapper's message, asks it for a string and turns that
 * into a [PRODUCT_TYPE]; and for no media, asks the message for a list, picks a share card from it
 * and parses that card's string field as a [PARSED_URI], next to the strings "reel" and "reels". Every
 * piece has to be public, since the extension's bridges read them. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findChatReelOpener(read: FlagLoad, collector: ReelCollector): ChatReelOpener {
    val wrapper = collector.message.definingClass
    val message = collector.message
    val found = methodsCalling(read.type, read.name).filter { caller ->
        val takes = caller.parameterTypes.map(CharSequence::toString)
        AccessFlags.STATIC.isSet(caller.accessFlags) && caller.returnType == "V" && THREAD_KEY in takes && MESSAGE_ID in takes
    }.mapNotNull { opener -> readOpener(opener, wrapper, message) }
    val (opener, parts) = found.singleOrNull()
        ?: refuseShut("expected one chat opener that looks a message up and tests it for a reel, found ${found.size}")
    val where = "${opener.definingClass}->${opener.name}"
    if (parts.index in opener.jumpTargets()) refuseShut("something in $where jumps to just past its message lookup")
    val scratch = opener.freeLocalsAt(SHUT_PATCH, parts.index, 1, highest = 255).single()

    for ((owner, member, public) in listOf(
        Triple(wrapper, "${message.name}", isPublicField(message)),
        Triple(message.type, "${parts.media.name}", isPublicField(parts.media)),
        Triple(MEDIA, "${parts.product.name}", isPublicMethod(parts.product)),
        Triple(message.type, "${parts.cards.name}", isPublicMethod(parts.cards)),
        Triple(parts.firstCard.definingClass, "${parts.firstCard.name}", isPublicMethod(parts.firstCard)),
        Triple(parts.link.definingClass, "${parts.link.name}", isPublicField(parts.link)),
    )) {
        if (!public) refuseShut("$owner->$member can't be read from the extension")
    }

    val takes = opener.parameterTypes.map(CharSequence::toString)
    val mutable = mutableClassDefBy(opener.definingClass).methods.single {
        it.name == opener.name && it.parameterTypes.map(CharSequence::toString) == takes
    }
    return ChatReelOpener(mutable, parts.index, parts.wrapper, scratch, message, parts.media, parts.product,
        parts.cards, parts.firstCard, parts.link)
}

private class OpenerParts(
    val index: Int,
    val wrapper: Int,
    val media: FieldReference,
    val product: MethodReference,
    val cards: MethodReference,
    val firstCard: MethodReference,
    val link: FieldReference,
)

/** [opener]'s message lookup and reel test, or null when it hasn't both. */
private fun readOpener(opener: Method, wrapper: String, message: FieldReference): Pair<Method, OpenerParts>? {
    val code = opener.implementation?.instructions?.toList() ?: return null
    val lookup = code.indices.firstOrNull { at ->
        val call = code[at].calledMethod()
        code[at].opcode == Opcode.INVOKE_VIRTUAL && call != null && call.returnType == wrapper && call.takesOnly(TEXT) &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    } ?: return null
    val held = (code[lookup + 1] as OneRegisterInstruction).registerA

    val typed = code.indices.firstNotNullOfOrNull { at -> productRead(code, at, message) } ?: return null
    val linked = code.indices.firstNotNullOfOrNull { at -> linkRead(code, at, message) } ?: return null
    return opener to OpenerParts(lookup + 2, held, typed.first, typed.second, linked.first, linked.second, linked.third)
}

/** The instruction [at] up to four before [from], newest first. */
private fun before(from: Int) = (from - 1 downTo maxOf(0, from - 4))

/**
 * When [at] turns a string into a [PRODUCT_TYPE]: the [message]'s [MEDIA] field read just before and
 * the media's string getter that answered that string. Null for anything else.
 */
private fun productRead(code: List<Instruction>, at: Int, message: FieldReference): Pair<FieldReference, MethodReference>? {
    val call = code[at].calledMethod() ?: return null
    if (code[at].opcode != Opcode.INVOKE_STATIC || call.definingClass != PRODUCT_TYPE || !call.takesOnly(TEXT) ||
        call.returnType != PRODUCT_TYPE
    ) {
        return null
    }
    val (named, product) = before(at).firstNotNullOfOrNull { near ->
        code[near].calledMethod()?.takeIf {
            code[near].opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == MEDIA && it.parameterTypes.isEmpty() && it.returnType == TEXT
        }?.let { near to it }
    } ?: return null
    val media = before(named).firstNotNullOfOrNull { near ->
        code[near].readField()?.takeIf {
            code[near].opcode == Opcode.IGET_OBJECT && it.definingClass == message.type && it.type == MEDIA
        }
    } ?: return null
    return media to product
}

/**
 * When [at] parses a string as a [PARSED_URI] with "reel" and "reels" loaded just after: the
 * [message]'s list of share cards, the static pick of the first one and the card's string field the
 * parse was handed, read just before. The pick answers the card, or, on 456.1's arm64-v8a and x86_64
 * builds, a shared (List)Object helper whose answer is cast to the card before the read. Null for
 * anything else.
 */
private fun linkRead(code: List<Instruction>, at: Int, message: FieldReference): Triple<MethodReference, MethodReference, FieldReference>? {
    val call = code[at].calledMethod() ?: return null
    if (code[at].opcode != Opcode.INVOKE_STATIC || !call.takesOnly(TEXT) || call.returnType != PARSED_URI) return null
    fun loadsNear(text: String, reach: Int) = (at until minOf(code.size, at + reach)).any { near ->
        code[near].opcode == Opcode.CONST_STRING && (code[near] as ReferenceInstruction).reference.toString() == text
    }
    if (!loadsNear("reel", 12) || !loadsNear("reels", 16)) return null
    val (read, link) = before(at).firstNotNullOfOrNull { near ->
        code[near].readField()?.takeIf { code[near].opcode == Opcode.IGET_OBJECT && it.type == TEXT }?.let { near to it }
    } ?: return null
    fun castToCard(from: Int) = (from + 1 until read).any { near ->
        code[near].opcode == Opcode.CHECK_CAST && (code[near] as ReferenceInstruction).reference.toString() == link.definingClass
    }
    val (picked, firstCard) = before(read).firstNotNullOfOrNull { near ->
        code[near].calledMethod()?.takeIf {
            code[near].opcode == Opcode.INVOKE_STATIC && it.takesOnly(ANY_LIST) &&
                (it.returnType == link.definingClass || it.returnType == "Ljava/lang/Object;" && castToCard(near))
        }?.let { near to it }
    } ?: return null
    val cards = before(picked).firstNotNullOfOrNull { near ->
        code[near].calledMethod()?.takeIf {
            code[near].opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == message.type && it.parameterTypes.isEmpty() &&
                it.returnType == CARD_LIST
        }
    } ?: return null
    return Triple(cards, firstCard, link)
}

private fun BytecodePatchContext.isPublicField(field: FieldReference): Boolean {
    val owner = classDefByOrNull(field.definingClass) ?: return false
    val declared = owner.fields.singleOrNull { it.name == field.name && it.type == field.type } ?: return false
    return AccessFlags.PUBLIC.isSet(owner.accessFlags) && AccessFlags.PUBLIC.isSet(declared.accessFlags)
}

private fun BytecodePatchContext.isPublicMethod(method: MethodReference): Boolean {
    val owner = classDefByOrNull(method.definingClass) ?: return false
    val declared = owner.methods.singleOrNull {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    } ?: return false
    return AccessFlags.PUBLIC.isSet(owner.accessFlags) && AccessFlags.PUBLIC.isSet(declared.accessFlags)
}

/**
 * Has the chat's opener ask the extension through [MAY_OPEN], with the tapped message's wrapper,
 * right after it looks the wrapper up and before it opens anything. A no returns, so the reel stays
 * shut; a yes, a missing wrapper and everything else go on to Instagram. Also writes the bodies of
 * the extension's `sharedProduct` and `shareLink` bridges from the reads Instagram's own reel test
 * makes there, each answering null where a piece is missing.
 */
internal fun BytecodePatchContext.keepChatReelsShut(found: ChatReelOpener) {
    val opener = found.opener
    opener.addInstructionsWithLabels(
        found.index,
        """
            invoke-static/range { v${found.wrapper} .. v${found.wrapper} }, $MAY_OPEN
            move-result v${found.scratch}
            if-nez v${found.scratch}, :instagram
            return-void
        """,
        ExternalLabel("instagram", opener.getInstruction(found.index)),
    )
    val stubs = mutableClassDefBy(CHAT_REELS).methods
    fun bridge(name: String) = stubs.singleOrNull {
        it.name == name && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") && it.returnType == TEXT &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuseShut("the extension has no public static $name bridge")
    val message = found.message
    // A pick answering Object (456.1's 64-bit builds) hands the card over uncast, so the bridge casts it as Instagram does.
    val castCard = if (found.firstCard.returnType == found.link.definingClass) "" else "check-cast p0, ${found.link.definingClass}"
    bridge("sharedProduct").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${message.definingClass}
            iget-object p0, p0, $message
            if-eqz p0, :none
            iget-object p0, p0, ${found.media}
            if-eqz p0, :none
            invoke-virtual { p0 }, ${found.product}
            move-result-object p0
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """.trimIndent(),
    )
    bridge("shareLink").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${message.definingClass}
            iget-object p0, p0, $message
            if-eqz p0, :none
            invoke-virtual { p0 }, ${found.cards}
            move-result-object p0
            if-eqz p0, :none
            invoke-static { p0 }, ${found.firstCard}
            move-result-object p0
            if-eqz p0, :none
            $castCard
            iget-object p0, p0, ${found.link}
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """.trimIndent(),
    )
}

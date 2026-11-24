/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.buttonlinks

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.classesReadingType
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import java.util.BitSet

private const val PATCH = "Copy links from chat buttons"
internal const val CHAT_BUTTON_LINKS = "$EXTENSION_PACKAGE/direct/ChatButtonLinks;"
internal const val BUTTON_ROWS = "$CHAT_BUTTON_LINKS->rows(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;"
internal const val BUTTON_PICKED = "$CHAT_BUTTON_LINKS->picked(Landroid/app/Activity;Ljava/lang/Object;Ljava/lang/String;)Z"

/** The kind of card button Instagram turns into a row of a held message's menu, compared where it makes them. */
internal const val LONG_PRESS_BUTTON = "xma_long_press_action"

/** The key the buttons under a text message are parsed from. */
internal const val TEXT_BUTTONS_KEY = "mustache_ctas"

/** The name the menu's card row action carries in the enum of a held message's actions. */
internal const val CARD_ROW_ACTION = "xma_action"

internal const val MESSAGE_ACTIONS = "Lcom/instagram/direct/messagethread/messageactions/model/MessageActionsViewModel;"
private const val THREAD_KEY = "Lcom/instagram/model/direct/DirectThreadKey;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val LIST = "Ljava/util/List;"
private const val ITERABLE = "Ljava/lang/Iterable;"
private const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val ENUM = "Ljava/lang/Enum;"

/** How far after the text buttons' key the parser may store them: 24 instructions on 386215122. */
private const val PARSE_REACH = 60

/**
 * Copy link in the menu you get when you hold a chat message with link buttons (#852). Included in
 * the default selection with its switch off.
 *
 * Instagram makes a model for a held message's menu, and that model carries a list of extra card
 * rows, which it fills from the card's buttons of one kind ([LONG_PRESS_BUTTON]). Right before the
 * model copies that list, the patch hands it and the message to the extension, which can add a Copy
 * link row made with the same row model, so the menu shows it like a card row whatever menu style
 * the build draws. A card row's tap reaches the chat's dispatcher for the menu with the row's text,
 * and the dispatcher asks the extension first, which handles a Copy link row and has the dispatcher
 * return. The extension reads the message's buttons, under a text and on its cards, through stubs
 * the patch fills with the reads the model's own code makes. Every refusal happens before anything
 * changes.
 */
@Suppress("unused")
val chatButtonLinksPatch = bytecodePatch(
    name = "Copy links from chat buttons",
    description = "Adds Copy link to the menu you get when you hold a chat message with link buttons, like the " +
        "ones automated accounts send. It copies where a button goes without opening it. Starts off. Turn it on " +
        "in HushGram settings > Messages.",
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("chatButtonLinks")
        val site = findChatButtonLinks()
        applyChatButtonLinks(site)
        enableStatus("chatButtonLinks")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Everything the patch changes and reads. The menu model's maker is [builderType]->[builderName]:
 * at [hookAt] it tests the card row list in [list] right before copying it into the model, jumping
 * to [skipTo] when there is none, with the message in [message]. The reads are the message's
 * [cards], a card's [cardButtons], a button's [title] and [url], the [textButtons] under a text
 * message and the card row model's constructor [row]. The dispatcher is [dispatcherType]->
 * [dispatcherName], whose class keeps the chat's [activity].
 */
internal class ButtonLinkSite(
    val builderType: String,
    val builderName: String,
    val builderParameters: List<String>,
    val builderReturns: String,
    val hookAt: Int,
    val list: Int,
    val message: Int,
    val skipTo: Int,
    val messageType: String,
    val cards: MethodReference,
    val cardType: String,
    val cardButtons: MethodReference,
    val buttonType: String,
    val title: FieldReference,
    val url: FieldReference,
    val row: MethodReference,
    val textButtons: FieldReference,
    val dispatcherType: String,
    val dispatcherName: String,
    val dispatcherParameters: List<String>,
    val activity: FieldReference,
)

/** The stubs the patch fills: name, parameter types and return type. */
internal val BUTTON_STUBS = listOf(
    Triple("textButtons", listOf(OBJECT), LIST),
    Triple("cards", listOf(OBJECT), LIST),
    Triple("cardButtons", listOf(OBJECT), LIST),
    Triple("buttonTitle", listOf(OBJECT), STRING),
    Triple("buttonUrl", listOf(OBJECT), STRING),
    Triple("row", listOf(STRING, INTEGER, STRING), OBJECT),
)

/** Throws unless the extension has both hooks and every stub. */
internal fun BytecodePatchContext.requireButtonLinkHooks() {
    val extension = classDefByOrNull(CHAT_BUTTON_LINKS) ?: refuse("the extension has no $CHAT_BUTTON_LINKS")
    for (hook in listOf(BUTTON_ROWS, BUTTON_PICKED)) {
        if (extension.methods.none { it.signature() == hook && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }) {
            refuse("the extension has no public static $hook")
        }
    }
    for ((name, parameters, returns) in BUTTON_STUBS) {
        extension.methods.singleOrNull {
            it.name == name && it.parameters() == parameters && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: refuse("$CHAT_BUTTON_LINKS has no static $name(${parameters.joinToString("")})$returns")
    }
}

/** Whether [method] reads two lists before it loads the word and makes a (words, icon, text) row after it. */
private fun BytecodePatchContext.makesCardRows(method: Method): Boolean {
    val code = method.code()
    val load = code.indices.firstOrNull { stringLoadedAt(code, it) == LONG_PRESS_BUTTON } ?: return false
    val lists = (0 until load).count { at ->
        val call = code[at].call()
        code[at].opcode == Opcode.INVOKE_VIRTUAL && call != null && call.parameterTypes.isEmpty() && call.returnType == IMMUTABLE_LIST
    }
    return lists >= 2 && (load + 1 until code.size).any { at ->
        val call = code[at].call()
        code[at].opcode == Opcode.INVOKE_DIRECT && call?.name == "<init>" && call.parameters() == listOf(STRING, INTEGER, STRING)
    }
}

/** Finds every place the patch changes and every read the stubs make, before anything changes. */
internal fun BytecodePatchContext.findChatButtonLinks(): ButtonLinkSite {
    requireButtonLinkHooks()
    // The string pool and code asking it for the word load it too, so the maker is the one that
    // also reads a card's buttons before the word and makes a card row after it.
    val builders = classesLoadingString(LONG_PRESS_BUTTON).flatMap { it.methods }
        .filter { loadsString(it, LONG_PRESS_BUTTON) && makesCardRows(it) }
    val builder = builders.singleOrNull()
        ?: refuse("expected one method reading card buttons around \"$LONG_PRESS_BUTTON\", found ${builders.size}")
    val code = builder.code()
    val where = "${builder.definingClass}->${builder.name}"
    val load = code.indices.filter { stringLoadedAt(code, it) == LONG_PRESS_BUTTON }.singleOrNull()
        ?: refuse("$where doesn't load \"$LONG_PRESS_BUTTON\" once")

    // The card's buttons come from the message's first card: message.cards(), the first one, a
    // cast to the card, card.buttons(), and the loop over them that compares each one's kind.
    val lists = (0 until load).filter { at ->
        val call = code[at].call()
        code[at].opcode == Opcode.INVOKE_VIRTUAL && call != null && call.parameterTypes.isEmpty() && call.returnType == IMMUTABLE_LIST
    }
    if (lists.size < 2) refuse("$where reads no card buttons before \"$LONG_PRESS_BUTTON\"")
    val (cardsAt, buttonsAt) = lists.takeLast(2)
    val cards = code[cardsAt].call()!!
    val cardButtons = code[buttonsAt].call()!!
    val cardCasts = (cardsAt + 1 until buttonsAt).filter { code[it].opcode == Opcode.CHECK_CAST }
        .map { (code[it].reference() as TypeReference).type }
    if (cardCasts != listOf(cardButtons.definingClass)) refuse("$where doesn't cast the message's first card before reading its buttons")
    val messageType = cards.definingClass
    val message = code[cardsAt].arguments().single()

    // The row: the card row model's (words, icon, text) constructor after the comparison, added to the list.
    val rowAt = (load + 1 until code.size).firstOrNull { at ->
        val call = code[at].call()
        code[at].opcode == Opcode.INVOKE_DIRECT && call?.name == "<init>" && call.returnType == "V" &&
            call.parameters() == listOf(STRING, INTEGER, STRING)
    } ?: refuse("$where makes no card row after \"$LONG_PRESS_BUTTON\"")
    val row = code[rowAt].call()!!
    val (made, words, _, text) = code[rowAt].arguments()
    fun stringField(register: Int): FieldReference {
        val read = (load + 1 until rowAt).lastOrNull { code[it].writes(register) }?.let { code[it] }
        val field = read?.field()
        if (read?.opcode != Opcode.IGET_OBJECT || field?.type != STRING) refuse("$where doesn't read a card row's words and text off the button")
        return field
    }
    val title = stringField(words)
    val url = stringField(text)
    val buttonType = title.definingClass
    if (url.definingClass != buttonType) refuse("$where reads a card row's words and text off two kinds of object")
    val buttonCasts = (buttonsAt + 1 until load).filter { code[it].opcode == Opcode.CHECK_CAST }
        .map { (code[it].reference() as TypeReference).type }
    if (buttonType !in buttonCasts) refuse("$where doesn't cast each card button to $buttonType")
    val adds = (rowAt + 1 until minOf(rowAt + 4, code.size)).filter {
        val call = code[it].call()
        call?.name == "add" && call.parameters() == listOf(OBJECT) && code[it].arguments().getOrNull(1) == made
    }
    val list = adds.singleOrNull()?.let { code[it].arguments()[0] } ?: refuse("$where doesn't add its card row to a list")

    // The test of the list right before the model copies it.
    val tests = (rowAt + 1 until code.size - 1).filter { at ->
        val next = code[at + 1].call()
        code[at].opcode == Opcode.IF_EQZ && (code[at] as OneRegisterInstruction).registerA == list &&
            code[at + 1].opcode == Opcode.INVOKE_STATIC && next?.parameters() == listOf(ITERABLE) && code[at + 1].arguments() == listOf(list)
    }
    val hookAt = tests.singleOrNull() ?: refuse("$where tests its card row list before copying it ${tests.size} times, not once")
    val flow = ControlFlow.of(builder)
    val skipTo = flow.normal[hookAt].first()
    if (list > 15 || message > 15) refuse("$where keeps the list or the message past v15")
    val liveness = RegisterLiveness.of(builder)
    if (list in liveness.liveInto(hookAt + 2) || list in liveness.liveInto(skipTo)) {
        refuse("$where reads its card row list again after copying it")
    }
    requireMessageHeld(code, flow, message, messageType, cardsAt, hookAt, where)

    val textButtons = findTextButtons(messageType)
    val (dispatcher, activity) = findDispatcher()
    for ((type, what) in listOf(messageType to "message", cardButtons.definingClass to "card", buttonType to "button", row.definingClass to "card row")) {
        val classDef = classDefByOrNull(type) ?: continue
        if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) refuse("the $what class $type isn't public")
    }
    return ButtonLinkSite(
        builder.definingClass, builder.name, builder.parameters(), builder.returnType, hookAt, list, message, skipTo,
        messageType, cards, cardButtons.definingClass, cardButtons, buttonType, title, url, row, textButtons,
        dispatcher.definingClass, dispatcher.name, dispatcher.parameters(), activity,
    )
}

/**
 * Throws unless [message] still holds the message at [hookAt]: the register is set from a field of
 * the message's type before [cardsAt], and no other write to it reaches [hookAt] without passing
 * that read first.
 */
private fun requireMessageHeld(
    code: List<Instruction>,
    flow: ControlFlow,
    message: Int,
    messageType: String,
    cardsAt: Int,
    hookAt: Int,
    where: String,
) {
    val writes = code.indices.filter { code[it].writes(message) }
    val read = writes.lastOrNull { it < cardsAt && code[it].opcode == Opcode.IGET_OBJECT && code[it].field()?.type == messageType }
        ?: refuse("$where doesn't read its message off a field before its cards")
    val reached = BitSet()
    val pending = ArrayDeque<Int>()
    fun visit(next: List<Int>) = next.forEach { if (!reached[it]) { reached.set(it); pending += it } }
    writes.filter { it != read }.forEach { visit(flow.normal[it]) }
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        visit(flow.exceptional[at])
        if (at != read) visit(flow.normal[at])
    }
    if (reached[hookAt]) refuse("$where writes over its message before the card row list is copied")
}

/**
 * The field the message parser stores the buttons under a text in: the first list field of the
 * message's own type or one of its superclasses that a method loading [TEXT_BUTTONS_KEY] stores
 * within [PARSE_REACH] instructions after the key. One field across every such method.
 */
private fun BytecodePatchContext.findTextButtons(messageType: String): FieldReference {
    val owners = mutableSetOf<String>()
    var type: String? = messageType
    while (type != null && type.startsWith("L") && owners.add(type)) type = classDefByOrNull(type)?.superclass
    val fields = classesLoadingString(TEXT_BUTTONS_KEY).flatMap { it.methods }.filter { loadsString(it, TEXT_BUTTONS_KEY) }
        .flatMap { method ->
            val code = method.code()
            code.indices.filter { stringLoadedAt(code, it) == TEXT_BUTTONS_KEY }.mapNotNull { at ->
                (at + 1 until minOf(at + PARSE_REACH, code.size)).firstNotNullOfOrNull { index ->
                    code[index].field()?.takeIf {
                        code[index].opcode == Opcode.IPUT_OBJECT && it.type == LIST && it.definingClass in owners
                    }
                }
            }
        }.distinct()
    return fields.singleOrNull() ?: refuse("expected one list of a message's text buttons stored after \"$TEXT_BUTTONS_KEY\", found ${fields.size}")
}

/**
 * The chat's dispatcher for a held message's menu: the class reading the menu's model
 * ([MESSAGE_ACTIONS]) that keeps the chat's thread key and one activity, with a method taking the
 * menu's action (the enum naming [CARD_ROW_ACTION]) and a row's text. Answers the method and the
 * activity field.
 */
private fun BytecodePatchContext.findDispatcher(): Pair<Method, FieldReference> {
    val enums = classesLoadingString(CARD_ROW_ACTION).filter { action ->
        action.superclass == ENUM && action.methods.any { it.name == "<clinit>" && loadsString(it, CARD_ROW_ACTION) }
    }
    val actions = enums.singleOrNull()?.type ?: refuse("expected one action enum naming \"$CARD_ROW_ACTION\", found ${enums.size}")
    val found = classesReadingType(MESSAGE_ACTIONS).mapNotNull { host -> dispatcherIn(host, actions) }
    return found.singleOrNull() ?: refuse("expected one chat dispatcher for a held message's menu, found ${found.size}")
}

private fun dispatcherIn(host: ClassDef, actions: String): Pair<Method, FieldReference>? {
    if (host.fields.none { it.type == THREAD_KEY }) return null
    val activities = host.fields.filter { it.type == ACTIVITY && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val activity = activities.singleOrNull() ?: return null
    val methods = host.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null && it.returnType == "V" &&
            it.parameters() == listOf(actions, STRING)
    }
    val method = methods.singleOrNull() ?: return null
    return method to activity
}

/** Only called once [findChatButtonLinks] found everything. */
internal fun BytecodePatchContext.applyChatButtonLinks(site: ButtonLinkSite) {
    val builder = mutableClassDefBy(site.builderType).methods.single {
        it.name == site.builderName && it.returnType == site.builderReturns && it.parameters() == site.builderParameters
    }
    val skip = builder.getInstruction(site.skipTo)
    // The test is a jump target, so the call takes its place, which keeps the jump onto it, and
    // the test goes back right after the answer.
    builder.replaceInstruction(site.hookAt, "invoke-static { v${site.list}, v${site.message} }, $BUTTON_ROWS")
    builder.addInstructionsWithLabels(
        site.hookAt + 1,
        """
            move-result-object v${site.list}
            if-eqz v${site.list}, :none
        """,
        ExternalLabel("none", skip),
    )

    val dispatcher = mutableClassDefBy(site.dispatcherType).methods.single {
        it.name == site.dispatcherName && it.returnType == "V" && it.parameters() == site.dispatcherParameters
    }
    if (dispatcher.localRegisterCount() < 3) refuse("${site.dispatcherType}->${site.dispatcherName} has fewer than three locals")
    if (0 in dispatcher.jumpTargets()) refuse("${site.dispatcherType}->${site.dispatcherName} jumps back to its first instruction")
    dispatcher.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${site.activity}
            move-object/from16 v1, p1
            move-object/from16 v2, p2
            invoke-static { v0, v1, v2 }, $BUTTON_PICKED
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", dispatcher.getInstruction(0)),
    )

    fill("textButtons", 2, """
        const/4 v0, 0x0
        if-eqz p0, :none
        instance-of v1, p0, ${site.textButtons.definingClass}
        if-eqz v1, :none
        check-cast p0, ${site.textButtons.definingClass}
        iget-object v0, p0, ${site.textButtons}
        :none
        return-object v0
    """)
    fill("cards", 2, """
        const/4 v0, 0x0
        if-eqz p0, :none
        instance-of v1, p0, ${site.messageType}
        if-eqz v1, :none
        check-cast p0, ${site.messageType}
        invoke-virtual { p0 }, ${site.cards}
        move-result-object v0
        :none
        return-object v0
    """)
    fill("cardButtons", 2, """
        const/4 v0, 0x0
        if-eqz p0, :none
        instance-of v1, p0, ${site.cardType}
        if-eqz v1, :none
        check-cast p0, ${site.cardType}
        invoke-virtual { p0 }, ${site.cardButtons}
        move-result-object v0
        :none
        return-object v0
    """)
    for ((name, field) in listOf("buttonTitle" to site.title, "buttonUrl" to site.url)) {
        fill(name, 2, """
            const/4 v0, 0x0
            if-eqz p0, :none
            instance-of v1, p0, ${site.buttonType}
            if-eqz v1, :none
            check-cast p0, ${site.buttonType}
            iget-object v0, p0, $field
            :none
            return-object v0
        """)
    }
    fill("row", 1, """
        new-instance v0, ${site.row.definingClass}
        invoke-direct { v0, p0, p1, p2 }, ${site.row}
        return-object v0
    """)
}

/** Replaces the stub [name]'s body with [body], which uses [locals] registers of its own. */
private fun BytecodePatchContext.fill(name: String, locals: Int, body: String) {
    val (_, parameters, returns) = BUTTON_STUBS.single { it.first == name }
    val owner = mutableClassDefBy(CHAT_BUTTON_LINKS)
    val stub = owner.methods.single { it.name == name && it.parameters() == parameters && it.returnType == returns }
    val replacement = ImmutableMethod(stub.definingClass, stub.name, stub.parameters, stub.returnType,
        stub.accessFlags, stub.annotations, stub.hiddenApiRestrictions,
        ImmutableMethodImplementation(locals + parameters.size, emptyList(), null, null)).toMutable().apply {
        addInstructionsWithLabels(0, body.trimIndent())
    }
    owner.methods.remove(stub)
    owner.methods.add(replacement)
}

private fun Method.signature() = "$definingClass->$name(${parameters().joinToString("")})$returnType"
private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun Instruction.call() = reference() as? MethodReference
private fun Instruction.field() = reference() as? FieldReference
private fun MethodReference.parameters(): List<String> = parameterTypes.map { it.toString() }
private fun Instruction.writes(register: Int) = opcode.setsRegister() &&
    (this as? OneRegisterInstruction)?.let { it.registerA == register || opcode.setsWideRegister() && it.registerA + 1 == register } == true
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

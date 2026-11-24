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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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
 * the build draws. A card row's tap reaches the menu's action dispatcher: the chat's handler for the
 * menu's bottom row hands every action but More on to it with the row's text, and the rows of the
 * list More opens call it straight. The dispatcher asks the extension first, which handles a Copy
 * link row and has the dispatcher return. A row of the list More opens that keeps its row in a field
 * hands the dispatcher More's text in place of its own (#852), so each of those asks the extension
 * first too, with its row's own text. The extension reads the message's buttons, under a text and on
 * its cards, through stubs the patch fills with the reads the model's own code makes. Every refusal
 * happens before anything changes.
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
 * [dispatcherName], whose parameters at [activityParameter], [actionParameter] and [textParameter]
 * are the chat's activity, the row's action and the row's text. [rowHandlers] are the rows of the
 * list More opens that keep their row in a field and hand the dispatcher another text.
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
    val activityParameter: Int,
    val actionParameter: Int,
    val textParameter: Int,
    val rowHandlers: List<RowHandler>,
    val scratch: Int? = null,
)

/**
 * A row of the list More opens that hands the dispatcher a text other than its row's own:
 * [type]->[name], which reads its row off [row] and the chat's activity off [activity]. A row
 * carries its action in [action] and its text in [text]. [unit] is the static field the method
 * returns, for one returning an object, else null.
 */
internal class RowHandler(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returns: String,
    val row: FieldReference,
    val activity: FieldReference,
    val action: FieldReference,
    val text: FieldReference,
    val unit: FieldReference?,
)

/** The menu's action dispatcher: its class, name and parameters, and which of them hold the activity, action and text. */
internal class ActionDispatcher(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val activity: Int,
    val action: Int,
    val text: Int,
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
    val addAt = adds.singleOrNull() ?: refuse("$where doesn't add its card row to a list")
    val list = listKeptIn(code, rowAt, addAt, where)

    // The test of the list right before the model copies it.
    val tests = (rowAt + 1 until code.size - 1).filter { at ->
        val next = code[at + 1].call()
        code[at].opcode == Opcode.IF_EQZ && (code[at] as OneRegisterInstruction).registerA == list &&
            code[at + 1].opcode in STATIC_CALLS && next?.parameters() == listOf(ITERABLE) && code[at + 1].arguments() == listOf(list)
    }
    val hookAt = tests.singleOrNull() ?: refuse("$where tests its card row list before copying it ${tests.size} times, not once")
    val flow = ControlFlow.of(builder)
    val skipTo = flow.normal[hookAt].first()
    val liveness = RegisterLiveness.of(builder)
    if (list in liveness.liveInto(hookAt + 2) || list in liveness.liveInto(skipTo)) {
        refuse("$where reads its card row list again after copying it")
    }
    // 457 keeps the list past v15 (v31 on 386314341), where a plain call can't name it, so the ask
    // copies the list and the message into two registers in a row that nothing reads from the test on.
    val scratch = if (list <= 15 && message <= 15) null else {
        val live = liveness.liveInto(hookAt) + list + message
        (0 until builder.localRegisterCount() - 1).firstOrNull { it !in live && it + 1 !in live }
            ?: refuse("$where keeps the list or the message past v15 and has no two free registers in a row for them")
    }
    requireMessageHeld(code, flow, message, messageType, cardsAt, hookAt, where)

    val textButtons = findTextButtons(messageType)
    val (menu, actions) = findMenuHandler()
    val dispatcher = actionDispatcher(menu, actions)
    val rowHandlers = rowHandlers(dispatcher, menu, actions)
    for ((type, what) in listOf(messageType to "message", cardButtons.definingClass to "card", buttonType to "button", row.definingClass to "card row")) {
        val classDef = classDefByOrNull(type) ?: continue
        if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) refuse("the $what class $type isn't public")
    }
    return ButtonLinkSite(
        builder.definingClass, builder.name, builder.parameters(), builder.returnType, hookAt, list, message, skipTo,
        messageType, cards, cardButtons.definingClass, cardButtons, buttonType, title, url, row, textButtons,
        dispatcher.type, dispatcher.name, dispatcher.parameters, dispatcher.activity, dispatcher.action, dispatcher.text,
        rowHandlers, scratch,
    )
}

/** The calls the builder can copy its card row list with. */
private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

/**
 * The register the card row list is kept in: the one the add at [addAt] names, or, when that was set
 * between the row at [rowAt] and the add, the register it was copied from (457 copies v31 into v0
 * for the add on 386314341).
 */
private fun listKeptIn(code: List<Instruction>, rowAt: Int, addAt: Int, where: String): Int {
    val added = code[addAt].arguments()[0]
    val write = (rowAt + 1 until addAt).lastOrNull { code[it].writes(added) } ?: return added
    val copy = code[write]
    if (copy.opcode !in OBJECT_MOVES) refuse("$where sets the list it adds its card row to after making the row")
    return (copy as TwoRegisterInstruction).registerB
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
 * The chat's handler for a held message's menu: the class reading the menu's model
 * ([MESSAGE_ACTIONS]) that keeps the chat's thread key and one activity, with a method taking the
 * menu's action (the enum naming [CARD_ROW_ACTION]) and a row's text. Answers the method and the
 * action enum.
 */
private fun BytecodePatchContext.findMenuHandler(): Pair<Method, String> {
    val enums = classesLoadingString(CARD_ROW_ACTION).filter { action ->
        action.superclass == ENUM && action.methods.any { it.name == "<clinit>" && loadsString(it, CARD_ROW_ACTION) }
    }
    val actions = enums.singleOrNull()?.type ?: refuse("expected one action enum naming \"$CARD_ROW_ACTION\", found ${enums.size}")
    val found = classesReadingType(MESSAGE_ACTIONS).mapNotNull { host -> menuHandlerIn(host, actions) }
    val handler = found.singleOrNull() ?: refuse("expected one chat handler for a held message's menu, found ${found.size}")
    return handler to actions
}

private fun menuHandlerIn(host: ClassDef, actions: String): Method? {
    if (host.fields.none { it.type == THREAD_KEY }) return null
    if (host.fields.count { it.type == ACTIVITY && !AccessFlags.STATIC.isSet(it.accessFlags) } != 1) return null
    val methods = host.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null && it.returnType == "V" &&
            it.parameters() == listOf(actions, STRING)
    }
    return methods.singleOrNull()
}

/**
 * The menu's action dispatcher: the one void method [menu] hands a row's action on to with the
 * chat's activity, which the rows of the list More opens call straight, past [menu]. The row's text
 * is the one string argument holding the text [menu] was handed, along every path to the call.
 */
private fun actionDispatcher(menu: Method, actions: String): ActionDispatcher {
    val code = menu.code()
    val where = "${menu.definingClass}->${menu.name}"
    val calls = code.indices.filter { at ->
        val call = code[at].call()
        call != null && call.returnType == "V" && actions in call.parameters() && ACTIVITY in call.parameters()
    }
    val at = calls.singleOrNull() ?: refuse("$where hands a row's action on with the activity ${calls.size} times, not once")
    val target = code[at].call()!!
    val parameters = target.parameters()
    val called = "${target.definingClass}->${target.name}"
    val activity = parameters.indices.singleOrNull { parameters[it] == ACTIVITY } ?: refuse("$called takes more than one activity")
    val action = parameters.indices.singleOrNull { parameters[it] == actions } ?: refuse("$called takes more than one action")
    val static = code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE
    val arguments = code[at].arguments()
    val handed = menu.implementation!!.registerCount - 1
    val texts = parameters.indices.filter { index ->
        parameters[index] == STRING && carries(menu, at, arguments[slot(parameters, index, static)], handed)
    }
    val text = texts.singleOrNull() ?: refuse("$where hands its row's text on to $called as ${texts.size} strings, not one")
    return ActionDispatcher(target.definingClass, target.name, parameters, activity, action, text)
}

/** Where parameter [index] sits among a call's argument registers (or a method's parameter registers). */
private fun slot(parameters: List<String>, index: Int, static: Boolean): Int =
    (if (static) 0 else 1) + parameters.take(index).sumOf { if (it == "J" || it == "D") 2 else 1 }

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * Whether [register] holds what [source] held on entry, wherever [method] comes from to [at]: [source]
 * is never written, and every write to [register] that reaches [at] is a move from [source] (or none
 * does and the two are one register).
 */
private fun carries(method: Method, at: Int, register: Int, source: Int): Boolean {
    val code = method.code()
    if (code.any { it.writes(source) }) return false
    val flow = ControlFlow.of(method)
    val before = Array(code.size) { mutableListOf<Int>() }
    for (index in code.indices) for (next in flow.normal[index] + flow.exceptional[index]) before[next] += index
    val seen = BitSet()
    val pending = ArrayDeque(before[at])
    var fromEntry = at == 0
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        if (seen[index]) continue
        seen.set(index)
        val instruction = code[index]
        if (instruction.writes(register)) {
            if (instruction.opcode !in OBJECT_MOVES || (instruction as TwoRegisterInstruction).registerB != source) return false
            continue
        }
        if (index == 0) fromEntry = true
        pending += before[index]
    }
    return !fromEntry || register == source
}

/**
 * The rows of the list More opens that hand the dispatcher a text other than their row's own. Each
 * call to the dispatcher that reads the row's action off a row (a field of the action's type) either
 * hands on that row's text too, or reads the row off a field of its own object, and then it's one of
 * these. A row's text is the row's string field that the bottom row's call to the chat's handler, or
 * a call to the dispatcher, hands on with the row's action. Throws when a row's action reaches the
 * dispatcher any other way, when the text isn't one field, or when a guard can't go first in one.
 */
private fun BytecodePatchContext.rowHandlers(dispatcher: ActionDispatcher, menu: Method, actions: String): List<RowHandler> {
    class Kept(val method: Method, val row: FieldReference, val activity: FieldReference, val action: FieldReference)
    val kept = mutableListOf<Kept>()
    val texts = HashMap<String, FieldReference>()
    fun isText(field: FieldReference?, action: FieldReference) =
        field != null && field.definingClass == action.definingClass && field.type == STRING
    for (method in classesReadingType(actions).flatMap { it.methods }) {
        if (method.implementation == null) continue
        val code = method.code()
        val where = "${method.definingClass}->${method.name}"
        val reads by lazy { Reads(method) }
        for (at in code.indices) {
            val call = code[at].call() ?: continue
            val parameters = call.parameters()
            if (call.returnType != "V") continue
            val static = code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE
            val arguments = code[at].arguments()
            fun argument(index: Int) = reads.field(reads.origin(at, arguments[slot(parameters, index, static)]))
            if (call.name == menu.name && parameters == menu.parameters()) {
                val action = argument(0)?.first ?: continue
                val text = argument(1)?.first
                if (action.type == actions && isText(text, action)) texts[text.toString()] = text!!
                continue
            }
            if (call.definingClass != dispatcher.type || call.name != dispatcher.name || parameters != dispatcher.parameters) continue
            val actionRead = reads.origin(at, arguments[slot(parameters, dispatcher.action, static)])
            val (action, rowFrom) = reads.field(actionRead) ?: continue
            if (action.type != actions) continue
            val text = argument(dispatcher.text)?.first
            if (isText(text, action)) {
                texts[text.toString()] = text!!
                continue
            }
            val (row, holder) = reads.field(rowFrom) ?: refuse("$where hands a row's action on without the row's text")
            if (row.definingClass != method.definingClass || row.type != action.definingClass || !reads.self(holder)) {
                refuse("$where hands a row's action on without the row's text")
            }
            // The guard reads the row and its action untested, as the method itself does before it
            // can branch or return.
            if (!reads.straightTo(actionRead!!.at)) refuse("$where may branch or return before it reads its row's action")
            val (activity, keeper) = argument(dispatcher.activity) ?: refuse("$where doesn't hand on an activity it keeps")
            if (activity.definingClass != method.definingClass || activity.type != ACTIVITY || !reads.self(keeper)) {
                refuse("$where doesn't hand on an activity it keeps")
            }
            kept += Kept(method, row, activity, action)
        }
    }
    val text = texts.values.singleOrNull() ?: refuse("expected one field a held message's row keeps its text in, found ${texts.size}")
    return kept.distinctBy { it.method.signature() }.map { handler ->
        val method = handler.method
        val where = "${method.definingClass}->${method.name}"
        if (handler.action.definingClass != text.definingClass) refuse("$where reads its row's action off a ${handler.action.definingClass}")
        if (method.localRegisterCount() < 3) refuse("$where has fewer than three locals")
        if (0 in method.jumpTargets()) refuse("$where jumps back to its first instruction")
        val unit = if (method.returnType == "V") null else returned(method)
        RowHandler(method.definingClass, method.name, method.parameters(), method.returnType, handler.row, handler.activity, handler.action, text, unit)
    }
}

/** The one static field every return-object of [method] returns, which its guard returns too. */
private fun returned(method: Method): FieldReference {
    val reads = Reads(method)
    val where = "${method.definingClass}->${method.name}"
    val fields = reads.code.indices.filter { reads.code[it].opcode == Opcode.RETURN_OBJECT }.map { at ->
        val origin = reads.origin(at, (reads.code[at] as OneRegisterInstruction).registerA)
        origin?.takeIf { it.at >= 0 && reads.code[it.at].opcode == Opcode.SGET_OBJECT }?.let { reads.code[it.at].field() }
            ?: refuse("$where returns something other than a static field")
    }.distinctBy { it.toString() }
    return fields.singleOrNull() ?: refuse("$where returns ${fields.size} static fields, not one")
}

/** Where a value was written: the instruction at [at], or with [at] -1 what [register] held on entry. */
private class Origin(val at: Int, val register: Int)

/** Where the values [method]'s code reads come from. */
private class Reads(private val method: Method) {
    val code = method.code()
    private val flow = ControlFlow.of(method)
    private val before = Array(code.size) { mutableListOf<Int>() }.also { before ->
        for (index in code.indices) for (next in flow.normal[index] + flow.exceptional[index]) before[next] += index
    }

    /** Whether every instruction before [at] goes on to the next one and nowhere else. */
    fun straightTo(at: Int): Boolean = (0 until at).all { flow.normal[it].toList() == listOf(it + 1) }

    /** The one write of what [register] holds at [at], followed back through object moves, or null when more than one reaches it. */
    fun origin(at: Int, register: Int): Origin? {
        val seen = BitSet()
        val pending = ArrayDeque(before[at])
        val writes = mutableSetOf<Int>()
        var entry = at == 0
        while (pending.isNotEmpty()) {
            val index = pending.removeFirst()
            if (seen[index]) continue
            seen.set(index)
            if (code[index].writes(register)) {
                writes += index
                continue
            }
            if (index == 0) entry = true
            pending += before[index]
        }
        if (writes.size + (if (entry) 1 else 0) != 1) return null
        val write = writes.singleOrNull() ?: return Origin(-1, register)
        val instruction = code[write]
        if (instruction.opcode in OBJECT_MOVES) return origin(write, (instruction as TwoRegisterInstruction).registerB)
        return Origin(write, register)
    }

    /** The field [origin] read, with where the object it read it off came from, or null when it is no field read. */
    fun field(origin: Origin?): Pair<FieldReference, Origin?>? {
        if (origin == null || origin.at < 0 || code[origin.at].opcode != Opcode.IGET_OBJECT) return null
        return code[origin.at].field()!! to origin(origin.at, (code[origin.at] as TwoRegisterInstruction).registerB)
    }

    /** Whether [origin] is the method's own object. */
    fun self(origin: Origin?): Boolean {
        if (origin == null || origin.at >= 0 || AccessFlags.STATIC.isSet(method.accessFlags)) return false
        val ins = 1 + method.parameters().sumOf { if (it == "J" || it == "D") 2 else 1 }
        return origin.register == method.implementation!!.registerCount - ins
    }
}

/** Only called once [findChatButtonLinks] found everything. */
internal fun BytecodePatchContext.applyChatButtonLinks(site: ButtonLinkSite) {
    val builder = mutableClassDefBy(site.builderType).methods.single {
        it.name == site.builderName && it.returnType == site.builderReturns && it.parameters() == site.builderParameters
    }
    val skip = builder.getInstruction(site.skipTo)
    // The test is a jump target, so the ask's first instruction takes its place, which keeps the
    // jump onto it, and the test goes back right after the answer.
    val scratch = site.scratch
    if (scratch == null) {
        builder.replaceInstruction(site.hookAt, "invoke-static { v${site.list}, v${site.message} }, $BUTTON_ROWS")
        builder.addInstructionsWithLabels(
            site.hookAt + 1,
            """
                move-result-object v${site.list}
                if-eqz v${site.list}, :none
            """,
            ExternalLabel("none", skip),
        )
    } else {
        builder.replaceInstruction(site.hookAt, "move-object/from16 v$scratch, v${site.list}")
        builder.addInstructionsWithLabels(
            site.hookAt + 1,
            """
                move-object/from16 v${scratch + 1}, v${site.message}
                invoke-static/range { v$scratch .. v${scratch + 1} }, $BUTTON_ROWS
                move-result-object v${site.list}
                if-eqz v${site.list}, :none
            """,
            ExternalLabel("none", skip),
        )
    }

    val dispatcher = mutableClassDefBy(site.dispatcherType).methods.single {
        it.name == site.dispatcherName && it.returnType == "V" && it.parameters() == site.dispatcherParameters
    }
    if (dispatcher.localRegisterCount() < 3) refuse("${site.dispatcherType}->${site.dispatcherName} has fewer than three locals")
    if (0 in dispatcher.jumpTargets()) refuse("${site.dispatcherType}->${site.dispatcherName} jumps back to its first instruction")
    val static = AccessFlags.STATIC.isSet(dispatcher.accessFlags)
    fun parameter(index: Int) = "p${slot(site.dispatcherParameters, index, static)}"
    dispatcher.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${parameter(site.activityParameter)}
            move-object/from16 v1, ${parameter(site.actionParameter)}
            move-object/from16 v2, ${parameter(site.textParameter)}
            invoke-static { v0, v1, v2 }, $BUTTON_PICKED
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", dispatcher.getInstruction(0)),
    )
    // A row of the list More opens that hands the dispatcher More's text asks first with its own,
    // reading its row with no test, as its own code does before it can branch or return.
    for (handler in site.rowHandlers) {
        val method = mutableClassDefBy(handler.type).methods.single {
            it.name == handler.name && it.returnType == handler.returns && it.parameters() == handler.parameters
        }
        val back = handler.unit?.let { "sget-object v0, $it\nreturn-object v0" } ?: "return-void"
        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                iget-object v1, v0, ${handler.row}
                iget-object v0, v0, ${handler.activity}
                iget-object v2, v1, ${handler.text}
                iget-object v1, v1, ${handler.action}
                invoke-static { v0, v1, v2 }, $BUTTON_PICKED
                move-result v0
                if-eqz v0, :instagram
                $back
            """,
            ExternalLabel("instagram", method.getInstruction(0)),
        )
    }

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

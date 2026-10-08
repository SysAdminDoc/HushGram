/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.util.ControlFlow
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val COMMENT_SELECT = "select_comment_screen_comment_select_tap_"
internal const val COMMENT_ROWS = "instagram_share_comment_to_story_entrypoint_impression"
internal const val COMMENT_LEGACY = "comment_options_menu_rendered"
internal const val COMMENT_NATIVE = "$EXTENSION_PACKAGE/comment/CommentMenuNative;"
internal const val COPY_ROW = "$EXTENSION_PACKAGE/comment/CopyRow;"
internal const val COMMENT_COPY = "$EXTENSION_PACKAGE/comment/CommentCopy;"
internal const val COMMENT_ACTIONS = "$EXTENSION_PACKAGE/comment/CommentActions;"
/** The one call both comment families share in the native renderer. */
internal const val COMMENT_HOOK = "$COMMENT_ACTIONS->rows(Ljava/util/List;Ljava/lang/Object;Landroid/content/Context;)Ljava/util/List;"
internal const val COPY_PATCH = "Copy comment"
internal const val FUNCTION = "Lkotlin/jvm/functions/Function0;"
internal const val OBJECT = "Ljava/lang/Object;"
internal const val STRING = "Ljava/lang/String;"
internal const val LIST = "Ljava/util/List;"
internal const val INTEGER = "Ljava/lang/Integer;"
internal const val GIPHY = "Lcom/instagram/api/schemas/CommentGiphyMediaInfoIntf;"
internal const val JSON_ROOT_FIELD = "Current token not FIELD_NAME (to contain expected root name %s), but %s"
internal const val JSON_ROOT_MISMATCH = "Root name (%s) does not match expected (%s) for type %s"

/**
 * The selected comment, the common renderer and its native row type, found before a single method
 * is changed. Both comment families build on it and share the renderer's one call. It holds plain
 * references only, never the app's classes or methods, so keeping it holds none of the app's code.
 */
internal data class CommentSurface(
    val selectedType: String, val rawField: FieldReference, val raw: String, val pando: String,
    val renderer: MethodReference, val at: Int, val rows: Int, val selected: Int, val context: Int, val spares: List<Int>,
    val rowConstructor: MethodReference, val style: FieldReference, val iconConstructor: MethodReference,
    val labelConstructor: MethodReference, val callback: FieldReference, val copyAction: String,
)

/** Copy's own boundaries on top of the shared surface. */
internal data class CommentMenu(val surface: CommentSurface, val text: FieldReference, val icon: Int, val label: Int)

private val surfaces = java.util.WeakHashMap<BytecodePatchContext, CommentSurface>()
private val discoveringPatch = ThreadLocal.withInitial { COPY_PATCH }

/** Runs [block] with refusals naming [patch]. */
internal fun <T> discovering(patch: String, block: () -> T): T {
    val outer = discoveringPatch.get()
    discoveringPatch.set(patch)
    try {
        return block()
    } finally {
        discoveringPatch.set(outer)
    }
}

/** Every class as it stands now, by type. */
internal fun BytecodePatchContext.classPool(): Map<String, ClassDef> {
    val classes = linkedMapOf<String, ClassDef>()
    classDefForEach { classes[it.type] = it }
    return classes
}

/**
 * The surface as the first comment family in this patch run found it. The second family can't
 * read the renderer again once the shared call is in, so it reuses what was found before that.
 */
internal fun BytecodePatchContext.commentSurface(classes: Map<String, ClassDef>): CommentSurface {
    synchronized(surfaces) { surfaces[this] }?.let { return it }
    val surface = findCommentSurface(classes)
    synchronized(surfaces) { surfaces[this] = surface }
    return surface
}

internal fun treeBacked(type: ClassDef, classes: Map<String, ClassDef>): Boolean {
    var parent = type.superclass
    val seen = mutableSetOf<String>()
    while (parent != null && seen.add(parent)) {
        if (parent == "Lcom/facebook/pando/TreeJNI;") return true
        parent = classes[parent]?.superclass
    }
    return false
}

internal fun BytecodePatchContext.findCommentMenu(): CommentMenu = discovering(COPY_PATCH) {
    val classes = classPool()
    val surface = commentSurface(classes)
    for (type in listOf(COMMENT_NATIVE, COPY_ROW, COMMENT_COPY)) {
        if (type !in classes) refuse("missing extension boundary $type")
    }
    fun clazz(type: String) = classes[type] ?: refuse("missing native class $type")
    fun methods() = classes.values.asSequence().flatMap { it.methods.asSequence() }
    val selectedType = surface.selectedType
    val model = clazz(selectedType)
    val raw = clazz(surface.raw)
    val pando = clazz(surface.pando)
    val pandoText = pandoGetter(COPY_PATCH, pando.type, "text", STRING)
    val getter = raw.methods.filter {
        it.name == pandoText.name && it.parameterTypes.isEmpty() && it.returnType == STRING
    }.one("original text interface getter")
    val valueModel = classes.values.filter { raw.type in it.interfaces && it.type != pando.type &&
        it.methods.any { method -> method.matches(getter) && method.code().any { instruction ->
            instruction.opcode == Opcode.IGET_OBJECT && instruction.field()?.type == STRING
        } } }.one("comment's parsed value model")
    val valueGetter = valueModel.methods.filter { it.matches(getter) }.one("parsed text getter")
    val valueText = parsedTextField(valueGetter, valueModel.type)
    val parser = methods().filter { method -> method.name == "unsafeParseFromJson" &&
        method.returnType in setOf(OBJECT, valueModel.type) && "text" in method.strings() &&
        method.code().any { it.call()?.let { call -> call.name == "<init>" && call.definingClass == valueModel.type } == true }
    }.toList().one("comment text parser")
    val readAt = parserOriginalRead(parser, classes, jsonReads(parserInput(parser), classes))
    if (constructorField(parser, readAt + 1, valueModel.type, classes, allowAbsent = true).toString() != valueText.toString()) {
        refuse("parsed text does not reach the original text getter")
    }
    val converter = methods().filter { it.returnType == selectedType && it.code().any { instruction ->
        instruction.call()?.let { call -> call.definingClass == raw.type && call.matches(getter) } == true
    } }.toList().one("selected-comment conversion")
    val textAt = converter.code().indices.filter { at ->
        converter.code()[at].call()?.let { it.definingClass == raw.type && it.matches(getter) } == true
    }.one("conversion's original text read")
    if (converter.code().getOrNull(textAt + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("conversion loses original text")
    val text = constructorField(converter, textAt + 1, selectedType, classes)
    requirePublicField(model, text)
    val (icon, label) = actionResources(clazz(surface.copyAction), classes)
    validateCommentStubs()
    CommentMenu(surface, text, icon, label)
}

/** Everything both families need from the selection, the row builder and the renderer. */
private fun BytecodePatchContext.findCommentSurface(classes: Map<String, ClassDef>): CommentSurface {
    if (COMMENT_ACTIONS !in classes) refuse("missing extension boundary $COMMENT_ACTIONS")
    fun clazz(type: String) = classes[type] ?: refuse("missing native class $type")
    fun methods() = classes.values.asSequence().flatMap { it.methods.asSequence() }
    val select = classesHolding(COMMENT_SELECT).asSequence().flatMap { it.methods.asSequence() }.filter {
        COMMENT_SELECT in it.strings() && it.publicInstance() &&
            it.returnType == "V" && it.parameters() == listOf(STRING, STRING, "F", "Z")
    }.toList().one("selected-comment anchor")
    val selectedType = selectionType(select)
    val model = clazz(selectedType)
    requirePublic(model)
    val rawField = model.fields.filter { field ->
        classes[field.type]?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) &&
            it.methods.any { method -> method.parameterTypes.isEmpty() && method.returnType == GIPHY } } == true
    }.one("selected comment's raw model")
    val raw = clazz(rawField.type)
    val pando = classes.values.filter {
        raw.type in it.interfaces && treeBacked(it, classes)
    }.one("comment's tree-backed model")

    val builder = clazz(select.definingClass).methods.filter { COMMENT_ROWS in it.strings() }.one("comment row builder")
    if (builder.returnType != "Ljava/util/ArrayList;" || !select.code().any { it.call()?.matches(builder) == true }) {
        refuse("selection no longer calls the native row builder")
    }
    val rowConstructor = builder.code().mapNotNull { it.call() }.filter {
        it.name == "<init>" && it.returnType == "V" && it.parameters().size == 4 && it.parameters().last() == FUNCTION
    }.distinctBy { it.toString() }.one("native display row constructor")
    val rowBase = clazz(rowConstructor.definingClass)
    requirePublic(rowBase)
    if (AccessFlags.FINAL.isSet(rowBase.accessFlags) || rowBase.methods.any { AccessFlags.ABSTRACT.isSet(it.accessFlags) }) {
        refuse("native row cannot accept an independent action subtype")
    }
    requireConstructor(rowBase, rowConstructor.parameters())
    val callback = rowBase.fields.filter { it.type == FUNCTION && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .one("native row callback")
    requirePublicField(rowBase, callback)
    val styleBase = clazz(rowConstructor.parameters()[0])
    requirePublic(styleBase)
    val color = styleBase.fields.filter { it.type == INTEGER }.one("row text color")
    val style = builder.code().filter { it.opcode == Opcode.SGET_OBJECT }.mapNotNull { it.field() }.filter { field ->
        classes[field.type]?.let { styleClass ->
            styleClass.superclass == styleBase.type && styleClass.methods.any { method ->
                method.name == "<clinit>" && method.code().indices.any { at ->
                    val instruction = method.code()[at]
                    instruction.opcode == Opcode.IPUT_OBJECT && instruction.field()?.toString() == color.toString() &&
                        method.constantBefore(at, (instruction as TwoRegisterInstruction).registerA) == 0
                }
            }
        } == true
    }.distinctBy { it.toString() }.one("native normal style singleton")
    requirePublic(clazz(style.type))
    requirePublicField(clazz(style.definingClass), style)
    if (!AccessFlags.STATIC.isSet(clazz(style.definingClass).fields.single { it.name == style.name }.accessFlags)) {
        refuse("normal style is no longer static")
    }
    val iconClass = clazz(rowConstructor.parameters()[1])
    val labelClass = clazz(rowConstructor.parameters()[2])
    val iconConstructor = requireConstructor(iconClass, listOf("I"))
    val labelConstructor = requireConstructor(labelClass, listOf("I"))

    // The selected comment's register is counted back from the list, so the value between them
    // must take one register.
    val renderer = methods().filter { method ->
        method.publicInstance() && method.returnType == "V" && method.parameters().let {
            it.size == 5 && it[1] == selectedType && it[2] != "J" && it[2] != "D" && it[3] == LIST && it[4] == "F"
        } && method.code().any { it.field()?.toString() == callback.toString() ||
            (it.reference() as? TypeReference)?.type == rowBase.type }
    }.toList().one("common comment menu renderer")
    // The stock renderer supplies its own callback wrapper. Its click consumes Function0,
    // and its boolean callback says to dismiss the popup, regardless of the row subtype.
    val wrapper = renderer.code().mapNotNull { it.call() }.filter {
        it.name == "<init>" && it.parameters() == listOf(OBJECT, "I")
    }.mapNotNull { classes[it.definingClass] }.distinctBy { it.type }.filter { type ->
        type.methods.any { method -> method.code().any { it.field()?.toString() == callback.toString() } &&
            method.code().any { it.call()?.toString() == "$FUNCTION->invoke()$OBJECT" } }
    }.one("stock comment row callback")
    val dismiss = wrapper.methods.filter { it.parameterTypes.isEmpty() && it.returnType == "Z" }.one("stock dismissal decision")
    if (dismiss.code().map { it.opcode } != listOf(Opcode.CONST_4, Opcode.RETURN) ||
        (dismiss.code()[0] as NarrowLiteralInstruction).narrowLiteral != 1) refuse("stock row callback no longer dismisses")
    val listRegister = renderer.implementation!!.registerCount - 2
    val selectedRegister = renderer.implementation!!.registerCount - 4
    val code = renderer.code()
    if (code.any { it.call()?.toString() == COMMENT_HOOK }) refuse("renderer already carries the comment hook")
    val at = code.indices.filter { index ->
        code[index].call()?.let { it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Iterable;") &&
            it.returnType == "I" } == true && code[index].arguments() == listOf(listRegister) &&
            code.getOrNull(index - 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            code.getOrNull(index - 2)?.call()?.let { it.name == "requireContext" &&
                it.returnType == "Landroid/content/Context;" } == true
    }.one("guarded renderer's list boundary")
    if (code.take(at).any { it.writes(listRegister) || it.writes(selectedRegister) }) refuse("renderer reuses its input parameters")
    if (code.take(at).count { it.opcode == Opcode.IF_NEZ } < 2) refuse("renderer lacks its duplicate-popup guards")
    val context = (code[at - 1] as OneRegisterInstruction).registerA
    val spare = try {
        renderer.getFreeRegisterProvider(at, 3, listRegister, selectedRegister, context).let {
            List(3) { _ -> it.getFreeRegister4Bit() }
        }
    } catch (failure: IllegalStateException) {
        refuse("renderer has no safe invocation registers: ${failure.message}")
    } catch (failure: IllegalArgumentException) {
        refuse("renderer has no safe invocation registers: ${failure.message}")
    }
    val copy = methods().filter { it.name == "toString" && it.returnType == STRING && "CopyText" in it.strings() }
        .toList().one("native CopyText label anchor")
    return CommentSurface(selectedType, ImmutableFieldReference.of(rawField), raw.type, pando.type,
        ImmutableMethodReference.of(renderer), at, listRegister, selectedRegister, context, spare,
        ImmutableMethodReference.of(rowConstructor), ImmutableFieldReference.of(style),
        ImmutableMethodReference.of(iconConstructor), ImmutableMethodReference.of(labelConstructor),
        ImmutableFieldReference.of(callback), copy.definingClass)
}

/**
 * A native menu action's icon and label: the constants its no-argument constructor hands to its
 * family's resource constructor (an object, the icon, the label and a flag).
 */
internal fun actionResources(action: ClassDef, classes: Map<String, ClassDef>): Pair<Int, Int> {
    val what = action.type.substringAfterLast('/').removeSuffix(";").let { "native action $it" }
    val constructor = requireConstructor(action, emptyList())
    val code = constructor.code()
    if (code.any { it is OffsetInstruction }) refuse("$what constructor branches")
    val parentCallAt = code.indices.filter { index -> code[index].call()?.let {
        it.name == "<init>" && it.definingClass == action.superclass && it.parameters().let { types -> types.size == 4 &&
            types[1] == "I" && types[2] == "I" && types[3] == "Z" }
    } == true }.one("$what resource constructor")
    val parent = code[parentCallAt].call()!!
    requireConstructor(classes[parent.definingClass] ?: refuse("missing native class ${parent.definingClass}"), parent.parameters())
    val arguments = code[parentCallAt].arguments()
    val icon = constructor.constantBefore(parentCallAt, arguments[2]) ?: refuse("$what icon is not a constant")
    val label = constructor.constantBefore(parentCallAt, arguments[3]) ?: refuse("$what label is not a constant")
    if (icon ushr 24 != 0x7f || label ushr 24 != 0x7f || icon == label) refuse("$what resources are invalid")
    return icon to label
}

/** The selected comment, as resolved by the controller for the two comment IDs. */
internal fun selectionType(select: Method): String = select.code().mapNotNull { it.call() }.filter {
    it.parameters().size == 3 && it.parameters().takeLast(2) == listOf(STRING, STRING) &&
        it.returnType.startsWith("L") && it.returnType != STRING
}.distinctBy { it.toString() }.one("selected-comment resolver").returnType

/** The one JSON input a model parser reads. */
internal fun parserInput(parser: Method): String =
    parser.parameters().singleOrNull()?.takeIf { it.startsWith("L") } ?: refuse("parser input changed shape")

/** A key comparison is useful only when the native name reader has advanced to that key's value. */
private fun parserOriginalRead(parser: Method, classes: Map<String, ClassDef>, api: JsonReads): Int =
    keyGuards(parser, "text", classes, api) { read, readers -> readers[read] == ORIGINAL }.guards
        .map { it + 1 }.distinct().one("parser's proven original string read")

/**
 * The value a parser keeps for [key]: the one call consuming the input on the path that only the
 * key's proven guard leads to, before that path rejoins the parser's loop. The call is either
 * [valueParser]'s singleton parsing a nested model, or a direct reader answering [returns].
 */
internal fun parserKeyedRead(parser: Method, key: String, classes: Map<String, ClassDef>, api: JsonReads,
                             valueParser: String? = null, returns: String? = null): Int {
    val keyed = keyGuards(parser, key, classes, api) { _, _ -> true }
    val start = keyed.guards.distinct().one("parser's $key discriminator") + 1
    val code = keyed.code
    val flow = keyed.inputs.flow
    fun reach(from: Int, avoid: Int?): Set<Int> {
        val seen = mutableSetOf<Int>()
        val pending = java.util.ArrayDeque<Int>().apply { add(from) }
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            if (at == avoid || !seen.add(at)) continue
            pending.addAll(flow.normal[at]); pending.addAll(flow.exceptional[at])
        }
        return seen
    }
    // Only what can't be reached around the guard belongs to the key.
    val inside = reach(start, null) - reach(0, start)
    fun onInput(at: Int) = code[at].arguments().any { (keyed.inputs.before[at]?.get(it) ?: UNKNOWN) and RECEIVER != 0 }
    val read = inside.filter { at -> code[at].call() != null && onInput(at) &&
        code[at].call().toString() != api.current.toString() }.one("parser's $key value read")
    val call = code[read].call()!!
    if (code[read].opcode in staticInvokes == (valueParser != null) ||
        code[read].arguments().none { keyed.inputs.before[read]?.get(it) == RECEIVER } ||
        code.getOrNull(read + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("parser's $key read changed shape")
    if (valueParser == null) {
        if (call.returnType != returns) refuse("parser's $key reader answers ${call.returnType}")
        return read
    }
    val holder = code.getOrNull(read - 1)
    val singleton = holder?.field()
    if (holder?.opcode != Opcode.SGET_OBJECT || singleton?.definingClass != valueParser || singleton.type != valueParser ||
        code[read].arguments().first() != (holder as OneRegisterInstruction).registerA ||
        flow.normal.indices.filter { read in flow.normal[it] } != listOf(read - 1) || flow.exceptional.any { read in it }) {
        refuse("parser's $key isn't read by its own model parser")
    }
    val nested = classes[valueParser] ?: refuse("missing native class $valueParser")
    if (!AccessFlags.FINAL.isSet(nested.accessFlags)) refuse("$key model parser can be overridden")
    nested.fields.filter { it.name == singleton.name && it.type == singleton.type &&
        AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.FINAL.isSet(it.accessFlags) }.one("$key model parser singleton")
    val entry = nested.methods.filter { it.matches(call) && !AccessFlags.STATIC.isSet(it.accessFlags) }.one("$key model parser entry")
    if (!parsesItself(entry, classes, 3)) refuse("$key model parser entry no longer parses")
    return read
}

/**
 * Whether a parser's entry hands its input to its own unsafeParseFromJson, itself or through the
 * entry it inherits. The parser class is final, so that call reaches the parser proved for the key.
 */
private fun parsesItself(entry: Method, classes: Map<String, ClassDef>, depth: Int): Boolean {
    val code = entry.code()
    val self = entry.implementation!!.registerCount - entry.parameters().size - 1
    fun onSelf(at: Int) = code[at].arguments().firstOrNull() == self &&
        code.take(at).none { it.writes(self) }
    if (code.indices.any { at -> onSelf(at) && code[at].opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) &&
            code[at].call()?.let { it.name == "unsafeParseFromJson" && it.parameters() == entry.parameters() } == true }) return true
    if (depth == 0) return false
    return code.indices.filter { at -> onSelf(at) && code[at].opcode in setOf(Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE) &&
        code[at].call()?.let { it.matches(entry) && it.definingClass == classes[entry.definingClass]?.superclass } == true
    }.any { at ->
        val inherited = classes[code[at].call()!!.definingClass]?.methods?.singleOrNull { it.matches(entry) && it.implementation != null }
        inherited != null && parsesItself(inherited, classes, depth - 1)
    }
}

/** Each guard that falls through only when the current field name equals [key]. */
private class KeyGuards(val code: List<Instruction>, val inputs: TextOrigins, val guards: List<Int>)

private fun keyGuards(parser: Method, key: String, classes: Map<String, ClassDef>, api: JsonReads,
                      accept: (Int, Map<Int, Int>) -> Boolean): KeyGuards {
    val code = parser.code()
    val keyAt = code.indices.filter { (code[it].reference() as? StringReference)?.string == key }
        .one("parser's $key key")
    val input = parserInput(parser)
    val receiver = parser.implementation!!.registerCount - 1
    val inputs = textOrigins(parser, receiver = receiver)
    fun inputCall(at: Int) = code[at].arguments().any { inputs.before[at]?.get(it) == RECEIVER }
    val mutating = code.indices.filter { code[it].call() != null && inputCall(it) }.toSet()
    val readers = code.indices.filter { at -> code[at].call()?.let { call -> call.returnType == STRING &&
        call.parameters() == listOf(input) && code[at].opcode in staticInvokes &&
        code[at].arguments().singleOrNull()?.let { inputs.before[at]?.get(it) == RECEIVER } == true
    } == true && code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT }
        .associateWith { at ->
            val call = code[at].call()!!
            val helper = classes[call.definingClass]?.methods?.filter { it.matches(call) }
                ?.singleOrNull() ?: refuse("missing JSON reader implementation")
            jsonReader(helper, api)
        }
    val names = readers.filterValues { it == FIELD_NAME }.keys.associate { it + 1 to FIELD_NAME }
    val fields = textOrigins(parser, fieldAt = keyAt, definitions = names, forgetAt = mutating)
    val guarded = mutableListOf<Int>()
    for (equalAt in code.indices.filter { code[it].call()?.toString() == "$STRING->equals($OBJECT)Z" }) {
        val arguments = code[equalAt].arguments()
        val state = fields.before[equalAt] ?: continue
        if (arguments.size != 2 || arguments.map { state[it] }.toSet() != setOf(ORIGINAL, FIELD_NAME) ||
            code.getOrNull(equalAt + 1)?.opcode != Opcode.MOVE_RESULT) continue
        val matches = textOrigins(parser, fieldAt = keyAt, definitions = names + (equalAt + 1 to KEY_MATCH),
            forgetAt = mutating)
        for (guard in code.indices.filter { code[it].opcode == Opcode.IF_EQZ &&
            matches.before[it]?.get((code[it] as OneRegisterInstruction).registerA) == KEY_MATCH }) {
            val read = guard + 1
            if (!accept(read, readers) || matches.flow.normal[guard].distinct().size != 2) continue
            if (matches.flow.normal.indices.filter { read in matches.flow.normal[it] } != listOf(guard) ||
                matches.flow.exceptional.any { read in it }) refuse("a path bypasses the $key key discriminator")
            guarded += guard
        }
    }
    return KeyGuards(code, inputs, guarded)
}

internal data class JsonReads(val name: MethodReference, val advance: MethodReference,
                              val value: MethodReference, val current: MethodReference)
private val staticInvokes = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

/** Jackson's retained root-unwrapping errors distinguish a current name from a string value. */
internal fun BytecodePatchContext.jsonReads(input: String, classes: Map<String, ClassDef>): JsonReads {
    val root = classesHolding(JSON_ROOT_FIELD, JSON_ROOT_MISMATCH).flatMap { it.methods.toList() }.filter {
        JSON_ROOT_FIELD in it.strings() && JSON_ROOT_MISMATCH in it.strings()
    }.one("JSON root-name semantics anchor")
    val parameters = root.parameters()
    val slot = parameters.indices.filter { parameters[it] == input }.one("root parser input")
    val receiver = root.implementation!!.registerCount - parameters.sumOf { if (it == "J" || it == "D") 2 else 1 } +
        parameters.take(slot).sumOf { if (it == "J" || it == "D") 2 else 1 }
    val code = root.code()
    val inputs = textOrigins(root, receiver = receiver)
    fun onInput(at: Int) = code[at].arguments().singleOrNull()?.let { inputs.before[at]?.get(it) == RECEIVER } == true
    val token = code.mapNotNull { it.field() }.mapNotNull { classes[it.type] }.distinctBy { it.type }.filter {
        AccessFlags.ENUM.isSet(it.accessFlags) && it.methods.any { method ->
            method.name == "<clinit>" && setOf("FIELD_NAME", "VALUE_STRING").all { name -> name in method.strings() }
        }
    }.one("JSON token enum")
    val fieldName = enumConstant(token, "FIELD_NAME")
    val stringValue = enumConstant(token, "VALUE_STRING")
    val nameAt = code.indices.filter { at -> code[at].call()?.let {
        it.definingClass == input && it.parameters().isEmpty() && it.returnType == STRING
    } == true && code[at].opcode !in staticInvokes && onInput(at) }.one("root current-name read")
    if (code.getOrNull(nameAt + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT ||
        code.getOrNull(nameAt + 2)?.call()?.toString() != "$STRING->equals($OBJECT)Z" ||
        code.getOrNull(nameAt + 3)?.opcode != Opcode.MOVE_RESULT ||
        code.getOrNull(nameAt + 4)?.opcode != Opcode.IF_NEZ) refuse("root name comparison changed shape")
    val advanceAt = code.indices.filter { at -> code[at].call()?.let {
        it.definingClass == input && it.parameters().isEmpty() && it.returnType == token.type
    } == true && onInput(at) && code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
        code.getOrNull(at + 2)?.field()?.toString() == fieldName.toString() &&
        code.getOrNull(at + 3)?.opcode == Opcode.IF_EQ &&
        inputs.flow.normal[at + 3].firstOrNull() == nameAt }.one("root field-name token advance")
    val guard = code[advanceAt + 3] as TwoRegisterInstruction
    val tokens = textOrigins(root, definitions = mapOf(advanceAt + 1 to TOKEN, advanceAt + 2 to FIELD_NAME))
    if (setOf(guard.registerA, guard.registerB) != setOf(
            (code[advanceAt + 1] as OneRegisterInstruction).registerA,
            (code[advanceAt + 2] as OneRegisterInstruction).registerA) || guard.registerA == guard.registerB ||
        listOf(guard.registerA, guard.registerB).map { tokens.before[advanceAt + 3]?.get(it) }.toSet() != setOf(TOKEN, FIELD_NAME) ||
        inputs.flow.normal.indices.filter { nameAt in inputs.flow.normal[it] } != listOf(advanceAt + 3) ||
        inputs.flow.exceptional.any { nameAt in it }) refuse("root name read bypasses its FIELD_NAME check")
    val equalAt = nameAt + 2
    val expectedAt = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT && code[it].field()?.type == STRING }
        .filter { at ->
            val origins = textOrigins(root, fieldAt = at, definitions = mapOf(nameAt + 1 to FIELD_NAME))
            code[equalAt].arguments().map { origins.before[equalAt]?.get(it) }.toSet() == setOf(ORIGINAL, FIELD_NAME)
        }.one("expected root name in equality")
    val equals = textOrigins(root, fieldAt = expectedAt,
        definitions = mapOf(nameAt + 1 to FIELD_NAME, nameAt + 3 to KEY_MATCH))
    if (equals.before[nameAt + 4]?.get((code[nameAt + 4] as OneRegisterInstruction).registerA) != KEY_MATCH) {
        refuse("root name equality result is replaced")
    }
    val next = inputs.flow.normal[nameAt + 4].first()
    if (code[next].call()?.toString() != code[advanceAt].call()?.toString() || !onInput(next)) {
        refuse("matched root name does not advance the same parser")
    }
    fun errorPath(start: Int, marker: String, stop: Int): Boolean {
        val pending = java.util.ArrayDeque<Int>().apply { add(start) }
        val seen = mutableSetOf<Int>()
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            if (at == stop || !seen.add(at)) continue
            if ((code[at].reference() as? StringReference)?.string == marker) return true
            pending.addAll(inputs.flow.normal[at]); pending.addAll(inputs.flow.exceptional[at])
        }
        return false
    }
    if (!errorPath(advanceAt + 4, JSON_ROOT_FIELD, nameAt) ||
        !errorPath(nameAt + 5, JSON_ROOT_MISMATCH, next)) refuse("root errors no longer describe the guarded name")
    val parser = classes[input] ?: refuse("missing native JSON input")
    // Jackson's nextTextValue: advance, and on VALUE_STRING answer the value-string accessor. 450 made
    // that accessor abstract, so it's found by what the base class calls rather than by its body.
    val value = parser.methods.filter { it.publicInstance() && it.parameters().isEmpty() && it.returnType == STRING }
        .mapNotNull { method -> method.code().takeIf { body -> body.size == 9 &&
            body[0].call()?.let { it.definingClass == input && it.parameters().isEmpty() && it.returnType == token.type } == true &&
            body[2].field()?.toString() == stringValue.toString() && body[3].opcode == Opcode.IF_NE &&
            body[6].opcode == Opcode.RETURN_OBJECT && body[8].opcode == Opcode.RETURN_OBJECT
        }?.get(4)?.call()?.takeIf { it.definingClass == input && it.parameters().isEmpty() && it.returnType == STRING } }
        .distinctBy { it.toString() }.one("JSON value-string API")
    val current = parser.methods.filter { it.publicInstance() && AccessFlags.ABSTRACT.isSet(it.accessFlags) &&
        it.parameters().isEmpty() && it.returnType == token.type && !it.matches(code[advanceAt].call()!!)
    }.one("JSON current-token API")
    for (api in listOf(code[nameAt].call()!!, code[advanceAt].call()!!)) {
        parser.methods.filter { it.matches(api) && it.publicInstance() }.one("anchored JSON API implementation")
    }
    return JsonReads(code[nameAt].call()!!, code[advanceAt].call()!!, value, current)
}

/** The label must be the enum constructor's name, and the stored singleton must be that allocation. */
private fun enumConstant(type: ClassDef, label: String): FieldReference {
    val init = type.methods.filter { it.name == "<clinit>" }.one("token enum initializer")
    val code = init.code()
    val labelAt = code.indices.filter { (code[it].reference() as? StringReference)?.string == label }.one("$label token label")
    val names = textOrigins(init, fieldAt = labelAt)
    val constructorAt = code.indices.filter { at -> code[at].call()?.let { it.definingClass == type.type && it.name == "<init>" } == true &&
        code[at].arguments().drop(1).any { names.before[at]?.get(it) == ORIGINAL }
    }.one("$label token constructor")
    val call = code[constructorAt].call()!!
    val argument = code[constructorAt].arguments().indices.filter {
        names.before[constructorAt]?.get(code[constructorAt].arguments()[it]) == ORIGINAL
    }.one("$label token name argument")
    val constructor = type.methods.filter { it.matches(call) }.one("token constructor implementation")
    val self = constructor.implementation!!.registerCount - code[constructorAt].arguments().size
    val passed = textOrigins(constructor, incoming = self + argument, receiver = self)
    if (constructor.code().indices.none { at -> constructor.code()[at].call()?.toString() ==
            "Ljava/lang/Enum;-><init>(${STRING}I)V" && constructor.code()[at].arguments().let {
            it.size == 3 && passed.before[at]?.get(it[0]) == RECEIVER && passed.before[at]?.get(it[1]) == ORIGINAL
        } }) refuse("$label is not the token's enum name")
    val allocation = code.indices.filter { code[it].opcode == Opcode.NEW_INSTANCE &&
        (code[it].reference() as? TypeReference)?.type == type.type &&
        textOrigins(init, fieldAt = it).before[constructorAt]?.get(code[constructorAt].arguments().first()) == ORIGINAL
    }.one("$label token allocation")
    val objects = textOrigins(init, fieldAt = allocation)
    return code.indices.filter { code[it].opcode == Opcode.SPUT_OBJECT && code[it].field()?.let {
        it.definingClass == type.type && it.type == type.type
    } == true && objects.before[it]?.get((code[it] as OneRegisterInstruction).registerA) == ORIGINAL }
        .map { code[it].field()!! }.one("$label token singleton")
}

/** Summarize a native reader only after its receiver, return and cursor advance are proved. */
private fun jsonReader(method: Method, api: JsonReads): Int {
    if (!AccessFlags.STATIC.isSet(method.accessFlags)) return UNKNOWN
    val code = method.code()
    val readAt = code.indices.filter { code[it].call()?.toString() in setOf(api.name.toString(), api.value.toString()) }
        .singleOrNull() ?: return UNKNOWN
    if (code.getOrNull(readAt + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return UNKNOWN
    val isName = code[readAt].call()?.toString() == api.name.toString()
    val origins = textOrigins(method, resultAt = readAt + 1, receiver = method.implementation!!.registerCount - 1)
    val advance = mutableSetOf<Int>()
    for (at in code.indices.filter { code[it].call() != null }) {
        val call = code[at].call()!!
        if (call.definingClass == api.name.definingClass || code[at].arguments().any { origins.before[at]?.get(it) == RECEIVER }) {
            if (code[at].opcode in staticInvokes || code[at].arguments().singleOrNull()?.let {
                    origins.before[at]?.get(it) == RECEIVER } != true) return UNKNOWN
            when (call.toString()) {
                api.name.toString() -> if (!isName) return UNKNOWN
                api.value.toString() -> if (isName) return UNKNOWN
                api.advance.toString() -> if (isName) advance += at else return UNKNOWN
                api.current.toString() -> if (isName) return UNKNOWN
                else -> return UNKNOWN
            }
        }
    }
    var returned = false
    val pending = java.util.ArrayDeque<Pair<Int, Int>>().apply { add(0 to 0) }
    val seen = mutableSetOf<Pair<Int, Int>>()
    while (pending.isNotEmpty()) {
        val (at, count) = pending.removeFirst()
        if (!seen.add(at to count)) continue
        if (at == readAt && count != 0) return UNKNOWN
        if (code[at].opcode == Opcode.RETURN_OBJECT) {
            val origin = origins.before[at]?.get((code[at] as OneRegisterInstruction).registerA)
            if (origin != ORIGINAL && !(origin == ABSENT && !isName) || isName && count != 1) return UNKNOWN
            if (origin == ORIGINAL) returned = true
        } else if (code[at].opcode in setOf(Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_VOID)) return UNKNOWN
        origins.flow.normal[at].forEach { pending.add(it to (count + if (at in advance) 1 else 0).coerceAtMost(2)) }
        origins.flow.exceptional[at].forEach { pending.add(it to count) }
    }
    return if (returned) { if (isName) FIELD_NAME else ORIGINAL } else UNKNOWN
}

/** Finding an unused field read isn't proof that the native getter returns the parsed value. */
internal fun parsedTextField(getter: Method, type: String, returns: String = STRING): FieldReference {
    if (!getter.publicInstance() || getter.parameterTypes.isNotEmpty() || getter.returnType != returns) {
        refuse("parsed getter ${getter.name} changed shape")
    }
    val code = getter.code()
    val readAt = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT &&
        code[it].field()?.let { field -> field.definingClass == type && field.type == returns } == true }
        .one("parsed field read in ${getter.name}")
    val origins = textOrigins(getter, fieldAt = readAt, receiver = getter.implementation!!.registerCount - 1)
    val read = code[readAt] as TwoRegisterInstruction
    if (origins.before[readAt]?.get(read.registerB) != RECEIVER) refuse("parsed getter reads a different receiver")
    val returns = code.indices.filter { origins.before[it] != null &&
        code[it].opcode in setOf(Opcode.RETURN_OBJECT, Opcode.RETURN, Opcode.RETURN_WIDE) }
    if (returns.isEmpty() || returns.any { code[it].opcode != Opcode.RETURN_OBJECT ||
            origins.before[it]!![(code[it] as OneRegisterInstruction).registerA] != ORIGINAL }) {
        refuse("a parsed getter path substitutes or bypasses the original field")
    }
    return code[readAt].field()!!
}

/**
 * The original read (the result at [resultAt], or the [incoming] parameter) must supply the argument
 * on every path, and that argument must supply the field of [valueType] on the returned model.
 */
internal fun constructorField(method: Method, resultAt: Int?, type: String, classes: Map<String, ClassDef>,
                              allowAbsent: Boolean = false, valueType: String = STRING, incoming: Int? = null,
                              what: String = "original text"): FieldReference {
    val code = method.code()
    val origins = textOrigins(method, resultAt = resultAt, incoming = incoming)
    val candidates = code.indices.flatMap { at ->
        val call = code[at].call()
        val state = origins.before[at]
        if (call == null || call.definingClass != type || call.name != "<init>" || state == null) emptyList()
        else {
            val arguments = code[at].arguments()
            val slots = listOf(OBJECT) + call.parameters().flatMap { if (it == "J" || it == "D") listOf(it, "") else listOf(it) }
            if (arguments.size != slots.size || arguments.any { it !in state.indices }) refuse("invalid text constructor arguments")
            arguments.indices.filter { slots[it] == valueType && state[arguments[it]] and ORIGINAL != 0 }.map { at to it }
        }
    }
    val (at, argument) = candidates.one("$what constructor argument")
    val allowed = ORIGINAL or if (allowAbsent) ABSENT else 0
    if (origins.before[at]!![code[at].arguments()[argument]] and allowed.inv() != 0) {
        refuse("a path bypasses or replaces the $what read")
    }
    val returned = returnedConstruction(method, at, type)
    val call = code[at].call()!!
    val constructor = classes[type]?.methods?.filter { it.matches(call) }?.one("text model constructor")
        ?: refuse("missing text model constructor")
    val receiver = constructor.implementation!!.registerCount - code[at].arguments().size
    val fields = textOrigins(constructor, incoming = receiver + argument, receiver = receiver)
    val body = fields.flow.instructions
    val writes = body.indices.filter { body[it].opcode == Opcode.IPUT_OBJECT && fields.before[it] != null &&
        body[it].field()?.let { field -> field.definingClass == type && field.type == valueType } == true }
    val field = writes.filter { fields.before[it]!![(body[it] as TwoRegisterInstruction).registerA] and ORIGINAL != 0 }
        .map { body[it].field()!! }.distinctBy { it.toString() }.one("$what field")
    val stores = writes.filter { body[it].field()?.toString() == field.toString() }.toSet()
    if (stores.any { index ->
            val write = body[index] as TwoRegisterInstruction
            fields.before[index]!![write.registerA] != ORIGINAL || fields.before[index]!![write.registerB] != RECEIVER
        }) refuse("a constructor path substitutes the $what or its receiver")
    // An exception from a store has not assigned the field. Only its normal edge carries the write.
    val pending = java.util.ArrayDeque<Int>().apply { add(0) }
    val seen = mutableSetOf<Int>()
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        if (!seen.add(index)) continue
        if (body[index].opcode == Opcode.RETURN_VOID) refuse("a constructor path bypasses the $what assignment")
        pending.addAll(fields.flow.exceptional[index])
        if (index !in stores) pending.addAll(fields.flow.normal[index])
    }
    if (code.indices.any { index -> code[index].field()?.toString() == field.toString() &&
            code[index].opcode == Opcode.IPUT_OBJECT &&
            (returned.before[index]?.get((code[index] as TwoRegisterInstruction).registerB)?.and(RECEIVER) ?: 0) != 0 }) {
        refuse("the returned model's $what field is overwritten")
    }
    return field
}

/** A proved constructor is useful only if every non-null return carries that initialized object. */
private fun returnedConstruction(method: Method, constructorAt: Int, type: String): TextOrigins {
    val code = method.code()
    val receiver = code[constructorAt].arguments().first()
    val allocation = code.indices.filter { code[it].opcode == Opcode.NEW_INSTANCE &&
        (code[it].reference() as? TypeReference)?.type == type &&
        textOrigins(method, fieldAt = it).before[constructorAt]?.get(receiver) == ORIGINAL }
        .one("verified model allocation")
    val objects = textOrigins(method, fieldAt = allocation, constructedAt = constructorAt)
    val allocated = mutableSetOf<Int>()
    val pending = java.util.ArrayDeque<Int>().apply { addAll(objects.flow.normal[allocation]) }
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (allocated.add(at)) {
            pending.addAll(objects.flow.normal[at])
            pending.addAll(objects.flow.exceptional[at])
        }
    }
    val returns = code.indices.filter { objects.before[it] != null &&
        code[it].opcode in setOf(Opcode.RETURN_OBJECT, Opcode.RETURN, Opcode.RETURN_WIDE) }
    var initializedReturn = false
    for (at in returns) {
        if (code[at].opcode != Opcode.RETURN_OBJECT) refuse("model conversion has a non-object return")
        when (objects.before[at]!![(code[at] as OneRegisterInstruction).registerA]) {
            RECEIVER -> initializedReturn = true
            ABSENT -> if (at in allocated) refuse("model conversion discards its allocated object for null")
            else -> refuse("a return substitutes or bypasses the verified model constructor")
        }
    }
    if (!initializedReturn) refuse("the verified model is discarded")
    if (code.indices.any { at -> at != constructorAt && code[at].call() != null &&
            code[at].arguments().any { (objects.before[at]?.get(it)?.and(RECEIVER) ?: 0) != 0 } }) {
        refuse("the verified model escapes before its return")
    }
    return objects
}

private const val UNKNOWN = 1
private const val ORIGINAL = 2
private const val ABSENT = 4
private const val RECEIVER = 8
private const val FIELD_NAME = 16
private const val KEY_MATCH = 32
private const val TOKEN = 64
private val objectMoves = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val constants = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)
internal data class TextOrigins(val flow: ControlFlow, val before: Array<IntArray?>)

/** Finite origin sets joined across every branch and pre-write exception edge. */
private fun textOrigins(method: Method, resultAt: Int? = null, incoming: Int? = null, receiver: Int? = null,
                        fieldAt: Int? = null, definitions: Map<Int, Int> = emptyMap(), constructedAt: Int? = null,
                        forgetAt: Set<Int> = emptySet()): TextOrigins {
    val flow = try { ControlFlow.of(method) } catch (failure: IllegalArgumentException) {
        refuse("unsupported text control flow: ${failure.message}")
    } catch (failure: ClassCastException) {
        refuse("unsupported text control flow: ${failure.message}")
    }
    if (flow.instructions.isEmpty()) refuse("empty text control flow")
    if (resultAt != null && (flow.normal.indices.any { it != resultAt - 1 && resultAt in flow.normal[it] } ||
        flow.exceptional.any { resultAt in it })) refuse("a path bypasses the original getter before its result")
    for (at in definitions.keys) {
        if (flow.normal.indices.any { it != at - 1 && at in flow.normal[it] } || flow.exceptional.any { at in it }) {
            refuse("a path bypasses the parser discriminator's result")
        }
    }
    val before = arrayOfNulls<IntArray>(flow.instructions.size)
    val start = IntArray(method.implementation!!.registerCount) { UNKNOWN }
    incoming?.let { start[it] = ORIGINAL }
    receiver?.let { start[it] = RECEIVER }
    before[0] = start
    val pending = java.util.ArrayDeque<Int>().apply { add(0) }
    fun merge(at: Int, state: IntArray) {
        val previous = before[at]
        if (previous == null) {
            before[at] = state.clone()
            pending.add(at)
        } else {
            var changed = false
            for (register in previous.indices) {
                val joined = previous[register] or state[register]
                if (joined != previous[register]) { previous[register] = joined; changed = true }
            }
            if (changed) pending.add(at)
        }
    }
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        val state = before[at]!!.clone()
        val instruction = flow.instructions[at]
        if (at in forgetAt) for (register in state.indices) {
            if (state[register] and (FIELD_NAME or KEY_MATCH) != 0) state[register] = UNKNOWN
        }
        val after = state.clone()
        val destination = (instruction as? OneRegisterInstruction)?.registerA
        if (instruction.opcode.setsRegister() && destination != null) {
            if (destination !in state.indices || instruction.opcode.setsWideRegister() && destination + 1 !in state.indices) {
                refuse("invalid register in original text flow")
            }
            after[destination] = when {
                at in definitions -> definitions.getValue(at)
                at == resultAt || at == fieldAt -> ORIGINAL
                instruction.opcode in objectMoves -> state[(instruction as TwoRegisterInstruction).registerB]
                instruction.opcode == Opcode.CHECK_CAST -> state[destination]
                instruction.opcode in constants && (instruction as NarrowLiteralInstruction).narrowLiteral == 0 -> ABSENT
                else -> UNKNOWN
            }
            if (instruction.opcode.setsWideRegister()) after[destination + 1] = UNKNOWN
        }
        if (at == constructedAt) {
            if (state[instruction.arguments().first()] != ORIGINAL) refuse("ambiguous model constructor receiver")
            for (register in after.indices) if (after[register] and ORIGINAL != 0) {
                after[register] = (after[register] and ORIGINAL.inv()) or RECEIVER
            }
        }
        flow.normal[at].forEach { merge(it, after) }
        flow.exceptional[at].forEach { merge(it, state) }
    }
    return TextOrigins(flow, before)
}

internal fun requireConstructor(type: ClassDef, parameters: List<String>): Method {
    requirePublic(type)
    return type.methods.filter { it.name == "<init>" && it.publicInstance() && it.parameters() == parameters }
        .one("public constructor on ${type.type}")
}
internal fun requirePublic(type: ClassDef) {
    if (!AccessFlags.PUBLIC.isSet(type.accessFlags)) refuse("native type isn't public: ${type.type}")
}
internal fun requirePublicField(type: ClassDef, field: FieldReference) {
    if (type.fields.none { it.name == field.name && it.type == field.type && AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
        refuse("native field isn't public: $field")
    }
}
internal fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
internal fun Method.strings(): List<String> = code().mapNotNull { (it.reference() as? StringReference)?.string }
internal fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
internal fun Instruction.call() = reference() as? MethodReference
internal fun Instruction.field() = reference() as? FieldReference
internal fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
    is Instruction35c -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}
internal fun MethodReference.parameters() = parameterTypes.map(Any::toString)
internal fun MethodReference.matches(other: MethodReference) =
    name == other.name && returnType == other.returnType && parameters() == other.parameters()
internal fun Method.publicInstance() = AccessFlags.PUBLIC.isSet(accessFlags) && !AccessFlags.STATIC.isSet(accessFlags)
/** The constant [register] last took before [at], counting only a const write. */
internal fun Method.constantBefore(at: Int, register: Int): Int? =
    code().take(at).lastOrNull { it.writes(register) }?.takeIf { it.opcode in constants }
        ?.let { it as? NarrowLiteralInstruction }?.narrowLiteral
internal fun Instruction.writes(register: Int) = opcode.setsRegister() &&
    (this as? OneRegisterInstruction)?.let { it.registerA == register || opcode.setsWideRegister() && it.registerA + 1 == register } == true
internal fun <T> Collection<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")
/** Stops the comment family being discovered, naming it, before anything has changed. */
internal fun refuse(detail: String): Nothing = throw PatchException("${discoveringPatch.get()}: $detail")

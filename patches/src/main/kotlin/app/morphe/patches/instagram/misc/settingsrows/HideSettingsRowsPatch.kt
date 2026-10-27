/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.settingsrows

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

internal const val SETTINGS_ROWS_PATCH = "Hide rows in Instagram's settings"

internal const val SETTINGS_ROWS = "$EXTENSION_PACKAGE/misc/SettingsRows;"
internal const val ROW_HIDDEN = "$SETTINGS_ROWS->hidden(Ljava/lang/Object;)Z"
internal const val ROW_BUILT = "$SETTINGS_ROWS->built(Ljava/lang/Object;)V"

/** What the row Instagram's settings screen draws says first in its toString, which only that class loads. */
internal const val ROW_TEXT = "NavigationRowUiState(visible="

/** What the row's toString appends right before its title. */
internal const val LABEL_TEXT = ", label="

/** The file of the methods that turn a row model into a row, a class Instagram keeps by name. */
internal const val UI_STATE = "Lcom/instagram/settings2/core/viewmodel/UiStateKt;"

/** The package of the settings screen's session, which every builder also takes once. */
internal const val SESSION = "Lcom/instagram/settings2/core/session/"

internal const val FBT_MODEL = "Lcom/instagram/settings2/core/model/FbtModel;"
internal const val FBT_TOKEN = "Lcom/instagram/settings2/core/model/FbtModelToken;"
internal const val RESOURCES = "Landroid/content/res/Resources;"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val APPEND_OBJECT = "Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;"

/**
 * Leaves the rows the user picks off Instagram's own Settings and activity screen. See SettingsRows
 * in the extension. Nothing is hidden until rows are picked, so it's in the default selection.
 */
@Suppress("unused")
val hideSettingsRowsPatch = bytecodePatch(
    name = "Hide rows in Instagram's settings",
    description = "Lets you take the rows you don't use off Instagram's own Settings and activity screen. Open " +
        "that screen once, then untick rows in HushGram settings > Layout > Rows in Instagram's settings. Every " +
        "row shows to start.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("settingsRows")
        hideSettingsRows(findSettingsRows())
        enableStatus("settingsRows")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$SETTINGS_ROWS_PATCH: $why")

/** One method making rows: the method, where its row model comes in, and where it makes the row. */
internal class RowBuilder(val method: MutableMethod, val modelRegister: String, val made: Int, val rowRegister: Int)

/** The row builders, the fields the stubs read, Instagram's title renderer, and the extension's four stubs. */
internal class SettingsRowTargets(
    val builders: List<RowBuilder>,
    val model: String,
    val modelId: FieldReference,
    val row: String,
    val rowId: FieldReference,
    val rowTitle: FieldReference,
    val renderer: Method,
    val idOfModel: MutableMethod,
    val idOfRow: MutableMethod,
    val titleOfRow: MutableMethod,
    val text: MutableMethod,
)

/**
 * Finds everything before anything changes. The row is the one class loading [ROW_TEXT]. The
 * builders are the static methods of [UI_STATE] answering Object that make one of those rows, with
 * one constructor call each. The row model is the one parameter type every builder takes once that
 * shares a field type with the row, each class holding that type once: that field is the row's id.
 * The title is the field the row's toString appends right after [LABEL_TEXT]. The renderer is the
 * one static (Resources, FbtModel)String method among the classes calling into [FBT_TOKEN]. Fails
 * when any of it isn't so.
 */
internal fun BytecodePatchContext.findSettingsRows(): SettingsRowTargets {
    val rows = classesLoadingString(ROW_TEXT).filter { holder -> holder.methods.any { it.name == "toString" } }
    val row = rows.singleOrNull() ?: refuse("expected one row class loading \"$ROW_TEXT\", found ${rows.size}")

    val uiState = classDefByOrNull(UI_STATE) ?: refuse("the build has no $UI_STATE")
    val makers = uiState.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == OBJECT &&
            method.implementation?.instructions?.any { it.opcode == Opcode.NEW_INSTANCE && it.typeIs(row.type) } == true
    }
    if (makers.isEmpty()) refuse("no static method of $UI_STATE makes a ${row.type}")

    val shared = makers.map { method -> method.parameterTypes.map(Any::toString).groupingBy { it }.eachCount().filterValues { it == 1 }.keys }
        .reduce { kept, next -> kept intersect next }
    val ids = shared.filter { it.startsWith("L") && !it.startsWith("Ljava/") && !it.startsWith("Landroid/") && !it.startsWith(SESSION) }.mapNotNull { type ->
        val model = classDefByOrNull(type) ?: return@mapNotNull null
        val modelOnce = model.fields.groupBy { it.type }.filterValues { it.size == 1 }
        // The id is an interface Instagram's row id enum implements, so a value or a session field never counts.
        val pairs = modelOnce.mapNotNull { (fieldType, fields) ->
            row.fields.filter { it.type == fieldType }.singleOrNull()?.let { rowField -> fields.single() to rowField }
        }.filter { (field, _) ->
            field.type.startsWith("L") && classDefByOrNull(field.type)?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
        }
        pairs.singleOrNull()?.let { Triple(model, it.first, it.second) }
    }
    val (model, modelField, rowField) = ids.singleOrNull()
        ?: refuse("expected one row model taken by every builder sharing one id field with ${row.type}, found ${ids.size}")

    val builders = makers.map { method -> builderOf(method, model.type, row.type) }
    val rowTitle = titleField(row)
    val renderer = findRenderer()

    val extension = mutableClassDefBy(SETTINGS_ROWS)
    for (hook in listOf(ROW_HIDDEN, ROW_BUILT)) {
        if (extension.methods.none { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        ) refuse("the extension has no public static $hook")
    }
    fun stub(name: String, parameters: List<String>, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == parameters
    } ?: refuse("$SETTINGS_ROWS has no static $returns $name(${parameters.joinToString("")})")

    return SettingsRowTargets(
        builders = builders,
        model = model.type,
        modelId = modelField.reference(),
        row = row.type,
        rowId = rowField.reference(),
        rowTitle = rowTitle,
        renderer = renderer,
        idOfModel = stub("idOfModel", listOf(OBJECT), OBJECT),
        idOfRow = stub("idOfRow", listOf(OBJECT), OBJECT),
        titleOfRow = stub("titleOfRow", listOf(OBJECT), OBJECT),
        text = stub("text", listOf(RESOURCES, OBJECT), STRING),
    )
}

private fun Instruction.typeIs(type: String) =
    ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type == type

private fun Field.reference(): FieldReference = ImmutableFieldReference(definingClass, name, type)

/**
 * A builder's row model parameter register and its one constructor call of the row, checked: it
 * has a local for the answer, nothing jumps to its first instruction or right after the call.
 */
private fun BytecodePatchContext.builderOf(method: Method, model: String, row: String): RowBuilder {
    val where = "$UI_STATE->${method.name}"
    val parameters = method.parameterTypes.map(Any::toString)
    val at = parameters.indexOf(model)
    val register = parameters.take(at).sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
    if (method.localRegisterCount() < 1) refuse("$where has no local for the answer")
    val targets = method.jumpTargets()
    if (0 in targets) refuse("a jump or exception handler enters $where at its first instruction")

    val code = method.implementation!!.instructions.toList()
    val calls = code.indices.filter { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        (code[index].opcode == Opcode.INVOKE_DIRECT || code[index].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            call?.definingClass == row && call.name == "<init>"
    }
    val made = calls.singleOrNull() ?: refuse("expected one $row constructor call in $where, found ${calls.size}")
    if (made + 1 in targets) refuse("a jump lands right after the $row constructor call in $where")
    val rowRegister = when (val call = code[made]) {
        is RegisterRangeInstruction -> call.startRegister
        is FiveRegisterInstruction -> call.registerC
        else -> refuse("the $row constructor call in $where names no registers")
    }
    val mutable = mutableClassDefBy(UI_STATE).methods.single { candidate ->
        candidate.name == method.name && candidate.parameterTypes.map(Any::toString) == parameters && candidate.returnType == method.returnType
    }
    return RowBuilder(mutable, "p$register", made, rowRegister)
}

/** The row field the row's toString appends right after it appends [LABEL_TEXT]. It has to hold a FbtModel. */
private fun BytecodePatchContext.titleField(row: ClassDef): FieldReference {
    val code = row.methods.single { it.name == "toString" && it.parameterTypes.isEmpty() }.implementation!!.instructions.toList()
    val label = code.indices.firstOrNull { stringLoadedAt(code, it) == LABEL_TEXT }
        ?: refuse("${row.type}'s toString loads no \"$LABEL_TEXT\"")
    val append = code.drop(label + 1).firstOrNull { (it as? ReferenceInstruction)?.reference?.toString() == APPEND_OBJECT }
        as? FiveRegisterInstruction ?: refuse("${row.type}'s toString appends nothing after \"$LABEL_TEXT\"")
    val reads = code.take(label).filter { read ->
        read.opcode == Opcode.IGET_OBJECT && (read as TwoRegisterInstruction).registerA == append.registerD &&
            ((read as ReferenceInstruction).reference as FieldReference).definingClass == row.type
    }
    val field = (reads.lastOrNull() as? ReferenceInstruction)?.reference as? FieldReference
        ?: refuse("${row.type}'s toString appends no field of its own after \"$LABEL_TEXT\"")
    if (field.type != FBT_MODEL) refuse("${row.type}'s title field ${field.name} holds ${field.type}, not $FBT_MODEL")
    return field
}

/** Instagram's renderer of a title model: the one static (Resources, FbtModel)String among the classes calling into [FBT_TOKEN]. */
private fun BytecodePatchContext.findRenderer(): Method {
    val renderers = classesCallingInto(FBT_TOKEN).flatMap { holder ->
        holder.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == STRING &&
                method.parameterTypes.map(Any::toString) == listOf(RESOURCES, FBT_MODEL)
        }
    }
    return renderers.singleOrNull()
        ?: refuse("expected one static ($RESOURCES$FBT_MODEL)$STRING calling into $FBT_TOKEN, found ${renderers.size}")
}

/**
 * Fills the four stubs with reads of Instagram's fields and its renderer, hands each row a builder
 * makes to [ROW_BUILT] right after its constructor, then asks [ROW_HIDDEN] first thing in each
 * builder with its row model. A yes answers null, as the builder does for a row that shouldn't show.
 */
internal fun BytecodePatchContext.hideSettingsRows(found: SettingsRowTargets = findSettingsRows()) {
    found.idOfModel.addInstructions(
        0,
        """
            check-cast p0, ${found.model}
            iget-object p0, p0, ${found.modelId}
            return-object p0
        """,
    )
    found.idOfRow.addInstructions(
        0,
        """
            check-cast p0, ${found.row}
            iget-object p0, p0, ${found.rowId}
            return-object p0
        """,
    )
    found.titleOfRow.addInstructions(
        0,
        """
            check-cast p0, ${found.row}
            iget-object p0, p0, ${found.rowTitle}
            return-object p0
        """,
    )
    val renderer = found.renderer
    found.text.addInstructions(
        0,
        """
            check-cast p1, $FBT_MODEL
            invoke-static { p0, p1 }, ${renderer.definingClass}->${renderer.name}($RESOURCES$FBT_MODEL)$STRING
            move-result-object p0
            return-object p0
        """,
    )
    for (builder in found.builders) {
        val method = builder.method
        // The later insertion first, so the first one doesn't move the constructor call.
        method.addInstructions(builder.made + 1, "invoke-static/range { v${builder.rowRegister} .. v${builder.rowRegister} }, $ROW_BUILT")
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { ${builder.modelRegister} .. ${builder.modelRegister} }, $ROW_HIDDEN
                move-result v0
                if-eqz v0, :instagram
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("instagram", method.getInstruction(0)),
        )
    }
}

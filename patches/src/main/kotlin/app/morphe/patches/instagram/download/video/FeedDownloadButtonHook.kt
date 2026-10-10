/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.share.findFeedUfiSite
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val PATCH = "Download any video"

internal const val FEED_BUTTON =
    "$EXTENSION_PACKAGE/download/FeedDownloadButton;->bind(Landroid/view/View;Ljava/lang/Object;Ljava/lang/Object;Landroid/app/Activity;)V"

/** Feed's inflated Save button in Instagram 450 (`row_feed_button_save`), the same id in every 450 build. */
internal const val SAVE_BUTTON_ID = 0x7f0b370e

private const val BOUNCY = "Lcom/instagram/ui/widget/bouncyufibutton/IgBouncyUfiButtonImageView;"
private const val ACTIVITY = "Landroid/app/Activity;"

/**
 * Where a feed post's action row is bound, and what the "Download button on feed posts" hook reads
 * there: the holder's Save button ([save]), the post the row's state keeps ([media], read from the
 * parameter at [state]), the feed state at [item] that knows the carousel page on screen, and the
 * activity the binder keeps ([activity]). [holder] is the parameter that holds the row's views.
 */
internal class FeedButtonSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val holder: Int,
    val state: Int,
    val item: Int,
    val save: FieldReference,
    val media: FieldReference,
    val activity: FieldReference,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the binder Hide the Repost button hooks (the one inflating `reposts_ufi_icon`), then what
 * the button hook reads in it, all by structure: the holder is the parameter of the class that
 * keeps the repost icon, its Save button the bouncy field its constructor stores the view
 * [SAVE_BUTTON_ID] names into, the state the one other parameter holding a [MEDIA] field, the feed
 * state the one parameter of the type [page] reads the carousel page from, and the activity the
 * binder's one Activity field. Fails naming what it didn't find exactly once.
 */
internal fun BytecodePatchContext.findFeedButtonSite(page: PageIndex): FeedButtonSite {
    val ufi = try {
        findFeedUfiSite()
    } catch (failure: PatchException) {
        refuse("the Feed action-row binder isn't found (${failure.message})")
    }
    val binder = classDefByOrNull(ufi.type) ?: refuse("${ufi.type} is missing")
    val binders = binder.methods.filter { it.name == ufi.name && it.parameterTypes.map(CharSequence::toString) == ufi.parameters }
    val method = binders.singleOrNull() ?: refuse("expected one ${ufi.type}->${ufi.name}, found ${binders.size}")
    if (AccessFlags.STATIC.isSet(method.accessFlags)) refuse("${ufi.type}->${ufi.name} is static")
    val holderType = ufi.icon.definingClass

    val holders = ufi.parameters.indices.filter { ufi.parameters[it] == holderType }
    val holder = holders.singleOrNull() ?: refuse("${ufi.type}->${ufi.name} takes the row's holder ${holders.size} times")
    val holderClass = classDefByOrNull(holderType) ?: refuse("$holderType is missing")
    val saves = holderClass.methods.flatMap { constructor ->
        val code = constructor.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        code.indices.filter { code[it].loadsLiteral(SAVE_BUTTON_ID) }.mapNotNull { code.fieldStoreAfter(it, holderType) }
    }.distinctBy { it.toString() }
    val save = saves.singleOrNull() ?: refuse("expected one Save button stored from view $SAVE_BUTTON_ID in $holderType, found ${saves.size}")

    val states = ufi.parameters.indices.filter { index ->
        index != holder && classDefByOrNull(ufi.parameters[index])?.fields?.count {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == MEDIA
        } == 1
    }
    val state = states.singleOrNull() ?: refuse("expected one parameter of ${ufi.type}->${ufi.name} holding a $MEDIA, found ${states.size}")
    val media = classDefBy(ufi.parameters[state]).fields.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == MEDIA }

    val items = ufi.parameters.indices.filter { ufi.parameters[it] == page.index.definingClass }
    val item = items.singleOrNull() ?: refuse(
        "expected one parameter of ${ufi.type}->${ufi.name} for the carousel page's feed state ${page.index.definingClass}, found ${items.size}",
    )
    if (item == state || item == holder) refuse("${ufi.type}->${ufi.name}'s feed state is also its row state or holder")

    val activities = binder.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == ACTIVITY }
    val activity = activities.singleOrNull() ?: refuse("expected one Activity field in ${ufi.type}, found ${activities.size}")

    return FeedButtonSite(
        ufi.type, ufi.name, ufi.parameters, holder, state, item,
        save,
        ImmutableFieldReference(media.definingClass, media.name, media.type),
        ImmutableFieldReference(activity.definingClass, activity.name, activity.type),
    )
}

/**
 * First thing in the binder, hands [FEED_BUTTON] the holder's Save button, the post, the feed state
 * and the activity. Nothing is live yet but the parameters, so the four locals it borrows are free;
 * each parameter is copied with a 16-bit move since its register can be past what a field read
 * reaches.
 */
internal fun BytecodePatchContext.addFeedDownloadButton(site: FeedButtonSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.requireLocals(PATCH, 4)
    val self = method.localRegisterCount()
    method.addInstructions(
        0,
        """
            move-object/from16 v0, v${method.parameterRegisterNumber(site.holder)}
            iget-object v0, v0, ${site.save}
            move-object/from16 v1, v${method.parameterRegisterNumber(site.state)}
            iget-object v1, v1, ${site.media}
            move-object/from16 v2, v${method.parameterRegisterNumber(site.item)}
            move-object/from16 v3, v$self
            iget-object v3, v3, ${site.activity}
            invoke-static { v0, v1, v2, v3 }, $FEED_BUTTON
        """,
    )
}

private fun Instruction.loadsLiteral(value: Int) =
    this is NarrowLiteralInstruction && opcode == Opcode.CONST && narrowLiteral == value

/** The bouncy field of [holder] stored within a few instructions after the view lookup at [start]. */
private fun List<Instruction>.fieldStoreAfter(start: Int, holder: String): FieldReference? {
    for (at in start + 1 until minOf(size, start + 12)) {
        val instruction = this[at]
        if (instruction.opcode != Opcode.IPUT_OBJECT || instruction !is TwoRegisterInstruction) continue
        val field = ((instruction as? ReferenceInstruction)?.reference as? FieldReference) ?: continue
        if (field.definingClass == holder && field.type == BOUNCY) return field
    }
    return null
}

// The rows Home draws as components (#97).

internal const val LITHO_BUTTON =
    "$EXTENSION_PACKAGE/download/FeedDownloadButton;->litho(Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;)V"
private const val FEED_BUTTON_CLASS = "$EXTENSION_PACKAGE/download/FeedDownloadButton;"

/** The Feed action row's own id (`row_feed_view_group_buttons`), set on the host that holds the buttons. */
internal const val ROW_BUTTONS_ID = 0x7f0b374c

private const val OBJECT = "Ljava/lang/Object;"
private const val CHAR_SEQUENCE_TYPE = "Ljava/lang/CharSequence;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val SCALE_TYPE = "Landroid/widget/ImageView\$ScaleType;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private val LISTS = setOf("Ljava/util/AbstractCollection;", "Ljava/util/ArrayList;", "Ljava/util/List;", "Ljava/util/Collection;")

/**
 * Where the component-backed Feed row builds its Save button, and what the hook there needs. [at]
 * is the instruction right after the constructor of Save's icon spec ([specType], built with
 * [specConstructor]), where [spec] holds that spec, [list] the list of the right-hand buttons the
 * spec's button is about to join, and [state] the row's state ([stateType]).
 *
 * The spec keeps its modifier ([modifier], of [modifierType]), scale type, default color and flag.
 * The styles [id], [click], [longClick] and [description] are the calls Save's own modifier is
 * built with, found by where they sit around Save's id.
 */
internal class LithoSaveSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val at: Int,
    val list: Int,
    val spec: Int,
    val state: Int,
    val specType: String,
    val specConstructor: MethodReference,
    val modifierType: String,
    val modifier: FieldReference,
    val scale: FieldReference,
    val color: FieldReference,
    val flag: FieldReference,
    val id: MethodReference,
    val click: MethodReference,
    val longClick: MethodReference,
    val description: MethodReference,
    val stateType: String,
    val media: FieldReference,
    val item: FieldReference,
)

private fun Instruction.calls(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.takes(vararg types: String) = parameterTypes.map(CharSequence::toString) == types.toList()

private fun Instruction.writes(register: Int): Boolean =
    opcode.setsRegister() && this is OneRegisterInstruction &&
        (registerA == register || (opcode.setsWideRegister() && registerA + 1 == register))

/**
 * Finds the one method that composes the Feed action row ([ROW_BUTTONS_ID]) with its Save button
 * ([SAVE_BUTTON_ID]) as a component, and in it Save's icon spec: the constructor taking a scale
 * type, a modifier, an Integer, two ints and a flag, right after the modifier is given Save's id.
 * What differs in a build is found by that shape, and the registers the hook reads are checked to
 * keep their value up to the hook. Fails naming what it didn't find.
 */
internal fun BytecodePatchContext.findLithoSaveSite(button: FeedButtonSite, page: PageIndex): LithoSaveSite {
    val stateType = button.media.definingClass
    val builders = classesLoading(SAVE_BUTTON_ID.toLong()).filter { !it.type.startsWith(EXTENSION_ROOT) }.flatMap { classDef ->
        classDef.methods.filter { method ->
            val code = method.implementation?.instructions?.toList() ?: return@filter false
            !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.returnType.startsWith("L") &&
                code.any { it.loadsLiteral(SAVE_BUTTON_ID) } && code.any { it.loadsLiteral(ROW_BUTTONS_ID) }
        }
    }
    val builder = builders.singleOrNull()
        ?: refuse("expected one component row builder loading Save ($SAVE_BUTTON_ID) and the row ($ROW_BUTTONS_ID), found ${builders.size}")
    val label = "${builder.definingClass}->${builder.name}"
    val code = builder.implementation!!.instructions.toList()

    val saves = code.indices.filter { code[it].loadsLiteral(SAVE_BUTTON_ID) }
    val idAt = saves.singleOrNull() ?: refuse("$label loads Save's id ${saves.size} times")
    val idRegister = (code[idAt] as OneRegisterInstruction).registerA
    val idCall = (idAt + 1..minOf(code.lastIndex, idAt + 3)).firstOrNull { at ->
        val call = code[at].calls()
        call != null && code[at].opcode == Opcode.INVOKE_STATIC && call.returnType.startsWith("L") &&
            call.takes(call.returnType, "I") && (code[at] as FiveRegisterInstruction).let {
                it.registerCount == 2 && it.registerD == idRegister
            }
    } ?: refuse("$label gives Save's id to no modifier style")
    val idStyle = code[idCall].calls()!!
    val modifierType = idStyle.returnType

    val constructorAt = (idCall + 1 until code.size).firstOrNull { at ->
        val call = code[at].calls()
        call != null && (code[at].opcode == Opcode.INVOKE_DIRECT || code[at].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            call.name == "<init>" && call.takes(SCALE_TYPE, modifierType, INTEGER, "I", "I", "Z")
    } ?: refuse("$label builds no icon spec (scale type, modifier, Integer, two ints, flag) after Save's id")
    val constructor = code[constructorAt].calls()!!
    val specType = constructor.definingClass
    val spec = when (val instruction = code[constructorAt]) {
        is RegisterRangeInstruction -> instruction.startRegister
        is FiveRegisterInstruction -> instruction.registerC
        else -> refuse("$label's icon spec constructor has no receiver")
    }

    val functionStyles = (idCall + 1 until constructorAt).mapNotNull { at ->
        code[at].calls()?.takeIf {
            code[at].opcode == Opcode.INVOKE_STATIC && it.returnType == modifierType && it.takes(modifierType, FUNCTION1)
        }
    }
    if (functionStyles.size != 2) {
        refuse("expected a tap style and a long press style between Save's id and its icon in $label, found ${functionStyles.size}")
    }
    val descriptions = (maxOf(0, idAt - 30) until idAt).mapNotNull { at ->
        code[at].calls()?.takeIf {
            code[at].opcode == Opcode.INVOKE_STATIC && it.returnType == modifierType && it.takes(modifierType, CHAR_SEQUENCE_TYPE)
        }
    }
    val description = descriptions.lastOrNull() ?: refuse("$label gives Save no description style before its id")

    val specClass = classDefByOrNull(specType) ?: refuse("$specType is missing")
    val constructors = specClass.methods.filter {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == constructor.parameterTypes.map(CharSequence::toString)
    }
    val specBuilder = constructors.singleOrNull() ?: refuse("expected one $specType constructor, found ${constructors.size}")
    val stored = specBuilder.implementation!!.instructions.toList()
    val locals = specBuilder.localRegisterCount()
    fun kept(parameter: Int, type: String): FieldReference {
        val fields = stored.filter {
            (it.opcode == Opcode.IPUT || it.opcode == Opcode.IPUT_OBJECT || it.opcode == Opcode.IPUT_BOOLEAN) &&
                it is TwoRegisterInstruction && it.registerA == locals + 1 + parameter && it.registerB == locals
        }.mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
            .filter { it.type == type && it.definingClass == specType }
        return fields.singleOrNull() ?: refuse("expected one field of $specType kept from constructor parameter $parameter, found ${fields.size}")
    }
    val scale = kept(0, SCALE_TYPE)
    val modifier = kept(1, modifierType)
    kept(2, INTEGER)
    val drawable = kept(3, "I")
    val color = kept(4, "I")
    val flag = kept(5, "Z")
    if (drawable == color) refuse("$specType keeps its drawable and its color in one field")

    val addAt = (constructorAt + 1 until minOf(code.size, constructorAt + 40)).firstOrNull { at ->
        val call = code[at].calls()
        call != null && (code[at].opcode == Opcode.INVOKE_VIRTUAL || code[at].opcode == Opcode.INVOKE_INTERFACE) &&
            call.name == "add" && call.definingClass in LISTS && call.returnType == "Z" && call.takes(OBJECT)
    } ?: refuse("$label adds Save's button to no list after its icon spec")
    val list = (code[addAt] as FiveRegisterInstruction).registerC
    for (at in constructorAt + 1 until addAt) {
        if (code[at].writes(list)) refuse("$label changes the button list between Save's icon spec and its add")
    }
    val at = constructorAt + 1
    val flow = ControlFlow.of(builder)
    if (flow.normal.indices.any { from -> from != constructorAt && at in flow.normal[from] }) {
        refuse("$label is branched to right after Save's icon spec")
    }

    val castAt = code.indices.firstOrNull { index ->
        index < idAt && code[index].opcode == Opcode.CHECK_CAST &&
            ((code[index] as ReferenceInstruction).reference as? TypeReference)?.type == stateType
    } ?: refuse("$label never narrows to the row state $stateType before Save")
    val state = (code[castAt] as OneRegisterInstruction).registerA
    for (index in castAt + 1..constructorAt) {
        val instruction = code[index]
        val narrowsAgain = instruction.opcode == Opcode.CHECK_CAST && (instruction as OneRegisterInstruction).registerA == state &&
            ((instruction as ReferenceInstruction).reference as? TypeReference)?.type == stateType
        if (!narrowsAgain && instruction.writes(state)) {
            refuse("$label puts something else in the row state's register v$state before Save's icon spec")
        }
    }
    if (list == spec || list == state || spec == state) refuse("$label keeps the list, the spec and the state in one register")

    val stateClass = classDefByOrNull(stateType) ?: refuse("$stateType is missing")
    val items = stateClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == page.index.definingClass }
    val item = items.singleOrNull()
        ?: refuse("expected one ${page.index.definingClass} field in $stateType for the carousel page's feed state, found ${items.size}")

    return LithoSaveSite(
        builder.definingClass, builder.name, builder.parameterTypes.map(CharSequence::toString), at, list, spec, state,
        specType, constructor, modifierType, modifier, scale, color, flag,
        idStyle, functionStyles[0], functionStyles[1], description,
        stateType, button.media, ImmutableFieldReference(item.definingClass, item.name, item.type),
    )
}

/**
 * Right after Save's icon spec is made, hands [LITHO_BUTTON] the button list, the spec and the row
 * state, and writes the three bridges it asks Instagram's classes through. Three locals nothing
 * reads afterwards carry the arguments.
 */
internal fun BytecodePatchContext.addLithoDownloadButton(site: LithoSaveSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val bridges = mutableClassDefBy(FEED_BUTTON_CLASS)
    fun stub(name: String, vararg parameters: String, returns: String) = bridges.methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterTypes.map(CharSequence::toString) == parameters.toList()
    } ?: refuse("$FEED_BUTTON_CLASS has no static $returns $name(${parameters.joinToString()})")
    val postOf = stub("lithoPost", OBJECT, returns = OBJECT)
    val itemOf = stub("lithoItem", OBJECT, returns = OBJECT)
    val iconOf = stub("lithoIcon", OBJECT, OBJECT, OBJECT, "I", CHAR_SEQUENCE_TYPE, "I", returns = OBJECT)
    val (list, spec, state) = method.freeLocalsAt(PATCH, site.at, 3, except = listOf(site.list, site.spec, site.state))

    method.addInstructions(
        site.at,
        """
            move-object/from16 v$list, v${site.list}
            move-object/from16 v$spec, v${site.spec}
            move-object/from16 v$state, v${site.state}
            invoke-static { v$list, v$spec, v$state }, $LITHO_BUTTON
        """,
    )
    postOf.addInstructions(
        0,
        """
            check-cast p0, ${site.stateType}
            iget-object p0, p0, ${site.media}
            return-object p0
        """,
    )
    itemOf.addInstructions(
        0,
        """
            check-cast p0, ${site.stateType}
            iget-object p0, p0, ${site.item}
            return-object p0
        """,
    )
    bridges.methods.remove(iconOf)
    bridges.methods.add(
        ImmutableMethod(
            iconOf.definingClass, iconOf.name, iconOf.parameters, iconOf.returnType, iconOf.accessFlags, iconOf.annotations,
            iconOf.hiddenApiRestrictions, ImmutableMethodImplementation(8 + 6, emptyList(), null, null),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    check-cast p0, ${site.specType}
                    iget-object v0, p0, ${site.modifier}
                    invoke-static { v0, p3 }, ${site.id}
                    move-result-object v0
                    check-cast p1, $FUNCTION1
                    invoke-static { v0, p1 }, ${site.click}
                    move-result-object v0
                    check-cast p2, $FUNCTION1
                    invoke-static { v0, p2 }, ${site.longClick}
                    move-result-object v0
                    invoke-static { v0, p4 }, ${site.description}
                    move-result-object v0
                    new-instance v1, ${site.specType}
                    iget-object v2, p0, ${site.scale}
                    move-object v3, v0
                    const/4 v4, 0x0
                    move v5, p5
                    iget v6, p0, ${site.color}
                    iget-boolean v7, p0, ${site.flag}
                    invoke-direct/range { v1 .. v7 }, ${site.specConstructor}
                    return-object v1
                """,
            )
        },
    )
}

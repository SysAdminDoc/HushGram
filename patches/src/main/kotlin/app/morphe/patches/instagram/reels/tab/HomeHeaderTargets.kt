/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.comment.replace
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide the Reels tab"

/** Home's header, a view whose class keeps its name in Instagram's builds. */
internal const val MAIN_FEED_ACTION_BAR = "Linstagram/features/feed/mainfeed/actionbar/MainFeedActionBar;"

private const val HOME_HEADER = "$EXTENSION_PACKAGE/reels/HomeHeader;"
internal const val HEADER_BUTTONS = "$HOME_HEADER->buttons(Ljava/util/List;)Ljava/util/List;"
internal const val HEADER_ICON_STUB = "icon"
internal const val HEADER_HEART_STUB = "heart"
internal const val HEADER_ROW_STUB = "endRow"
internal const val HEADER_START_ROW_STUB = "startRow"
internal const val HEADER_TITLE_STUB = "title"

/**
 * Called as the header starts drawing from its state, with the header, to put the Search and Ghost
 * mode buttons on it and center its logo.
 */
internal const val GHOST_BUTTON = "$EXTENSION_PACKAGE/settings/GhostHeaderButton;->drew(Landroid/view/View;)V"

/** The notifications heart's view, a class Instagram's layouts name, so every build keeps the name. */
internal const val TOASTING_BADGE = "Lcom/instagram/notifications/badging/ui/component/ToastingBadge;"

/** The header's title view, holding the logo and the feed's name: a class Instagram's layouts name, so every build keeps it. */
internal const val TITLE_VIEW = "Lcom/instagram/actionbar/ActionBarTitleViewSwitcher;"

private const val OBJECT = "Ljava/lang/Object;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val IMAGE_VIEW = "Landroid/widget/ImageView;"
private const val LINEAR_LAYOUT = "Landroid/widget/LinearLayout;"
private const val VIEW_GROUP = "Landroid/view/ViewGroup;"
private const val VIEW = "Landroid/view/View;"

/**
 * Home's header: the constructor of the state class the header draws from, and the register its list
 * of buttons arrives in, the type of an image button (Create, Messages and the others) with the int
 * field holding its icon's resource id, and the type of the notifications heart, the method that
 * draws the header from a state, the header's fields holding its end row and its start row of
 * buttons, and its field holding the title view.
 */
internal class HomeHeaderHook(
    val draw: Method,
    val state: Method,
    val list: Int,
    val image: String,
    val icon: String,
    val badge: String,
    val row: String,
    val start: String,
    val title: String,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds what the header's two buttons are told apart by, failing before anything changes when any of
 * it isn't there exactly once. In [MAIN_FEED_ACTION_BAR]:
 *
 *  - the method that draws the header from a state takes the session and the state, and reads the
 *    state's list of buttons, which the state's constructor keeps from its first parameter;
 *  - the one private method taking an ImageView and a button wires an image button's click, so its
 *    second parameter is the image button type, whose constructor takes the kind, the label and the
 *    icon, and stores that icon in an int field;
 *  - the drawing method asks, by type, whether each button is the image button or the notifications
 *    heart, which extends the same class as the image button and is the only other type it asks about
 *    that does;
 *  - the drawing method adds the heart's view, cast to [TOASTING_BADGE], to one of the header's
 *    LinearLayout fields through a static (ViewGroup, View) helper, and that field is the end row;
 *  - where Instagram's newer header centers its logo, the drawing method gives two of the header's
 *    LinearLayout fields the same minimum width: the end row and the start row;
 *  - the header has one field of [TITLE_VIEW], the view between the two rows.
 */
internal fun BytecodePatchContext.findHomeHeader(): HomeHeaderHook {
    val bar = classDefByOrNull(MAIN_FEED_ACTION_BAR) ?: refuse("this Instagram build has no $MAIN_FEED_ACTION_BAR")
    val draws = bar.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.parameterTypes.size == 3 &&
            method.parameterTypes[1].toString() == SESSION && method.parameterTypes[2].toString().startsWith("L") &&
            !method.parameterTypes[2].toString().startsWith("Landroid/")
    }
    val draw = draws.singleOrNull() ?: refuse("expected one method in $MAIN_FEED_ACTION_BAR drawing the header from a state, found ${draws.size}")
    val stateType = draw.parameterTypes[2].toString()
    val state = classDefByOrNull(stateType) ?: refuse("the header's state $stateType isn't in the app")

    val constructors = state.methods.filter { method ->
        method.name == "<init>" && method.parameterTypes.size == 5 && method.parameterTypes[0].toString() == LIST
    }
    val constructor = constructors.singleOrNull()
        ?: refuse("expected one constructor of $stateType taking its list of buttons first, found ${constructors.size}")
    val list = constructor.parameterRegisterNumber(0)
    if (list > 255) refuse("the header state keeps its list in v$list, past v255")
    val code = constructor.code()
    val kept = code.firstNotNullOfOrNull { instruction ->
        instruction.fieldReference()?.takeIf { instruction.opcode == Opcode.IPUT_OBJECT && (instruction as OneRegisterInstruction).registerA == list &&
            it.definingClass == stateType && it.type == LIST }
    } ?: refuse("$stateType's constructor doesn't keep its list of buttons")
    if (constructor.jumpTargets().contains(0)) refuse("something jumps to the start of $stateType's constructor")
    if (draw.jumpTargets().contains(0)) refuse("something jumps to the start of ${draw.name} in $MAIN_FEED_ACTION_BAR")
    val reads = draw.code().count { instruction ->
        instruction.opcode == Opcode.IGET_OBJECT && instruction.fieldReference()?.let {
            it.definingClass == stateType && it.name == kept.name && it.type == LIST
        } == true
    }
    if (reads == 0) refuse("${draw.name} in $MAIN_FEED_ACTION_BAR doesn't read the header state's list of buttons")

    val wiring = bar.methods.filter { method ->
        method.returnType == "V" && method.parameterTypes.size == 2 && method.parameterTypes[0].toString() == IMAGE_VIEW &&
            method.parameterTypes[1].toString().startsWith("L")
    }
    val wire = wiring.singleOrNull() ?: refuse("expected one method in $MAIN_FEED_ACTION_BAR wiring an image button, found ${wiring.size}")
    val image = wire.parameterTypes[1].toString()
    val asked = draw.code().filter { it.opcode == Opcode.INSTANCE_OF }
        .mapNotNull { (it as ReferenceInstruction).reference as? TypeReference }.map { it.type }.toSet()
    if (image !in asked) refuse("the header doesn't ask whether a button is its image button $image, it asked about $asked")
    val imageClass = classDefByOrNull(image) ?: refuse("the image button type $image isn't in the app")
    // The other kind of button extends the same class as the image button; the other types it asks about don't.
    val siblings = (asked - image).filter { classDefByOrNull(it)?.superclass == imageClass.superclass }
    val badge = siblings.singleOrNull()
        ?: refuse("expected the header to ask about one button type besides $image, found $siblings among $asked")
    val builders = imageClass.methods.filter { method ->
        method.name == "<init>" && method.parameterTypes.map(CharSequence::toString) == listOf(INTEGER, INTEGER, "I")
    }
    val builder = builders.singleOrNull() ?: refuse("expected one constructor of $image taking a kind, a label and an icon, found ${builders.size}")
    val iconRegister = builder.parameterRegisterNumber(2)
    val icons = builder.code().mapNotNull { instruction ->
        instruction.fieldReference()?.takeIf {
            instruction.opcode == Opcode.IPUT && (instruction as OneRegisterInstruction).registerA == iconRegister &&
                it.definingClass == image && it.type == "I"
        }
    }
    // 450 stores the icon twice, in the field the button draws from and in the one that names the button; either reads it.
    val icon = icons.firstOrNull() ?: refuse("expected $image's constructor to store its icon in an int field, found ${icons.size}")
    val field = imageClass.fields.singleOrNull { it.name == icon.name && it.type == "I" }
        ?: refuse("$image has no int field ${icon.name}")
    if (!AccessFlags.PUBLIC.isSet(imageClass.accessFlags) || !AccessFlags.PUBLIC.isSet(field.accessFlags)) {
        refuse("$image's icon ${icon.name} isn't public, so the extension can't read it")
    }
    val badgeClass = classDefByOrNull(badge) ?: refuse("the notifications button type $badge isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(badgeClass.accessFlags)) refuse("$badge isn't public, so the extension can't ask about it")

    val rows = endRows(draw.code())
    val row = rows.singleOrNull() ?: refuse("expected the header to add its heart's view to one row, found ${rows.map { it.name }}")
    val rowField = bar.fields.singleOrNull { it.name == row.name && it.type == LINEAR_LAYOUT }
        ?: refuse("$MAIN_FEED_ACTION_BAR has no LinearLayout field ${row.name}")
    if (!AccessFlags.PUBLIC.isSet(bar.accessFlags) || !AccessFlags.PUBLIC.isSet(rowField.accessFlags) ||
        AccessFlags.STATIC.isSet(rowField.accessFlags)
    ) {
        refuse("the header's end row ${row.name} isn't a public instance field, so the extension can't read it")
    }
    val widened = widenedRows(draw.code())
    if (widened.size != 2 || widened.none { it.name == row.name }) {
        refuse("expected the header to give its end row ${row.name} and one other row the same width, found ${widened.map { it.name }}")
    }
    val start = widened.single { it.name != row.name }
    val startField = bar.fields.singleOrNull { it.name == start.name && it.type == LINEAR_LAYOUT }
        ?: refuse("$MAIN_FEED_ACTION_BAR has no LinearLayout field ${start.name}")
    if (!AccessFlags.PUBLIC.isSet(startField.accessFlags) || AccessFlags.STATIC.isSet(startField.accessFlags)) {
        refuse("the header's start row ${start.name} isn't a public instance field, so the extension can't read it")
    }
    val titles = bar.fields.filter { it.type == TITLE_VIEW }
    val title = titles.singleOrNull() ?: refuse("expected one $TITLE_VIEW field in $MAIN_FEED_ACTION_BAR, found ${titles.size}")
    if (!AccessFlags.PUBLIC.isSet(title.accessFlags) || AccessFlags.STATIC.isSet(title.accessFlags)) {
        refuse("the header's title view ${title.name} isn't a public instance field, so the extension can't read it")
    }

    val extension = classDefByOrNull(HOME_HEADER) ?: refuse("the extension has no $HOME_HEADER")
    for (stub in listOf(HEADER_ICON_STUB, HEADER_HEART_STUB)) {
        if (extension.methods.none { it.isStub(stub) }) refuse("$HOME_HEADER has no public static I $stub($OBJECT)")
    }
    for (stub in listOf(HEADER_ROW_STUB, HEADER_START_ROW_STUB, HEADER_TITLE_STUB)) {
        if (extension.methods.none { it.isReadStub(stub) }) refuse("$HOME_HEADER has no public static $OBJECT $stub($OBJECT)")
    }
    val ghost = classDefByOrNull(GHOST_BUTTON.substringBefore("->")) ?: refuse("the extension has no ${GHOST_BUTTON.substringBefore("->")}")
    if (ghost.methods.none { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == GHOST_BUTTON.substringAfter("->") && it.isPublicStatic() }) {
        refuse("the extension has no public static $GHOST_BUTTON")
    }
    if (extension.methods.none { it.name == "buttons" && it.parameterTypes.map(CharSequence::toString) == listOf(LIST) && it.returnType == LIST && it.isPublicStatic() }) {
        refuse("the extension has no public static $HEADER_BUTTONS")
    }
    return HomeHeaderHook(
        draw, constructor, list, image, "$image->${icon.name}:I", badge, "$MAIN_FEED_ACTION_BAR->${row.name}:$LINEAR_LAYOUT",
        "$MAIN_FEED_ACTION_BAR->${start.name}:$LINEAR_LAYOUT", "$MAIN_FEED_ACTION_BAR->${title.name}:$TITLE_VIEW",
    )
}

/**
 * The header's LinearLayout fields the draw method gives a minimum width: the field an iget-object
 * last read into the register a View.setMinimumWidth(I) call is made on, looking back from the call.
 * Instagram's newer header does it to its two rows of buttons, so the title between them sits in
 * the middle. A register written by anything else in between doesn't count.
 */
internal fun widenedRows(code: List<Instruction>): List<FieldReference> = code.indices.mapNotNull { index ->
    val call = code[index]
    val method = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
    if (method.definingClass != VIEW || method.name != "setMinimumWidth" ||
        method.parameterTypes.map(CharSequence::toString) != listOf("I") || method.returnType != "V"
    ) return@mapNotNull null
    val row = when (call.opcode) {
        Opcode.INVOKE_VIRTUAL -> (call as FiveRegisterInstruction).registerC
        Opcode.INVOKE_VIRTUAL_RANGE -> (call as RegisterRangeInstruction).startRegister
        else -> return@mapNotNull null
    }
    val written = code.subList(0, index).lastOrNull { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == row }
    written?.fieldReference()?.takeIf {
        written.opcode == Opcode.IGET_OBJECT && it.definingClass == MAIN_FEED_ACTION_BAR && it.type == LINEAR_LAYOUT
    }
}.distinctBy { it.name }

/**
 * The header's LinearLayout fields the draw method adds the heart's view to: a read of the field
 * straight before a static (ViewGroup, View)V call handed it with a view the last write to was a cast
 * to [TOASTING_BADGE]. Image buttons go to the start or the end row by a server flag; the heart
 * always goes to the end row.
 */
private fun endRows(code: List<Instruction>): List<FieldReference> = code.indices.mapNotNull { index ->
    val read = code[index]
    val add = code.getOrNull(index + 1) ?: return@mapNotNull null
    val field = read.fieldReference()?.takeIf {
        read.opcode == Opcode.IGET_OBJECT && it.definingClass == MAIN_FEED_ACTION_BAR && it.type == LINEAR_LAYOUT
    } ?: return@mapNotNull null
    ((add as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
        add.opcode == Opcode.INVOKE_STATIC && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(VIEW_GROUP, VIEW)
    } ?: return@mapNotNull null
    val registers = add as FiveRegisterInstruction
    if (registers.registerC != (read as OneRegisterInstruction).registerA) return@mapNotNull null
    val view = registers.registerD
    val written = code.subList(0, index).lastOrNull { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == view }
    field.takeIf { written?.opcode == Opcode.CHECK_CAST && ((written as ReferenceInstruction).reference as? TypeReference)?.type == TOASTING_BADGE }
}.distinctBy { it.name }

/**
 * Writes the stubs, then hands the header state's list of buttons to [HEADER_BUTTONS] as its
 * constructor starts, keeping the answer in the same register, and hands the header to
 * [GHOST_BUTTON] as the method that draws it starts. Only called once [findHomeHeader] found
 * everything.
 */
internal fun BytecodePatchContext.hideHomeHeaderButtons(hook: HomeHeaderHook) {
    // Two registers, so v0 is a local and p0 is v1: the icon stub casts p0 after instance-of wrote
    // v0, and with one register that write would land on p0 and Android's verifier rejects the class.
    replace(mutableClassDefBy(HOME_HEADER).methods.single { it.isStub(HEADER_ICON_STUB) }, 2, """
        instance-of v0, p0, ${hook.image}
        if-eqz v0, :other
        check-cast p0, ${hook.image}
        iget v0, p0, ${hook.icon}
        return v0
        :other
        const/4 v0, 0x0
        return v0
    """)
    replace(mutableClassDefBy(HOME_HEADER).methods.single { it.isStub(HEADER_HEART_STUB) }, 1, """
        instance-of v0, p0, ${hook.badge}
        return v0
    """)
    readFromHeader(HEADER_ROW_STUB, hook.row)
    readFromHeader(HEADER_START_ROW_STUB, hook.start)
    readFromHeader(HEADER_TITLE_STUB, hook.title)
    val state = mutableClassDefBy(hook.state.definingClass).methods.single { it.sameShape(hook.state) }
    state.addInstructions(
        0,
        """
            invoke-static/range { v${hook.list} .. v${hook.list} }, $HEADER_BUTTONS
            move-result-object v${hook.list}
        """,
    )
    // The draw method's first instruction is nothing a jump lands on (checked when it was found),
    // and p0, the header, is untouched there. The hook returns nothing and takes no register.
    mutableClassDefBy(hook.draw.definingClass).methods.single { it.sameShape(hook.draw) }.addInstructions(
        0,
        "invoke-static/range { p0 .. p0 }, $GHOST_BUTTON",
    )
}

/**
 * Writes the [stub] that reads [field] out of the header. It answers an Object: the field's view, or
 * null for anything that isn't the header. Two registers for the same reason as the icon stub: v0
 * is a local and p0, the header, is v1.
 */
private fun BytecodePatchContext.readFromHeader(stub: String, field: String) {
    replace(mutableClassDefBy(HOME_HEADER).methods.single { it.isReadStub(stub) }, 2, """
        instance-of v0, p0, $MAIN_FEED_ACTION_BAR
        if-eqz v0, :other
        check-cast p0, $MAIN_FEED_ACTION_BAR
        iget-object v0, p0, $field
        return-object v0
        :other
        const/4 v0, 0x0
        return-object v0
    """)
}

private fun Method.isPublicStatic() = AccessFlags.PUBLIC.isSet(accessFlags) && AccessFlags.STATIC.isSet(accessFlags)

private fun Method.isStub(name: String) =
    this.name == name && parameterTypes.map(CharSequence::toString) == listOf(OBJECT) && returnType == "I" && isPublicStatic()

private fun Method.isReadStub(name: String) =
    this.name == name && parameterTypes.map(CharSequence::toString) == listOf(OBJECT) && returnType == OBJECT && isPublicStatic()

private fun MutableMethod.sameShape(other: Method) =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

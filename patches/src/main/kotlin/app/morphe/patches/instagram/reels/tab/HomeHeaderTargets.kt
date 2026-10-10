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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide the Reels tab"

/** Home's header, a view whose class keeps its name in Instagram's builds. */
internal const val MAIN_FEED_ACTION_BAR = "Linstagram/features/feed/mainfeed/actionbar/MainFeedActionBar;"

private const val HOME_HEADER = "$EXTENSION_PACKAGE/reels/HomeHeader;"
internal const val HEADER_BUTTONS = "$HOME_HEADER->buttons(Ljava/util/List;)Ljava/util/List;"
internal const val HEADER_ICON_STUB = "icon"
internal const val HEADER_HEART_STUB = "heart"

private const val OBJECT = "Ljava/lang/Object;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val IMAGE_VIEW = "Landroid/widget/ImageView;"

/**
 * Home's header: the constructor of the state class the header draws from, and the register its list
 * of buttons arrives in, the type of an image button (Create, Messages and the others) with the int
 * field holding its icon's resource id, and the type of the notifications heart.
 */
internal class HomeHeaderHook(
    val state: Method,
    val list: Int,
    val image: String,
    val icon: String,
    val badge: String,
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
 *    that does.
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

    val extension = classDefByOrNull(HOME_HEADER) ?: refuse("the extension has no $HOME_HEADER")
    for (stub in listOf(HEADER_ICON_STUB, HEADER_HEART_STUB)) {
        if (extension.methods.none { it.isStub(stub) }) refuse("$HOME_HEADER has no public static I $stub($OBJECT)")
    }
    if (extension.methods.none { it.name == "buttons" && it.parameterTypes.map(CharSequence::toString) == listOf(LIST) && it.returnType == LIST && it.isPublicStatic() }) {
        refuse("the extension has no public static $HEADER_BUTTONS")
    }
    return HomeHeaderHook(constructor, list, image, "$image->${icon.name}:I", badge)
}

/**
 * Writes the two stubs, then hands the header state's list of buttons to [HEADER_BUTTONS] as its
 * constructor starts, keeping the answer in the same register. Only called once [findHomeHeader]
 * found everything.
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
    val state = mutableClassDefBy(hook.state.definingClass).methods.single { it.sameShape(hook.state) }
    state.addInstructions(
        0,
        """
            invoke-static/range { v${hook.list} .. v${hook.list} }, $HEADER_BUTTONS
            move-result-object v${hook.list}
        """,
    )
}

private fun Method.isPublicStatic() = AccessFlags.PUBLIC.isSet(accessFlags) && AccessFlags.STATIC.isSet(accessFlags)

private fun Method.isStub(name: String) =
    this.name == name && parameterTypes.map(CharSequence::toString) == listOf(OBJECT) && returnType == "I" && isPublicStatic()

private fun MutableMethod.sameShape(other: Method) =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PROMOTION_OWNER = "$EXTENSION_PACKAGE/metaai/MetaAi"

internal const val DROPS_PROMOTION = "$PROMOTION_OWNER;->dropsPromotion(Ljava/lang/Object;)Z"

/** The templates a quick promotion's controller is picked by. The method that picks it names all three. */
internal val PROMOTION_TEMPLATES = listOf("fixed_banner_ig", "social_context_condensed_megaphone_ig", "inline_editing_standard_megaphone_ig")

/** Instagram's own, unobfuscated names for a promotion's slot and the text of its creative. */
internal const val PROMOTION_SLOT_TYPE = "Lcom/instagram/quickpromotion/intf/QuickPromotionSlot;"
internal const val PROMOTION_TEXT_TYPE = "Lcom/instagram/api/schemas/QPTextContainerImpl;"

/** The extension's stubs the patch fills to read a promotion: its slot's name, then its creative's three texts. */
internal val PROMOTION_STUBS = listOf("promotionSlot", "promotionText1", "promotionText2", "promotionText3")

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/** A field the stubs read: its class, name and type. */
internal class PromotionField(val owner: String, val name: String, val type: String) {
    val reference get() = "$owner->$name:$type"
}

/**
 * Where a quick promotion becomes a card, and how to read one: the method that turns the promotion
 * into the controller Instagram builds its card with, the promotion's class, the field holding its
 * slot, the field holding its creative, the creative's three text containers in order, and the one
 * string each container holds.
 */
internal class QuickPromotionBuilder(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val promotion: String,
    val slot: PromotionField,
    val creative: PromotionField,
    val texts: List<PromotionField>,
    val text: PromotionField,
)

/**
 * A quick promotion is a server-sent card (the Meet Muse card is one) bound for a slot such as
 * Home's feed header or your profile. One method turns the promotion into the controller that builds
 * its card: it picks a controller by the promotion's template, which it names in three string
 * constants, and it answers null for a promotion it was handed null. It's the one non-static
 * method taking two parameters, an object answered, and loading all of [PROMOTION_TEMPLATES].
 *
 * The promotion is the class its first check-cast names. It has to hold exactly one slot field
 * ([PROMOTION_SLOT_TYPE]) and exactly one field whose class holds exactly three text containers
 * ([PROMOTION_TEXT_TYPE]), the creative's, which a container holds a single string of. Anything else
 * is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findQuickPromotionBuilder(): QuickPromotionBuilder {
    val builders = classesLoadingString(PROMOTION_TEMPLATES.first()).flatMap { classDef ->
        classDef.methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") &&
                method.parameterTypes.size == 2 && PROMOTION_TEMPLATES.all { loadsString(method, it) }
        }.map { classDef to it }
    }
    val (owner, builder) = builders.singleOrNull()
        ?: throw PatchException("Hide Meta AI: ${builders.size} methods turn a quick promotion into its card (${PROMOTION_TEMPLATES.joinToString()}), not one")

    // The hook hands the extension p2, the method's last register, so only a cast of p2 names the promotion.
    val lastParameter = (builder.implementation?.registerCount ?: 0) - 1
    val promotion = builder.implementation?.instructions
        ?.filter { it.opcode == Opcode.CHECK_CAST && (it as OneRegisterInstruction).registerA == lastParameter }
        ?.map { ((it as ReferenceInstruction).reference as TypeReference).type }
        ?.firstOrNull { cast -> classDefByOrNull(cast)?.instanceFields()?.any { it.type == PROMOTION_SLOT_TYPE } == true }
        ?: throw PatchException("Hide Meta AI: the quick promotion method casts its last parameter to no class holding a slot")
    val promotionClass = classDefBy(promotion)

    val slot = promotionClass.instanceFields().filter { it.type == PROMOTION_SLOT_TYPE }.singleOrNull()
        ?: throw PatchException("Hide Meta AI: $promotion doesn't hold exactly one slot field")
    val creatives = promotionClass.instanceFields().filter { field ->
        classDefByOrNull(field.type)?.instanceFields().orEmpty().count { it.type == PROMOTION_TEXT_TYPE } == 3
    }
    val creative = creatives.singleOrNull()
        ?: throw PatchException("Hide Meta AI: $promotion has ${creatives.size} fields holding three text containers, not one")
    val texts = classDefBy(creative.type).instanceFields().filter { it.type == PROMOTION_TEXT_TYPE }

    val container = classDefByOrNull(PROMOTION_TEXT_TYPE)
        ?: throw PatchException("Hide Meta AI: $PROMOTION_TEXT_TYPE isn't in this build")
    val strings = container.instanceFields().filter { it.type == STRING }
    val text = strings.singleOrNull()
        ?: throw PatchException("Hide Meta AI: $PROMOTION_TEXT_TYPE holds ${strings.size} string fields, not one")

    for (type in listOf(promotion, creative.type, PROMOTION_TEXT_TYPE)) requirePublic(classDefBy(type))
    for (field in listOf(slot, creative) + texts + text) {
        if (!AccessFlags.PUBLIC.isSet(field.accessFlags)) {
            throw PatchException("Hide Meta AI: ${field.definingClass}->${field.name} isn't public, so the extension can't reach it")
        }
    }
    return QuickPromotionBuilder(
        owner.type, builder.name, builder.parameterTypes.map(CharSequence::toString), builder.returnType, promotion,
        slot.asField(), creative.asField(), texts.map { it.asField() }, text.asField(),
    )
}

private fun requirePublic(classDef: ClassDef) {
    if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) {
        throw PatchException("Hide Meta AI: ${classDef.type} isn't public, so the extension can't reach it")
    }
}

private fun ClassDef.instanceFields(): List<Field> = fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }

private fun Field.asField() = PromotionField(definingClass, name, type)

/**
 * Fills the extension's stubs to read a promotion, then makes the promotion method answer null from
 * its first instruction when [DROPS_PROMOTION] says to, which is what it does for a promotion it was
 * handed null, so no card is built. The switch and Pause are read on every call, so off takes
 * Instagram's own path. The method has locals at its start, none live, so v0 is free.
 */
internal fun BytecodePatchContext.holdQuickPromotion(builder: QuickPromotionBuilder) {
    val method = mutableClassDefBy(builder.type).methods.single {
        it.name == builder.name && it.returnType == builder.returnType &&
            it.parameterTypes.map(CharSequence::toString) == builder.parameters
    }
    method.requireLocals("Hide Meta AI", 1)
    val stubs = PROMOTION_STUBS.map { stub ->
        mutableClassDefBy("$PROMOTION_OWNER;").methods.singleOrNull {
            it.name == stub && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == STRING &&
                it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT)
        } ?: throw PatchException("Hide Meta AI: $PROMOTION_OWNER has no static $stub(Object)String")
    }
    val promotion = builder.promotion
    stubs[0].addInstructions(
        0,
        """
            check-cast p0, $promotion
            iget-object p0, p0, ${builder.slot.reference}
            invoke-virtual { p0 }, Ljava/lang/Object;->toString()Ljava/lang/String;
            move-result-object p0
            return-object p0
        """,
    )
    builder.texts.forEachIndexed { index, text ->
        stubs[index + 1].addInstructions(
            0,
            """
                check-cast p0, $promotion
                iget-object p0, p0, ${builder.creative.reference}
                iget-object p0, p0, ${text.reference}
                iget-object p0, p0, ${builder.text.reference}
                return-object p0
            """,
        )
    }
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { p2 }, $DROPS_PROMOTION
            move-result v0
            if-eqz v0, :build
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("build", method.getInstruction(0)),
    )
}

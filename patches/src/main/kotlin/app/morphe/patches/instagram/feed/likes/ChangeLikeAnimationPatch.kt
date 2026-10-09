/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.likes

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Change the like animation"

/** The heart that pops up over a post when it's double tapped, and its set-up for one of Instagram's own animations. */
internal const val LIKE_ACTION_VIEW = "Lcom/instagram/ui/mediaactions/LikeActionView;"
internal const val SET_UP_CUSTOM_LIKES = "setUpCustomLikesAnimation"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val ENUM = "Ljava/lang/Enum;"

internal const val LIKE_ANIMATION = "$EXTENSION_PACKAGE/feed/LikeAnimation;"
internal const val PICK_LIKE_ANIMATION = "$LIKE_ANIMATION->pick(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val ALLOW_LIKE_ANIMATION = "$LIKE_ANIMATION->allow(I)Z"

/**
 * The extension's stub the patch fills: the animation type's value for the plain heart. The
 * extension reads the type itself off that value.
 */
internal const val NO_ANIMATION_STUB = "noAnimation"

/**
 * Plays the like animation picked in settings, one of the animations Instagram made for Instagram
 * Rings creators, in the heart that pops up when you double tap a post. In simple mode with its
 * switch off, so nothing changes until it's turned on and an animation is picked.
 */
@Suppress("unused")
val changeLikeAnimationPatch = bytecodePatch(
    name = "Change the like animation",
    description = "Plays an animation you pick, from the ones Instagram made for Instagram Rings creators, in the " +
        "heart that pops up when you double tap a post. Off until you turn it on. Switch: HushGram settings > Reels.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("likeAnimation")
        val anchors = findLikeAnimation()
        applyLikeAnimation(anchors)
        enableStatus("likeAnimation")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * What the patch changes: the heart view's set-up for a post, which takes Instagram's animation for
 * it, [animation] its type, [none] the value it compares first for the plain heart, and [gate] the
 * index of the answer of the check it makes before it plays any of Instagram's own animations.
 */
internal class LikeAnimationAnchors(val configure: Method, val animation: String, val none: FieldReference, val gate: Int)

/**
 * The heart view [LIKE_ACTION_VIEW] sets up the animation a double tap plays in one instance method
 * taking the session and one of Instagram's animations, an enum. On 450 it plays the plain heart
 * when the animation is missing or the plain heart's own value, or when a check of the session
 * answers no, and otherwise hands it to [SET_UP_CUSTOM_LIKES].
 *
 * Fails before any change when the view or its [SET_UP_CUSTOM_LIKES] is missing, when that takes
 * anything but one enum of a public class, when no instance method or more than one takes the
 * session and that enum and calls it once, when that method doesn't compare the animation with one
 * value of its own type first, when it makes no check or more than one before the set-up, or when
 * the check's answer is something a branch lands on. Also fails when the extension lacks the hooks
 * or the stubs the patch fills.
 */
internal fun BytecodePatchContext.findLikeAnimation(): LikeAnimationAnchors {
    val view = classDefByOrNull(LIKE_ACTION_VIEW) ?: refuse("no $LIKE_ACTION_VIEW")
    val setUp = view.methods.singleOrNull {
        it.name == SET_UP_CUSTOM_LIKES && it.returnType == "V" && it.parameterTypes.size == 1 &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$LIKE_ACTION_VIEW has no $SET_UP_CUSTOM_LIKES taking one animation")
    val animation = setUp.parameterTypes.single().toString()
    val animationClass = classDefByOrNull(animation) ?: refuse("$SET_UP_CUSTOM_LIKES takes $animation, which isn't in the app")
    if (animationClass.superclass != ENUM || !AccessFlags.PUBLIC.isSet(animationClass.accessFlags)) {
        refuse("$SET_UP_CUSTOM_LIKES takes $animation, which isn't a public enum")
    }
    val setUpReference = "$LIKE_ACTION_VIEW->${setUp.name}($animation)V"

    val candidates = view.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION, animation) &&
            method.code().count { it.calls(setUpReference) } == 1
    }
    val configure = candidates.singleOrNull() ?: refuse(
        "expected one method of $LIKE_ACTION_VIEW taking the session and $animation and calling " +
            "$SET_UP_CUSTOM_LIKES once, found " + if (candidates.isEmpty()) "none" else candidates.joinToString { it.name },
    )
    val where = "$LIKE_ACTION_VIEW->${configure.name}"
    val code = configure.code()
    val setUpAt = code.indexOfFirst { it.calls(setUpReference) }
    val before = code.subList(0, setUpAt)

    val noneReads = before.filter { it.opcode == Opcode.SGET_OBJECT && (it.reference() as? FieldReference)?.type == animation }
    val none = noneReads.singleOrNull()?.reference() as? FieldReference
        ?: refuse("$where reads ${noneReads.size} values of $animation before the set-up, not one")
    val noneField = animationClass.staticFields.singleOrNull { it.name == none.name && it.type == animation }
    if (none.definingClass != animation || noneField == null) {
        refuse("$where compares the animation with ${none.definingClass}->${none.name}, which isn't one of its own values")
    }
    if (!AccessFlags.PUBLIC.isSet(noneField.accessFlags)) refuse("${none.name}, the plain heart's value, isn't public")
    val compared = before.any {
        (it.opcode == Opcode.IF_EQ || it.opcode == Opcode.IF_NE) &&
            (it as TwoRegisterInstruction).let { test -> listOf(test.registerA, test.registerB) }
                .containsAll(listOf((noneReads.single() as OneRegisterInstruction).registerA, configure.animationRegister()))
    }
    if (!compared) refuse("$where doesn't compare the animation with ${none.name}")

    val checks = before.indices.filter { at ->
        val called = before[at].takeIf { it.opcode == Opcode.INVOKE_STATIC }?.reference() as? MethodReference
        called != null && called.returnType == "Z" && called.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION)
    }
    val check = checks.singleOrNull() ?: refuse("$where makes ${checks.size} checks of the session before the set-up, not one")
    val gate = check + 1
    if (code.getOrNull(gate)?.opcode != Opcode.MOVE_RESULT) refuse("$where doesn't keep its check's answer")
    if (configure.animationRegister() > 255) refuse("$where keeps the animation in a register the hook can't write")

    val extension = classDefByOrNull(LIKE_ANIMATION) ?: refuse("the extension has no $LIKE_ANIMATION")
    for (hook in listOf(PICK_LIKE_ANIMATION, ALLOW_LIKE_ANIMATION)) {
        extension.methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: refuse("the extension has no $hook")
    }
    extension.stub(NO_ANIMATION_STUB, "Ljava/lang/Object;") ?: refuse("$LIKE_ANIMATION has no static Object $NO_ANIMATION_STUB()")
    return LikeAnimationAnchors(configure, animation, none, gate)
}

/**
 * The check's answer goes through [ALLOW_LIKE_ANIMATION] right after it's kept, so it's yes while
 * an animation is picked; then, first thing in the set-up, Instagram's animation goes through
 * [PICK_LIKE_ANIMATION], which answers the picked one in its place. The stub is filled last. The
 * check's answer goes in as an int, since ART may type the register holding it as one.
 * A branch that lands on the check's test would skip the first, so one fails the patch first.
 */
internal fun BytecodePatchContext.applyLikeAnimation(anchors: LikeAnimationAnchors) {
    val method = mutable(anchors.configure)
    val answer = (method.getInstruction(anchors.gate) as OneRegisterInstruction).registerA
    val instructions = method.implementation!!.instructions
    if (anchors.gate + 1 >= instructions.size || (instructions[anchors.gate + 1] as BuilderInstruction).location.labels.isNotEmpty()) {
        refuse("a branch in $LIKE_ACTION_VIEW->${method.name} lands right after its check")
    }
    method.addInstructions(
        anchors.gate + 1,
        """
            invoke-static { v$answer }, $ALLOW_LIKE_ANIMATION
            move-result v$answer
        """,
    )
    val register = anchors.configure.animationRegister()
    method.addInstructions(
        0,
        """
            invoke-static/range { v$register .. v$register }, $PICK_LIKE_ANIMATION
            move-result-object v$register
            check-cast v$register, ${anchors.animation}
        """,
    )

    val extension = mutableClassDefBy(LIKE_ANIMATION)
    fun stub(name: String): MutableMethod = extension.methods.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }
    stub(NO_ANIMATION_STUB).addInstructions(
        0,
        """
            sget-object v0, ${anchors.none.definingClass}->${anchors.none.name}:${anchors.none.type}
            return-object v0
        """,
    )
}

/** The register of the set-up's animation: the last parameter, after the view and the session. */
private fun Method.animationRegister(): Int = implementation!!.registerCount - 1

private fun ClassDef.stub(name: String, answer: String): Method? = methods.singleOrNull {
    it.name == name && it.returnType == answer && it.parameterTypes.isEmpty() && AccessFlags.STATIC.isSet(it.accessFlags) &&
        (it.implementation?.registerCount ?: 0) >= 1
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

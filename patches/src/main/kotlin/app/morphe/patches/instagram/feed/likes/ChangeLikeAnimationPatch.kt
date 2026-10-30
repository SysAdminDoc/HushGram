/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.likes

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Change the like animation"

/** The heart that pops up over a post when it's double tapped, and its set-up for one of Instagram's own animations. */
internal const val LIKE_ACTION_VIEW = "Lcom/instagram/ui/mediaactions/LikeActionView;"
internal const val SET_UP_CUSTOM_LIKES = "setUpCustomLikesAnimation"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val CONTEXT = "Landroid/content/Context;"
private const val ENUM = "Ljava/lang/Enum;"
private const val OBJECT = "Ljava/lang/Object;"

/** How many instructions after reading the plain heart's value an eligibility check may come and still be about the heart. */
private const val CHECK_REACH = 10

internal const val LIKE_ANIMATION = "$EXTENSION_PACKAGE/feed/LikeAnimation;"
internal const val PICK_LIKE_ANIMATION = "$LIKE_ANIMATION->pick(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val ALLOW_LIKE_ANIMATION = "$LIKE_ANIMATION->allow(I)Z"

/**
 * The extension's stub the patch fills: the animation type's value for the plain heart. The
 * extension reads the type itself off that value.
 */
internal const val NO_ANIMATION_STUB = "noAnimation"

/**
 * The stubs the patch fills so the settings list can show each heart before it's picked (#299): the
 * heart Instagram builds for an animation, and the call that plays one. Left as they are when the
 * build doesn't have what they need, which only takes the preview away.
 */
internal const val HEART_STUB = "heart"
internal const val PLAY_STUB = "play"

/** How many instructions after the view reads its heart out of a field the call that plays it may come. */
private const val PLAY_REACH = 3

/**
 * Plays the like animation picked in settings, one of the animations Instagram made for Instagram
 * Rings creators, in the heart that pops up when you double tap a post or a reel. In simple mode with its
 * switch off, so nothing changes until it's turned on and an animation is picked.
 */
@Suppress("unused")
val changeLikeAnimationPatch = bytecodePatch(
    name = "Change the like animation",
    description = "Lets you pick the animation that plays when you double tap a post to like it. The choices are " +
        "animations Instagram made for its Rings creators. Starts off. Turn it on in HushGram settings > Reels.",
    default = true,
) {
    category("Interaction")
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
 * Then the hearts drawn without the view (see [findHearts]): the post's animation where it's
 * [returned], where each heart component [kept] its own, and each eligibility check's answer in
 * [checks]. Last the settings preview's (#299): the [factory] a heart is built with, and the call that
 * [play]s one, each null when the extension can't make it.
 */
internal class LikeAnimationAnchors(
    val configure: Method,
    val animation: String,
    val none: FieldReference,
    val gate: Int,
    val returned: LikeAnimationSite,
    val kept: List<LikeAnimationSite>,
    val checks: List<LikeAnimationSite>,
    val factory: String? = null,
    val play: String? = null,
)

/** One instruction of [method] the patch hooks, at index [at]. */
internal class LikeAnimationSite(val method: Method, val at: Int)

/** What [findHearts] answers, [factory] null when the extension can't call it. */
private class Hearts(
    val returned: LikeAnimationSite,
    val kept: List<LikeAnimationSite>,
    val checks: List<LikeAnimationSite>,
    val factory: String?,
)

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
    val hearts = findHearts(setUp, animation, none, (before[check].reference() as MethodReference).definingClass)

    val extension = classDefByOrNull(LIKE_ANIMATION) ?: refuse("the extension has no $LIKE_ANIMATION")
    for (hook in listOf(PICK_LIKE_ANIMATION, ALLOW_LIKE_ANIMATION)) {
        extension.methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: refuse("the extension has no $hook")
    }
    extension.stub(NO_ANIMATION_STUB, "Ljava/lang/Object;") ?: refuse("$LIKE_ANIMATION has no static Object $NO_ANIMATION_STUB()")
    extension.stub(HEART_STUB, "Ljava/lang/Object;", listOf(CONTEXT, OBJECT), locals = 0)
        ?: refuse("$LIKE_ANIMATION has no static Object $HEART_STUB(Context, Object)")
    extension.stub(PLAY_STUB, "V", listOf(OBJECT), locals = 0) ?: refuse("$LIKE_ANIMATION has no static void $PLAY_STUB(Object)")
    val play = hearts.factory?.let { findPlay(view, it.substringAfterLast(')')) }
    return LikeAnimationAnchors(configure, animation, none, gate, hearts.returned, hearts.kept, hearts.checks, hearts.factory, play)
}

/**
 * #299: how the view plays its heart, the one no-argument call it makes on the drawable [heart] right
 * after reading it out of one of its fields. Null when it makes none or more than one, or when that
 * method isn't public, so the extension couldn't make the call.
 */
private fun BytecodePatchContext.findPlay(view: ClassDef, heart: String): String? {
    val plays = view.methods.flatMap { method ->
        val code = method.code()
        code.indices.mapNotNull { at ->
            val read = code[at]
            if (read.opcode != Opcode.IGET_OBJECT || (read.reference() as? FieldReference)?.type != heart) return@mapNotNull null
            val held = (read as TwoRegisterInstruction).registerA
            code.subList(at + 1, minOf(code.size, at + 1 + PLAY_REACH)).firstNotNullOfOrNull { next ->
                (next.reference() as? MethodReference)?.takeIf {
                    next.opcode == Opcode.INVOKE_VIRTUAL && it.parameterTypes.isEmpty() && it.returnType == "V" &&
                        (next as FiveRegisterInstruction).registerCount == 1 && next.registerC == held
                }
            }
        }
    }.map { it.text() }.distinct()
    val play = plays.singleOrNull() ?: return null
    val owner = classDefByOrNull(play.substringBefore("->")) ?: return null
    val name = play.substringAfter("->").substringBefore('(')
    val method = owner.methods.singleOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" } ?: return null
    val callable = AccessFlags.PUBLIC.isSet(owner.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags) &&
        !AccessFlags.STATIC.isSet(method.accessFlags)
    return play.takeIf { callable }
}

/**
 * Since 452 the double tap heart of a feed post or a reel isn't [LIKE_ACTION_VIEW]: Litho components
 * draw it. Each keeps the animation it was made with in a field and has a lambda read that field
 * straight into the static factory [SET_UP_CUSTOM_LIKES] builds the view's drawable with. A post's
 * animation comes from the one static method of the factory's class that answers the animation
 * type, which reads the post's config. The double tap's handler, each component and the heart's
 * spring then check the session again, with the view's eligibility check or another static check of
 * its class, right after comparing the animation with the plain heart [none], and play the plain
 * heart when it says no. The view's own set-up is left to [findLikeAnimation].
 *
 * Fails when the set-up calls no such factory or more than one, when the factory's class has no one
 * static method answering the animation from one argument with one return, when anything but the
 * view hands the factory an animation it didn't read out of a field the instruction before, when no
 * component is found or one never keeps its animation, when no check follows a comparison with the
 * plain heart, when one's answer isn't kept, or when a branch, switch or catch lands right after a
 * check's answer from anywhere but right after another check's answer, which has its own hook.
 */
private fun BytecodePatchContext.findHearts(setUp: Method, animation: String, none: FieldReference, checks: String): Hearts {
    val factories = setUp.code().filter { it.isStaticCall() }.map { it.reference() as MethodReference }
        .filter { it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, animation) }
        .map { it.text() }.distinct()
    val factory = factories.singleOrNull()
        ?: refuse("$SET_UP_CUSTOM_LIKES calls ${factories.size} static methods taking a context and $animation, not one")
    val factoryClass = factory.substringBefore("->")
    val factoryDef = classDefByOrNull(factoryClass)
    val built = factoryDef?.methods?.singleOrNull { it.text() == factory }
    val callable = factoryDef != null && built != null && AccessFlags.PUBLIC.isSet(factoryDef.accessFlags) &&
        AccessFlags.PUBLIC.isSet(built.accessFlags) && AccessFlags.STATIC.isSet(built.accessFlags)
    val sources = factoryDef?.methods?.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == animation && it.parameterTypes.size == 1
    }.orEmpty()
    val source = sources.singleOrNull()
        ?: refuse("$factoryClass has ${sources.size} static methods answering $animation from one argument, not one")
    val sourceCode = source.code()
    val returns = sourceCode.indices.filter { sourceCode[it].opcode == Opcode.RETURN_OBJECT }
    val returned = LikeAnimationSite(source, returns.singleOrNull() ?: refuse("${source.text()} returns in ${returns.size} places, not one"))

    // Only code calling the factory or into the checks' class can hand the factory an animation or
    // make a check, so the patcher's call index names the classes to read (#173). Its answer leaves
    // the extension out, so the extension is read as before, and the walk keeps the app's order.
    val factoryName = factory.substringAfter("->").substringBefore('(')
    val callers = (classesCalling(factoryClass, factoryName) + classesCallingInto(checks)).mapTo(HashSet()) { it.type }
    val fields = linkedSetOf<String>()
    val found = mutableListOf<LikeAnimationSite>()
    classDefForEach { classDef ->
        if (classDef.type == LIKE_ACTION_VIEW) return@classDefForEach
        if (classDef.type !in callers && !classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        for (method in classDef.methods) {
            val code = method.code()
            var noneAt = Int.MIN_VALUE / 2
            for ((at, instruction) in code.withIndex()) {
                if (instruction.opcode == Opcode.SGET_OBJECT && (instruction.reference() as FieldReference).let {
                        it.definingClass == none.definingClass && it.name == none.name
                    }
                ) {
                    noneAt = at
                }
                if (!instruction.isStaticCall()) continue
                val called = instruction.reference() as MethodReference
                if (called.definingClass == factoryClass && called.text() == factory) {
                    val read = code.getOrNull(at - 1)?.takeIf { it.opcode == Opcode.IGET_OBJECT }
                    val field = read?.reference() as? FieldReference
                    if (field == null || field.type != animation || (read as OneRegisterInstruction).registerA != instruction.argument(1)) {
                        refuse("${method.text()} hands the factory an animation it didn't just read out of a field")
                    }
                    fields += field.text()
                } else if (called.definingClass == checks && called.returnType == "Z" &&
                    called.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION) && at - noneAt <= CHECK_REACH
                ) {
                    if (code.getOrNull(at + 1)?.opcode != Opcode.MOVE_RESULT) refuse("${method.text()} doesn't keep its check's answer")
                    found += LikeAnimationSite(method, at + 1)
                }
            }
        }
    }

    if (fields.isEmpty()) refuse("nothing but $LIKE_ACTION_VIEW builds a heart with $factory")
    val kept = fields.flatMap { field ->
        val component = field.substringBefore("->")
        val sites = classDefByOrNull(component)?.methods?.flatMap { method ->
            method.code().withIndex().filter { (_, it) -> it.opcode == Opcode.IPUT_OBJECT && (it.reference() as FieldReference).text() == field }
                .map { LikeAnimationSite(method, it.index) }
        }.orEmpty()
        sites.ifEmpty { refuse("$component, a heart, never keeps its animation in $field") }
    }

    if (found.isEmpty()) refuse("no eligibility check of $checks follows a comparison with ${none.name}")
    for (sites in found.groupBy { it.method.text() }.values) {
        val answers = sites.map { it.at }.toSet()
        val landings = sites.first().method.landings()
        for (answer in answers) {
            if (landings[answer + 1].orEmpty().any { from -> from - 1 !in answers }) {
                refuse("a branch in ${sites.first().method.text()} lands right after a check's answer")
            }
        }
    }
    return Hearts(returned, kept, found, factory.takeIf { callable })
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

    applyHearts(anchors)

    val extension = mutableClassDefBy(LIKE_ANIMATION)
    fun stub(name: String): MutableMethod = extension.methods.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }
    stub(NO_ANIMATION_STUB).addInstructions(
        0,
        """
            sget-object v0, ${anchors.none.definingClass}->${anchors.none.name}:${anchors.none.type}
            return-object v0
        """,
    )
    val factory = anchors.factory ?: return
    stub(HEART_STUB).addInstructions(
        0,
        """
            check-cast p1, ${anchors.animation}
            invoke-static { p0, p1 }, $factory
            move-result-object p0
            return-object p0
        """,
    )
    val play = anchors.play ?: return
    stub(PLAY_STUB).addInstructions(
        0,
        """
            check-cast p0, ${play.substringBefore("->")}
            invoke-virtual { p0 }, $play
            return-void
        """,
    )
}

/**
 * The hearts drawn without the view: the post's animation goes through [PICK_LIKE_ANIMATION] where
 * it's returned, and each component's where it's kept, the call taking the place of the return or
 * the store so a branch landing there runs it too, and each eligibility check's answer through
 * [ALLOW_LIKE_ANIMATION] right after it's kept. A method's sites go in from its last up, so each
 * index still points where it did.
 */
private fun BytecodePatchContext.applyHearts(anchors: LikeAnimationAnchors) {
    val sites = (anchors.kept + anchors.returned).map { it to true } + anchors.checks.map { it to false }
    for (group in sites.groupBy { it.first.method.text() }.values) {
        val method = mutable(group.first().first.method)
        for ((site, picks) in group.sortedByDescending { it.first.at }) {
            val register = (method.getInstruction(site.at) as OneRegisterInstruction).registerA
            if (!picks) {
                method.addInstructions(
                    site.at + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $ALLOW_LIKE_ANIMATION
                        move-result v$register
                    """,
                )
                continue
            }
            val use = method.getInstruction(site.at)
            val again = if (use.opcode == Opcode.RETURN_OBJECT) {
                "return-object v$register"
            } else {
                "iput-object v$register, v${(use as TwoRegisterInstruction).registerB}, ${(use.reference() as FieldReference).text()}"
            }
            method.replaceInstruction(site.at, "invoke-static/range { v$register .. v$register }, $PICK_LIKE_ANIMATION")
            method.addInstructions(
                site.at + 1,
                """
                    move-result-object v$register
                    check-cast v$register, ${anchors.animation}
                    $again
                """,
            )
        }
    }
}

/** The register of the set-up's animation: the last parameter, after the view and the session. */
private fun Method.animationRegister(): Int = implementation!!.registerCount - 1

private fun Instruction.isStaticCall() = opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE

/** The register of a call's [word]th argument word. */
private fun Instruction.argument(word: Int): Int = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).getOrElse(word) { -1 }
    is RegisterRangeInstruction -> if (word < registerCount) startRegister + word else -1
    else -> -1
}

private fun MethodReference.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.text() = "$definingClass->$name:$type"

/**
 * The indexes of this method's code that a branch, a switch or a catch lands on, each with the
 * indexes of what lands there, -1 for a catch.
 */
private fun Method.landings(): Map<Int, List<Int>> {
    val code = code()
    val addresses = IntArray(code.size)
    var address = 0
    code.forEachIndexed { at, instruction ->
        addresses[at] = address
        address += instruction.codeUnits
    }
    val at = addresses.withIndex().associate { (index, start) -> start to index }
    val landings = mutableMapOf<Int, MutableList<Int>>()
    fun land(target: Int, from: Int) {
        at[target]?.let { landings.getOrPut(it, ::mutableListOf) += from }
    }
    code.forEachIndexed { from, instruction ->
        if (instruction !is OffsetInstruction) return@forEachIndexed
        val target = addresses[from] + instruction.codeOffset
        if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
            val payload = at[target]?.let(code::get) as? SwitchPayload ?: return@forEachIndexed
            for (element in payload.switchElements) land(addresses[from] + element.offset, from)
        } else {
            land(target, from)
        }
    }
    implementation?.tryBlocks?.forEach { block -> block.exceptionHandlers.forEach { land(it.handlerCodeAddress, -1) } }
    return landings
}

/** The extension's static stub [name] taking [parameters] (none of them wide) with at least [locals] registers of its own. */
private fun ClassDef.stub(name: String, answer: String, parameters: List<String> = emptyList(), locals: Int = 1): Method? = methods.singleOrNull {
    it.name == name && it.returnType == answer && it.parameterTypes.map(CharSequence::toString) == parameters &&
        AccessFlags.STATIC.isSet(it.accessFlags) && (it.implementation?.registerCount ?: 0) >= parameters.size + locals
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

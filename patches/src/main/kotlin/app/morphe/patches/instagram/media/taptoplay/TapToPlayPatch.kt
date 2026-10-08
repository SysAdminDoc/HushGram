/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.taptoplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PATCH = "Tap to play"

internal const val TAP_TO_PLAY = "$EXTENSION_PACKAGE/media/TapToPlay;"
internal const val ALLOW_START = "$TAP_TO_PLAY->allowStart(Ljava/lang/Object;Ljava/lang/String;I)Z"
internal const val ALLOW_DIRECT_START = "$TAP_TO_PLAY->allowDirectStart(Ljava/lang/Object;Ljava/lang/String;)Z"
internal const val PAUSED = "$TAP_TO_PLAY->paused(Ljava/lang/Object;Ljava/lang/String;)V"
internal const val REBOUND = "$TAP_TO_PLAY->rebound(Ljava/lang/Object;)V"
internal const val AUTOPLAY_ALLOWED = "$TAP_TO_PLAY->autoplayAllowed(I)Z"
internal const val PLAY_BUTTON_TAPPED = "$TAP_TO_PLAY->playButtonTapped(Ljava/lang/Object;)V"
internal const val TOUCH = "$EXTENSION_PACKAGE/media/TapClock;->touch(Landroid/app/Activity;Landroid/view/MotionEvent;)V"

/** The strings Instagram's build keeps in its players' own log lines, which pick each method out. */
internal const val PLAY_INTERNAL = "IgVideoPlayerImpl.playInternal playAfterSeek: "
internal const val GROOT_PREPARE = "IgGrootPlayer.prepare"
internal val GROOT_PLAY = listOf("retry", "play_after_recovery")
internal val AUTOPLAY_CHECKER = listOf("VideoAutoplayChecker", "zero_rating_or_data_saver")
internal const val PLAY_BUTTON_BINDER = "VideoPlayButtonBinder.bindView"

/** The activity every Instagram screen extends, which Redex leaves under its own name. */
internal const val FRAGMENT_ACTIVITY = "Lcom/instagram/base/activity/IgFragmentActivity;"

/** The Reels viewer's config, which Redex leaves under its own name, and its video logger is made with. */
internal const val CLIPS_VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val ENUM = "Ljava/lang/Enum;"
private const val CONTEXT = "Landroid/content/Context;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * Videos, reels and stories wait for a tap.
 *
 * Instagram 449 plays through IgGrootPlayer. A feed video or a reel starts through
 * IgVideoPlayerImpl's playInternal, which plays the IgGrootPlayer it holds; the story viewer and a
 * few other screens play an IgGrootPlayer themselves. Both starts ask the extension first, handing
 * it that IgGrootPlayer, and a start no tap asked for returns before it does anything. playInternal
 * is gated before it marks the video as playing, so a later tap finds it stopped and starts it.
 * IgGrootPlayer's pause and prepare tell the extension when what a tap started has ended, every
 * touch on an Instagram screen goes past the tap clock, and Instagram's own autoplay check answers
 * no, so the feed draws its play button. That button is a Litho one that stays drawn until the post
 * is, so its click tells the extension, which hides it while the video it started plays. A tap on a
 * reel resumes it only when Instagram knows you paused it, so the Reels tap's decision goes past the
 * extension too, which sends a tap on a reel that isn't playing down the resume path ([hookReelTap]).
 * The story player resumes on the release of a press and hold only a story that played before the
 * press, so the flag its resume checks goes past the extension as well ([hookStoryRelease]). When
 * Instagram's own auto scroll in Reels moves on to the next reel, the move tells the extension just
 * before the pager moves, and the extension counts it as a tap on the reel it moves to, so auto scroll
 * carries on reel after reel instead of stopping at the first reel the gate held ([hookAutoScroll]).
 * playInternal also tells the extension whether its player is the Reels viewer's ([findReelsLogger]),
 * so the extension's choice of where Tap to play holds starts can leave Reels out, or everything else.
 *
 * Everything is found before anything changes, so a build that differs stops the patch naming
 * what it couldn't find, and nothing is half done. The one exception is a build with no auto
 * scroller marker at all, which gets every other hook and a warning in the patch log
 * ([findAutoScroll] says why).
 */
@Suppress("unused")
val tapToPlayPatch = bytecodePatch(
    name = "Tap to play",
    description = "Videos, reels and stories wait for your tap instead of starting by themselves. Feed videos show a play " +
        "button, the way they do when Instagram saves mobile data.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("tapToPlay")
        holdStartsWithoutATap()
        enableStatus("tapToPlay")
    }
}

/** Every method this patch changes, found and checked before any of them is. */
internal class PlayerHooks(
    val playInternal: Method,
    val grootField: FieldReference,
    val reelsLogger: ReelsLogger,
    val play: Method,
    val pause: Method,
    val prepare: Method,
    val checker: Method,
    val touch: Method,
    val playButton: PlayButtonClick,
)

/**
 * The feed's Litho play button's click, the index of its call that hands the video to Instagram,
 * and which of its parameters is the click event.
 */
internal class PlayButtonClick(val method: Method, val call: Int, val event: Int)

internal fun BytecodePatchContext.holdStartsWithoutATap() {
    val hooks = findPlayerHooks()
    val reelTap = findReelTap()
    val storyRelease = findStoryRelease(hooks.prepare.definingClass)
    val autoScroll = findAutoScroll()

    mutable(hooks.playInternal).apply {
        requireLocals(PATCH, 2)
        addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, ${hooks.grootField}
                iget-object v1, p0, ${hooks.reelsLogger.field}
                instance-of v1, v1, ${hooks.reelsLogger.type}
                invoke-static { v0, p1, v1 }, $ALLOW_START
                move-result v0
                if-nez v0, :start
                return-void
            """,
            ExternalLabel("start", getInstruction(0)),
        )
    }
    mutable(hooks.play).apply {
        requireLocals(PATCH, 1)
        addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p1 }, $ALLOW_DIRECT_START
                move-result v0
                if-nez v0, :start
                return-void
            """,
            ExternalLabel("start", getInstruction(0)),
        )
    }
    mutable(hooks.pause).addInstructions(0, "invoke-static/range { p0 .. p1 }, $PAUSED")
    mutable(hooks.prepare).addInstructions(0, "invoke-static/range { p0 .. p0 }, $REBOUND")
    mutable(hooks.checker).apply {
        // The call goes in at each return's own label, so a branch straight to a return passes
        // through it too. The hook takes the answer as an int: ART lets a boolean method return a
        // register it types as int or byte (an and-int/lit8 before the return is enough), and a
        // boolean parameter would fail verification on that when the class loads.
        implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN }
            .map { it.index to (it.value as OneRegisterInstruction).registerA }
            .asReversed()
            .forEach { (index, register) ->
                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static/range { v$register .. v$register }, $AUTOPLAY_ALLOWED
                        move-result v$register
                    """,
                )
            }
    }
    mutable(hooks.touch).addInstructions(0, "invoke-static/range { p0 .. p1 }, $TOUCH")
    // After the call, so a branch to the instruction after it, from the click's other cases, skips
    // the hook.
    mutable(hooks.playButton.method).addInstructions(
        hooks.playButton.call + 1,
        hooks.playButton.method.parameterRegister(hooks.playButton.event).let { "invoke-static/range { $it .. $it }, $PLAY_BUTTON_TAPPED" },
    )
    hookReelTap(reelTap)
    hookStoryRelease(storyRelease)
    autoScroll?.let { hookAutoScroll(it) }
}

/**
 * The players' play, pause and prepare, the autoplay check and the touch dispatch, each the one
 * method of its shape holding its strings. playInternal must read the IgGrootPlayer it plays from
 * a field of its own player, so the hook can hand that player over first thing; the play and
 * prepare must be IgGrootPlayer's, the class holding [GROOT_PREPARE].
 */
internal fun BytecodePatchContext.findPlayerHooks(): PlayerHooks {
    val internals = mutableListOf<Method>()
    val preparers = mutableListOf<Method>()
    val checkers = mutableListOf<Method>()
    classesHolding(PLAY_INTERNAL).forEach { classDef -> classDef.methods.filterTo(internals) { PLAY_INTERNAL in it.strings() } }
    classesHolding(GROOT_PREPARE).forEach { classDef -> classDef.methods.filterTo(preparers) { GROOT_PREPARE in it.strings() } }
    classesHolding(*AUTOPLAY_CHECKER.toTypedArray()).forEach { classDef ->
        classDef.methods.filterTo(checkers) { it.strings().containsAll(AUTOPLAY_CHECKER) }
    }
    val playInternal = internals.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method holding \"$PLAY_INTERNAL\", found ${internals.size}")
    val owner = playInternal.definingClass
    if (!AccessFlags.STATIC.isSet(playInternal.accessFlags) || playInternal.returnType != "V" ||
        playInternal.parameterTypes.map(Any::toString) != listOf(owner, STRING, "Z", "Z")
    ) {
        throw PatchException("$PATCH: $owner->${playInternal.name}, playInternal, isn't static void ($owner, String, boolean, boolean)")
    }
    if (playInternal.parameterRegisterNumber(1) > 15) {
        throw PatchException("$PATCH: $owner->${playInternal.name} keeps its start reason past v15")
    }

    val prepare = preparers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method holding \"$GROOT_PREPARE\", found ${preparers.size}")
    val groot = classDefBy(prepare.definingClass)
    if (AccessFlags.STATIC.isSet(prepare.accessFlags) || prepare.returnType != "V") {
        throw PatchException("$PATCH: ${groot.type}->${prepare.name}, IgGrootPlayer's prepare, isn't an instance method returning nothing")
    }

    val plays = groot.instanceMethods { it.parameterTypes.map(Any::toString) == listOf(STRING, "Z") && it.strings().containsAll(GROOT_PLAY) }
    val play = plays.singleOrNull()
        ?: throw PatchException("$PATCH: expected one play (String, boolean) in ${groot.type} holding $GROOT_PLAY, found ${plays.size}")

    // IgGrootPlayer's pause takes the reason and hands it on to the player underneath, and holds
    // no string of its own. Its other (String) method holds log lines.
    val pauses = groot.instanceMethods { method ->
        method.parameterTypes.map(Any::toString) == listOf(STRING) && method.strings().isEmpty() &&
            method.code().any { instruction ->
                val call = instruction.methodReference()
                instruction.opcode == Opcode.INVOKE_VIRTUAL && call != null && call.definingClass != groot.type &&
                    call.returnType == "V" && call.parameterTypes.map(Any::toString) == listOf(STRING)
            }
    }
    val pause = pauses.singleOrNull()
        ?: throw PatchException("$PATCH: expected one pause (String) in ${groot.type} handing its reason on, found ${pauses.size}")

    val grootField = playInternal.grootField(groot.type, play)

    val checker = checkers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method holding $AUTOPLAY_CHECKER, found ${checkers.size}")
    if (AccessFlags.STATIC.isSet(checker.accessFlags) || checker.returnType != "Z" || checker.parameterTypes.isNotEmpty()) {
        throw PatchException("$PATCH: ${checker.definingClass}->${checker.name}, the autoplay check, isn't an instance method answering a boolean")
    }
    if (checker.code().none { it.opcode == Opcode.RETURN }) {
        throw PatchException("$PATCH: ${checker.definingClass}->${checker.name}, the autoplay check, never returns")
    }

    val activity = classDefByOrNull(FRAGMENT_ACTIVITY)
        ?: throw PatchException("$PATCH: this build has no $FRAGMENT_ACTIVITY")
    val touch = activity.methods.singleOrNull {
        it.name == "dispatchTouchEvent" && it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf(MOTION_EVENT) &&
            it.implementation != null
    } ?: throw PatchException("$PATCH: $FRAGMENT_ACTIVITY has no dispatchTouchEvent of its own")

    return PlayerHooks(playInternal, grootField, findReelsLogger(owner), play, pause, prepare, checker, touch, findPlayButtonClick())
}

/** [player]'s field holding the video logger it was made with, and the class of the Reels viewer's logger. */
internal class ReelsLogger(val field: FieldReference, val type: String)

/**
 * The video logger the Reels viewer makes its players with. IgVideoPlayerImpl, [player], keeps the
 * logger it was made with in a final field, and the Reels viewer's logger is the one subclass of
 * that field's type whose constructor takes a [CLIPS_VIEWER_CONFIG] first and keeps it. A player
 * whose logger is one of those, or of a subclass, plays in the Reels viewer, which playInternal's
 * hook checks with an instance-of. A reel in the feed is the feed's player, with the feed's logger.
 */
internal fun BytecodePatchContext.findReelsLogger(player: String): ReelsLogger {
    val fields = classDefBy(player).fields
        .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.FINAL.isSet(it.accessFlags) && it.type.startsWith("L") }
        .groupBy { it.type }
    val loggers = Fingerprint(
        name = "<init>",
        filters = listOf(fieldAccess(type = CLIPS_VIEWER_CONFIG, opcode = Opcode.IPUT_OBJECT)),
        custom = { method, classDef -> method.parameterTypes.firstOrNull()?.toString() == CLIPS_VIEWER_CONFIG && classDef.superclass in fields },
    ).matchAllOrNull().orEmpty().map { it.originalMethod.definingClass }.distinct()
    val type = loggers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one class made with $CLIPS_VIEWER_CONFIG extending the type of a final field of $player, found ${loggers.size}: $loggers")
    val base = classDefBy(type).superclass!!
    val field = fields.getValue(base).singleOrNull()
        ?: throw PatchException("$PATCH: $player has more than one final field of $base, the Reels viewer's video logger's type")
    return ReelsLogger(field, type)
}

/**
 * The feed's Litho play button's click. The view-based button's binders hold [PLAY_BUTTON_BINDER],
 * and the one enum every binder takes is the button's state. The Litho button is the one class
 * holding that state and a Function0, the video's start, and its click is the one method reading that
 * Function0, an invoke(Object) taking the click event, which hands it to a static (Context, *,
 * UserSession, Function0) that asks about mobile data before it starts the video. The click must
 * read the start once and make that one call, with the start it read, so the hook after the call
 * is sure to follow the button's own start. The event must still be in its own register at that
 * call, where the hook reads it.
 */
internal fun BytecodePatchContext.findPlayButtonClick(): PlayButtonClick {
    val binders = mutableListOf<Method>()
    classesHolding(PLAY_BUTTON_BINDER).forEach { classDef -> classDef.methods.filterTo(binders) { PLAY_BUTTON_BINDER in it.strings() } }
    if (binders.isEmpty()) throw PatchException("$PATCH: no method holds \"$PLAY_BUTTON_BINDER\"")
    val shared = binders.map { binder -> binder.parameterTypes.map(Any::toString).toSet() }.reduce { all, next -> all intersect next }
    val states = shared.filter { classDefByOrNull(it)?.superclass == ENUM }
    val state = states.singleOrNull()
        ?: throw PatchException("$PATCH: the ${binders.size} play button binders share ${states.size} enum parameters, expected one, the button's state")

    val buttons = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        val types = classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }
        if (state in types && FUNCTION0 in types) buttons += classDef
    }
    val button = buttons.singleOrNull()
        ?: throw PatchException("$PATCH: expected one class holding a play button state $state and a Function0, found ${buttons.size}")
    val start = button.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == FUNCTION0 }.singleOrNull()
        ?: throw PatchException("$PATCH: ${button.type}, the Litho play button, holds more than one Function0")

    val clicks = mutableListOf<Method>()
    val reading = classesAccessing(button.type, start.name, Opcode.IGET_OBJECT).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in reading) return@classDefForEach
        classDef.methods.filterTo(clicks) { method ->
            method.code().any { instruction ->
                instruction.opcode == Opcode.IGET_OBJECT && instruction.fieldReference()?.let {
                    it.definingClass == button.type && it.name == start.name && it.type == FUNCTION0
                } == true
            }
        }
    }
    val click = clicks.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method reading ${button.type}->${start.name}, the play button's start, found ${clicks.size}")
    // 449 keeps the click in its own lambda's invoke(Object). 450's Redex merges lambdas into one
    // static (int case, Object lambda, Object event) method switching on the case.
    val static = AccessFlags.STATIC.isSet(click.accessFlags)
    val parameters = click.parameterTypes.map(Any::toString)
    if (click.returnType != OBJECT || parameters != if (static) listOf("I", OBJECT, OBJECT) else listOf(OBJECT)) {
        throw PatchException(
            "$PATCH: ${click.definingClass}->${click.name}, the play button's click, isn't an instance Object invoke(Object) " +
                "or a merged static Object (int, Object, Object)",
        )
    }
    val event = parameters.lastIndex
    val code = click.code()
    val where = "${click.definingClass}->${click.name}, the play button's click,"
    val reads = code.indices.filter { index ->
        code[index].opcode == Opcode.IGET_OBJECT && code[index].fieldReference()?.let { it.definingClass == button.type && it.name == start.name } == true
    }
    val read = reads.singleOrNull() ?: throw PatchException("$PATCH: $where reads its start ${reads.size} times, expected once")
    fun handsOver(index: Int): Boolean {
        val reference = code[index].methodReference()
        return code[index].opcode == Opcode.INVOKE_STATIC && reference != null && reference.returnType == "V" &&
            reference.parameterTypes.map(Any::toString).let { it.size == 4 && it[0] == CONTEXT && it[2] == USER_SESSION && it[3] == FUNCTION0 }
    }
    // A merged method's other cases make calls of their own, so the call is the first one after the read.
    val call = (read + 1 until code.size).firstOrNull { handsOver(it) }
        ?: throw PatchException("$PATCH: $where makes no static (Context, *, UserSession, Function0) call after reading its start")
    if (!static && code.indices.count { handsOver(it) } != 1) {
        throw PatchException("$PATCH: $where makes more than one static (Context, *, UserSession, Function0) call")
    }
    (read + 1 until call).firstOrNull { code[it] is OffsetInstruction || !code[it].opcode.canContinue() }?.let {
        throw PatchException("$PATCH: $where can leave the way from its start to the call at instruction $it")
    }
    val loaded = (code[read] as TwoRegisterInstruction).registerA
    if (code[call].argumentRegisters().lastOrNull() != loaded) {
        throw PatchException("$PATCH: $where doesn't hand the start it read, v$loaded, to ${code[call].methodReference()}")
    }
    (read + 1 until call).firstOrNull { index ->
        val set = code[index] as? OneRegisterInstruction
        set != null && code[index].opcode.setsRegister() &&
            (set.registerA == loaded || code[index].opcode.setsWideRegister() && set.registerA + 1 == loaded)
    }?.let { throw PatchException("$PATCH: $where writes over its start, v$loaded, at instruction $it, before it hands it over") }
    click.requireParameterIntact(PATCH, event, listOf(call))
    return PlayButtonClick(click, call, event)
}

private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/**
 * The field of its own player that [this], playInternal, reads the IgGrootPlayer of [grootType]
 * from before it calls [play] on it. Every such read must be of the same field, from the player
 * playInternal was handed, since the hook reads it first thing.
 */
private fun Method.grootField(grootType: String, play: Method): FieldReference {
    val code = code()
    val reads = code.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IGET_OBJECT && (instruction.fieldReference())?.let {
            it.definingClass == definingClass && it.type == grootType
        } == true
    }
    val player = parameterRegisterNumber(0)
    val fields = reads.map { it.value.fieldReference()!! }.distinctBy { "${it.definingClass}->${it.name}" }
    val field = fields.singleOrNull()
        ?: throw PatchException("$PATCH: $definingClass->$name reads ${fields.size} IgGrootPlayer fields of its player, expected one")
    if (reads.any { (it.value as TwoRegisterInstruction).registerB != player }) {
        throw PatchException("$PATCH: $definingClass->$name reads an IgGrootPlayer from something other than its player")
    }
    val plays = code.withIndex().filter { (_, instruction) ->
        instruction.methodReference()?.let {
            it.definingClass == grootType && it.name == play.name &&
                it.parameterTypes.map(Any::toString) == play.parameterTypes.map(Any::toString)
        } == true
    }
    if (plays.isEmpty()) throw PatchException("$PATCH: $definingClass->$name never plays its IgGrootPlayer")
    return field
}

private fun ClassDef.instanceMethods(filter: (Method) -> Boolean): List<Method> =
    methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.implementation != null && filter(it) }

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.strings(): Set<String> = code().mapNotNull { instruction ->
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
    else ((instruction as ReferenceInstruction).reference as StringReference).string
}.toSet()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
